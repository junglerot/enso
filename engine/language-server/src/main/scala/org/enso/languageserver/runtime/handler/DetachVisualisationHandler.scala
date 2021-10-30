package org.enso.languageserver.runtime.handler

import akka.actor.{Actor, ActorRef, Cancellable, Props}
import akka.pattern.pipe
import com.typesafe.scalalogging.LazyLogging
import org.enso.languageserver.requesthandler.RequestTimeout
import org.enso.languageserver.runtime.{
  ContextRegistryProtocol,
  RuntimeFailureMapper
}
import org.enso.languageserver.util.UnhandledLogging
import org.enso.polyglot.runtime.Runtime.Api

import java.util.UUID
import scala.concurrent.duration.FiniteDuration

/** A request handler for detach visualisation commands.
  *
  * @param runtimeFailureMapper mapper for runtime failures
  * @param timeout request timeout
  * @param runtime reference to the runtime connector
  */
class DetachVisualisationHandler(
  runtimeFailureMapper: RuntimeFailureMapper,
  timeout: FiniteDuration,
  runtime: ActorRef
) extends Actor
    with LazyLogging
    with UnhandledLogging {

  import context.dispatcher

  override def receive: Receive = requestStage

  private def requestStage: Receive = { case msg: Api.DetachVisualisation =>
    runtime ! Api.Request(UUID.randomUUID(), msg)
    val cancellable =
      context.system.scheduler.scheduleOnce(timeout, self, RequestTimeout)
    context.become(responseStage(sender(), cancellable))
  }

  private def responseStage(
    replyTo: ActorRef,
    cancellable: Cancellable
  ): Receive = {
    case RequestTimeout =>
      replyTo ! RequestTimeout
      context.stop(self)

    case Api.Response(_, Api.VisualisationDetached()) =>
      replyTo ! ContextRegistryProtocol.VisualisationDetached
      cancellable.cancel()
      context.stop(self)

    case Api.Response(_, error: Api.Error) =>
      runtimeFailureMapper.mapApiError(error).pipeTo(replyTo)
      cancellable.cancel()
      context.stop(self)
  }

}

object DetachVisualisationHandler {

  /** Creates configuration object used to create a [[DetachVisualisationHandler]].
    *
    * @param runtimeFailureMapper mapper for runtime failures
    * @param timeout request timeout
    * @param runtime reference to the runtime connector
    */
  def props(
    runtimeFailureMapper: RuntimeFailureMapper,
    timeout: FiniteDuration,
    runtime: ActorRef
  ): Props =
    Props(
      new DetachVisualisationHandler(runtimeFailureMapper, timeout, runtime)
    )

}
