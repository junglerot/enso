package org.enso.languageserver.requesthandler.session

import akka.actor.{Actor, ActorRef, Cancellable, Props}
import com.typesafe.scalalogging.LazyLogging
import org.enso.jsonrpc.{Errors, Id, Request, ResponseError, ResponseResult}
import org.enso.languageserver.filemanager.FileManagerProtocol
import org.enso.languageserver.filemanager.FileManagerProtocol.ContentRootsResult
import org.enso.languageserver.requesthandler.RequestTimeout
import org.enso.languageserver.session.SessionApi.InitProtocolConnection
import org.enso.languageserver.util.UnhandledLogging

import scala.concurrent.duration.FiniteDuration

/** A request handler for `session/initProtocolConnection` commands.
  *
  * @param fileManager a file manager reference
  * @param timeout a request timeout
  */
class InitProtocolConnectionHandler(
  fileManager: ActorRef,
  timeout: FiniteDuration
) extends Actor
    with LazyLogging
    with UnhandledLogging {

  import context.dispatcher

  override def receive: Receive = requestStage

  private def requestStage: Receive = {
    case Request(InitProtocolConnection, id, _) =>
      fileManager ! FileManagerProtocol.GetContentRoots
      val cancellable =
        context.system.scheduler.scheduleOnce(timeout, self, RequestTimeout)
      context.become(responseStage(id, sender(), cancellable))
  }

  private def responseStage(
    id: Id,
    replyTo: ActorRef,
    cancellable: Cancellable
  ): Receive = {
    case RequestTimeout =>
      logger.error("Getting content roots request [{}] timed out.", id)
      replyTo ! ResponseError(Some(id), Errors.RequestTimeout)
      context.stop(self)

    case ContentRootsResult(contentRoots) =>
      cancellable.cancel()
      replyTo ! ResponseResult(
        InitProtocolConnection,
        id,
        InitProtocolConnection.Result(contentRoots)
      )
      context.stop(self)
  }

}

object InitProtocolConnectionHandler {

  /** Creates a configuration object used to create a [[InitProtocolConnectionHandler]]
    *
    * @param fileManager a file manager reference
    * @param timeout a request timeout
    * @return a configuration object
    */
  def props(fileManager: ActorRef, timeout: FiniteDuration): Props =
    Props(new InitProtocolConnectionHandler(fileManager, timeout))

}
