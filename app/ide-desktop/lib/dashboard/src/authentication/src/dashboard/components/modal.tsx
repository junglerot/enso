/** @file Base modal component that provides the full-screen element that blocks mouse events. */
import * as React from 'react'

import * as modalProvider from '../../providers/modal'

// =================
// === Component ===
// =================

/** Props for a {@link Modal}. */
export interface ModalProps extends React.PropsWithChildren {
    // This can intentionally be `undefined`, in order to simplify consumers of this component.
    // eslint-disable-next-line no-restricted-syntax
    centered?: boolean | undefined
    style?: React.CSSProperties
    className?: string
    onClick?: React.MouseEventHandler<HTMLDivElement>
    onContextMenu?: React.MouseEventHandler<HTMLDivElement>
}

/** A fullscreen modal with content at the center. The background is fully opaque by default;
 * background transparency can be enabled with Tailwind's `bg-opacity` classes, like
 * `className="bg-opacity-50"`. */
export default function Modal(props: ModalProps) {
    const { children, centered = false, style, className, onClick, onContextMenu } = props
    const { unsetModal } = modalProvider.useSetModal()

    return (
        <div
            style={style}
            className={`inset-0 z-10 ${
                centered ? 'fixed w-screen h-screen grid place-items-center' : ''
            } ${className ?? ''}`}
            onClick={
                onClick ??
                (event => {
                    if (event.currentTarget === event.target && getSelection()?.type !== 'Range') {
                        event.stopPropagation()
                        unsetModal()
                    }
                })
            }
            onContextMenu={onContextMenu}
        >
            {children}
        </div>
    )
}
