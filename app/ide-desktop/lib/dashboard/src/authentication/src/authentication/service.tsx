/** @file Provides an {@link AuthService} which consists of an underyling {@link Cognito} API
 * wrapper, along with some convenience callbacks to make URL redirects for the authentication flows
 * work with Electron. */
import * as amplify from '@aws-amplify/auth'

import * as common from 'enso-common'

import * as app from '../components/app'
import * as auth from './config'
import * as cognito from './cognito'
import * as config from '../config'
import * as listen from './listen'
import * as loggerProvider from '../providers/logger'
import * as newtype from '../newtype'
import * as platformModule from '../platform'

// =================
// === Constants ===
// =================

/** Pathname of the {@link URL} for deep links to the sign-in page, after a redirect from a
 * federated identity provider. */
const SIGN_IN_PATHNAME = '//auth'
/** Pathname of the {@link URL} for deep links to the sign-out page, after a redirect from a
 * federated identity provider. */
const SIGN_OUT_PATHNAME = '//auth'
/** Pathname of the {@link URL} for deep links to the registration confirmation page, after a
 * redirect from an account verification email. */
const CONFIRM_REGISTRATION_PATHNAME = '//auth/confirmation'
/** Pathname of the {@link URL} for deep links to the login page, after a redirect from a reset
 * password email. */
const LOGIN_PATHNAME = '//auth/login'

/** URL used as the OAuth redirect when running in the desktop app. */
const DESKTOP_REDIRECT = newtype.asNewtype<auth.OAuthRedirect>(`${common.DEEP_LINK_SCHEME}://auth`)
/** Map from platform to the OAuth redirect URL that should be used for that platform. */
const PLATFORM_TO_CONFIG: Record<
    platformModule.Platform,
    Pick<auth.AmplifyConfig, 'redirectSignIn' | 'redirectSignOut'>
> = {
    [platformModule.Platform.desktop]: {
        redirectSignIn: DESKTOP_REDIRECT,
        redirectSignOut: DESKTOP_REDIRECT,
    },
    [platformModule.Platform.cloud]: {
        redirectSignIn: config.ACTIVE_CONFIG.cloudRedirect,
        redirectSignOut: config.ACTIVE_CONFIG.cloudRedirect,
    },
}

const BASE_AMPLIFY_CONFIG = {
    region: auth.AWS_REGION,
    scope: auth.OAUTH_SCOPES,
    responseType: auth.OAUTH_RESPONSE_TYPE,
} satisfies Partial<auth.AmplifyConfig>

/** Collection of configuration details for Amplify user pools, sorted by deployment environment. */
const AMPLIFY_CONFIGS = {
    /** Configuration for @pbuchu's Cognito user pool. */
    pbuchu: {
        userPoolId: newtype.asNewtype<auth.UserPoolId>('eu-west-1_jSF1RbgPK'),
        userPoolWebClientId: newtype.asNewtype<auth.UserPoolWebClientId>(
            '1bnib0jfon3aqc5g3lkia2infr'
        ),
        domain: newtype.asNewtype<auth.OAuthDomain>(
            'pb-enso-domain.auth.eu-west-1.amazoncognito.com'
        ),
        ...BASE_AMPLIFY_CONFIG,
    } satisfies Partial<auth.AmplifyConfig>,
    /** Configuration for the production Cognito user pool. */
    production: {
        userPoolId: newtype.asNewtype<auth.UserPoolId>('eu-west-1_9Kycu2SbD'),
        userPoolWebClientId: newtype.asNewtype<auth.UserPoolWebClientId>(
            '4j9bfs8e7415erf82l129v0qhe'
        ),
        domain: newtype.asNewtype<auth.OAuthDomain>(
            'production-enso-domain.auth.eu-west-1.amazoncognito.com'
        ),
        ...BASE_AMPLIFY_CONFIG,
    } satisfies Partial<auth.AmplifyConfig>,
}

// ==================
// === AuthConfig ===
// ==================

/** Configuration for the authentication service. */
export interface AuthConfig {
    /** Logger for the authentication service. */
    logger: loggerProvider.Logger
    /** Whether the application is running on a desktop (i.e., versus in the Cloud). */
    platform: platformModule.Platform
    /** Function to navigate to a given (relative) URL.
     *
     * Used to redirect to pages like the password reset page with the query parameters set in the
     * URL (e.g., `?verification_code=...`). */
    navigate: (url: string) => void
}

// ===================
// === AuthService ===
// ===================

/** API for the authentication service. */
export interface AuthService {
    /** @see {@link cognito.Cognito}. */
    cognito: cognito.Cognito
    /** @see {@link listen.ListenFunction} */
    registerAuthEventListener: listen.ListenFunction
}

/** Create an instance of the authentication service.
 *
 * # Warning
 *
 * This function should only be called once, and the returned service should be used throughout the
 * application. This is because it performs global configuration of the Amplify library. */
export function initAuthService(authConfig: AuthConfig): AuthService {
    const { logger, platform, navigate } = authConfig
    const amplifyConfig = loadAmplifyConfig(logger, platform, navigate)
    const cognitoClient = new cognito.Cognito(logger, platform, amplifyConfig)
    return {
        cognito: cognitoClient,
        registerAuthEventListener: listen.registerAuthEventListener,
    }
}

/** Return the appropriate Amplify configuration for the current platform. */
function loadAmplifyConfig(
    logger: loggerProvider.Logger,
    platform: platformModule.Platform,
    navigate: (url: string) => void
): auth.AmplifyConfig {
    /** Load the environment-specific Amplify configuration. */
    const baseConfig = AMPLIFY_CONFIGS[config.ENVIRONMENT]
    let urlOpener = null
    let accessTokenSaver = null
    if (platform === platformModule.Platform.desktop) {
        /** If we're running on the desktop, we want to override the default URL opener for OAuth
         * flows.  This is because the default URL opener opens the URL in the desktop app itself,
         * but we want the user to be sent to their system browser instead. The user should be sent
         * to their system browser because:
         *
         * - users trust their system browser with their credentials more than they trust our app;
         * - our app can keep itself on the relevant page until the user is sent back to it (i.e.,
         * we avoid unnecessary reloads/refreshes caused by redirects. */
        urlOpener = openUrlWithExternalBrowser

        /** When running on destop we want to have option to save access token to a file,
         * so it can be later reused when issuing requests to the Cloud API. */
        accessTokenSaver = saveAccessToken

        /** To handle redirects back to the application from the system browser, we also need to
         * register a custom URL handler. */
        setDeepLinkHandler(logger, navigate)
    }
    /** Load the platform-specific Amplify configuration. */
    const platformConfig = PLATFORM_TO_CONFIG[platform]
    return {
        ...baseConfig,
        ...platformConfig,
        urlOpener,
        accessTokenSaver,
    }
}

/** Open a URL with the user's default browser. */
function openUrlWithExternalBrowser(url: string) {
    window.authenticationApi.openUrlInSystemBrowser(url)
}

/** Save the access token to a file. */
function saveAccessToken(accessToken: string) {
    window.authenticationApi.saveAccessToken(accessToken)
}

/** Set the callback that will be invoked when a deep link to the application is opened.
 *
 * Typically this callback is invoked when the user is redirected back to the app after:
 *
 * 1. Authenticating with a federated identity provider; or
 * 2. Clicking a "reset password" link in a password reset email.
 *
 * For example, when the user completes an OAuth sign in flow (e.g., through Google), they are
 * redirected to a URL like `enso://authentication/register?code=...`. This listener will intercept
 * that URL and open the page `register?code=...` in the application window.
 *
 * This is only used when running on the desktop, as the browser version of the app lets Amplify
 * handle the redirect for us. On the desktop however, we need to handle the redirect ourselves,
 * because it's a deep link into the app, and Amplify doesn't handle deep links.
 *
 * All URLs that don't have a pathname that starts with {@link AUTHENTICATION_PATHNAME_BASE} will be
 * ignored by this handler. */
function setDeepLinkHandler(logger: loggerProvider.Logger, navigate: (url: string) => void) {
    const onDeepLink = (url: string) => {
        const parsedUrl = new URL(url)
        logger.log(`Parsed pathname: ${parsedUrl.pathname}`)
        // We need to get rid of the trailing slash in the pathname, because it is inconsistent
        // between the platforms. On Windows it is present, on macOS it is not.
        const pathname = parsedUrl.pathname.replace(/\/$/, '')
        switch (pathname) {
            /** If the user is being redirected after clicking the registration confirmation link in their
             * email, then the URL will be for the confirmation page path. */
            case CONFIRM_REGISTRATION_PATHNAME: {
                const redirectUrl = `${app.CONFIRM_REGISTRATION_PATH}${parsedUrl.search}`
                navigate(redirectUrl)
                break
            }
            /** TODO [NP]: https://github.com/enso-org/cloud-v2/issues/339
             * Don't use `enso://auth` for both authentication redirect & signout redirect so we don't
             * have to disambiguate between the two on the `DASHBOARD_PATH`. */
            case SIGN_OUT_PATHNAME:
            case SIGN_IN_PATHNAME:
                /** If the user is being redirected after a sign-out, then no query args will be present. */
                if (parsedUrl.search === '') {
                    navigate(app.LOGIN_PATH)
                } else {
                    handleAuthResponse(url)
                }
                break
            /** If the user is being redirected after finishing the password reset flow, then the URL will
             * be for the login page. */
            case LOGIN_PATHNAME:
                navigate(app.LOGIN_PATH)
                break
            /** If the user is being redirected from a password reset email, then we need to navigate to
             * the password reset page, with the verification code and email passed in the URL s-o they can
             * be filled in automatically. */
            case app.RESET_PASSWORD_PATH: {
                const resetPasswordRedirectUrl = `${app.RESET_PASSWORD_PATH}${parsedUrl.search}`
                navigate(resetPasswordRedirectUrl)
                break
            }
            default:
                logger.error(`${url} is an unrecognized deep link. Ignoring.`)
        }
    }

    window.authenticationApi.setDeepLinkHandler(onDeepLink)
}

/** When the user is being redirected from a federated identity provider, then we need to pass the
 * URL to the Amplify library, which will parse the URL and complete the OAuth flow. */
function handleAuthResponse(url: string) {
    void (async () => {
        /** Temporarily override the `window.location` object so that Amplify doesn't try to call
         * `window.location.replaceState` (which doesn't work in the renderer process because of
         * Electron's `webSecurity`). This is a hack, but it's the only way to get Amplify to work
         * with a custom URL protocol in Electron.
         *
         * # Safety
         *
         * It is safe to disable the `unbound-method` lint here because we intentionally want to use
         * the original `window.history.replaceState` function, which is not bound to the
         * `window.history` object. */
        // eslint-disable-next-line @typescript-eslint/unbound-method
        const replaceState = window.history.replaceState
        window.history.replaceState = () => false
        try {
            /** # Safety
             *
             * It is safe to disable the `no-unsafe-call` lint here because we know that the `Auth` object
             * has the `_handleAuthResponse` method, and we know that it is safe to call it with the `url`
             * argument. There is no way to prove this to the TypeScript compiler, because these methods
             * are intentionally not part of the public AWS Amplify API. */
            // @ts-expect-error `_handleAuthResponse` is a private method without typings.
            // eslint-disable-next-line @typescript-eslint/no-unsafe-call
            await amplify.Auth._handleAuthResponse(url)
        } finally {
            /** Restore the original `window.location.replaceState` function. */
            window.history.replaceState = replaceState
        }
    })()
}
