/** @file Module for authenticating users with AWS Cognito.
 *
 * Provides an `AuthProvider` component that wraps the entire application, and a `useAuth` hook that
 * can be used from any React component to access the currently logged-in user's session data. The
 * hook also provides methods for registering a user, logging in, logging out, etc. */
import * as react from 'react'
import * as router from 'react-router-dom'
import toast from 'react-hot-toast'

import * as app from '../../components/app'
import * as authServiceModule from '../service'
import * as backendModule from '../../dashboard/backend'
import * as backendProvider from '../../providers/backend'
import * as errorModule from '../../error'
import * as http from '../../http'
import * as localBackend from '../../dashboard/localBackend'
import * as loggerProvider from '../../providers/logger'
import * as newtype from '../../newtype'
import * as remoteBackend from '../../dashboard/remoteBackend'
import * as sessionProvider from './session'

// =================
// === Constants ===
// =================

/** The minimum delay between two requests. */
const REQUEST_DELAY_MS = 200
const MESSAGES = {
    signUpSuccess: 'We have sent you an email with further instructions!',
    confirmSignUpSuccess: 'Your account has been confirmed! Please log in.',
    setUsernameLoading: 'Setting username...',
    setUsernameSuccess: 'Your username has been set!',
    setUsernameFailure: 'Could not set your username.',
    signInWithPasswordSuccess: 'Successfully logged in!',
    forgotPasswordSuccess: 'We have sent you an email with further instructions!',
    changePasswordSuccess: 'Successfully changed password!',
    resetPasswordSuccess: 'Successfully reset password!',
    signOutLoading: 'Logging out...',
    signOutSuccess: 'Successfully logged out!',
    signOutError: 'Error logging out, please try again.',
    pleaseWait: 'Please wait...',
} as const

// ===================
// === UserSession ===
// ===================

/** Possible types of {@link BaseUserSession}. */
export enum UserSessionType {
    offline = 'offline',
    partial = 'partial',
    full = 'full',
}

/** Properties common to all {@link UserSession}s. */
interface BaseUserSession<Type extends UserSessionType> {
    /** A discriminator for TypeScript to be able to disambiguate between `UserSession` variants. */
    type: Type
    /** User's JSON Web Token (JWT), used for authenticating and authorizing requests to the API. */
    accessToken: string
    /** User's email address. */
    email: string
}

// Extends `BaseUserSession` in order to inherit the documentation.
/** Empty object of an offline user session.
 * Contains some fields from {@link FullUserSession} to allow destructuring. */
export interface OfflineUserSession extends Pick<BaseUserSession<UserSessionType.offline>, 'type'> {
    accessToken: null
    organization: null
}

/** The singleton instance of {@link OfflineUserSession}.
 * Minimizes React re-renders. */
const OFFLINE_USER_SESSION: OfflineUserSession = {
    type: UserSessionType.offline,
    accessToken: null,
    organization: null,
}

/** Object containing the currently signed-in user's session data, if the user has not yet set their
 * username.
 *
 * If a user has not yet set their username, they do not yet have an organization associated with
 * their account. Otherwise, this type is identical to the `Session` type. This type should ONLY be
 * used by the `SetUsername` component. */
export interface PartialUserSession extends BaseUserSession<UserSessionType.partial> {}

/** Object containing the currently signed-in user's session data. */
export interface FullUserSession extends BaseUserSession<UserSessionType.full> {
    /** User's organization information. */
    organization: backendModule.UserOrOrganization
}

/** A user session for a user that may be either fully registered,
 * or in the process of registering. */
export type UserSession = FullUserSession | OfflineUserSession | PartialUserSession

// ===================
// === AuthContext ===
// ===================

/** Interface returned by the `useAuth` hook.
 *
 * Contains the currently authenticated user's session data, as well as methods for signing in,
 * signing out, etc. All interactions with the authentication API should be done through this
 * interface.
 *
 * See {@link Cognito} for details on each of the authentication functions. */
interface AuthContextType {
    goOffline: () => Promise<boolean>
    signUp: (email: string, password: string) => Promise<boolean>
    confirmSignUp: (email: string, code: string) => Promise<boolean>
    setUsername: (
        backend: backendProvider.AnyBackendAPI,
        username: string,
        email: string
    ) => Promise<boolean>
    signInWithGoogle: () => Promise<boolean>
    signInWithGitHub: () => Promise<boolean>
    signInWithPassword: (email: string, password: string) => Promise<boolean>
    forgotPassword: (email: string) => Promise<boolean>
    changePassword: (oldPassword: string, newPassword: string) => Promise<boolean>
    resetPassword: (email: string, code: string, password: string) => Promise<boolean>
    signOut: () => Promise<boolean>
    /** Session containing the currently authenticated user's authentication information.
     *
     * If the user has not signed in, the session will be `null`. */
    session: UserSession | null
}

// Eslint doesn't like headings.
/** Create a global instance of the `AuthContextType`, that will be re-used between all React
 * components that use the `useAuth` hook.
 *
 * # Safety of Context Initialization
 *
 * An `as ...` cast is unsafe. We use this cast when creating the context. So it appears that the
 * `AuthContextType` can be unsafely (i.e., only partially) initialized as a result of this.
 *
 * So it appears that we should remove the cast and initialize the context as `null` instead.
 *
 * **However**, initializing a context the existing way is the recommended way to initialize a
 * context in React.  It is safe, for non-obvious reasons. It is safe because the `AuthContext` is
 * only accessible through the `useAuth` hook.
 *
 * 1. If the `useAuth` hook is called in a component that is a child of an `AuthProvider`, then the
 * context is guaranteed to be initialized, because the `AuthProvider` constructor is what
 * initializes it. So the cast is safe.
 * 2. If the `useAuth` hook is called in a component that is not a child of an `AuthProvider`, then
 * the hook will throw an error regardless, because React does not support using hooks outside of
 * their supporting providers.
 *
 * So changing the cast would provide no safety guarantees, and would require us to introduce null
 * checks everywhere we use the context. */
// eslint-disable-next-line no-restricted-syntax
const AuthContext = react.createContext<AuthContextType>({} as AuthContextType)

// ====================
// === AuthProvider ===
// ====================

/** Props for an {@link AuthProvider}. */
export interface AuthProviderProps {
    authService: authServiceModule.AuthService
    /** Callback to execute once the user has authenticated successfully. */
    onAuthenticated: () => void
    children: react.ReactNode
}

/** A React provider for the Cognito API. */
export function AuthProvider(props: AuthProviderProps) {
    const { authService, onAuthenticated, children } = props
    const { cognito } = authService
    const { session, deinitializeSession } = sessionProvider.useSession()
    const { setBackendWithoutSavingType } = backendProvider.useSetBackend()
    const logger = loggerProvider.useLogger()
    // This must not be `hooks.useNavigate` as `goOffline` would be inaccessible,
    // and the function call would error.
    // eslint-disable-next-line no-restricted-properties
    const navigate = router.useNavigate()
    const [initialized, setInitialized] = react.useState(false)
    const [userSession, setUserSession] = react.useState<UserSession | null>(null)

    // This is identical to `hooks.useOnlineCheck`, however it is inline here to avoid any possible
    // circular dependency.
    react.useEffect(() => {
        if (!navigator.onLine) {
            void goOffline()
        }
    }, [navigator.onLine])

    /** Fetch the JWT access token from the session via the AWS Amplify library.
     *
     * When invoked, retrieves the access token (if available) from the storage method chosen when
     * Amplify was configured (e.g. local storage). If the token is not available, return `undefined`.
     * If the token has expired, automatically refreshes the token and returns the new token. */
    react.useEffect(() => {
        const fetchSession = async () => {
            if (!navigator.onLine) {
                goOfflineInternal()
            } else if (session.none) {
                setInitialized(true)
                setUserSession(null)
            } else {
                const { accessToken, email } = session.val
                const headers = new Headers()
                headers.append('Authorization', `Bearer ${accessToken}`)
                const client = new http.Client(headers)
                const backend = new remoteBackend.RemoteBackend(client, logger)
                // The backend MUST be the remote backend before login is finished.
                // This is because the "set username" flow requires the remote backend.
                if (!initialized || userSession == null) {
                    setBackendWithoutSavingType(backend)
                }
                let organization
                // eslint-disable-next-line no-restricted-syntax
                while (organization === undefined) {
                    try {
                        organization = await backend.usersMe()
                    } catch {
                        // The value may have changed after the `await`.
                        // eslint-disable-next-line @typescript-eslint/no-unnecessary-condition
                        if (!navigator.onLine) {
                            goOfflineInternal()
                            // eslint-disable-next-line no-restricted-syntax
                            return
                        }
                        // This prevents a busy loop when request blocking is enabled in DevTools.
                        // The UI will be blank indefinitely. This is intentional, since for real
                        // network outages, `navigator.onLine` will be false.
                        await new Promise(resolve => setTimeout(resolve, REQUEST_DELAY_MS))
                    }
                }
                let newUserSession: UserSession
                const sharedSessionData = { email, accessToken }
                if (!organization) {
                    newUserSession = {
                        type: UserSessionType.partial,
                        ...sharedSessionData,
                    }
                } else {
                    newUserSession = {
                        type: UserSessionType.full,
                        ...sharedSessionData,
                        organization,
                    }

                    /** Save access token so can be reused by Enso backend. */
                    cognito.saveAccessToken(accessToken)

                    /** Execute the callback that should inform the Electron app that the user has logged in.
                     * This is done to transition the app from the authentication/dashboard view to the IDE. */
                    onAuthenticated()
                }

                setUserSession(newUserSession)
                setInitialized(true)
            }
        }

        fetchSession().catch(error => {
            if (isUserFacingError(error)) {
                toast.error(error.message)
            } else {
                logger.error(error)
            }
        })
    }, [session])

    /** Wrap a function returning a {@link Promise} to displays a loading toast notification
     * until the returned {@link Promise} finishes loading. */
    const withLoadingToast =
        <T extends unknown[], R>(action: (...args: T) => Promise<R>) =>
        async (...args: T) => {
            const loadingToast = toast.loading(MESSAGES.pleaseWait)
            let result
            try {
                result = await action(...args)
            } finally {
                toast.dismiss(loadingToast)
            }
            return result
        }

    const goOfflineInternal = () => {
        setInitialized(true)
        setUserSession(OFFLINE_USER_SESSION)
        setBackendWithoutSavingType(new localBackend.LocalBackend())
    }

    const goOffline = () => {
        toast.error('You are offline, switching to offline mode.')
        goOfflineInternal()
        navigate(app.DASHBOARD_PATH)
        return Promise.resolve(true)
    }

    const signUp = async (username: string, password: string) => {
        const result = await cognito.signUp(username, password)
        if (result.ok) {
            toast.success(MESSAGES.signUpSuccess)
        } else {
            toast.error(result.val.message)
        }
        return result.ok
    }

    const confirmSignUp = async (email: string, code: string) => {
        const result = await cognito.confirmSignUp(email, code)
        if (result.err) {
            switch (result.val.kind) {
                case 'UserAlreadyConfirmed':
                    break
                default:
                    throw new errorModule.UnreachableCaseError(result.val.kind)
            }
        }

        toast.success(MESSAGES.confirmSignUpSuccess)
        navigate(app.LOGIN_PATH)
        return result.ok
    }

    const signInWithPassword = async (email: string, password: string) => {
        const result = await cognito.signInWithPassword(email, password)
        if (result.ok) {
            toast.success(MESSAGES.signInWithPasswordSuccess)
        } else {
            if (result.val.kind === 'UserNotFound') {
                navigate(app.REGISTRATION_PATH)
            }

            toast.error(result.val.message)
        }
        return result.ok
    }

    const setUsername = async (
        backend: backendProvider.AnyBackendAPI,
        username: string,
        email: string
    ) => {
        if (backend.type === backendModule.BackendType.local) {
            toast.error('You cannot set your username on the local backend.')
            return false
        } else {
            try {
                await toast.promise(
                    backend.createUser({
                        userName: username,
                        userEmail: newtype.asNewtype<backendModule.EmailAddress>(email),
                    }),
                    {
                        success: MESSAGES.setUsernameSuccess,
                        error: MESSAGES.setUsernameFailure,
                        loading: MESSAGES.setUsernameLoading,
                    }
                )
                navigate(app.DASHBOARD_PATH)
                return true
            } catch (e) {
                return false
            }
        }
    }

    const forgotPassword = async (email: string) => {
        const result = await cognito.forgotPassword(email)
        if (result.ok) {
            toast.success(MESSAGES.forgotPasswordSuccess)
            navigate(app.RESET_PASSWORD_PATH)
        } else {
            toast.error(result.val.message)
        }
        return result.ok
    }

    const resetPassword = async (email: string, code: string, password: string) => {
        const result = await cognito.forgotPasswordSubmit(email, code, password)
        if (result.ok) {
            toast.success(MESSAGES.resetPasswordSuccess)
            navigate(app.LOGIN_PATH)
        } else {
            toast.error(result.val.message)
        }
        return result.ok
    }

    const changePassword = async (oldPassword: string, newPassword: string) => {
        const result = await cognito.changePassword(oldPassword, newPassword)
        if (result.ok) {
            toast.success(MESSAGES.changePasswordSuccess)
        } else {
            toast.error(result.val.message)
        }
        return result.ok
    }

    const signOut = async () => {
        deinitializeSession()
        setInitialized(false)
        setUserSession(null)
        await toast.promise(cognito.signOut(), {
            success: MESSAGES.signOutSuccess,
            error: MESSAGES.signOutError,
            loading: MESSAGES.signOutLoading,
        })
        return true
    }

    const value = {
        goOffline: goOffline,
        signUp: withLoadingToast(signUp),
        confirmSignUp: withLoadingToast(confirmSignUp),
        setUsername,
        signInWithGoogle: () =>
            cognito.signInWithGoogle().then(
                () => true,
                () => false
            ),
        signInWithGitHub: () =>
            cognito.signInWithGitHub().then(
                () => true,
                () => false
            ),
        signInWithPassword: withLoadingToast(signInWithPassword),
        forgotPassword: withLoadingToast(forgotPassword),
        resetPassword: withLoadingToast(resetPassword),
        changePassword: withLoadingToast(changePassword),
        signOut,
        session: userSession,
    }

    return (
        <AuthContext.Provider value={value}>
            {/* Only render the underlying app after we assert for the presence of a current user. */}
            {initialized && children}
        </AuthContext.Provider>
    )
}

/** Type of an error containing a `string`-typed `message` field.
 *
 * Many types of errors fall into this category. We use this type to check if an error can be safely
 * displayed to the user. */
interface UserFacingError {
    /** The user-facing error message. */
    message: string
}

/** Return `true` if the value is a {@link UserFacingError}. */
function isUserFacingError(value: unknown): value is UserFacingError {
    return typeof value === 'object' && value != null && 'message' in value
}

// ===============
// === useAuth ===
// ===============

/** A React hook that provides access to the authentication context.
 *
 * Only the hook is exported, and not the context, because we only want to use the hook directly and
 * never the context component. */
export function useAuth() {
    return react.useContext(AuthContext)
}

// ===============================
// === shouldPreventNavigation ===
// ===============================

/** True if navigation should be prevented, for debugging purposes. */
function getShouldPreventNavigation() {
    const location = router.useLocation()
    return new URLSearchParams(location.search).get('prevent-navigation') === 'true'
}

// =======================
// === ProtectedLayout ===
// =======================

/** A React Router layout route containing routes only accessible by users that are logged in. */
export function ProtectedLayout() {
    const { session } = useAuth()
    const shouldPreventNavigation = getShouldPreventNavigation()

    if (!shouldPreventNavigation && !session) {
        return <router.Navigate to={app.LOGIN_PATH} />
    } else if (!shouldPreventNavigation && session?.type === UserSessionType.partial) {
        return <router.Navigate to={app.SET_USERNAME_PATH} />
    } else {
        return <router.Outlet context={session} />
    }
}

// ===========================
// === SemiProtectedLayout ===
// ===========================

/** A React Router layout route containing routes only accessible by users that are
 * in the process of registering. */
export function SemiProtectedLayout() {
    const { session } = useAuth()
    const shouldPreventNavigation = getShouldPreventNavigation()

    if (!shouldPreventNavigation && session?.type === UserSessionType.full) {
        return <router.Navigate to={app.DASHBOARD_PATH} />
    } else {
        return <router.Outlet context={session} />
    }
}

// ===================
// === GuestLayout ===
// ===================

/** A React Router layout route containing routes only accessible by users that are
 * not logged in. */
export function GuestLayout() {
    const { session } = useAuth()
    const shouldPreventNavigation = getShouldPreventNavigation()

    if (!shouldPreventNavigation && session?.type === UserSessionType.partial) {
        return <router.Navigate to={app.SET_USERNAME_PATH} />
    } else if (!shouldPreventNavigation && session?.type === UserSessionType.full) {
        return <router.Navigate to={app.DASHBOARD_PATH} />
    } else {
        return <router.Outlet />
    }
}

// =============================
// === usePartialUserSession ===
// =============================

/** A React context hook returning the user session
 * for a user that has not yet completed registration. */
export function usePartialUserSession() {
    return router.useOutletContext<PartialUserSession>()
}

// ================================
// === useNonPartialUserSession ===
// ================================

/** A React context hook returning the user session for a user that can perform actions. */
export function useNonPartialUserSession() {
    return router.useOutletContext<Exclude<UserSession, PartialUserSession>>()
}
