package org.enso.languageserver.session

import org.enso.jsonrpc.{Error, HasParams, HasResult, Method}
import org.enso.languageserver.filemanager.ContentRoot

import java.util.UUID

/** The connection management JSON RPC API provided by the language server.
  * See [[https://github.com/enso-org/enso/blob/main/docs/language-server/protocol-language-server.md#connection-management]]
  * for message specifications.
  */
object SessionApi {

  case object InitProtocolConnection
      extends Method("session/initProtocolConnection") {

    case class Params(clientId: UUID)

    case class Result(contentRoots: Set[ContentRoot])

    implicit val hasParams = new HasParams[this.type] {
      type Params = InitProtocolConnection.Params
    }
    implicit val hasResult = new HasResult[this.type] {
      type Result = InitProtocolConnection.Result
    }
  }

  case object SessionNotInitialisedError
      extends Error(6001, "Session not initialised")

  case object SessionAlreadyInitialisedError
      extends Error(6002, "Session already initialised")

  case object ResourcesInitializationError
      extends Error(6003, "Failed to initialize the Language Server resources")
}
