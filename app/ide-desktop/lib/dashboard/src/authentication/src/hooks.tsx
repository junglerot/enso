/** @file Module containing common custom React hooks used throughout out Dashboard. */
import * as React from 'react'
import * as router from 'react-router'

import * as app from './components/app'
import * as auth from './authentication/providers/auth'
import * as loggerProvider from './providers/logger'

// ==================
// === useRefresh ===
// ==================

/** An alias to make the purpose of the returned empty object clearer. */
export interface RefreshState {}

/** A hook that contains no state, and is used only to tell React when to re-render. */
export function useRefresh() {
    // Uses an empty object literal because every distinct literal
    // is a new reference and therefore is not equal to any other object literal.
    return React.useReducer((): RefreshState => ({}), {})
}

// ======================
// === useAsyncEffect ===
// ======================

/** A React hook for re-rendering a component once an asynchronous call is over.
 *
 * This hook will take care of setting an initial value for the component state (so that it can
 * render immediately), updating the state once the asynchronous call is over (to re-render the
 * component), and cancelling any in-progress asynchronous calls when the component is unmounted (to
 * avoid race conditions where "update 1" starts, "update 2" starts and finishes, then "update 1"
 * finishes and sets the state).
 *
 * For further details, see: https://devtrium.com/posts/async-functions-useeffect.
 * Also see: https://stackoverflow.com/questions/61751728/asynchronous-calls-with-react-usememo.
 *
 * @param initialValue - The initial value of the state controlled by this hook.
 * @param asyncEffect - The asynchronous function used to load the state controlled by this hook.
 * @param deps - The list of dependencies that, when updated, trigger the asynchronous effect.
 * @returns The current value of the state controlled by this hook. */
export function useAsyncEffect<T>(
    initialValue: T,
    asyncEffect: (signal: AbortSignal) => Promise<T>,
    deps?: React.DependencyList
): T {
    const logger = loggerProvider.useLogger()
    const [value, setValue] = React.useState<T>(initialValue)

    React.useEffect(() => {
        const controller = new AbortController()
        void asyncEffect(controller.signal).then(
            result => {
                if (!controller.signal.aborted) {
                    setValue(result)
                }
            },
            error => {
                logger.error('Error while fetching data:', error)
            }
        )
        /** Cancel any future `setValue` calls. */
        return () => {
            controller.abort()
        }
        // This is a wrapper function around `useEffect`, so it has its own `deps` array.
        // `asyncEffect` is omitted as it always changes - this is intentional.
        // `logger` is omitted as it should not trigger the effect.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, deps ?? [])

    return value
}

// ===================
// === useNavigate ===
// ===================

/** A wrapper around {@link router.useNavigate} that goes into offline mode when
 * offline. */
export function useNavigate() {
    const { goOffline } = auth.useAuth()
    // This function is a wrapper around `router.useNavigate`. It shouldbe the only place where
    // `router.useNavigate` is used.
    // eslint-disable-next-line no-restricted-properties
    const originalNavigate = router.useNavigate()

    const navigate: router.NavigateFunction = (...args: [unknown, unknown?]) => {
        const isOnline = navigator.onLine
        if (!isOnline) {
            void goOffline()
            originalNavigate(app.DASHBOARD_PATH)
        } else {
            // This is safe, because the arguments are being passed through transparently.
            // eslint-disable-next-line no-restricted-syntax
            originalNavigate(...(args as [never, never?]))
        }
    }

    return navigate
}

// ================
// === useEvent ===
// ================

/** A wrapper around `useState` that sets its value to `null` after the current render. */
export function useEvent<T>(): [event: T | null, dispatchEvent: (value: T | null) => void] {
    const [event, setEvent] = React.useState<T | null>(null)

    React.useEffect(() => {
        if (event != null) {
            setEvent(null)
        }
    }, [event])

    return [event, setEvent]
}

// =========================================
// === Debug wrappers for built-in hooks ===
// =========================================

// `console.*` is allowed because these are for debugging purposes only.
/* eslint-disable no-restricted-properties */

// === useDebugState ===

/** A modified `useState` that logs the old and new values when `setState` is called. */
export function useDebugState<T>(
    initialState: T | (() => T),
    name?: string
): [state: T, setState: (valueOrUpdater: React.SetStateAction<T>, source?: string) => void] {
    const [state, rawSetState] = React.useState(initialState)

    const description = name != null ? `state for '${name}'` : 'state'

    const setState = React.useCallback(
        (valueOrUpdater: React.SetStateAction<T>, source?: string) => {
            const fullDescription = `${description}${source != null ? ` from '${source}'` : ''}`
            if (typeof valueOrUpdater === 'function') {
                // This is UNSAFE, however React makes the same assumption.
                // eslint-disable-next-line no-restricted-syntax
                const updater = valueOrUpdater as (prevState: T) => T
                rawSetState(oldState => {
                    console.group(description)
                    console.log(`Old ${fullDescription}:`, oldState)
                    const newState = updater(oldState)
                    console.log(`New ${fullDescription}:`, newState)
                    console.groupEnd()
                    return newState
                })
            } else {
                rawSetState(oldState => {
                    if (!Object.is(oldState, valueOrUpdater)) {
                        console.group(description)
                        console.log(`Old ${fullDescription}:`, oldState)
                        console.log(`New ${fullDescription}:`, valueOrUpdater)
                        console.groupEnd()
                    }
                    return valueOrUpdater
                })
            }
        },
        [description]
    )

    return [state, setState]
}

// === useMonitorDependencies ===

/** A helper function to log the old and new values of changed dependencies. */
function useMonitorDependencies(
    dependencies: React.DependencyList,
    description?: string,
    dependencyDescriptions?: readonly string[]
) {
    const oldDependenciesRef = React.useRef(dependencies)
    const indicesOfChangedDependencies = dependencies.flatMap((dep, i) =>
        Object.is(dep, oldDependenciesRef.current[i]) ? [] : [i]
    )
    if (indicesOfChangedDependencies.length !== 0) {
        const descriptionText = description == null ? '' : `for '${description}'`
        console.group(`dependencies changed${descriptionText}`)
        for (const i of indicesOfChangedDependencies) {
            console.group(dependencyDescriptions?.[i] ?? `dependency #${i + 1}`)
            console.log('old value:', oldDependenciesRef.current[i])
            console.log('new value:', dependencies[i])
            console.groupEnd()
        }
        console.groupEnd()
    }
    oldDependenciesRef.current = dependencies
}

// === useDebugEffect ===

/** A modified `useEffect` that logs the old and new values of changed dependencies. */
export function useDebugEffect(
    effect: React.EffectCallback,
    deps: React.DependencyList,
    description?: string,
    dependencyDescriptions?: readonly string[]
) {
    useMonitorDependencies(deps, description, dependencyDescriptions)
    // eslint-disable-next-line react-hooks/exhaustive-deps
    React.useEffect(effect, deps)
}

// === useDebugMemo ===

/** A modified `useMemo` that logs the old and new values of changed dependencies. */
export function useDebugMemo<T>(
    factory: () => T,
    deps: React.DependencyList,
    description?: string,
    dependencyDescriptions?: readonly string[]
) {
    useMonitorDependencies(deps, description, dependencyDescriptions)
    // eslint-disable-next-line react-hooks/exhaustive-deps
    return React.useMemo<T>(factory, deps)
}

// === useDebugCallback ===

/** A modified `useCallback` that logs the old and new values of changed dependencies. */
export function useDebugCallback<T extends (...args: never[]) => unknown>(
    callback: T,
    deps: React.DependencyList,
    description?: string,
    dependencyDescriptions?: readonly string[]
) {
    useMonitorDependencies(deps, description, dependencyDescriptions)
    // eslint-disable-next-line react-hooks/exhaustive-deps
    return React.useCallback<T>(callback, deps)
}

/* eslint-enable no-restricted-properties */
