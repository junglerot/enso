/** @file This module defines a TS script that is responsible for invoking the Electron Builder
 * process to bundle the entire IDE distribution.
 *
 * There are two areas to this:
 * - Parsing CLI options as per our needs.
 * - The default configuration of the build process. */
/** @module */

import path from 'node:path'
import child_process from 'node:child_process'
import fs from 'node:fs/promises'
import { CliOptions, Configuration, LinuxTargetSpecificOptions, Platform } from 'electron-builder'
import builder from 'electron-builder'
import { notarize } from 'electron-notarize'
import signArchivesMacOs from './tasks/signArchivesMacOs.js'

import { project_manager_bundle } from './paths.js'
import * as shared from './shared.js'
import build from '../../build.json' assert { type: 'json' }
import yargs from 'yargs'
import { MacOsTargetName } from 'app-builder-lib/out/options/macOptions'

/** The parts of the electron-builder configuration that we want to keep configurable.
 *
 * @see `args` definition below for fields description.
 * */
export interface Arguments {
    target?: string
    iconsDist: string
    guiDist: string
    ideDist: string
    projectManagerDist: string
    platform: Platform
}

/** CLI argument parser (with support for environment variables) that provides the necessary options. */
export const args: Arguments = await yargs(process.argv.slice(2))
    .env('ENSO_BUILD')
    .option({
        ideDist: {
            // Alias here (and subsequent occurrences) are for the environment variable name.
            alias: 'ide',
            type: 'string',
            description: 'Output directory for IDE',
            demandOption: true,
        },
        guiDist: {
            alias: 'gui',
            type: 'string',
            description: 'Output directory with GUI',
            demandOption: true,
        },
        iconsDist: {
            alias: 'icons',
            type: 'string',
            description: 'Output directory with icons',
            demandOption: true,
        },
        projectManagerDist: {
            alias: 'project-manager',
            type: 'string',
            description: 'Output directory with project manager',
            demandOption: true,
        },
        platform: {
            type: 'string',
            description: 'Platform that Electron Builder should target',
            default: Platform.current().toString(),
            coerce: (p: string) => Platform.fromString(p),
        },
        target: {
            type: 'string',
            description: 'Overwrite the platform-default target',
        },
    }).argv

/** Based on the given arguments, creates a configuration for the Electron Builder. */
export function createElectronBuilderConfig(args: Arguments): Configuration {
    return {
        appId: 'org.enso',
        productName: shared.PRODUCT_NAME,
        extraMetadata: {
            version: build.version,
        },
        copyright: 'Copyright © 2022 ${author}.',
        artifactName: 'enso-${os}-${version}.${ext}',
        /** Definitions of URL {@link builder.Protocol} schemes used by the IDE.
         *
         * Electron will register all URL protocol schemes defined here with the OS. Once a URL protocol
         * scheme is registered with the OS, any links using that scheme will function as "deep links".
         * Deep links are used to redirect the user from external sources (e.g., system web browser,
         * email client) to the IDE.
         *
         * Clicking a deep link will:
         * - open the IDE (if it is not already open),
         * - focus the IDE, and
         * - navigate to the location specified by the URL of the deep link.
         *
         * For details on how this works, see:
         * https://www.electronjs.org/docs/latest/tutorial/launch-app-from-url-in-another-app */
        protocols: [
            /** Electron URL protocol scheme definition for deep links to authentication flow pages. */
            {
                name: `${shared.PRODUCT_NAME} url`,
                schemes: [shared.DEEP_LINK_SCHEME],
                role: 'Editor',
            },
        ],
        mac: {
            // We do not use compression as the build time is huge and file size saving is almost zero.
            target: (args.target as MacOsTargetName) ?? 'dmg',
            icon: `${args.iconsDist}/icon.icns`,
            category: 'public.app-category.developer-tools',
            darkModeSupport: true,
            type: 'distribution',
            // The following settings are required for macOS signing and notarisation.
            // The hardened runtime is required to be able to notarise the application.
            hardenedRuntime: true,
            // This is a custom check that is not working correctly, so we disable it. See for more
            // details https://kilianvalkhof.com/2019/electron/notarizing-your-electron-application/
            gatekeeperAssess: false,
            // Location of the entitlements files with the entitlements we need to run our application
            // in the hardened runtime.
            entitlements: './entitlements.mac.plist',
            entitlementsInherit: './entitlements.mac.plist',
        },
        win: {
            // We do not use compression as the build time is huge and file size saving is almost zero.
            target: args.target ?? 'nsis',
            icon: `${args.iconsDist}/icon.ico`,
        },
        linux: {
            // We do not use compression as the build time is huge and file size saving is almost zero.
            target: args.target ?? 'AppImage',
            icon: `${args.iconsDist}/png`,
            category: 'Development',
        },
        files: [
            '!**/node_modules/**/*',
            { from: `${args.guiDist}/`, to: '.' },
            { from: `${args.ideDist}/client`, to: '.' },
        ],
        extraResources: [
            {
                from: `${args.projectManagerDist}/`,
                to: project_manager_bundle,
                filter: ['!**.tar.gz', '!**.zip'],
            },
        ],
        fileAssociations: [
            {
                ext: 'enso',
                name: 'Enso Source File',
                role: 'Editor',
            },
        ],
        directories: {
            output: `${args.ideDist}`,
        },
        nsis: {
            // Disables "block map" generation during electron building. Block maps
            // can be used for incremental package update on client-side. However,
            // their generation can take long time (even 30 mins), so we removed it
            // for now. Moreover, we may probably never need them, as our updates
            // are handled by us. More info:
            // https://github.com/electron-userland/electron-builder/issues/2851
            // https://github.com/electron-userland/electron-builder/issues/2900
            differentialPackage: false,
        },
        dmg: {
            // Disables "block map" generation during electron building. Block maps
            // can be used for incremental package update on client-side. However,
            // their generation can take long time (even 30 mins), so we removed it
            // for now. Moreover, we may probably never need them, as our updates
            // are handled by us. More info:
            // https://github.com/electron-userland/electron-builder/issues/2851
            // https://github.com/electron-userland/electron-builder/issues/2900
            writeUpdateInfo: false,
            // Disable code signing of the final dmg as this triggers an issue
            // with Apple’s Gatekeeper. Since the DMG contains a signed and
            // notarised application it will still be detected as trusted.
            // For more details see step (4) at
            // https://kilianvalkhof.com/2019/electron/notarizing-your-electron-application/
            sign: false,
        },
        afterAllArtifactBuild: path.join('tasks', 'computeHashes.cjs'),

        afterPack: ctx => {
            if (args.platform === Platform.MAC) {
                // Make the subtree writable, so we can sign the binaries.
                // This is needed because GraalVM distribution comes with read-only binaries.
                child_process.execFileSync('chmod', ['-R', 'u+w', ctx.appOutDir])
            }
        },

        afterSign: async context => {
            // Notarization for macOS.
            if (args.platform === Platform.MAC && process.env['CSC_LINK']) {
                const { packager, appOutDir } = context
                const appName = packager.appInfo.productFilename

                // We need to manually re-sign our build artifacts before notarization.
                console.log('  • Performing additional signing of dependencies.')
                await signArchivesMacOs({
                    appOutDir: appOutDir,
                    productFilename: appName,
                    // @ts-ignore
                    entitlements: context.packager.config.mac.entitlements,
                    identity: 'Developer ID Application: New Byte Order Sp. z o. o. (NM77WTZJFQ)',
                })

                console.log('  • Notarizing.')
                await notarize({
                    appBundleId: packager.platformSpecificBuildOptions.appId,
                    appPath: `${appOutDir}/${appName}.app`,
                    // @ts-ignore
                    appleId: process.env.APPLEID,
                    // @ts-ignore
                    appleIdPassword: process.env.APPLEIDPASS,
                })
            }
        },

        publish: null,
    }
}

/** Build the IDE package with Electron Builder. */
export async function buildPackage(args: Arguments) {
    // `electron-builder` checks for presence of `node_modules` directory. If it is not present, it will
    // install dependencies with `--production` flag (erasing all dev-only dependencies). This does not
    // work sensibly with NPM workspaces. We have our `node_modules` in the root directory, not here.
    //
    // Without this workaround, `electron-builder` will end up erasing its own dependencies and failing
    // because of that.
    await fs.mkdir('node_modules', { recursive: true })

    const cli_opts: CliOptions = {
        config: createElectronBuilderConfig(args),
        targets: args.platform.createTarget(),
    }
    console.log('Building with configuration:', cli_opts)
    const result = await builder.build(cli_opts)
    console.log('Electron Builder is done. Result:', result)
}
