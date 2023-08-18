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
            className={`whitespace-nowrap first:rounded-t-2xl last:rounded-b-2xl px-4 py-2 ${
                disabled ? 'opacity-50' : ''
            } ${onClick && !disabled ? 'hover:bg-black-a10' : ''} ${
                onClick != null && !disabled ? 'cursor-pointer' : ''
            }`}
            onClick={onClick}
        >
            {children}
        </div>
    )
}

/** Props for a {@link UserMenu}. */
export interface UserMenuProps {
    onSignOut: () => void
}

/** Handling the UserMenuItem click event logic and displaying its content. */
export default function UserMenu(props: UserMenuProps) {
    const { onSignOut } = props
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
            className="absolute bg-frame-selected backdrop-blur-3xl right-2.25 top-11 z-1 flex flex-col rounded-2xl"
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
                    <UserMenuItem
                        onClick={() => {
                            onSignOut()
                            // Wait until React has switched back to drive view, before signing out.
                            window.setTimeout(() => {
                                void signOut()
                            }, 0)
                        }}
                    >
                        Sign out
                    </UserMenuItem>
                </>
            ) : (
                <>
                    <UserMenuItem>You are not signed in.</UserMenuItem>
                    <UserMenuItem onClick={goToLoginPage}>Login</UserMenuItem>
                </>
            )}
        </div>
    )
}
