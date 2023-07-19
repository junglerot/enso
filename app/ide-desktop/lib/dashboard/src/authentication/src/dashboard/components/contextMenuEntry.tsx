/** @file An entry in a context menu. */
import * as React from 'react'

// ========================
// === ContextMenuEntry ===
// ========================

/** Props for a {@link ContextMenuEntry}. */
export interface ContextMenuEntryProps {
    disabled?: boolean
    title?: string
    onClick: (event: React.MouseEvent<HTMLButtonElement>) => void
}

/** An item in a `ContextMenu`. */
function ContextMenuEntry(props: React.PropsWithChildren<ContextMenuEntryProps>) {
    const { children, disabled = false, title, onClick } = props
    return (
        <button
            disabled={disabled}
            title={title}
            className={`${
                disabled ? 'opacity-50' : ''
            } p-1 hover:bg-gray-200 first:rounded-t-lg last:rounded-b-lg text-left`}
            onClick={event => {
                event.stopPropagation()
                onClick(event)
            }}
        >
            {children}
        </button>
    )
}

export default ContextMenuEntry
