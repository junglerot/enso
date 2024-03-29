/** @file The icon and name of a {@link backendModule.SecretAsset}. */
import * as React from 'react'

import ConnectorIcon from 'enso-assets/connector.svg'

import * as eventHooks from '#/hooks/eventHooks'
import * as setAssetHooks from '#/hooks/setAssetHooks'
import * as toastAndLogHooks from '#/hooks/toastAndLogHooks'

import * as backendProvider from '#/providers/BackendProvider'
import * as modalProvider from '#/providers/ModalProvider'
import * as shortcutManagerProvider from '#/providers/ShortcutManagerProvider'

import AssetEventType from '#/events/AssetEventType'
import AssetListEventType from '#/events/AssetListEventType'

import UpsertSecretModal from '#/layouts/dashboard/UpsertSecretModal'

import type * as column from '#/components/dashboard/column'

import * as backendModule from '#/services/Backend'

import * as eventModule from '#/utilities/event'
import * as indent from '#/utilities/indent'
import * as object from '#/utilities/object'
import * as shortcutManagerModule from '#/utilities/ShortcutManager'
import Visibility from '#/utilities/visibility'

// =====================
// === ConnectorName ===
// =====================

/** Props for a {@link SecretNameColumn}. */
export interface SecretNameColumnProps extends column.AssetColumnProps {}

/** The icon and name of a {@link backendModule.SecretAsset}.
 * @throws {Error} when the asset is not a {@link backendModule.SecretAsset}.
 * This should never happen. */
export default function SecretNameColumn(props: SecretNameColumnProps) {
  const { item, setItem, selected, state, rowState, setRowState } = props
  const { assetEvents, dispatchAssetListEvent } = state
  const toastAndLog = toastAndLogHooks.useToastAndLog()
  const { setModal } = modalProvider.useSetModal()
  const { backend } = backendProvider.useBackend()
  const { shortcutManager } = shortcutManagerProvider.useShortcutManager()
  const asset = item.item
  if (asset.type !== backendModule.AssetType.secret) {
    // eslint-disable-next-line no-restricted-syntax
    throw new Error('`SecretNameColumn` can only display secrets.')
  }
  const setAsset = setAssetHooks.useSetAsset(asset, setItem)

  eventHooks.useEventHandler(assetEvents, async event => {
    switch (event.type) {
      case AssetEventType.newProject:
      case AssetEventType.newFolder:
      case AssetEventType.uploadFiles:
      case AssetEventType.openProject:
      case AssetEventType.updateFiles:
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
        // Ignored. These events should all be unrelated to secrets.
        // `deleteMultiple`, `restoreMultiple`, `download`,
        // and `downloadSelected` are handled by `AssetRow`.
        break
      }
      case AssetEventType.newSecret: {
        if (item.key === event.placeholderId) {
          if (backend.type !== backendModule.BackendType.remote) {
            toastAndLog('Data connectors cannot be created on the local backend')
          } else {
            rowState.setVisibility(Visibility.faded)
            try {
              const id = await backend.createSecret({
                parentDirectoryId: asset.parentId,
                name: asset.title,
                value: event.value,
              })
              rowState.setVisibility(Visibility.visible)
              setAsset(object.merger({ id }))
            } catch (error) {
              dispatchAssetListEvent({
                type: AssetListEventType.delete,
                key: item.key,
              })
              toastAndLog('Error creating new data connector', error)
            }
          }
        }
        break
      }
    }
  })

  return (
    <div
      className={`flex text-left items-center whitespace-nowrap rounded-l-full gap-1 px-1.5 py-1 min-w-max ${indent.indentClass(
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
          (selected ||
            shortcutManager.matchesMouseAction(shortcutManagerModule.MouseAction.editName, event))
        ) {
          setRowState(object.merger({ isEditingName: true }))
        } else if (eventModule.isDoubleClick(event)) {
          event.stopPropagation()
          setModal(
            <UpsertSecretModal
              id={asset.id}
              name={asset.title}
              doCreate={async (_name, value) => {
                try {
                  await backend.updateSecret(asset.id, { value }, asset.title)
                } catch (error) {
                  toastAndLog(null, error)
                }
              }}
            />
          )
        }
      }}
    >
      <img src={ConnectorIcon} className="m-1" />
      {/* Secrets cannot be renamed. */}
      <span data-testid="asset-row-name" className="bg-transparent grow leading-170 h-6 py-px">
        {asset.title}
      </span>
    </div>
  )
}
