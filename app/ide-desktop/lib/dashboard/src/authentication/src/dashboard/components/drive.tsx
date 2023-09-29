/** @file The directory header bar and directory item listing. */
import * as React from 'react'

import * as common from 'enso-common'

import * as assetEventModule from '../events/assetEvent'
import * as assetListEventModule from '../events/assetListEvent'
import * as authProvider from '../../authentication/providers/auth'
import * as backendModule from '../backend'
import * as backendProvider from '../../providers/backend'
import * as hooks from '../../hooks'
import * as localStorageModule from '../localStorage'
import * as localStorageProvider from '../../providers/localStorage'

import * as app from '../../components/app'
import * as pageSwitcher from './pageSwitcher'
import CategorySwitcher, * as categorySwitcher from './categorySwitcher'
import AssetsTable from './assetsTable'
import DriveBar from './driveBar'

// =============
// === Drive ===
// =============

/** Props for a {@link Drive}. */
export interface DriveProps {
    page: pageSwitcher.Page
    hidden: boolean
    initialProjectName: string | null
    /** These events will be dispatched the next time the assets list is refreshed, rather than
     * immediately. */
    queuedAssetEvents: assetEventModule.AssetEvent[]
    assetListEvents: assetListEventModule.AssetListEvent[]
    dispatchAssetListEvent: (directoryEvent: assetListEventModule.AssetListEvent) => void
    assetEvents: assetEventModule.AssetEvent[]
    dispatchAssetEvent: (directoryEvent: assetEventModule.AssetEvent) => void
    query: string
    projectStartupInfo: backendModule.ProjectStartupInfo | null
    doCreateProject: (templateId: string | null) => void
    doOpenEditor: (
        project: backendModule.ProjectAsset,
        setProject: React.Dispatch<React.SetStateAction<backendModule.ProjectAsset>>,
        switchPage: boolean
    ) => void
    doCloseEditor: (project: backendModule.ProjectAsset) => void
    loadingProjectManagerDidFail: boolean
    isListingRemoteDirectoryWhileOffline: boolean
    isListingLocalDirectoryAndWillFail: boolean
    isListingRemoteDirectoryAndWillFail: boolean
}

/** Contains directory path and directory contents (projects, folders, secrets and files). */
export default function Drive(props: DriveProps) {
    const {
        page,
        hidden,
        initialProjectName,
        queuedAssetEvents,
        query,
        projectStartupInfo,
        assetListEvents,
        dispatchAssetListEvent,
        assetEvents,
        dispatchAssetEvent,
        doCreateProject,
        doOpenEditor,
        doCloseEditor,
        loadingProjectManagerDidFail,
        isListingRemoteDirectoryWhileOffline,
        isListingLocalDirectoryAndWillFail,
        isListingRemoteDirectoryAndWillFail,
    } = props
    const navigate = hooks.useNavigate()
    const { organization } = authProvider.useNonPartialUserSession()
    const { backend } = backendProvider.useBackend()
    const { localStorage } = localStorageProvider.useLocalStorage()
    const toastAndLog = hooks.useToastAndLog()
    const [isFileBeingDragged, setIsFileBeingDragged] = React.useState(false)
    const [category, setCategory] = React.useState(
        () =>
            localStorage.get(localStorageModule.LocalStorageKey.driveCategory) ??
            categorySwitcher.Category.home
    )

    React.useEffect(() => {
        const onBlur = () => {
            setIsFileBeingDragged(false)
        }
        window.addEventListener('blur', onBlur)
        return () => {
            window.removeEventListener('blur', onBlur)
        }
    }, [])

    const doUploadFiles = React.useCallback(
        (files: File[]) => {
            if (backend.type !== backendModule.BackendType.local && organization == null) {
                // This should never happen, however display a nice error message in case it does.
                toastAndLog('Files cannot be uploaded while offline')
            } else {
                dispatchAssetListEvent({
                    type: assetListEventModule.AssetListEventType.uploadFiles,
                    parentKey: null,
                    parentId: null,
                    files,
                })
            }
        },
        [backend, organization, toastAndLog, /* should never change */ dispatchAssetListEvent]
    )

    const doCreateDirectory = React.useCallback(() => {
        dispatchAssetListEvent({
            type: assetListEventModule.AssetListEventType.newFolder,
            parentKey: null,
            parentId: null,
        })
    }, [/* should never change */ dispatchAssetListEvent])

    React.useEffect(() => {
        const onDragEnter = (event: DragEvent) => {
            if (
                page === pageSwitcher.Page.drive &&
                event.dataTransfer?.types.includes('Files') === true
            ) {
                setIsFileBeingDragged(true)
            }
        }
        document.body.addEventListener('dragenter', onDragEnter)
        return () => {
            document.body.removeEventListener('dragenter', onDragEnter)
        }
    }, [page])

    return isListingRemoteDirectoryWhileOffline ? (
        <div className={`grow grid place-items-center mx-2 ${hidden ? 'hidden' : ''}`}>
            <div className="flex flex-col gap-4">
                <div className="text-base text-center">You are not signed in.</div>
                <button
                    className="text-base text-white bg-help rounded-full self-center leading-170 h-8 py-px w-16"
                    onClick={() => {
                        navigate(app.LOGIN_PATH)
                    }}
                >
                    Login
                </button>
            </div>
        </div>
    ) : isListingLocalDirectoryAndWillFail ? (
        <div className={`grow grid place-items-center mx-2 ${hidden ? 'hidden' : ''}`}>
            <div className="text-base text-center">
                Could not connect to the Project Manager. Please try restarting{' '}
                {common.PRODUCT_NAME}, or manually launching the Project Manager.
            </div>
        </div>
    ) : isListingRemoteDirectoryAndWillFail ? (
        <div className={`grow grid place-items-center mx-2 ${hidden ? 'hidden' : ''}`}>
            <div className="text-base text-center">
                We will review your user details and enable the cloud experience for you shortly.
            </div>
        </div>
    ) : (
        <div
            className={`flex flex-col flex-1 overflow-hidden gap-2.5 px-3.25 mt-8 ${
                hidden ? 'hidden' : ''
            }`}
        >
            <div className="flex flex-col self-start gap-3">
                <h1 className="text-xl font-bold h-9.5 pl-1.5">
                    {backend.type === backendModule.BackendType.remote
                        ? 'Cloud Drive'
                        : 'Local Drive'}
                </h1>
                <DriveBar
                    category={category}
                    doCreateProject={doCreateProject}
                    doUploadFiles={doUploadFiles}
                    doCreateDirectory={doCreateDirectory}
                    dispatchAssetEvent={dispatchAssetEvent}
                />
            </div>
            <div className="flex flex-1 gap-3 overflow-hidden">
                {backend.type === backendModule.BackendType.remote && (
                    <div className="flex flex-col gap-4 py-1">
                        <CategorySwitcher category={category} setCategory={setCategory} />
                    </div>
                )}
                <AssetsTable
                    query={query}
                    category={category}
                    initialProjectName={initialProjectName}
                    projectStartupInfo={projectStartupInfo}
                    queuedAssetEvents={queuedAssetEvents}
                    assetEvents={assetEvents}
                    dispatchAssetEvent={dispatchAssetEvent}
                    assetListEvents={assetListEvents}
                    dispatchAssetListEvent={dispatchAssetListEvent}
                    doOpenIde={doOpenEditor}
                    doCloseIde={doCloseEditor}
                    loadingProjectManagerDidFail={loadingProjectManagerDidFail}
                    isListingRemoteDirectoryWhileOffline={isListingRemoteDirectoryWhileOffline}
                    isListingLocalDirectoryAndWillFail={isListingLocalDirectoryAndWillFail}
                    isListingRemoteDirectoryAndWillFail={isListingRemoteDirectoryAndWillFail}
                />
            </div>
            {isFileBeingDragged &&
            organization != null &&
            backend.type === backendModule.BackendType.remote ? (
                <div
                    className="text-white text-lg fixed w-screen h-screen inset-0 opacity-0 hover:opacity-100 bg-primary bg-opacity-75 backdrop-blur-none hover:backdrop-blur-xs transition-all grid place-items-center"
                    onDragLeave={() => {
                        setIsFileBeingDragged(false)
                    }}
                    onDragOver={event => {
                        event.preventDefault()
                    }}
                    onDrop={event => {
                        event.preventDefault()
                        setIsFileBeingDragged(false)
                        dispatchAssetListEvent({
                            type: assetListEventModule.AssetListEventType.uploadFiles,
                            parentKey: null,
                            parentId: null,
                            files: Array.from(event.dataTransfer.files),
                        })
                    }}
                >
                    Drop to upload files.
                </div>
            ) : null}
        </div>
    )
}
