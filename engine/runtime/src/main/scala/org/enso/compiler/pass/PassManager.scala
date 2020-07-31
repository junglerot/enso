package org.enso.compiler.pass

import org.enso.compiler.context.{InlineContext, ModuleContext}
import org.enso.compiler.core.IR
import org.enso.compiler.exception.CompilerError

// TODO [AA] In the future, the pass ordering should be _computed_ from the list
//  of available passes, rather than just verified.

/** A manager for compiler passes.
  *
  * It is responsible for verifying and executing passes (in groups) on the
  * compiler IR.
  *
  * @param passes the pass groups, must all be unique
  * @param passConfiguration the configuration for each pass in `passes`
  */
//noinspection DuplicatedCode
class PassManager(
  passes: List[PassGroup],
  passConfiguration: PassConfiguration
) {
  val allPasses = verifyPassOrdering(passes.flatMap(_.passes))

  /** Computes a valid pass ordering for the compiler.
    *
    * @param passes the input list of passes
    * @throws CompilerError if a valid pass ordering cannot be computed
    * @return a valid pass ordering for the compiler, based on `passes`
    */
  def verifyPassOrdering(passes: List[IRPass]): List[IRPass] = {
    var validPasses: Set[IRPass] = Set()

    passes.foreach(pass => {
      val prereqsSatisfied =
        pass.precursorPasses.forall(validPasses.contains(_))

      if (prereqsSatisfied) {
        validPasses += pass
      } else {
        val missingPrereqsStr =
          pass.precursorPasses.filterNot(validPasses.contains(_)).mkString(", ")

        throw new CompilerError(
          s"The pass ordering is invalid. $pass is missing valid results " +
          s"for: $missingPrereqsStr"
        )
      }

      pass.invalidatedPasses.foreach(p => validPasses -= p)
    })

    passes
  }

  /** Executes all pass groups on the [[IR.Module]].
    *
    * @param ir the module to execute the compiler passes on
    * @param moduleContext the module context in which the passes are executed
    * @return the result of executing `passGroup` on `ir`
    */
  def runPassesOnModule(
    ir: IR.Module,
    moduleContext: ModuleContext
  ): IR.Module = {
    passes.foldLeft(ir)((ir, group) =>
      runPassesOnModule(ir, moduleContext, group)
    )
  }

  /** Executes the provided `passGroup` on the [[IR.Module]].
    *
    * @param ir the module to execute the compiler passes on
    * @param moduleContext the module context in which the passes are executed
    * @param passGroup the group of passes being executed
    * @return the result of executing `passGroup` on `ir`
    */
  def runPassesOnModule(
    ir: IR.Module,
    moduleContext: ModuleContext,
    passGroup: PassGroup
  ): IR.Module = {
    if (!passes.contains(passGroup)) {
      throw new CompilerError("Cannot run an unvalidated pass group.")
    }

    val newContext =
      moduleContext.copy(passConfiguration = Some(passConfiguration))

    val passesWithIndex = passGroup.passes.zipWithIndex

    passesWithIndex.foldLeft(ir) {
      case (intermediateIR, (pass, index)) => {
        // TODO [AA, MK] This is a possible race condition.
        passConfiguration
          .get(pass)
          .foreach(c =>
            c.shouldWriteToContext = isLastRunOf(index, pass, passGroup)
          )

        pass.runModule(intermediateIR, newContext)
      }
    }
  }

  /** Executes all passes on the [[IR.Expression]].
    *
    * @param ir the expression to execute the compiler passes on
    * @param inlineContext the inline context in which the passes are executed
    * @return the result of executing `passGroup` on `ir`
    */
  def runPassesInline(
    ir: IR.Expression,
    inlineContext: InlineContext
  ): IR.Expression = {
    passes.foldLeft(ir)((ir, group) =>
      runPassesInline(ir, inlineContext, group)
    )
  }

  /** Executes the provided `passGroup` on the [[IR.Expression]].
    *
    * @param ir the expression to execute the compiler passes on
    * @param inlineContext the inline context in which the passes are executed
    * @param passGroup the group of passes being executed
    * @return the result of executing `passGroup` on `ir`
    */
  def runPassesInline(
    ir: IR.Expression,
    inlineContext: InlineContext,
    passGroup: PassGroup
  ): IR.Expression = {
    if (!passes.contains(passGroup)) {
      throw new CompilerError("Cannot run an unvalidated pass group.")
    }

    val newContext =
      inlineContext.copy(passConfiguration = Some(passConfiguration))

    val passesWithIndex = passGroup.passes.zipWithIndex

    passesWithIndex.foldLeft(ir) {
      case (intermediateIR, (pass, index)) => {
        // TODO [AA, MK] This is a possible race condition.
        passConfiguration
          .get(pass)
          .foreach(c =>
            c.shouldWriteToContext = isLastRunOf(index, pass, passGroup)
          )

        pass.runExpression(intermediateIR, newContext)
      }
    }
  }

  /** Determines whether the run at index `indexOfPassInGroup` is the last run
    * of that pass in the overall pass ordering.
    *
    * @param indexOfPassInGroup the index of `pass` in `passGroup`
    * @param pass the pass to check for
    * @param passGroup the pass group being run
    * @return `true` if the condition holds, otherwise `false`
    */
  def isLastRunOf(
    indexOfPassInGroup: Int,
    pass: IRPass,
    passGroup: PassGroup
  ): Boolean = {
    val ix          = allPasses.lastIndexOf(pass)
    val before      = passes.takeWhile(_ != passGroup)
    val totalLength = before.map(_.passes.length).sum

    ix - totalLength == indexOfPassInGroup
  }
}

/** A representation of a group of passes.
  *
  * @param passes the passes in the group
  */
class PassGroup(val passes: List[IRPass])
