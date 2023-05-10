/** @file File containing SVG icon definitions. */
/** TODO [NP]: https://github.com/enso-org/cloud-v2/issues/342
 * These should all be regular `.svg` files rather than React components, but React doesn't include
 * the `svg` files when building for Electron. Once the build scripts have been adapted to allow for
 * for this, the contents of this file should be moved back to standalone SVG files. */

// =================
// === Constants ===
// =================

export const AT = (
    <Svg path="M16 12a4 4 0 10-8 0 4 4 0 008 0zm0 0v1.5a2.5 2.5 0 005 0V12a9 9 0 10-9 9m4.5-1.206a8.959 8.959 0 01-4.5 1.207" />
)

export const LOCK = (
    <Svg path="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z" />
)

export const RIGHT_ARROW = <Svg path="M13 9l3 3m0 0l-3 3m3-3H8m13 0a9 9 0 11-18 0 9 9 0 0118 0z" />

export const CREATE_ACCOUNT = (
    <Svg path="M18 9v3m0 0v3m0-3h3m-3 0h-3m-2-5a4 4 0 11-8 0 4 4 0 018 0zM3 20a6 6 0 0112 0v1H3v-1z" />
)

export const GO_BACK = (
    <Svg path="M11 16l-4-4m0 0l4-4m-4 4h14m-5 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h7a3 3 0 013 3v1" />
)

// ===================================
// === SVGs with custom formatting ===
// ===================================

/** Icon used to indicate a warning. */
export const EXCLAMATION_ICON = (
    <svg width={18} height={18} viewBox="0 0 18 18" fill="none" xmlns="http://www.w3.org/2000/svg">
        <path
            fill="#f9fafb"
            fillOpacity={0.7}
            fillRule="evenodd"
            d="M9 0A9 9 0 1 1 9 18 9 9 0 1 1 9 0M7.5 3.5H10.5L10 10.5H8L7.5 3.5ZM8 12L10 12 10 14 8 14"
        />
    </svg>
)

/** Icon representing a file being uploaded. */
export const UPLOAD_ICON = (
    <svg width={24} height={24} viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
        <rect
            x={3}
            y={14}
            width={12}
            height={17}
            rx={2}
            transform="rotate(-90 3 14)"
            fill="currentColor"
            fillOpacity={0.2}
        />
        <path
            d="M11.5 21C10.6716 21 10 20.3284 10 19.5L10 11L13 11L13 19.5C13 20.33 12.33 21 11.5 21Z"
            fill="currentColor"
        />
        <path d="M7 11L11.5 5L16 11L7 11Z" fill="currentColor" />
    </svg>
)

/** Icon representing a file being downloaded. */
export const DOWNLOAD_ICON = (
    <svg width={24} height={24} viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
        <rect
            x={3}
            y={12}
            width={10}
            height={18}
            rx={2}
            transform="rotate(-90 3 12)"
            fill="currentColor"
            fillOpacity={0.2}
        />
        <path
            d="M11.5 7C12.33 7 13 7.67 13 8.5L13 15L10 15L10 8.5C10 7.67 10.67 7 11.5 7Z"
            fill="currentColor"
        />
        <path d="M16 15L11.5 21L7.00003 15L16 15Z" fill="currentColor" />
    </svg>
)

/** Icon representing a directory. */
export const DIRECTORY_ICON = (
    <svg width={24} height={24} viewBox="-2 -2 20 20">
        <path
            d="M0 7h16v6a2 2 0 0 1-2 2H2a2 2 0 0 1-2-2V7Zm0-4h14a2 2 0 0 1 2 2v1H0V3Zm0 0c0-1.1.9-2 2-2h4a2 2 0 0 1 2 2H0Z"
            fill="currentColor"
            fillOpacity={0.4}
        />
    </svg>
)

/** Icon representing a secret. */
export const SECRET_ICON = (
    <svg width={24} height={24} viewBox="0 0 24 24">
        <path
            d="M10.3 13a4 4 0 1 1 0-2h10a1 1 0 0 1 1 1v3a1 1 0 0 1-2 0v-2h-2v2a1 1 0 0 1-2 0v-2ZM3.5 12a1 1 0 1 1 2 0a1 1 0 1 1-2 0"
            fill="currentColor"
            fillRule="evenodd"
        />
    </svg>
)

/** Icon representing a file whose filetype does not have an associated icon. */
export const FILE_ICON = (
    <svg width={24} height={24} viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
        <path
            d="M6.5 3h8v2a2 2 0 0 0 2 2h2v13a1 1 0 0 1 -1 1h-11a1 1 0 0 1 -1 -1v-16a1 1 0 0 1 1 -1ZM15 3v2a1.5 1.5 0 0 0 1.5 1.5h2"
            fill="currentColor"
        />
    </svg>
)

/** Icon typically indicating that the item on the right is a child of the item on the left. */
export const SMALL_RIGHT_ARROW_ICON = (
    <svg width={8} height={8} viewBox="-1 0 8 8" fill="none" xmlns="http://www.w3.org/2000/svg">
        <path d="m0 0 6 4-6 4V0Z" fill="currentColor" fillOpacity={0.7} />
    </svg>
)

/** Displayed when a project is ready to start. */
export const PLAY_ICON = (
    <svg width={24} height={24} viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
        <path
            d="m10.04 7.34 6 3.85a1 1 0 0 1 0 1.68l-6 3.85a1 1 0 0 1-1.54-.84v-7.7a1 1 0 0 1 1.54-.84Z"
            fill="currentColor"
        />
        <rect
            x={1.5}
            y={1.5}
            width={21}
            height={21}
            rx={10.5}
            stroke="currentColor"
            strokeOpacity={0.1}
            strokeWidth={3}
        />
    </svg>
)

/** Displayed when a project is ready for opening an IDE. */
export const ARROW_UP_ICON = (
    <svg width={24} height={24} viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
        <rect
            width={21}
            height={21}
            x={1.5}
            y={1.5}
            rx={10.5}
            stroke="currentColor"
            strokeOpacity={0.1}
            strokeWidth={3}
        />
        <path d="M12 17a1.5 1.5 0 0 1-1.5-1.5V12h3v3.5A1.5 1.5 0 0 1 12 17Z" fill="currentColor" />
        <path
            d="M8.943 12a1 1 0 0 1-.814-1.581l3.057-4.28a1 1 0 0 1 1.628 0l3.056 4.28A1 1 0 0 1 15.057 12H8.943Z"
            fill="currentColor"
        />
    </svg>
)

/** `+`-shaped icon representing creation of an item. */
export const ADD_ICON = (
    <svg width={18} height={18} viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
        <circle cx={12} cy={12} r={12} fill="currentColor" fillOpacity={0.1} />
        <g opacity={0.66}>
            <rect x={11} y={6} width={2} height={12} fill="currentColor" />
            <rect x={6} y={11} width={12} height={2} fill="currentColor" />
        </g>
    </svg>
)

/** An icon representing creation of an item. */
export const CIRCLED_PLUS_ICON = (
    <svg
        xmlns="http://www.w3.org/2000/svg"
        width={80}
        height={80}
        viewBox="0 0 24 24"
        fill="none"
        strokeWidth={0.5}
        stroke="currentColor"
    >
        <path
            strokeLinecap="round"
            strokeLinejoin="round"
            d="M12 9v6m3-3H9m12 0a9 9 0 11-18 0 9 9 0 0118 0z"
        />
    </svg>
)

/** Icon with three bars. */
export const BARS_ICON = (
    <svg width={16} height={16} viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
        <rect x={2} y={1} width={12} height={3} fill="#767676" />
        <rect x={2} y={6} width={12} height={3} fill="#767676" />
        <rect x={2} y={11} width={12} height={3} fill="#767676" />
    </svg>
)

/** Icon indicating a search input. */
export const MAGNIFYING_GLASS_ICON = (
    <svg width={16} height={16} viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
        <g opacity={0.5}>
            <path
                d="M11.4142 10L15.6569 14.2426L14.2426 15.6569L10 11.4142L11.4142 10Z"
                fill="currentColor"
            />
            <circle cx={7} cy={7} r={5} stroke="currentColor" strokeWidth={2} />
        </g>
    </svg>
)

/** Icon indicating a chat dialog. */
export const SPEECH_BUBBLE_ICON = (
    <svg width={16} height={17} viewBox="0 0 16 17" fill="none" xmlns="http://www.w3.org/2000/svg">
        <ellipse cx={8} cy={8} rx={8} ry={7.5} fill="white" />
        <path d="M4.17269e-05 16.5L2 10.5L5.50006 14L4.17269e-05 16.5Z" fill="white" />
    </svg>
)

/** `x`-shaped icon representing the closing of a window. */
export const CLOSE_ICON = (
    <svg width={18} height={18} viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
        <circle cx={12} cy={12} r={12} fill="currentColor" fillOpacity={0.1} />
        <g opacity={0.66} transform="rotate(45 12 12)">
            <rect x={11} y={6} width={2} height={12} fill="currentColor" />
            <rect x={6} y={11} width={12} height={2} fill="currentColor" />
        </g>
    </svg>
)

export const CLOUD_ICON = (
    <svg width={18} height={18} viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
        <path
            d="M6.5 16A2.9 2.9 0 1 1 8 10.5 4 4 0 0 1 15.5 11 2 2 0 0 1 17.5 12 1.9 1.9 0 1 1 18.5 16"
            fill="currentColor"
        />
    </svg>
)

export const COMPUTER_ICON = (
    <svg width={18} height={18} viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
        <path
            d="M3.5 18.5a1 1 0 0 1 0-2h3.5v-1.5h-3.5a1 1 0 0 1-1-1v-7a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v7a1 1 0 0 1-1 1h-3.5v1.5h3.5a1 1 0 0 1 0 2ZM4 14a.5.5 0 0 1-.5-.5v-6a.5.5 0 0 1 .5-.5h9a.5.5 0 0 1 .5.5v6a.5.5 0 0 1-.5.5ZM17.3 18.5a1 1 0 0 1-1-1v-10.5a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1v10.5a1 1 0 0 1-1 1ZM17.3 9a.3.3 0 1 1 0-.6h3a.3.3 0 1 1 0 .6ZM18.8 16a.7.7 0 1 1 0-1.4.7.7 0 1 1 0 1.4Z"
            fill="currentColor"
            fillRule="evenodd"
        />
    </svg>
)

/** An icon representing a user without a profile picture. */
export const DEFAULT_USER_ICON = (
    <svg height={32} width={32} viewBox="2 2 20 20" xmlns="http://www.w3.org/2000/svg">
        <path
            d="M6 20a10 10 0 0 1 6 -18 10 10 0 0 1 6 18 6 6 0 0 0 -4 -5 4.3 4.3 0 0 0 -2 -8 4.3 4.3 0 0 0 -2 8 6 6 0 0 0 -4 5"
            fill="#888888"
        />
    </svg>
)

export interface StopIconProps {
    className?: string
}

/** Icon displayed when a project is ready to stop. */
export function StopIcon(props: StopIconProps) {
    const { className } = props
    return (
        <svg
            width={24}
            height={24}
            viewBox="0 0 24 24"
            fill="none"
            xmlns="http://www.w3.org/2000/svg"
        >
            <path
                d="m9 8L15 8a1 1 0 0 1 1 1L16 15a1 1 0 0 1 -1 1L9 16a1 1 0 0 1 -1 -1L8 9a1 1 0 0 1 1 -1"
                fill="currentColor"
            />
            <rect
                x={1.5}
                y={1.5}
                width={21}
                height={21}
                rx={10.5}
                stroke="currentColor"
                strokeOpacity={0.1}
                strokeWidth={3}
            />
            <rect
                x={1.5}
                y={1.5}
                width={21}
                height={21}
                rx={10.5}
                stroke="currentColor"
                strokeLinecap="round"
                strokeWidth={3}
                className={`animate-spin-ease origin-center transition-stroke-dasharray ${
                    className ?? ''
                }`}
            />
        </svg>
    )
}

// ===========
// === Svg ===
// ===========

/** Props for the `Svg` component. */
interface Props {
    path: string
}

/** Component for rendering SVG icons.
 *
 * @param props - Extra props for the SVG path. The `props.data` field in particular contains the
 * SVG path data. */
function Svg(props: Props) {
    return (
        <svg
            className="h-6 w-6"
            fill="none"
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth="2"
            viewBox="0 0 24 24"
            stroke="currentColor"
        >
            <path d={props.path} />
        </svg>
    )
}
