/** @file Events related to changes in asset state. */
import type AssetEventType from '#/events/AssetEventType'
import type * as backendModule from '#/services/backend'

import type * as spinner from '#/components/Spinner'

// This is required, to whitelist this event.
// eslint-disable-next-line no-restricted-syntax
declare module '#/hooks/eventHooks' {
    /** A map containing all known event types. */
    export interface KnownEventsMap {
        assetEvent: AssetEvent
    }
}

// ==================
// === AssetEvent ===
// ==================

/** Properties common to all asset state change events. */
interface AssetBaseEvent<Type extends AssetEventType> {
    type: Type
}

/** All possible events. */
interface AssetEvents {
    newProject: AssetNewProjectEvent
    newFolder: AssetNewFolderEvent
    uploadFiles: AssetUploadFilesEvent
    newDataConnector: AssetNewDataConnectorEvent
    openProject: AssetOpenProjectEvent
    closeProject: AssetCloseProjectEvent
    cancelOpeningAllProjects: AssetCancelOpeningAllProjectsEvent
    copy: AssetCopyEvent
    cut: AssetCutEvent
    cancelCut: AssetCancelCutEvent
    move: AssetMoveEvent
    delete: AssetDeleteEvent
    restore: AssetRestoreEvent
    download: AssetDownloadEvent
    downloadSelected: AssetDownloadSelectedEvent
    removeSelf: AssetRemoveSelfEvent
    temporarilyAddLabels: AssetTemporarilyAddLabelsEvent
    temporarilyRemoveLabels: AssetTemporarilyRemoveLabelsEvent
    addLabels: AssetAddLabelsEvent
    removeLabels: AssetRemoveLabelsEvent
    deleteLabel: AssetDeleteLabelEvent
}

/** A type to ensure that {@link AssetEvents} contains every {@link AssetEventType}. */
// This is meant only as a sanity check, so it is allowed to break lint rules.
// eslint-disable-next-line @typescript-eslint/no-unused-vars
type SanityCheck<
    T extends {
        [Type in keyof typeof AssetEventType]: AssetBaseEvent<(typeof AssetEventType)[Type]>
    } = AssetEvents,
    // eslint-disable-next-line no-restricted-syntax
> = T

/** A signal to create a project. */
export interface AssetNewProjectEvent extends AssetBaseEvent<AssetEventType.newProject> {
    placeholderId: backendModule.ProjectId
    templateId: string | null
    onSpinnerStateChange: ((state: spinner.SpinnerState) => void) | null
}

/** A signal to create a directory. */
export interface AssetNewFolderEvent extends AssetBaseEvent<AssetEventType.newFolder> {
    placeholderId: backendModule.DirectoryId
}

/** A signal to upload files. */
export interface AssetUploadFilesEvent extends AssetBaseEvent<AssetEventType.uploadFiles> {
    files: Map<backendModule.AssetId, File>
}

/** A signal to create a data connector. */
export interface AssetNewDataConnectorEvent
    extends AssetBaseEvent<AssetEventType.newDataConnector> {
    placeholderId: backendModule.SecretId
    value: string
}

/** A signal to open the specified project. */
export interface AssetOpenProjectEvent extends AssetBaseEvent<AssetEventType.openProject> {
    id: backendModule.ProjectId
    shouldAutomaticallySwitchPage: boolean
    runInBackground: boolean
}

/** A signal to close the specified project. */
export interface AssetCloseProjectEvent extends AssetBaseEvent<AssetEventType.closeProject> {
    id: backendModule.ProjectId
}

/** A signal to cancel automatically opening any project that is currently opening. */
export interface AssetCancelOpeningAllProjectsEvent
    extends AssetBaseEvent<AssetEventType.cancelOpeningAllProjects> {}

/** A signal that multiple assets should be copied. `ids` are the `Id`s of the newly created
 * placeholder items. */
export interface AssetCopyEvent extends AssetBaseEvent<AssetEventType.copy> {
    ids: Set<backendModule.AssetId>
    newParentKey: backendModule.AssetId
    newParentId: backendModule.DirectoryId
}

/** A signal to cut multiple assets. */
export interface AssetCutEvent extends AssetBaseEvent<AssetEventType.cut> {
    ids: Set<backendModule.AssetId>
}

/** A signal that a cut operation has been cancelled. */
export interface AssetCancelCutEvent extends AssetBaseEvent<AssetEventType.cancelCut> {
    ids: Set<backendModule.AssetId>
}

/** A signal to move multiple assets. */
export interface AssetMoveEvent extends AssetBaseEvent<AssetEventType.move> {
    ids: Set<backendModule.AssetId>
    newParentKey: backendModule.AssetId
    newParentId: backendModule.DirectoryId
}

/** A signal to delete assets. */
export interface AssetDeleteEvent extends AssetBaseEvent<AssetEventType.delete> {
    ids: Set<backendModule.AssetId>
}

/** A signal to restore assets from trash. */
export interface AssetRestoreEvent extends AssetBaseEvent<AssetEventType.restore> {
    ids: Set<backendModule.AssetId>
}

/** A signal to download assets. */
export interface AssetDownloadEvent extends AssetBaseEvent<AssetEventType.download> {
    ids: Set<backendModule.AssetId>
}

/** A signal to download the currently selected assets. */
export interface AssetDownloadSelectedEvent
    extends AssetBaseEvent<AssetEventType.downloadSelected> {}

/** A signal to remove the current user's permissions for an asset. */
export interface AssetRemoveSelfEvent extends AssetBaseEvent<AssetEventType.removeSelf> {
    id: backendModule.AssetId
}

/** A signal to temporarily add labels to the selected assetss. */
export interface AssetTemporarilyAddLabelsEvent
    extends AssetBaseEvent<AssetEventType.temporarilyAddLabels> {
    ids: Set<backendModule.AssetId>
    labelNames: ReadonlySet<backendModule.LabelName>
}

/** A signal to temporarily remove labels from the selected assetss. */
export interface AssetTemporarilyRemoveLabelsEvent
    extends AssetBaseEvent<AssetEventType.temporarilyRemoveLabels> {
    ids: Set<backendModule.AssetId>
    labelNames: ReadonlySet<backendModule.LabelName>
}

/** A signal to add labels to the selected assetss. */
export interface AssetAddLabelsEvent extends AssetBaseEvent<AssetEventType.addLabels> {
    ids: Set<backendModule.AssetId>
    labelNames: ReadonlySet<backendModule.LabelName>
}

/** A signal to remove labels from the selected assetss. */
export interface AssetRemoveLabelsEvent extends AssetBaseEvent<AssetEventType.removeLabels> {
    ids: Set<backendModule.AssetId>
    labelNames: ReadonlySet<backendModule.LabelName>
}

/** A signal to remove a label from all assets. */
export interface AssetDeleteLabelEvent extends AssetBaseEvent<AssetEventType.deleteLabel> {
    labelName: backendModule.LabelName
}

/** Every possible type of asset event. */
export type AssetEvent = AssetEvents[keyof AssetEvents]
