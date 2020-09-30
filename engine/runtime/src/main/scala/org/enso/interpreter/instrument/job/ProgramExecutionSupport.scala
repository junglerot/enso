package org.enso.interpreter.instrument.job

import java.util.{Objects, UUID}
import java.util.function.Consumer
import java.util.logging.Level

import cats.implicits._
import com.oracle.truffle.api.TruffleException
import com.oracle.truffle.api.interop.InteropException
import org.enso.interpreter.instrument.IdExecutionInstrument.{
  ExpressionCall,
  ExpressionValue
}
import org.enso.interpreter.instrument.execution.RuntimeContext
import org.enso.interpreter.instrument.job.ProgramExecutionSupport.{
  ExecutionFrame,
  ExecutionItem,
  LocalCallFrame
}
import org.enso.interpreter.instrument.{
  InstrumentFrame,
  MethodCallsCache,
  RuntimeCache,
  Visualisation
}
import org.enso.interpreter.node.callable.FunctionCallInstrumentationNode.FunctionCall
import org.enso.interpreter.runtime.data.text.Text
import org.enso.interpreter.service.error.ServiceException
import org.enso.polyglot.runtime.Runtime.Api
import org.enso.polyglot.runtime.Runtime.Api.ContextId

/**
  * Provides support for executing Enso code. Adds convenient methods to
  * run Enso programs in a Truffle context.
  */
trait ProgramExecutionSupport {

  /**
    * Runs an Enso program.
    *
    * @param executionFrame an execution frame
    * @param callStack a call stack
    * @param onCachedMethodCallCallback a listener of cached method calls
    * @param onComputedCallback a listener of computed values
    * @param onCachedCallback a listener of cached values
    * @param onExceptionalCallback the consumer of the exceptional events.
    */
  @scala.annotation.tailrec
  final private def executeProgram(
    executionFrame: ExecutionFrame,
    callStack: List[LocalCallFrame],
    onCachedMethodCallCallback: Consumer[ExpressionValue],
    onComputedCallback: Consumer[ExpressionValue],
    onCachedCallback: Consumer[ExpressionValue],
    onExceptionalCallback: Consumer[Throwable]
  )(implicit ctx: RuntimeContext): Unit = {
    val methodCallsCache = new MethodCallsCache
    var enterables       = Map[UUID, FunctionCall]()
    val computedCallback: Consumer[ExpressionValue] =
      if (callStack.isEmpty) onComputedCallback else _ => ()
    val callablesCallback: Consumer[ExpressionCall] = fun =>
      if (callStack.headOption.exists(_.expressionId == fun.getExpressionId)) {
        enterables += fun.getExpressionId -> fun.getCall
      }
    executionFrame match {
      case ExecutionFrame(
            ExecutionItem.Method(module, cons, function),
            cache
          ) =>
        ctx.executionService.execute(
          module,
          cons,
          function,
          cache,
          methodCallsCache,
          callStack.headOption.map(_.expressionId).orNull,
          callablesCallback,
          computedCallback,
          onCachedCallback,
          onExceptionalCallback
        )
      case ExecutionFrame(ExecutionItem.CallData(callData), cache) =>
        ctx.executionService.execute(
          callData,
          cache,
          methodCallsCache,
          callStack.headOption.map(_.expressionId).orNull,
          callablesCallback,
          computedCallback,
          onCachedCallback,
          onExceptionalCallback
        )
    }

    callStack match {
      case Nil =>
        val notExecuted =
          methodCallsCache.getNotExecuted(executionFrame.cache.getCalls)
        notExecuted.forEach { expressionId =>
          val expressionType = executionFrame.cache.getType(expressionId)
          val expressionCall = executionFrame.cache.getCall(expressionId)
          onCachedMethodCallCallback.accept(
            new ExpressionValue(
              expressionId,
              null,
              expressionType,
              expressionType,
              expressionCall,
              expressionCall
            )
          )
        }
      case item :: tail =>
        enterables.get(item.expressionId) match {
          case Some(call) =>
            executeProgram(
              ExecutionFrame(ExecutionItem.CallData(call), item.cache),
              tail,
              onCachedMethodCallCallback,
              onComputedCallback,
              onCachedCallback,
              onExceptionalCallback
            )
          case None =>
            ()
        }
    }
  }

  /**
    * Runs an Enso program.
    *
    * @param contextId an identifier of an execution context
    * @param stack a call stack
    * @param updatedVisualisations a list of updated visualisations
    * @param sendMethodCallUpdates a flag to send all the method calls of the
    * executed frame as a value updates
    * @param ctx a runtime context
    * @return either an error message or Unit signaling completion of a program
    */
  final def runProgram(
    contextId: Api.ContextId,
    stack: List[InstrumentFrame],
    updatedVisualisations: Seq[Api.ExpressionId],
    sendMethodCallUpdates: Boolean
  )(implicit ctx: RuntimeContext): Either[String, Unit] = {
    @scala.annotation.tailrec
    def unwind(
      stack: List[InstrumentFrame],
      explicitCalls: List[ExecutionFrame],
      localCalls: List[LocalCallFrame]
    ): (Option[ExecutionFrame], List[LocalCallFrame]) =
      stack match {
        case Nil =>
          (explicitCalls.lastOption, localCalls)
        case List(InstrumentFrame(call: Api.StackItem.ExplicitCall, cache)) =>
          (Some(ExecutionFrame(ExecutionItem.Method(call), cache)), localCalls)
        case InstrumentFrame(Api.StackItem.LocalCall(id), cache) :: xs =>
          unwind(xs, explicitCalls, LocalCallFrame(id, cache) :: localCalls)
      }

    val onCachedMethodCallCallback: Consumer[ExpressionValue] = { value =>
      ctx.executionService.getLogger.finer(s"ON_CACHED_CALL $value")
      sendValueUpdate(contextId, value, sendMethodCallUpdates)
    }

    val onCachedValueCallback: Consumer[ExpressionValue] = { value =>
      if (updatedVisualisations.contains(value.getExpressionId)) {
        ctx.executionService.getLogger.finer(s"ON_CACHED_VALUE $value")
        fireVisualisationUpdates(contextId, value)
      }
    }

    val onComputedValueCallback: Consumer[ExpressionValue] = { value =>
      ctx.executionService.getLogger.finer(s"ON_COMPUTED $value")
      sendValueUpdate(contextId, value, sendMethodCallUpdates)
      fireVisualisationUpdates(contextId, value)
    }

    val onExceptionalCallback: Consumer[Throwable] = { value =>
      ctx.executionService.getLogger.finer(s"ON_ERROR $value")
      sendErrorUpdate(contextId, value)
    }

    val (explicitCallOpt, localCalls) = unwind(stack, Nil, Nil)
    for {
      stackItem <- Either.fromOption(explicitCallOpt, "Stack is empty.")
      _ <-
        Either
          .catchNonFatal(
            executeProgram(
              stackItem,
              localCalls,
              onCachedMethodCallCallback,
              onComputedValueCallback,
              onCachedValueCallback,
              onExceptionalCallback
            )
          )
          .leftMap(onExecutionError(stackItem.item, _))
    } yield ()
  }

  /** Execution error handler.
    *
    * @param item the stack item being executed
    * @param error the execution error
    * @return the error message
    */
  private def onExecutionError(
    item: ExecutionItem,
    error: Throwable
  )(implicit ctx: RuntimeContext): String = {
    val itemName = item match {
      case ExecutionItem.Method(_, _, function) => function
      case ExecutionItem.CallData(call)         => call.getFunction.getName
    }
    val errorMessage = getErrorMessage(error)
    errorMessage match {
      case Some(_) =>
        ctx.executionService.getLogger
          .log(Level.FINE, s"Error executing a function $itemName.")
      case None =>
        ctx.executionService.getLogger
          .log(Level.FINE, s"Error executing a function $itemName.", error)
    }
    errorMessage.getOrElse(s"Error in function $itemName.")
  }

  /** Get error message from throwable. */
  private def getErrorMessage(t: Throwable): Option[String] =
    t match {
      case ex: InteropException => Some(ex.getMessage)
      case ex: TruffleException => Some(ex.getMessage)
      case ex: ServiceException => Some(ex.getMessage)
      case _                    => None
    }

  private def sendErrorUpdate(contextId: ContextId, error: Throwable)(implicit
    ctx: RuntimeContext
  ): Unit = {
    ctx.endpoint.sendToClient(
      Api.Response(Api.ExecutionFailed(contextId, error.getMessage))
    )
  }

  private def sendValueUpdate(
    contextId: ContextId,
    value: ExpressionValue,
    sendMethodCallUpdates: Boolean
  )(implicit ctx: RuntimeContext): Unit = {
    val methodPointer = toMethodPointer(value)
    if (
      (sendMethodCallUpdates && methodPointer.isDefined) ||
      !Objects.equals(value.getCallInfo, value.getCachedCallInfo) ||
      !Objects.equals(value.getType, value.getCachedType)
    ) {
      ctx.endpoint.sendToClient(
        Api.Response(
          Api.ExpressionValuesComputed(
            contextId,
            Vector(
              Api.ExpressionValueUpdate(
                value.getExpressionId,
                Option(value.getType),
                methodPointer
              )
            )
          )
        )
      )
    }
  }

  private def fireVisualisationUpdates(
    contextId: ContextId,
    value: ExpressionValue
  )(implicit ctx: RuntimeContext): Unit = {
    val visualisations =
      ctx.contextManager.findVisualisationForExpression(
        contextId,
        value.getExpressionId
      )
    visualisations foreach { visualisation =>
      emitVisualisationUpdate(contextId, value, visualisation)
    }
  }

  private def emitVisualisationUpdate(
    contextId: ContextId,
    value: ExpressionValue,
    visualisation: Visualisation
  )(implicit ctx: RuntimeContext): Unit = {
    val errorMsgOrVisualisationData =
      Either
        .catchNonFatal {
          ctx.executionService.callFunction(
            visualisation.callback,
            value.getValue
          )
        }
        .leftMap(_.getMessage)
        .flatMap {
          case text: String       => Right(text.getBytes("UTF-8"))
          case text: Text         => Right(text.toString.getBytes("UTF-8"))
          case bytes: Array[Byte] => Right(bytes)
          case other =>
            Left(s"Cannot encode ${other.getClass} to byte array")
        }

    errorMsgOrVisualisationData match {
      case Left(msg) =>
        ctx.endpoint.sendToClient(
          Api.Response(Api.VisualisationEvaluationFailed(contextId, msg))
        )

      case Right(data) =>
        ctx.endpoint.sendToClient(
          Api.Response(
            Api.VisualisationUpdate(
              Api.VisualisationContext(
                visualisation.id,
                contextId,
                value.getExpressionId
              ),
              data
            )
          )
        )
    }
  }

  private def toMethodPointer(
    value: ExpressionValue
  ): Option[Api.MethodPointer] =
    for {
      call       <- Option(value.getCallInfo)
      moduleName <- Option(call.getModuleName)
      typeName   <- Option(call.getTypeName).map(_.item)
    } yield Api.MethodPointer(
      moduleName.toString,
      typeName,
      call.getFunctionName
    )
}

object ProgramExecutionSupport {

  /** An execution frame.
    *
    * @param item the executionitem
    * @param cache the cache of this stack frame
    */
  sealed private case class ExecutionFrame(
    item: ExecutionItem,
    cache: RuntimeCache
  )

  /** A local call frame defined by the expression id.
    *
    * @param expressionId the id of the expression
    * @param cache the cache of this frame
    */
  sealed private case class LocalCallFrame(
    expressionId: UUID,
    cache: RuntimeCache
  )

  /** An execution item. */
  sealed private trait ExecutionItem
  private object ExecutionItem {

    /** The explicit method call.
      *
      * @param module the module containing the method
      * @param constructor the type on which the method is defined
      * @param function the method name
      */
    case class Method(module: String, constructor: String, function: String)
        extends ExecutionItem

    object Method {

      /** Construct the method call from the [[Api.StackItem.ExplicitCall]].
        *
        * @param call the Api call
        * @return the method call
        */
      def apply(call: Api.StackItem.ExplicitCall): Method =
        Method(
          call.methodPointer.module,
          call.methodPointer.definedOnType,
          call.methodPointer.name
        )
    }

    /** The call data captured during the program execution.
      *
      * @param callData the fucntion call data
      */
    case class CallData(callData: FunctionCall) extends ExecutionItem
  }
}
