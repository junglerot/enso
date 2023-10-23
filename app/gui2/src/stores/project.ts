import { useGuiConfig, type GuiConfig } from '@/providers/guiConfig'
import { ComputedValueRegistry } from '@/util/computedValueRegistry'
import { attachProvider } from '@/util/crdt'
import { AsyncQueue, rpcWithRetries as lsRpcWithRetries } from '@/util/net'
import { isSome, type Opt } from '@/util/opt'
import { tryQualifiedName } from '@/util/qualifiedName'
import { VisualizationDataRegistry } from '@/util/visualizationDataRegistry'
import { Client, RequestManager, WebSocketTransport } from '@open-rpc/client-js'
import { computedAsync } from '@vueuse/core'
import { namespace } from 'd3'
import * as array from 'lib0/array'
import * as object from 'lib0/object'
import { ObservableV2 } from 'lib0/observable'
import * as random from 'lib0/random'
import { defineStore } from 'pinia'
import { mainModule } from 'process'
import { DataServer } from 'shared/dataServer'
import { LanguageServer } from 'shared/languageServer'
import type {
  ContentRoot,
  ContextId,
  Diagnostic,
  ExecutionEnvironment,
  ExplicitCall,
  ExpressionId,
  ExpressionUpdate,
  StackItem,
  VisualizationConfiguration,
} from 'shared/languageServerTypes'
import { WebsocketClient } from 'shared/websocket'
import { DistributedProject, type ExprId, type Uuid } from 'shared/yjsModel'
import {
  computed,
  markRaw,
  ref,
  shallowRef,
  watch,
  watchEffect,
  type ShallowRef,
  type WatchSource,
} from 'vue'
import { Awareness } from 'y-protocols/awareness'
import * as Y from 'yjs'

interface LsUrls {
  rpcUrl: string
  dataUrl: string
}

function resolveLsUrl(config: GuiConfig): LsUrls {
  const engine = config.engine
  if (engine == null) throw new Error('Missing engine configuration')

  if (engine.rpcUrl != null && engine.dataUrl != null) {
    return {
      rpcUrl: engine.rpcUrl,
      dataUrl: engine.dataUrl,
    }
  }

  throw new Error('Incomplete engine configuration')
}

async function initializeLsRpcConnection(
  clientId: Uuid,
  url: string,
): Promise<{
  connection: LanguageServer
  contentRoots: ContentRoot[]
}> {
  const transport = new WebSocketTransport(url)
  const requestManager = new RequestManager([transport])
  const client = new Client(requestManager)
  const connection = new LanguageServer(client)

  const initialization = await lsRpcWithRetries(() => connection.initProtocolConnection(clientId), {
    onBeforeRetry: (error, _, delay) => {
      console.warn(
        `Failed to initialize language server connection, retrying after ${delay}ms...\n`,
        error,
      )
    },
  })
  const contentRoots = initialization.contentRoots
  return { connection, contentRoots }
}

async function initializeDataConnection(clientId: Uuid, url: string) {
  const client = new WebsocketClient(url, { binaryType: 'arraybuffer', sendPings: false })
  const connection = new DataServer(client)
  await connection.initialize(clientId)
  return connection
}

export type NodeVisualizationConfiguration = Omit<
  VisualizationConfiguration,
  'executionContextId'
> & {
  expressionId: ExprId
}

interface ExecutionContextState {
  lsRpc: LanguageServer
  created: boolean
  visualizations: Map<Uuid, NodeVisualizationConfiguration>
  stack: StackItem[]
}

function visualizationConfigEqual(
  a: NodeVisualizationConfiguration,
  b: NodeVisualizationConfiguration,
): boolean {
  return (
    a === b ||
    (a.visualizationModule === b.visualizationModule &&
      (a.positionalArgumentsExpressions === b.positionalArgumentsExpressions ||
        (Array.isArray(a.positionalArgumentsExpressions) &&
          Array.isArray(b.positionalArgumentsExpressions) &&
          array.equalFlat(a.positionalArgumentsExpressions, b.positionalArgumentsExpressions))) &&
      (a.expression === b.expression ||
        (typeof a.expression === 'object' &&
          typeof b.expression === 'object' &&
          object.equalFlat(a.expression, b.expression))))
  )
}

type EntryPoint = Omit<ExplicitCall, 'type'>

type ExecutionContextNotification = {
  'expressionUpdates'(updates: ExpressionUpdate[]): void
  'visualizationEvaluationFailed'(
    visualizationId: Uuid,
    expressionId: ExpressionId,
    message: string,
    diagnostic: Diagnostic | undefined,
  ): void
  'executionFailed'(message: string): void
  'executionComplete'(): void
  'executionStatus'(diagnostics: Diagnostic[]): void
  'visualizationsConfigured'(configs: Set<Uuid>): void
}

/**
 * Execution Context
 *
 * This class represent an execution context created in the Language Server. It creates
 * it and pushes the initial frame upon construction.
 *
 * It hides the asynchronous nature of the language server. Each call is scheduled and
 * run only when the previous call is done.
 */
export class ExecutionContext extends ObservableV2<ExecutionContextNotification> {
  id: ContextId = random.uuidv4() as ContextId
  queue: AsyncQueue<ExecutionContextState>
  taskRunning = false
  visSyncScheduled = false
  visualizationConfigs: Map<Uuid, NodeVisualizationConfiguration> = new Map()
  abortCtl = new AbortController()

  constructor(lsRpc: Promise<LanguageServer>, entryPoint: EntryPoint) {
    super()

    this.abortCtl.signal.addEventListener('abort', () => {
      this.queue.clear()
    })

    this.queue = new AsyncQueue(
      lsRpc.then((lsRpc) => ({
        lsRpc,
        created: false,
        visualizations: new Map(),
        stack: [],
      })),
    )
    this.registerHandlers()
    this.create()
    this.pushItem({ type: 'ExplicitCall', ...entryPoint })
    this.recompute()
  }

  private withBackoff<T>(f: () => Promise<T>, message: string): Promise<T> {
    return lsRpcWithRetries(f, {
      onBeforeRetry: (error, _, delay) => {
        if (this.abortCtl.signal.aborted) return false
        console.warn(
          `${message}: ${error.payload.cause.message}. Retrying after ${delay}ms...\n`,
          error,
        )
      },
    })
  }

  private syncVisualizations() {
    if (this.visSyncScheduled) return
    this.visSyncScheduled = true
    this.queue.pushTask(async (state) => {
      this.visSyncScheduled = false
      if (!state.created) return state
      const promises: Promise<void>[] = []

      const attach = (id: Uuid, config: NodeVisualizationConfiguration) => {
        return this.withBackoff(
          () =>
            state.lsRpc.attachVisualization(id, config.expressionId, {
              executionContextId: this.id,
              expression: config.expression,
              visualizationModule: config.visualizationModule,
              ...(config.positionalArgumentsExpressions
                ? { positionalArgumentsExpressions: config.positionalArgumentsExpressions }
                : {}),
            }),
          'Failed to attach visualization',
        ).then(() => {
          state.visualizations.set(id, config)
        })
      }

      const modify = (id: Uuid, config: NodeVisualizationConfiguration) => {
        return this.withBackoff(
          () =>
            state.lsRpc.modifyVisualization(id, {
              executionContextId: this.id,
              expression: config.expression,
              visualizationModule: config.visualizationModule,
              ...(config.positionalArgumentsExpressions
                ? { positionalArgumentsExpressions: config.positionalArgumentsExpressions }
                : {}),
            }),
          'Failed to modify visualization',
        ).then(() => {
          state.visualizations.set(id, config)
        })
      }

      const detach = (id: Uuid, config: NodeVisualizationConfiguration) => {
        return this.withBackoff(
          () => state.lsRpc.detachVisualization(id, config.expressionId, this.id),
          'Failed to detach visualization',
        ).then(() => {
          state.visualizations.delete(id)
        })
      }

      // Attach new and update existing visualizations.
      for (const [id, config] of this.visualizationConfigs) {
        const previousConfig = state.visualizations.get(id)
        if (previousConfig == null) {
          promises.push(attach(id, config))
        } else if (!visualizationConfigEqual(previousConfig, config)) {
          if (previousConfig.expressionId === config.expressionId) {
            promises.push(modify(id, config))
          } else {
            promises.push(detach(id, previousConfig).then(() => attach(id, config)))
          }
        }
      }

      // Detach removed visualizations.
      for (const [id, config] of state.visualizations) {
        if (this.visualizationConfigs.get(id) == undefined) {
          promises.push(detach(id, config))
        }
      }
      const settled = await Promise.allSettled(promises)

      // Emit errors for failed requests.
      const errors = settled
        .map((result) => (result.status === 'rejected' ? result.reason : null))
        .filter(isSome)
      if (errors.length > 0) {
        console.error('Failed to synchronize visualizations:', errors)
      }

      this.emit('visualizationsConfigured', [new Set(this.visualizationConfigs.keys())])

      // State object was updated in-place in each successful promise.
      return state
    })
  }

  private pushItem(item: StackItem) {
    this.queue.pushTask(async (state) => {
      if (!state.created) return state
      await this.withBackoff(
        () => state.lsRpc.pushExecutionContextItem(this.id, item),
        'Failed to push item to execution context stack',
      )
      state.stack.push(item)
      return state
    })
  }

  push(expressionId: ExpressionId) {
    this.pushItem({ type: 'LocalCall', expressionId })
  }

  pop() {
    this.queue.pushTask(async (state) => {
      if (!state.created) return state
      if (state.stack.length === 0) {
        throw new Error('Cannot pop from empty execution context stack')
      }
      await this.withBackoff(
        () => state.lsRpc.popExecutionContextItem(this.id),
        'Failed to pop item from execution context stack',
      )
      state.stack.pop()
      return state
    })
  }

  async setVisualization(id: Uuid, configuration: Opt<NodeVisualizationConfiguration>) {
    if (configuration == null) {
      this.visualizationConfigs.delete(id)
    } else {
      this.visualizationConfigs.set(id, configuration)
    }
    this.syncVisualizations()
  }

  private create() {
    this.queue.pushTask(async (state) => {
      if (state.created) return state
      return this.withBackoff(async () => {
        const result = await state.lsRpc.createExecutionContext(this.id)
        if (result.contextId !== this.id) {
          throw new Error('Unexpected Context ID returned by the language server.')
        }
        return { ...state, created: true }
      }, 'Failed to create execution context')
    })
    this.abortCtl.signal.addEventListener('abort', () => {
      this.queue.pushTask(async (state) => {
        if (!state.created) return state
        await state.lsRpc.destroyExecutionContext(this.id)
        return { ...state, created: false }
      })
    })
  }

  private registerHandlers() {
    this.queue.pushTask(async (state) => {
      const expressionUpdates = state.lsRpc.on('executionContext/expressionUpdates', (event) => {
        if (event.contextId == this.id) this.emit('expressionUpdates', [event.updates])
      })
      const executionFailed = state.lsRpc.on('executionContext/executionFailed', (event) => {
        if (event.contextId == this.id) this.emit('executionFailed', [event.message])
      })
      const executionComplete = state.lsRpc.on('executionContext/executionComplete', (event) => {
        if (event.contextId == this.id) this.emit('executionComplete', [])
      })
      const executionStatus = state.lsRpc.on('executionContext/executionStatus', (event) => {
        if (event.contextId == this.id) this.emit('executionStatus', [event.diagnostics])
      })
      const visualizationEvaluationFailed = state.lsRpc.on(
        'executionContext/visualizationEvaluationFailed',
        (event) => {
          if (event.contextId == this.id)
            this.emit('visualizationEvaluationFailed', [
              event.visualizationId,
              event.expressionId,
              event.message,
              event.diagnostic,
            ])
        },
      )
      this.abortCtl.signal.addEventListener('abort', () => {
        state.lsRpc.off('executionContext/expressionUpdates', expressionUpdates)
        state.lsRpc.off('executionContext/executionFailed', executionFailed)
        state.lsRpc.off('executionContext/executionComplete', executionComplete)
        state.lsRpc.off('executionContext/executionStatus', executionStatus)
        state.lsRpc.off(
          'executionContext/visualizationEvaluationFailed',
          visualizationEvaluationFailed,
        )
      })
      return state
    })
  }

  recompute(expressionIds: 'all' | ExprId[] = 'all', executionEnvironment?: ExecutionEnvironment) {
    this.queue.pushTask(async (state) => {
      if (!state.created) return state
      await state.lsRpc.recomputeExecutionContext(this.id, expressionIds, executionEnvironment)
      return state
    })
  }

  destroy() {
    this.abortCtl.abort()
  }
}

/**
 * The project store synchronizes and holds the open project-related data. The synchronization is
 * performed using a CRDT data types from Yjs. Once the data is synchronized with a "LS bridge"
 * client, it is submitted to the language server as a document update.
 */
export const useProjectStore = defineStore('project', () => {
  const observedFileName = ref<string>()

  const doc = new Y.Doc()
  const awareness = new Awareness(doc)

  const config = useGuiConfig()
  const projectName = config.value.startup?.project
  if (projectName == null) throw new Error('Missing project name.')

  const clientId = random.uuidv4() as Uuid
  const lsUrls = resolveLsUrl(config.value)
  const initializedConnection = initializeLsRpcConnection(clientId, lsUrls.rpcUrl)
  const lsRpcConnection = initializedConnection.then(({ connection }) => connection)
  const contentRoots = initializedConnection.then(({ contentRoots }) => contentRoots)
  const dataConnection = initializeDataConnection(clientId, lsUrls.dataUrl)

  const name = computed(() => config.value.startup?.project)
  const namespace = computed(() => config.value.engine?.namespace)
  const fullName = computed(() => {
    const ns = namespace.value
    if (ns == null) {
      console.warn(
        'Unknown project\'s namespace. Assuming "local", however it likely won\'t work in cloud',
      )
    }
    const projectName = name.value
    if (projectName == null) {
      console.error(
        "Unknown project's name. Cannot specify opened module's qualified path; many things may not work",
      )
      return null
    }
    return `${ns ?? 'local'}.${projectName}`
  })
  const modulePath = computed(() => {
    const filePath = observedFileName.value
    console.log(filePath)
    if (filePath == null) return undefined
    const withoutFileExt = filePath.replace(/\.enso$/, '')
    const withDotSeparators = withoutFileExt.replace(/\//g, '.')
    return tryQualifiedName(`${fullName.value}.${withDotSeparators}`)
  })

  watchEffect((onCleanup) => {
    // For now, let's assume that the websocket server is running on the same host as the web server.
    // Eventually, we can make this configurable, or even runtime variable.
    const socketUrl = new URL(location.origin)
    socketUrl.protocol = location.protocol.replace(/^http/, 'ws')
    socketUrl.pathname = '/project'
    const provider = attachProvider(socketUrl.href, 'index', { ls: lsUrls.rpcUrl }, doc, awareness)
    onCleanup(() => {
      provider.dispose()
    })
  })

  const projectModel = new DistributedProject(doc)
  const moduleDocGuid = ref<string>()

  function currentDocGuid() {
    const name = observedFileName.value
    if (name == null) return
    return projectModel.modules.get(name)?.guid
  }
  function tryReadDocGuid() {
    const guid = currentDocGuid()
    if (guid === moduleDocGuid.value) return
    moduleDocGuid.value = guid
  }

  projectModel.modules.observe((_) => tryReadDocGuid())
  watchEffect(tryReadDocGuid)

  const module = computedAsync(async () => {
    const guid = moduleDocGuid.value
    if (guid == null) return null
    const moduleName = projectModel.findModuleByDocId(guid)
    if (moduleName == null) return null
    const mod = await projectModel.openModule(moduleName)
    mod?.undoManager.addTrackedOrigin('local')
    return mod
  })

  function createExecutionContextForMain(): ExecutionContext {
    const projectName = fullName.value
    const mainModule = `${projectName}.Main`
    const entryPoint = { module: mainModule, definedOnType: mainModule, name: 'main' }
    return new ExecutionContext(lsRpcConnection, {
      methodPointer: entryPoint,
      positionalArgumentsExpressions: [],
    })
  }

  const executionContext = createExecutionContextForMain()
  const computedValueRegistry = new ComputedValueRegistry(executionContext)
  const visualizationDataRegistry = new VisualizationDataRegistry(executionContext, dataConnection)

  function useVisualizationData(
    configuration: WatchSource<Opt<NodeVisualizationConfiguration>>,
  ): ShallowRef<{} | undefined> {
    const id = random.uuidv4() as Uuid

    watch(
      configuration,
      async (config, _, onCleanup) => {
        executionContext.setVisualization(id, config)
        onCleanup(() => {
          executionContext.setVisualization(id, null)
        })
      },
      { immediate: true },
    )

    return shallowRef(
      computed(() => {
        const json = visualizationDataRegistry.getRawData(id)
        return json != null ? JSON.parse(json) : undefined
      }),
    )
  }

  function stopCapturingUndo() {
    module.value?.undoManager.stopCapturing()
  }

  return {
    setObservedFileName(name: string) {
      observedFileName.value = name
    },
    name: projectName,
    executionContext,
    module,
    modulePath,
    contentRoots,
    awareness,
    computedValueRegistry,
    lsRpcConnection: markRaw(lsRpcConnection),
    dataConnection: markRaw(dataConnection),
    useVisualizationData,
    stopCapturingUndo,
  }
})
