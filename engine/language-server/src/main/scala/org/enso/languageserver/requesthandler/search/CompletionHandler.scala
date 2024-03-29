package org.enso.languageserver.requesthandler.search

import akka.actor.{Actor, ActorRef, Cancellable, Props, Status}
import com.typesafe.scalalogging.LazyLogging
import org.enso.jsonrpc._
import org.enso.languageserver.requesthandler.RequestTimeout
import org.enso.languageserver.search.SearchApi.{
  Completion,
  SuggestionsDatabaseError
}
import org.enso.languageserver.search.{SearchFailureMapper, SearchProtocol}
import org.enso.languageserver.util.UnhandledLogging

import scala.concurrent.duration.FiniteDuration

/** A request handler for `search/completion` command.
  *
  * @param timeout request timeout
  * @param suggestionsHandler a reference to the suggestions handler
  */
class CompletionHandler(
  timeout: FiniteDuration,
  suggestionsHandler: ActorRef
) extends Actor
    with LazyLogging
    with UnhandledLogging {

  import context.dispatcher

  override def receive: Receive = requestStage

  private def requestStage: Receive = {
    case Request(
          Completion,
          id,
          Completion.Params(file, pos, selfType, returnType, tags, isStatic)
        ) =>
      suggestionsHandler ! SearchProtocol.Completion(
        file,
        pos,
        selfType,
        returnType,
        tags,
        isStatic
      )
      val cancellable =
        context.system.scheduler.scheduleOnce(timeout, self, RequestTimeout)
      context.become(responseStage(id, sender(), cancellable))
  }

  private def responseStage(
    id: Id,
    replyTo: ActorRef,
    cancellable: Cancellable
  ): Receive = {
    case Status.Failure(ex) =>
      logger.error("Search completion error.", ex)
      replyTo ! ResponseError(Some(id), SuggestionsDatabaseError)
      cancellable.cancel()
      context.stop(self)

    case RequestTimeout =>
      logger.error("Request [{}] timed out.", id)
      replyTo ! ResponseError(Some(id), Errors.RequestTimeout)
      context.stop(self)

    case msg: SearchProtocol.SearchFailure =>
      replyTo ! ResponseError(Some(id), SearchFailureMapper.mapFailure(msg))

    case SearchProtocol.CompletionResult(version, results) =>
      replyTo ! ResponseResult(
        Completion,
        id,
        Completion.Result(results, version)
      )
      cancellable.cancel()
      context.stop(self)
  }
}

object CompletionHandler {

  /** Creates configuration object used to create a [[CompletionHandler]].
    *
    * @param timeout request timeout
    * @param suggestionsHandler a reference to the suggestions handler
    */
  def props(
    timeout: FiniteDuration,
    suggestionsHandler: ActorRef
  ): Props =
    Props(new CompletionHandler(timeout, suggestionsHandler))

}
