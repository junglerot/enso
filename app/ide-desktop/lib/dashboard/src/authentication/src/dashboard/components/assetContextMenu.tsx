/** @file The context menu for an arbitrary {@link backendModule.Asset}. */
import * as React from 'react'
import * as toast from 'react-toastify'

import * as assetEventModule from '../events/assetEvent'
import * as assetListEvent from '../events/assetListEvent'
import type * as assetTreeNode from '../assetTreeNode'
import * as backendModule from '../backend'
import * as hooks from '../../hooks'
import * as http from '../../http'
import * as object from '../../object'
import * as permissions from '../permissions'
import * as remoteBackendModule from '../remoteBackend'
import * as shortcuts from '../shortcuts'

import * as authProvider from '../../authentication/providers/auth'
import * as backendProvider from '../../providers/backend'
import * as loggerProvider from '../../providers/logger'
import * as modalProvider from '../../providers/modal'

import type * as assetsTable from './assetsTable'
import * as categorySwitcher from './categorySwitcher'
import type * as tableRow from './tableRow'
import ConfirmDeleteModal from './confirmDeleteModal'
import ContextMenu from './contextMenu'
import ContextMenuSeparator from './contextMenuSeparator'
import ContextMenus from './contextMenus'
import GlobalContextMenu from './globalContextMenu'
import ManagePermissionsModal from './managePermissionsModal'
import MenuEntry from './menuEntry'

// ========================
// === AssetContextMenu ===
// ========================

/** Props for a {@link AssetContextMenu}. */
export interface AssetContextMenuProps {
    hidden?: boolean
    innerProps: tableRow.TableRowInnerProps<
        assetTreeNode.AssetTreeNode,
        assetsTable.AssetsTableState,
        assetsTable.AssetRowState
    >
    event: Pick<React.MouseEvent, 'pageX' | 'pageY'>
    eventTarget: HTMLElement | null
    doDelete: () => void
    doCopy: () => void
    doCut: () => void
    doPaste: (newParentKey: backendModule.AssetId, newParentId: backendModule.DirectoryId) => void
}

/** The context menu for an arbitrary {@link backendModule.Asset}. */
export default function AssetContextMenu(props: AssetContextMenuProps) {
    const {
        hidden = false,
        innerProps: {
            item,
            setItem,
            state: { category, hasPasteData, dispatchAssetEvent, dispatchAssetListEvent },
            setRowState,
        },
        event,
        eventTarget,
        doCopy,
        doCut,
        doPaste,
        doDelete,
    } = props
    const logger = loggerProvider.useLogger()
    const { organization, accessToken } = authProvider.useNonPartialUserSession()
    const { setModal, unsetModal } = modalProvider.useSetModal()
    const { backend } = backendProvider.useBackend()
    const toastAndLog = hooks.useToastAndLog()
    const asset = item.item
    const self = asset.permissions?.find(
        permission => permission.user.user_email === organization?.email
    )
    const isCloud = backend.type === backendModule.BackendType.remote
    const ownsThisAsset =
        backend.type === backendModule.BackendType.local ||
        self?.permission === permissions.PermissionAction.own
    const managesThisAsset =
        backend.type === backendModule.BackendType.local ||
        ownsThisAsset ||
        self?.permission === permissions.PermissionAction.admin
    const isRunningProject =
        asset.type === backendModule.AssetType.project &&
        backendModule.DOES_PROJECT_STATE_INDICATE_VM_EXISTS[asset.projectState.type]
    const canExecute =
        backend.type === backendModule.BackendType.local ||
        (self?.permission != null && permissions.PERMISSION_ACTION_CAN_EXECUTE[self.permission])
    const isOtherUserUsingProject =
        backend.type !== backendModule.BackendType.local &&
        backendModule.assetIsProject(asset) &&
        asset.projectState.opened_by != null &&
        asset.projectState.opened_by !== organization?.email
    const setAsset = React.useCallback(
        (valueOrUpdater: React.SetStateAction<backendModule.AnyAsset>) => {
            if (typeof valueOrUpdater === 'function') {
                setItem(oldItem =>
                    oldItem.with({
                        item: valueOrUpdater(oldItem.item),
                    })
                )
            } else {
                setItem(oldItem => oldItem.with({ item: valueOrUpdater }))
            }
        },
        [/* should never change */ setItem]
    )
    return category === categorySwitcher.Category.trash ? (
        !ownsThisAsset ? null : (
            <ContextMenus hidden={hidden} key={asset.id} event={event}>
                <ContextMenu hidden={hidden}>
                    <MenuEntry
                        hidden={hidden}
                        action={shortcuts.KeyboardAction.restoreFromTrash}
                        doAction={() => {
                            unsetModal()
                            dispatchAssetEvent({
                                type: assetEventModule.AssetEventType.restore,
                                ids: new Set([asset.id]),
                            })
                        }}
                    />
                </ContextMenu>
            </ContextMenus>
        )
    ) : (
        <ContextMenus hidden={hidden} key={asset.id} event={event}>
            <ContextMenu hidden={hidden}>
                {asset.type === backendModule.AssetType.project &&
                    canExecute &&
                    !isRunningProject &&
                    !isOtherUserUsingProject && (
                        <MenuEntry
                            hidden={hidden}
                            action={shortcuts.KeyboardAction.open}
                            doAction={() => {
                                unsetModal()
                                dispatchAssetEvent({
                                    type: assetEventModule.AssetEventType.openProject,
                                    id: asset.id,
                                    shouldAutomaticallySwitchPage: true,
                                    runInBackground: false,
                                })
                            }}
                        />
                    )}
                {asset.type === backendModule.AssetType.project && isCloud && (
                    <MenuEntry
                        hidden={hidden}
                        action={shortcuts.KeyboardAction.run}
                        doAction={() => {
                            unsetModal()
                            dispatchAssetEvent({
                                type: assetEventModule.AssetEventType.openProject,
                                id: asset.id,
                                shouldAutomaticallySwitchPage: false,
                                runInBackground: true,
                            })
                        }}
                    />
                )}
                {asset.type === backendModule.AssetType.project &&
                    canExecute &&
                    isRunningProject &&
                    !isOtherUserUsingProject && (
                        <MenuEntry
                            hidden={hidden}
                            action={shortcuts.KeyboardAction.close}
                            doAction={() => {
                                unsetModal()
                                dispatchAssetEvent({
                                    type: assetEventModule.AssetEventType.closeProject,
                                    id: asset.id,
                                })
                            }}
                        />
                    )}
                {asset.type === backendModule.AssetType.project && !isCloud && (
                    <MenuEntry
                        hidden={hidden}
                        action={shortcuts.KeyboardAction.uploadToCloud}
                        doAction={async () => {
                            unsetModal()
                            if (accessToken == null) {
                                toastAndLog('Cannot upload to cloud in offline mode')
                            } else {
                                try {
                                    const headers = new Headers([
                                        ['Authorization', `Bearer ${accessToken}`],
                                    ])
                                    const client = new http.Client(headers)
                                    const remoteBackend = new remoteBackendModule.RemoteBackend(
                                        client,
                                        logger
                                    )
                                    const projectResponse = await fetch(
                                        `./api/project-manager/projects/${asset.id}/enso-project`
                                    )
                                    // This DOES NOT update the cloud assets list when it
                                    // completes, as the current backend is not the remote
                                    // (cloud) backend. The user may change to the cloud backend
                                    // while this request is in progress, however this is
                                    // uncommon enough that it is not worth the added complexity.
                                    await remoteBackend.uploadFile(
                                        {
                                            fileName: `${asset.title}.enso-project`,
                                            fileId: null,
                                            parentDirectoryId: null,
                                        },
                                        await projectResponse.blob()
                                    )
                                    toast.toast.success(
                                        'Successfully uploaded local project to cloud!'
                                    )
                                } catch (error) {
                                    toastAndLog('Could not upload local project to cloud', error)
                                }
                            }
                        }}
                    />
                )}
                {canExecute && !isRunningProject && !isOtherUserUsingProject && (
                    <MenuEntry
                        hidden={hidden}
                        disabled={
                            asset.type !== backendModule.AssetType.project &&
                            asset.type !== backendModule.AssetType.directory
                        }
                        action={shortcuts.KeyboardAction.rename}
                        doAction={() => {
                            setRowState(object.merger({ isEditingName: true }))
                            unsetModal()
                        }}
                    />
                )}
                {isCloud && (
                    <MenuEntry
                        hidden={hidden}
                        disabled
                        action={shortcuts.KeyboardAction.snapshot}
                        doAction={() => {
                            // No backend support yet.
                        }}
                    />
                )}
                {ownsThisAsset && !isRunningProject && !isOtherUserUsingProject && (
                    <MenuEntry
                        hidden={hidden}
                        action={
                            backend.type === backendModule.BackendType.local
                                ? shortcuts.KeyboardAction.delete
                                : shortcuts.KeyboardAction.moveToTrash
                        }
                        doAction={() => {
                            if (backend.type === backendModule.BackendType.remote) {
                                unsetModal()
                                doDelete()
                            } else {
                                setModal(
                                    <ConfirmDeleteModal
                                        description={`the ${asset.type} '${asset.title}'`}
                                        doDelete={doDelete}
                                    />
                                )
                            }
                        }}
                    />
                )}
                {isCloud && <ContextMenuSeparator hidden={hidden} />}
                {isCloud && managesThisAsset && self != null && (
                    <MenuEntry
                        hidden={hidden}
                        action={shortcuts.KeyboardAction.share}
                        doAction={() => {
                            setModal(
                                <ManagePermissionsModal
                                    item={asset}
                                    setItem={setAsset}
                                    self={self}
                                    eventTarget={eventTarget}
                                    doRemoveSelf={() => {
                                        dispatchAssetEvent({
                                            type: assetEventModule.AssetEventType.removeSelf,
                                            id: asset.id,
                                        })
                                    }}
                                />
                            )
                        }}
                    />
                )}
                {isCloud && (
                    <MenuEntry
                        hidden={hidden}
                        disabled
                        action={shortcuts.KeyboardAction.label}
                        doAction={() => {
                            // No backend support yet.
                        }}
                    />
                )}
                {isCloud && managesThisAsset && self != null && (
                    <ContextMenuSeparator hidden={hidden} />
                )}
                <MenuEntry
                    hidden={hidden}
                    disabled={!isCloud}
                    action={shortcuts.KeyboardAction.duplicate}
                    doAction={() => {
                        unsetModal()
                        dispatchAssetListEvent({
                            type: assetListEvent.AssetListEventType.copy,
                            newParentId: item.directoryId,
                            newParentKey: item.directoryKey,
                            items: [asset],
                        })
                    }}
                />
                {isCloud && (
                    <MenuEntry
                        hidden={hidden}
                        action={shortcuts.KeyboardAction.copy}
                        doAction={doCopy}
                    />
                )}
                {isCloud && !isOtherUserUsingProject && (
                    <MenuEntry
                        hidden={hidden}
                        action={shortcuts.KeyboardAction.cut}
                        doAction={doCut}
                    />
                )}
                <MenuEntry
                    hidden={hidden}
                    action={shortcuts.KeyboardAction.download}
                    doAction={() => {
                        dispatchAssetEvent({
                            type: assetEventModule.AssetEventType.download,
                            ids: new Set([asset.id]),
                        })
                    }}
                />
                {hasPasteData && (
                    <MenuEntry
                        hidden={hidden}
                        action={shortcuts.KeyboardAction.paste}
                        doAction={() => {
                            const [directoryKey, directoryId] =
                                item.item.type === backendModule.AssetType.directory
                                    ? [item.key, item.item.id]
                                    : [item.directoryKey, item.directoryId]
                            doPaste(directoryKey, directoryId)
                        }}
                    />
                )}
            </ContextMenu>
            {category === categorySwitcher.Category.home && (
                <GlobalContextMenu
                    hidden={hidden}
                    hasCopyData={hasPasteData}
                    directoryKey={
                        // This is SAFE, as both branches are guaranteed to be `DirectoryId`s
                        // eslint-disable-next-line no-restricted-syntax
                        (asset.type === backendModule.AssetType.directory
                            ? item.key
                            : item.directoryKey) as backendModule.DirectoryId
                    }
                    directoryId={
                        asset.type === backendModule.AssetType.directory
                            ? asset.id
                            : item.directoryId
                    }
                    dispatchAssetListEvent={dispatchAssetListEvent}
                    doPaste={doPaste}
                />
            )}
        </ContextMenus>
    )
}
