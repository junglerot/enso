package org.enso.polyglot.runtime

import com.fasterxml.jackson.annotation.{
  JsonIgnoreProperties,
  JsonSubTypes,
  JsonTypeInfo
}
import com.fasterxml.jackson.module.scala.deser.ScalaObjectDeserializerModule
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.cbor.CBORFactory
import com.fasterxml.jackson.module.scala.{
  ClassTagExtensions,
  DefaultScalaModule
}
import org.enso.editions.LibraryName
import org.enso.logger.masking.{MaskedPath, MaskedString, ToLogString}
import org.enso.pkg.{ComponentGroups, QualifiedName}
import org.enso.polyglot.{ModuleExports, Suggestion}
import org.enso.polyglot.data.{Tree, TypeGraph}
import org.enso.text.editing.model
import org.enso.text.editing.model.{Range, TextEdit}

import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import scala.util.Try

object Runtime {

  /** A common supertype for all Runtime API methods.
    */
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes(
    Array(
      new JsonSubTypes.Type(
        value = classOf[Api.Request],
        name  = "request"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.Response],
        name  = "response"
      ),
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
        value = classOf[Api.InterruptContextRequest],
        name  = "interruptContextRequest"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.InterruptContextResponse],
        name  = "interruptContextResponse"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.GetComponentGroupsRequest],
        name  = "getComponentGroupsRequest"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.GetComponentGroupsResponse],
        name  = "getComponentGroupsResponse"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.OpenFileRequest],
        name  = "setModuleSourcesNotification"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.OpenFileResponse.type],
        name  = "moduleSourcesSetNotification"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.EditFileNotification],
        name  = "editFileNotification"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.SetExpressionValueNotification],
        name  = "setExpressionValueNotification"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.CloseFileNotification],
        name  = "closeFileNotification"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.VisualizationUpdate],
        name  = "visualizationUpdate"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.FileEdit],
        name  = "fileEdit"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.AttachVisualization],
        name  = "attachVisualization"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.ExecuteExpression],
        name  = "executeExpression"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.VisualizationAttached],
        name  = "visualizationAttached"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.DetachVisualization],
        name  = "detachVisualization"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.VisualizationDetached],
        name  = "visualizationDetached"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.ModifyVisualization],
        name  = "modifyVisualization"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.VisualizationModified],
        name  = "visualizationModified"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.ExpressionUpdates],
        name  = "expressionUpdates"
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
        value = classOf[Api.ProjectRenameFailed],
        name  = "projectRenameFailed"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.RenameSymbol],
        name  = "renameSymbol"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.SymbolRenamed],
        name  = "symbolRenamed"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.SymbolRenameFailed],
        name  = "symbolRenameFailed"
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
        value = classOf[Api.ExecutionUpdate],
        name  = "executionUpdate"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.ExecutionFailed],
        name  = "executionFailed"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.ExecutionComplete],
        name  = "executionSuccessful"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.VisualizationExpressionFailed],
        name  = "visualizationExpressionFailed"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.VisualizationEvaluationFailed],
        name  = "visualizationEvaluationFailed"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.VisualizationNotFound],
        name  = "visualizationNotFound"
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
        value = classOf[Api.SuggestionsDatabaseModuleUpdateNotification],
        name  = "suggestionsDatabaseModuleUpdateNotification"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.SuggestionsDatabaseSuggestionsLoadedNotification],
        name  = "suggestionsDatabaseSuggestionsLoadedNotification"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.AnalyzeModuleInScopeJobFinished],
        name  = "analyzeModuleInScopeJobFinished"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.InvalidateModulesIndexRequest],
        name  = "invalidateModulesIndexRequest"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.InvalidateModulesIndexResponse],
        name  = "invalidateModulesIndexResponse"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.GetTypeGraphRequest],
        name  = "getTypeGraphRequest"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.GetTypeGraphResponse],
        name  = "getTypeGraphResponse"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.LibraryLoaded],
        name  = "libraryLoaded"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.ProgressNotification],
        name  = "progressNotification"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.AcquireLockRequest],
        name  = "acquireLockRequest"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.ReleaseLockRequest],
        name  = "releaseLockRequest"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.LockAcquired],
        name  = "lockAcquired"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.CannotAcquireImmediately],
        name  = "cannotAcquireImmediately"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.LockAcquireFailed],
        name  = "lockAcquireFailed"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.LockReleased],
        name  = "lockReleased"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.LockReleaseFailed],
        name  = "lockReleaseFailed"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.DeserializeLibrarySuggestions],
        name  = "deserializeLibrarySuggestions"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.StartBackgroundProcessing],
        name  = "startBackgroundProcessing"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.BackgroundJobsStartedNotification],
        name  = "backgroundJobsStartedNotification"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.SerializeModule],
        name  = "serializeModule"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.SetExecutionEnvironmentRequest],
        name  = "setExecutionEnvironmentRequest"
      ),
      new JsonSubTypes.Type(
        value = classOf[Api.SetExecutionEnvironmentResponse],
        name  = "setExecutionEnvironmentResponse"
      )
    )
  )
  sealed trait Api
  sealed trait ApiEnvelope     extends Api
  sealed trait ApiRequest      extends Api
  sealed trait ApiResponse     extends Api
  sealed trait ApiNotification extends ApiResponse

  object Api {

    type ContextId       = UUID
    type ExpressionId    = UUID
    type RequestId       = UUID
    type VisualizationId = UUID

    /** Indicates error response.
      */
    sealed trait Error extends ApiResponse

    /** A representation of a pointer to a method definition. */
    case class MethodPointer(
      module: String,
      definedOnType: String,
      name: String
    )

    /** A representation of a method call.
      *
      * @param methodPointer the method pointer of a call
      * @param notAppliedArguments indexes of arguments that have not been applied
      * to this method
      */
    case class MethodCall(
      methodPointer: MethodPointer,
      notAppliedArguments: Vector[Int]
    )
    object MethodCall {

      /** Create a method call with all the arguments applied.
        *
        * @param methodPointer the method pointer of a call
        * @return a new [[MethodCall]].
        */
      def apply(methodPointer: MethodPointer): MethodCall =
        MethodCall(methodPointer, Vector())
    }

    /** Contains a method pointer with information on the partially applied
      * arguments positions.
      *
      * @param methodPointer the method pointer
      * @param notAppliedArguments indexes of arguments that have not been applied
      * to this method
      */
    case class FunctionSchema(
      methodPointer: MethodPointer,
      notAppliedArguments: Vector[Int]
    )

    /** A representation of an executable position in code.
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

      /** A call performed at the top of the stack, to initialize the context.
        */
      case class ExplicitCall(
        methodPointer: MethodPointer,
        thisArgumentExpression: Option[String],
        positionalArgumentsExpressions: Vector[String]
      ) extends StackItem
          with ToLogString {

        /** @inheritdoc */
        override def toLogString(shouldMask: Boolean): String =
          s"ExplicitCall(" +
          s"methodPointer=$methodPointer,thisArgumentExpression=" +
          (if (shouldMask) thisArgumentExpression.map(_ => STUB)
           else thisArgumentExpression) +
          ",positionalArgumentExpression=" +
          (if (shouldMask) positionalArgumentsExpressions.map(_ => STUB)
           else positionalArgumentsExpressions) +
          ")"
      }

      /** A call corresponding to "entering a function call". */
      case class LocalCall(expressionId: ExpressionId) extends StackItem
    }

    /** An update about the computed expression.
      *
      * @param expressionId the expression id
      * @param expressionType the type of expression
      * @param methodCall the underlying method call of this expression
      * @param profilingInfo profiling information about the execution of this
      * expression
      * @param fromCache whether or not the value for this expression came
      * from the cache
      * @param typeChanged whether or not the type of the value or method definition
      * has changed from the one that was cached, if any
      * @param payload an extra information about the computed value
      */
    case class ExpressionUpdate(
      expressionId: ExpressionId,
      expressionType: Option[String],
      methodCall: Option[MethodCall],
      profilingInfo: Vector[ProfilingInfo],
      fromCache: Boolean,
      typeChanged: Boolean,
      payload: ExpressionUpdate.Payload
    )
    object ExpressionUpdate {

      /** Base trait for expression payloads. */
      @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
      @JsonSubTypes(
        Array(
          new JsonSubTypes.Type(
            value = classOf[Payload.Value],
            name  = "expressionUpdatePayloadValue"
          ),
          new JsonSubTypes.Type(
            value = classOf[Payload.DataflowError],
            name  = "expressionUpdatePayloadDataflowError"
          ),
          new JsonSubTypes.Type(
            value = classOf[Payload.Panic],
            name  = "expressionUpdatePayloadPanic"
          ),
          new JsonSubTypes.Type(
            value = classOf[Payload.Pending],
            name  = "expressionUpdatePayloadPending"
          )
        )
      )
      sealed trait Payload
      object Payload {

        /** Indicates that the expression was computed to a value.
          *
          * @param warnings information about attached warnings.
          * @param functionSchema if the value represents a function, the function schema of that function, empty option otherwise
          */
        case class Value(
          warnings: Option[Value.Warnings]       = None,
          functionSchema: Option[FunctionSchema] = None
        ) extends Payload

        object Value {

          /** Information about warnings associated with the value.
            *
            * @param count the number of attached warnings.
            * @param warning textual representation of the attached warning.
            * @param reachedMaxCount true when reported a maximal number of allowed warnings, false otherwise.
            */
          case class Warnings(
            count: Int,
            warning: Option[String],
            reachedMaxCount: Boolean
          )
        }

        /** TBD
          */
        case class Pending(message: Option[String], progress: Option[Double])
            extends Payload;

        /** Indicates that the expression was computed to an error.
          *
          * @param trace the list of expressions leading to the root error.
          */
        case class DataflowError(trace: Seq[ExpressionId]) extends Payload

        /** Indicates that the expression failed with the runtime exception.
          *
          * @param message the error message
          * @param trace the stack trace
          */
        case class Panic(
          message: String,
          trace: Seq[ExpressionId]
        ) extends Payload
            with ToLogString {

          /** @inheritdoc */
          override def toLogString(shouldMask: Boolean): String =
            s"Panic(message=${if (shouldMask) STUB else message},trace=$trace)"
        }

      }
    }

    /** An object representing profiling information about an executed
      * expression.
      */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes(
      Array(
        new JsonSubTypes.Type(
          value = classOf[ProfilingInfo.ExecutionTime],
          name  = "executionTime"
        )
      )
    )
    sealed trait ProfilingInfo
    object ProfilingInfo {

      /** A representation of the time elapsed during execution.
        *
        * @param nanoTime the time elapsed during execution in nanoseconds
        */
      case class ExecutionTime(nanoTime: Long) extends ProfilingInfo
    }

    /** An object representing invalidated expressions selector.
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

      /** An object representing invalidation of all expressions.
        */
      case class All() extends InvalidatedExpressions

      /** An object representing invalidation of a list of expressions.
        *
        * @param value a list of expressions to invalidate.
        */
      case class Expressions(value: Vector[ExpressionId])
          extends InvalidatedExpressions
    }

    /** A notification about updated expressions of the context.
      *
      * @param contextId the context's id.
      * @param updates a list of updates.
      */
    final case class ExpressionUpdates(
      contextId: ContextId,
      updates: Set[ExpressionUpdate]
    ) extends ApiNotification

    /** Represents a visualization context.
      *
      * @param visualizationId a visualization identifier
      * @param contextId a context identifier
      * @param expressionId an expression identifier
      */
    case class VisualizationContext(
      visualizationId: VisualizationId,
      contextId: ContextId,
      expressionId: ExpressionId
    )

    /** A visualization expression. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes(
      Array(
        new JsonSubTypes.Type(
          value = classOf[VisualizationExpression.Text],
          name  = "visualizationExpressionText"
        ),
        new JsonSubTypes.Type(
          value = classOf[VisualizationExpression.ModuleMethod],
          name  = "visualizationExpressionModuleMethod"
        )
      )
    )
    sealed trait VisualizationExpression extends ToLogString {
      def module:                         String
      def positionalArgumentsExpressions: Vector[String]
    }
    object VisualizationExpression {

      /** Visualization expression represented as a text.
        *
        * @param module a qualified module name containing the expression
        * @param expression an expression that creates a visualization
        */
      case class Text(module: String, expression: String)
          extends VisualizationExpression {

        override val positionalArgumentsExpressions: Vector[String] =
          Vector()

        /** @inheritdoc */
        override def toLogString(shouldMask: Boolean): String =
          s"Text(module=$module" +
          s",expression=$expression" +
          ")"
      }

      /** Visualization expression represented as a module method.
        *
        * @param methodPointer a pointer to a method definition
        * @param positionalArgumentsExpressions the list of arguments that will
        * be passed to the method
        */
      case class ModuleMethod(
        methodPointer: MethodPointer,
        positionalArgumentsExpressions: Vector[String]
      ) extends VisualizationExpression {

        /** @inheritdoc */
        override val module: String = methodPointer.module

        /** @inheritdoc */
        override def toLogString(shouldMask: Boolean): String =
          s"ModuleMethod(methodPointer=$methodPointer," +
          "positionalArgumentsExpressions=" +
          positionalArgumentsExpressions.mkString("[", ",", "]") +
          ")"
      }
    }

    /** A configuration object for properties of the visualization.
      *
      * @param executionContextId an execution context of the visualization
      * @param expression the expression that creates a visualization
      * @param visualizationModule module to evaluate arguments for visualization at
      */
    case class VisualizationConfiguration(
      executionContextId: ContextId,
      expression: VisualizationExpression,
      visualizationModule: String
    ) extends ToLogString {

      /** @inheritdoc */
      override def toLogString(shouldMask: Boolean): String =
        s"VisualizationConfiguration(" +
        s"executionContextId=$executionContextId," +
        s"expression=${expression.toLogString(shouldMask)})" +
        s"visualizationModule=$visualizationModule)"
    }

    /** An operation applied to the suggestion argument. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes(
      Array(
        new JsonSubTypes.Type(
          value = classOf[SuggestionArgumentAction.Add],
          name  = "suggestionArgumentActionAdd"
        ),
        new JsonSubTypes.Type(
          value = classOf[SuggestionArgumentAction.Remove],
          name  = "suggestionArgumentActionRemove"
        ),
        new JsonSubTypes.Type(
          value = classOf[SuggestionArgumentAction.Modify],
          name  = "suggestionArgumentActionModify"
        )
      )
    )
    sealed trait SuggestionArgumentAction extends ToLogString
    object SuggestionArgumentAction {

      /** Add the argument to a list.
        *
        * @param index the position of the argument
        * @param argument the argument to add
        */
      case class Add(index: Int, argument: Suggestion.Argument)
          extends SuggestionArgumentAction {

        /** @inheritdoc */
        override def toLogString(shouldMask: Boolean): String =
          "Add(" +
          s"index=$index," +
          s"argument=${argument.toLogString(shouldMask)}" +
          ")"
      }

      /** Remove the argument from a list.
        *
        * @param index the position of the arugment
        */
      case class Remove(index: Int) extends SuggestionArgumentAction {

        /** @inheritdoc */
        override def toLogString(shouldMask: Boolean): String =
          s"Remove(index=$index)"
      }

      /** Modify the argument at the specified index.
        *
        * @param index the position of the argument
        * @param name the name to update
        * @param reprType the argument type to update
        * @param isSuspended the suspended flag to update
        * @param hasDefault the default flag to update
        * @param defaultValue the default value to update
        */
      case class Modify(
        index: Int,
        name: Option[String]                 = None,
        reprType: Option[String]             = None,
        isSuspended: Option[Boolean]         = None,
        hasDefault: Option[Boolean]          = None,
        defaultValue: Option[Option[String]] = None
      ) extends SuggestionArgumentAction {

        /** @inheritdoc */
        override def toLogString(shouldMask: Boolean): String =
          "Modify(" +
          s"index=$index," +
          s"name=$name," +
          s"reprType=$reprType," +
          s"isSuspended=$isSuspended," +
          s"hasDefault=$hasDefault,defaultValue=" +
          (if (shouldMask) defaultValue.map(_ => STUB) else defaultValue) +
          ")"
      }
    }

    /** An operation applied to the update */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes(
      Array(
        new JsonSubTypes.Type(
          value = classOf[SuggestionAction.Add],
          name  = "suggestionActionAdd"
        ),
        new JsonSubTypes.Type(
          value = classOf[SuggestionAction.Remove],
          name  = "suggestionActionRemove"
        ),
        new JsonSubTypes.Type(
          value = classOf[SuggestionAction.Modify],
          name  = "suggestionActionModify"
        )
      )
    )
    sealed trait SuggestionAction extends ToLogString
    object SuggestionAction {

      /** Add the suggestion. */
      case class Add() extends SuggestionAction {

        /** @inheritdoc */
        override def toLogString(shouldMask: Boolean): String =
          "Add()"
      }

      /** Remove the suggestion. */
      case class Remove() extends SuggestionAction {

        /** @inheritdoc */
        override def toLogString(shouldMask: Boolean): String =
          "Remove()"
      }

      /** Modify the suggestion.
        *
        * @param externalId the external id to update
        * @param arguments the arguments to update
        * @param returnType the return type to update
        * @param documentation the documentation string to update
        * @param scope the scope to update
        * @param reexport the reexport field to update
        */
      case class Modify(
        externalId: Option[Option[Suggestion.ExternalID]] = None,
        arguments: Option[Seq[SuggestionArgumentAction]]  = None,
        returnType: Option[String]                        = None,
        documentation: Option[Option[String]]             = None,
        scope: Option[Suggestion.Scope]                   = None,
        reexport: Option[Option[String]]                  = None
      ) extends SuggestionAction
          with ToLogString {

        /** @inheritdoc */
        override def toLogString(shouldMask: Boolean): String =
          "Modify(" +
          s"externalId=$externalId" +
          s",arguments=${arguments.map(_.map(_.toLogString(shouldMask)))}" +
          s",returnType=$returnType" +
          s",documentation=" +
          (if (shouldMask) documentation.map(_.map(_ => STUB))
           else documentation) +
          s",scope=$scope" +
          s",reexport=$reexport" +
          ")"
      }
    }

    /** An action to apply to the suggestions database. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes(
      Array(
        new JsonSubTypes.Type(
          value = classOf[SuggestionsDatabaseAction.Clean],
          name  = "suggestionsDatabaseActionClean"
        )
      )
    )
    sealed trait SuggestionsDatabaseAction
    object SuggestionsDatabaseAction {

      /** Remove all module entries from the database.
        *
        * @param module the module name
        */
      case class Clean(module: String) extends SuggestionsDatabaseAction
    }

    case class ExportsUpdate(
      exports: ModuleExports,
      action: ExportsAction
    )

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes(
      Array(
        new JsonSubTypes.Type(
          value = classOf[ExportsAction.Add],
          name  = "exportsActionAdd"
        ),
        new JsonSubTypes.Type(
          value = classOf[ExportsAction.Remove],
          name  = "exportsActionRemove"
        )
      )
    )
    sealed trait ExportsAction
    object ExportsAction {
      case class Add()    extends ExportsAction
      case class Remove() extends ExportsAction
    }

    /** A suggestion update.
      *
      * @param suggestion the original suggestion
      * @param action the operation that is applied to the update
      */
    case class SuggestionUpdate(
      suggestion: Suggestion,
      action: SuggestionAction
    ) extends ToLogString {

      /** @inheritdoc */
      override def toLogString(shouldMask: Boolean): String =
        "SuggestionUpdate(suggestion=" +
        suggestion.toLogString(shouldMask) +
        s",action=${action.toLogString(shouldMask)})"
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes(
      Array(
        new JsonSubTypes.Type(
          value = classOf[DiagnosticType.Error.type],
          name  = "diagnosticTypeError"
        ),
        new JsonSubTypes.Type(
          value = classOf[DiagnosticType.Warning.type],
          name  = "diagnosticTypeWarning"
        )
      )
    )
    sealed trait DiagnosticType

    object DiagnosticType {
      case object Error   extends DiagnosticType
      case object Warning extends DiagnosticType
    }

    /** The element in the stack trace.
      *
      * @param functionName the function containing the stack call
      * @param file the location of a file
      * @param location the location of the element in a file
      * @param expressionId the id of an expression
      */
    case class StackTraceElement(
      functionName: String,
      file: Option[File],
      location: Option[Range],
      expressionId: Option[ExpressionId]
    ) extends ToLogString {

      /** @inheritdoc */
      override def toLogString(shouldMask: Boolean): String =
        "StackTraceElement(" +
        s"functionName=$functionName," +
        s"file=${file.map(f => MaskedPath(f.toPath).toLogString(shouldMask))}" +
        s"location=$location," +
        s"expressionId=$expressionId" +
        ")"
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes(
      Array(
        new JsonSubTypes.Type(
          value = classOf[ExecutionResult.Diagnostic],
          name  = "executionOutcomeDiagnostic"
        ),
        new JsonSubTypes.Type(
          value = classOf[ExecutionResult.Failure],
          name  = "executionOutcomeFailure"
        )
      )
    )
    @JsonIgnoreProperties(Array("error", "failure"))
    sealed trait ExecutionResult extends ToLogString {

      /** Checks if this result represents a critical failure. * */
      def isFailure: Boolean

      /** Checks if this result represents a non-critical error. * */
      def isError: Boolean
    }
    object ExecutionResult {

      /** A diagnostic object produced as a compilation outcome, like error or
        * warning.
        *
        * @param kind the diagnostic type
        * @param message the diagnostic message
        * @param file the location of a file
        * @param location the location of the diagnostic object in a file
        * @param expressionId the id of related expression
        * @param stack the stack trace
        */
      case class Diagnostic(
        kind: DiagnosticType,
        message: Option[String],
        file: Option[File],
        location: Option[model.Range],
        expressionId: Option[ExpressionId],
        stack: Vector[StackTraceElement]
      ) extends ExecutionResult {

        /** @inheritdoc */
        override def toLogString(shouldMask: Boolean): String =
          "Diagnostic(" +
          s"kind=$kind," +
          s"message=${message.map(m => MaskedString(m).toLogString(shouldMask))}," +
          s"file=${file.map(f => MaskedPath(f.toPath).toLogString(shouldMask))}," +
          s"location=$location," +
          s"expressionId=$expressionId," +
          s"stack=${stack.map(_.toLogString(shouldMask))}" +
          ")"

        override def isFailure: Boolean = false

        override def isError: Boolean = kind == DiagnosticType.Error
      }

      object Diagnostic {

        /** Create an error diagnostic message.
          *
          * @param message the diagnostic message
          * @param file the location of a file
          * @param location the location of the diagnostic object in a file
          * @param expressionId the id of related expression
          * @param stack the stack trace
          * @return the instance of an error [[Diagnostic]] message
          */
        def error(
          message: String,
          file: Option[File]                 = None,
          location: Option[model.Range]      = None,
          expressionId: Option[ExpressionId] = None,
          stack: Vector[StackTraceElement]   = Vector()
        ): Diagnostic =
          Diagnostic(
            DiagnosticType.Error,
            Option(message),
            file,
            location,
            expressionId,
            stack
          )

        /** Create a warning diagnostic message.
          *
          * @param message the diagnostic message
          * @param file the location of a file
          * @param location the location of the diagnostic object in a file
          * @param expressionId the id of related expression
          * @param stack the stack trace
          * @return the instance of a warning [[Diagnostic]] message
          */
        def warning(
          message: String,
          file: Option[File],
          location: Option[model.Range]      = None,
          expressionId: Option[ExpressionId] = None,
          stack: Vector[StackTraceElement]   = Vector()
        ): Diagnostic =
          Diagnostic(
            DiagnosticType.Warning,
            Option(message),
            file,
            location,
            expressionId,
            stack
          )
      }

      /** A critical failure when attempting to execute a context.
        *
        * @param message the error message
        * @param file the location of a file producing the error
        */
      case class Failure(message: String, file: Option[File])
          extends ExecutionResult {

        /** @inheritdoc */
        override def toLogString(shouldMask: Boolean): String =
          s"Failure(message=$message,file=" +
          file.map(f => MaskedPath(f.toPath).toLogString(shouldMask)) +
          ")"

        override def isFailure: Boolean = true

        override def isError: Boolean = true
      }

    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes(
      Array(
        new JsonSubTypes.Type(
          value = classOf[Export.Qualified],
          name  = "exportQualified"
        ),
        new JsonSubTypes.Type(
          value = classOf[Export.Unqualified],
          name  = "exportUnqualified"
        )
      )
    )
    sealed trait Export {
      def module: String
    }
    object Export {

      /** Qualified module re-export.
        *
        * @param module the module name that exports the given module
        * @param alias new module name if the module was renamed in the export
        * clause
        */
      case class Qualified(module: String, alias: Option[String]) extends Export

      /** Unqualified module export.
        *
        * @param module the module name that exports the given module
        */
      case class Unqualified(module: String) extends Export
    }

    /** Base trait for runtime execution environment. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes(
      Array(
        new JsonSubTypes.Type(
          value = classOf[ExecutionEnvironment.Live],
          name  = "executionEnvironmentLive"
        ),
        new JsonSubTypes.Type(
          value = classOf[ExecutionEnvironment.Design],
          name  = "executionEnvironmentDesign"
        )
      )
    )
    sealed trait ExecutionEnvironment {

      /** The environment name. */
      def name: String
    }
    object ExecutionEnvironment {

      final case class Live() extends ExecutionEnvironment {

        /** @inheritdoc */
        override val name: String = "live"
      }

      final case class Design() extends ExecutionEnvironment {

        /** @inheritdoc */
        override val name: String = "design"
      }
    }

    /** The notification about the execution status.
      *
      * @param contextId the context's id
      * @param diagnostics the list of diagnostic messages
      */
    final case class ExecutionUpdate(
      contextId: ContextId,
      diagnostics: Seq[ExecutionResult.Diagnostic]
    ) extends ApiNotification
        with ToLogString {

      /** @inheritdoc */
      override def toLogString(shouldMask: Boolean): String =
        "ExecutionUpdate(" +
        s"contextId=$contextId,diagnostics=" +
        diagnostics.map(_.toLogString(shouldMask)) +
        ")"
    }

    /** Signals about the failure during the context execution.
      *
      * @param contextId the context's id
      * @param result the result of the execution
      */
    final case class ExecutionFailed(
      contextId: ContextId,
      result: ExecutionResult
    ) extends ApiNotification
        with ToLogString {

      /** @inheritdoc */
      override def toLogString(shouldMask: Boolean): String =
        "ExecutionFailed(" +
        s"contextId=$contextId,result=" +
        result.toLogString(shouldMask) +
        ")"
    }

    /** An event signaling a visualization update.
      *
      * @param visualizationContext a visualization context
      * @param data a visualization data
      */
    final case class VisualizationUpdate(
      visualizationContext: VisualizationContext,
      data: Array[Byte]
    ) extends ApiNotification
        with ToLogString {

      override def toLogString(shouldMask: Boolean): String = {
        "VisualizationUpdate(" +
        s"visualizationContext=$visualizationContext,data=" +
        (if (shouldMask) STUB else data.toString()) +
        ")"
      }
    }

    /** A list of edits applied to a file.
      *
      * @param path the module file path
      * @param edits the list of text edits
      * @param oldVersion the current version of a buffer
      * @param newVersion the version of a buffer after applying all edits
      */
    final case class FileEdit(
      path: File,
      edits: Vector[TextEdit],
      oldVersion: String,
      newVersion: String
    ) extends ApiNotification
        with ToLogString {

      override def toLogString(shouldMask: Boolean): String =
        "FileEdit(" +
        s"path=${MaskedPath(path.toPath).toLogString(shouldMask)}," +
        s"edits=${edits.mkString("[", ",", "]")}" +
        s"oldVersion=$oldVersion" +
        s"newVersion=$newVersion" +
        ")"
    }

    /** Envelope for an Api request.
      *
      * @param requestId the request identifier.
      * @param payload the request payload.
      */
    final case class Request(requestId: Option[RequestId], payload: ApiRequest)
        extends ApiEnvelope

    object Request {

      /** A smart constructor for [[Request]].
        *
        * @param requestId the reqest identifier.
        * @param payload the request payload.
        * @return a request object with specified request id and payload.
        */
      def apply(requestId: RequestId, payload: ApiRequest): Request =
        Request(Some(requestId), payload)

      /** A smart constructor for [[Request]].
        *
        * @param payload the request payload.
        * @return a request object without request id and specified payload.
        */
      def apply(payload: ApiRequest): Request =
        Request(None, payload)
    }

    /** Envelope for an Api response.
      *
      * @param correlationId request that initiated the response
      * @param payload response
      */
    final case class Response(
      correlationId: Option[RequestId],
      payload: ApiResponse
    ) extends ApiEnvelope

    object Response {

      /** A smart constructor for [[Response]].
        *
        * @param correlationId the request id triggering this response.
        * @param payload the response payload.
        * @return a response object with specified correlation id and payload.
        */
      def apply(correlationId: RequestId, payload: ApiResponse): Response =
        Response(Some(correlationId), payload)

      /** A smart constructor for [[Response]] that was not triggered by
        * any request (i.e. a notification).
        *
        * @param payload the data carried by the response.
        * @return a response without a correlation id and specified payload.
        */
      def apply(payload: ApiResponse): Response = Response(None, payload)
    }

    /** A Request sent from the client to the runtime server, to create a new
      * execution context with a given id.
      *
      * @param contextId the newly created context's id.
      */
    final case class CreateContextRequest(contextId: ContextId)
        extends ApiRequest

    /** A response sent from the server upon handling the [[CreateContextRequest]]
      *
      * @param contextId the newly created context's id.
      */
    final case class CreateContextResponse(contextId: ContextId)
        extends ApiResponse

    /** A Request sent from the client to the runtime server, to destroy an
      * execution context with a given id.
      *
      * @param contextId the destroyed context's id.
      */
    final case class DestroyContextRequest(contextId: ContextId)
        extends ApiRequest

    /** A success response sent from the server upon handling the
      * [[DestroyContextRequest]]
      *
      * @param contextId the destroyed context's id
      */
    final case class DestroyContextResponse(contextId: ContextId)
        extends ApiResponse

    /** A Request sent from the client to the runtime server, to move
      * the execution context to a new location deeper down the stack.
      *
      * @param contextId the context's id.
      * @param stackItem an item that should be pushed on the stack.
      */
    final case class PushContextRequest(
      contextId: ContextId,
      stackItem: StackItem
    ) extends ApiRequest

    /** A response sent from the server upon handling the [[PushContextRequest]]
      *
      * @param contextId the context's id.
      */
    final case class PushContextResponse(contextId: ContextId)
        extends ApiResponse

    /** A Request sent from the client to the runtime server, to move
      * the execution context up the stack.
      *
      * @param contextId the context's id.
      */
    final case class PopContextRequest(contextId: ContextId) extends ApiRequest

    /** A response sent from the server upon handling the [[PopContextRequest]]
      *
      * @param contextId the context's id.
      */
    final case class PopContextResponse(contextId: ContextId)
        extends ApiResponse

    /** A Request sent from the client to the runtime server, to recompute
      * the execution context.
      *
      * @param contextId the context's id.
      * @param expressions the selector specifying which expressions should be
      * recomputed.
      * @param executionEnvironment the environment used for execution
      */
    final case class RecomputeContextRequest(
      contextId: ContextId,
      expressions: Option[InvalidatedExpressions],
      executionEnvironment: Option[ExecutionEnvironment]
    ) extends ApiRequest

    /** A response sent from the server upon handling the
      * [[RecomputeContextRequest]].
      *
      * @param contextId the context's id.
      */
    final case class RecomputeContextResponse(contextId: ContextId)
        extends ApiResponse

    /** A Request sent from the client to the runtime server, to interrupt
      * the execution context.
      *
      * @param contextId the context's id.
      */
    final case class InterruptContextRequest(contextId: ContextId)
        extends ApiRequest

    /** A response sent from the server upon handling the
      * [[InterruptContextRequest]].
      *
      * @param contextId the context's id.
      */
    final case class InterruptContextResponse(contextId: ContextId)
        extends ApiResponse

    /** A request sent from the client to the runtime server to get the
      * component groups loaded in runtime.
      */
    final case class GetComponentGroupsRequest() extends ApiRequest

    /** A response sent from the server upon handling the
      * [[GetComponentGroupsRequest]].
      *
      * @param componentGroups the mapping containing the loaded component
      * groups
      */
    final case class GetComponentGroupsResponse(
      componentGroups: Vector[(LibraryName, ComponentGroups)]
    ) extends ApiResponse

    /** An error response signifying a non-existent context.
      *
      * @param contextId the context's id
      */
    final case class ContextNotExistError(contextId: ContextId) extends Error

    /** Signals that a module cannot be found.
      *
      * @param moduleName the module name
      */
    final case class ModuleNotFound(moduleName: String) extends Error

    /** Signals that execution of a context completed.
      *
      * @param contextId the context's id
      */
    final case class ExecutionComplete(contextId: ContextId)
        extends ApiNotification

    /** Signals that an expression specified in a [[AttachVisualization]] or
      * a [[ModifyVisualization]] cannot be evaluated.
      *
      * @param message the reason of the failure
      * @param failure the detailed information about the failure
      */
    final case class VisualizationExpressionFailed(
      ctx: VisualizationContext,
      message: String,
      failure: Option[ExecutionResult.Diagnostic]
    ) extends Error
        with ToLogString {

      /** @inheritdoc */
      override def toLogString(shouldMask: Boolean): String =
        "VisualizationExpressionFailed(" +
        s"contextId=${ctx.contextId}," +
        s"visualizationId=${ctx.visualizationId}," +
        s"expressionId=${ctx.expressionId}," +
        s"message=${MaskedString(message).toLogString(shouldMask)}," +
        s"failure=${failure.map(_.toLogString(shouldMask))}" +
        ")"
    }

    /** Signals that an evaluation of a code responsible for generating
      * visualization data failed.
      *
      * @param ctx the visualization context
      * @param message the reason of the failure
      * @param diagnostic the detailed information about the failure
      */
    final case class VisualizationEvaluationFailed(
      ctx: VisualizationContext,
      message: String,
      diagnostic: Option[ExecutionResult.Diagnostic]
    ) extends ApiNotification
        with ToLogString {

      /** @inheritdoc */
      override def toLogString(shouldMask: Boolean): String =
        "VisualizationEvaluationFailed(" +
        s"ctx=$ctx," +
        s"message=${MaskedString(message).toLogString(shouldMask)}," +
        s"diagnostic=${diagnostic.map(_.toLogString(shouldMask))}" +
        ")"
    }

    /** Signals that visualization cannot be found. */
    final case class VisualizationNotFound() extends Error

    /** An error response signifying that stack is empty.
      *
      * @param contextId the context's id
      */
    final case class EmptyStackError(contextId: ContextId) extends Error

    /** An error response signifying that stack item is invalid.
      *
      * @param contextId the context's id
      */
    final case class InvalidStackItemError(contextId: ContextId) extends Error

    /** A request sent to the server to open a file with a contents.
      *
      * @param path the file being moved to memory.
      * @param contents the current module's contents.
      */
    final case class OpenFileRequest(
      path: File,
      contents: String
    ) extends ApiRequest
        with ToLogString {

      /** @inheritdoc */
      override def toLogString(shouldMask: Boolean): String =
        "OpenFileRequest(" +
        s"path=${MaskedPath(path.toPath).toLogString(shouldMask)}," +
        s"contents=${MaskedString(contents).toLogString(shouldMask)}," +
        ")"
    }

    /** A response from the server confirming opening of a file.
      */
    final case object OpenFileResponse extends ApiResponse

    /** A notification sent to the server about in-memory file contents being
      * edited.
      *
      * @param path the file being edited
      * @param edits the diffs to apply to the contents
      * @param execute whether to execute the program after applying the edits
      */
    final case class EditFileNotification(
      path: File,
      edits: Seq[TextEdit],
      execute: Boolean
    ) extends ApiRequest
        with ToLogString {

      /** @inheritdoc */
      override def toLogString(shouldMask: Boolean): String =
        "EditFileNotification(" +
        s"path=${MaskedPath(path.toPath).toLogString(shouldMask)},edits=" +
        (if (shouldMask) edits.map(_ => STUB) else edits) +
        ",execute=" + execute + ")"
    }

    /** A notification sent to the server about in-memory file contents being
      * edited.
      *
      * @param path the file being edited
      * @param edits the diffs to apply to the contents
      * @param expressionId the expression to update
      * @param expressionValue the new value of the expression
      */
    final case class SetExpressionValueNotification(
      path: File,
      edits: Seq[TextEdit],
      expressionId: ExpressionId,
      expressionValue: String
    ) extends ApiRequest
        with ToLogString {

      /** @inheritdoc */
      override def toLogString(shouldMask: Boolean): String =
        "SetExpressionValueNotification(" +
        s"path=${MaskedPath(path.toPath).toLogString(shouldMask)},edits=" +
        (if (shouldMask) edits.map(_ => STUB) else edits) +
        s",expressionId=$expressionId,expressionValue=" +
        (if (shouldMask) STUB else expressionValue) +
        ")"
    }

    /** A notification sent to the server about dropping the file from memory
      * back to on-disk version.
      *
      * @param path the file being closed.
      */
    final case class CloseFileNotification(path: File)
        extends ApiRequest
        with ToLogString {

      /** @inheritdoc */
      override def toLogString(shouldMask: Boolean): String =
        s"CloseFileNotification(path=${MaskedPath(path.toPath).toLogString(shouldMask)})"
    }

    /** Notification sent from the server to the client upon successful
      * initialization. Any messages sent to the server before receiving this
      * message will be dropped.
      */
    final case class InitializedNotification() extends ApiResponse

    final case class ExecuteExpression(
      contextId: ContextId,
      visualizationId: VisualizationId,
      expressionId: ExpressionId,
      expression: String
    ) extends ApiRequest

    /** A request sent from the client to the runtime server, to create a new
      * visualization for an expression identified by `expressionId`.
      *
      * @param visualizationId an identifier of a visualization
      * @param expressionId an identifier of an expression which is visualised
      * @param visualizationConfig a configuration object for properties of the
      *                            visualization
      */
    final case class AttachVisualization(
      visualizationId: VisualizationId,
      expressionId: ExpressionId,
      visualizationConfig: VisualizationConfiguration
    ) extends ApiRequest
        with ToLogString {

      /** @inheritdoc */
      override def toLogString(shouldMask: Boolean): String =
        s"AttachVisualization(" +
        s"visualizationId=$visualizationId," +
        s"expressionId=$expressionId,visualizationConfig=" +
        visualizationConfig.toLogString(shouldMask) +
        ")"
    }

    /** Signals that attaching a visualization has succeeded.
      */
    final case class VisualizationAttached() extends ApiResponse

    /** A request sent from the client to the runtime server, to detach a
      * visualization from an expression identified by `expressionId`.
      *
      * @param contextId an execution context identifier
      * @param visualizationId an identifier of a visualization
      * @param expressionId an identifier of an expression which is visualised
      */
    final case class DetachVisualization(
      contextId: ContextId,
      visualizationId: VisualizationId,
      expressionId: ExpressionId
    ) extends ApiRequest

    /** Signals that detaching a visualization has succeeded.
      */
    final case class VisualizationDetached() extends ApiResponse

    /** A request sent from the client to the runtime server, to modify a
      * visualization identified by `visualizationId`.
      *
      * @param visualizationId     an identifier of a visualization
      * @param visualizationConfig a configuration object for properties of the
      *                            visualization
      */
    final case class ModifyVisualization(
      visualizationId: VisualizationId,
      visualizationConfig: VisualizationConfiguration
    ) extends ToLogString
        with ApiRequest {

      /** @inheritdoc */
      override def toLogString(shouldMask: Boolean): String =
        "ModifyVisualization(" +
        s"visualizationId=$visualizationId,visualizationConfig=" +
        visualizationConfig.toLogString(shouldMask) +
        ")"
    }

    /** Signals that a visualization modification has succeeded.
      */
    final case class VisualizationModified() extends ApiResponse

    /** A request to shut down the runtime server.
      */
    final case class ShutDownRuntimeServer() extends ApiRequest

    /** Signals that the runtime server has been shut down.
      */
    final case class RuntimeServerShutDown() extends ApiResponse

    /** A request for project renaming.
      *
      * @param namespace the namespace the renamed project belongs to
      * @param oldName the old project name
      * @param newName the new project name
      */
    final case class RenameProject(
      namespace: String,
      oldName: String,
      newName: String
    ) extends ApiRequest

    /** Signals that project has been renamed.
      *
      * @param oldNormalizedName old normalized name of the project
      * @param newNormalizedName new normalized name of the project
      * @param newName new display name of the project
      */
    final case class ProjectRenamed(
      oldNormalizedName: String,
      newNormalizedName: String,
      newName: String
    ) extends ApiResponse

    /** Signals that project has been renamed.
      *
      * @param oldName the old name of the project
      * @param newName the new name of the project
      */
    final case class ProjectRenameFailed(oldName: String, newName: String)
        extends ApiResponse

    /** A request for symbol renaming.
      *
      * @param module the qualified module name
      * @param expressionId the symbol to rename
      * @param newName the new name of the symbol
      */
    final case class RenameSymbol(
      module: String,
      expressionId: ExpressionId,
      newName: String
    ) extends ApiRequest

    /** Signals that the symbol has been renamed.
      *
      * @param newName the new name of the symbol
      */
    final case class SymbolRenamed(newName: String) extends ApiResponse

    /** Signals that the symbol rename has failed.
      *
      * @param error the error that happened
      */
    final case class SymbolRenameFailed(error: SymbolRenameFailed.Error)
        extends ApiResponse

    object SymbolRenameFailed {

      @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
      @JsonSubTypes(
        Array(
          new JsonSubTypes.Type(
            value = classOf[SymbolRenameFailed.ExpressionNotFound],
            name  = "symbolRenameFailedExpressionNotFound"
          ),
          new JsonSubTypes.Type(
            value = classOf[SymbolRenameFailed.FailedToApplyEdits],
            name  = "symbolRenameFailedFailedToApplyEdits"
          ),
          new JsonSubTypes.Type(
            value = classOf[SymbolRenameFailed.OperationNotSupported],
            name  = "symbolRenameFailedOperationNotSupported"
          )
        )
      ) sealed trait Error

      /** Signals that an expression cannot be found by provided id.
        *
        * @param expressionId the id of expression
        */
      final case class ExpressionNotFound(expressionId: ExpressionId)
          extends SymbolRenameFailed.Error

      /** Signals that it was unable to apply edits to the current module contents.
        *
        * @param module the module name
        */
      final case class FailedToApplyEdits(module: String)
          extends SymbolRenameFailed.Error

      /** Signals that the renaming operation is not supported for the
        * provided expression.
        *
        * @param expressionId the id of expression
        */
      final case class OperationNotSupported(expressionId: ExpressionId)
          extends SymbolRenameFailed.Error
    }

    /** A notification about the changes in the suggestions database.
      *
      * @param module the module name
      * @param actions the list of actions to apply to the suggestions database
      * @param exports the list of re-exported symbols
      * @param updates the list of suggestions extracted from module
      */
    final case class SuggestionsDatabaseModuleUpdateNotification(
      module: String,
      actions: Vector[SuggestionsDatabaseAction],
      exports: Vector[ExportsUpdate],
      updates: Tree[SuggestionUpdate]
    ) extends ApiNotification
        with ToLogString {

      /** @inheritdoc */
      override def toLogString(shouldMask: Boolean): String =
        "SuggestionsDatabaseModuleUpdateNotification(" +
        s"module=$module," +
        s"actions=$actions," +
        s"exports=$exports" +
        s"updates=${updates.map(_.toLogString(shouldMask))}" +
        ")"
    }

    /** A notification about the suggestions of the loaded library.
      *
      * @param libraryName the name of the loaded library
      * @param suggestions the loaded suggestions
      */
    final case class SuggestionsDatabaseSuggestionsLoadedNotification(
      libraryName: LibraryName,
      suggestions: Vector[Suggestion]
    ) extends ApiNotification
        with ToLogString {

      /** @inheritdoc */
      override def toLogString(shouldMask: Boolean): String =
        "SuggestionsDatabaseSuggestionsLoadedNotification(" +
        s"libraryName=$libraryName," +
        s"suggestions=${suggestions.map(_.toLogString(shouldMask))}" +
        ")"
    }

    /** A notification about the finished background analyze job. */
    final case class AnalyzeModuleInScopeJobFinished() extends ApiNotification

    /** A request to invalidate the indexed flag of the modules. */
    final case class InvalidateModulesIndexRequest() extends ApiRequest

    /** Signals that the module indexes has been invalidated. */
    final case class InvalidateModulesIndexResponse() extends ApiResponse

    /** A request for the type hierarchy graph. */
    final case class GetTypeGraphRequest() extends ApiRequest

    /** The result of the type graph request.
      *
      * @param graph the graph.
      */
    final case class GetTypeGraphResponse(graph: TypeGraph) extends ApiResponse

    /** Signals that a new library has been imported, which means its content
      * root should be registered.
      *
      * @param namespace namespace of the loaded library
      * @param name name of the loaded library
      * @param version library version that was selected
      * @param location location on disk of the project root belonging to the
      *                 loaded library
      */
    final case class LibraryLoaded(
      namespace: String,
      name: String,
      version: String,
      location: File
    ) extends ApiNotification

    /** A notification containing updates on the progress of long-running tasks.
      *
      * @param payload the actual update contained within this notification
      */
    final case class ProgressNotification(
      payload: ProgressNotification.NotificationType
    ) extends ApiNotification

    object ProgressNotification {
      sealed trait NotificationType

      /** Indicates that a new task has been started. */
      case class TaskStarted(
        taskId: UUID,
        relatedOperation: String,
        unit: String,
        total: Option[Long]
      ) extends NotificationType

      /** Indicates that the task has progressed. */
      case class TaskProgressUpdate(
        taskId: UUID,
        message: Option[String],
        done: Long
      ) extends NotificationType

      /** Indicates that the task has been finished. */
      case class TaskFinished(
        taskId: UUID,
        message: Option[String],
        success: Boolean
      ) extends NotificationType
    }

    /** A request sent from the runtime to acquire a lock.
      *
      * @param resourceName name of the resource identifying the lock
      * @param exclusive whether the lock should be exclusive (if false, a
      *                  shared lock is acquired, if supported)
      * @param returnImmediately if set to true, will immediately return even if
      *                          the lock cannot be acquired; if set to false,
      *                          the response to the request will come only once
      *                          the lock has been successfully acquired (which
      *                          may take an arbitrarily large amount of time)
      */
    final case class AcquireLockRequest(
      resourceName: String,
      exclusive: Boolean,
      returnImmediately: Boolean
    ) extends ApiRequest

    /** A response indicating that the lock has been successfully acquired.
      *
      * @param lockId a unique identifier of the lock that can be used to
      *               release it
      */
    final case class LockAcquired(lockId: UUID) extends ApiResponse

    /** A response indicating that the lock could not be acquired immediately.
      *
      * It is only sent if the request had `returnImmediately` set to true.
      */
    final case class CannotAcquireImmediately() extends ApiResponse

    /** A response indicating a general failure to acquire the lock.
      *
      * @param errorMessage message associated with the exception that caused
      *                     this failure
      */
    final case class LockAcquireFailed(errorMessage: String) extends ApiResponse

    /** A request sent from the runtime to release a lock.
      *
      * @param lockId the identifier of the lock to release, as specified in the
      *               [[LockAcquired]] response
      */
    final case class ReleaseLockRequest(lockId: UUID) extends ApiRequest

    /** A response indicating that the lock has been successfully released. */
    final case class LockReleased() extends ApiResponse

    /** A response indicating a general failure to release the lock.
      *
      * @param errorMessage message associated with the exception that caused
      *                     this failure
      */
    final case class LockReleaseFailed(errorMessage: String) extends ApiResponse

    /** A request to deserialize the library suggestions.
      *
      * Does not have a companion response message. The response will be
      * delivered asynchronously as a notification.
      *
      * @param libraryName the name of the loaded library.
      */
    final case class DeserializeLibrarySuggestions(libraryName: LibraryName)
        extends ApiRequest

    /** A request to start the background jobs processing. */
    final case class StartBackgroundProcessing() extends ApiRequest

    /** A notification about started background jobs. */
    final case class BackgroundJobsStartedNotification() extends ApiNotification

    /** A request to serialize the module.
      *
      * @param module qualified module name
      */
    final case class SerializeModule(module: QualifiedName) extends ApiRequest

    /** A request to set the execution environment. */
    final case class SetExecutionEnvironmentRequest(
      contextId: ContextId,
      executionEnvironment: ExecutionEnvironment
    ) extends ApiRequest

    /** A response to the set execution environment request. */
    final case class SetExecutionEnvironmentResponse(contextId: ContextId)
        extends ApiResponse

    private lazy val mapper = {
      val factory = new CBORFactory()
      val mapper  = new ObjectMapper(factory) with ClassTagExtensions
      mapper
        .registerModule(DefaultScalaModule)
        .registerModule(ScalaObjectDeserializerModule)
    }

    /** Serializes an ApiEnvelope into a byte buffer.
      *
      * @param message the message to serialize.
      * @return the serialized version of the message.
      */
    def serialize(message: ApiEnvelope): ByteBuffer =
      ByteBuffer.wrap(mapper.writeValueAsBytes(message))

    /** Deserializes a byte buffer into an ApiEnvelope, which can be a Request
      * or a Response.
      *
      * @param bytes the buffer to deserialize
      * @return the deserialized message, if the byte buffer can be deserialized.
      */
    def deserializeApiEnvelope(bytes: ByteBuffer): Option[ApiEnvelope] =
      Try(mapper.readValue(bytes.array(), classOf[ApiEnvelope])).toOption
  }

}
