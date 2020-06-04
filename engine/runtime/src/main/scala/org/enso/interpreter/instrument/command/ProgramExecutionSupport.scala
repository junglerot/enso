package org.enso.interpreter.instrument.command

import java.io.File
import java.util.UUID
import java.util.function.Consumer
import java.util.logging.Level

import cats.implicits._
import org.enso.interpreter.instrument.IdExecutionInstrument.{
  ExpressionCall,
  ExpressionValue
}
import org.enso.interpreter.instrument.command.ProgramExecutionSupport.ExecutionItem
import org.enso.interpreter.instrument.{
  InstrumentFrame,
  RuntimeCache,
  Visualisation
}
import org.enso.interpreter.instrument.execution.RuntimeContext
import org.enso.interpreter.node.callable.FunctionCallInstrumentationNode.FunctionCall
import org.enso.pkg.QualifiedName
import org.enso.polyglot.runtime.Runtime.Api
import org.enso.polyglot.runtime.Runtime.Api.ContextId

import scala.jdk.javaapi.OptionConverters

/**
  * Provides support for executing Enso code. Adds convenient methods to
  * run Enso programs in a Truffle context.
  */
trait ProgramExecutionSupport {

  /**
    * Executes action in a newly created Truffle context.
    *
    * @param action an action
    * @param ctx a runtime context
    * @return a result of executing the action
    */
  def withContext[A](action: => A)(implicit ctx: RuntimeContext): A = {
    val token = ctx.truffleContext.enter()
    try {
      action
    } finally {
      ctx.truffleContext.leave(token)
    }
  }

  /**
    * Runs an Enso program.
    *
    * @param executionItem an execution item
    * @param callStack a call stack
    * @param cache the runtime cache
    * @param valueCallback a listener of computed values
    */
  @scala.annotation.tailrec
  final def runProgram(
    executionItem: ExecutionItem,
    callStack: List[UUID],
    cache: RuntimeCache,
    valueCallback: Consumer[ExpressionValue]
  )(implicit ctx: RuntimeContext): Unit = {
    var enterables: Map[UUID, FunctionCall] = Map()
    val valsCallback: Consumer[ExpressionValue] =
      if (callStack.isEmpty) valueCallback else _ => ()
    val callablesCallback: Consumer[ExpressionCall] = fun =>
      enterables += fun.getExpressionId -> fun.getCall
    executionItem match {
      case ExecutionItem.Method(file, cons, function) =>
        ctx.executionService.execute(
          file,
          cons,
          function,
          cache,
          valsCallback,
          callablesCallback
        )
      case ExecutionItem.CallData(callData) =>
        ctx.executionService.execute(
          callData,
          cache,
          valsCallback,
          callablesCallback
        )
    }

    callStack match {
      case Nil => ()
      case item :: tail =>
        enterables.get(item) match {
          case Some(call) =>
            runProgram(ExecutionItem.CallData(call), tail, cache, valueCallback)
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
    * @param ctx a runtime context
    * @return either an error message or Unit signaling completion of a program
    */
  def runProgram(
    contextId: Api.ContextId,
    stack: List[InstrumentFrame]
  )(implicit ctx: RuntimeContext): Either[String, Unit] = {
    @scala.annotation.tailrec
    def unwind(
      stack: List[InstrumentFrame],
      explicitCalls: List[Api.StackItem.ExplicitCall],
      localCalls: List[UUID],
      caches: List[RuntimeCache]
    ): (Option[Api.StackItem.ExplicitCall], List[UUID], Option[RuntimeCache]) =
      stack match {
        case Nil =>
          (explicitCalls.lastOption, localCalls, caches.lastOption)
        case List(InstrumentFrame(call: Api.StackItem.ExplicitCall, cache)) =>
          (Some(call), localCalls, Some(cache))
        case InstrumentFrame(Api.StackItem.LocalCall(id), cache) :: xs =>
          unwind(xs, explicitCalls, id :: localCalls, cache :: caches)
      }
    val (explicitCallOpt, localCalls, cacheOpt) = unwind(stack, Nil, Nil, Nil)
    for {
      stackItem <- Either.fromOption(explicitCallOpt, "stack is empty")
      cache     <- Either.fromOption(cacheOpt, "cache does not exist")
      item = toExecutionItem(stackItem)
      _ <- Either
        .catchNonFatal(
          runProgram(
            item,
            localCalls,
            cache,
            onExpressionValueComputed(contextId, _)
          )
        )
        .leftMap { ex =>
          ctx.executionService.getLogger.log(
            Level.FINE,
            s"Error executing a function '${item.function}'",
            ex
          )
          s"error in function: ${item.function}"
        }
    } yield ()
  }

  private def onExpressionValueComputed(
    contextId: Api.ContextId,
    value: ExpressionValue
  )(implicit ctx: RuntimeContext): Unit = {
    sendValueUpdate(contextId, value)
    fireVisualisationUpdates(contextId, value)
  }

  private def sendValueUpdate(
    contextId: ContextId,
    value: ExpressionValue
  )(implicit ctx: RuntimeContext): Unit = {
    ctx.endpoint.sendToClient(
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(
            Api.ExpressionValueUpdate(
              value.getExpressionId,
              OptionConverters.toScala(value.getType),
              Some(value.getValue.toString),
              toMethodPointer(value)
            )
          )
        )
      )
    )
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
          case bytes: Array[Byte] => Right(bytes)
          case other =>
            Left(s"Cannot encode ${other.getClass} to byte array")
        }

    errorMsgOrVisualisationData match {
      case Left(msg) =>
        ctx.endpoint.sendToClient(
          Api.Response(Api.VisualisationEvaluationFailed(msg))
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
  )(implicit ctx: RuntimeContext): Option[Api.MethodPointer] =
    for {
      call <- Option(value.getCall)
      qualifiedName <- QualifiedName.fromString(
        call.getFunction.getCallTarget.getRootNode.getQualifiedName
      )
      moduleName   <- qualifiedName.getParent
      functionName <- QualifiedName.fromString(call.getFunction.getName)
      typeName     <- functionName.getParent
      module <- OptionConverters.toScala(
        ctx.executionService.getContext.getTopScope
          .getModule(moduleName.toString)
      )
      modulePath <- Option(module.getPath)
    } yield Api.MethodPointer(
      new File(modulePath),
      typeName.toString,
      functionName.module
    )

  private def toExecutionItem(
    call: Api.StackItem.ExplicitCall
  ): ExecutionItem.Method =
    ExecutionItem.Method(
      call.methodPointer.file,
      call.methodPointer.definedOnType,
      call.methodPointer.name
    )

}

object ProgramExecutionSupport {

  sealed private trait ExecutionItem

  private object ExecutionItem {

    case class Method(
      file: File,
      constructor: String,
      function: String
    ) extends ExecutionItem

    case class CallData(callData: FunctionCall) extends ExecutionItem

  }

}
