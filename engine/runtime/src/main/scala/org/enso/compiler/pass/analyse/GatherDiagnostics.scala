package org.enso.compiler.pass.analyse

import org.enso.compiler.context.{InlineContext, ModuleContext}
import org.enso.compiler.core.IR
import org.enso.compiler.core.ir.MetadataStorage._
import org.enso.compiler.pass.IRPass

/** A pass that traverses the given root IR and accumulates all the encountered
  * diagnostic nodes in the root.
  *
  * This pass requires the context to provide:
  *
  * - Nothing
  */
case object GatherDiagnostics extends IRPass {
  override type Metadata = DiagnosticsMeta
  override type Config   = IRPass.Configuration.Default

  override val precursorPasses: Seq[IRPass]   = List()
  override val invalidatedPasses: Seq[IRPass] = List()

  /** Executes the pass on the provided `ir`, and attaches all the encountered
    * diagnostics to its metadata storage.
    *
    * @param ir the Enso IR to process
    * @param moduleContext a context object that contains the information needed
    *                      to process a module
    * @return `ir`, possibly having made transformations or annotations to that
    *         IR.
    */
  override def runModule(
    ir: IR.Module,
    moduleContext: ModuleContext
  ): IR.Module =
    ir.updateMetadata(this -->> gatherMetadata(ir))

  /** Executes the pass on the provided `ir`, and attaches all the encountered
    * diagnostics to its metadata storage.
    *
    * @param ir the IR to process
    * @param inlineContext a context object that contains the information needed
    *                      for inline evaluation
    * @return `ir` with all the errors accumulated in pass metadata.
    */
  override def runExpression(
    ir: IR.Expression,
    inlineContext: InlineContext
  ): IR.Expression = ir.updateMetadata(this -->> gatherMetadata(ir))

  /** Gathers diagnostics from all children of an IR node.
    *
    * @param ir the node to gather diagnostics from
    * @return `ir`, with all diagnostics from its subtree associated with it
    */
  private def gatherMetadata(ir: IR): DiagnosticsMeta = {
    val diagnostics = ir.preorder.collect {
      case err: IR.Diagnostic => List(err)
      case x                  => x.diagnostics.toList
    }.flatten
    DiagnosticsMeta(
      diagnostics.distinctBy(d => (d.location, d.toString))
    )
  }

  /** A container for diagnostics found in the IR.
    *
    * @param diagnostics a list of the errors found in the IR
    */
  case class DiagnosticsMeta(diagnostics: List[IR.Diagnostic])
      extends IRPass.Metadata {

    /** The name of the metadata as a string. */
    override val metadataName: String = "GatherDiagnostics.Diagnostics"

    override def duplicate(): Option[IRPass.Metadata] =
      Some(this)
  }
}
