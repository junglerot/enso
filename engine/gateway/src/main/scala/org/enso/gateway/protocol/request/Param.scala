package org.enso.gateway.protocol.request

import io.circe.Decoder
import io.circe.generic.extras.semiauto.{
  deriveEnumerationDecoder,
  deriveUnwrappedDecoder
}
import io.circe.generic.semiauto.deriveDecoder
import cats.syntax.functor._

/** An element of [[Params.Array]]. */
sealed trait Param
object Param {
  implicit val paramDecoder: Decoder[Param] = List[Decoder[Param]](
    Decoder[Number].widen,
    Decoder[Bool].widen,
    Decoder[Array].widen,
    Decoder[Text].widen,
    Decoder[ClientInfo].widen,
    Decoder[ClientCapabilities].widen,
    Decoder[InitializationOptions].widen,
    Decoder[Trace].widen,
    Decoder[WorkspaceFolder].widen
  ).reduceLeft(_ or _)

  /** A string element. */
  case class Text(value: String) extends Param
  object Text {
    implicit val paramStringDecoder: Decoder[Text] = deriveUnwrappedDecoder
  }

  /** A number element. */
  case class Number(value: Int) extends Param
  object Number {
    implicit val paramNumberDecoder: Decoder[Number] = deriveUnwrappedDecoder
  }

  /** A boolean element. */
  case class Bool(value: Boolean) extends Param
  object Bool {
    implicit val paramBooleanDecoder: Decoder[Bool] =
      deriveUnwrappedDecoder
  }

  /** An array element. */
  case class Array(value: Seq[Option[Param]]) extends Param
  object Array {
    implicit val paramArrayDecoder: Decoder[Array] =
      deriveUnwrappedDecoder
  }

  /** A param of the request [[org.enso.gateway.protocol.Requests.Initialize]].
    *
    * @see [[org.enso.gateway.protocol.request.Params.InitializeParams]].
    */
  case class InitializationOptions(value: Text) extends Param
  object InitializationOptions {
    implicit val initializationOptionsDecoder: Decoder[InitializationOptions] =
      deriveUnwrappedDecoder
  }

  /** A param of the request [[org.enso.gateway.protocol.Requests.Initialize]]
    *
    * @see [[org.enso.gateway.protocol.request.Params.InitializeParams]].
    */
  case class ClientInfo(
    name: Text,
    version: Option[Text]
  ) extends Param
  object ClientInfo {
    implicit val clientInfoDecoder: Decoder[ClientInfo] = deriveDecoder
  }

  /** A param of the request [[org.enso.gateway.protocol.Requests.Initialize]].
    *
    * @see [[org.enso.gateway.protocol.request.Params.InitializeParams]].
    *      The initial trace setting.
    */
  sealed trait Trace extends Param
  object Trace {
    implicit val traceOffDecoder: Decoder[Trace] = deriveEnumerationDecoder

    case object off extends Trace

    case object messages extends Trace

    case object verbose extends Trace
  }

  /** A param of the request [[org.enso.gateway.protocol.Requests.Initialize]].
    *
    * @see [[org.enso.gateway.protocol.request.Params.InitializeParams]].
    */
  sealed trait WorkspaceFolder extends Param
  object WorkspaceFolder {
    implicit val workspaceFolderDecoder: Decoder[WorkspaceFolder] =
      List[Decoder[WorkspaceFolder]](
        Decoder[WorkspaceFolderImpl].widen
      ).reduceLeft(_ or _)

    case class WorkspaceFolderImpl() extends WorkspaceFolder
    object WorkspaceFolderImpl {
      implicit val workspaceFolderImplDecoder: Decoder[WorkspaceFolderImpl] =
        deriveDecoder
    }
  }

  /** A param of the request [[org.enso.gateway.protocol.Requests.Initialize]].
    *
    * @see [[org.enso.gateway.protocol.request.Params.InitializeParams]].
    *      The capabilities provided by the client (editor or tool).
    *      Define capabilities for dynamic registration, workspace and text document
    *      features the client supports.
    */
  case class ClientCapabilities(
    workspace: Option[clientcapabilities.Workspace]       = None,
    textDocument: Option[clientcapabilities.TextDocument] = None,
    experimental: Option[clientcapabilities.Experimental] = None
  ) extends Param
  object ClientCapabilities {
    implicit val clientCapabilitiesDecoder: Decoder[ClientCapabilities] =
      deriveDecoder
  }
}
