/** @file Preload script containing code that runs before web page is loaded into the browser
 * window. It has access to both DOM APIs and Node environment, and is used to expose privileged
 * APIs to the renderer via the contextBridge API. To learn more, visit:
 * https://www.electronjs.org/docs/latest/tutorial/tutorial-preload. */

import * as electron from 'electron'

import * as ipc from 'ipc'

// =================
// === Constants ===
// =================

/** Name given to the {@link AUTHENTICATION_API} object, when it is exposed on the Electron main
 * window. */
const BACKEND_API_KEY = 'backendApi'
/** Name given to the {@link AUTHENTICATION_API} object, when it is exposed on the Electron main
 * window. */
const AUTHENTICATION_API_KEY = 'authenticationApi'

// =============================
// === importProjectFromPath ===
// =============================

const IMPORT_PROJECT_RESOLVE_FUNCTIONS = new Map<string, (projectId: string) => void>()

electron.contextBridge.exposeInMainWorld(BACKEND_API_KEY, {
    importProjectFromPath: (projectPath: string) => {
        electron.ipcRenderer.send(ipc.Channel.importProjectFromPath, projectPath)
        return new Promise<string>(resolve => {
            IMPORT_PROJECT_RESOLVE_FUNCTIONS.set(projectPath, resolve)
        })
    },
})

electron.ipcRenderer.on(
    ipc.Channel.importProjectFromPath,
    (_event, projectPath: string, projectId: string) => {
        const resolveFunction = IMPORT_PROJECT_RESOLVE_FUNCTIONS.get(projectPath)
        IMPORT_PROJECT_RESOLVE_FUNCTIONS.delete(projectPath)
        resolveFunction?.(projectId)
    }
)

// =======================
// === Debug Info APIs ===
// =======================

// These APIs expose functionality for use from Rust. See the bindings in the `debug_api` module for
// the primary documentation.

/** Shutdown-related commands and events. */
electron.contextBridge.exposeInMainWorld('enso_lifecycle', {
    /** Allow application-exit to be initiated from WASM code.
     * This is used, for example, in a key binding (Ctrl+Alt+Q) that saves a performance profile and
     * exits. */
    quit: () => {
        electron.ipcRenderer.send(ipc.Channel.quit)
    },
})

// Save and load profile data.
let onProfiles: ((profiles: string[]) => void)[] = []
let profilesLoaded: string[] | null

electron.ipcRenderer.on(ipc.Channel.profilesLoaded, (_event, profiles: string[]) => {
    for (const callback of onProfiles) {
        callback(profiles)
    }
    onProfiles = []
    profilesLoaded = profiles
})

electron.contextBridge.exposeInMainWorld('enso_profiling_data', {
    // Delivers profiling log.
    saveProfile: (data: unknown) => {
        electron.ipcRenderer.send(ipc.Channel.saveProfile, data)
    },
    // Requests any loaded profiling logs.
    loadProfiles: (callback: (profiles: string[]) => void) => {
        if (profilesLoaded == null) {
            electron.ipcRenderer.send('load-profiles')
            onProfiles.push(callback)
        } else {
            callback(profilesLoaded)
        }
    },
})

electron.contextBridge.exposeInMainWorld('enso_hardware_info', {
    // Open a page displaying GPU debug info.
    openGpuDebugInfo: () => {
        electron.ipcRenderer.send(ipc.Channel.openGpuDebugInfo)
    },
})

// Access to the system console that Electron was run from.
electron.contextBridge.exposeInMainWorld('enso_console', {
    // Print an error message with `console.error`.
    error: (data: unknown) => {
        electron.ipcRenderer.send('error', data)
    },
})

// ==========================
// === Authentication API ===
// ==========================

let currentDeepLinkHandler: ((event: Electron.IpcRendererEvent, url: string) => void) | null = null

/** Object exposed on the Electron main window; provides proxy functions to:
 * - open OAuth flows in the system browser, and
 * - handle deep links from the system browser or email client to the dashboard.
 *
 * Some functions (i.e., the functions to open URLs in the system browser) are not available in
 * sandboxed processes (i.e., the dashboard). So the
 * {@link electron.contextBridge.exposeInMainWorld} API is used to expose these functions.
 * The functions are exposed via this "API object", which is added to the main window.
 *
 * For more details, see:
 * https://www.electronjs.org/docs/latest/api/context-bridge#api-functions. */
const AUTHENTICATION_API = {
    /** Open a URL in the system browser (rather than in the app).
     *
     * OAuth URLs must be opened this way because the dashboard application is sandboxed and thus
     * not privileged to do so unless we explicitly expose this functionality. */
    openUrlInSystemBrowser: (url: string) => {
        electron.ipcRenderer.send(ipc.Channel.openUrlInSystemBrowser, url)
    },
    /** Set the callback that will be called when a deep link to the application is opened.
     *
     * The callback is intended to handle links like
     * `enso://authentication/register?code=...&state=...` from external sources like the user's
     * system browser or email client. Handling the links involves resuming whatever flow was in
     * progress when the link was opened (e.g., an OAuth registration flow). */
    setDeepLinkHandler: (callback: (url: string) => void) => {
        if (currentDeepLinkHandler != null) {
            electron.ipcRenderer.off(ipc.Channel.openDeepLink, currentDeepLinkHandler)
        }
        currentDeepLinkHandler = (_event, url: string) => {
            callback(url)
        }
        electron.ipcRenderer.on(ipc.Channel.openDeepLink, currentDeepLinkHandler)
    },
    /** Save the access token to a credentials file.
     *
     * The backend doesn't have access to Electron's `localStorage` so we need to save access token
     * to a file. Then the token will be used to sign cloud API requests. */
    saveAccessToken: (accessToken: string) => {
        electron.ipcRenderer.send(ipc.Channel.saveAccessToken, accessToken)
    },
}
electron.contextBridge.exposeInMainWorld(AUTHENTICATION_API_KEY, AUTHENTICATION_API)
