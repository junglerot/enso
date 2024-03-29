package org.enso.languageserver.protocol.json

import org.enso.jsonrpc.Error

/** Generic errors provided by the language server.
  *
  * @see [[https://github.com/enso-org/enso/blob/develop/docs/language-server/protocol-language-server.md#errors---language-server]]
  */
object ErrorApi {

  case object AccessDeniedError extends Error(100, "Access denied")

}
