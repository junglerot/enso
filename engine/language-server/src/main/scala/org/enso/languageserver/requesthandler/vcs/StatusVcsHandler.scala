package org.enso.languageserver.requesthandler.vcs

import akka.actor.{Actor, ActorRef, Cancellable, Props}
import com.typesafe.scalalogging.LazyLogging
import org.enso.jsonrpc.{Errors, Id, Request, ResponseError, ResponseResult}
import org.enso.languageserver.requesthandler.RequestTimeout
import org.enso.languageserver.session.JsonSession
import org.enso.languageserver.util.UnhandledLogging
import org.enso.languageserver.vcsmanager.VcsManagerApi.StatusVcs
import org.enso.languageserver.vcsmanager.{VcsFailureMapper, VcsProtocol}

import scala.concurrent.duration.FiniteDuration

class StatusVcsHandler(
  requestTimeout: FiniteDuration,
  vcsManager: ActorRef,
  rpcSession: JsonSession
) extends Actor
    with LazyLogging
    with UnhandledLogging {

  import context.dispatcher

  override def receive: Receive = requestStage

  private def requestStage: Receive = {
    case Request(StatusVcs, id, params: StatusVcs.Params) =>
      vcsManager ! VcsProtocol.StatusRepo(rpcSession.clientId, params.root)
      val cancellable = context.system.scheduler
        .scheduleOnce(requestTimeout, self, RequestTimeout)
      context.become(responseStage(id, sender(), cancellable))
  }

  private def responseStage(
    id: Id,
    replyTo: ActorRef,
    cancellable: Cancellable
  ): Receive = {
    case RequestTimeout =>
      logger.error(
        "Status project request [{}] for [{}] timed out.",
        id,
        rpcSession.clientId
      )
      replyTo ! ResponseError(Some(id), Errors.RequestTimeout)
      context.stop(self)

    case VcsProtocol.StatusRepoResponse(Right((isModified, changed, last))) =>
      replyTo ! ResponseResult(
        StatusVcs,
        id,
        StatusVcs.Result(
          isModified,
          changed,
          last.map(commit => StatusVcs.Save(commit._1, commit._2))
        )
      )
      cancellable.cancel()
      context.stop(self)

    case VcsProtocol.StatusRepoResponse(Left(failure)) =>
      replyTo ! ResponseError(Some(id), VcsFailureMapper.mapFailure(failure))
      cancellable.cancel()
      context.stop(self)
  }
}

object StatusVcsHandler {

  def props(
    timeout: FiniteDuration,
    vcsManager: ActorRef,
    rpcSession: JsonSession
  ): Props =
    Props(new StatusVcsHandler(timeout, vcsManager, rpcSession))
}
