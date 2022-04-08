package org.enso.compiler.pass.resolve

import org.enso.compiler.context.{InlineContext, ModuleContext}
import org.enso.compiler.core.IR
import org.enso.compiler.data.BindingsMap
import org.enso.compiler.data.BindingsMap.{Resolution, ResolvedConstructor}
import org.enso.compiler.pass.IRPass

/** Resolves parameter-less calls to Atom Constructors with the parameter list
  * fully defaulted.
  */
object FullyAppliedFunctionUses extends IRPass {

  override type Metadata = BindingsMap.Resolution
  override type Config   = IRPass.Configuration.Default

  override val precursorPasses: Seq[IRPass] =
    Seq(UppercaseNames)
  override val invalidatedPasses: Seq[IRPass] = Seq()

  override def updateMetadataInDuplicate[T <: IR](sourceIr: T, copyOfIr: T): T =
    copyOfIr

  /** Executes the pass on the provided `ir`, and returns a possibly transformed
    * or annotated version of `ir`.
    *
    * @param ir            the Enso IR to process
    * @param moduleContext a context object that contains the information needed
    *                      to process a module
    * @return `ir`, possibly having made transformations or annotations to that
    *         IR.
    */
  override def runModule(
    ir: IR.Module,
    moduleContext: ModuleContext
  ): IR.Module = ir.mapExpressions(doExpression)

  /** Executes the pass on the provided `ir`, and returns a possibly transformed
    * or annotated version of `ir` in an inline context.
    *
    * @param ir            the Enso IR to process
    * @param inlineContext a context object that contains the information needed
    *                      for inline evaluation
    * @return `ir`, possibly having made transformations or annotations to that
    *         IR.
    */
  override def runExpression(
    ir: IR.Expression,
    inlineContext: InlineContext
  ): IR.Expression = doExpression(ir)

  private def doExpression(expr: IR.Expression): IR.Expression = {
    expr.transformExpressions {
      case app: IR.Application.Prefix =>
        app.copy(arguments = app.arguments.map(_.mapExpressions(doExpression)))
      case name: IR.Name.Literal =>
        val meta = name.getMetadata(UppercaseNames)
        meta match {
          case Some(Resolution(ResolvedConstructor(_, cons)))
              if cons.allFieldsDefaulted && cons.arity > 0 =>
            IR.Application.Prefix(name, List(), false, None);
          case _ => name
        }
    }
  }
}
