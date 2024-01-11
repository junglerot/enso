/** @file A user and their permissions for a specific asset. */
import * as React from 'react'

import * as toastAndLogHooks from '#/hooks/toastAndLogHooks'
import * as backendProvider from '#/providers/BackendProvider'
import * as backendModule from '#/services/backend'
import * as object from '#/utilities/object'

import PermissionSelector from '#/components/dashboard/PermissionSelector'

/** Props for a {@link UserPermissions}. */
export interface UserPermissionsProps {
    asset: backendModule.Asset
    self: backendModule.UserPermission
    isOnlyOwner: boolean
    userPermission: backendModule.UserPermission
    setUserPermission: (userPermissions: backendModule.UserPermission) => void
    doDelete: (user: backendModule.User) => void
}

/** A user and their permissions for a specific asset. */
export default function UserPermissions(props: UserPermissionsProps) {
    const { asset, self, isOnlyOwner, doDelete } = props
    const { userPermission: initialUserPermission, setUserPermission: outerSetUserPermission } =
        props
    const { backend } = backendProvider.useBackend()
    const toastAndLog = toastAndLogHooks.useToastAndLog()
    const [userPermissions, setUserPermissions] = React.useState(initialUserPermission)

    React.useEffect(() => {
        setUserPermissions(initialUserPermission)
    }, [initialUserPermission])

    const doSetUserPermission = async (newUserPermissions: backendModule.UserPermission) => {
        try {
            setUserPermissions(newUserPermissions)
            outerSetUserPermission(newUserPermissions)
            await backend.createPermission({
                userSubjects: [newUserPermissions.user.pk],
                resourceId: asset.id,
                action: newUserPermissions.permission,
            })
        } catch (error) {
            setUserPermissions(userPermissions)
            outerSetUserPermission(userPermissions)
            toastAndLog(
                `Could not set permissions of '${newUserPermissions.user.user_email}'`,
                error
            )
        }
    }

    return (
        <div className="flex gap-3 items-center">
            <PermissionSelector
                showDelete
                disabled={isOnlyOwner && userPermissions.user.pk === self.user.pk}
                error={
                    isOnlyOwner
                        ? `This ${
                              backendModule.ASSET_TYPE_NAME[asset.type]
                          } must have at least one owner.`
                        : null
                }
                selfPermission={self.permission}
                action={userPermissions.permission}
                assetType={asset.type}
                onChange={async permissions => {
                    await doSetUserPermission(
                        object.merge(userPermissions, { permission: permissions })
                    )
                }}
                doDelete={() => {
                    doDelete(userPermissions.user)
                }}
            />
            <span className="leading-170 h-6 py-px">{userPermissions.user.user_name}</span>
        </div>
    )
}
