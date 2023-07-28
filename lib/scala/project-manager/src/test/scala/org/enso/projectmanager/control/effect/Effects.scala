package org.enso.projectmanager.control.effect

import scala.concurrent.Await
import scala.concurrent.duration._

/** Functions helping to manage effects in tests. */
trait Effects {

  protected def opTimeout: FiniteDuration = 3.seconds

  implicit final class UnsafeRunZio[E, A](io: zio.ZIO[zio.ZAny, E, A]) {
    def unsafeRunSync(): Either[E, A] =
      Await.result(new ZioEnvExec(zio.Runtime.default).exec(io), opTimeout)
  }
}

object Effects extends Effects
