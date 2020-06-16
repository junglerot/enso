package org.enso.interpreter.instrument.execution

import org.enso.interpreter.instrument.job.Job

import scala.concurrent.Future

/**
  * Defines a uniform interface to execute job.
  */
trait JobProcessor {

  /**
    * Runs a job with the provided context.
    *
    * @param job a job to execute
    * @return the future result of an asynchronous computation
    */
  def run[A](job: Job[A]): Future[A]

  /**
    * Stops the job processor.
    */
  def stop(): Unit

}
