/** @file Configuration definition for the Dashboard. */

import * as auth from './authentication/config'
import * as newtype from './newtype'

// =================
// === Constants ===
// =================

/** The web domain of the cloud website. */
export const CLOUD_DOMAIN = 'https://cloud.enso.org'

/** The current environment that we're running in. */
export const ENVIRONMENT: Environment = 'production'

/** All possible URLs used as the OAuth redirects when running the cloud app. */
const CLOUD_REDIRECTS = {
    /** In development, a fixed port is used so that the redirect URL can be known ahead of time.
     * The redirect URL must be known ahead of time because it is registered with the OAuth provider
     * when it is created. In the native app, the port is unpredictable, but this is not a problem
     * because the native app does not use port-based redirects, but deep links. */
    development: newtype.asNewtype<auth.OAuthRedirect>('http://localhost:8080'),
    production: newtype.asNewtype<auth.OAuthRedirect>(REDIRECT_OVERRIDE ?? CLOUD_DOMAIN),
}

/** All possible API URLs, sorted by environment. */
const API_URLS = {
    pbuchu: newtype.asNewtype<ApiUrl>('https://xw0g8j3tsb.execute-api.eu-west-1.amazonaws.com'),
    npekin: newtype.asNewtype<ApiUrl>('https://s02ejyepk1.execute-api.eu-west-1.amazonaws.com'),
    production: newtype.asNewtype<ApiUrl>('https://7aqkn3tnbc.execute-api.eu-west-1.amazonaws.com'),
}

/**
 * All possible Help Chat endpoint URLs, sorted by environment.
 *
 * In development mode, the chat bot will need to be run locally:
 * https://github.com/enso-org/enso-bot */
const CHAT_URLS = {
    development: newtype.asNewtype<ChatUrl>('ws://localhost:8082'),
    // TODO[sb]: Insert the actual URL of the production chat bot here.
    production: newtype.asNewtype<ChatUrl>('wss://chat.cloud.enso.org'),
}

/** All possible configuration options, sorted by environment. */
const CONFIGS = {
    npekin: {
        cloudRedirect: CLOUD_REDIRECTS.development,
        apiUrl: API_URLS.npekin,
        chatUrl: CHAT_URLS.development,
    } satisfies Config,
    pbuchu: {
        cloudRedirect: CLOUD_REDIRECTS.development,
        apiUrl: API_URLS.pbuchu,
        chatUrl: CHAT_URLS.development,
    } satisfies Config,
    production: {
        cloudRedirect: CLOUD_REDIRECTS.production,
        apiUrl: API_URLS.production,
        chatUrl: CHAT_URLS.production,
    } satisfies Config,
}
/** Export the configuration that is currently in use. */
export const ACTIVE_CONFIG: Config = CONFIGS[ENVIRONMENT]

// ==============
// === Config ===
// ==============

/** Interface defining the configuration options that we expect to provide for the Dashboard. */
export interface Config {
    /** URL of the OAuth redirect when running in the cloud app.
     *
     * The desktop app redirects to a static deep link, so it does not have to be configured. */
    cloudRedirect: auth.OAuthRedirect
    /** Base URL for requests to our Cloud API backend. */
    apiUrl: ApiUrl
    /** URL to the websocket endpoint of the Help Chat. */
    chatUrl: ChatUrl
}

// ===================
// === Environment ===
// ===================

/** Possible values for the environment/user we're running for and whose infrastructure we're
 * testing against. */
export type Environment = 'npekin' | 'pbuchu' | 'production'

// ===========
// === API ===
// ===========

/** Base URL for requests to our Cloud API backend. */
type ApiUrl = newtype.Newtype<`http://${string}` | `https://${string}`, 'ApiUrl'>

/** URL to the websocket endpoint of the Help Chat. */
type ChatUrl = newtype.Newtype<`ws://${string}` | `wss://${string}`, 'ChatUrl'>
