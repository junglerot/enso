/** @file The UserMenu component provides a dropdown menu of user actions and settings. */
import * as React from 'react'

import * as app from '../../components/app'
import * as auth from '../../authentication/providers/auth'
import * as hooks from '../../hooks'
import * as modalProvider from '../../providers/modal'

import ChangePasswordModal from './changePasswordModal'

// ================
// === UserMenu ===
// ================

/** This is the UI component for a `UserMenu` list item.
 * The main interaction logic is in the `onClick` injected by `UserMenu`. */
export interface UserMenuItemProps {
    disabled?: boolean
    onClick?: React.MouseEventHandler<HTMLDivElement>
}

/** User menu item. */
function UserMenuItem(props: React.PropsWithChildren<UserMenuItemProps>) {
    const { children, disabled = false, onClick } = props

    return (
        <div
            className={`whitespace-nowrap px-4 py-2 ${disabled ? 'opacity-50' : ''} ${
                onClick ? 'hover:bg-blue-500 hover:text-white' : ''
            } ${onClick != null && !disabled ? 'cursor-pointer' : ''}`}
            onClick={onClick}
        >
            {children}
        </div>
    )
}

/** Handling the UserMenuItem click event logic and displaying its content. */
export default function UserMenu() {
    const { signOut } = auth.useAuth()
    const { accessToken, organization } = auth.useNonPartialUserSession()
    const navigate = hooks.useNavigate()

    const { setModal } = modalProvider.useSetModal()

    const goToProfile = () => {
        // TODO: Implement this when the backend endpoints are implemented.
    }

    const goToLoginPage = () => {
        navigate(app.LOGIN_PATH)
    }

    // The shape of the JWT payload is statically known.
    // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
    const username: string | null =
        // eslint-disable-next-line @typescript-eslint/no-unsafe-member-access, @typescript-eslint/no-non-null-assertion
        accessToken != null ? JSON.parse(atob(accessToken.split('.')[1]!)).username : null
    const canChangePassword = username != null ? !/^Github_|^Google_/.test(username) : false

    return (
        <div
            className="absolute right-4.75 top-11 z-10 flex flex-col rounded-md bg-white py-1 border"
            onClick={event => {
                event.stopPropagation()
            }}
        >
            {organization != null ? (
                <>
                    <UserMenuItem>
                        Signed in as <span className="font-bold">{organization.name}</span>
                    </UserMenuItem>
                    <UserMenuItem disabled onClick={goToProfile}>
                        Your profile
                    </UserMenuItem>
                    {canChangePassword && (
                        <UserMenuItem
                            onClick={() => {
                                setModal(<ChangePasswordModal />)
                            }}
                        >
                            Change your password
                        </UserMenuItem>
                    )}
                    <UserMenuItem onClick={signOut}>Sign out</UserMenuItem>
                </>
            ) : (
                <>
                    <UserMenuItem>You are offline.</UserMenuItem>
                    <UserMenuItem onClick={goToLoginPage}>Login</UserMenuItem>
                </>
            )}
        </div>
    )
}
