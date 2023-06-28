/** @file This module is responsible for loading the WASM binary, its dependencies, and providing
 * the user with a visual representation of this process (welcome screen). It also implements a view
 * allowing to choose a debug rendering test from. */

import * as semver from 'semver'

import * as common from 'enso-common'
import * as contentConfig from 'enso-content-config'
import * as dashboard from 'enso-authentication'
import * as detect from 'enso-common/src/detect'

import * as app from '../../../../../target/ensogl-pack/linked-dist'
import GLOBAL_CONFIG from '../../../../gui/config.yaml' assert { type: 'yaml' }

const logger = app.log.logger

// =================
// === Constants ===
// =================

/** The name of the `localStorage` key storing the initial URL of the app. */
const INITIAL_URL_KEY = `${common.PRODUCT_NAME.toLowerCase()}-initial-url`
/** Path to the SSE endpoint over which esbuild sends events. */
const ESBUILD_PATH = '/esbuild'
/** SSE event indicating a build has finished. */
const ESBUILD_EVENT_NAME = 'change'
/** Path to the serice worker that caches assets for offline usage.
 * In development, it also resolves all extensionless paths to `/index.html`.
 * This is required for client-side routing to work when doing `./run gui watch`.
 */
const SERVICE_WORKER_PATH = '/serviceWorker.js'
/** One second in milliseconds. */
const SECOND = 1000
/** Time in seconds after which a `fetchTimeout` ends. */
const FETCH_TIMEOUT = 300

// ===================
// === Live reload ===
// ===================

if (IS_DEV_MODE && !detect.isRunningInElectron()) {
    new EventSource(ESBUILD_PATH).addEventListener(ESBUILD_EVENT_NAME, () => {
        // This acts like `location.reload`, but it preserves the query-string.
        // The `toString()` is to bypass a lint without using a comment.
        location.href = location.href.toString()
    })
}
void navigator.serviceWorker.register(SERVICE_WORKER_PATH)

// =============
// === Fetch ===
// =============

/** Returns an `AbortController` that aborts after the specified number of seconds. */
function timeout(timeSeconds: number) {
    const controller = new AbortController()
    setTimeout(() => {
        controller.abort()
    }, timeSeconds * SECOND)
    return controller
}

/** A version of `fetch` which times out after the provided time. */
async function fetchTimeout(url: string, timeoutSeconds: number): Promise<unknown> {
    return fetch(url, { signal: timeout(timeoutSeconds).signal }).then(response => {
        const statusCodeOK = 200
        if (response.status === statusCodeOK) {
            return response.json()
        } else {
            throw new Error(`Failed to fetch '${url}'. Response status: ${response.status}.`)
        }
    })
}

/** Return `true` if the current application version is still supported and `false` otherwise.
 *
 * Function downloads the application config containing the minimum supported version from GitHub
 * and compares it with the version of the `client` js package. When the function is unable to
 * download the application config, or one of the compared versions does not match the semver
 * scheme, it returns `true`. */
async function checkMinSupportedVersion(config: typeof contentConfig.OPTIONS) {
    let supported = false
    if (config.groups.engine.options.skipMinVersionCheck.value) {
        supported = true
    } else {
        try {
            const appConfig = await fetchTimeout(
                config.groups.engine.options.configUrl.value,
                FETCH_TIMEOUT
            )
            if (
                typeof appConfig === 'object' &&
                appConfig != null &&
                'minimumSupportedVersion' in appConfig
            ) {
                const minSupportedVersion = appConfig.minimumSupportedVersion
                if (typeof minSupportedVersion !== 'string') {
                    logger.error('The minimum supported version is not a string.')
                } else {
                    const comparator = new semver.Comparator(`>=${minSupportedVersion}`)
                    supported = comparator.test(contentConfig.VERSION.ide)
                }
            } else {
                logger.error('The application config is not an object.')
            }
        } catch (e) {
            console.error('Minimum version check failed.', e)
            supported = true
        }
    }
    return supported
}

/** Display information that the current app version is deprecated. */
function displayDeprecatedVersionDialog() {
    const versionCheckText = document.createTextNode(
        'This version is no longer supported. Please download a new one.'
    )

    const root = document.getElementById('root')
    const versionCheckDiv = document.createElement('div')
    versionCheckDiv.id = 'version-check'
    versionCheckDiv.className = 'auth-info'
    versionCheckDiv.style.display = 'block'
    versionCheckDiv.appendChild(versionCheckText)
    if (root == null) {
        console.error('Cannot find the root DOM element.')
    } else {
        root.appendChild(versionCheckDiv)
    }
}

// ========================
// === Main entry point ===
// ========================

/** Nested configuration options with `string` values. */
interface StringConfig {
    [key: string]: StringConfig | string
}

/** Configuration options for the authentication flow and dashboard. */
interface AuthenticationConfig {
    isInAuthenticationFlow: boolean
    shouldUseAuthentication: boolean
    shouldUseNewDashboard: boolean
    initialProjectName: string | null
    inputConfig: StringConfig | null
}

/** Contains the entrypoint into the IDE. */
class Main implements AppRunner {
    app: app.App | null = null

    /** Stop an app instance, if one is running. */
    stopApp() {
        this.app?.stop()
    }

    /** Run an app instance with the specified configuration.
     * This includes the scene to run and the WebSocket endpoints to the backend. */
    async runApp(inputConfig?: StringConfig | null) {
        this.stopApp()

        /** FIXME: https://github.com/enso-org/enso/issues/6475
         * Default values names are out of sync with values used in code.
         * Rather than setting fixed values here we need to fix default values in config. */
        const config = Object.assign(
            {
                loader: {
                    wasmUrl: 'pkg-opt.wasm',
                },
            },
            inputConfig
        )

        this.app = new app.App({
            config,
            configOptions: contentConfig.OPTIONS,
            packageInfo: {
                version: BUILD_INFO.version,
                engineVersion: BUILD_INFO.engineVersion,
            },
        })

        if (!this.app.initialized) {
            console.error('Failed to initialize the application.')
        } else {
            if (contentConfig.OPTIONS.options.dataCollection.value) {
                // TODO: Add remote-logging here.
            }
            if (!(await checkMinSupportedVersion(contentConfig.OPTIONS))) {
                displayDeprecatedVersionDialog()
            } else {
                const email = contentConfig.OPTIONS.groups.authentication.options.email.value
                // The default value is `""`, so a truthiness check is most appropriate here.
                if (email) {
                    logger.log(`User identified as '${email}'.`)
                }
                void this.app.run()
            }
        }
    }

    /** The entrypoint into the IDE. */
    main(inputConfig?: StringConfig) {
        /** Note: Signing out always redirects to `/`. It is impossible to make this work,
         * as it is not possible to distinguish between having just logged out, and explicitly
         * opening a page with no URL parameters set.
         *
         * Client-side routing endpoints are explicitly not supported for live-reload, as they are
         * transitional pages that should not need live-reload when running `gui watch`. */
        const url = new URL(location.href)
        const isInAuthenticationFlow = url.searchParams.has('code') && url.searchParams.has('state')
        const authenticationUrl = location.href
        if (isInAuthenticationFlow) {
            history.replaceState(null, '', localStorage.getItem(INITIAL_URL_KEY))
        }
        const parseOk = contentConfig.OPTIONS.loadAllAndDisplayHelpIfUnsuccessful([app.urlParams()])
        if (isInAuthenticationFlow) {
            history.replaceState(null, '', authenticationUrl)
        } else {
            localStorage.setItem(INITIAL_URL_KEY, location.href)
        }
        if (parseOk) {
            const shouldUseAuthentication = contentConfig.OPTIONS.options.authentication.value
            const shouldUseNewDashboard =
                contentConfig.OPTIONS.groups.featurePreview.options.newDashboard.value
            const isOpeningMainEntryPoint =
                contentConfig.OPTIONS.groups.startup.options.entry.value ===
                contentConfig.OPTIONS.groups.startup.options.entry.default
            const initialProjectName =
                contentConfig.OPTIONS.groups.startup.options.project.value || null
            // This MUST be removed as it would otherwise override the `startup.project` passed
            // explicitly in `ide.tsx`.
            if (isOpeningMainEntryPoint && url.searchParams.has('startup.project')) {
                url.searchParams.delete('startup.project')
                history.replaceState(null, '', url.toString())
            }
            if ((shouldUseAuthentication || shouldUseNewDashboard) && isOpeningMainEntryPoint) {
                this.runAuthentication({
                    isInAuthenticationFlow,
                    shouldUseAuthentication,
                    shouldUseNewDashboard,
                    initialProjectName,
                    inputConfig: inputConfig ?? null,
                })
            } else {
                void this.runApp(inputConfig)
            }
        }
    }

    /** Begins the authentication UI flow. */
    runAuthentication(config: AuthenticationConfig) {
        /** TODO [NP]: https://github.com/enso-org/cloud-v2/issues/345
         * `content` and `dashboard` packages **MUST BE MERGED INTO ONE**. The IDE
         * should only have one entry point. Right now, we have two. One for the cloud
         * and one for the desktop. */
        /** FIXME [PB]: https://github.com/enso-org/cloud-v2/issues/366
         * React hooks rerender themselves multiple times. It is resulting in multiple
         * Enso main scene being initialized. As a temporary workaround we check whether
         * appInstance was already ran. Target solution should move running appInstance
         * where it will be called only once. */
        dashboard.run({
            appRunner: this,
            logger,
            supportsLocalBackend: SUPPORTS_LOCAL_BACKEND,
            supportsDeepLinks: SUPPORTS_DEEP_LINKS,
            isAuthenticationDisabled: !config.shouldUseAuthentication,
            shouldShowDashboard: config.shouldUseNewDashboard,
            initialProjectName: config.initialProjectName,
            onAuthenticated: () => {
                if (config.isInAuthenticationFlow) {
                    const initialUrl = localStorage.getItem(INITIAL_URL_KEY)
                    if (initialUrl != null) {
                        // This is not used past this point, however it is set to the initial URL
                        // to make refreshing work as expected.
                        history.replaceState(null, '', initialUrl)
                    }
                }
                if (!config.shouldUseNewDashboard) {
                    document.getElementById('enso-dashboard')?.remove()
                    const ideElement = document.getElementById('root')
                    if (ideElement) {
                        ideElement.style.top = ''
                        ideElement.style.display = ''
                    }
                    if (this.app == null) {
                        void this.runApp(config.inputConfig)
                    }
                }
            },
        })
    }
}

// @ts-expect-error `globalConfig.windowAppScopeName` is not known at typecheck time.
// eslint-disable-next-line @typescript-eslint/no-unsafe-member-access
window[GLOBAL_CONFIG.windowAppScopeName] = new Main()
