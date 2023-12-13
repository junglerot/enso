import { createContextStore } from '@/providers'
import { type WidgetConfiguration } from '@/providers/widgetRegistry/configuration'
import type { GraphDb } from '@/stores/graph/graphDatabase'
import { computed, shallowReactive, type Component, type PropType } from 'vue'

export type WidgetComponent<T extends WidgetInput> = Component<WidgetProps<T>>

/**
 * A type representing any kind of input that can have a widget attached to it. It is defined as an
 * interface to allow for extension by widgets themselves. The actual input received by the widget
 * will always be one of the types defined in this interface. The key of each property is used as a
 * key for the discriminated union, so it should be unique.
 *
 * Declare new widget input types by declaring a new unique symbol key, then extending this
 * interface with a new property with that key and a value of the new input type.
 *
 * ```ts
 * declare const MyCustomInputTypeKey: unique symbol;
 * declare module '@/providers/widgetRegistry' {
 *   export interface WidgetInputTypes {
 *     [MyCustomInputTypeKey]: MyCustomInputType
 *   }
 * }
 * ```
 *
 * All declared widget input types must have unique symbols, and all values must be objects.
 * Declarations that do not follow these rules will be ignored or will cause type errors.
 */
export interface WidgetInputTypes {}

/**
 * An union of all possible widget input types. A collection of all correctly declared value types
 * on the extendable {@link WidgetInputTypes} interface.
 *
 * From the defined input types, it accepts only keys that are declared as symbols and have values
 * that are objects. If your extension of {@link WidgetInputTypes} does not appear in this union,
 * check if you have followed those rules.
 */
export type WidgetInput = Extract<WidgetInputTypes[keyof WidgetInputTypes & symbol], object>

/**
 * Description of how well a widget matches given input. Used to determine which widget should be
 * used, or whether the applied widget override is valid in given context.
 */
export enum Score {
  /**
   * This widget kind cannot accept the node. It will never be used, even if it was explicitly
   * requested using an override.
   */
  Mismatch,
  /**
   * A good match, but there might be a better one. This widget will be used if there is no better
   * option.
   */
  Good,
  /** Widget matches perfectly and can be used outright, without checking other kinds. */
  Perfect,
}

export interface WidgetProps<T> {
  input: T
  config?: WidgetConfiguration | undefined
  nesting: number
}

/**
 * Create Vue props definition for a widget component. This cannot be done automatically by using
 * typed `defineProps`, because vue compiler is not able to resolve conditional types. As a
 * workaround, the runtime prop information is specified manually, and the inferred `T: WidgetInput`
 * type is provided through `PropType`.
 */
export function widgetProps<T extends WidgetInput>(_def: WidgetDefinition<T>) {
  return {
    input: {
      type: Object as PropType<T>,
      required: true,
    },
    config: { type: Object as PropType<WidgetConfiguration | undefined>, required: false },
    nesting: { type: Number, required: true },
  } as const
}

/**
 * A class which instances have type `T`. Accepts classes that have a private constructors, such as
 * `Ast` or `ArgumentPlaceholder`.
 */
type Class<T extends WidgetInput> = Function & { prototype: T }
type InputMatcherFn<T extends WidgetInput> = (input: WidgetInput) => input is T
type InputMatcher<T extends WidgetInput> = InputMatcherFn<T> | Class<T>

type InputTy<M> = M extends (infer T)[]
  ? InputTy<T>
  : M extends InputMatcherFn<infer T>
  ? T
  : M extends Class<infer T>
  ? T
  : never

export interface WidgetOptions<T extends WidgetInput> {
  /** The priority number determining the order in which the widgets are matched. Smaller numbers
   * have higher priority, and are matched first.
   */
  priority: number
  /**
   * Score how well this widget type matches current {@link WidgetProps}, e.g. checking if AST node
   * or declaration type matches specific patterns. When this method returns
   * {@link Score::Mismatch}, this widget component will not be used.
   *
   * When a static score value is provided, it will be used for all inputs that pass the input
   * filter. When not provided, the widget will be considered a perfect match for all inputs that
   * pass the input filter.
   */
  score?: ((info: WidgetProps<T>, db: GraphDb) => Score) | Score
  // A list of widget kinds that will be prevented from being used on the same node as this widget,
  // once this widget is used.
  prevent?: WidgetComponent<any>[]
}

export interface WidgetDefinition<T extends WidgetInput> {
  /** The priority number determining the order in which the widgets are matched. Smaller numbers
   * have higher priority, and are matched first.
   */
  priority: number
  /**
   * Filter the widget input type to only accept specific types of input. The declared widget props
   * will depend on the type of input accepted by this filter. Only widgets that pass this filter
   * will be scored and potentially used.
   */
  match: (input: WidgetInput) => input is T
  /**
   * Score how well this widget type matches current {@link WidgetProps}, e.g. checking if AST node
   * or declaration type matches specific patterns. When this method returns
   * {@link Score::Mismatch}, this widget component will not be used.
   *
   * When a static score value is provided, it will be used for all inputs that pass the input
   * filter. When not provided, the widget will be considered a perfect match for all inputs that
   */
  score: (props: WidgetProps<T>, db: GraphDb) => Score
  prevent: WidgetComponent<any>[] | undefined
}

export interface WidgetModule<T extends WidgetInput> {
  default: WidgetComponent<T>
  widgetDefinition: WidgetDefinition<T>
}

function isWidgetModule(module: unknown): module is WidgetModule<any> {
  return (
    typeof module === 'object' &&
    module !== null &&
    'default' in module &&
    'widgetDefinition' in module &&
    isWidgetComponent(module.default) &&
    isWidgetDefinition(module.widgetDefinition)
  )
}

function isWidgetComponent(component: unknown): component is WidgetComponent<any> {
  return typeof component === 'object' && component !== null
}

function isWidgetDefinition(config: unknown): config is WidgetDefinition<any> {
  return typeof config === 'object' && config !== null && 'priority' in config && 'match' in config
}

/**
 *
 * @param matchInputs Filter the widget input type to only accept specific types of input. The
 * declared widget props will depend on the type of input accepted by this filter. Only widgets that
 * pass this filter will be scored and potentially used.
 *
 * The filter can be a type guard function `(input: WidgetInput) => input is MyType`, a class
 * constructor `MyType`, or an array combining any of the above. When an array is provided, the
 * widget will match if any of the filters in the array match.
 */
export function defineWidget<M extends InputMatcher<any> | InputMatcher<any>[]>(
  matchInputs: M,
  definition: WidgetOptions<InputTy<M>>,
): WidgetDefinition<InputTy<M>> {
  let score: WidgetDefinition<InputTy<M>>['score']
  if (typeof definition.score === 'function') {
    score = definition.score
  } else {
    const staticScore = definition.score ?? Score.Perfect
    score = () => staticScore
  }

  return {
    priority: definition.priority,
    match: makeInputMatcher<InputTy<M>>(matchInputs),
    score,
    prevent: definition.prevent,
  }
}

function makeInputMatcher<T extends WidgetInput>(
  matcher: InputMatcher<T> | InputMatcher<T>[],
): (input: WidgetInput) => input is T {
  if (Array.isArray(matcher)) {
    const matchers = matcher.map(makeInputMatcher)
    return (input: WidgetInput): input is T => matchers.some((f) => f(input))
  } else if (Object.getOwnPropertyDescriptor(matcher, 'prototype')?.writable === false) {
    // A function with an existing non-writable prototype is a class constructor.
    return (input: WidgetInput): input is T => input instanceof matcher
  } else if (typeof matcher === 'function') {
    // When matcher is a function with assignable prototype, it must be a type guard.
    return matcher as (input: WidgetInput) => input is T
  } else {
    throw new Error('Invalid widget input matcher definiton: ' + matcher)
  }
}

export { injectFn as injectWidgetRegistry, provideFn as provideWidgetRegistry }
const { provideFn, injectFn } = createContextStore(
  'Widget registry',
  (db: GraphDb) => new WidgetRegistry(db),
)

export class WidgetRegistry {
  loadedModules: WidgetModule<any>[] = shallowReactive([])
  sortedModules = computed(() => {
    return [...this.loadedModules].sort(
      (a, b) => a.widgetDefinition.priority - b.widgetDefinition.priority,
    )
  })
  constructor(private db: GraphDb) {}

  loadBuiltins() {
    const bulitinWidgets = import.meta.glob('@/components/GraphEditor/widgets/*.vue')
    this.loadAndCheckWidgetModules(Object.entries(bulitinWidgets))
  }

  async loadAndCheckWidgetModules(
    asyncModules: [path: string, asyncModule: () => Promise<unknown>][],
  ) {
    const modules = await Promise.allSettled(
      asyncModules.map(([path, mod]) => mod().then((m) => [path, m] as const)),
    )
    for (const result of modules) {
      if (result.status === 'fulfilled') {
        const [path, mod] = result.value
        if (isWidgetModule(mod)) this.registerWidgetModule(mod)
        else console.error('Invalid widget module:', path, mod)
      }
    }
  }

  /**
   * Register a known loaded widget module. Once registered, the widget will take part in widget
   * selection process. Caution: registering a new widget module will trigger re-matching of all
   * widgets on all nodes in the scene, which can be expensive.
   */
  registerWidgetModule(module: WidgetModule<any>) {
    this.loadedModules.push(module)
  }

  /**
   * Select a type of widget to use for given widget input (e.g. AST node). The selection is based
   * on the widget's `match` function, which returns a score indicating how well the widget matches
   * the input. The widget with the highest score and priority is selected. Widget kinds that are
   * present in `alreadyUsed` set are always skipped.
   */
  select<T extends WidgetInput>(
    props: WidgetProps<T>,
    alreadyUsed?: Set<WidgetComponent<any>>,
  ): WidgetModule<T> | undefined {
    // The type and score of the best widget found so far.
    let best: WidgetModule<T> | undefined = undefined
    let bestScore = Score.Mismatch

    // Iterate over all loaded widget kinds in order of decreasing priority.
    for (const widgetModule of this.sortedModules.value) {
      // Skip matching widgets that are declared as already used.
      if (alreadyUsed && alreadyUsed.has(widgetModule.default)) continue

      // Skip widgets that don't match the input type.
      if (!widgetModule.widgetDefinition.match(props.input)) continue

      // Perform a match and update the best widget if the match is better than the previous one.
      const score = widgetModule.widgetDefinition.score(props, this.db)
      // If we found a perfect match, we can return immediately, as there can be no better match.
      if (score === Score.Perfect) return widgetModule
      if (score > bestScore) {
        bestScore = score
        best = widgetModule
      }
    }
    // Once we've checked all widgets, return the best match found, if any.
    return best
  }
}

// TODO: add tests for select
