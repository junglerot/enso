/** @file The icon and name of a {@link backendModule.DirectoryAsset}. */
import * as React from 'react'

import FolderIcon from 'enso-assets/folder.svg'
import TriangleDownIcon from 'enso-assets/triangle_down.svg'

import AssetEventType from '#/events/AssetEventType'
import AssetListEventType from '#/events/AssetListEventType'
import * as hooks from '#/hooks'
import * as backendProvider from '#/providers/BackendProvider'
import * as shortcutsProvider from '#/providers/ShortcutsProvider'
import * as backendModule from '#/services/backend'
import * as assetTreeNode from '#/utilities/assetTreeNode'
import * as eventModule from '#/utilities/event'
import * as indent from '#/utilities/indent'
import * as object from '#/utilities/object'
import * as shortcutsModule from '#/utilities/shortcuts'
import Visibility from '#/utilities/visibility'

import type * as column from '#/components/dashboard/column'
import EditableSpan from '#/components/EditableSpan'
import SvgMask from '#/components/SvgMask'

// =====================
// === DirectoryName ===
// =====================

/** Props for a {@link DirectoryNameColumn}. */
export interface DirectoryNameColumnProps extends column.AssetColumnProps {}

/** The icon and name of a {@link backendModule.DirectoryAsset}.
 * @throws {Error} when the asset is not a {@link backendModule.DirectoryAsset}.
 * This should never happen. */
export default function DirectoryNameColumn(props: DirectoryNameColumnProps) {
    const { item, setItem, selected, setSelected, state, rowState, setRowState } = props
    const { numberOfSelectedItems, assetEvents, dispatchAssetListEvent, nodeMap } = state
    const { doToggleDirectoryExpansion } = state
    const toastAndLog = hooks.useToastAndLog()
    const { backend } = backendProvider.useBackend()
    const { shortcuts } = shortcutsProvider.useShortcuts()
    const asset = item.item
    if (asset.type !== backendModule.AssetType.directory) {
        // eslint-disable-next-line no-restricted-syntax
        throw new Error('`DirectoryNameColumn` can only display directory assets.')
    }
    const setAsset = assetTreeNode.useSetAsset(asset, setItem)

    const doRename = async (newName: string) => {
        if (backend.type !== backendModule.BackendType.local) {
            try {
                await backend.updateDirectory(asset.id, { title: newName }, asset.title)
                return
            } catch (error) {
                toastAndLog('Could not rename folder', error)
                throw error
            }
        }
    }

    hooks.useEventHandler(assetEvents, async event => {
        switch (event.type) {
            case AssetEventType.newProject:
            case AssetEventType.uploadFiles:
            case AssetEventType.newDataConnector:
            case AssetEventType.openProject:
            case AssetEventType.closeProject:
            case AssetEventType.cancelOpeningAllProjects:
            case AssetEventType.copy:
            case AssetEventType.cut:
            case AssetEventType.cancelCut:
            case AssetEventType.move:
            case AssetEventType.delete:
            case AssetEventType.restore:
            case AssetEventType.download:
            case AssetEventType.downloadSelected:
            case AssetEventType.removeSelf:
            case AssetEventType.temporarilyAddLabels:
            case AssetEventType.temporarilyRemoveLabels:
            case AssetEventType.addLabels:
            case AssetEventType.removeLabels:
            case AssetEventType.deleteLabel: {
                // Ignored. These events should all be unrelated to directories.
                // `deleteMultiple`, `restoreMultiple`, `download`,
                // and `downloadSelected` are handled by `AssetRow`.
                break
            }
            case AssetEventType.newFolder: {
                if (item.key === event.placeholderId) {
                    if (backend.type !== backendModule.BackendType.remote) {
                        toastAndLog('Cannot create folders on the local drive')
                    } else {
                        rowState.setVisibility(Visibility.faded)
                        try {
                            const createdDirectory = await backend.createDirectory({
                                parentId: asset.parentId,
                                title: asset.title,
                            })
                            rowState.setVisibility(Visibility.visible)
                            setAsset(object.merge(asset, createdDirectory))
                        } catch (error) {
                            dispatchAssetListEvent({
                                type: AssetListEventType.delete,
                                key: item.key,
                            })
                            toastAndLog('Could not create new folder', error)
                        }
                    }
                }
                break
            }
        }
    })

    return (
        <div
            className={`group flex text-left items-center whitespace-nowrap rounded-l-full gap-1 px-1.5 py-1 min-w-max ${indent.indentClass(
                item.depth
            )}`}
            onKeyDown={event => {
                if (rowState.isEditingName && event.key === 'Enter') {
                    event.stopPropagation()
                }
            }}
            onClick={event => {
                if (
                    eventModule.isSingleClick(event) &&
                    ((selected && numberOfSelectedItems === 1) ||
                        shortcuts.matchesMouseAction(shortcutsModule.MouseAction.editName, event))
                ) {
                    setRowState(object.merger({ isEditingName: true }))
                } else if (eventModule.isDoubleClick(event)) {
                    if (!rowState.isEditingName) {
                        // This must be processed on the next tick, otherwise it will be overridden
                        // by the default click handler.
                        window.setTimeout(() => {
                            setSelected(false)
                        }, 0)
                        doToggleDirectoryExpansion(asset.id, item.key, asset.title)
                    }
                }
            }}
        >
            <SvgMask
                src={TriangleDownIcon}
                className={`hidden group-hover:inline-block cursor-pointer h-4 w-4 m-1 transition-transform duration-300 ${
                    item.children != null ? '' : '-rotate-90'
                }`}
                onClick={event => {
                    event.stopPropagation()
                    doToggleDirectoryExpansion(asset.id, item.key, asset.title)
                }}
            />
            <SvgMask src={FolderIcon} className="group-hover:hidden h-4 w-4 m-1" />
            <EditableSpan
                editable={rowState.isEditingName}
                checkSubmittable={newTitle =>
                    (nodeMap.current.get(item.directoryKey)?.children ?? []).every(
                        child =>
                            // All siblings,
                            child.key === item.key ||
                            // that are directories,
                            !backendModule.assetIsDirectory(child.item) ||
                            // must have a different name.
                            child.item.title !== newTitle
                    )
                }
                onSubmit={async newTitle => {
                    setRowState(object.merger({ isEditingName: false }))
                    if (newTitle !== asset.title) {
                        const oldTitle = asset.title
                        setAsset(object.merger({ title: newTitle }))
                        try {
                            await doRename(newTitle)
                        } catch {
                            setAsset(object.merger({ title: oldTitle }))
                        }
                    }
                }}
                onCancel={() => {
                    setRowState(object.merger({ isEditingName: false }))
                }}
                className={`cursor-pointer bg-transparent grow leading-170 h-6 py-px ${
                    rowState.isEditingName ? 'cursor-text' : 'cursor-pointer'
                }`}
            >
                {asset.title}
            </EditableSpan>
        </div>
    )
}
