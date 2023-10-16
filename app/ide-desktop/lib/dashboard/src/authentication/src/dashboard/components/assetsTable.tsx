/** @file Table displaying a list of projects. */
import * as React from 'react'
import * as toast from 'react-toastify'

import * as array from '../array'
import * as assetEventModule from '../events/assetEvent'
import * as assetListEventModule from '../events/assetListEvent'
import * as assetTreeNode from '../assetTreeNode'
import * as backendModule from '../backend'
import * as columnModule from '../column'
import * as dateTime from '../dateTime'
import * as hooks from '../../hooks'
import * as localStorageModule from '../localStorage'
import * as localStorageProvider from '../../providers/localStorage'
import * as permissions from '../permissions'
import * as shortcutsModule from '../shortcuts'
import * as shortcutsProvider from '../../providers/shortcuts'
import * as sorting from '../sorting'
import * as string from '../../string'
import * as style from '../style'
import * as uniqueString from '../../uniqueString'
import type * as visibilityModule from '../visibility'

import * as authProvider from '../../authentication/providers/auth'
import * as backendProvider from '../../providers/backend'
import * as modalProvider from '../../providers/modal'

import * as categorySwitcher from './categorySwitcher'
import AssetNameColumn from './assetNameColumn'
import AssetRow from './assetRow'
import Button from './button'
import ConfirmDeleteModal from './confirmDeleteModal'
import ContextMenu from './contextMenu'
import ContextMenus from './contextMenus'
import DragModal from './dragModal'
import GlobalContextMenu from './globalContextMenu'
import MenuEntry from './menuEntry'
import Table from './table'

// =================
// === Constants ===
// =================

/** The number of pixels the header bar should shrink when the extra tab selector is visible. */
const TABLE_HEADER_WIDTH_SHRINKAGE_PX = 274
/** A value that represents that the first argument is less than the second argument, in a
 * sorting function. */
const COMPARE_LESS_THAN = -1
/** The user-facing name of this asset type. */
const ASSET_TYPE_NAME = 'item'
/** The user-facing plural name of this asset type. */
const ASSET_TYPE_NAME_PLURAL = 'items'
// This is a function, even though it is not syntactically a function.
// eslint-disable-next-line no-restricted-syntax
const pluralize = string.makePluralize(ASSET_TYPE_NAME, ASSET_TYPE_NAME_PLURAL)
/** The default placeholder row. */
const PLACEHOLDER = (
    <span className="opacity-75">
        You have no projects yet. Go ahead and create one using the button above, or open a template
        from the home screen.
    </span>
)
/** The placeholder row for the Trash category. */
const TRASH_PLACEHOLDER = <span className="opacity-75 px-1.5">Your trash is empty.</span>
/** Placeholder row for directories that are empty. */
export const EMPTY_DIRECTORY_PLACEHOLDER = (
    <span className="px-2 opacity-75">This folder is empty.</span>
)

/** The {@link RegExp} matching a directory name following the default naming convention. */
const DIRECTORY_NAME_REGEX = /^New_Folder_(?<directoryIndex>\d+)$/
/** The default prefix of an automatically generated directory. */
const DIRECTORY_NAME_DEFAULT_PREFIX = 'New_Folder_'

// ============================
// === AssetRowsDragPayload ===
// ============================

const ASSET_ROWS_DRAG_PAYLOAD_MIMETYPE = 'application/x-enso-asset-list'
const ASSET_ROWS_DRAG_PAYLOAD_MIMETYPE_REGEX = new RegExp(
    '^' + ASSET_ROWS_DRAG_PAYLOAD_MIMETYPE + '; id=(.+)$'
)
const ASSET_ROWS_DRAG_PAYLOAD_MAP = new Map<string, AssetRowsDragPayload>()

/** Resolve to an {@link AssetRowsDragPayload}, if any, else resolve to `null`. */
export function tryGetAssetRowsDragPayload(dataTransfer: DataTransfer) {
    const item = Array.from(dataTransfer.items).find(dataTransferItem =>
        dataTransferItem.type.startsWith(ASSET_ROWS_DRAG_PAYLOAD_MIMETYPE)
    )
    const id = item?.type.match(ASSET_ROWS_DRAG_PAYLOAD_MIMETYPE_REGEX)?.[1] ?? null
    return id != null ? ASSET_ROWS_DRAG_PAYLOAD_MAP.get(id) ?? null : null
}

/** Metadata for an asset row. */
interface AssetRowsDragPayloadItem {
    key: backendModule.AssetId
    asset: backendModule.AnyAsset
}

/** Data for a {@link DragEvent} started from an {@link AssetsTable}. */
export type AssetRowsDragPayload = AssetRowsDragPayloadItem[]

// ===================================
// === insertAssetTreeNodeChildren ===
// ===================================

/** Return a list of nodes, plus new nodes created from a list of assets.
 * The list of children MUST be all of one specific asset type. */
function insertChildrenIntoAssetTreeNodeArray(
    nodes: assetTreeNode.AssetTreeNode[],
    children: backendModule.AnyAsset[],
    directoryKey: backendModule.AssetId | null,
    directoryId: backendModule.DirectoryId | null,
    depth: number
) {
    const typeOrder = children[0] != null ? backendModule.ASSET_TYPE_ORDER[children[0].type] : 0
    return array.splicedBefore(
        nodes.filter(node => node.item.type !== backendModule.AssetType.specialEmpty),
        children.map(asset =>
            assetTreeNode.assetTreeNodeFromAsset(asset, directoryKey, directoryId, depth)
        ),
        innerItem => backendModule.ASSET_TYPE_ORDER[innerItem.item.type] >= typeOrder
    )
}

/** Return a directory, with new children added into its list of children.
 * The list of children MUST be all of one specific asset type. */
function insertAssetTreeNodeChildren(
    item: assetTreeNode.AssetTreeNode,
    children: backendModule.AnyAsset[],
    directoryKey: backendModule.AssetId | null,
    directoryId: backendModule.DirectoryId | null
): assetTreeNode.AssetTreeNode {
    const newDepth = item.depth + 1
    return {
        ...item,
        children: insertChildrenIntoAssetTreeNodeArray(
            (item.children ?? []).filter(
                node => node.item.type !== backendModule.AssetType.specialEmpty
            ),
            children,
            directoryKey,
            directoryId,
            newDepth
        ),
    }
}

// =============================
// === Category to filter by ===
// =============================

const CATEGORY_TO_FILTER_BY: Record<categorySwitcher.Category, backendModule.FilterBy | null> = {
    [categorySwitcher.Category.recent]: null,
    [categorySwitcher.Category.drafts]: null,
    [categorySwitcher.Category.home]: backendModule.FilterBy.active,
    [categorySwitcher.Category.root]: null,
    [categorySwitcher.Category.trash]: backendModule.FilterBy.trashed,
}

// ===================
// === AssetsTable ===
// ===================

/** State passed through from a {@link AssetsTable} to every cell. */
export interface AssetsTableState {
    numberOfSelectedItems: number
    category: categorySwitcher.Category
    hasCopyData: boolean
    sortColumn: columnModule.SortableColumn | null
    setSortColumn: (column: columnModule.SortableColumn | null) => void
    sortDirection: sorting.SortDirection | null
    setSortDirection: (sortDirection: sorting.SortDirection | null) => void
    dispatchAssetListEvent: (event: assetListEventModule.AssetListEvent) => void
    assetEvents: assetEventModule.AssetEvent[]
    dispatchAssetEvent: (event: assetEventModule.AssetEvent) => void
    topLevelAssets: Readonly<React.MutableRefObject<assetTreeNode.AssetTreeNode[]>>
    nodeMap: Readonly<
        React.MutableRefObject<ReadonlyMap<backendModule.AssetId, assetTreeNode.AssetTreeNode>>
    >
    doToggleDirectoryExpansion: (
        directoryId: backendModule.DirectoryId,
        key: backendModule.AssetId,
        title?: string
    ) => void
    /** Called when the project is opened via the {@link ProjectActionButton}. */
    doOpenManually: (projectId: backendModule.ProjectId) => void
    doOpenIde: (
        project: backendModule.ProjectAsset,
        setProject: React.Dispatch<React.SetStateAction<backendModule.ProjectAsset>>,
        switchPage: boolean
    ) => void
    doCloseIde: (project: backendModule.ProjectAsset) => void
    doCut: () => void
    doPaste: (
        newParentKey: backendModule.AssetId | null,
        newParentId: backendModule.DirectoryId | null
    ) => void
}

/** Data associated with a {@link AssetRow}, used for rendering. */
export interface AssetRowState {
    setVisibility: (presence: visibilityModule.Visibility) => void
    isEditingName: boolean
}

/** The default {@link AssetRowState} associated with a {@link AssetRow}. */
export const INITIAL_ROW_STATE: AssetRowState = Object.freeze({
    setVisibility: () => {
        // Ignored. This MUST be replaced by the row component. It should also update `presence`.
    },
    isEditingName: false,
})

/** Props for a {@link AssetsTable}. */
export interface AssetsTableProps {
    query: string
    category: categorySwitcher.Category
    initialProjectName: string | null
    projectStartupInfo: backendModule.ProjectStartupInfo | null
    /** These events will be dispatched the next time the assets list is refreshed, rather than
     * immediately. */
    queuedAssetEvents: assetEventModule.AssetEvent[]
    assetListEvents: assetListEventModule.AssetListEvent[]
    dispatchAssetListEvent: (event: assetListEventModule.AssetListEvent) => void
    assetEvents: assetEventModule.AssetEvent[]
    dispatchAssetEvent: (event: assetEventModule.AssetEvent) => void
    doOpenIde: (
        project: backendModule.ProjectAsset,
        setProject: React.Dispatch<React.SetStateAction<backendModule.ProjectAsset>>,
        switchPage: boolean
    ) => void
    doCloseIde: (project: backendModule.ProjectAsset) => void
    loadingProjectManagerDidFail: boolean
    isListingRemoteDirectoryWhileOffline: boolean
    isListingLocalDirectoryAndWillFail: boolean
    isListingRemoteDirectoryAndWillFail: boolean
}

/** The table of project assets. */
export default function AssetsTable(props: AssetsTableProps) {
    const {
        query,
        category,
        initialProjectName,
        projectStartupInfo,
        queuedAssetEvents: rawQueuedAssetEvents,
        assetListEvents,
        dispatchAssetListEvent,
        assetEvents,
        dispatchAssetEvent,
        doOpenIde,
        doCloseIde: rawDoCloseIde,
        loadingProjectManagerDidFail,
        isListingRemoteDirectoryWhileOffline,
        isListingLocalDirectoryAndWillFail,
        isListingRemoteDirectoryAndWillFail,
    } = props
    const { organization, user, accessToken } = authProvider.useNonPartialUserSession()
    const { backend } = backendProvider.useBackend()
    const { setModal, unsetModal } = modalProvider.useSetModal()
    const { localStorage } = localStorageProvider.useLocalStorage()
    const { shortcuts } = shortcutsProvider.useShortcuts()
    const toastAndLog = hooks.useToastAndLog()
    const [initialized, setInitialized] = React.useState(false)
    const [assetTree, setAssetTree] = React.useState<assetTreeNode.AssetTreeNode[]>([])
    const [isLoading, setIsLoading] = React.useState(true)
    const [extraColumns, setExtraColumns] = React.useState(
        () => new Set<columnModule.ExtraColumn>()
    )
    const [sortColumn, setSortColumn] = React.useState<columnModule.SortableColumn | null>(null)
    const [sortDirection, setSortDirection] = React.useState<sorting.SortDirection | null>(null)
    const [selectedKeys, setSelectedKeys] = React.useState(() => new Set<backendModule.AssetId>())
    const [copyData, setCopyData] = React.useState<Set<backendModule.AssetId> | null>(null)
    const [, setQueuedAssetEvents] = React.useState<assetEventModule.AssetEvent[]>([])
    const [, setNameOfProjectToImmediatelyOpen] = React.useState(initialProjectName)
    const scrollContainerRef = React.useRef<HTMLDivElement>(null)
    const headerRowRef = React.useRef<HTMLTableRowElement>(null)
    const assetTreeRef = React.useRef<assetTreeNode.AssetTreeNode[]>([])
    const copyDataRef = React.useRef<Set<backendModule.AssetId> | null>(null)
    const nodeMapRef = React.useRef<
        ReadonlyMap<backendModule.AssetId, assetTreeNode.AssetTreeNode>
    >(new Map<backendModule.AssetId, assetTreeNode.AssetTreeNode>())
    const rootDirectoryId = React.useMemo(
        () => backend.rootDirectoryId(organization),
        [backend, organization]
    )
    const filter = React.useMemo(() => {
        if (query === '') {
            return null
        } else {
            const regex = new RegExp(string.regexEscape(query), 'i')
            return (node: assetTreeNode.AssetTreeNode) => regex.test(node.item.title)
        }
    }, [query])
    const displayItems = React.useMemo(() => {
        if (sortColumn == null || sortDirection == null) {
            return assetTreeNode.assetTreePreorderTraversal(assetTree)
        } else {
            const sortDescendingMultiplier = -1
            const multiplier = {
                [sorting.SortDirection.ascending]: 1,
                [sorting.SortDirection.descending]: sortDescendingMultiplier,
            }[sortDirection]
            let compare: (a: assetTreeNode.AssetTreeNode, b: assetTreeNode.AssetTreeNode) => number
            switch (sortColumn) {
                case columnModule.Column.name: {
                    compare = (a, b) =>
                        multiplier *
                        (a.item.title > b.item.title
                            ? 1
                            : a.item.title < b.item.title
                            ? COMPARE_LESS_THAN
                            : 0)

                    break
                }
                case columnModule.Column.modified: {
                    compare = (a, b) =>
                        multiplier *
                        (Number(new Date(a.item.modifiedAt)) - Number(new Date(b.item.modifiedAt)))
                    break
                }
            }
            return assetTreeNode.assetTreePreorderTraversal(assetTree, tree =>
                Array.from(tree).sort(compare)
            )
        }
    }, [assetTree, sortColumn, sortDirection])

    React.useEffect(() => {
        if (rawQueuedAssetEvents.length !== 0) {
            setQueuedAssetEvents(oldEvents => [...oldEvents, ...rawQueuedAssetEvents])
        }
    }, [rawQueuedAssetEvents])

    React.useEffect(() => {
        setIsLoading(true)
    }, [backend, category])

    React.useEffect(() => {
        if (backend.type === backendModule.BackendType.local && loadingProjectManagerDidFail) {
            setIsLoading(false)
        }
    }, [loadingProjectManagerDidFail, backend.type])

    React.useEffect(() => {
        assetTreeRef.current = assetTree
        nodeMapRef.current = new Map(
            assetTreeNode.assetTreePreorderTraversal(assetTree).map(asset => [asset.key, asset])
        )
    }, [assetTree])

    React.useEffect(() => {
        copyDataRef.current = copyData
    }, [copyData])

    React.useEffect(() => {
        return shortcuts.registerKeyboardHandlers({
            [shortcutsModule.KeyboardAction.cancelCut]: () => {
                if (copyDataRef.current == null) {
                    return false
                } else {
                    dispatchAssetEvent({
                        type: assetEventModule.AssetEventType.cancelCut,
                        ids: copyDataRef.current,
                    })
                    setCopyData(null)
                    return
                }
            },
        })
    }, [/* should never change */ shortcuts, /* should never change */ dispatchAssetEvent])

    React.useEffect(() => {
        if (isLoading) {
            setNameOfProjectToImmediatelyOpen(initialProjectName)
        } else {
            // The project name here might also be a string with project id, e.g. when opening
            // a project file from explorer on Windows.
            const isInitialProject = (asset: backendModule.AnyAsset) =>
                asset.title === initialProjectName || asset.id === initialProjectName
            const projectToLoad = assetTree
                .map(node => node.item)
                .filter(backendModule.assetIsProject)
                .find(isInitialProject)
            if (projectToLoad != null) {
                dispatchAssetEvent({
                    type: assetEventModule.AssetEventType.openProject,
                    id: projectToLoad.id,
                    shouldAutomaticallySwitchPage: true,
                    runInBackground: false,
                })
            } else {
                toastAndLog(`Could not find project '${initialProjectName}'`)
            }
        }
        // This effect MUST only run when `initialProjectName` is changed.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [initialProjectName])

    const overwriteAssets = React.useCallback(
        (newAssets: backendModule.AnyAsset[]) => {
            // This is required, otherwise we are using an outdated
            // `nameOfProjectToImmediatelyOpen`.
            setNameOfProjectToImmediatelyOpen(oldNameOfProjectToImmediatelyOpen => {
                setTimeout(() => {
                    setInitialized(true)
                    setAssetTree(
                        newAssets.map(asset => ({
                            key: asset.id,
                            item: asset,
                            directoryKey: null,
                            directoryId: null,
                            children: null,
                            depth: 0,
                        }))
                    )
                    // The project name here might also be a string with project id, e.g.
                    // when opening a project file from explorer on Windows.
                    const isInitialProject = (asset: backendModule.AnyAsset) =>
                        asset.title === oldNameOfProjectToImmediatelyOpen ||
                        asset.id === oldNameOfProjectToImmediatelyOpen
                    if (oldNameOfProjectToImmediatelyOpen != null) {
                        const projectToLoad = newAssets
                            .filter(backendModule.assetIsProject)
                            .find(isInitialProject)
                        if (projectToLoad != null) {
                            dispatchAssetEvent({
                                type: assetEventModule.AssetEventType.openProject,
                                id: projectToLoad.id,
                                shouldAutomaticallySwitchPage: true,
                                runInBackground: false,
                            })
                        } else {
                            toastAndLog(
                                `Could not find project '${oldNameOfProjectToImmediatelyOpen}'`
                            )
                        }
                    }
                    setQueuedAssetEvents(oldQueuedAssetEvents => {
                        if (oldQueuedAssetEvents.length !== 0) {
                            window.setTimeout(() => {
                                for (const event of oldQueuedAssetEvents) {
                                    dispatchAssetEvent(event)
                                }
                            }, 0)
                        }
                        return []
                    })
                }, 0)
                return null
            })
        },
        [
            /* should never change */ setNameOfProjectToImmediatelyOpen,
            /* should never change */ dispatchAssetEvent,
            /* should never change */ toastAndLog,
        ]
    )

    React.useEffect(() => {
        if (initialized) {
            overwriteAssets([])
        }
        // `overwriteAssets` is a callback, not a dependency.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [backend, category])

    hooks.useAsyncEffect(
        null,
        async signal => {
            switch (backend.type) {
                case backendModule.BackendType.local: {
                    if (!isListingLocalDirectoryAndWillFail) {
                        const newAssets = await backend.listDirectory(
                            {
                                parentId: null,
                                filterBy: CATEGORY_TO_FILTER_BY[category],
                                recentProjects: category === categorySwitcher.Category.recent,
                            },
                            null
                        )
                        if (!signal.aborted) {
                            setIsLoading(false)
                            overwriteAssets(newAssets)
                        }
                    }
                    break
                }
                case backendModule.BackendType.remote: {
                    if (
                        !isListingRemoteDirectoryAndWillFail &&
                        !isListingRemoteDirectoryWhileOffline
                    ) {
                        const newAssets = await backend.listDirectory(
                            {
                                parentId: null,
                                filterBy: CATEGORY_TO_FILTER_BY[category],
                                recentProjects: category === categorySwitcher.Category.recent,
                            },
                            null
                        )
                        if (!signal.aborted) {
                            setIsLoading(false)
                            overwriteAssets(newAssets)
                        }
                    } else {
                        setIsLoading(false)
                    }
                    break
                }
            }
        },
        [category, accessToken, organization, backend]
    )

    React.useEffect(() => {
        const savedExtraColumns = localStorage.get(localStorageModule.LocalStorageKey.extraColumns)
        if (savedExtraColumns != null) {
            setExtraColumns(new Set(savedExtraColumns))
        }
    }, [/* should never change */ localStorage])

    // Clip the header bar so that the background behind the extra colums selector is visible.
    React.useEffect(() => {
        const headerRow = headerRowRef.current
        const scrollContainer = scrollContainerRef.current
        if (
            backend.type === backendModule.BackendType.remote &&
            headerRow != null &&
            scrollContainer != null
        ) {
            let isClipPathUpdateQueued = false
            const updateClipPath = () => {
                isClipPathUpdateQueued = false
                const hasVerticalScrollbar =
                    scrollContainer.scrollHeight > scrollContainer.clientHeight
                const shrinkage =
                    TABLE_HEADER_WIDTH_SHRINKAGE_PX +
                    (hasVerticalScrollbar ? style.SCROLLBAR_WIDTH_PX : 0)
                const rightOffset = `calc(100vw - ${shrinkage}px + ${scrollContainer.scrollLeft}px)`
                headerRow.style.clipPath = `polygon(0 0, ${rightOffset} 0, ${rightOffset} 100%, 0 100%)`
            }
            const onScroll = () => {
                if (!isClipPathUpdateQueued) {
                    isClipPathUpdateQueued = true
                    requestAnimationFrame(updateClipPath)
                }
            }
            updateClipPath()
            scrollContainer.addEventListener('scroll', onScroll)
            return () => {
                scrollContainer.removeEventListener('scroll', onScroll)
            }
        } else {
            return
        }
    }, [backend.type])

    React.useEffect(() => {
        if (initialized) {
            localStorage.set(
                localStorageModule.LocalStorageKey.extraColumns,
                Array.from(extraColumns)
            )
        }
    }, [extraColumns, initialized, /* should never change */ localStorage])

    const directoryListAbortControllersRef = React.useRef(
        new Map<backendModule.DirectoryId, AbortController>()
    )
    const doToggleDirectoryExpansion = React.useCallback(
        (directoryId: backendModule.DirectoryId, key: backendModule.AssetId, title?: string) => {
            const directory = nodeMapRef.current.get(key)
            if (directory?.children != null) {
                const abortController = directoryListAbortControllersRef.current.get(directoryId)
                if (abortController != null) {
                    abortController.abort()
                    directoryListAbortControllersRef.current.delete(directoryId)
                }
                setAssetTree(oldAssetTree =>
                    assetTreeNode.assetTreeMap(oldAssetTree, item =>
                        item.key !== key ? item : { ...item, children: null }
                    )
                )
            } else {
                setAssetTree(oldAssetTree =>
                    assetTreeNode.assetTreeMap(oldAssetTree, item =>
                        item.key !== key
                            ? item
                            : {
                                  ...item,
                                  children: [
                                      assetTreeNode.assetTreeNodeFromAsset(
                                          backendModule.createSpecialLoadingAsset(directoryId),
                                          key,
                                          directoryId,
                                          item.depth + 1
                                      ),
                                  ],
                              }
                    )
                )
                void (async () => {
                    const abortController = new AbortController()
                    directoryListAbortControllersRef.current.set(directoryId, abortController)
                    const childAssets = await backend.listDirectory(
                        {
                            parentId: directoryId,
                            filterBy: CATEGORY_TO_FILTER_BY[category],
                            recentProjects: category === categorySwitcher.Category.recent,
                        },
                        title ?? null
                    )
                    if (!abortController.signal.aborted) {
                        setAssetTree(oldAssetTree =>
                            assetTreeNode.assetTreeMap(oldAssetTree, item => {
                                if (item.key !== key) {
                                    return item
                                } else {
                                    const initialChildren = item.children?.filter(
                                        child =>
                                            child.item.type !==
                                            backendModule.AssetType.specialLoading
                                    )
                                    const childAssetsMap = new Map(
                                        childAssets.map(asset => [asset.id, asset])
                                    )
                                    for (const child of initialChildren ?? []) {
                                        const newChild = childAssetsMap.get(child.item.id)
                                        if (newChild != null) {
                                            child.item = newChild
                                            childAssetsMap.delete(child.item.id)
                                        }
                                    }
                                    const childAssetNodes = Array.from(
                                        childAssetsMap.values(),
                                        child =>
                                            assetTreeNode.assetTreeNodeFromAsset(
                                                child,
                                                key,
                                                directoryId,
                                                item.depth + 1
                                            )
                                    )
                                    const specialEmptyAsset: backendModule.SpecialEmptyAsset | null =
                                        (initialChildren != null && initialChildren.length !== 0) ||
                                        childAssetNodes.length !== 0
                                            ? null
                                            : backendModule.createSpecialEmptyAsset(directoryId)
                                    const children =
                                        specialEmptyAsset != null
                                            ? [
                                                  assetTreeNode.assetTreeNodeFromAsset(
                                                      specialEmptyAsset,
                                                      key,
                                                      directoryId,
                                                      item.depth + 1
                                                  ),
                                              ]
                                            : initialChildren == null ||
                                              initialChildren.length === 0
                                            ? childAssetNodes
                                            : [...initialChildren, ...childAssetNodes].sort(
                                                  assetTreeNode.compareAssetTreeNodes
                                              )
                                    return {
                                        ...item,
                                        children,
                                    }
                                }
                            })
                        )
                    }
                })()
            }
        },
        [category, nodeMapRef, backend]
    )

    const getNewProjectName = React.useCallback(
        (templateId: string | null, parentKey: backendModule.DirectoryId | null) => {
            const prefix = `${templateId ?? 'New_Project'}_`
            const projectNameTemplate = new RegExp(`^${prefix}(?<projectIndex>\\d+)$`)
            const siblings =
                parentKey == null ? assetTree : nodeMapRef.current.get(parentKey)?.children ?? []
            const projectIndices = siblings
                .filter(node => backendModule.assetIsProject(node.item))
                .map(node => projectNameTemplate.exec(node.item.title)?.groups?.projectIndex)
                .map(maybeIndex => (maybeIndex != null ? parseInt(maybeIndex, 10) : 0))
            return `${prefix}${Math.max(0, ...projectIndices) + 1}`
        },
        [assetTree, nodeMapRef]
    )

    hooks.useEventHandler(assetListEvents, event => {
        switch (event.type) {
            case assetListEventModule.AssetListEventType.newFolder: {
                const siblings =
                    event.parentKey == null
                        ? assetTree
                        : nodeMapRef.current.get(event.parentKey)?.children ?? []
                const directoryIndices = siblings
                    .filter(node => backendModule.assetIsDirectory(node.item))
                    .map(node => DIRECTORY_NAME_REGEX.exec(node.item.title))
                    .map(match => match?.groups?.directoryIndex)
                    .map(maybeIndex => (maybeIndex != null ? parseInt(maybeIndex, 10) : 0))
                const title = `${DIRECTORY_NAME_DEFAULT_PREFIX}${
                    Math.max(0, ...directoryIndices) + 1
                }`
                const placeholderItem: backendModule.DirectoryAsset = {
                    id: backendModule.DirectoryId(uniqueString.uniqueString()),
                    title,
                    modifiedAt: dateTime.toRfc3339(new Date()),
                    parentId: event.parentId ?? rootDirectoryId,
                    permissions: permissions.tryGetSingletonOwnerPermission(organization, user),
                    projectState: null,
                    type: backendModule.AssetType.directory,
                }
                if (
                    event.parentId != null &&
                    event.parentKey != null &&
                    nodeMapRef.current.get(event.parentKey)?.children == null
                ) {
                    doToggleDirectoryExpansion(event.parentId, event.parentKey)
                }
                setAssetTree(oldAssetTree =>
                    event.parentKey == null
                        ? insertChildrenIntoAssetTreeNodeArray(
                              oldAssetTree,
                              [placeholderItem],
                              event.parentKey,
                              event.parentId,
                              0
                          )
                        : assetTreeNode.assetTreeMap(oldAssetTree, item =>
                              item.key !== event.parentKey
                                  ? item
                                  : insertAssetTreeNodeChildren(
                                        item,
                                        [placeholderItem],
                                        event.parentKey,
                                        event.parentId
                                    )
                          )
                )
                dispatchAssetEvent({
                    type: assetEventModule.AssetEventType.newFolder,
                    placeholderId: placeholderItem.id,
                })
                break
            }
            case assetListEventModule.AssetListEventType.newProject: {
                const projectName = getNewProjectName(event.templateId, event.parentId)
                const dummyId = backendModule.ProjectId(uniqueString.uniqueString())
                const placeholderItem: backendModule.ProjectAsset = {
                    id: dummyId,
                    title: projectName,
                    modifiedAt: dateTime.toRfc3339(new Date()),
                    parentId: event.parentId ?? rootDirectoryId,
                    permissions: permissions.tryGetSingletonOwnerPermission(organization, user),
                    projectState: {
                        type: backendModule.ProjectState.placeholder,
                        // eslint-disable-next-line @typescript-eslint/naming-convention
                        volume_id: '',
                        // eslint-disable-next-line @typescript-eslint/naming-convention
                        ...(organization != null ? { opened_by: organization.email } : {}),
                    },
                    type: backendModule.AssetType.project,
                }
                if (
                    event.parentId != null &&
                    event.parentKey != null &&
                    nodeMapRef.current.get(event.parentKey)?.children == null
                ) {
                    doToggleDirectoryExpansion(event.parentId, event.parentKey)
                }
                setAssetTree(oldAssetTree =>
                    event.parentKey == null
                        ? insertChildrenIntoAssetTreeNodeArray(
                              oldAssetTree,
                              [placeholderItem],
                              event.parentKey,
                              event.parentId,
                              0
                          )
                        : assetTreeNode.assetTreeMap(oldAssetTree, item =>
                              item.key !== event.parentKey
                                  ? item
                                  : insertAssetTreeNodeChildren(
                                        item,
                                        [placeholderItem],
                                        event.parentKey,
                                        event.parentId
                                    )
                          )
                )
                dispatchAssetEvent({
                    type: assetEventModule.AssetEventType.newProject,
                    placeholderId: dummyId,
                    templateId: event.templateId,
                    onSpinnerStateChange: event.onSpinnerStateChange,
                })
                break
            }
            case assetListEventModule.AssetListEventType.uploadFiles: {
                const reversedFiles = Array.from(event.files).reverse()
                const parentId = event.parentId ?? rootDirectoryId
                const placeholderFiles = reversedFiles.filter(backendModule.fileIsNotProject).map(
                    (file): backendModule.FileAsset => ({
                        type: backendModule.AssetType.file,
                        id: backendModule.FileId(uniqueString.uniqueString()),
                        title: file.name,
                        parentId,
                        permissions: permissions.tryGetSingletonOwnerPermission(organization, user),
                        modifiedAt: dateTime.toRfc3339(new Date()),
                        projectState: null,
                    })
                )
                const placeholderProjects = reversedFiles.filter(backendModule.fileIsProject).map(
                    (file): backendModule.ProjectAsset => ({
                        type: backendModule.AssetType.project,
                        id: backendModule.ProjectId(uniqueString.uniqueString()),
                        title: file.name,
                        parentId,
                        permissions: permissions.tryGetSingletonOwnerPermission(organization, user),
                        modifiedAt: dateTime.toRfc3339(new Date()),
                        projectState: {
                            type: backendModule.ProjectState.new,
                            // eslint-disable-next-line @typescript-eslint/naming-convention
                            volume_id: '',
                            // eslint-disable-next-line @typescript-eslint/naming-convention
                            ...(organization != null ? { opened_by: organization.email } : {}),
                        },
                    })
                )
                if (
                    event.parentId != null &&
                    event.parentKey != null &&
                    nodeMapRef.current.get(event.parentKey)?.children == null
                ) {
                    doToggleDirectoryExpansion(event.parentId, event.parentKey)
                }
                setAssetTree(oldAssetTree =>
                    event.parentKey == null
                        ? insertChildrenIntoAssetTreeNodeArray(
                              insertChildrenIntoAssetTreeNodeArray(
                                  oldAssetTree,
                                  placeholderFiles,
                                  event.parentKey,
                                  event.parentId,
                                  0
                              ),
                              placeholderProjects,
                              event.parentKey,
                              event.parentId,
                              0
                          )
                        : assetTreeNode.assetTreeMap(oldAssetTree, item =>
                              item.key !== event.parentKey
                                  ? item
                                  : insertAssetTreeNodeChildren(
                                        insertAssetTreeNodeChildren(
                                            item,
                                            placeholderFiles,
                                            event.parentKey,
                                            event.parentId
                                        ),
                                        placeholderProjects,
                                        event.parentKey,
                                        event.parentId
                                    )
                          )
                )
                dispatchAssetEvent({
                    type: assetEventModule.AssetEventType.uploadFiles,
                    files: new Map(
                        [...placeholderFiles, ...placeholderProjects].map((placeholderItem, i) => [
                            placeholderItem.id,
                            // This is SAFE, as `placeholderItems` is created using a map on
                            // `event.files`.
                            // eslint-disable-next-line @typescript-eslint/no-non-null-assertion
                            event.files[i]!,
                        ])
                    ),
                })
                break
            }
            case assetListEventModule.AssetListEventType.newDataConnector: {
                const placeholderItem: backendModule.SecretAsset = {
                    id: backendModule.SecretId(uniqueString.uniqueString()),
                    title: event.name,
                    modifiedAt: dateTime.toRfc3339(new Date()),
                    parentId: event.parentId ?? rootDirectoryId,
                    permissions: permissions.tryGetSingletonOwnerPermission(organization, user),
                    projectState: null,
                    type: backendModule.AssetType.secret,
                }
                if (
                    event.parentId != null &&
                    event.parentKey != null &&
                    nodeMapRef.current.get(event.parentKey)?.children == null
                ) {
                    doToggleDirectoryExpansion(event.parentId, event.parentKey)
                }
                setAssetTree(oldAssetTree =>
                    event.parentKey == null
                        ? insertChildrenIntoAssetTreeNodeArray(
                              oldAssetTree,
                              [placeholderItem],
                              event.parentKey,
                              event.parentId,
                              0
                          )
                        : assetTreeNode.assetTreeMap(oldAssetTree, item =>
                              item.key !== event.parentKey
                                  ? item
                                  : insertAssetTreeNodeChildren(
                                        item,
                                        [placeholderItem],
                                        event.parentKey,
                                        event.parentId
                                    )
                          )
                )
                dispatchAssetEvent({
                    type: assetEventModule.AssetEventType.newDataConnector,
                    placeholderId: placeholderItem.id,
                    value: event.value,
                })
                break
            }
            case assetListEventModule.AssetListEventType.willDelete: {
                if (selectedKeys.has(event.key)) {
                    setSelectedKeys(oldSelectedKeys => {
                        const newSelectedKeys = new Set(oldSelectedKeys)
                        newSelectedKeys.delete(event.key)
                        return newSelectedKeys
                    })
                }
                break
            }
            case assetListEventModule.AssetListEventType.move: {
                setAssetTree(oldAssetTree => {
                    const withNodeDeleted = assetTreeNode.assetTreeFilter(
                        oldAssetTree,
                        item => item.key !== event.key
                    )
                    return event.newParentKey == null
                        ? insertChildrenIntoAssetTreeNodeArray(
                              withNodeDeleted,
                              [event.item],
                              event.newParentKey,
                              event.newParentId,
                              0
                          )
                        : assetTreeNode.assetTreeMap(withNodeDeleted, item =>
                              item.key !== event.newParentKey || item.children == null
                                  ? item
                                  : insertAssetTreeNodeChildren(
                                        item,
                                        [event.item],
                                        event.newParentKey,
                                        event.newParentId
                                    )
                          )
                })
                break
            }
            case assetListEventModule.AssetListEventType.delete: {
                setAssetTree(oldAssetTree =>
                    assetTreeNode.assetTreeFilter(oldAssetTree, item => item.key !== event.key)
                )
                break
            }
            case assetListEventModule.AssetListEventType.removeSelf: {
                dispatchAssetEvent({
                    type: assetEventModule.AssetEventType.removeSelf,
                    id: event.id,
                })
                break
            }
            case assetListEventModule.AssetListEventType.closeFolder: {
                if (nodeMapRef.current.get(event.key)?.children != null) {
                    doToggleDirectoryExpansion(event.id, event.key)
                }
                break
            }
        }
    })

    const doOpenManually = React.useCallback(
        (projectId: backendModule.ProjectId) => {
            dispatchAssetEvent({
                type: assetEventModule.AssetEventType.openProject,
                id: projectId,
                shouldAutomaticallySwitchPage: true,
                runInBackground: false,
            })
        },
        [/* should never change */ dispatchAssetEvent]
    )

    const doCloseIde = React.useCallback(
        (project: backendModule.ProjectAsset) => {
            if (project.id === projectStartupInfo?.projectAsset.id) {
                dispatchAssetEvent({
                    type: assetEventModule.AssetEventType.cancelOpeningAllProjects,
                })
                rawDoCloseIde(project)
            }
        },
        [projectStartupInfo, rawDoCloseIde, /* should never change */ dispatchAssetEvent]
    )

    const doCut = React.useCallback(() => {
        setSelectedKeys(oldSelectedKeys => {
            queueMicrotask(() => {
                if (copyData != null) {
                    dispatchAssetEvent({
                        type: assetEventModule.AssetEventType.cancelCut,
                        ids: copyData,
                    })
                }
                setCopyData(oldSelectedKeys)
                dispatchAssetEvent({
                    type: assetEventModule.AssetEventType.cut,
                    ids: oldSelectedKeys,
                })
            })
            return new Set()
        })
    }, [copyData, /* should never change */ dispatchAssetEvent])

    const doPaste = React.useCallback(
        (
            newParentKey: backendModule.AssetId | null,
            newParentId: backendModule.DirectoryId | null
        ) => {
            if (copyData != null) {
                if (newParentKey != null && copyData.has(newParentKey)) {
                    toast.toast.error('Cannot paste a folder into itself.')
                } else {
                    setCopyData(null)
                    dispatchAssetEvent({
                        type: assetEventModule.AssetEventType.move,
                        ids: copyData,
                        newParentKey,
                        newParentId,
                    })
                }
            }
        },
        [copyData, /* should never change */ dispatchAssetEvent]
    )

    const doRenderContextMenu = React.useCallback(
        (
            innerSelectedKeys: Set<backendModule.AssetId>,
            event: Pick<React.MouseEvent<Element, MouseEvent>, 'pageX' | 'pageY'>,
            innerSetSelectedKeys: (items: Set<backendModule.AssetId>) => void,
            hidden: boolean
        ) => {
            const pluralized = pluralize(innerSelectedKeys.size)
            // This works because all items are mutated, ensuring their value stays
            // up to date.
            const ownsAllSelectedAssets =
                backend.type === backendModule.BackendType.local ||
                (organization != null &&
                    Array.from(innerSelectedKeys, key => {
                        const userPermissions = nodeMapRef.current.get(key)?.item.permissions
                        const selfPermission = userPermissions?.find(
                            permission => permission.user.user_email === organization.email
                        )
                        return selfPermission?.permission === permissions.PermissionAction.own
                    }).every(isOwner => isOwner))
            // This is not a React component even though it contains JSX.
            // eslint-disable-next-line no-restricted-syntax
            const doDeleteAll = () => {
                if (backend.type === backendModule.BackendType.remote) {
                    unsetModal()
                    dispatchAssetEvent({
                        type: assetEventModule.AssetEventType.delete,
                        ids: innerSelectedKeys,
                    })
                } else {
                    setModal(
                        <ConfirmDeleteModal
                            description={`${innerSelectedKeys.size} selected ${pluralized}`}
                            doDelete={() => {
                                innerSetSelectedKeys(new Set())
                                dispatchAssetEvent({
                                    type: assetEventModule.AssetEventType.delete,
                                    ids: innerSelectedKeys,
                                })
                            }}
                        />
                    )
                }
            }
            // This is not a React component even though it contains JSX.
            // eslint-disable-next-line no-restricted-syntax
            const doRestoreAll = () => {
                unsetModal()
                dispatchAssetEvent({
                    type: assetEventModule.AssetEventType.restore,
                    ids: innerSelectedKeys,
                })
            }
            if (category === categorySwitcher.Category.trash) {
                return innerSelectedKeys.size === 0 ? (
                    <></>
                ) : (
                    <ContextMenus key={uniqueString.uniqueString()} hidden={hidden} event={event}>
                        <ContextMenu hidden={hidden}>
                            <MenuEntry
                                hidden={hidden}
                                action={shortcutsModule.KeyboardAction.restoreAllFromTrash}
                                doAction={doRestoreAll}
                            />
                        </ContextMenu>
                    </ContextMenus>
                )
            } else {
                const deleteAction =
                    backend.type === backendModule.BackendType.local
                        ? shortcutsModule.KeyboardAction.deleteAll
                        : shortcutsModule.KeyboardAction.moveAllToTrash
                return (
                    <ContextMenus key={uniqueString.uniqueString()} hidden={hidden} event={event}>
                        {innerSelectedKeys.size !== 0 && ownsAllSelectedAssets && (
                            <ContextMenu hidden={hidden}>
                                <MenuEntry
                                    hidden={hidden}
                                    action={deleteAction}
                                    doAction={doDeleteAll}
                                />
                                {backend.type !== backendModule.BackendType.local && (
                                    <MenuEntry
                                        hidden={hidden}
                                        action={shortcutsModule.KeyboardAction.cutAll}
                                        doAction={() => {
                                            unsetModal()
                                            doCut()
                                        }}
                                    />
                                )}
                            </ContextMenu>
                        )}
                        <GlobalContextMenu
                            hidden={hidden}
                            hasCopyData={copyData != null}
                            directoryKey={null}
                            directoryId={null}
                            dispatchAssetListEvent={dispatchAssetListEvent}
                            doPaste={doPaste}
                        />
                    </ContextMenus>
                )
            }
        },
        [
            backend.type,
            category,
            copyData,
            doCut,
            doPaste,
            organization,
            /* should never change */ dispatchAssetEvent,
            /* should never change */ dispatchAssetListEvent,
            /* should never change */ setModal,
            /* should never change */ unsetModal,
        ]
    )

    const hiddenContextMenu = React.useMemo(
        () => doRenderContextMenu(selectedKeys, { pageX: 0, pageY: 0 }, setSelectedKeys, true),
        [doRenderContextMenu, selectedKeys]
    )

    const state = React.useMemo(
        // The type MUST be here to trigger excess property errors at typecheck time.
        (): AssetsTableState => ({
            numberOfSelectedItems: selectedKeys.size,
            category,
            hasCopyData: copyData != null,
            sortColumn,
            setSortColumn,
            sortDirection,
            setSortDirection,
            assetEvents,
            dispatchAssetEvent,
            dispatchAssetListEvent,
            topLevelAssets: assetTreeRef,
            nodeMap: nodeMapRef,
            doToggleDirectoryExpansion,
            doOpenManually,
            doOpenIde,
            doCloseIde,
            doCut,
            doPaste,
        }),
        [
            selectedKeys.size,
            category,
            copyData,
            sortColumn,
            sortDirection,
            assetEvents,
            doToggleDirectoryExpansion,
            doOpenManually,
            doOpenIde,
            doCloseIde,
            doCut,
            doPaste,
            /* should never change */ setSortColumn,
            /* should never change */ setSortDirection,
            /* should never change */ dispatchAssetEvent,
            /* should never change */ dispatchAssetListEvent,
        ]
    )

    return (
        <div ref={scrollContainerRef} className="flex-1 overflow-auto">
            <div className="flex flex-col w-min min-w-full h-full">
                {backend.type !== backendModule.BackendType.local && (
                    <div className="sticky top-0 h-0">
                        <div className="block sticky right-0 ml-auto w-29 p-2 z-1">
                            <div className="inline-flex gap-3">
                                {columnModule.EXTRA_COLUMNS.map(column => (
                                    <Button
                                        key={column}
                                        active={extraColumns.has(column)}
                                        image={columnModule.EXTRA_COLUMN_IMAGES[column]}
                                        onClick={() => {
                                            const newExtraColumns = new Set(extraColumns)
                                            if (extraColumns.has(column)) {
                                                newExtraColumns.delete(column)
                                            } else {
                                                newExtraColumns.add(column)
                                            }
                                            setExtraColumns(newExtraColumns)
                                        }}
                                    />
                                ))}
                            </div>
                        </div>
                    </div>
                )}
                {hiddenContextMenu}
                <Table<
                    assetTreeNode.AssetTreeNode,
                    AssetsTableState,
                    AssetRowState,
                    backendModule.AssetId
                >
                    scrollContainerRef={scrollContainerRef}
                    headerRowRef={headerRowRef}
                    footer={
                        <div
                            className="grow"
                            onDragOver={event => {
                                const payload = tryGetAssetRowsDragPayload(event.dataTransfer)
                                const filtered = payload?.filter(
                                    item => item.asset.parentId !== rootDirectoryId
                                )
                                if (filtered != null && filtered.length > 0) {
                                    event.preventDefault()
                                }
                            }}
                            onDrop={event => {
                                const payload = tryGetAssetRowsDragPayload(event.dataTransfer)
                                const filtered = payload?.filter(
                                    item => item.asset.parentId !== rootDirectoryId
                                )
                                if (filtered != null && filtered.length > 0) {
                                    event.preventDefault()
                                    event.stopPropagation()
                                    unsetModal()
                                    dispatchAssetEvent({
                                        type: assetEventModule.AssetEventType.move,
                                        newParentKey: null,
                                        newParentId: null,
                                        ids: new Set(filtered.map(dragItem => dragItem.asset.id)),
                                    })
                                }
                            }}
                        />
                    }
                    rowComponent={AssetRow}
                    items={displayItems}
                    filter={filter}
                    isLoading={isLoading}
                    state={state}
                    initialRowState={INITIAL_ROW_STATE}
                    getKey={assetTreeNode.getAssetTreeNodeKey}
                    selectedKeys={selectedKeys}
                    setSelectedKeys={setSelectedKeys}
                    placeholder={
                        category === categorySwitcher.Category.trash
                            ? TRASH_PLACEHOLDER
                            : PLACEHOLDER
                    }
                    columns={columnModule.getColumnList(backend.type, extraColumns).map(column => ({
                        id: column,
                        className: columnModule.COLUMN_CSS_CLASS[column],
                        heading: columnModule.COLUMN_HEADING[column],
                        render: columnModule.COLUMN_RENDERER[column],
                    }))}
                    onContextMenu={(innerSelectedKeys, event, innerSetSelectedKeys) => {
                        event.preventDefault()
                        event.stopPropagation()
                        setModal(
                            doRenderContextMenu(
                                innerSelectedKeys,
                                event,
                                innerSetSelectedKeys,
                                false
                            )
                        )
                    }}
                    draggableRows
                    onRowDragStart={event => {
                        setSelectedKeys(oldSelectedKeys => {
                            const nodes = assetTreeNode
                                .assetTreePreorderTraversal(assetTree)
                                .filter(node => oldSelectedKeys.has(node.key))
                            const data: AssetRowsDragPayload = nodes.map(node => ({
                                key: node.key,
                                asset: node.item,
                            }))
                            const id = uniqueString.uniqueString()
                            event.dataTransfer.setData(
                                `${ASSET_ROWS_DRAG_PAYLOAD_MIMETYPE}; id=${id}`,
                                JSON.stringify(data)
                            )
                            ASSET_ROWS_DRAG_PAYLOAD_MAP.set(id, data)
                            const blankElement = document.createElement('div')
                            const image = new Image()
                            image.src =
                                // Blank GIF
                                'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7'
                            event.dataTransfer.setDragImage(image, 0, 0)
                            blankElement.remove()
                            queueMicrotask(() => {
                                setModal(
                                    <DragModal
                                        event={event}
                                        className="flex flex-col bg-frame rounded-2xl bg-frame-selected backdrop-blur-3xl"
                                        doCleanup={() => {
                                            ASSET_ROWS_DRAG_PAYLOAD_MAP.delete(id)
                                        }}
                                    >
                                        {nodes.map(node => (
                                            <AssetNameColumn
                                                key={node.key}
                                                keyProp={node.key}
                                                item={{ ...node, depth: 0 }}
                                                state={state}
                                                // Default states.
                                                selected={false}
                                                rowState={INITIAL_ROW_STATE}
                                                // The drag placeholder cannot be interacted with.
                                                setSelected={() => {}}
                                                setItem={() => {}}
                                                setRowState={() => {}}
                                            />
                                        ))}
                                    </DragModal>
                                )
                            })
                            return oldSelectedKeys
                        })
                    }}
                />
            </div>
        </div>
    )
}
