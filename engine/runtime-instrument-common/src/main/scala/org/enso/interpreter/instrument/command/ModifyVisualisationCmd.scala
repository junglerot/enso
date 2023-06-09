package org.enso.interpreter.instrument.command

import org.enso.interpreter.instrument.execution.RuntimeContext
import org.enso.interpreter.instrument.job.{
  EnsureCompiledJob,
  ExecuteJob,
  UpsertVisualisationJob
}
import org.enso.polyglot.runtime.Runtime.Api
import org.enso.polyglot.runtime.Runtime.Api.RequestId

import scala.concurrent.{ExecutionContext, Future}

/** A command that modifies a visualisation.
  *
  * @param maybeRequestId an option with request id
  * @param request a request for a service
  */
class ModifyVisualisationCmd(
  maybeRequestId: Option[RequestId],
  request: Api.ModifyVisualisation
) extends AsynchronousCommand(maybeRequestId) {

  /** @inheritdoc */
  override def executeAsynchronously(implicit
    ctx: RuntimeContext,
    ec: ExecutionContext
  ): Future[Unit] = {
    val contextId = request.visualisationConfig.executionContextId
    ctx.locking.acquireContextLock(contextId)
    try {
      if (doesContextExist) {
        modifyVisualisation()
      } else {
        replyWithContextNotExistError()
      }
    } finally {
      ctx.locking.releaseContextLock(contextId)
    }
  }

  private def modifyVisualisation()(implicit
    ctx: RuntimeContext,
    ec: ExecutionContext
  ): Future[Unit] = {
    val maybeVisualisation = ctx.contextManager.getVisualisationById(
      request.visualisationConfig.executionContextId,
      request.visualisationId
    )
    maybeVisualisation match {
      case None =>
        Future {
          ctx.endpoint.sendToClient(
            Api.Response(maybeRequestId, Api.VisualisationNotFound())
          )
        }

      case Some(visualisation) =>
        val maybeFutureExecutable =
          ctx.jobProcessor.run(
            new UpsertVisualisationJob(
              maybeRequestId,
              Api.VisualisationModified(),
              request.visualisationId,
              visualisation.expressionId,
              request.visualisationConfig
            )
          )
        maybeFutureExecutable flatMap {
          case None =>
            Future.successful(())

          case Some(exec) =>
            for {
              _ <- Future {
                ctx.jobProcessor.run(EnsureCompiledJob(exec.stack))
              }
              _ <- ctx.jobProcessor.run(ExecuteJob(exec))
            } yield ()
        }
    }
  }

  private def doesContextExist(implicit ctx: RuntimeContext): Boolean = {
    ctx.contextManager.contains(
      request.visualisationConfig.executionContextId
    )
  }

  private def replyWithContextNotExistError()(implicit
    ctx: RuntimeContext,
    ec: ExecutionContext
  ): Future[Unit] = {
    Future {
      reply(
        Api.ContextNotExistError(request.visualisationConfig.executionContextId)
      )
    }
  }

}
