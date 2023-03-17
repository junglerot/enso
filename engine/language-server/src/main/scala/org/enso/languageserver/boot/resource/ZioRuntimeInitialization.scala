package org.enso.languageserver.boot.resource

import com.typesafe.scalalogging.LazyLogging
import org.enso.languageserver.effect

import scala.concurrent.{ExecutionContext, Future}

/** Initialization of ZIO runtime.
  *
  * @param runtime the runtime to initialize
  * @param ec the execution context
  */
class ZioRuntimeInitialization(runtime: effect.Runtime)(implicit
  ec: ExecutionContext
) extends InitializationComponent
    with LazyLogging {

  /** @inheritdoc */
  override def init(): Future[InitializationComponent.Initialized.type] =
    Future {
      logger.info("Initializing ZIO runtime...")
      runtime.init()
      logger.info("ZIO runtime initialized [{}].", runtime)
      InitializationComponent.Initialized
    }
}
