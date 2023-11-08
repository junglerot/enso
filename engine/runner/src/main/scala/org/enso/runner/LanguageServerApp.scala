package org.enso.runner

import com.typesafe.scalalogging.Logger
import org.enso.languageserver.boot.{
  LanguageServerComponent,
  LanguageServerConfig
}
import org.slf4j.event.Level

import java.util.concurrent.Semaphore
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.io.StdIn

/** Language server runner.
  */
object LanguageServerApp {

  private val semaphore   = new Semaphore(1)
  private lazy val logger = Logger[LanguageServerApp.type]

  /** Runs a Language Server
    *
    * @param config the application config
    * @param logLevel the logging level
    * @param daemonize should the language server process be daemonized
    */
  def run(
    config: LanguageServerConfig,
    logLevel: Level,
    daemonize: Boolean
  ): Unit = {
    val server = new LanguageServerComponent(config, logLevel)
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      stop(server, "shutdown hook")(config.computeExecutionContext)
    }))
    Await.result(server.start(), 1.minute)
    if (daemonize) {
      val lock = new AnyRef
      lock.synchronized {
        lock.wait()
      }
    } else {
      StdIn.readLine()
      stop(server, "stopped by the user")(config.computeExecutionContext)
    }
  }

  /** Stops the language server.
    *
    * @param server the language server component
    * @param ec the execution context
    */
  private def stop(
    server: LanguageServerComponent,
    reason: String
  )(implicit ec: ExecutionContext): Unit = {
    logger.info("Stopping Language Server: {}", reason)
    Await.ready(synchronize(server.stop()), 40.seconds)
  }

  /** Makes sure that the calls to the provided future are synchronized. */
  private def synchronize[A](
    fut: => Future[A]
  )(implicit ec: ExecutionContext): Future[A] = {
    val task = for {
      _      <- Future { semaphore.acquire() }
      result <- fut
    } yield result
    task.onComplete(_ => semaphore.release())

    task
  }
}
