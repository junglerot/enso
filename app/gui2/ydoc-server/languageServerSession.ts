import { Client, RequestManager, WebSocketTransport } from '@open-rpc/client-js'
import * as json from 'lib0/json'
import * as map from 'lib0/map'
import { ObservableV2 } from 'lib0/observable'
import * as random from 'lib0/random'
import * as Y from 'yjs'
import {
  Ast,
  MutableModule,
  parseBlockWithSpans,
  setExternalIds,
  spanMapToIdMap,
} from '../shared/ast'
import { print } from '../shared/ast/parse'
import { EnsoFileParts, combineFileParts, splitFileContents } from '../shared/ensoFile'
import { LanguageServer, computeTextChecksum } from '../shared/languageServer'
import { Checksum, FileEdit, Path, TextEdit, response } from '../shared/languageServerTypes'
import { exponentialBackoff, printingCallbacks } from '../shared/retry'
import {
  DistributedProject,
  ExternalId,
  IdMap,
  ModuleDoc,
  visMetadataEquals,
  type Uuid,
} from '../shared/yjsModel'
import {
  applyDiffAsTextEdits,
  applyDocumentUpdates,
  prettyPrintDiff,
  translateVisualizationFromFile,
} from './edits'
import * as fileFormat from './fileFormat'
import { deserializeIdMap, serializeIdMap } from './serialization'
import { WSSharedDoc } from './ydoc'

const SOURCE_DIR = 'src'
const EXTENSION = '.enso'

const DEBUG_LOG_SYNC = false

function createOpenRPCClient(url: string) {
  const transport = new WebSocketTransport(url)
  const requestManager = new RequestManager([transport])
  transport.connection.on('error', (error) =>
    console.error('Language Server transport error:', error),
  )
  return new Client(requestManager)
}

export class LanguageServerSession {
  clientId: Uuid
  indexDoc: WSSharedDoc
  docs: Map<string, WSSharedDoc>
  retainCount: number
  url: string
  client: Client
  ls: LanguageServer
  connection: response.InitProtocolConnection | undefined
  model: DistributedProject
  projectRootId: Uuid | null
  authoritativeModules: Map<string, ModulePersistence>

  constructor(url: string) {
    this.clientId = random.uuidv4() as Uuid
    this.docs = new Map()
    this.retainCount = 0
    this.url = url
    console.log('new session with', url)
    this.indexDoc = new WSSharedDoc()
    this.docs.set('index', this.indexDoc)
    this.model = new DistributedProject(this.indexDoc.doc)
    this.projectRootId = null
    this.authoritativeModules = new Map()

    this.indexDoc.doc.on('subdocs', (subdocs: { loaded: Set<Y.Doc> }) => {
      for (const doc of subdocs.loaded) {
        const name = this.model.findModuleByDocId(doc.guid)
        if (!name) continue
        const persistence = this.authoritativeModules.get(name)
        if (!persistence) continue
      }
    })
    const { client, ls } = this.setupClient()
    this.client = client
    this.ls = ls
  }

  static sessions = new Map<string, LanguageServerSession>()
  static get(url: string): LanguageServerSession {
    const session = map.setIfUndefined(
      LanguageServerSession.sessions,
      url,
      () => new LanguageServerSession(url),
    )
    session.retain()
    return session
  }

  private restartClient() {
    this.client.close()
    this.ls.destroy()
    this.connection = undefined
    this.setupClient()
  }

  private setupClient() {
    this.client = createOpenRPCClient(this.url)
    this.ls = new LanguageServer(this.client)
    this.ls.on('file/event', async (event) => {
      if (DEBUG_LOG_SYNC) {
        console.log('file/event', event)
      }
      const path = event.path.segments.join('/')
      try {
        switch (event.kind) {
          case 'Added': {
            if (isSourceFile(event.path)) {
              const fileInfo = await this.ls.fileInfo(event.path)
              if (fileInfo.attributes.kind.type == 'File') {
                await exponentialBackoff(
                  () => this.getModuleModel(event.path).open(),
                  printingCallbacks(`opened new file '${path}'`, `open new file '${path}'`),
                )
              }
            }
            break
          }
          case 'Modified': {
            await exponentialBackoff(
              async () => this.tryGetExistingModuleModel(event.path)?.reload(),
              printingCallbacks(`reloaded file '${path}'`, `reload file '${path}'`),
            )
            break
          }
        }
      } catch {
        this.restartClient()
      }
    })
    this.ls.on('text/fileModifiedOnDisk', async (event) => {
      const path = event.path.segments.join('/')
      try {
        await exponentialBackoff(
          async () => this.tryGetExistingModuleModel(event.path)?.reload(),
          printingCallbacks(`reloaded file '${path}'`, `reload file '${path}'`),
        )
      } catch {
        this.restartClient()
      }
    })
    exponentialBackoff(
      () => this.readInitialState(),
      printingCallbacks('read initial state', 'read initial state'),
    ).catch((error) => {
      console.error('Could not read initial state.')
      console.error(error)
      exponentialBackoff(
        async () => this.restartClient(),
        printingCallbacks('restarted RPC client', 'restart RPC client'),
      )
    })
    return { client: this.client, ls: this.ls }
  }

  private assertProjectRoot(): asserts this is { projectRootId: Uuid } {
    if (this.projectRootId == null) throw new Error('Missing project root')
  }

  private async readInitialState() {
    let moduleOpenPromises: Promise<void>[] = []
    try {
      const connection = this.connection ?? (await this.ls.initProtocolConnection(this.clientId))
      this.connection = connection
      const projectRoot = connection.contentRoots.find((root) => root.type === 'Project')
      if (!projectRoot) throw new Error('Missing project root')
      this.projectRootId = projectRoot.id
      await this.ls.acquireReceivesTreeUpdates({ rootId: this.projectRootId, segments: [] })
      const files = await this.scanSourceFiles()
      moduleOpenPromises = this.indexDoc.doc.transact(
        () =>
          files.map((file) => this.getModuleModel(pushPathSegment(file.path, file.name)).open()),
        this,
      )
      await Promise.all(moduleOpenPromises)
    } catch (error) {
      console.error('LS initialization failed.')
      throw error
    }
    console.log('LS connection initialized.')
  }

  async scanSourceFiles() {
    this.assertProjectRoot()
    const sourceDir: Path = { rootId: this.projectRootId, segments: [SOURCE_DIR] }
    const srcModules = await this.ls.listFiles(sourceDir)
    return srcModules.paths.filter((file) => file.type === 'File' && file.name.endsWith(EXTENSION))
  }

  tryGetExistingModuleModel(path: Path): ModulePersistence | undefined {
    const name = pathToModuleName(path)
    return this.authoritativeModules.get(name)
  }

  getModuleModel(path: Path): ModulePersistence {
    const name = pathToModuleName(path)
    return map.setIfUndefined(this.authoritativeModules, name, () => {
      const wsDoc = new WSSharedDoc()
      this.docs.set(wsDoc.doc.guid, wsDoc)
      this.model.createUnloadedModule(name, wsDoc.doc)
      const mod = new ModulePersistence(this.ls, path, wsDoc.doc)
      mod.once('removed', () => {
        const index = this.model.findModuleByDocId(wsDoc.doc.guid)
        this.docs.delete(wsDoc.doc.guid)
        this.authoritativeModules.delete(name)
        if (index != null) this.model.deleteModule(index)
      })
      return mod
    })
  }

  retain() {
    this.retainCount += 1
  }

  async release(): Promise<void> {
    this.retainCount -= 1
    if (this.retainCount !== 0) return
    const modules = this.authoritativeModules.values()
    const moduleDisposePromises = Array.from(modules, (mod) => mod.dispose())
    this.authoritativeModules.clear()
    this.model.doc.destroy()
    this.ls.dispose()
    LanguageServerSession.sessions.delete(this.url)
    await Promise.all(moduleDisposePromises)
  }

  getYDoc(guid: string): WSSharedDoc | undefined {
    return this.docs.get(guid)
  }
}

function isSourceFile(path: Path): boolean {
  return (
    path.segments[0] === SOURCE_DIR && path.segments[path.segments.length - 1].endsWith(EXTENSION)
  )
}

function pathToModuleName(path: Path): string {
  if (path.segments[0] === SOURCE_DIR) return path.segments.slice(1).join('/')
  else return '//' + path.segments.join('/')
}

function pushPathSegment(path: Path, segment: string): Path {
  return { rootId: path.rootId, segments: [...path.segments, segment] }
}

enum LsSyncState {
  Closed,
  Opening,
  Synchronized,
  WritingFile,
  WriteError,
  Reloading,
  Closing,
  Disposed,
}

enum LsAction {
  Open,
  Close,
  Reload,
}

class ModulePersistence extends ObservableV2<{ removed: () => void }> {
  ls: LanguageServer
  path: Path
  doc: ModuleDoc = new ModuleDoc(new Y.Doc())
  readonly state: LsSyncState = LsSyncState.Closed
  readonly lastAction = Promise.resolve()
  updateToApply: Uint8Array | null = null
  syncedCode: string | null = null
  syncedIdMap: string | null = null
  syncedMetaJson: string | null = null
  syncedContent: string | null = null
  syncedVersion: Checksum | null = null
  syncedMeta: fileFormat.Metadata = fileFormat.tryParseMetadataOrFallback(null)
  queuedAction: LsAction | null = null
  cleanup = () => {}

  constructor(ls: LanguageServer, path: Path, sharedDoc: Y.Doc) {
    super()
    this.ls = ls
    this.path = path

    const onRemoteUpdate = this.queueRemoteUpdate.bind(this)
    const onLocalUpdate = (update: Uint8Array, origin: unknown) => {
      if (origin === 'file') Y.applyUpdate(sharedDoc, update, this)
    }
    const onFileModified = this.handleFileModified.bind(this)
    const onFileRemoved = this.handleFileRemoved.bind(this)
    this.doc.ydoc.on('update', onLocalUpdate)
    sharedDoc.on('update', onRemoteUpdate)
    this.ls.on('text/fileModifiedOnDisk', onFileModified)
    this.ls.on('file/rootRemoved', onFileRemoved)
    this.cleanup = () => {
      this.doc.ydoc.off('update', onLocalUpdate)
      sharedDoc.off('update', onRemoteUpdate)
      this.ls.off('text/fileModifiedOnDisk', onFileModified)
      this.ls.off('file/rootRemoved', onFileRemoved)
    }
  }

  private inState(...states: LsSyncState[]): boolean {
    return states.includes(this.state)
  }

  private setState(state: LsSyncState) {
    if (this.state !== LsSyncState.Disposed) {
      if (DEBUG_LOG_SYNC) {
        console.debug('State change:', LsSyncState[this.state], '->', LsSyncState[state])
      }
      // This is SAFE. `this.state` is only `readonly` to ensure that this is the only place
      // where it is mutated.
      // @ts-expect-error
      this.state = state
      if (state === LsSyncState.Synchronized) this.trySyncRemoveUpdates()
    } else {
      throw new Error('LsSync disposed')
    }
  }

  private setLastAction<T>(lastAction: Promise<T>) {
    // This is SAFE. `this.lastAction` is only `readonly` to ensure that this is the only place
    // where it is mutated.
    // @ts-expect-error
    this.lastAction = lastAction.then(
      () => {},
      () => {},
    )
    return lastAction
  }

  /** Set the current state to the given state while the callback is running.
   * Set the current state back to {@link LsSyncState.Synchronized} when the callback finishes. */
  private async withState(state: LsSyncState, callback: () => void | Promise<void>) {
    this.setState(state)
    await callback()
    this.setState(LsSyncState.Synchronized)
  }

  async open() {
    this.queuedAction = LsAction.Open
    switch (this.state) {
      case LsSyncState.Disposed:
      case LsSyncState.WritingFile:
      case LsSyncState.Synchronized:
      case LsSyncState.WriteError:
      case LsSyncState.Reloading: {
        return
      }
      case LsSyncState.Closing: {
        await this.lastAction
        if (this.queuedAction === LsAction.Open) await this.open()
        return
      }
      case LsSyncState.Opening: {
        await this.lastAction
        return
      }
      case LsSyncState.Closed: {
        await this.withState(LsSyncState.Opening, async () => {
          const promise = this.ls.openTextFile(this.path)
          this.setLastAction(promise.catch(() => this.setState(LsSyncState.Closed)))
          const result = await promise
          if (!result.writeCapability) {
            console.error('Could not acquire write capability for module:', this.path)
            throw new Error(
              `Could not acquire write capability for module '${this.path.segments.join('/')}'`,
            )
          }
          this.syncFileContents(result.content, result.currentVersion)
        })
        return
      }
      default: {
        this.state satisfies never
        return
      }
    }
  }

  handleFileRemoved() {
    if (this.inState(LsSyncState.Closed)) return
    this.close()
  }

  handleFileModified() {
    if (this.inState(LsSyncState.Closed)) return
  }

  queueRemoteUpdate(update: Uint8Array, origin: unknown) {
    if (origin === this) return
    if (this.updateToApply != null) {
      this.updateToApply = Y.mergeUpdates([this.updateToApply, update])
    } else {
      this.updateToApply = update
    }
    this.trySyncRemoveUpdates()
  }

  trySyncRemoveUpdates() {
    if (this.updateToApply == null) return
    // apply updates to the ls-representation doc if we are already in sync with the LS.
    if (!this.inState(LsSyncState.Synchronized)) return
    const update = this.updateToApply
    this.updateToApply = null

    const syncModule = new MutableModule(this.doc.ydoc)
    const moduleUpdate = syncModule.applyUpdate(update, 'remote')
    if (moduleUpdate && this.syncedContent) {
      const synced = splitFileContents(this.syncedContent)
      const { newCode, newIdMap, newMetadata } = applyDocumentUpdates(
        this.doc,
        synced,
        moduleUpdate,
      )
      this.sendLsUpdate(synced, newCode, newIdMap, newMetadata)
    }
  }

  private sendLsUpdate(
    synced: EnsoFileParts,
    newCode: string | undefined,
    newIdMap: IdMap | undefined,
    newMetadata: fileFormat.IdeMetadata['node'] | undefined,
  ) {
    if (this.syncedContent == null || this.syncedVersion == null) return

    const code = newCode ?? synced.code
    const newMetadataJson =
      newMetadata &&
      json.stringify({ ...this.syncedMeta, ide: { ...this.syncedMeta.ide, node: newMetadata } })
    const newIdMapJson = newIdMap && serializeIdMap(newIdMap)
    const newContent = combineFileParts({
      code,
      idMapJson: newIdMapJson ?? synced.idMapJson ?? '[]',
      metadataJson: newMetadataJson ?? synced.metadataJson ?? '{}',
    })

    const edits: TextEdit[] = []
    if (newCode) edits.push(...applyDiffAsTextEdits(0, synced.code, newCode))
    if (newIdMap || newMetadata) {
      const oldMetaContent = this.syncedContent.slice(synced.code.length)
      const metaContent = newContent.slice(code.length)
      const metaStartLine = (code.match(/\n/g) ?? []).length
      edits.push(...applyDiffAsTextEdits(metaStartLine, oldMetaContent, metaContent))
    }

    const newVersion = computeTextChecksum(newContent)

    if (DEBUG_LOG_SYNC) {
      console.debug(' === changes === ')
      console.debug('number of edits:', edits.length)
      if (edits.length > 0) {
        console.debug('version:', this.syncedVersion, '->', newVersion)
        console.debug('Content diff:')
        console.debug(prettyPrintDiff(this.syncedContent, newContent))
      }
      console.debug(' =============== ')
    }

    this.setState(LsSyncState.WritingFile)

    const execute = newCode != null || newIdMap != null
    const edit: FileEdit = { path: this.path, edits, oldVersion: this.syncedVersion, newVersion }
    const apply = this.ls.applyEdit(edit, execute)
    const promise = apply.then(
      () => {
        this.syncedContent = newContent
        this.syncedVersion = newVersion
        if (newMetadata) this.syncedMeta.ide.node = newMetadata
        if (newCode) this.syncedCode = newCode
        if (newIdMapJson) this.syncedIdMap = newIdMapJson
        if (newMetadataJson) this.syncedMetaJson = newMetadataJson
        this.setState(LsSyncState.Synchronized)
      },
      (error) => {
        console.error('Could not apply edit:', error)
        // Try to recover by reloading the file.
        // Drop the attempted updates, since applying them have failed.
        this.setState(LsSyncState.WriteError)
        this.syncedContent = null
        this.syncedVersion = null
        this.syncedCode = null
        this.syncedIdMap = null
        this.syncedMetaJson = null
        return this.reload()
      },
    )
    this.setLastAction(promise)
    return promise
  }

  private syncFileContents(content: string, version: Checksum) {
    const contentsReceived = splitFileContents(content)
    let unsyncedIdMap: IdMap | undefined
    this.doc.ydoc.transact(() => {
      const { code, idMapJson, metadataJson } = contentsReceived
      const metadata = fileFormat.tryParseMetadataOrFallback(metadataJson)
      const nodeMeta = Object.entries(metadata.ide.node)

      let parsedSpans
      const syncModule = new MutableModule(this.doc.ydoc)
      if (code !== this.syncedCode) {
        const { root, spans } = parseBlockWithSpans(code, syncModule)
        syncModule.syncRoot(root)
        parsedSpans = spans
      }
      const astRoot = syncModule.root()
      if (!astRoot) return
      if ((code !== this.syncedCode || idMapJson !== this.syncedIdMap) && idMapJson) {
        const idMap = deserializeIdMap(idMapJson)
        const spans = parsedSpans ?? print(astRoot).info
        const newExternalIds = setExternalIds(syncModule, spans, idMap)
        if (newExternalIds !== 0) {
          if (code !== this.syncedCode) {
            unsyncedIdMap = spanMapToIdMap(spans)
          } else {
            console.warn(
              `The LS sent an IdMap-only edit that is missing ${newExternalIds} of our expected ASTs.`,
            )
          }
        }
      }
      if (
        (code !== this.syncedCode ||
          idMapJson !== this.syncedIdMap ||
          metadataJson !== this.syncedMetaJson) &&
        nodeMeta.length !== 0
      ) {
        const externalIdToAst = new Map<ExternalId, Ast>()
        astRoot.visitRecursiveAst((ast) => {
          if (!externalIdToAst.has(ast.externalId)) externalIdToAst.set(ast.externalId, ast)
        })
        const missing = new Set<string>()
        for (const [id, meta] of nodeMeta) {
          if (typeof id !== 'string') continue
          const ast = externalIdToAst.get(id as ExternalId)
          if (!ast) {
            missing.add(id)
            continue
          }
          const metadata = syncModule.getVersion(ast).mutableNodeMetadata()
          const oldPos = metadata.get('position')
          const newPos = { x: meta.position.vector[0], y: -meta.position.vector[1] }
          if (oldPos?.x !== newPos.x || oldPos?.y !== newPos.y) metadata.set('position', newPos)
          const oldVis = metadata.get('visualization')
          const newVis = meta.visualization && translateVisualizationFromFile(meta.visualization)
          if (!visMetadataEquals(newVis, oldVis)) metadata.set('visualization', newVis)
        }
      }

      this.syncedCode = code
      this.syncedIdMap = unsyncedIdMap ? null : idMapJson
      this.syncedContent = content
      this.syncedVersion = version
      this.syncedMeta = metadata
      this.syncedMetaJson = metadataJson
    }, 'file')
    if (unsyncedIdMap) this.sendLsUpdate(contentsReceived, undefined, unsyncedIdMap, undefined)
  }

  async close() {
    this.queuedAction = LsAction.Close
    switch (this.state) {
      case LsSyncState.Disposed:
      case LsSyncState.Closed: {
        return
      }
      case LsSyncState.Closing: {
        await this.lastAction
        return
      }
      case LsSyncState.Opening:
      case LsSyncState.WritingFile:
      case LsSyncState.Reloading: {
        await this.lastAction
        if (this.queuedAction === LsAction.Close) {
          await this.close()
        }
        return
      }
      case LsSyncState.WriteError:
      case LsSyncState.Synchronized: {
        this.setState(LsSyncState.Closing)
        const promise = this.ls.closeTextFile(this.path)
        const state = this.state
        this.setLastAction(promise.catch(() => this.setState(state)))
        await promise
        this.setState(LsSyncState.Closed)
        return
      }
      default: {
        this.state satisfies never
        return
      }
    }
  }

  async reload() {
    this.queuedAction = LsAction.Reload
    switch (this.state) {
      case LsSyncState.Opening:
      case LsSyncState.Disposed:
      case LsSyncState.Closed:
      case LsSyncState.Closing: {
        return
      }
      case LsSyncState.Reloading: {
        await this.lastAction
        return
      }
      case LsSyncState.WritingFile: {
        await this.lastAction
        if (this.queuedAction === LsAction.Reload) await this.reload()
        return
      }
      case LsSyncState.Synchronized: {
        this.withState(LsSyncState.Reloading, async () => {
          const promise = Promise.all([
            this.ls.readFile(this.path),
            this.ls.fileChecksum(this.path),
          ])
          this.setLastAction(promise)
          const [contents, checksum] = await promise
          this.syncFileContents(contents.contents, checksum.checksum)
        })
        return
      }
      case LsSyncState.WriteError: {
        this.withState(LsSyncState.Reloading, async () => {
          const path = this.path.segments.join('/')
          const reloading = this.ls
            .closeTextFile(this.path)
            .catch((error) => {
              console.error('Could not close file after write error:')
              console.error(error)
            })
            .then(
              () =>
                exponentialBackoff(
                  async () => {
                    const result = await this.ls.openTextFile(this.path)
                    if (!result.writeCapability) {
                      const message = `Could not acquire write capability for module '${this.path.segments.join(
                        '/',
                      )}'`
                      console.error(message)
                      throw new Error(message)
                    }
                    return result
                  },
                  printingCallbacks(
                    `opened file '${path}' for writing`,
                    `open file '${path}' for writing`,
                  ),
                ),
              (error) => {
                console.error('Could not reopen file after write error:')
                console.error(error)
                // This error is unrecoverable.
                throw error
              },
            )
          this.setLastAction(reloading)
          const result = await reloading
          this.syncFileContents(result.content, result.currentVersion)
        })
        return
      }
      default: {
        this.state satisfies never
        return
      }
    }
  }

  async dispose(): Promise<void> {
    this.cleanup()
    const alreadyClosed = this.inState(LsSyncState.Closing, LsSyncState.Closed)
    this.setState(LsSyncState.Disposed)
    if (alreadyClosed) return Promise.resolve()
    return this.ls.closeTextFile(this.path)
  }
}
