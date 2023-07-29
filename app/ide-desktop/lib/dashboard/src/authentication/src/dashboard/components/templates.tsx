/** @file Renders the list of templates from which a project can be created. */
import * as React from 'react'

import PlusCircledIcon from 'enso-assets/plus_circled.svg'
import RotatingArrowIcon from 'enso-assets/rotating_arrow.svg'

import GeoImage from 'enso-assets/geo.png'
import SpreadsheetsImage from 'enso-assets/spreadsheets.png'
import VisualizeImage from 'enso-assets/visualize.png'

import * as common from 'enso-common'

import Spinner, * as spinner from './spinner'

// =================
// === Constants ===
// =================

/** The `localStorage` key used to store whether the {@link Templates} element should be
 * expanded by default. */
const IS_TEMPLATES_OPEN_KEY = `${common.PRODUCT_NAME.toLowerCase()}-is-templates-expanded`
/** The max width at which the bottom shadow should be visible. */
const MAX_WIDTH_NEEDING_SCROLL = 1031
/** The height of the bottom padding - 8px for the grid gap, and another 8px for the height
 * of the padding div. */
const PADDING_HEIGHT = 16
/** The size (both width and height) of the spinner, in pixels. */
const SPINNER_SIZE = 64
/** The duration of the "spinner done" animation. */
const SPINNER_DONE_DURATION_MS = 1000

// =============
// === Types ===
// =============

/** The CSS class to apply inset shadows on the specified side(s). */
enum ShadowClass {
    none = '',
    top = 'shadow-inset-t-lg',
    bottom = 'shadow-inset-b-lg',
    both = 'shadow-inset-v-lg',
}

// =============
// === Types ===
// =============

/** Template metadata. */
export interface Template {
    title: string
    description: string
    id: string
    background: string
}

// =================
// === Constants ===
// =================

/** The full list of templates. */
export const TEMPLATES: [Template, ...Template[]] = [
    {
        title: 'Colorado COVID',
        id: 'Colorado_COVID',
        description: 'Learn to glue multiple spreadsheets to analyses all your data at once.',
        background: '#6b7280',
    },
    {
        title: 'KMeans',
        id: 'KMeans',
        description: 'Learn where to open a coffee shop to maximize your income.',
        background: '#6b7280',
    },
    {
        title: 'NASDAQ Returns',
        id: 'NASDAQReturns',
        description: 'Learn how to clean your data to prepare it for advanced analysis.',
        background: '#6b7280',
    },
    {
        title: 'Combine spreadsheets',
        id: 'Orders',
        description: 'Glue multiple spreadsheets together to analyse all your data at once.',
        background: `url("${SpreadsheetsImage}") 50% 11% / 50% no-repeat, #479366`,
    },
    {
        title: 'Geospatial analysis',
        id: 'Restaurants',
        description: 'Learn where to open a coffee shop to maximize your income.',
        background: `url("${GeoImage}") 50% 0% / 186.7768% no-repeat, #181818`,
    },
    {
        title: 'Analyze GitHub stars',
        id: 'Stargazers',
        description: "Find out which of Enso's repositories are most popular over time.",
        background: `url("${VisualizeImage}") center / cover, #dddddd`,
    },
]

// ==========================
// === EmptyProjectButton ===
// ==========================

/** Props for an {@link EmptyProjectButton}. */
interface InternalEmptyProjectButtonProps {
    onTemplateClick: (
        name: null,
        onSpinnerStateChange: (spinnerState: spinner.SpinnerState | null) => void
    ) => void
}

/** A button that, when clicked, creates and opens a new blank project. */
function EmptyProjectButton(props: InternalEmptyProjectButtonProps) {
    const { onTemplateClick } = props
    const [spinnerState, setSpinnerState] = React.useState<spinner.SpinnerState | null>(null)

    return (
        <button
            onClick={() => {
                setSpinnerState(spinner.SpinnerState.initial)
                onTemplateClick(null, newSpinnerState => {
                    setSpinnerState(newSpinnerState)
                    if (newSpinnerState === spinner.SpinnerState.done) {
                        setTimeout(() => {
                            setSpinnerState(null)
                        }, SPINNER_DONE_DURATION_MS)
                    }
                })
            }}
            className="cursor-pointer relative text-primary h-40"
        >
            <div className="flex h-full w-full border-dashed-custom rounded-2xl">
                <div className="flex flex-col text-center items-center m-auto">
                    {spinnerState != null ? (
                        <div className="p-2">
                            <Spinner size={SPINNER_SIZE} state={spinnerState} />
                        </div>
                    ) : (
                        <img src={PlusCircledIcon} />
                    )}
                    <p className="font-semibold text-sm">New empty project</p>
                </div>
            </div>
        </button>
    )
}

// ======================
// === TemplateButton ===
// ======================

/** Props for a {@link TemplateButton}. */
interface InternalTemplateButtonProps {
    template: Template
    onTemplateClick: (
        name: string | null,
        onSpinnerStateChange: (spinnerState: spinner.SpinnerState | null) => void
    ) => void
}

/** A button that, when clicked, creates and opens a new project based on a template. */
function TemplateButton(props: InternalTemplateButtonProps) {
    const { template, onTemplateClick } = props
    const [spinnerState, setSpinnerState] = React.useState<spinner.SpinnerState | null>(null)

    const onSpinnerStateChange = React.useCallback(
        (newSpinnerState: spinner.SpinnerState | null) => {
            setSpinnerState(newSpinnerState)
            if (newSpinnerState === spinner.SpinnerState.done) {
                setTimeout(() => {
                    setSpinnerState(null)
                }, SPINNER_DONE_DURATION_MS)
            }
        },
        []
    )

    return (
        <button
            key={template.title}
            className="h-40 cursor-pointer"
            onClick={() => {
                setSpinnerState(spinner.SpinnerState.initial)
                onTemplateClick(template.id, onSpinnerStateChange)
            }}
        >
            <div
                style={{
                    background: template.background,
                }}
                className="relative flex flex-col justify-end h-full w-full rounded-2xl overflow-hidden text-white text-left"
            >
                <div className="bg-black bg-opacity-30 px-4 py-2">
                    <h2 className="text-sm font-bold">{template.title}</h2>
                    <div className="text-xs h-16 text-ellipsis py-2">{template.description}</div>
                </div>
                {spinnerState != null && (
                    <div className="absolute grid w-full h-full place-items-center">
                        <Spinner size={SPINNER_SIZE} state={spinnerState} />
                    </div>
                )}
            </div>
        </button>
    )
}

// =======================
// === TemplatesRender ===
// =======================

/** Props for a {@link TemplatesRender}. */
interface InternalTemplatesRenderProps {
    // Later this data may be requested and therefore needs to be passed dynamically.
    templates: Template[]
    onTemplateClick: (
        name: string | null,
        onSpinnerStateChange: (spinnerState: spinner.SpinnerState | null) => void
    ) => void
}

/** Render all templates, and a button to create an empty project. */
function TemplatesRender(props: InternalTemplatesRenderProps) {
    const { templates, onTemplateClick } = props

    return (
        <>
            <EmptyProjectButton onTemplateClick={onTemplateClick} />
            {templates.map(template => (
                <TemplateButton
                    key={template.id}
                    template={template}
                    onTemplateClick={onTemplateClick}
                />
            ))}
        </>
    )
}

// =================
// === Templates ===
// =================

/** Props for a {@link Templates}. */
export interface TemplatesProps {
    onTemplateClick: (
        name: string | null,
        onSpinnerStateChange: (state: spinner.SpinnerState | null) => void
    ) => void
}

/** A container for a {@link TemplatesRender} which passes it a list of templates. */
export default function Templates(props: TemplatesProps) {
    const { onTemplateClick } = props

    const [shadowClass, setShadowClass] = React.useState(
        window.innerWidth <= MAX_WIDTH_NEEDING_SCROLL ? ShadowClass.bottom : ShadowClass.none
    )
    const [isOpen, setIsOpen] = React.useState(() => {
        /** This must not be in a `useEffect` as it would flash open for one frame.
         * It can be in a `useLayoutEffect` but as that needs to be checked every re-render,
         * this is slightly more performant. */
        const savedIsOpen = localStorage.getItem(IS_TEMPLATES_OPEN_KEY)
        let result = true
        if (savedIsOpen != null) {
            try {
                result = JSON.parse(savedIsOpen) !== false
            } catch {
                // Ignored. This should only happen when a user manually sets invalid JSON into
                // the `localStorage` key used by this component.
            }
        }
        return result
    })

    // This is incorrect, but SAFE, as its value will always be assigned before any hooks are run.
    // eslint-disable-next-line @typescript-eslint/no-non-null-assertion
    const containerRef = React.useRef<HTMLDivElement>(null!)

    const toggleIsOpen = React.useCallback(() => {
        setIsOpen(oldIsOpen => !oldIsOpen)
    }, [])

    const updateShadowClass = () => {
        const element = containerRef.current
        const boundingBox = element.getBoundingClientRect()
        let newShadowClass: ShadowClass
        const shouldShowTopShadow = element.scrollTop !== 0
        // `window.innerWidth <= MAX_WIDTH_NEEDING_SCROLL` is repeated. This is intentional,
        // to avoid adding it as a dependency.
        const paddingHeight = window.innerWidth <= MAX_WIDTH_NEEDING_SCROLL ? 0 : PADDING_HEIGHT
        // Chrome has decimal places in its bounding box, which can overshoot the target size
        // slightly.
        const shouldShowBottomShadow =
            element.scrollTop + boundingBox.height + paddingHeight + 1 < element.scrollHeight
        if (shouldShowTopShadow && shouldShowBottomShadow) {
            newShadowClass = ShadowClass.both
        } else if (shouldShowTopShadow) {
            newShadowClass = ShadowClass.top
        } else if (shouldShowBottomShadow) {
            newShadowClass = ShadowClass.bottom
        } else {
            newShadowClass = ShadowClass.none
        }
        setShadowClass(newShadowClass)
    }

    React.useEffect(() => {
        window.addEventListener('resize', updateShadowClass)
        return () => {
            window.removeEventListener('resize', updateShadowClass)
        }
    })

    React.useEffect(() => {
        localStorage.setItem(IS_TEMPLATES_OPEN_KEY, JSON.stringify(isOpen))
    }, [isOpen])

    return (
        <div className="mx-2">
            <div className="flex items-center my-2">
                <div className="w-4">
                    <div
                        className={`cursor-pointer transition-all ease-in-out ${
                            isOpen ? 'rotate-90' : ''
                        }`}
                        onClick={toggleIsOpen}
                    >
                        <img src={RotatingArrowIcon} />
                    </div>
                </div>
                <h1 className="text-xl font-bold self-center">Templates</h1>
            </div>
            <div
                ref={containerRef}
                className={`grid gap-2 grid-cols-fill-60 justify-center overflow-y-scroll scroll-hidden transition-all duration-300 ease-in-out px-4 ${
                    isOpen ? `h-templates-custom ${shadowClass}` : 'h-0'
                }`}
                onScroll={updateShadowClass}
            >
                <TemplatesRender templates={TEMPLATES} onTemplateClick={onTemplateClick} />
                {/* Spacing. */}
                <div className="col-span-full h-2" />
            </div>
        </div>
    )
}
