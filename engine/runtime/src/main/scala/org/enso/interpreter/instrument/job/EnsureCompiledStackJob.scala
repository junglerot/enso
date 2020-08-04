package org.enso.interpreter.instrument.job

import java.io.File

import org.enso.compiler.pass.analyse.CachePreferenceAnalysis
import org.enso.interpreter.instrument.{CacheInvalidation, InstrumentFrame}
import org.enso.interpreter.instrument.execution.RuntimeContext
import org.enso.polyglot.runtime.Runtime.Api

import scala.jdk.OptionConverters._

/**
  * Ensures that all files on the provided stack are compiled.
  *
  * @param stack a call stack
  */
class EnsureCompiledStackJob(stack: Iterable[InstrumentFrame])(implicit
  ctx: RuntimeContext
) extends EnsureCompiledJob(EnsureCompiledStackJob.extractFiles(stack)) {

  /** @inheritdoc */
  override def ensureCompiled(
    files: Iterable[File]
  )(implicit ctx: RuntimeContext): Unit = {
    super.ensureCompiled(files)
    getCacheMetadata(stack).foreach { metadata =>
      CacheInvalidation.run(
        stack,
        CacheInvalidation(
          CacheInvalidation.StackSelector.Top,
          CacheInvalidation.Command.SetMetadata(metadata)
        )
      )
    }
  }

  private def getCacheMetadata(
    stack: Iterable[InstrumentFrame]
  )(implicit ctx: RuntimeContext): Option[CachePreferenceAnalysis.Metadata] =
    stack.lastOption flatMap {
      case InstrumentFrame(Api.StackItem.ExplicitCall(ptr, _, _), _) =>
        ctx.executionService.getContext.findModule(ptr.module).toScala.map {
          module =>
            module.getIr
              .unsafeGetMetadata(
                CachePreferenceAnalysis,
                "Empty cache preference metadata"
              )
        }
      case _ => None
    }
}

object EnsureCompiledStackJob {

  /**
    * Extracts files to compile from a call stack.
    *
    * @param stack a call stack
    * @return a list of files to compile
    */
  private def extractFiles(stack: Iterable[InstrumentFrame])(implicit
    ctx: RuntimeContext
  ): Iterable[File] =
    stack
      .map(_.item)
      .flatMap {
        case Api.StackItem.ExplicitCall(methodPointer, _, _) =>
          ctx.executionService.getContext
            .findModule(methodPointer.module)
            .flatMap(module => java.util.Optional.ofNullable(module.getPath))
            .map(path => new File(path))
            .toScala
        case _ =>
          None
      }
}
