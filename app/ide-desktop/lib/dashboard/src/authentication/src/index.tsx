/** @file Authentication module used by Enso IDE & Cloud.
 *
 * This module declares the main DOM structure for the authentication/dashboard app. */
import * as React from 'react'
import * as reactDOM from 'react-dom/client'

import * as detect from 'enso-common/src/detect'

import type * as app from './components/app'
import App from './components/app'

// =================
// === Constants ===
// =================

/** The `id` attribute of the root element that the app will be rendered into. */
const ROOT_ELEMENT_ID = 'enso-dashboard'

// ===========
// === run ===
// ===========

/** Entrypoint for the authentication/dashboard app.
 *
 * Running this function finds a `div` element with the ID `dashboard`, and renders the
 * authentication/dashboard UI using React. It also handles routing and other interactions (e.g.,
 * for redirecting the user to/from the login page). */
export // This export declaration must be broken up to satisfy the `require-jsdoc` rule.
// This is not a React component even though it contains JSX.
// eslint-disable-next-line no-restricted-syntax
function run(props: app.AppProps) {
    const { logger, supportsDeepLinks } = props
    logger.log('Starting authentication/dashboard UI.')
    /** The root element into which the authentication/dashboard app will be rendered. */
    const root = document.getElementById(ROOT_ELEMENT_ID)
    if (root == null) {
        logger.error(`Could not find root element with ID '${ROOT_ELEMENT_ID}'.`)
    } else {
        // `supportsDeepLinks` will be incorrect when accessing the installed Electron app's pages
        // via the browser.
        const actuallySupportsDeepLinks = supportsDeepLinks && detect.isOnElectron()
        reactDOM.createRoot(root).render(
            detect.IS_DEV_MODE ? (
                <React.StrictMode>
                    <App {...props} />
                </React.StrictMode>
            ) : (
                <App {...props} supportsDeepLinks={actuallySupportsDeepLinks} />
            )
        )
    }
}

/** Global configuration for the {@link App} component. */
export type AppProps = app.AppProps
