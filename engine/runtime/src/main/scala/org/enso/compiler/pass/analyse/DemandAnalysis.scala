package org.enso.compiler.pass.analyse

import org.enso.compiler.InlineContext
import org.enso.compiler.core.IR
import org.enso.compiler.exception.CompilerError
import org.enso.compiler.pass.IRPass

/** This pass implements demand analysis for Enso.
  *
  * Demand analysis is the process of determining _when_ a suspended term needs
  * to be forced (where the suspended value is _demanded_).
  *
  * This pass needs to be run after [[AliasAnalysis]], and also assumes that
  * all members of [[IR.IRKind.Primitive]] have been removed from the IR by the
  * time that it runs.
  */
case object DemandAnalysis extends IRPass {
  override type Metadata = IR.Metadata.Empty

  /** Executes the demand analysis process on an Enso module.
    *
    * @param ir the Enso IR to process
    * @return `ir`, transformed to correctly force terms
    */
  override def runModule(ir: IR.Module): IR.Module = {
    ir.copy(bindings =
      ir.bindings.map(t => t.mapExpressions(runExpression(_, InlineContext())))
    )
  }

  /** Executes the demand analysis process on an Enso expression.
    *
    * @param expression the Enso IR to process
    * @param inlineContext a context object that contains the information needed
    *                      for inline evaluation
    * @return `ir`, transformed to correctly force terms
    */
  override def runExpression(
    expression: IR.Expression,
    inlineContext: InlineContext
  ): IR.Expression =
    analyseExpression(
      expression,
      isInsideApplication  = false,
      isInsideCallArgument = false
    )

  /** Performs demand analysis on an arbitrary program expression.
    *
    * @param expression the expression to perform demand analysis on
    * @param isInsideApplication whether the current expression occurs _inside_
    *                            an application (note that this should not be
    *                            set for the application itself)
    * @param isInsideCallArgument whether the current expression occurs _inside_
    *                             a call argument (note that this should not be
    *                             set for the call argument itself)
    * @return `expression`, transformed by the demand analysis process
    */
  def analyseExpression(
    expression: IR.Expression,
    isInsideApplication: Boolean,
    isInsideCallArgument: Boolean
  ): IR.Expression = {
    expression match {
      case fn: IR.Function => analyseFunction(fn, isInsideApplication)
      case name: IR.Name   => analyseName(name, isInsideCallArgument)
      case app: IR.Application =>
        analyseApplication(app, isInsideApplication, isInsideCallArgument)
      case typ: IR.Type =>
        analyseType(typ, isInsideApplication, isInsideCallArgument)
      case cse: IR.Case =>
        analyseCase(cse, isInsideApplication, isInsideCallArgument)
      case block @ IR.Expression.Block(expressions, retVal, _, _, _) =>
        block.copy(
          expressions = expressions.map(x =>
            analyseExpression(x, isInsideApplication, isInsideCallArgument)
          ),
          returnValue =
            analyseExpression(retVal, isInsideApplication, isInsideCallArgument)
        )
      case binding @ IR.Expression.Binding(_, expression, _, _) =>
        binding.copy(expression =
          analyseExpression(
            expression,
            isInsideApplication,
            isInsideCallArgument = false
          )
        )
      case lit: IR.Literal     => lit
      case err: IR.Error       => err
      case foreign: IR.Foreign => foreign
      case comment: IR.Comment =>
        comment.mapExpressions(x =>
          analyseExpression(
            x,
            isInsideApplication = false,
            isInsideCallArgument
          )
        )
    }
  }

  /** Performs demand analysis for a function.
    *
    * @param function the function to perform demand analysis on
    * @param isInsideApplication whether or not the function occurs inside an
    *                            application
    * @return `function`, transformed by the demand analysis process
    */
  def analyseFunction(
    function: IR.Function,
    isInsideApplication: Boolean
  ): IR.Function = function match {
    case lam @ IR.Function.Lambda(args, body, _, _, _) =>
      lam.copy(
        arguments = args.map(analyseDefinitionArgument),
        body = analyseExpression(
          body,
          isInsideApplication,
          isInsideCallArgument = false
        )
      )
  }

  /** Performs demand analysis for a name.
    *
    * If the name refers to a term that is suspended, this name is forced unless
    * it is being passed to a function. If the name is being passed to a function
    * it is passed raw.
    *
    * @param name the name to perform demand analysis on.
    * @param isInsideCallArgument whether or not the name occurs inside a call
    *                             call argument
    * @return `name`, transformed by the demand analysis process
    */
  def analyseName(
    name: IR.Name,
    isInsideCallArgument: Boolean
  ): IR.Expression = {
    val usesLazyTerm = isUsageOfSuspendedTerm(name)

    if (isInsideCallArgument) {
      name
    } else {
      if (usesLazyTerm) {
        val forceLocation   = name.location
        val newNameLocation = name.location.map(l => l.copy(id = None))

        val newName = name match {
          case lit: IR.Name.Literal => lit.copy(location  = newNameLocation)
          case ths: IR.Name.This    => ths.copy(location  = newNameLocation)
          case here: IR.Name.Here   => here.copy(location = newNameLocation)
        }

        IR.Application.Force(newName, forceLocation)
      } else {
        name
      }
    }
  }

  /** Performs demand analysis on an application.
    *
    * @param application the function application to perform demand analysis on
    * @param isInsideApplication whether or not the application is occuring
    *                            inside another application
    * @param isInsideCallArgument whether or not the application is occurring
    *                             inside a call argument
    * @return `application`, transformed by the demand analysis process
    */
  def analyseApplication(
    application: IR.Application,
    isInsideApplication: Boolean,
    isInsideCallArgument: Boolean
  ): IR.Application = application match {
    case pref @ IR.Application.Prefix(fn, args, _, _, _) =>
      pref.copy(
        function = analyseExpression(
          fn,
          isInsideApplication  = true,
          isInsideCallArgument = false
        ),
        arguments = args.map(analyseCallArgument)
      )
    case force @ IR.Application.Force(target, _, _) =>
      force.copy(target =
        analyseExpression(
          target,
          isInsideApplication,
          isInsideCallArgument
        )
      )
    case _ =>
      throw new CompilerError(
        "Unexpected application type during demand analysis."
      )
  }

  /** Determines whether a particular piece of IR represents the usage of a
    * suspended term (and hence requires forcing).
    *
    * @param expr the expression to check
    * @return `true` if `expr` represents the usage of a suspended term, `false`
    *         otherwise
    */
  def isUsageOfSuspendedTerm(expr: IR.Expression): Boolean = {
    expr match {
      case name: IR.Name =>
        val aliasInfo = name.unsafeGetMetadata[AliasAnalysis.Info.Occurrence](
          "Missing alias occurrence information for a name usage"
        )

        aliasInfo.graph
          .defLinkFor(aliasInfo.id)
          .flatMap(link => {
            aliasInfo.graph
              .getOccurrence(link.target)
              .getOrElse(
                throw new CompilerError(
                  s"Malformed aliasing link with target ${link.target}"
                )
              ) match {
              case AliasAnalysis.Graph.Occurrence.Def(_, _, _, isLazy) =>
                if (isLazy) Some(true) else None
              case _ => None
            }
          })
          .isDefined
      case _ => false
    }
  }

  /** Performs demand analysis on a function call argument.
    *
    * In keeping with the requirement by the runtime to pass all function
    * arguments as thunks, we mark the argument as needing suspension based on
    * whether it already is a thunk or not.
    *
    * @param arg the argument to perform demand analysis on
    * @return `arg`, transformed by the demand analysis process
    */
  def analyseCallArgument(arg: IR.CallArgument): IR.CallArgument = {
    arg match {
      case spec @ IR.CallArgument.Specified(_, expr, _, _, _) =>
        spec.copy(
          value = analyseExpression(
            expr,
            isInsideApplication  = true,
            isInsideCallArgument = true
          ),
          shouldBeSuspended = Some(!isUsageOfSuspendedTerm(expr))
        )
    }
  }

  /** Performs demand analysis on a function definition argument.
    *
    * @param arg the argument to perform demand analysis on
    * @return `arg`, transformed by the demand analysis process
    */
  def analyseDefinitionArgument(
    arg: IR.DefinitionArgument
  ): IR.DefinitionArgument = {
    arg match {
      case spec @ IR.DefinitionArgument.Specified(_, default, _, _, _) =>
        spec.copy(
          defaultValue = default.map(x =>
            analyseExpression(
              x,
              isInsideApplication  = false,
              isInsideCallArgument = false
            )
          )
        )
      case redef: IR.Error.Redefined.Argument => redef
    }
  }

  /** Performs demand analysis on a typing expression.
    *
    * @param typ the expression to perform demand analysis on
    * @param isInsideApplication whether the typing expression occurs inside a
    *                            function application
    * @param isInsideCallArgument whether the typing expression occurs inside a
    *                             function call argument
    * @return `typ`, transformed by the demand analysis process
    */
  def analyseType(
    typ: IR.Type,
    isInsideApplication: Boolean,
    isInsideCallArgument: Boolean
  ): IR.Type =
    typ.mapExpressions(x =>
      analyseExpression(x, isInsideApplication, isInsideCallArgument)
    )

  /** Performs demand analysis on a case expression.
    *
    * @param cse the case expression to perform demand analysis on
    * @param isInsideApplication whether the case expression occurs inside a
    *                            function application
    * @param isInsideCallArgument whether the case expression occurs inside a
    *                             function call argument
    * @return `cse`, transformed by the demand analysis process
    */
  def analyseCase(
    cse: IR.Case,
    isInsideApplication: Boolean,
    isInsideCallArgument: Boolean
  ): IR.Case = cse match {
    case expr @ IR.Case.Expr(scrutinee, branches, fallback, _, _) =>
      expr.copy(
        scrutinee = analyseExpression(
          scrutinee,
          isInsideApplication,
          isInsideCallArgument
        ),
        branches = branches.map(b => analyseCaseBranch(b)),
        fallback = fallback.map(x =>
          analyseExpression(
            x,
            isInsideApplication  = false,
            isInsideCallArgument = false
          )
        )
      )
    case _ => throw new CompilerError("Unexpected case construct.")
  }

  /** Performs demand analysis on a case branch.
    *
    * @param branch the case branch to perform demand analysis on
    * @return `branch`, transformed by the demand analysis process
    */
  def analyseCaseBranch(branch: IR.Case.Branch): IR.Case.Branch = {
    branch.copy(
      expression = analyseExpression(
        branch.expression,
        isInsideApplication  = false,
        isInsideCallArgument = false
      )
    )
  }
}
