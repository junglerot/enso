package org.enso.languageserver.runtime

import com.typesafe.scalalogging.Logger
import org.enso.jsonrpc.Error
import org.enso.languageserver.filemanager.{
  ContentRootManager,
  FileSystemFailureMapper,
  Path
}
import org.enso.languageserver.protocol.json.ErrorApi._
import org.enso.languageserver.runtime.ExecutionApi._
import org.enso.polyglot.runtime.Runtime.Api
import cats.implicits._
import java.io.File
import scala.concurrent.{ExecutionContext, Future}

final class RuntimeFailureMapper(contentRootManager: ContentRootManager) {
  private lazy val logger = Logger[RuntimeFailureMapper]

  /** Maps runtime Api error into a registry error.
    *
    * @param error runtime Api error
    * @return registry error
    */
  def mapApiError(
    error: Api.Error
  )(implicit ec: ExecutionContext): Future[ContextRegistryProtocol.Failure] = {
    implicit def liftToFuture(
      result: ContextRegistryProtocol.Failure
    ): Future[ContextRegistryProtocol.Failure] = Future.successful(result)
    error match {
      case Api.ContextNotExistError(contextId) =>
        ContextRegistryProtocol.ContextNotFound(contextId)
      case Api.EmptyStackError(contextId) =>
        ContextRegistryProtocol.EmptyStackError(contextId)
      case Api.InvalidStackItemError(contextId) =>
        ContextRegistryProtocol.InvalidStackItemError(contextId)
      case Api.ModuleNotFound(moduleName) =>
        ContextRegistryProtocol.ModuleNotFound(moduleName)
      case Api.ModuleNotFoundForExpression(expressionId) =>
        ContextRegistryProtocol.ModuleNotFoundForExpression(expressionId)
      case Api.VisualisationExpressionFailed(message, result) =>
        for (diagnostic <- result.map(toProtocolDiagnostic).sequence)
          yield ContextRegistryProtocol.VisualisationExpressionFailed(
            message,
            diagnostic
          )
      case Api.VisualisationNotFound() =>
        ContextRegistryProtocol.VisualisationNotFound
    }
  }

  /** Convert the runtime failure message to the context registry protocol
    * representation.
    *
    * @param error the error message
    * @return the registry protocol representation fo the diagnostic message
    */
  def toProtocolFailure(
    error: Api.ExecutionResult.Failure
  )(implicit
    ec: ExecutionContext
  ): Future[ContextRegistryProtocol.ExecutionFailure] =
    for (path <- findRelativePath(error.file))
      yield ContextRegistryProtocol.ExecutionFailure(error.message, path)

  /** Convert the runtime diagnostic message to the context registry protocol
    * representation.
    *
    * @param diagnostic the diagnostic message
    * @return the registry protocol representation of the diagnostic message
    */
  def toProtocolDiagnostic(diagnostic: Api.ExecutionResult.Diagnostic)(implicit
    ec: ExecutionContext
  ): Future[ContextRegistryProtocol.ExecutionDiagnostic] =
    for {
      path  <- findRelativePath(diagnostic.file)
      stack <- diagnostic.stack.map(toStackTraceElement).sequence
    } yield ContextRegistryProtocol.ExecutionDiagnostic(
      toDiagnosticType(diagnostic.kind),
      diagnostic.message,
      path,
      diagnostic.location,
      diagnostic.expressionId,
      stack
    )

  /** Convert the runtime diagnostic type to the context registry protocol
    * representation.
    *
    * @param kind the diagnostic type
    * @return the registry protocol representation of the diagnostic type
    */
  private def toDiagnosticType(
    kind: Api.DiagnosticType
  ): ContextRegistryProtocol.ExecutionDiagnosticKind =
    kind match {
      case Api.DiagnosticType.Error() =>
        ContextRegistryProtocol.ExecutionDiagnosticKind.Error
      case Api.DiagnosticType.Warning() =>
        ContextRegistryProtocol.ExecutionDiagnosticKind.Warning
    }

  /** Convert the runtime stack trace element to the context registry protocol
    * representation.
    *
    * @param element the runtime stack trace element
    * @return the registry protocol representation of the stack trace element
    */
  private def toStackTraceElement(element: Api.StackTraceElement)(implicit
    ec: ExecutionContext
  ): Future[ContextRegistryProtocol.ExecutionStackTraceElement] =
    for (path <- findRelativePath(element.file))
      yield ContextRegistryProtocol.ExecutionStackTraceElement(
        element.functionName,
        path,
        element.location,
        element.expressionId
      )

  private def findRelativePath(
    path: Option[File]
  )(implicit ec: ExecutionContext): Future[Option[Path]] =
    path match {
      case Some(value) =>
        contentRootManager.findRelativePath(value).recoverWith { error =>
          logger.warn(
            s"Could not resolve a path within a failure, " +
            s"so it will contain none: $error"
          )
          Future.successful(None)
        }
      case None =>
        Future.successful(None)
    }

}
object RuntimeFailureMapper {

  /** Create runtime failure mapper instance.
    *
    * @param contentRootManager the content root manager
    * @return a new instance of [[RuntimeFailureMapper]]
    */
  def apply(contentRootManager: ContentRootManager): RuntimeFailureMapper =
    new RuntimeFailureMapper(contentRootManager)

  /** Maps registry error into JSON RPC error.
    *
    * @param error registry error
    * @return JSON RPC error
    */
  def mapFailure(error: ContextRegistryProtocol.Failure): Error =
    error match {
      case ContextRegistryProtocol.AccessDenied =>
        AccessDeniedError
      case ContextRegistryProtocol.ContextNotFound(_) =>
        ContextNotFoundError
      case ContextRegistryProtocol.FileSystemError(error) =>
        FileSystemFailureMapper.mapFailure(error)
      case ContextRegistryProtocol.EmptyStackError(_) =>
        EmptyStackError
      case ContextRegistryProtocol.InvalidStackItemError(_) =>
        InvalidStackItemError
      case ContextRegistryProtocol.VisualisationNotFound =>
        VisualisationNotFoundError
      case ContextRegistryProtocol.ModuleNotFound(name) =>
        ModuleNotFoundError(name)
      case ContextRegistryProtocol.ModuleNotFoundForExpression(expressionId) =>
        ModuleNotFoundForExpressionError(expressionId)
      case ContextRegistryProtocol.VisualisationExpressionFailed(msg, result) =>
        VisualisationExpressionError(msg, result)
    }
}
