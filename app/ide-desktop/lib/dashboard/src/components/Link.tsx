/** @file A styled colored link with an icon. */
import * as React from 'react'

import * as router from 'react-router-dom'

import SvgMask from '#/components/SvgMask'

// ============
// === Link ===
// ============

/** Props for a {@link Link}. */
export interface LinkProps {
  to: string
  icon: string
  text: string
}

/** A styled colored link with an icon. */
export default function Link(props: LinkProps) {
  const { to, icon, text } = props
  return (
    <router.Link
      to={to}
      className="flex gap-2 items-center font-bold text-blue-500 hover:text-blue-700 focus:text-blue-700 text-xs text-center transition-all duration-300"
    >
      <SvgMask src={icon} />
      {text}
    </router.Link>
  )
}
