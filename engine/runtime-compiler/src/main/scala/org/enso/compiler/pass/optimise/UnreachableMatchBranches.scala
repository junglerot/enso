package org.enso.compiler.pass.optimise

import org.enso.compiler.context.{InlineContext, ModuleContext}
import org.enso.compiler.core.Implicits.AsDiagnostics
import org.enso.compiler.core.ir.{
  Expression,
  IdentifiedLocation,
  Location,
  Module,
  Pattern
}
import org.enso.compiler.core.ir.expression.{errors, warnings, Case}
import org.enso.compiler.core.CompilerError
import org.enso.compiler.pass.IRPass
import org.enso.compiler.pass.analyse.{
  AliasAnalysis,
  DataflowAnalysis,
  DemandAnalysis,
  TailCall
}
import org.enso.compiler.pass.desugar._
import org.enso.compiler.pass.resolve.{DocumentationComments, IgnoredBindings}

import scala.annotation.unused

/** This pass discovers and optimised away unreachable case branches.
  *
  * It removes these unreachable expressions from the IR, and attaches a
  * [[org.enso.compiler.core.ir.Warning]] diagnostic to the case expression itself.
  *
  * Currently, a branch is considered 'unreachable' by this pass if:
  *
  * - It occurs after a catch-all branch.
  *
  * In the future, this pass should be expanded to consider patterns that are
  * entirely subsumed by previous patterns in its definition of uncreachable,
  * but this requires doing sophisticated coverage analysis, and hence should
  * happen as part of the broader refactor of nested patterns desugaring.
  *
  * This pass requires no configuration.
  *
  * This pass requires the context to provide:
  *
  * - Nothing
  */
case object UnreachableMatchBranches extends IRPass {
  override type Metadata = IRPass.Metadata.Empty
  override type Config   = IRPass.Configuration.Default

  override lazy val precursorPasses: Seq[IRPass] = List(
    ComplexType,
    DocumentationComments,
    FunctionBinding,
    GenerateMethodBodies,
    LambdaShorthandToLambda
  )
  override lazy val invalidatedPasses: Seq[IRPass] = List(
    AliasAnalysis,
    DataflowAnalysis,
    DemandAnalysis,
    IgnoredBindings,
    NestedPatternMatch,
    TailCall
  )

  /** Runs unreachable branch optimisation on a module.
    *
    * @param ir the Enso IR to process
    * @param moduleContext a context object that contains the information needed
    *                      to process a module
    * @return `ir`, possibly having made transformations or annotations to that
    *         IR.
    */
  override def runModule(
    ir: Module,
    @unused moduleContext: ModuleContext
  ): Module = {
    ir.mapExpressions(optimizeExpression)
  }

  /** Runs unreachable branch optimisation on an expression.
    *
    * @param ir the Enso IR to process
    * @param inlineContext a context object that contains the information needed
    *                      for inline evaluation
    * @return `ir`, possibly having made transformations or annotations to that
    *         IR.
    */
  override def runExpression(
    ir: Expression,
    @unused inlineContext: InlineContext
  ): Expression = {
    ir.transformExpressions { case x =>
      optimizeExpression(x)
    }
  }

  // === Pass Internals =======================================================

  /** Optimizes an expression by removing unreachable branches in case
    * expressions.
    *
    * @param expression the expression to optimize
    * @return `expression` with unreachable case branches removed
    */
  def optimizeExpression(expression: Expression): Expression = {
    expression.transformExpressions { case cse: Case =>
      optimizeCase(cse)
    }
  }

  /** Optimizes a case expression by removing unreachable branches.
    *
    * Additionally, it will attach a warning about unreachable branches to the
    * case expression.
    *
    * @param cse the case expression to optimize
    * @return `cse` with unreachable branches removed
    */
  //noinspection DuplicatedCode
  def optimizeCase(cse: Case): Case = {
    cse match {
      case expr @ Case.Expr(scrutinee, branches, _, _, _, _) =>
        val reachableNonCatchAllBranches = branches.takeWhile(!isCatchAll(_))
        val firstCatchAll                = branches.find(isCatchAll)
        val unreachableBranches =
          branches.dropWhile(!isCatchAll(_)).drop(1)
        val reachableBranches = firstCatchAll
          .flatMap(b => Some(reachableNonCatchAllBranches :+ b))
          .getOrElse(List())
          .toList

        if (unreachableBranches.isEmpty) {
          expr.copy(
            scrutinee = optimizeExpression(scrutinee),
            branches = branches.map(b =>
              b.copy(expression = optimizeExpression(b.expression))
            )
          )
        } else {
          val unreachableLocation =
            unreachableBranches.foldLeft(None: Option[IdentifiedLocation])(
              (loc, branch) => {
                loc match {
                  case Some(loc) =>
                    branch.location match {
                      case Some(branchLoc) =>
                        Some(
                          IdentifiedLocation.create(
                            new Location(loc.start, branchLoc.end),
                            loc.id
                          )
                        )
                      case None => Some(loc)
                    }
                  case None => branch.location
                }
              }
            )

          val diagnostic = warnings.Unreachable.Branches(unreachableLocation)

          expr
            .copy(
              scrutinee = optimizeExpression(scrutinee),
              branches = reachableBranches
                .map(b => b.copy(expression = optimizeExpression(b.expression)))
            )
            .addDiagnostic(diagnostic)
        }
      case _: Case.Branch =>
        throw new CompilerError("Unexpected case branch.")
    }
  }

  /** Determines if a branch is a catch all branch.
    *
    * @param branch the branch to check
    * @return `true` if `branch` is catch-all, otherwise `false`
    */
  def isCatchAll(branch: Case.Branch): Boolean = {
    branch.pattern match {
      case _: Pattern.Name          => true
      case _: Pattern.Constructor   => false
      case _: Pattern.Literal       => false
      case _: Pattern.Type          => false
      case _: Pattern.Documentation => false
      case _: errors.Pattern        => true
    }
  }
}
