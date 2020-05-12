package org.enso.compiler.codegen

import org.enso.data
import org.enso.data.List1
import org.enso.syntax.text.AST
import org.enso.syntax.text.AST.Ident.{Opr, Var}

/** This object contains view patterns that allow matching on the parser [[AST]]
  * for more sophisticated constructs.
  *
  * These view patterns are implemented as custom unapply methods that only
  * return [[Some]] when more complex conditions are met. These view patterns
  * return the [[AST]] representations of the relevant segments in order to
  * allow location information to easily be provided to the translation
  * mechanism.
  */
object AstView {

  object Block {

    /** Matches an arbitrary block in the program source.
      *
      * @param ast the structure to try and match on
      * @return a list of expressions in the block, and the final expression
      *         separately
      */
    def unapply(ast: AST): Option[(List[AST], AST)] = ast match {
      case AST.Block(_, _, firstLine, lines) =>
        val actualLines = firstLine.elem :: lines.flatMap(_.elem)
        if (actualLines.nonEmpty) {
          Some((actualLines.dropRight(1), actualLines.last))
        } else {
          None
        }
      case _ => None
    }
  }

  object SuspendedBlock {

    /** Matches an arbitrary suspended block in the program source.
      *
      * A suspended block is one that is bound to a name but takes no arguments.
      *
      * @param ast the structure to try and match on
      * @return the name to which the block is assigned, and the block itself
      */
    def unapply(ast: AST): Option[(AST.Ident, AST.Block)] = {
      ast match {
        case Assignment(name, AST.Block.any(block)) =>
          Some((name, block))
        case _ => None
      }
    }
  }

  object Binding {
    val bindingOpSym: Opr = AST.Ident.Opr("=")

    /** Matches an arbitrary binding in the program source.
      *
      * A binding is any expression of the form `<expr> = <expr>`, and this
      * matcher asserts no additional properties on the structure it matches.
      *
      * @param ast the structure to try and match on
      * @return the expression on the left of the binding operator, and the
      *         expression on the right side of the binding operator
      */
    def unapply(ast: AST): Option[(AST, AST)] = {
      ast match {
        case AST.App.Infix.any(ast) =>
          val left  = ast.larg
          val op    = ast.opr
          val right = ast.rarg

          if (op == bindingOpSym) {
            Some((left, right))
          } else {
            None
          }
        case _ => None
      }
    }
  }

  object Assignment {
    val assignmentOpSym: Opr = AST.Ident.Opr("=")

    /** Matches an assignment.
      *
      * An assignment is a [[Binding]] where the left-hand side is a variable
      * name.
      *
      * @param ast the structure to try and match on
      * @return the variable name assigned to, and the expression being assigned
      */
    def unapply(ast: AST): Option[(AST.Ident, AST)] = {
      ast match {
        case Binding(MaybeBlankName(left), right) => Some((left, right))
        case _                                    => None
      }
    }
  }

  object MaybeBlankName {
    val blankSym: String = "_"

    /** Matches an identifier that may be a blank `_`.
      *
      * @param ast the structure to try and match on
      * @return the identifier
      */
    def unapply(ast: AST): Option[AST.Ident] = {
      ast match {
        case AST.Ident.Var.any(variable) => Some(variable)
        case AST.Ident.Cons.any(cons)    => Some(cons)
        case AST.Ident.Blank.any(blank)  => Some(blank)
        case _                           => None
      }
    }
  }

  object Lambda {
    val lambdaOpSym: Opr = AST.Ident.Opr("->")

    /** Matches a lambda expression in the program source.
      *
      * A lambda expression is of the form `<args> -> <expression>` where
      * `<args>` is a space-separated list of valid argument definitions, and
      * `<expression>` is an arbitrary program expression.
      *
      * @param ast the structure to try and match on
      * @return a list of the arguments defined for the lambda, and the body of
      *         the lambda
      */
    def unapply(ast: AST): Option[(List[AST], AST)] = {
      ast match {
        case AST.App.Infix.any(ast) =>
          val left  = ast.larg
          val op    = ast.opr
          val right = ast.rarg

          if (op == lambdaOpSym) {
            left match {
              case LambdaParamList(args) => Some((args, right))
              case _                     => None
            }
          } else {
            None
          }
        case _ => None
      }
    }
  }

  object ContextAscription {

    /** Matches a usage of the `in` keyword for ascribing a monadic context to
      * an expression.
      *
      * @param ast the ast structure to match on
      * @return a pair containing the expression and the context
      */
    def unapply(ast: AST): Option[(AST, AST)] = {
      ast match {
        case MaybeParensed(
            AST.App.Prefix(expr, AST.App.Prefix(AST.Ident.Var("in"), context))
            ) =>
          Some((expr, context))
        case _ => None
      }
    }
  }

  object LazyArgument {

    /** Matches on a lazy argument definition or usage.
      *
      * A lazy argument is one of the form `~t` where `t` is a valid parameter
      * name. This is temporary syntax and will be removed once we have the
      * ability to insert these analyticallyl
      *
      * @param ast the structure to try and match on
      * @return the term being forced
      */
    def unapply(ast: AST): Option[AST] = ast match {
      case MaybeParensed(
          AST.App.Section.Right(AST.Ident.Opr("~"), FunctionParam(arg))
          ) =>
        Some(arg)
      case _ => None
    }
  }

  object FunctionParam {

    /** Matches a definition-site function parameter.
      *
      * @param ast the structure to try and match on
      * @return the parameter definition
      */
    def unapply(ast: AST): Option[AST] = ast match {
      case LazyAssignedArgumentDefinition(_, _) => Some(ast)
      case AssignedArgument(_, _)               => Some(ast)
      case DefinitionArgument(_)                => Some(ast)
      case PatternMatch(_, _)                   => Some(ast)
      case LazyArgument(_)                      => Some(ast)
      case _                                    => None
    }
  }

  object LambdaParamList {

    /** Matches on the parameter list of a lambda.
      *
      * @param ast the structure to try and match on
      * @return a list of the arguments for which the lambda is defined
      */
    def unapply(ast: AST): Option[List[AST]] = {
      ast match {
        case SpacedList(args) =>
          val realArgs = args.collect { case a @ FunctionParam(_) => a }

          if (realArgs.length == args.length) {
            Some(args)
          } else {
            None
          }
        case FunctionParam(p) => Some(List(p))
        case _                => None
      }
    }
  }

  object MaybeTyped {

    /** Matches on terms that _may_ have a type signature.
      *
      * Such terms take the form of `<term> : <type>`, where both `<term>` and
      * `<type>` can be arbitrary program expressions.
      *
      * @param ast the structure to try and match on
      * @return the term and the type ascribed to it
      */
    def unapply(ast: AST): Option[(AST, AST)] = ast match {
      case AST.App.Infix(entity, AST.Ident.Opr(":"), signature) =>
        Some((entity, signature))
      case _ => None
    }
  }

  object MaybeParensed {

    /** Matches on terms that _may_ be surrounded by parentheses.
      *
      * @param ast the structure to try and match on
      * @return the term contained in the parentheses
      */
    def unapply(ast: AST): Option[AST] = {
      ast match {
        case AST.Group(mExpr) => mExpr.flatMap(unapply)
        case a                => Some(a)
      }
    }
  }

  object AssignedArgument {

    /** Matches on the structure of an 'assigned argument'.
      *
      * Such an argument has the structure `<var> = <expression>` where `<var>`
      * must be a valid variable name, and `<expression>` is an arbitrary Enso
      * expression.
      *
      * @param ast the structure to try and match on
      * @return the variable name and the expression being bound to it
      */
    def unapply(ast: AST): Option[(AST.Ident, AST)] =
      MaybeParensed.unapply(ast).flatMap(Assignment.unapply)
  }

  object LazyAssignedArgumentDefinition {

    /** Matches on the definition of a lazy argument for a function that also
      * has a default value.
      *
      * @param ast the structure to try and match on
      * @return the name of the argument being declared and the expression of
      *         the default value being bound to it
      */
    def unapply(ast: AST): Option[(AST.Ident, AST)] = {
      ast match {
        case MaybeParensed(
            Binding(
              AST.App.Section.Right(AST.Ident.Opr("~"), AST.Ident.Var.any(v)),
              r
            )
            ) =>
          Some((v, r))
        case _ => None
      }
    }
  }

  object DefinitionArgument {

    /** Matches on a definition argument, which is a standard variable
      * identifier.
      *
      * @param ast the structure to try and match on
      * @return the name of the argument
      */
    def unapply(ast: AST): Option[AST.Ident] = ast match {
      case MaybeParensed(MaybeBlankName(ast)) => Some(ast)
      case _                                  => None
    }
  }

  object Application {

    /** Matches an arbitrary function application. This includes both method
      * calls and standard function applications as they are syntactically
      * unified.
      *
      * @param ast the structure to try and match on
      * @return the name of the function, and a list of its arguments (including
      *         the `self` argument if using method-call syntax)
      */
    def unapply(ast: AST): Option[(AST, List[AST])] =
      SpacedList.unapply(ast).flatMap {
        case fun :: args =>
          fun match {
            case MethodCall(target, function, methodArgs) =>
              Some((function, target :: methodArgs ++ args))
            case _ => Some((fun, args))
          }
        case _ => None
      }
  }

  object MethodCall {

    /** Matches on a method call.
      *
      * A method call has the form `<obj>.<fn-name> <args...>` where `<obj>` is
      * an arbitrary expression, `<fn-name>` is the name of the function being
      * called, and `<args>` are the arguments to the call.
      *
      * @param ast the structure to try and match on
      * @return the `self` expression, the function name, and the arguments to
      *         the function
      */
    def unapply(ast: AST): Option[(AST, AST.Ident, List[AST])] = ast match {
      case OperatorDot(target, Application(ConsOrVar(ident), args)) =>
        Some((target, ident, args))
      case AST.App.Section.Left(
          MethodCall(target, ident, List()),
          susp @ SuspendDefaultsOperator(_)
          ) =>
        Some((target, ident, List(susp)))
      case OperatorDot(target, ConsOrVar(ident)) =>
        Some((target, ident, List()))
      case _ => None
    }
  }

  object SuspendDefaultsOperator {

    /** Matches on a usage of the `...` 'suspend defaults' operator.
      *
      * @param ast the structure to try and match on
      * @return the 'suspend defaults' operator
      */
    def unapply(ast: AST): Option[AST] = {
      ast match {
        case AST.Ident.Opr("...") => Some(ast)
        case _                    => None
      }
    }
  }

  object SpacedList {

    /** Matches an arbitrary space-separated list in the AST, possibly including
      * a usage of the `...` operator.
      *
      * @param ast the structure to try and match on
      * @return the elements of the list
      */
    def unapply(ast: AST): Option[List[AST]] = {
      matchSpacedList(ast)
    }

    private[this] def matchSpacedList(ast: AST): Option[List[AST]] = {
      ast match {
        case MaybeParensed(AST.App.Prefix(fn, arg)) =>
          val fnRecurse = matchSpacedList(fn)

          fnRecurse match {
            case Some(headItems) => Some(headItems :+ arg)
            case None            => Some(List(fn, arg))
          }
        case MaybeParensed(
            AST.App.Section.Left(ast, SuspendDefaultsOperator(suspend))
            ) =>
          ast match {
            case ConsOrVar(_) => Some(List(ast, suspend))
            case _ =>
              val astRecurse = matchSpacedList(ast)

              astRecurse match {
                case Some(items) => Some(items :+ suspend)
                case None        => None
              }
          }
        case _ => None
      }
    }
  }

  object MethodDefinition {

    /** Matches on the definition of a method.
      *
      * These take the form of `<type>.<fn-name> = <expression>`,
      * or `<fn-name> = <expression>`, where `<type>` is the name of a type,
      * `<fn-name>` is the name of a function, and `<expression>` is an
      * arbitrary program expression.
      *
      * @param ast the structure to try and match on
      * @return the path segments of the type reference, the function name, and
      *         the bound expression
      */
    def unapply(ast: AST): Option[(List[AST], AST, AST)] = {
      ast match {
        case Binding(lhs, rhs) =>
          lhs match {
            case MethodReference(targetPath, name) =>
              Some((targetPath, name, rhs))
            case AST.Ident.Var.any(name) => Some((List(), name, rhs))
            case _ =>
              None
          }
        case _ =>
          None
      }
    }
  }

  object ConsOrVar {

    /** Matches any expression that is either the name of a constructor or a
      * variable.
      *
      * @param arg the structure to try and match on
      * @return the identifier matched on
      */
    def unapply(arg: AST): Option[AST.Ident] = arg match {
      case AST.Ident.Var.any(arg)  => Some(arg)
      case AST.Ident.Cons.any(arg) => Some(arg)
      case _                       => None
    }
  }

  object OperatorDot {

    /** Matches on an arbitrary usage of operator `.` with no restrictions on
      * the operands.
      *
      * @param ast the structure to try and match on
      * @return the left- and right-hand sides of the operator
      */
    def unapply(ast: AST): Option[(AST, AST)] = ast match {
      case AST.App.Infix(left, AST.Ident.Opr("."), right) => Some((left, right))
      case _ =>
        None
    }
  }

  object DotChain {

    /** Matches an arbitrary chain of [[OperatorDot]] expressions.
      *
      * @param ast the structure to try and match on
      * @return the segments making up the chain
      */
    def unapply(ast: AST): Option[List[AST]] = {
      val path = matchDotChain(ast)

      if (path.isEmpty) {
        None
      } else {
        Some(path)
      }
    }

    private[this] def matchDotChain(ast: AST): List[AST] = {
      ast match {
        case OperatorDot(left, right) => matchDotChain(left) :+ right
        case AST.Ident.any(ast)       => List(ast)
        case _                        => List()
      }
    }
  }

  object MethodReference {

    /** Matches on a method reference.
      *
      * A method reference is a [[DotChain]] where all but the last element are
      * the names of constructors.
      *
      * @param ast the structure to try and match on
      * @return the constructor segments and the final segment
      */
    def unapply(ast: AST): Option[(List[AST], AST)] = {
      ast match {
        case DotChain(segments) =>
          if (segments.length >= 2) {
            val consPath = segments.dropRight(1)
            val maybeVar = segments.last

            val isValid = consPath.collect {
                case a @ AST.Ident.Cons(_) => a
              }.length == consPath.length

            if (isValid) {
              maybeVar match {
                case AST.Ident.Var(_) => Some((consPath, maybeVar))
                case _                => None
              }
            } else {
              None
            }
          } else {
            None
          }
        case _ => None
      }
    }
  }

  object CaseExpression {
    val caseName: List1[Var] =
      data.List1(AST.Ident.Var("case"), AST.Ident.Var("of"))

    /** Matches on a case expression.
      *
      * A case expression is of the following form:
      *
      * {{{
      *   case <scrutinee> of
      *     <matcher> -> <expression>
      *     <...>
      * }}}
      *
      * where:
      * - `<scrutinee>` is an arbitrary non-block program expression
      * - `<matcher>` is a [[PatternMatch]]
      * - `<expression>` is an arbirary program expression
      *
      * @param ast the structure to try and match on
      * @return the scrutinee and a list of the case branches
      */
    def unapply(ast: AST): Option[(AST, List[AST])] = {
      ast match {
        case AST.Mixfix(identSegments, argSegments) =>
          if (identSegments == caseName) {
            if (argSegments.length == 2) {
              val scrutinee    = argSegments.head
              val caseBranches = argSegments.last

              caseBranches match {
                case AST.Block(_, _, firstLine, restLines) =>
                  val blockLines = firstLine.elem :: restLines.flatMap(_.elem)

                  val matchBranches = blockLines.collect {
                    case b @ CaseBranch(_, _, _) => b
                  }

                  if (matchBranches.length == blockLines.length) {
                    Some((scrutinee, matchBranches))
                  } else {
                    None
                  }
                case _ => None
              }
            } else {
              None
            }
          } else {
            None
          }
        case _ => None
      }
    }
  }

  object ConsCaseBranch {

    /** Matches a case branch that performas a pattern match on a consctructor.
      *
      * A constructor case branch is of the form `<cons> <args..> -> <expr>`
      * where `<cons>` is the name of a constructor, `<args..>` is the list of
      * arguments to that constructor, and `<expr>` is the expression to execute
      * on a successful match.
      *
      * @param ast the structure to try and match on
      * @return the constructor name, the constructor arguments, and the
      *         expression to be executed
      */
    def unapply(ast: AST): Option[(AST, List[AST], AST)] = {
      CaseBranch.unapply(ast).flatMap {
        case (cons, args, ast) => cons.map((_, args, ast))
      }
    }
  }

  object FallbackCaseBranch {

    /** Matches on a fallback case branch.
      *
      * A fallback case branch is of the form `_ -> <expression>`, where
      * `<expression>` is an arbitrary Enso expression.
      *
      * @param ast the structure to try and match on
      * @return the expression of the fallback branch
      */
    def unapply(ast: AST): Option[AST] = {
      CaseBranch.unapply(ast).flatMap {
        case (cons, args, ast) =>
          if (cons.isEmpty && args.isEmpty) Some(ast) else None
      }
    }
  }

  object CaseBranch {

    /** Matches on an arbitrary pattern match case branch.
      *
      * A case branch has the form `<matcher> -> <expression>`, where
      * `<matcher>` is an expression that can match on the scrutinee, and
      * `<expression>` is an arbitrary expression to execute on a successful
      * match.
      *
      * @param ast the structure to try and match on
      * @return the matcher expression, its arguments (if they exist), and the
      *         body of the case branch
      */
    def unapply(ast: AST): Option[(Option[AST], List[AST], AST)] = {
      ast match {
        case AST.App.Infix(left, AST.Ident.Opr("->"), right) =>
          left match {
            case PatternMatch(cons, args) => Some((Some(cons), args, right))
            case MaybeParensed(AST.Ident.Blank.any(_)) =>
              Some((None, List(), right))
            case DefinitionArgument(v) => Some((None, List(v), right))
            case _                     => None
          }
        case _ => None
      }
    }
  }

  object PatternMatch {
    // Cons, args
    /** Matches an arbitrary pattern match on a constructor.
      *
      * A pattern match is of the form `<cons> <args..>` where `<cons>` is the
      * name of a constructor, and `<args>` are pattern match parameters.
      *
      * @param ast the structure to try and match on
      * @return the name of the constructor, and a list containing its arguments
      */
    def unapply(ast: AST): Option[(AST.Ident.Cons, List[AST])] = {
      ast match {
        case MaybeParensed(SpacedList(AST.Ident.Cons.any(cons) :: xs)) =>
          val realArgs: List[AST] = xs.collect { case a @ MatchParam(_) => a }

          if (realArgs.length == xs.length) {
            Some((cons, xs))
          } else {
            None
          }
        case AST.Ident.Cons.any(cons) => Some((cons, List()))
        case _                        => None
      }
    }
  }

  object MatchParam {

    /** Matches a valid parameter to a pattern match.
      *
      * @param ast the structure to try and match on
      * @return the argument
      */
    def unapply(ast: AST): Option[AST] = ast match {
      case DefinitionArgument(_)  => Some(ast)
      case PatternMatch(_, _)     => Some(ast)
      case AST.Ident.Blank.any(b) => Some(b)
      case _                      => None
    }
  }
}
