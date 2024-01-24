import { createContextStore } from '@/providers'
import type { AstId, TokenId } from '@/util/ast/abstract'
import { identity } from '@vueuse/core'

declare const portIdBrand: unique symbol
/**
 * Port identification. A port represents a fragment of code displayed/modified by the widget;
 * usually Ast nodes, but other ids are also possible (like argument placeholders).
 */
export type PortId = AstId | (string & { [portIdBrand]: never })

interface PortInfo {
  portId: PortId
  connected: boolean
}

export { injectFn as injectPortInfo, provideFn as providePortInfo }
const { provideFn, injectFn } = createContextStore('Port info', identity<PortInfo>)
