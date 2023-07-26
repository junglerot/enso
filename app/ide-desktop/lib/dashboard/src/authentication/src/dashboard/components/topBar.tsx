/** @file The top-bar of dashboard. */
import * as React from 'react'

import BarsIcon from 'enso-assets/bars.svg'
import CloudIcon from 'enso-assets/cloud.svg'
import ComputerIcon from 'enso-assets/computer.svg'
import DefaultUserIcon from 'enso-assets/default_user.svg'
import MagnifyingGlassIcon from 'enso-assets/magnifying_glass.svg'
import SpeechBubbleIcon from 'enso-assets/speech_bubble.svg'

import * as backendModule from '../backend'
import * as tabModule from '../tab'

import * as backendProvider from '../../providers/backend'
import * as modalProvider from '../../providers/modal'

import UserMenu from './userMenu'

// ==============
// === TopBar ===
// ==============

/** Props for a {@link TopBar}. */
export interface TopBarProps {
    /** Whether the application may have the local backend running. */
    supportsLocalBackend: boolean
    projectName: string | null
    tab: tabModule.Tab
    toggleTab: () => void
    setBackendType: (backendType: backendModule.BackendType) => void
    isHelpChatOpen: boolean
    setIsHelpChatOpen: (isHelpChatOpen: boolean) => void
    query: string
    setQuery: (value: string) => void
}

/** The {@link TopBarProps.setQuery} parameter is used to communicate with the parent component,
 * because `searchVal` may change parent component's project list. */
export default function TopBar(props: TopBarProps) {
    const {
        supportsLocalBackend,
        projectName,
        tab,
        toggleTab,
        setBackendType,
        isHelpChatOpen,
        setIsHelpChatOpen,
        query,
        setQuery,
    } = props
    const { backend } = backendProvider.useBackend()
    const { updateModal } = modalProvider.useSetModal()

    return (
        <div className="flex mx-2 h-8">
            {supportsLocalBackend && (
                <div className="bg-gray-100 rounded-full flex flex-row flex-nowrap p-1.5">
                    <button
                        onClick={() => {
                            setBackendType(backendModule.BackendType.local)
                        }}
                        className={`${
                            backend.type === backendModule.BackendType.local
                                ? 'bg-white shadow-soft'
                                : 'opacity-50'
                        } rounded-full px-1.5 py-1`}
                    >
                        <img src={ComputerIcon} />
                    </button>
                    <button
                        onClick={() => {
                            setBackendType(backendModule.BackendType.remote)
                        }}
                        className={`${
                            backend.type === backendModule.BackendType.remote
                                ? 'bg-white shadow-soft'
                                : 'opacity-50'
                        } rounded-full px-1.5 py-1`}
                    >
                        <img src={CloudIcon} />
                    </button>
                </div>
            )}
            <div
                className={`flex items-center bg-label rounded-full pl-1 pr-2.5 mx-2 ${
                    projectName != null ? 'cursor-pointer' : 'opacity-50'
                }`}
                onClick={toggleTab}
            >
                <span
                    className={`opacity-50 overflow-hidden transition-width nowrap ${
                        tab === tabModule.Tab.dashboard ? 'm-2 w-16' : 'w-0'
                    }`}
                >
                    {projectName ?? 'Dashboard'}
                </span>
                <div className="bg-white shadow-soft rounded-full px-1.5 py-1">
                    <img src={BarsIcon} />
                </div>
                <span
                    className={`opacity-50 overflow-hidden transition-width nowrap ${
                        tab === tabModule.Tab.ide ? 'm-2 w-16' : 'w-0'
                    }`}
                >
                    {projectName ?? 'No project open'}
                </span>
            </div>
            <div className="grow flex items-center bg-label rounded-full px-2">
                <div>
                    <img src={MagnifyingGlassIcon} />
                </div>
                <input
                    type="text"
                    size={1}
                    placeholder="Click here or start typing to search for projects, data connectors, users, and more ..."
                    value={query}
                    onChange={event => {
                        setQuery(event.target.value)
                    }}
                    className="flex-1 mx-2 bg-transparent"
                />
            </div>
            <div className="grow" />
            {!isHelpChatOpen && (
                <div
                    className="flex cursor-pointer items-center bg-help rounded-full px-2.5 text-white mx-2"
                    onClick={() => {
                        setIsHelpChatOpen(true)
                    }}
                >
                    <span className="whitespace-nowrap">help chat</span>
                    <div className="ml-2">
                        <img src={SpeechBubbleIcon} />
                    </div>
                </div>
            )}
            {/* User profile and menu. */}
            <div className="transform w-8">
                <div
                    onClick={event => {
                        event.stopPropagation()
                        updateModal(oldModal => (oldModal?.type === UserMenu ? null : <UserMenu />))
                    }}
                    className="rounded-full w-8 h-8 bg-cover cursor-pointer"
                >
                    <img src={DefaultUserIcon} />
                </div>
            </div>
        </div>
    )
}
