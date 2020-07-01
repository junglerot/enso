package org.enso.polyglot.runtime

import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

import com.fasterxml.jackson.annotation.{JsonSubTypes, JsonTypeInfo}
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.cbor.CBORFactory
import com.fasterxml.jackson.module.scala.{
  DefaultScalaModule,
  ScalaObjectMapper
}
import org.enso.searcher.Suggestion
import org.enso.text.editing.model.TextEdit

import scala.util.Try

object Runtime {

  /**
    * A common supertype for all Runtime API methods.
    */
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes(
    Array(
      new JsonSubTypes.Type(
        value = classOf[Api.CreateContextRequest],
        name  = "createContextRequest"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.CreateContextResponse],
        name  = "createContextResponse"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.DestroyContextRequest],
        name  = "destroyContextRequest"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.DestroyContextResponse],
        name  = "destroyContextResponse"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.PushContextRequest],
        name  = "pushContextRequest"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.PushContextResponse],
        name  = "pushContextResponse"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.PopContextRequest],
        name  = "popContextRequest"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.PopContextResponse],
        name  = "popContextResponse"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.RecomputeContextRequest],
        name  = "recomputeContextRequest"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.RecomputeContextResponse],
        name  = "recomputeContextResponse"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.OpenFileNotification],
        name  = "openFileNotification"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.EditFileNotification],
        name  = "editFileNotification"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.CloseFileNotification],
        name  = "closeFileNotification"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.VisualisationUpdate],
        name  = "visualisationUpdate"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.AttachVisualisation],
        name  = "attachVisualisation"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.VisualisationAttached],
        name  = "visualisationAttached"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.DetachVisualisation],
        name  = "detachVisualisation"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.VisualisationDetached],
        name  = "visualisationDetached"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.ModifyVisualisation],
        name  = "modifyVisualisation"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.VisualisationModified],
        name  = "visualisationModified"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.ExpressionValuesComputed],
        name  = "expressionValuesComputed"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.RenameProject],
        name  = "renameProject"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.ProjectRenamed],
        name  = "projectRenamed"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.ContextNotExistError],
        name  = "contextNotExistError"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.EmptyStackError],
        name  = "emptyStackError"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.ModuleNotFound],
        name  = "moduleNotFound"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.ExecutionFailed],
        name  = "executionFailed"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.VisualisationExpressionFailed],
        name  = "visualisationExpressionFailed"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.VisualisationEvaluationFailed],
        name  = "visualisationEvaluationFailed"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.VisualisationNotFound],
        name  = "visualisationNotFound"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.InvalidStackItemError],
        name  = "invalidStackItemError"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.InitializedNotification],
        name  = "initializedNotification"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.ShutDownRuntimeServer],
        name  = "shutDownRuntimeServer"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.RuntimeServerShutDown],
        name  = "runtimeServerShutDown"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.SuggestionsDatabaseUpdateNotification],
        name  = "suggestionsDatabaseUpdateNotification"
      )
    )
  )
  sealed trait Api
  sealed trait ApiRequest      extends Api
  sealed trait ApiResponse     extends Api
  sealed trait ApiNotification extends ApiResponse

  object Api {

    type ContextId       = UUID
    type ExpressionId    = UUID
    type RequestId       = UUID
    type VisualisationId = UUID

    /**
      * Indicates error response.
      */
    sealed trait Error extends ApiResponse

    /**
      * A representation of a pointer to a method definition.
      */
    case class MethodPointer(file: File, definedOnType: String, name: String)

    /**
      * A representation of an executable position in code.
      */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes(
      Array(
        new JsonSubTypes.Type(
          value = classOf[StackItem.ExplicitCall],
          name  = "explicitCall"
        ),
        new JsonSubTypes.Type(
          value = classOf[StackItem.LocalCall],
          name  = "localCall"
        )
      )
    )
    sealed trait StackItem

    object StackItem {

      /**
        * A call performed at the top of the stack, to initialize the context.
        */
      case class ExplicitCall(
        methodPointer: MethodPointer,
        thisArgumentExpression: Option[String],
        positionalArgumentsExpressions: Vector[String]
      ) extends StackItem

      /**
        * A call corresponding to "entering a function call".
        */
      case class LocalCall(expressionId: ExpressionId) extends StackItem
    }

    /**
      * An update containing information about expression.
      *
      * @param expressionId expression id.
      * @param expressionType the type of expression.
      * @param shortValue the value of expression.
      * @param methodCall the pointer to a method definition.
      */
    case class ExpressionValueUpdate(
      expressionId: ExpressionId,
      expressionType: Option[String],
      shortValue: Option[String],
      methodCall: Option[MethodPointer]
    )

    /**
      * An object representing invalidated expressions selector.
      */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes(
      Array(
        new JsonSubTypes.Type(
          value = classOf[InvalidatedExpressions.All],
          name  = "all"
        ),
        new JsonSubTypes.Type(
          value = classOf[InvalidatedExpressions.Expressions],
          name  = "expressions"
        )
      )
    )
    sealed trait InvalidatedExpressions

    object InvalidatedExpressions {

      /**
        * An object representing invalidation of all expressions.
        */
      case class All() extends InvalidatedExpressions

      /**
        * An object representing invalidation of a list of expressions.
        *
        * @param value a list of expressions to invalidate.
        */
      case class Expressions(value: Vector[ExpressionId])
          extends InvalidatedExpressions
    }

    /**
      * A notification about updated expressions of the context.
      *
      * @param contextId the context's id.
      * @param updates a list of updates.
      */
    case class ExpressionValuesComputed(
      contextId: ContextId,
      updates: Vector[ExpressionValueUpdate]
    ) extends ApiNotification

    /**
      * Represents a visualisation context.
      *
      * @param visualisationId a visualisation identifier
      * @param contextId a context identifier
      * @param expressionId an expression identifier
      */
    case class VisualisationContext(
      visualisationId: VisualisationId,
      contextId: ContextId,
      expressionId: ExpressionId
    )

    /**
      * A configuration object for properties of the visualisation.
      *
      * @param executionContextId an execution context of the visualisation
      * @param visualisationModule a qualified name of the module containing
      *                            the expression which creates visualisation
      * @param expression the expression that creates a visualisation
      */
    case class VisualisationConfiguration(
      executionContextId: ContextId,
      visualisationModule: String,
      expression: String
    )

    /** A change in the suggestions database. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes(
      Array(
        new JsonSubTypes.Type(
          value = classOf[SuggestionsDatabaseUpdate.Add],
          name  = "suggestionsDatabaseUpdateAdd"
        ),
        new JsonSubTypes.Type(
          value = classOf[SuggestionsDatabaseUpdate.Remove],
          name  = "suggestionsDatabaseUpdateRemove"
        ),
        new JsonSubTypes.Type(
          value = classOf[SuggestionsDatabaseUpdate.Modify],
          name  = "suggestionsDatabaseUpdateModify"
        )
      )
    )
    sealed trait SuggestionsDatabaseUpdate
    object SuggestionsDatabaseUpdate {

      /** Create or replace the database entry.
        *
        * @param id suggestion id
        * @param suggestion the new suggestion
        */
      case class Add(id: Long, suggestion: Suggestion)
          extends SuggestionsDatabaseUpdate

      /** Remove the database entry.
        *
        * @param id the suggestion id
        */
      case class Remove(id: Long) extends SuggestionsDatabaseUpdate

      /** Modify the database entry.
        *
        * @param id the suggestion id
        * @param name the new suggestion name
        * @param arguments the new suggestion arguments
        * @param selfType the new self type of the suggestion
        * @param returnType the new return type of the suggestion
        * @param documentation the new documentation string
        * @param scope the suggestion scope
        */
      case class Modify(
        id: Long,
        name: Option[String],
        arguments: Option[Seq[Suggestion.Argument]],
        selfType: Option[String],
        returnType: Option[String],
        documentation: Option[String],
        scope: Option[Suggestion.Scope]
      ) extends SuggestionsDatabaseUpdate
    }

    /**
      * An event signaling a visualisation update.
      *
      * @param visualisationContext a visualisation context
      * @param data a visualisation data
      */
    case class VisualisationUpdate(
      visualisationContext: VisualisationContext,
      data: Array[Byte]
    ) extends ApiNotification

    /**
      * Envelope for an Api request.
      *
      * @param requestId the request identifier.
      * @param payload the request payload.
      */
    case class Request(requestId: Option[RequestId], payload: ApiRequest)

    object Request {

      /**
        * A smart constructor for [[Request]].
        *
        * @param requestId the reqest identifier.
        * @param payload the request payload.
        * @return a request object with specified request id and payload.
        */
      def apply(requestId: RequestId, payload: ApiRequest): Request =
        Request(Some(requestId), payload)

      /**
        * A smart constructor for [[Request]].
        *
        * @param payload the request payload.
        * @return a request object without request id and specified payload.
        */
      def apply(payload: ApiRequest): Request =
        Request(None, payload)
    }

    /**
      * Envelope for an Api response.
      *
      * @param correlationId request that initiated the response
      * @param payload response
      */
    case class Response(correlationId: Option[RequestId], payload: ApiResponse)

    object Response {

      /**
        * A smart constructor for [[Response]].
        *
        * @param correlationId the request id triggering this response.
        * @param payload the response payload.
        * @return a response object with specified correlation id and payload.
        */
      def apply(correlationId: RequestId, payload: ApiResponse): Response =
        Response(Some(correlationId), payload)

      /**
        * A smart constructor for [[Response]] that was not triggered by
        * any request (i.e. a notification).
        *
        * @param payload the data carried by the response.
        * @return a response without a correlation id and specified payload.
        */
      def apply(payload: ApiResponse): Response = Response(None, payload)
    }

    /**
      * A Request sent from the client to the runtime server, to create a new
      * execution context with a given id.
      *
      * @param contextId the newly created context's id.
      */
    case class CreateContextRequest(contextId: ContextId) extends ApiRequest

    /**
      * A response sent from the server upon handling the [[CreateContextRequest]]
      *
      * @param contextId the newly created context's id.
      */
    case class CreateContextResponse(contextId: ContextId) extends ApiResponse

    /**
      * A Request sent from the client to the runtime server, to destroy an
      * execution context with a given id.
      *
      * @param contextId the destroyed context's id.
      */
    case class DestroyContextRequest(contextId: ContextId) extends ApiRequest

    /**
      * A success response sent from the server upon handling the
      * [[DestroyContextRequest]]
      *
      * @param contextId the destroyed context's id
      */
    case class DestroyContextResponse(contextId: ContextId) extends ApiResponse

    /**
      * A Request sent from the client to the runtime server, to move
      * the execution context to a new location deeper down the stack.
      *
      * @param contextId the context's id.
      * @param stackItem an item that should be pushed on the stack.
      */
    case class PushContextRequest(contextId: ContextId, stackItem: StackItem)
        extends ApiRequest

    /**
      * A response sent from the server upon handling the [[PushContextRequest]]
      *
      * @param contextId the context's id.
      */
    case class PushContextResponse(contextId: ContextId) extends ApiResponse

    /**
      * A Request sent from the client to the runtime server, to move
      * the execution context up the stack.
      *
      * @param contextId the context's id.
      */
    case class PopContextRequest(contextId: ContextId) extends ApiRequest

    /**
      * A response sent from the server upon handling the [[PopContextRequest]]
      *
      * @param contextId the context's id.
      */
    case class PopContextResponse(contextId: ContextId) extends ApiResponse

    /**
      * A Request sent from the client to the runtime server, to recompute
      * the execution context.
      *
      * @param contextId the context's id.
      * @param expressions the selector specifying which expressions should be
      * recomputed.
      */
    case class RecomputeContextRequest(
      contextId: ContextId,
      expressions: Option[InvalidatedExpressions]
    ) extends ApiRequest

    /**
      * A response sent from the server upon handling the
      * [[RecomputeContextRequest]]
      *
      * @param contextId the context's id.
      */
    case class RecomputeContextResponse(contextId: ContextId)
        extends ApiResponse

    /**
      * An error response signifying a non-existent context.
      *
      * @param contextId the context's id
      */
    case class ContextNotExistError(contextId: ContextId) extends Error

    /**
      * Signals that a module cannot be found.
      *
      * @param moduleName the module name
      */
    case class ModuleNotFound(moduleName: String) extends Error

    /**
      * Signals that execution of a context failed.
      *
      * @param contextId the context's id.
      * @param message the error message.
      */
    case class ExecutionFailed(contextId: ContextId, message: String)
        extends ApiNotification

    /**
      * Signals that an expression specified in a [[AttachVisualisation]] or
      * a [[ModifyVisualisation]] cannot be evaluated.
      *
      * @param message the reason of the failure
      */
    case class VisualisationExpressionFailed(message: String) extends Error

    /**
      * Signals that an evaluation of a code responsible for generating
      * visualisation data failed.
      *
      * @param contextId the context's id.
      * @param message the reason of the failure
      */
    case class VisualisationEvaluationFailed(
      contextId: ContextId,
      message: String
    ) extends ApiNotification

    /**
      * Signals that visualisation cannot be found.
      */
    case class VisualisationNotFound() extends Error

    /**
      * An error response signifying that stack is empty.
      *
      * @param contextId the context's id
      */
    case class EmptyStackError(contextId: ContextId) extends Error

    /**
      * An error response signifying that stack item is invalid.
      *
      * @param contextId the context's id
      */
    case class InvalidStackItemError(contextId: ContextId) extends Error

    /**
      * A notification sent to the server about switching a file to literal
      * contents.
      *
      * @param path the file being moved to memory.
      * @param contents the current file contents.
      */
    case class OpenFileNotification(path: File, contents: String)
        extends ApiRequest

    /**
      * A notification sent to the server about in-memory file contents being
      * edited.
      *
      * @param path the file being edited.
      * @param edits the diffs to apply to the contents.
      */
    case class EditFileNotification(path: File, edits: Seq[TextEdit])
        extends ApiRequest

    /**
      * A notification sent to the server about dropping the file from memory
      * back to on-disk version.
      *
      * @param path the file being closed.
      */
    case class CloseFileNotification(path: File) extends ApiRequest

    /**
      * Notification sent from the server to the client upon successful
      * initialization. Any messages sent to the server before receiving this
      * message will be dropped.
      */
    case class InitializedNotification() extends ApiResponse

    /**
      * A request sent from the client to the runtime server, to create a new
      * visualisation for an expression identified by `expressionId`.
      *
      * @param visualisationId an identifier of a visualisation
      * @param expressionId an identifier of an expression which is visualised
      * @param visualisationConfig a configuration object for properties of the
      *                            visualisation
      */
    case class AttachVisualisation(
      visualisationId: VisualisationId,
      expressionId: ExpressionId,
      visualisationConfig: VisualisationConfiguration
    ) extends ApiRequest

    /**
      * Signals that attaching a visualisation has succeeded.
      */
    case class VisualisationAttached() extends ApiResponse

    /**
      * A request sent from the client to the runtime server, to detach a
      * visualisation from an expression identified by `expressionId`.
      *
      * @param contextId an execution context identifier
      * @param visualisationId an identifier of a visualisation
      * @param expressionId an identifier of an expression which is visualised
      */
    case class DetachVisualisation(
      contextId: ContextId,
      visualisationId: VisualisationId,
      expressionId: ExpressionId
    ) extends ApiRequest

    /**
      * Signals that detaching a visualisation has succeeded.
      */
    case class VisualisationDetached() extends ApiResponse

    /**
      * A request sent from the client to the runtime server, to modify a
      * visualisation identified by `visualisationId`.
      *
      * @param visualisationId     an identifier of a visualisation
      * @param visualisationConfig a configuration object for properties of the
      *                            visualisation
      */
    case class ModifyVisualisation(
      visualisationId: VisualisationId,
      visualisationConfig: VisualisationConfiguration
    ) extends ApiRequest

    /**
      * Signals that a visualisation modification has succeeded.
      */
    case class VisualisationModified() extends ApiResponse

    /**
      * A request to shut down the runtime server.
      */
    case class ShutDownRuntimeServer() extends ApiRequest

    /**
      * Signals that the runtime server has been shut down.
      */
    case class RuntimeServerShutDown() extends ApiResponse

    /**
      * A request for project renaming.
      *
      * @param oldName the old project name
      * @param newName the new project name
      */
    case class RenameProject(oldName: String, newName: String)
        extends ApiRequest

    /**
      * Signals that project has been renamed.
      */
    case class ProjectRenamed() extends ApiResponse

    /**
      * A notification about the change in the suggestions database.
      *
      * @param updates the list of database updates
      */
    case class SuggestionsDatabaseUpdateNotification(
      updates: Seq[SuggestionsDatabaseUpdate]
    ) extends ApiNotification

    private lazy val mapper = {
      val factory = new CBORFactory()
      val mapper  = new ObjectMapper(factory) with ScalaObjectMapper
      mapper.registerModule(DefaultScalaModule)
    }

    /**
      * Serializes a Request into a byte buffer.
      *
      * @param message the message to serialize.
      * @return the serialized version of the message.
      */
    def serialize(message: Request): ByteBuffer =
      ByteBuffer.wrap(mapper.writeValueAsBytes(message))

    /**
      * Serializes a Response into a byte buffer.
      *
      * @param message the message to serialize.
      * @return the serialized version of the message.
      */
    def serialize(message: Response): ByteBuffer =
      ByteBuffer.wrap(mapper.writeValueAsBytes(message))

    /**
      * Deserializes a byte buffer into a Request message.
      *
      * @param bytes the buffer to deserialize
      * @return the deserialized message, if the byte buffer can be deserialized.
      */
    def deserializeRequest(bytes: ByteBuffer): Option[Request] =
      Try(mapper.readValue(bytes.array(), classOf[Request])).toOption

    /**
      * Deserializes a byte buffer into a Response message.
      *
      * @param bytes the buffer to deserialize
      * @return the deserialized message, if the byte buffer can be deserialized.
      */
    def deserializeResponse(bytes: ByteBuffer): Option[Response] =
      Try(mapper.readValue(bytes.array(), classOf[Response])).toOption
  }

}
