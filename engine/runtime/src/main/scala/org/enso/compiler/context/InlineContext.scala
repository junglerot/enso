package org.enso.compiler.context

import org.enso.compiler.data.CompilerConfig
import org.enso.compiler.pass.PassConfiguration
import org.enso.interpreter.node.BaseNode.TailStatus
import org.enso.interpreter.runtime.Module
import org.enso.interpreter.runtime.scope.{LocalScope, ModuleScope}

/** A type containing the information about the execution context for an inline
  * expression.
  *
  * @param module the module in which the expression is being executed
  * @param localScope the local scope in which the expression is being executed
  * @param isInTailPosition whether or not the inline expression occurs in tail
  *                         position ([[None]] indicates no information)
  * @param freshNameSupply the compiler's supply of fresh names
  * @param passConfiguration the pass configuration
  * @param compilerConfig the compiler configuration
  */
case class InlineContext(
  module: Module,
  localScope: Option[LocalScope]               = None,
  isInTailPosition: Option[Boolean]            = None,
  freshNameSupply: Option[FreshNameSupply]     = None,
  passConfiguration: Option[PassConfiguration] = None,
  compilerConfig: CompilerConfig
)
object InlineContext {

  /** Implements a null-safe conversion from nullable objects to Scala's option
    * internally.
    *
    * @param localScope the local scope instance
    * @param moduleScope the module scope instance
    * @param isInTailPosition whether or not the inline expression occurs in a
    *                         tail position
    * @return the [[InlineContext]] instance corresponding to the arguments
    */
  def fromJava(
    localScope: LocalScope,
    moduleScope: ModuleScope,
    isInTailPosition: TailStatus,
    compilerConfig: CompilerConfig
  ): InlineContext = {
    InlineContext(
      localScope       = Option(localScope),
      module           = moduleScope.getModule,
      isInTailPosition = Option(isInTailPosition != TailStatus.NOT_TAIL),
      compilerConfig   = compilerConfig
    )
  }

  /** Transform a module context into an inline context, retaining the useful
    * information.
    *
    * @param moduleContext the module context
    * @return an inline context wrapping the same data as `moduleContext`
    */
  def fromModuleContext(moduleContext: ModuleContext): InlineContext = {
    InlineContext(
      localScope        = None,
      module            = moduleContext.module,
      isInTailPosition  = None,
      freshNameSupply   = moduleContext.freshNameSupply,
      passConfiguration = moduleContext.passConfiguration,
      compilerConfig    = moduleContext.compilerConfig
    )
  }
}
