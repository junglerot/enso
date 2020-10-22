package org.enso.compiler.pass.resolve

import org.enso.compiler.context.{InlineContext, ModuleContext}
import org.enso.compiler.core.IR
import org.enso.compiler.core.ir.MetadataStorage.ToPair
import org.enso.compiler.data.BindingsMap
import org.enso.compiler.exception.CompilerError
import org.enso.compiler.pass.IRPass
import org.enso.compiler.pass.analyse.{AliasAnalysis, BindingAnalysis}
import org.enso.compiler.pass.desugar.{GenerateMethodBodies, NestedPatternMatch}

/** Resolves constructors in pattern matches and validates their arity.
  */
object Patterns extends IRPass {

  override type Metadata = BindingsMap.Resolution
  override type Config   = IRPass.Configuration.Default

  override val precursorPasses: Seq[IRPass] =
    Seq(NestedPatternMatch, GenerateMethodBodies, BindingAnalysis)
  override val invalidatedPasses: Seq[IRPass] = Seq(AliasAnalysis)

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
  ): IR.Module = {
    val bindings = ir.unsafeGetMetadata(
      BindingAnalysis,
      "Binding resolution was not run before pattern resolution"
    )
    ir.mapExpressions(doExpression(_, bindings))
  }

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
  ): IR.Expression = {
    val bindings = inlineContext.module.getIr.unsafeGetMetadata(
      BindingAnalysis,
      "Binding resolution was not run before pattern resolution"
    )
    doExpression(ir, bindings)
  }

  private def doExpression(
    expr: IR.Expression,
    bindings: BindingsMap
  ): IR.Expression = {
    expr.transformExpressions { case caseExpr: IR.Case.Expr =>
      val newBranches = caseExpr.branches.map { branch =>
        val resolvedPattern = branch.pattern match {
          case consPat: IR.Pattern.Constructor =>
            val consName = consPat.constructor
            val resolution = consName match {
              case qual: IR.Name.Qualified =>
                val parts = qual.parts.map(_.name)
                Some(bindings.resolveQualifiedName(parts))
              case lit: IR.Name.Literal =>
                Some(bindings.resolveUppercaseName(lit.name))
              case _ => None
            }
            val resolvedName = resolution
              .map {
                case Left(err) =>
                  IR.Error.Resolution(
                    consPat.constructor,
                    IR.Error.Resolution.ResolverError(err)
                  )
                case Right(value: BindingsMap.ResolvedConstructor) =>
                  consName.updateMetadata(
                    this -->> BindingsMap.Resolution(value)
                  )
                case Right(value: BindingsMap.ResolvedModule) =>
                  consName.updateMetadata(
                    this -->> BindingsMap.Resolution(value)
                  )
                case Right(_: BindingsMap.ResolvedPolyglotSymbol) =>
                  IR.Error.Resolution(
                    consName,
                    IR.Error.Resolution.UnexpectedPolyglot(
                      "a pattern match"
                    )
                  )
                case Right(_: BindingsMap.ResolvedMethod) =>
                  IR.Error.Resolution(
                    consName,
                    IR.Error.Resolution.UnexpectedMethod(
                      "a pattern match"
                    )
                  )
              }
              .getOrElse(consName)

            val actualResolution = resolvedName.getMetadata(this)
            val expectedArity = actualResolution.map { res =>
              res.target match {
                case BindingsMap.ResolvedConstructor(_, cons) => cons.arity
                case BindingsMap.ResolvedModule(_)            => 0
                case BindingsMap.ResolvedPolyglotSymbol(_, _) =>
                  throw new CompilerError(
                    "Impossible, should be transformed into an error before."
                  )
                case BindingsMap.ResolvedMethod(_, _) =>
                  throw new CompilerError(
                    "Impossible, should be transformed into an error before."
                  )
              }
            }
            expectedArity match {
              case Some(arity) =>
                if (consPat.fields.length != arity) {
                  IR.Error.Pattern(
                    consPat,
                    IR.Error.Pattern.WrongArity(
                      consPat.constructor.name,
                      arity,
                      consPat.fields.length
                    )
                  )
                } else {
                  consPat.copy(constructor = resolvedName)
                }
              case None => consPat.copy(constructor = resolvedName)
            }
          case other => other
        }
        branch.copy(
          pattern    = resolvedPattern,
          expression = doExpression(branch.expression, bindings)
        )
      }
      caseExpr.copy(branches = newBranches)

    }
  }
}
