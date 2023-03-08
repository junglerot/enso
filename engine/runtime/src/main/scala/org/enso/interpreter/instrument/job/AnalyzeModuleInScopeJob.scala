package org.enso.interpreter.instrument.job

import org.enso.compiler.context.{
  ExportsBuilder,
  ModuleExportsDiff,
  SuggestionBuilder,
  SuggestionDiff
}
import org.enso.interpreter.instrument.execution.RuntimeContext
import org.enso.interpreter.runtime.Module
import org.enso.polyglot.data.Tree
import org.enso.polyglot.runtime.Runtime.Api
import org.enso.polyglot.{ModuleExports, Suggestion}

import java.util.logging.Level

final class AnalyzeModuleInScopeJob(
  modules: Iterable[Module]
) extends Job[Unit](
      List(AnalyzeModuleJob.backgroundContextId),
      false,
      false
    ) {

  private val exportsBuilder = new ExportsBuilder

  /** @inheritdoc */
  override def run(implicit ctx: RuntimeContext): Unit = {
    // There are two runtime flags that can disable suggestions for project
    // and global modules (libraries). They are used primarily in tests to
    // disable the suggestion updates and reduce the number of messages that
    // runtime sends.
    if (ctx.executionService.getContext.isProjectSuggestionsEnabled) {
      modules.foreach(analyzeModuleInScope)
      ctx.endpoint.sendToClient(
        Api.Response(Api.AnalyzeModuleInScopeJobFinished())
      )
    }
  }

  private def analyzeModuleInScope(module: Module)(implicit
    ctx: RuntimeContext
  ): Unit = {
    if (!module.isIndexed && module.getSource != null) {
      ctx.executionService.getLogger
        .log(Level.FINEST, s"Analyzing module in scope ${module.getName}")
      val moduleName = module.getName
      val newSuggestions = SuggestionBuilder(module.getSource.getCharacters)
        .build(moduleName, module.getIr)
        .filter(isSuggestionGlobal)
      val version     = ctx.versioning.evalVersion(module.getSource.getCharacters)
      val prevExports = ModuleExports(moduleName.toString, Set())
      val newExports  = exportsBuilder.build(module.getName, module.getIr)
      val notification = Api.SuggestionsDatabaseModuleUpdateNotification(
        module  = moduleName.toString,
        version = version,
        actions =
          Vector(Api.SuggestionsDatabaseAction.Clean(moduleName.toString)),
        exports = ModuleExportsDiff.compute(prevExports, newExports),
        updates = SuggestionDiff.compute(Tree.empty, newSuggestions)
      )
      sendModuleUpdate(notification)
      module.setIndexed(true)
    }
  }

  private def isSuggestionGlobal(suggestion: Suggestion): Boolean =
    suggestion match {
      case _: Suggestion.Module      => true
      case _: Suggestion.Type        => true
      case _: Suggestion.Constructor => true
      case _: Suggestion.Method      => true
      case _: Suggestion.Conversion  => true
      case _: Suggestion.Function    => false
      case _: Suggestion.Local       => false
    }

  /** Send notification about module updates.
    *
    * @param payload the module update
    * @param ctx the runtime context
    */
  private def sendModuleUpdate(
    payload: Api.SuggestionsDatabaseModuleUpdateNotification
  )(implicit ctx: RuntimeContext): Unit =
    if (
      payload.actions.nonEmpty ||
      payload.exports.nonEmpty ||
      !payload.updates.isEmpty
    ) {
      ctx.endpoint.sendToClient(Api.Response(payload))
    }
}

object AnalyzeModuleInScopeJob {

  /** Create an instance of [[AnalyzeModuleInScopeJob]].
    *
    * @param modules the list of modules to analyze
    * @return the [[AnalyzeModuleInScopeJob]]
    */
  def apply(modules: Iterable[Module]): AnalyzeModuleInScopeJob =
    new AnalyzeModuleInScopeJob(modules)
}
