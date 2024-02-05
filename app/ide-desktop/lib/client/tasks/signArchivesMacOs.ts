/** @file This script signs the content of all archives that we have for macOS.
 * For this to work this needs to run on macOS with `codesign`, and a JDK installed.
 * `codesign` is needed to sign the files, while the JDK is needed for correct packing
 * and unpacking of java archives.
 *
 * We require this extra step as our dependencies contain files that require us
 * to re-sign jar contents that cannot be opened as pure zip archives,
 * but require a java toolchain to extract and re-assemble to preserve manifest information.
 * This functionality is not provided by `electron-osx-sign` out of the box.
 *
 * This code is based on https://github.com/electron/electron-osx-sign/pull/231
 * but our use-case is unlikely to be supported by `electron-osx-sign`
 * as it adds a java toolchain as additional dependency.
 * This script should be removed once the engine is signed. */

import * as childProcess from 'node:child_process'
import * as fs from 'node:fs/promises'
import * as os from 'node:os'
import * as pathModule from 'node:path'

import glob from 'fast-glob'

// ===============================================
// === Patterns of entities that need signing. ===
// ===============================================

/** Parts of the GraalVM distribution that need to be signed by us in an extra step. */
async function graalSignables(resourcesDir: string): Promise<Signable[]> {
    const archivePatterns: ArchivePattern[] = [
        [`Contents/Home/jmods/java.base.jmod`, ['bin/java', 'bin/keytool', 'lib/jspawnhelper']],
        [`Contents/Home/jmods/java.rmi.jmod`, ['bin/rmiregistry']],
        [`Contents/Home/jmods/java.scripting.jmod`, ['bin/jrunscript']],
        [`Contents/Home/jmods/jdk.compiler.jmod`, ['bin/javac', 'bin/serialver']],
        [`Contents/Home/jmods/jdk.hotspot.agent.jmod`, ['bin/jhsdb']],
        [`Contents/Home/jmods/jdk.httpserver.jmod`, ['bin/jwebserver']],
        [`Contents/Home/jmods/jdk.jartool.jmod`, ['bin/jarsigner', 'bin/jar']],
        [`Contents/Home/jmods/jdk.javadoc.jmod`, ['bin/javadoc']],
        [`Contents/Home/jmods/jdk.javadoc.jmod`, ['bin/javadoc']],
        [`Contents/Home/jmods/jdk.jconsole.jmod`, ['bin/jconsole']],
        [`Contents/Home/jmods/jdk.jdeps.jmod`, ['bin/javap', 'bin/jdeprscan', 'bin/jdeps']],
        [`Contents/Home/jmods/jdk.jdi.jmod`, ['bin/jdb']],
        [`Contents/Home/jmods/jdk.jfr.jmod`, ['bin/jfr']],
        [`Contents/Home/jmods/jdk.jlink.jmod`, ['bin/jmod', 'bin/jlink', 'bin/jimage']],
        [`Contents/Home/jmods/jdk.jshell.jmod`, ['bin/jshell']],
        [
            `Contents/Home/jmods/jdk.jpackage.jmod`,
            ['bin/jpackage', 'classes/jdk/jpackage/internal/resources/jpackageapplauncher'],
        ],
        [`Contents/Home/jmods/jdk.jstatd.jmod`, ['bin/jstatd']],
        [
            `Contents/Home/jmods/jdk.jcmd.jmod`,
            ['bin/jstack', 'bin/jcmd', 'bin/jps', 'bin/jmap', 'bin/jstat', 'bin/jinfo'],
        ],
    ]

    const binariesPatterns = [`Contents/MacOS/libjli.dylib`]

    // We use `*` for Graal versioned directory to not have to update this script on every GraalVM
    // update. Updates might still be needed when the list of binaries to sign changes.
    const graalDir = pathModule.join(resourcesDir, 'enso', 'runtime', '*')
    const archives = await ArchiveToSign.lookupMany(graalDir, archivePatterns)
    const binaries = await BinaryToSign.lookupMany(graalDir, binariesPatterns)
    return [...archives, ...binaries]
}

/** Parts of the Enso Engine distribution that need to be signed by us in an extra step. */
async function ensoPackageSignables(resourcesDir: string): Promise<Signable[]> {
    // Archives, and their content that need to be signed in an extra step. If a new archive is
    // added to the engine dependencies this also needs to be added here. If an archive is not added
    // here, it will show up as a failure to notarise the IDE. The offending archive will be named
    // in the error message provided by Apple and can then be added here.
    const engineDir = `${resourcesDir}/enso/dist/*`
    const archivePatterns: ArchivePattern[] = [
        [
            `/component/runner/runner.jar`,
            [
                'org/sqlite/native/Mac/x86_64/libsqlitejdbc.jnilib',
                'org/sqlite/native/Mac/aarch64/libsqlitejdbc.jnilib',
                'com/sun/jna/darwin-aarch64/libjnidispatch.jnilib',
                'com/sun/jna/darwin-x86-64/libjnidispatch.jnilib',
            ],
        ],
        [
            'component/python-resources-23.1.0.jar',
            [
                'META-INF/resources/darwin/*/lib/graalpy23.1/*.dylib',
                'META-INF/resources/darwin/*/lib/graalpy23.1/modules/*.so',
            ],
        ],
        [
            `component/truffle-nfi-libffi-23.1.0.jar`,
            ['META-INF/resources/nfi-native/libnfi/darwin/*/bin/libtrufflenfi.dylib'],
        ],
        [
            `component/truffle-runtime-23.1.0.jar`,
            [
                'META-INF/resources/engine/libtruffleattach/darwin/amd64/bin/libtruffleattach.dylib',
                'META-INF/resources/engine/libtruffleattach/darwin/aarch64/bin/libtruffleattach.dylib',
            ],
        ],
        [
            `lib/Standard/Database/*/polyglot/java/sqlite-jdbc-*.jar`,
            [
                'org/sqlite/native/Mac/aarch64/libsqlitejdbc.jnilib',
                'org/sqlite/native/Mac/x86_64/libsqlitejdbc.jnilib',
            ],
        ],
    ]
    return ArchiveToSign.lookupMany(engineDir, archivePatterns)
}

// ================
// === Signing. ===
// ================

/** Information we need to sign a given binary. */
interface SigningContext {
    /** A digital identity that is stored in a keychain that is on the calling user's keychain
     * search list. We rely on this already being set up by the Electron Builder. */
    identity: string
    /** Path to the entitlements file. */
    entitlements: string
}

/** An entity that we want to sign. */
interface Signable {
    /** Sign this entity. */
    sign: (context: SigningContext) => Promise<void>
}

/** Placeholder name for temporary archives. */
const TEMPORARY_ARCHIVE_PATH = 'temporary_archive.zip'

/** Helper to execute a program in a given directory and return the output. */
function run(cmd: string, args: string[], cwd?: string) {
    console.log('Running', cmd, args, cwd)
    return childProcess.execFileSync(cmd, args, { cwd }).toString()
}

/** Archive with some binaries that we want to sign.
 *
 * Can be either a zip or a jar file. */
class ArchiveToSign implements Signable {
    /** Looks up for archives to sign using the given path patterns. */
    static lookupMany = lookupManyHelper(ArchiveToSign.lookup.bind(this))

    /** Create a new instance. */
    constructor(
        /** An absolute path to the archive. */
        public path: string,
        /** A list of patterns for files to sign inside the archive.
         * Relative to the root of the archive. */
        public binaries: glob.Pattern[]
    ) {}

    /** Looks up for archives to sign using the given path pattern. */
    static async lookup(base: string, [pattern, binaries]: ArchivePattern) {
        return lookupHelper(path => new ArchiveToSign(path, binaries))(base, pattern)
    }

    /** Sign content of an archive. This function extracts the archive, signs the required files,
     * re-packages the archive and replaces the original. */
    async sign(context: SigningContext) {
        console.log(`Signing archive ${this.path}`)
        const archiveName = pathModule.basename(this.path)
        const workingDir = await getTmpDir()
        try {
            const isJar = archiveName.endsWith(`jar`)

            if (isJar) {
                run(`jar`, ['xf', this.path], workingDir)
            } else {
                // We cannot use `unzip` here because of the following issue:
                // https://unix.stackexchange.com/questions/115825/
                // This started to be an issue with GraalVM 22.3.0 release.
                run(`7za`, ['X', `-o${workingDir}`, this.path])
            }

            const binariesToSign = await BinaryToSign.lookupMany(workingDir, this.binaries)
            for (const binaryToSign of binariesToSign) {
                void binaryToSign.sign(context)
            }

            if (isJar) {
                if (archiveName.includes(`runner`)) {
                    run(
                        `jar`,
                        ['-cfm', TEMPORARY_ARCHIVE_PATH, 'META-INF/MANIFEST.MF', '.'],
                        workingDir
                    )
                } else {
                    run(`jar`, ['-cf', TEMPORARY_ARCHIVE_PATH, '.'], workingDir)
                }
            } else {
                run(`zip`, ['-rm', TEMPORARY_ARCHIVE_PATH, '.'], workingDir)
            }

            // We cannot use fs.rename because temp and target might be on different volumes.
            console.log(
                run(`/bin/mv`, [pathModule.join(workingDir, TEMPORARY_ARCHIVE_PATH), this.path])
            )
            console.log(
                `Successfully repacked ${this.path} to handle signing inner native dependency.`
            )
            return
        } catch (error) {
            console.error(
                `Could not repackage ${archiveName}. Please check the ${import.meta.url} task to ` +
                    `ensure that it's working. This jar has to be treated specially` +
                    ` because it has a native library and Apple's codesign does not sign inner ` +
                    `native libraries correctly for jar files.`
            )
            throw error
        } finally {
            await rmRf(workingDir)
        }
    }
}

/** A single code binary file to be signed. */
class BinaryToSign implements Signable {
    /** Looks up for binaries to sign using the given path pattern. */
    static lookup = lookupHelper(path => new BinaryToSign(path))

    /** Looks up for binaries to sign using the given path patterns. */
    static lookupMany = lookupManyHelper(BinaryToSign.lookup)

    /** Create a new instance. */
    constructor(
        /** An absolute path to the binary. */
        public path: string
    ) {}

    /** Sign this binary. */
    async sign({ entitlements, identity }: SigningContext) {
        console.log(`Signing ${this.path}`)
        run(`codesign`, [
            '-vvv',
            '--entitlements',
            entitlements,
            '--force',
            '--options=runtime',
            '--sign',
            identity,
            this.path,
        ])
        // Async functions should contain await.
        await Promise.resolve()
    }
}

// ==============================
// === Discovering Signables. ===
// ==============================

/** Helper used to concisely define patterns for an archive to sign.
 *
 * Consists of pattern of the archive path
 * and set of patterns for files to sign inside the archive. */
type ArchivePattern = [glob.Pattern, glob.Pattern[]]

/** Like `glob` but returns absolute paths by default. */
async function globAbsolute(pattern: glob.Pattern, options?: glob.Options): Promise<string[]> {
    const paths = await glob(pattern, { absolute: true, ...options })
    return paths
}

/** Glob patterns relative to a given base directory. The base directory is allowed to be a pattern
 * as well. */
async function globAbsoluteIn(
    base: glob.Pattern,
    pattern: glob.Pattern,
    options?: glob.Options
): Promise<string[]> {
    return globAbsolute(pathModule.join(base, pattern), options)
}

/** Generate a lookup function for a given Signable type. */
function lookupHelper<R extends Signable>(mapper: (path: string) => R) {
    return async (base: string, pattern: glob.Pattern) => {
        const paths = await globAbsoluteIn(base, pattern)
        return paths.map(mapper)
    }
}

/** Generate a lookup function for a given Signable type. */
function lookupManyHelper<T, R extends Signable>(
    lookup: (base: string, pattern: T) => Promise<R[]>
) {
    return async function (base: string, patterns: T[]) {
        const results = await Promise.all(
            patterns.map(async pattern => {
                const ret = await lookup(base, pattern)
                if (ret.length === 0) {
                    console.warn(`No files found for pattern ${String(pattern)} in ${base}`)
                }
                return ret
            })
        )
        return results.flat()
    }
}

// ==================
// === Utilities. ===
// ==================

/** Remove file recursively. */
async function rmRf(path: string) {
    await fs.rm(path, { recursive: true, force: true })
}

/** Get a new temporary directory. Caller is responsible for cleaning up the directory. */
async function getTmpDir(prefix?: string) {
    return await fs.mkdtemp(pathModule.join(os.tmpdir(), prefix ?? 'enso-signing-'))
}

// ====================
// === Entry point. ===
// ====================

/** Input for this script. */
interface Input extends SigningContext {
    appOutDir: string
    productFilename: string
}

/** Entry point, meant to be used from an afterSign Electron Builder's hook. */
export default async function (context: Input) {
    console.log('Environment: ', process.env)
    const { appOutDir, productFilename } = context
    const appDir = pathModule.join(appOutDir, `${productFilename}.app`)
    const contentsDir = pathModule.join(appDir, 'Contents')
    const resourcesDir = pathModule.join(contentsDir, 'Resources')

    // Sign archives.
    console.log('Signing GraalVM elemenets...')
    for (const signable of await graalSignables(resourcesDir)) await signable.sign(context)

    console.log('Signing Engine elements...')
    for (const signable of await ensoPackageSignables(resourcesDir)) await signable.sign(context)

    // Finally re-sign the top-level enso.
    const topLevelExecutable = new BinaryToSign(
        pathModule.join(contentsDir, 'MacOS', productFilename)
    )
    await topLevelExecutable.sign(context)
}
