package org.enso.projectmanager.control.effect

import zio.ZIO

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/** Instance of [[Async]] class for ZIO.
  */
class ZioAsync[R] extends Async[ZIO[R, +*, +*]] {

  implicit private val immediateEc =
    ExecutionContext.fromExecutor(ImmediateExecutor)

  /** @inheritdoc */
  override def async[E, A](
    register: (Either[E, A] => Unit) => Unit
  ): ZIO[R, E, A] =
    ZIO.effectAsync[R, E, A] { callback =>
      register { result => callback(ZIO.fromEither(result)) }

    }

  /** @inheritdoc */
  override def fromFuture[A](thunk: () => Future[A]): ZIO[R, Throwable, A] =
    ZIO.effectAsync[R, Throwable, A] { cb =>
      thunk().onComplete {
        case Success(value)     => cb(ZIO.succeed(value))
        case Failure(exception) => cb(ZIO.fail(exception))
      }
    }
}
