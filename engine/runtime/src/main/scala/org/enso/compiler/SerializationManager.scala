package org.enso.compiler

import com.oracle.truffle.api.TruffleLogger
import com.oracle.truffle.api.source.Source
import org.enso.compiler.context.SuggestionBuilder
import org.enso.compiler.core.IR
import org.enso.compiler.pass.analyse.BindingAnalysis
import org.enso.editions.LibraryName
import org.enso.interpreter.runtime.Module
import org.enso.pkg.QualifiedName
import org.enso.polyglot.Suggestion

import java.io.NotSerializableException
import java.util
import java.util.concurrent.{
  Callable,
  CompletableFuture,
  ConcurrentHashMap,
  Future,
  LinkedBlockingDeque,
  ThreadPoolExecutor,
  TimeUnit
}
import java.util.logging.Level

import scala.collection.mutable
import scala.jdk.OptionConverters.RichOptional

final class SerializationManager(
  compiler: Compiler
) {

  import SerializationManager._

  /** The debug logging level. */
  private val debugLogLevel = Level.FINE

  /** A logger for messages regarding serialization. */
  private val logger: TruffleLogger =
    compiler.context.getLogger(classOf[SerializationManager])

  /** A set of the modules that are currently being serialized.
    *
    * This set is accessed concurrently. This is safe as it is backed by a
    * [[ConcurrentHashMap]] and is wrapped with the scala [[mutable.Set]]
    * interface.
    */
  private val isSerializing: mutable.Set[QualifiedName] = buildConcurrentHashSet

  /** A map of the modules awaiting serialization to their associated tasks
    *
    * This map is accessed concurrently.
    */
  private val isWaitingForSerialization =
    collection.concurrent.TrieMap[QualifiedName, Future[Boolean]]()

  /** The runtime's environment. */
  private val env = compiler.context.getEnvironment

  /** The thread pool that handles serialization. */
  private val pool: ThreadPoolExecutor = new ThreadPoolExecutor(
    SerializationManager.startingThreadCount,
    SerializationManager.maximumThreadCount,
    SerializationManager.threadKeepalive,
    TimeUnit.SECONDS,
    new LinkedBlockingDeque[Runnable](),
    (runnable: Runnable) => {
      env.createSystemThread(runnable)
    }
  )

  // Make sure it is started to avoid races with language shutdown with low job
  // count.
  if (compiler.context.getEnvironment.isCreateThreadAllowed) {
    pool.prestartAllCoreThreads()
  }

  // === Interface ============================================================

  /** Requests that `module` be serialized.
    *
    * This method will attempt to schedule the provided module and IR for
    * serialization regardless of whether or not it is appropriate to do so. If
    * there are preconditions needed for serialization, these should be checked
    * before calling this method.
    *
    * In addition, this method handles breaking links between modules contained
    * in the IR to ensure safe serialization.
    *
    * It is responsible for taking a "snapshot" of the relevant module state at
    * the point at which serialization is requested. This is due to the fact
    * that serialization happens in a separate thread and the module may be
    * mutated beneath it.
    *
    * @param module the module to serialize
    * @param useGlobalCacheLocations if true, will use global caches location, local one otherwise
    * @param useThreadPool if true, will perform serialization asynchronously
    * @return Future referencing the serialization task. On completion Future will return
    *         `true` if `module` has been successfully serialized, `false` otherwise
    */
  def serializeModule(
    module: Module,
    useGlobalCacheLocations: Boolean,
    useThreadPool: Boolean = true
  ): Future[Boolean] = {
    logger.log(
      debugLogLevel,
      "Requesting serialization for module [{0}].",
      module.getName
    )
    val duplicatedIr = compiler.updateMetadata(
      module.getIr,
      module.getIr.duplicate(keepIdentifiers = true)
    )
    duplicatedIr.preorder.foreach(_.passData.prepareForSerialization(compiler))

    val task = doSerializeModule(
      module.getCache,
      duplicatedIr,
      module.getCompilationStage,
      module.getName,
      module.getSource,
      useGlobalCacheLocations
    )
    if (useThreadPool) {
      isWaitingForSerialization.synchronized {
        val future = pool.submit(task)
        isWaitingForSerialization.put(module.getName, future)
        future
      }
    } else {
      try {
        CompletableFuture.completedFuture(task.call())
      } catch {
        case e: Throwable =>
          logger.log(
            debugLogLevel,
            s"Serialization task failed in module [${module.getName}].",
            e
          )
          CompletableFuture.completedFuture(false)
      }
    }
  }

  def serializeLibrary(
    libraryName: LibraryName,
    useGlobalCacheLocations: Boolean
  ): Future[Boolean] = {
    logger.log(
      Level.INFO,
      "Requesting serialization for library [{0}].",
      libraryName
    )

    val task: Callable[Boolean] =
      doSerializeLibrary(libraryName, useGlobalCacheLocations)
    if (compiler.context.getEnvironment.isCreateThreadAllowed) {
      isWaitingForSerialization.synchronized {
        val future = pool.submit(task)
        isWaitingForSerialization.put(libraryName.toQualifiedName, future)
        future
      }
    } else {
      try {
        CompletableFuture.completedFuture(task.call())
      } catch {
        case e: Throwable =>
          logger.log(
            debugLogLevel,
            s"Serialization task failed for library [$libraryName].",
            e
          )
          CompletableFuture.completedFuture(false)
      }
    }
  }

  private def doSerializeLibrary(
    libraryName: LibraryName,
    useGlobalCacheLocations: Boolean
  ): Callable[Boolean] = () => {
    while (isSerializingLibrary(libraryName)) {
      Thread.sleep(100)
    }

    logger.log(
      debugLogLevel,
      "Running serialization for bindings [{0}].",
      libraryName
    )
    startSerializing(libraryName.toQualifiedName)
    val bindingsCache = new ImportExportCache.CachedBindings(
      libraryName,
      new ImportExportCache.MapToBindings(
        compiler.packageRepository
          .getModulesForLibrary(libraryName)
          .map { module =>
            val ir = module.getIr
            val bindings = ir.unsafeGetMetadata(
              BindingAnalysis,
              "Non-parsed module used in ImportResolver"
            )
            val abstractBindings = bindings.prepareForSerialization(compiler)
            (module.getName, abstractBindings)
          }
          .toMap
      ),
      compiler.packageRepository
        .getPackageForLibraryJava(libraryName)
        .map(_.listSourcesJava())
    )
    try {
      val result =
        try {
          new ImportExportCache(libraryName)
            .save(bindingsCache, compiler.context, useGlobalCacheLocations)
            .isPresent
        } catch {
          case e: NotSerializableException =>
            logger.log(
              Level.SEVERE,
              s"Could not serialize bindings [$libraryName].",
              e
            )
            throw e
          case e: Throwable =>
            logger.log(
              Level.SEVERE,
              s"Serialization of bindings `$libraryName` failed: ${e.getMessage}`",
              e
            )
            throw e
        }

      try {
        val suggestions = new util.ArrayList[Suggestion]()
        compiler.packageRepository
          .getModulesForLibrary(libraryName)
          .flatMap { module =>
            SuggestionBuilder(module)
              .build(module.getName, module.getIr)
              .toVector
          }
          .foreach(suggestions.add)
        val cachedSuggestions =
          new SuggestionsCache.CachedSuggestions(
            libraryName,
            new SuggestionsCache.Suggestions(suggestions),
            compiler.packageRepository
              .getPackageForLibraryJava(libraryName)
              .map(_.listSourcesJava())
          )
        new SuggestionsCache(libraryName)
          .save(cachedSuggestions, compiler.context, useGlobalCacheLocations)
          .isPresent
      } catch {
        case e: NotSerializableException =>
          logger.log(
            Level.SEVERE,
            s"Could not serialize suggestions [$libraryName].",
            e
          )
          throw e
        case e: Throwable =>
          logger.log(
            Level.SEVERE,
            s"Serialization of suggestions `$libraryName` failed: ${e.getMessage}`",
            e
          )
          throw e
      }

      result
    } finally {
      finishSerializing(libraryName.toQualifiedName)
    }
  }

  def deserializeSuggestions(
    libraryName: LibraryName
  ): Option[SuggestionsCache.CachedSuggestions] = {
    if (isWaitingForSerialization(libraryName)) {
      abort(libraryName)
      None
    } else {
      while (isSerializingLibrary(libraryName)) {
        Thread.sleep(100)
      }
      new SuggestionsCache(libraryName).load(compiler.context).toScala match {
        case result @ Some(_: SuggestionsCache.CachedSuggestions) =>
          logger.log(
            Level.FINE,
            "Restored suggestions for library [{0}].",
            libraryName
          )
          result
        case _ =>
          logger.log(
            Level.FINEST,
            "Unable to load suggestions for library [{0}].",
            libraryName
          )
          None
      }
    }
  }

  def deserializeLibraryBindings(
    libraryName: LibraryName
  ): Option[ImportExportCache.CachedBindings] = {
    if (isWaitingForSerialization(libraryName)) {
      abort(libraryName)
      None
    } else {
      while (isSerializingLibrary(libraryName)) {
        Thread.sleep(100)
      }
      new ImportExportCache(libraryName).load(compiler.context).toScala match {
        case result @ Some(_: ImportExportCache.CachedBindings) =>
          logger.log(
            Level.FINE,
            "Restored bindings for library [{0}].",
            libraryName
          )
          result
        case _ =>
          logger.log(
            Level.FINEST,
            "Unable to load bindings for library [{0}].",
            libraryName
          )
          None
      }

    }
  }

  /** Deserializes the requested module from the cache if possible.
    *
    * If the requested module is currently being serialized it will wait for
    * completion before loading. If the module is queued for serialization it
    * will evict it and not load from the cache (this is usually indicative of a
    * programming bug).
    *
    * @param module the module to deserialize from the cache.
    * @return [[Some]] when deserialization was successful, with `true` for
    *         relinking being successful and `false` otherwise. [[None]] if the
    *         cache could not be deserialized.
    */
  def deserialize(module: Module): Option[Boolean] = {
    if (isWaitingForSerialization(module)) {
      abort(module)
      None
    } else {
      while (isSerializingModule(module.getName)) {
        Thread.sleep(100)
      }

      module.getCache.load(compiler.context).toScala match {
        case Some(loadedCache) =>
          val relinkedIrChecks =
            loadedCache
              .moduleIR()
              .preorder
              .map(_.passData.restoreFromSerialization(this.compiler))
          module.unsafeSetIr(loadedCache.moduleIR())
          module.unsafeSetCompilationStage(loadedCache.compilationStage())
          module.setLoadedFromCache(true)
          logger.log(
            debugLogLevel,
            "Restored IR from cache for module [{0}] at stage [{1}].",
            Array(module.getName, loadedCache.compilationStage())
          )

          if (!relinkedIrChecks.contains(false)) {
            module.setHasCrossModuleLinks(true)
            logger.log(
              debugLogLevel,
              "Restored links (early phase) in module [{0}].",
              module.getName
            )
            Some(true)
          } else {
            logger.log(
              debugLogLevel,
              "Could not restore links (early phase) in module [{0}].",
              module.getName
            )
            module.setHasCrossModuleLinks(false)
            Some(false)
          }
        case None =>
          logger.log(
            debugLogLevel,
            "Unable to load a cache for module [{0}].",
            module.getName
          )
          None
      }
    }
  }

  /** Checks if the provided module is in the process of being serialized.
    *
    * @param module the module to check
    * @return `true` if `module` is currently being serialized, `false`
    *         otherwise
    */
  private def isSerializingModule(module: QualifiedName): Boolean = {
    isSerializing.contains(module)
  }

  private def isSerializingLibrary(library: LibraryName): Boolean = {
    isSerializing.contains(library.toQualifiedName)
  }

  private def isWaitingForSerialization(name: QualifiedName): Boolean = {
    isWaitingForSerialization.synchronized {
      isWaitingForSerialization.contains(name)
    }
  }

  /** Checks if the provided module is waiting for serialization.
    *
    * @param module the module to check
    * @return `true` if `module` is waiting for serialization, `false` otherwise
    */
  private def isWaitingForSerialization(module: Module): Boolean = {
    isWaitingForSerialization(module.getName)
  }

  /** Checks if the provided library's bindings are waiting for serialization.
    *
    * @param library the library to check
    * @return `true` if `library` is waiting for serialization, `false` otherwise
    */
  private def isWaitingForSerialization(library: LibraryName): Boolean = {
    isWaitingForSerialization(library.toQualifiedName)
  }

  private def abort(name: QualifiedName): Boolean = {
    isWaitingForSerialization.synchronized {
      if (isWaitingForSerialization(name)) {
        isWaitingForSerialization
          .remove(name)
          .map(_.cancel(false))
          .getOrElse(false)
      } else false
    }
  }

  /** Requests that serialization of `module` be aborted.
    *
    * If the module is already in the process of serialization it will not be
    * aborted.
    *
    * @param module the module for which to abort serialization
    * @return `true` if serialization for `module` was aborted, `false`
    *         otherwise
    */
  private def abort(module: Module): Boolean = {
    abort(module.getName)
  }

  /** Requests that serialization of library's bindings be aborted.
    *
    * If the library is already in the process of serialization it will not be
    * aborted.
    *
    * @param library the library for which to abort serialization
    * @return `true` if serialization for `library` was aborted, `false`
    *         otherwise
    */
  private def abort(library: LibraryName): Boolean = {
    abort(library.toQualifiedName)
  }

  /** Performs shutdown actions for the serialization manager.
    *
    * @param waitForPendingJobCompletion whether or not shutdown should wait for
    *                                    pending serialization jobs
    */
  def shutdown(waitForPendingJobCompletion: Boolean = false): Unit = {
    if (!pool.isShutdown) {
      if (waitForPendingJobCompletion && this.hasJobsRemaining) {
        val waitingCount = isWaitingForSerialization.synchronized {
          isWaitingForSerialization.size
        }
        val jobCount = waitingCount + isSerializing.size
        logger.log(
          debugLogLevel,
          "Waiting for #{0} serialization jobs to complete.",
          jobCount
        )

        // Bound the waiting loop
        val maxCount = 60
        var counter  = 0
        while (this.hasJobsRemaining && counter < maxCount) {
          counter += 1
          Thread.sleep(1 * 1000)
        }
      }

      pool.shutdown()

      // Bound the waiting loop
      val maxCount = 10
      var counter  = 0
      while (!pool.isTerminated && counter < maxCount) {
        pool.awaitTermination(500, TimeUnit.MILLISECONDS)
        counter += 1
      }

      pool.shutdownNow()
      Thread.sleep(100)
      logger.log(debugLogLevel, "Serialization manager has been shut down.")
    }
  }

  // === Internals ============================================================

  /** @return `true` if there are remaining serialization jobs, `false`
    *         otherwise
    */
  private def hasJobsRemaining: Boolean = {
    isWaitingForSerialization.synchronized {
      isWaitingForSerialization.nonEmpty || isSerializing.nonEmpty
    }
  }

  /** Create the task that serializes the provided module IR when it is run.
    *
    * @param cache the cache manager for the module being serialized
    * @param ir the IR for the module being serialized
    * @param stage the compilation stage of the module
    * @param name the name of the module being serialized
    * @param source the source of the module being serialized
    * @param useGlobalCacheLocations if true, will use global caches location, local one otherwise
    * @return the task that serialies the provided `ir`
    */
  private def doSerializeModule(
    cache: ModuleCache,
    ir: IR.Module,
    stage: Module.CompilationStage,
    name: QualifiedName,
    source: Source,
    useGlobalCacheLocations: Boolean
  ): Callable[Boolean] = { () =>
    while (isSerializingModule(name)) {
      Thread.sleep(100)
    }

    logger.log(
      debugLogLevel,
      "Running serialization for module [{0}].",
      name
    )
    startSerializing(name)
    try {
      val fixedStage =
        if (stage.isAtLeast(Module.CompilationStage.AFTER_STATIC_PASSES)) {
          Module.CompilationStage.AFTER_STATIC_PASSES
        } else stage
      cache
        .save(
          new ModuleCache.CachedModule(ir, fixedStage, source),
          compiler.context,
          useGlobalCacheLocations
        )
        .map(_ => true)
        .orElse(false)
    } catch {
      case e: NotSerializableException =>
        logger.log(
          Level.SEVERE,
          s"Could not serialize module [$name].",
          e
        )
        throw e
      case e: Throwable =>
        logger.log(
          Level.SEVERE,
          s"Serialization of module `$name` failed: ${e.getMessage}`",
          e
        )
        throw e
    } finally {
      finishSerializing(name)
    }
  }

  /** Sets the module described by `name` as serializing.
    *
    * @param name the name of the module to set as serializing
    */
  private def startSerializing(name: QualifiedName): Unit = {
    isWaitingForSerialization.synchronized {
      isWaitingForSerialization.remove(name)
    }
    isSerializing.add(name)
  }

  /** Sets the module described by `name` as finished with serialization.
    *
    * @param name the name of the module to set as having finished serialization
    */
  private def finishSerializing(name: QualifiedName): Unit = {
    isSerializing.remove(name)
  }

  /** Builds a [[mutable.Set]] that is backed by a [[ConcurrentHashMap]] and is
    * hence safe for concurrent access.
    *
    * @tparam T the type of the set elements
    * @return a concurrent [[mutable.Set]]
    */
  private def buildConcurrentHashSet[T]: mutable.Set[T] = {
    import scala.jdk.CollectionConverters._
    java.util.Collections
      .newSetFromMap(
        new ConcurrentHashMap[T, java.lang.Boolean]()
      )
      .asScala
  }
}

object SerializationManager {

  /** The maximum number of serialization threads allowed. */
  val maximumThreadCount: Integer = 2

  /** The number of threads at compiler start. */
  val startingThreadCount: Integer = maximumThreadCount

  /** The thread keep-alive time in seconds. */
  val threadKeepalive: Long = 3

  implicit private class LibraryOps(val libraryName: LibraryName)
      extends AnyVal {
    def toQualifiedName: QualifiedName =
      QualifiedName(List(libraryName.namespace), libraryName.name)
  }

}
