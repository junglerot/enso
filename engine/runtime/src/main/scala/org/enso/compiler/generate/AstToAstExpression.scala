package org.enso.compiler.generate

import cats.Foldable
import cats.implicits._
import org.enso.compiler.core.IR._
import org.enso.compiler.exception.UnhandledEntity
import org.enso.interpreter.Constants
import org.enso.syntax.text.{AST, Location}

// FIXME [AA] All places where we currently throw a `RuntimeException` should
//  generate informative and useful nodes in core.

/**
  * This file contains the functionality that translates from the parser's
  * [[AST]] type to the internal representation used by the compiler.
  *
  * This representation is currently [[Expression]], but this will change as
  * [[Core]] becomes implemented. Most function docs will refer to [[Core]]
  * now, as this will become true soon.
  */
object AstToAstExpression {

  /** Translates a program represented in the parser [[AST]] to the compiler's
    * [[Core]] internal representation.
    *
    * @param inputAST the [[AST]] representing the program to translate
    * @return the [[Core]] representation of `inputAST`
    */
  def translate(inputAST: AST): Module = {
    inputAST match {
      case AST.Module.any(inputAST) => translateModule(inputAST)
      case _ => {
        throw new UnhandledEntity(inputAST, "translate")
      }
    }
  }

  /** Translates an inline program expression represented in the parser [[AST]]
    * into the compiler's [[Core]] representation.
    *
    * Inline expressions must _only_ be expressions, and may not contain any
    * type of definition.
    *
    * @param inputAST the [[AST]] representing the expression to translate.
    * @return the [[Core]] representation of `inputAST` if it is valid,
    *         otherwise [[None]]
    */
  def translateInline(inputAST: AST): Option[Expression] = {
    inputAST match {
      case AST.Module.any(module) =>
        val presentBlocks = module.lines.collect {
          case t if t.elem.isDefined => t.elem.get
        }

        val expressions = presentBlocks.map(translateExpression)

        expressions match {
          case List()     => None
          case List(expr) => Some(expr)
          case _ =>
            Some(
              Block(
                Foldable[List].foldMap(expressions)(_.location),
                expressions.dropRight(1),
                expressions.last
              )
            )
        }
      case _ => None
    }
  }

  /** Translate a top-level Enso module into [[Core]].
    *
    * @param module the [[AST]] representation of the module to translate
    * @return the [[Core]] representation of `module`
    */
  def translateModule(module: AST.Module): Module = {
    module match {
      case AST.Module(blocks) => {
        val presentBlocks = blocks.collect {
          case t if t.elem.isDefined => t.elem.get
        }

        val imports = presentBlocks.collect {
          case AST.Import.any(list) => translateImport(list)
        }

        val nonImportBlocks = presentBlocks.filter {
          case AST.Import.any(_) => false
          case _                 => true
        }

        val statements = nonImportBlocks.map(translateModuleSymbol)
        Module(imports, statements)
      }
    }
  }

  /** Translates a module-level definition from its [[AST]] representation into
    * [[Core]].
    *
    * @param inputAST the definition to be translated
    * @return the [[Core]] representation of `inputAST`
    */
  def translateModuleSymbol(inputAST: AST): TopLevelSymbol = {
    inputAST match {
      case AST.Def(consName, args, body) =>
        if (body.isDefined) {
          throw new RuntimeException("Cannot support complex type defs yet!!!!")
        } else {
          AtomDef(consName.name, args.map(translateArgumentDefinition(_)))
        }
      case AstView.MethodDefinition(targetPath, name, definition) =>
        val path = if (targetPath.nonEmpty) {
          targetPath.collect { case AST.Ident.Cons(name) => name }.mkString(".")
        } else {
          Constants.Names.CURRENT_MODULE
        }
        val nameStr       = name match { case AST.Ident.Var(name) => name }
        val defExpression = translateExpression(definition)
        val defExpr: Lambda = defExpression match {
          case fun: Lambda => fun
          case expr        => Lambda(expr.location, List(), expr)
        }
        MethodDef(path, nameStr, defExpr)
      case _ =>
        throw new UnhandledEntity(inputAST, "translateModuleSymbol")
    }
  }

  /** Translates an arbitrary program expression from [[AST]] into [[Core]].
    *
    * @param inputAST the expresion to be translated
    * @return the [[Core]] representation of `inputAST`
    */
  def translateExpression(inputAST: AST): Expression = {
    inputAST match {
      case AstView
            .SuspendedBlock(name, block @ AstView.Block(lines, lastLine)) =>
        Binding(
          inputAST.location,
          name.name,
          Block(
            block.location,
            lines.map(translateExpression),
            translateExpression(lastLine),
            suspended = true
          )
        )
      case AstView.Assignment(name, expr) =>
        translateBinding(inputAST.location, name, expr)
      case AstView.MethodCall(target, name, args) =>
        Prefix(
          inputAST.location,
          translateExpression(name),
          (target :: args).map(translateCallArgument),
          false
        )
      case AstView.CaseExpression(scrutinee, branches) =>
        val actualScrutinee = translateExpression(scrutinee)
        val nonFallbackBranches =
          branches
            .takeWhile(AstView.FallbackCaseBranch.unapply(_).isEmpty)
            .map(translateCaseBranch)
        val potentialFallback =
          branches
            .drop(nonFallbackBranches.length)
            .headOption
            .map(translateFallbackBranch)
        CaseExpr(
          inputAST.location,
          actualScrutinee,
          nonFallbackBranches,
          potentialFallback
        )
      case AST.App.any(inputAST)     => translateApplicationLike(inputAST)
      case AST.Mixfix.any(inputAST)  => translateApplicationLike(inputAST)
      case AST.Literal.any(inputAST) => translateLiteral(inputAST)
      case AST.Group.any(inputAST) =>
        translateGroup(inputAST, translateExpression)
      case AST.Ident.any(inputAST) => translateIdent(inputAST)
      case AstView.Block(lines, retLine) =>
        Block(
          inputAST.location,
          lines.map(translateExpression),
          translateExpression(retLine)
        )
      case AST.Comment.any(inputAST) => translateComment(inputAST)
      case AST.Invalid.any(inputAST) => translateInvalid(inputAST)
      case AST.Foreign(_, _, _) =>
        throw new RuntimeException(
          "Enso does not yet support foreign language blocks"
        )
      case _ =>
        throw new UnhandledEntity(inputAST, "translateExpression")
    }
  }

  /** Translates a program literal from its [[AST]] representation into
    * [[Core]].
    *
    * @param literal the literal to translate
    * @return the [[Core]] representation of `literal`
    */
  def translateLiteral(literal: AST.Literal): Expression = {
    literal match {
      case AST.Literal.Number(base, number) => {
        if (base.isDefined && base.get != "10") {
          throw new RuntimeException("Only base 10 is currently supported")
        }

        NumberLiteral(literal.location, number)
      }
      case AST.Literal.Text.any(literal) =>
        literal.shape match {
          case AST.Literal.Text.Line.Raw(segments) =>
            val fullString = segments.collect {
              case AST.Literal.Text.Segment.Plain(str)   => str
              case AST.Literal.Text.Segment.RawEsc(code) => code.repr
            }.mkString

            TextLiteral(literal.location, fullString)
          case AST.Literal.Text.Block.Raw(lines, _, _) =>
            val fullString = lines
              .map(
                t =>
                  t.text.collect {
                    case AST.Literal.Text.Segment.Plain(str)   => str
                    case AST.Literal.Text.Segment.RawEsc(code) => code.repr
                  }.mkString
              )
              .mkString("\n")

            TextLiteral(literal.location, fullString)
          case AST.Literal.Text.Block.Fmt(_, _, _) =>
            throw new RuntimeException("Format strings not yet supported")
          case AST.Literal.Text.Line.Fmt(_) =>
            throw new RuntimeException("Format strings not yet supported")
          case _ =>
            throw new UnhandledEntity(literal.shape, "translateLiteral")
        }
      case _ => throw new UnhandledEntity(literal, "processLiteral")
    }
  }

  /** Translates an argument definition from [[AST]] into [[Core]].
    *
    * @param arg the argument to translate
    * @param isSuspended `true` if the argument is suspended, otherwise `false`
    * @return the [[Core]] representation of `arg`
    */
  @scala.annotation.tailrec
  def translateArgumentDefinition(
    arg: AST,
    isSuspended: Boolean = false
  ): DefinitionSiteArgument = {
    arg match {
      case AstView.LazyAssignedArgumentDefinition(name, value) =>
        DefinitionSiteArgument(
          name.name,
          Some(translateExpression(value)),
          true
        )
      case AstView.LazyArgument(arg) =>
        translateArgumentDefinition(arg, isSuspended = true)
      case AstView.DefinitionArgument(arg) =>
        DefinitionSiteArgument(arg.name, None, isSuspended)
      case AstView.AssignedArgument(name, value) =>
        DefinitionSiteArgument(
          name.name,
          Some(translateExpression(value)),
          isSuspended
        )
      case _ =>
        throw new UnhandledEntity(arg, "translateArgumentDefinition")
    }
  }

  /** Translates a call-site function argument from its [[AST]] representation
    * into [[Core]].
    *
    * @param arg the argument to translate
    * @return the [[Core]] representation of `arg`
    */
  def translateCallArgument(arg: AST): CallArgumentDefinition = arg match {
    case AstView.AssignedArgument(left, right) =>
      CallArgumentDefinition(Some(left.name), translateExpression(right))
    case _ => CallArgumentDefinition(None, translateExpression(arg))
  }

  /** Translates an arbitrary expression that takes the form of a syntactic
    * application from its [[AST]] representation into [[Core]].
    *
    * @param callable the callable to translate
    * @return the [[Core]] representation of `callable`
    */
  def translateApplicationLike(callable: AST): Expression = {
    callable match {
      case AstView.ForcedTerm(term) =>
        ForcedTerm(callable.location, translateExpression(term))
      case AstView.Application(name, args) =>
        val validArguments = args.filter {
          case AstView.SuspendDefaultsOperator(_) => false
          case _                                  => true
        }

        val suspendPositions = args.zipWithIndex.collect {
          case (AstView.SuspendDefaultsOperator(_), ix) => ix
        }

        val hasDefaultsSuspended = suspendPositions.contains(args.length - 1)

        Prefix(
          callable.location,
          translateExpression(name),
          validArguments.map(translateCallArgument),
          hasDefaultsSuspended
        )
      case AstView.Lambda(args, body) =>
        val realArgs = args.map(translateArgumentDefinition(_))
        val realBody = translateExpression(body)
        Lambda(callable.location, realArgs, realBody)
      case AST.App.Infix(left, fn, right) =>
        // TODO [AA] We should accept all ops when translating to core
        val validInfixOps = List("+", "/", "-", "*", "%")

        if (validInfixOps.contains(fn.name)) {
          BinaryOperator(callable.location, translateExpression(left), fn.name, translateExpression(right))
        } else {
          throw new RuntimeException(
            s"${fn.name} is not currently a valid infix operator"
          )
        }
      case AST.App.Prefix(_, _) =>
        throw new RuntimeException(
          "Enso does not support arbitrary prefix expressions"
        )
      case AST.App.Section.any(_) =>
        throw new RuntimeException(
          "Enso does not yet support operator sections"
        )
      case AST.Mixfix(nameSegments, args) =>
        val realNameSegments = nameSegments.collect {
          case AST.Ident.Var.any(v) => v
        }

        if (realNameSegments.length != nameSegments.length) {
          throw new RuntimeException("Badly named mixfix function.")
        }

        val functionName =
          AST.Ident.Var(realNameSegments.map(_.name).mkString("_"))

        Prefix(
          callable.location,
          translateExpression(functionName),
          args.map(translateCallArgument).toList,
          false
        )
      case _ => throw new UnhandledEntity(callable, "translateCallable")
    }
  }

  /** Translates an arbitrary program identifier from its [[AST]] representation
    * into [[Core]].
    *
    * @param identifier the identifier to translate
    * @return the [[Core]] representation of `identifier`
    */
  def translateIdent(identifier: AST.Ident): Expression = {
    identifier match {
      case AST.Ident.Var(name)  => LiteralName(identifier.location, name)
      case AST.Ident.Cons(name) => LiteralName(identifier.location, name)
      case AST.Ident.Blank(_) =>
        throw new RuntimeException("Blanks not yet properly supported")
      case AST.Ident.Opr.any(_) =>
        throw new RuntimeException("Operators not generically supported yet")
      case AST.Ident.Mod(_) =>
        throw new RuntimeException(
          "Enso does not support arbitrary module identifiers yet"
        )
      case _ =>
        throw new UnhandledEntity(identifier, "translateIdent")
    }
  }

  /** Translates an arbitrary binding operation from its [[AST]] representation
    * into [[Core]].
    *
    * @param location the source location of the binding
    * @param name the name of the binding being assigned to
    * @param expr the expression being assigned to `name`
    * @return the [[Core]] representation of `expr` being bound to `name`
    */
  def translateBinding(
    location: Option[Location],
    name: AST,
    expr: AST
  ): Binding = {
    name match {
      case AST.Ident.Var(name) =>
        Binding(location, name, translateExpression(expr))
      case _ =>
        throw new UnhandledEntity(name, "translateAssignment")
    }
  }

  /** Translates the branch of a case expression from its [[AST]] representation
    * into [[Core]].
    *
    * @param branch the case branch to translate
    * @return the [[Core]] representation of `branch`
    */
  def translateCaseBranch(branch: AST): CaseBranch = {
    branch match {
      case AstView.ConsCaseBranch(cons, args, body) =>
        CaseBranch(
          branch.location,
          translateExpression(cons),
          CaseFunction(
            body.location,
            args.map(translateArgumentDefinition(_)),
            translateExpression(body)
          )
        )

      case _ => throw new UnhandledEntity(branch, "translateCaseBranch")
    }
  }

  /** Translates the fallback branch of a case expression from its [[AST]]
    * representation into [[Core]].
    *
    * @param branch the fallback branch to translate
    * @return the [[Core]] representation of `branch`
    */
  def translateFallbackBranch(branch: AST): CaseFunction = {
    branch match {
      case AstView.FallbackCaseBranch(body) =>
        CaseFunction(body.location, List(), translateExpression(body))
      case _ => throw new UnhandledEntity(branch, "translateFallbackBranch")
    }
  }

  /** Translates an arbitrary grouped piece of syntax from its [[AST]]
    * representation into [[Core]].
    *
    * It is currently an error to have an empty group.
    *
    * @param group the group to translate
    * @param translator the function to apply to the group's contents
    * @tparam T the result type of translating the expression contained in
    *           `group`
    * @return the [[Core]] representation of the contents of `group`
    */
  def translateGroup[T](group: AST.Group, translator: AST => T): T = {
    group.body match {
      case Some(ast) => translator(ast)
      case None => {
        throw new RuntimeException("Empty group")
      }
    }
  }

  /** Translates an import statement from its [[AST]] representation into
    * [[Core]].
    *
    * @param imp the import to translate
    * @return the [[Core]] representation of `imp`
    */
  def translateImport(imp: AST.Import): AstImport = {
    AstImport(imp.path.map(t => t.name).reduceLeft((l, r) => l + "." + r))
  }

  /** Translates an arbitrary invalid expression from the [[AST]] representation
    * of the program into its [[Core]] representation.
    *
    * @param invalid the invalid entity to translate
    * @return the [[Core]] representation of `invalid`
    */
  def translateInvalid(invalid: AST.Invalid): Expression = {
    invalid match {
      case AST.Invalid.Unexpected(_, _) =>
        throw new RuntimeException(
          "Enso does not yet support unexpected blocks properly"
        )
      case AST.Invalid.Unrecognized(_) =>
        throw new RuntimeException(
          "Enso does not yet support unrecognised tokens properly"
        )
      case AST.Ident.InvalidSuffix(_, _) =>
        throw new RuntimeException(
          "Enso does not yet support invalid suffixes properly"
        )
      case AST.Literal.Text.Unclosed(_) =>
        throw new RuntimeException(
          "Enso does not yet support unclosed text literals properly"
        )
      case _ =>
        throw new RuntimeException(
          "Fatal: Unhandled entity in processInvalid = " + invalid
        )
    }
  }

  /** Translates a comment from its [[AST]] representation into its [[Core]]
    * representation.
    *
    * Currently this only supports documentation comments, and not standarc
    * types of comments as they can't currently be represented.
    *
    * @param comment the comment to transform
    * @return the [[Core]] representation of `comment`
    */
  def translateComment(comment: AST): Expression = {
    comment match {
      case AST.Comment(_) =>
        throw new RuntimeException(
          "Enso does not yet support comments properly"
        )
      case AST.Documented(_, _, ast) => translateExpression(ast)
      case _ =>
        throw new UnhandledEntity(comment, "processComment")
    }
  }
}
