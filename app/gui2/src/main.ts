import 'enso-dashboard/src/tailwind.css'

const INITIAL_URL_KEY = `Enso-initial-url`

import * as dashboard from 'enso-authentication'
import { isMac } from 'lib0/environment'
import { decodeQueryParams } from 'lib0/url'

const params = decodeQueryParams(location.href)

// Temporary hardcode
const config = {
  supportsLocalBackend: true,
  supportsDeepLinks: isMac,
  shouldUseAuthentication: false,
  projectManagerUrl: PROJECT_MANAGER_URL,
  initialProjectName: params.project ?? null,
}

let unmount: null | (() => void) = null
let runRequested = false

export interface StringConfig {
  [key: string]: StringConfig | string
}

const vueAppEntry = import('./createApp')

async function runApp(config: StringConfig | null, accessToken: string | null, metadata?: object) {
  runRequested = true
  const { mountProjectApp } = await vueAppEntry
  if (runRequested) {
    unmount?.()
    const app = mountProjectApp({ config, accessToken, metadata })
    unmount = () => app.unmount()
  }
}

function stopApp() {
  runRequested = false
  unmount?.()
  unmount = null
}

const appRunner = { runApp, stopApp }

/** The entrypoint into the IDE. */
function main() {
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
  // const configOptions = OPTIONS.clone()
  if (isInAuthenticationFlow) {
    history.replaceState(null, '', authenticationUrl)
  } else {
    localStorage.setItem(INITIAL_URL_KEY, location.href)
  }
  dashboard.run({
    appRunner,
    logger: console,
    supportsLocalBackend: true, // TODO
    supportsDeepLinks: false, // TODO
    projectManagerUrl: config.projectManagerUrl,
    isAuthenticationDisabled: !config.shouldUseAuthentication,
    shouldShowDashboard: true,
    initialProjectName: config.initialProjectName,
    onAuthenticated() {
      if (isInAuthenticationFlow) {
        const initialUrl = localStorage.getItem(INITIAL_URL_KEY)
        if (initialUrl != null) {
          // This is not used past this point, however it is set to the initial URL
          // to make refreshing work as expected.
          history.replaceState(null, '', initialUrl)
        }
      }
    },
  })
}

main()
