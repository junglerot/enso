package org.enso.compiler.pass.analyse

import org.enso.compiler.context.{InlineContext, ModuleContext}
import org.enso.compiler.core.Implicits.AsMetadata
import org.enso.compiler.core.{CompilerError, ExternalID, IR, Identifier}
import org.enso.compiler.core.ir.module.scope.Definition
import org.enso.compiler.core.ir.module.scope.definition
import org.enso.compiler.core.ir.expression.{
  errors,
  Application,
  Case,
  Comment,
  Error,
  Foreign,
  Operator
}
import org.enso.compiler.core.ir.{
  `type`,
  CallArgument,
  DefinitionArgument,
  Empty,
  Expression,
  Function,
  Literal,
  Module,
  Name,
  Pattern,
  Type
}
import org.enso.compiler.core.ir.MetadataStorage._
import org.enso.compiler.pass.IRPass
import org.enso.compiler.pass.analyse.DataflowAnalysis.DependencyInfo.Type.asStatic

import java.util.UUID
import scala.collection.immutable.ListSet
import scala.collection.mutable

/** This pass implements dataflow analysis for Enso.
  *
  * Dataflow analysis is the processes of determining the dependencies between
  * program expressions.
  *
  * This pass requires the context to provide:
  *
  * - A [[LocalScope]], where relevant.
  *
  * It requires that all members of [[org.enso.compiler.core.ir.IRKind.Primitive]] have been removed
  * from the IR by the time it runs.
  */
//noinspection DuplicatedCode
case object DataflowAnalysis extends IRPass {
  override type Metadata = DependencyInfo
  override type Config   = IRPass.Configuration.Default

  override lazy val precursorPasses: Seq[IRPass] = List(
    AliasAnalysis,
    DemandAnalysis,
    TailCall
  )

  override lazy val invalidatedPasses: Seq[IRPass] = List()

  /** Executes the dataflow analysis process on an Enso module.
    *
    * @param ir the Enso IR to process
    * @param moduleContext a context object that contains the information needed
    *                      to process a module
    * @return `ir`, possibly having made transformations or annotations to that
    *         IR.
    */
  override def runModule(
    ir: Module,
    moduleContext: ModuleContext
  ): Module = {
    val dependencyInfo = new DependencyInfo
    ir.copy(
      bindings = ir.bindings.map(analyseModuleDefinition(_, dependencyInfo))
    ).updateMetadata(new MetadataPair(this, dependencyInfo))
  }

  /** Performs dataflow analysis on an inline expression.
    *
    * @param ir the Enso IR to process
    * @param inlineContext a context object that contains the information needed
    *                      for inline evaluation
    * @return `ir`
    */
  override def runExpression(
    ir: Expression,
    inlineContext: InlineContext
  ): Expression = {
    val localScope = inlineContext.localScope.getOrElse(
      throw new CompilerError(
        "A valid local scope is required for the inline flow."
      )
    )
    analyseExpression(ir, localScope.dataflowInfo)
  }

  /** @inheritdoc */
  override def updateMetadataInDuplicate[T <: IR](
    sourceIr: T,
    copyOfIr: T
  ): T = {
    (sourceIr, copyOfIr) match {
      case (sourceIr: Module, copyOfIr: Module) =>
        val sourceMeta =
          sourceIr.unsafeGetMetadata(this, "Dataflow Analysis must have run.")
        val copyMeta = DependencyInfo(
          dependents   = sourceMeta.dependents.deepCopy,
          dependencies = sourceMeta.dependencies.deepCopy
        )

        val sourceNodes = sourceIr.preorder
        val copyNodes   = copyOfIr.preorder

        sourceNodes.lazyZip(copyNodes).foreach { case (src, copy) =>
          src
            .getMetadata(this)
            .foreach(_ => copy.updateMetadata(new MetadataPair(this, copyMeta)))
        }

        copyOfIr.asInstanceOf[T]
      case _ => copyOfIr
    }
  }

  // === Pass Internals =======================================================

  /** Performs dataflow analysis on a module definition.
    *
    * Atoms are dependent on the definitions of their arguments, while methods
    * are dependent on the definitions of their bodies.
    *
    * @param binding the binding to perform dataflow analysis on
    * @param info the dependency information for the module
    * @return `binding`, with attached dependency information
    */
  // TODO [AA] Can I abstract the pattern here?
  def analyseModuleDefinition(
    binding: Definition,
    info: DependencyInfo
  ): Definition = {
    binding match {
      case m: definition.Method.Conversion =>
        val bodyDep       = asStatic(m.body)
        val methodDep     = asStatic(m)
        val sourceTypeDep = asStatic(m.sourceTypeName)
        info.dependents.updateAt(sourceTypeDep, Set(methodDep))
        info.dependents.updateAt(bodyDep, Set(methodDep))
        info.dependencies.updateAt(methodDep, Set(bodyDep, sourceTypeDep))

        m.copy(
          body = analyseExpression(m.body, info),
          sourceTypeName =
            m.sourceTypeName.updateMetadata(new MetadataPair(this, info))
        ).updateMetadata(new MetadataPair(this, info))
      case method @ definition.Method
            .Explicit(_, body, _, _, _) =>
        val bodyDep   = asStatic(body)
        val methodDep = asStatic(method)
        info.dependents.updateAt(bodyDep, Set(methodDep))
        info.dependencies.update(methodDep, Set(bodyDep))

        method
          .copy(body = analyseExpression(body, info))
          .updateMetadata(new MetadataPair(this, info))
      case tp @ Definition.Type(_, params, members, _, _, _) =>
        val tpDep = asStatic(tp)
        val newParams = params.map { param =>
          val paramDep = asStatic(param)
          info.dependents.updateAt(paramDep, Set(tpDep))
          info.dependencies.updateAt(tpDep, Set(paramDep))
          analyseDefinitionArgument(param, info)
        }
        val newMembers = members.map { data =>
          val dataDep = asStatic(data)
          info.dependents.updateAt(dataDep, Set(tpDep))
          info.dependencies.updateAt(tpDep, Set(dataDep))
          data.arguments.foreach(arg => {
            val argDep = asStatic(arg)
            info.dependents.updateAt(argDep, Set(dataDep))
            info.dependencies.updateAt(dataDep, Set(argDep))
          })

          data
            .copy(
              arguments = data.arguments.map(analyseDefinitionArgument(_, info))
            )
            .updateMetadata(new MetadataPair(this, info))
        }
        tp.copy(params = newParams, members = newMembers)
          .updateMetadata(new MetadataPair(this, info))
      case _: definition.Method.Binding =>
        throw new CompilerError(
          "Sugared method definitions should not occur during dataflow " +
          "analysis."
        )
      case _: Definition.SugaredType =>
        throw new CompilerError(
          "Complex type definitions should not be present during " +
          "dataflow analysis."
        )
      case _: Comment.Documentation =>
        throw new CompilerError(
          "Documentation should not exist as an entity during dataflow analysis."
        )
      case _: Type.Ascription =>
        throw new CompilerError(
          "Type signatures should not exist at the top level during " +
          "dataflow analysis."
        )
      case _: Name.BuiltinAnnotation =>
        throw new CompilerError(
          "Annotations should already be associated by the point of " +
          "dataflow analysis."
        )
      case ann: Name.GenericAnnotation =>
        ann
          .copy(expression = analyseExpression(ann.expression, info))
          .updateMetadata(new MetadataPair(this, info))
      case err: Error => err
    }
  }

  /** Performs dependency analysis on an arbitrary expression.
    *
    * The value of a block depends on its return value, while the value of a
    * binding depends on the expression being bound and the name being bound to.
    *
    * @param expression the expression to perform dataflow analysis on
    * @param info the dependency information for the module
    * @return `expression`, with attached dependency information
    */
  def analyseExpression(
    expression: Expression,
    info: DependencyInfo
  ): Expression = {
    expression match {
      case empty: Empty       => empty.updateMetadata(new MetadataPair(this, info))
      case function: Function => analyseFunction(function, info)
      case app: Application   => analyseApplication(app, info)
      case typ: Type          => analyseType(typ, info)
      case name: Name         => analyseName(name, info)
      case cse: Case          => analyseCase(cse, info)
      case literal: Literal =>
        literal.updateMetadata(new MetadataPair(this, info))
      case foreign: Foreign =>
        foreign.updateMetadata(new MetadataPair(this, info))

      case block @ Expression.Block(expressions, returnValue, _, _, _, _) =>
        val retValDep = asStatic(returnValue)
        val blockDep  = asStatic(block)
        info.dependents.updateAt(retValDep, Set(blockDep))
        info.dependencies.updateAt(blockDep, Set(retValDep))

        block
          .copy(
            expressions = expressions.map(analyseExpression(_, info)),
            returnValue = analyseExpression(returnValue, info)
          )
          .updateMetadata(new MetadataPair(this, info))
      case binding @ Expression.Binding(name, expression, _, _, _) =>
        val expressionDep = asStatic(expression)
        val nameDep       = asStatic(name)
        val bindingDep    = asStatic(binding)
        info.dependents.updateAt(expressionDep, Set(bindingDep))
        info.dependents.updateAt(nameDep, Set(bindingDep))
        info.dependencies.updateAt(bindingDep, Set(expressionDep, nameDep))

        binding
          .copy(
            name       = name.updateMetadata(new MetadataPair(this, info)),
            expression = analyseExpression(expression, info)
          )
          .updateMetadata(new MetadataPair(this, info))

      case error: Error => error
      case _: Comment =>
        throw new CompilerError(
          "Comments should not be present during dataflow analysis."
        )
    }
  }

  /** Performs dataflow analysis on a function.
    *
    * The result of a function is dependent on the result from its body, as well
    * as the definitions of any defaults for its arguments.
    *
    * @param function the function to perform dataflow analysis on
    * @param info the dependency information for the module
    * @return `function`, with attached dependency information
    */
  def analyseFunction(
    function: Function,
    info: DependencyInfo
  ): Function = {
    function match {
      case lam @ Function.Lambda(arguments, body, _, _, _, _) =>
        val bodyDep = asStatic(body)
        val lamDep  = asStatic(lam)
        info.dependents.updateAt(bodyDep, Set(lamDep))
        info.dependencies.updateAt(lamDep, Set(bodyDep))

        lam
          .copy(
            arguments = arguments.map(analyseDefinitionArgument(_, info)),
            body      = analyseExpression(body, info)
          )
          .updateMetadata(new MetadataPair(this, info))
      case _: Function.Binding =>
        throw new CompilerError(
          "Function sugar should not be present during dataflow analysis."
        )
    }
  }

  /** Performs dependency analysis on an application.
    *
    * A prefix application depends on the values of the function and arguments,
    * while a force depends purely on the term being forced.
    *
    * @param application the application to perform dependency analysis on
    * @param info the dependency information for the module
    * @return `application`, with attached dependency information
    */
  def analyseApplication(
    application: Application,
    info: DependencyInfo
  ): Application = {
    application match {
      case prefix @ Application.Prefix(fn, args, _, _, _, _) =>
        val fnDep     = asStatic(fn)
        val prefixDep = asStatic(prefix)
        info.dependents.updateAt(fnDep, Set(prefixDep))
        info.dependencies.updateAt(prefixDep, Set(fnDep))
        args.foreach(arg => {
          val argDep = asStatic(arg)
          info.dependents.updateAt(argDep, Set(prefixDep))
          info.dependencies.updateAt(prefixDep, Set(argDep))
        })

        prefix
          .copy(
            function  = analyseExpression(fn, info),
            arguments = args.map(analyseCallArgument(_, info))
          )
          .updateMetadata(new MetadataPair(this, info))
      case force @ Application.Force(target, _, _, _) =>
        val targetDep = asStatic(target)
        val forceDep  = asStatic(force)
        info.dependents.updateAt(targetDep, Set(forceDep))
        info.dependencies.updateAt(forceDep, Set(targetDep))

        force
          .copy(target = analyseExpression(target, info))
          .updateMetadata(new MetadataPair(this, info))
      case vector @ Application.Sequence(items, _, _, _) =>
        val vectorDep = asStatic(vector)
        items.foreach(it => {
          val itemDep = asStatic(it)
          info.dependents.updateAt(itemDep, Set(vectorDep))
          info.dependencies.updateAt(vectorDep, Set(itemDep))
        })

        vector
          .copy(items = items.map(analyseExpression(_, info)))
          .updateMetadata(new MetadataPair(this, info))
      case tSet @ Application.Typeset(expr, _, _, _) =>
        val tSetDep = asStatic(tSet)
        expr.foreach(exp => {
          val exprDep = asStatic(exp)
          info.dependents.updateAt(exprDep, Set(tSetDep))
          info.dependencies.updateAt(tSetDep, Set(exprDep))
        })

        tSet
          .copy(expression = expr.map(analyseExpression(_, info)))
          .updateMetadata(new MetadataPair(this, info))
      case _: Operator =>
        throw new CompilerError("Unexpected operator during Dataflow Analysis.")
    }
  }

  /** Performs dataflow analysis on a typing expression.
    *
    * Dataflow for typing expressions is a simple dependency on their parts.
    *
    * @param typ the type expression to perform dataflow analysis on
    * @param info the dependency information for the module
    * @return `typ`, with attached dependency information
    */
  def analyseType(typ: Type, info: DependencyInfo): Type = {
    typ match {
      case asc @ Type.Ascription(typed, signature, _, _, _) =>
        val ascrDep  = asStatic(asc)
        val typedDep = asStatic(typed)
        val sigDep   = asStatic(signature)
        info.dependents.updateAt(typedDep, Set(ascrDep))
        info.dependents.updateAt(sigDep, Set(ascrDep))
        info.dependencies.updateAt(ascrDep, Set(typedDep, sigDep))

        asc
          .copy(
            typed     = analyseExpression(typed, info),
            signature = analyseExpression(signature, info)
          )
          .updateMetadata(new MetadataPair(this, info))

      case fun @ Type.Function(args, result, _, _, _) =>
        val funDep  = asStatic(fun)
        val argDeps = args.map(asStatic)
        val resDep  = asStatic(result)
        argDeps.foreach(info.dependents.updateAt(_, Set(funDep)))
        info.dependents.updateAt(resDep, Set(funDep))
        info.dependencies.updateAt(funDep, Set(resDep :: argDeps: _*))

        fun
          .copy(
            args   = args.map(analyseExpression(_, info)),
            result = analyseExpression(result, info)
          )
          .updateMetadata(new MetadataPair(this, info))
      case ctx @ Type.Context(typed, context, _, _, _) =>
        val ctxDep     = asStatic(ctx)
        val typedDep   = asStatic(typed)
        val contextDep = asStatic(context)
        info.dependents.updateAt(typedDep, Set(ctxDep))
        info.dependents.updateAt(contextDep, Set(ctxDep))
        info.dependencies.updateAt(ctxDep, Set(typedDep, contextDep))

        ctx
          .copy(
            typed   = analyseExpression(typed, info),
            context = analyseExpression(context, info)
          )
          .updateMetadata(new MetadataPair(this, info))
      case err @ Type.Error(typed, error, _, _, _) =>
        val errDep   = asStatic(err)
        val typedDep = asStatic(typed)
        val errorDep = asStatic(error)
        info.dependents.updateAt(typedDep, Set(errDep))
        info.dependents.updateAt(errorDep, Set(errDep))
        info.dependencies.updateAt(errDep, Set(typedDep, errorDep))

        err
          .copy(
            typed = analyseExpression(typed, info),
            error = analyseExpression(error, info)
          )
          .updateMetadata(new MetadataPair(this, info))
      case member @ `type`.Set.Member(_, memberType, value, _, _, _) =>
        val memberDep     = asStatic(member)
        val memberTypeDep = asStatic(memberType)
        val valueDep      = asStatic(value)
        info.dependents.updateAt(memberTypeDep, Set(memberDep))
        info.dependents.updateAt(valueDep, Set(memberDep))
        info.dependencies.updateAt(memberDep, Set(memberTypeDep, valueDep))

        member
          .copy(
            memberType = analyseExpression(memberType, info),
            value      = analyseExpression(value, info)
          )
          .updateMetadata(new MetadataPair(this, info))
      case concat @ `type`.Set.Concat(left, right, _, _, _) =>
        val concatDep = asStatic(concat)
        val leftDep   = asStatic(left)
        val rightDep  = asStatic(right)
        info.dependents.updateAt(leftDep, Set(concatDep))
        info.dependents.updateAt(rightDep, Set(concatDep))
        info.dependencies.updateAt(concatDep, Set(rightDep, leftDep))

        concat
          .copy(
            left  = analyseExpression(left, info),
            right = analyseExpression(right, info)
          )
          .updateMetadata(new MetadataPair(this, info))
      case eq @ `type`.Set.Equality(left, right, _, _, _) =>
        val eqDep    = asStatic(eq)
        val leftDep  = asStatic(left)
        val rightDep = asStatic(right)
        info.dependents.updateAt(leftDep, Set(eqDep))
        info.dependents.updateAt(rightDep, Set(eqDep))
        info.dependencies.updateAt(eqDep, Set(leftDep, rightDep))

        eq.copy(
          left  = analyseExpression(left, info),
          right = analyseExpression(right, info)
        ).updateMetadata(new MetadataPair(this, info))
      case intersect @ `type`.Set.Intersection(left, right, _, _, _) =>
        val intersectDep = asStatic(intersect)
        val leftDep      = asStatic(left)
        val rightDep     = asStatic(right)
        info.dependents.updateAt(leftDep, Set(intersectDep))
        info.dependents.updateAt(rightDep, Set(intersectDep))
        info.dependencies.updateAt(intersectDep, Set(leftDep, rightDep))

        intersect
          .copy(
            left  = analyseExpression(left, info),
            right = analyseExpression(right, info)
          )
          .updateMetadata(new MetadataPair(this, info))
      case union @ `type`.Set.Union(operands, _, _, _) =>
        val unionDep = asStatic(union)
        val opDeps   = operands.map(asStatic)
        opDeps.foreach(info.dependents.updateAt(_, Set(unionDep)))
        info.dependencies.updateAt(unionDep, opDeps.toSet)
        union
          .copy(operands = operands.map(analyseExpression(_, info)))
          .updateMetadata(new MetadataPair(this, info))
      case subsumption @ `type`.Set.Subsumption(left, right, _, _, _) =>
        val subDep   = asStatic(subsumption)
        val leftDep  = asStatic(left)
        val rightDep = asStatic(right)
        info.dependents.updateAt(leftDep, Set(subDep))
        info.dependents.updateAt(rightDep, Set(subDep))
        info.dependencies.updateAt(subDep, Set(leftDep, rightDep))

        subsumption
          .copy(
            left  = analyseExpression(left, info),
            right = analyseExpression(right, info)
          )
          .updateMetadata(new MetadataPair(this, info))
    }
  }

  /** Performs dataflow analysis for a name usage.
    *
    * Name usages are dependent on the definition positions for those names.
    * These names can either be dynamic symbols (in which case all usages of
    * that symbol should be invalidated when the symbol changes), or static
    * symbols, which can be resolved into a direct dependency.
    *
    * @param name the name to perform dataflow analysis on
    * @param info the dependency information for the module
    * @return `name`, with attached dependency information
    */
  def analyseName(name: Name, info: DependencyInfo): Name = {
    val aliasInfo = name.passData
      .get(AliasAnalysis)
      .getOrElse(
        throw new CompilerError(
          "Name occurrence with missing aliasing information."
        )
      )
      .asInstanceOf[AliasAnalysis.Info.Occurrence]

    name match {
      case _: Name.Blank =>
        throw new CompilerError(
          "Blanks should not be present during dataflow analysis."
        )
      case _ =>
        val defIdForName = aliasInfo.graph.defLinkFor(aliasInfo.id)
        val key: DependencyInfo.Type = defIdForName match {
          case Some(defLink) =>
            aliasInfo.graph.getOccurrence(defLink.target) match {
              case Some(AliasAnalysis.Graph.Occurrence.Def(_, _, id, ext, _)) =>
                DependencyInfo.Type.Static(id, ext)
              case _ =>
                DependencyInfo.Type.Dynamic(name.name, None)
            }

          case None =>
            DependencyInfo.Type.Dynamic(name.name, None)
        }

        val nameDep = asStatic(name)
        info.dependents.updateAt(key, Set(nameDep))
        info.dependencies.updateAt(nameDep, Set(key))

        name.updateMetadata(new MetadataPair(this, info))
    }
  }

  /** Performs dependency analysis on a case expression.
    *
    * The value of a case expression is dependent on both its scrutinee and the
    * definitions of its branches. The computation of the branches also depends
    * on the scrutinee.
    *
    * @param cse the case expression to perform dataflow analysis on
    * @param info the dependency information for the module
    * @return `cse`, with attached dependency information
    */
  def analyseCase(cse: Case, info: DependencyInfo): Case = {
    cse match {
      case expr: Case.Expr =>
        val exprDep  = asStatic(expr)
        val scrutDep = asStatic(expr.scrutinee)
        info.dependents.updateAt(scrutDep, Set(exprDep))
        info.dependencies.updateAt(exprDep, Set(scrutDep))
        expr.branches.foreach(branch => {
          val branchDep = asStatic(branch)
          info.dependents.updateAt(branchDep, Set(exprDep))
          info.dependencies.updateAt(exprDep, Set(branchDep))
        })

        expr
          .copy(
            scrutinee = analyseExpression(expr.scrutinee, info),
            branches  = expr.branches.map(analyseCaseBranch(_, info))
          )
          .updateMetadata(new MetadataPair(this, info))
      case _: Case.Branch =>
        throw new CompilerError("Unexpected case branch.")
    }
  }

  /** Performs dataflow analysis on a case branch.
    *
    * A case branch is dependent on both its pattern expression and the branch
    * expression.
    *
    * @param branch the case branch to perform dataflow analysis on.
    * @param info the dependency information for the module
    * @return `branch`, with attached dependency information
    */
  def analyseCaseBranch(
    branch: Case.Branch,
    info: DependencyInfo
  ): Case.Branch = {
    val pattern    = branch.pattern
    val expression = branch.expression

    val branchDep  = asStatic(branch)
    val patternDep = asStatic(pattern)
    val exprDep    = asStatic(expression)
    info.dependents.updateAt(patternDep, Set(branchDep))
    info.dependents.updateAt(exprDep, Set(branchDep))
    info.dependencies.updateAt(branchDep, Set(patternDep, exprDep))

    branch
      .copy(
        pattern    = analysePattern(pattern, info),
        expression = analyseExpression(expression, info)
      )
      .updateMetadata(new MetadataPair(this, info))
  }

  /** Performs dataflow analysis on a case branch.
    *
    * A case pattern is dependent on its subexpressions only.
    *
    * @param pattern the pattern to perform dataflow analysis on
    * @param info the dependency information for the module
    * @return `pattern`, with attached dependency information
    */
  def analysePattern(
    pattern: Pattern,
    info: DependencyInfo
  ): Pattern = {
    val patternDep = asStatic(pattern)
    pattern match {
      case named @ Pattern.Name(name, _, _, _) =>
        val nameDep = asStatic(name)
        info.dependents.updateAt(nameDep, Set(patternDep))
        info.dependencies.updateAt(patternDep, Set(nameDep))

        named.updateMetadata(new MetadataPair(this, info))
      case cons @ Pattern.Constructor(constructor, fields, _, _, _) =>
        val consDep = asStatic(constructor)
        info.dependents.updateAt(consDep, Set(patternDep))
        info.dependencies.updateAt(patternDep, Set(consDep))
        fields.foreach(field => {
          val fieldDep = asStatic(field)
          info.dependents.updateAt(fieldDep, Set(patternDep))
          info.dependencies.updateAt(patternDep, Set(fieldDep))
        })

        cons
          .copy(
            constructor = analyseName(constructor, info),
            fields      = fields.map(analysePattern(_, info))
          )
          .updateMetadata(new MetadataPair(this, info))
      case literal: Pattern.Literal =>
        literal.updateMetadata(new MetadataPair(this, info))
      case Pattern.Type(name, tpe, _, _, _) =>
        val nameDep = asStatic(name)
        info.dependents.updateAt(nameDep, Set(patternDep))
        info.dependencies.updateAt(patternDep, Set(nameDep))
        val tpeDep = asStatic(tpe)
        info.dependents.updateAt(tpeDep, Set(patternDep))
        info.dependencies.updateAt(patternDep, Set(tpeDep))

        pattern.updateMetadata(new MetadataPair(this, info))
      case _: Pattern.Documentation =>
        throw new CompilerError(
          "Branch documentation should be desugared at an earlier stage."
        )
      case err: errors.Pattern =>
        err.updateMetadata(new MetadataPair(this, info))
    }
  }

  /** Performs dataflow analysis on a function definition argument.
    *
    * A function definition argument is dependent purely on its default, if said
    * default is present.
    *
    * @param argument the definition argument to perform dataflow analysis on
    * @param info the dependency information for the module
    * @return `argument`, with attached dependency information
    */
  def analyseDefinitionArgument(
    argument: DefinitionArgument,
    info: DependencyInfo
  ): DefinitionArgument = {
    argument match {
      case spec @ DefinitionArgument.Specified(_, _, defValue, _, _, _, _) =>
        val specDep = asStatic(spec)
        defValue.foreach(expr => {
          val exprDep = asStatic(expr)
          info.dependents.updateAt(exprDep, Set(specDep))
          info.dependencies.updateAt(specDep, Set(exprDep))
        })

        spec
          .copy(
            defaultValue = defValue.map(analyseExpression(_, info))
          )
          .updateMetadata(new MetadataPair(this, info))
    }
  }

  /** Performs dataflow analysis on a function call argument.
    *
    * A function call argument is dependent both on the expression value that it
    * is wrapping, as well as the name of the argument, if it exists.
    *
    * @param argument the call argument to perform dataflow analysis on
    * @param info the dependency information for the module
    * @return `argument`, with attached dependency information
    */
  def analyseCallArgument(
    argument: CallArgument,
    info: DependencyInfo
  ): CallArgument = {
    argument match {
      case spec @ CallArgument.Specified(name, value, _, _, _) =>
        val specDep  = asStatic(spec)
        val valueDep = asStatic(value)
        info.dependents.updateAt(valueDep, Set(specDep))
        info.dependencies.updateAt(specDep, Set(valueDep))
        name.foreach(name => {
          val nameDep = asStatic(name)
          info.dependents.updateAt(nameDep, Set(specDep))
          info.dependencies.updateAt(specDep, Set(nameDep))
        })

        spec
          .copy(
            value = analyseExpression(value, info)
          )
          .updateMetadata(new MetadataPair(this, info))
    }
  }

  // === Pass Metadata ========================================================

  /** Storage for dependency information.
    *
    * It maps from an expression to other expressions based on some relationship
    * between them.
    *
    * @param mapping storage for the direct mapping between program components
    */
  sealed case class DependencyMapping(
    mapping: mutable.Map[DependencyInfo.Type, Set[DependencyInfo.Type]] =
      mutable.Map()
  ) {

    /** Returns the set of all program component associated with the provided
      * key.
      *
      * Please note that the result set contains not just the _direct_
      * associations with the key, but also the _indirect_ associations with the
      * key.
      *
      * @param key the key to get the associated components of
      * @return the set of all components associated with `key`
      * @throws NoSuchElementException when `key` does not exist in the
      *                                dependencies mapping
      */
    @throws[NoSuchElementException]
    def apply(key: DependencyInfo.Type): Set[DependencyInfo.Type] = {
      if (mapping.contains(key)) {
        get(key) match {
          case Some(deps) => deps
          case None       => throw new NoSuchElementException
        }
      } else {
        throw new NoSuchElementException
      }
    }

    /** Obtains the program components _directly_ associated with a given node
      * in the IR.
      *
      * Please note that this does _not_ return the transitive closure of all
      * associations with the node.
      *
      * @param key the key to get the associated components of
      * @return the set of the components directly associated with `key`, if it
      *         exists
      */
    def getDirect(
      key: DependencyInfo.Type
    ): Option[Set[DependencyInfo.Type]] = {
      mapping.get(key)
    }

    /** Obtains the external identifiers of the _direct_ dependents of a given
      * node in the IR.
      *
      * @param key the key to get the dependents of
      * @return the set of external identifiers for the direct dependencies of
      *         `key`, if they exist
      */
    def getExternalDirect(
      key: DependencyInfo.Type
    ): Option[Set[UUID @ExternalID]] = {
      getDirect(key).map(_.flatMap(_.externalId))
    }

    /** Safely gets the set of all program components associated with the
      * provided key.
      *
      * Please note that the result set contains not just the components that
      * are directly associated with the key, but all components associated with
      * the key
      *
      * @param key the key to get the associations of
      * @return the set of all associations with `key`, if key exists
      */
    def get(key: DependencyInfo.Type): Option[Set[DependencyInfo.Type]] = {

      @scala.annotation.tailrec
      def go(
        queue: mutable.Queue[DependencyInfo.Type],
        visited: mutable.Set[DependencyInfo.Type],
        result: mutable.Set[DependencyInfo.Type]
      ): Set[DependencyInfo.Type] =
        if (queue.isEmpty) result.to(ListSet)
        else {
          val elem = queue.dequeue()
          if (visited.contains(elem)) go(queue, visited, result)
          else {
            mapping.get(elem) match {
              case Some(deps) =>
                go(
                  queue.enqueueAll(deps),
                  visited.addOne(elem),
                  result.addAll(deps)
                )
              case None =>
                go(queue, visited.addOne(elem), result)
            }
          }
        }

      if (mapping.contains(key)) {
        Some(
          go(
            mutable.Queue(key),
            mutable.HashSet(),
            mutable.LinkedHashSet()
          )
        )
      } else {
        None
      }
    }

    /** Safely gets the external identifiers for all program component
      * associated with the provided key.
      *
      * Please note that the result set contains not just the components that
      * are directly associated with the key, but all associations with the key.
      *
      * @param key the key from which to get the external identifiers of its
      *            associated program components
      * @return the set of all external identifiers of program components
      *         associated with `key`, if it exists
      */
    def getExternal(key: DependencyInfo.Type): Option[Set[UUID @ExternalID]] = {
      get(key).map(_.flatMap(_.externalId))
    }

    /** Executes an update on the association information.
      *
      * @param key the key to update the associations for
      * @param newDependents the updated associations for `key`
      */
    def update(
      key: DependencyInfo.Type,
      newDependents: Set[DependencyInfo.Type]
    ): Unit =
      mapping(key) = newDependents

    /** Updates the associations for the provided key, or creates them if they
      * do not already exist.
      *
      * @param key the key to add or update associations for
      * @param newDependents the new associations information for `key`
      */
    def updateAt(
      key: DependencyInfo.Type,
      newDependents: Set[DependencyInfo.Type]
    ): Unit = {
      if (mapping.contains(key)) {
        mapping(key) ++= newDependents
      } else {
        mapping(key) = newDependents
      }
    }

    /** Updates the associations for the provided keys, or creates them if they
      * do not already exist.
      *
      * @param keys the keys to add or update assocuations for
      * @param dependents the new associations information for each `key` in
      *                   `keys`
      */
    def updateAt(
      keys: List[DependencyInfo.Type],
      dependents: Set[DependencyInfo.Type]
    ): Unit = keys.foreach(key => updateAt(key, dependents))

    /** Combines two dependency information containers.
      *
      * @param that the other container to combine with `this`
      * @return the result of combining `this` and `that`
      */
    def ++(that: DependencyMapping): DependencyMapping = {
      val combinedModule = new DependencyMapping(this.mapping)

      for ((key, value) <- that.mapping) {
        combinedModule.mapping.get(key) match {
          case Some(xs) => combinedModule(key) = value ++ xs
          case None     => combinedModule(key) = value
        }
      }

      combinedModule
    }

    /** @return A deep copy of this dependency mapping */
    def deepCopy: DependencyMapping = {
      DependencyMapping(mutable.Map.from(this.mapping.toMap))
    }
  }

  /** A representation of dependency information for dataflow analysis.
    *
    * @param dependents information on the dependents of program components,
    *                   mapping from a component to the components that depend
    *                   on it
    * @param dependencies information on the dependencies of program components,
    *                     mapping from a component to the components that it
    *                     depends on
    */
  sealed case class DependencyInfo(
    dependents: DependencyMapping   = DependencyMapping(),
    dependencies: DependencyMapping = DependencyMapping()
  ) extends IRPass.IRMetadata {
    override val metadataName: String = "DataflowAnalysis.DependencyInfo"

    /** Combines two dependency information containers.
      *
      * @param that the other container to combine with `this`
      * @return the result of combining `this` and `that`
      */
    def ++(that: DependencyInfo): DependencyInfo = {
      DependencyInfo(
        dependents   = this.dependents ++ that.dependents,
        dependencies = that.dependencies ++ that.dependencies
      )
    }

    override def duplicate(): Option[IRPass.IRMetadata] = None

    /** @inheritdoc */
    override def prepareForSerialization(compiler: Compiler): DependencyInfo =
      this

    /** @inheritdoc */
    override def restoreFromSerialization(
      compiler: Compiler
    ): Option[DependencyInfo] = Some(this)
  }
  object DependencyInfo {

    /** The type of symbols in this analysis. */
    type Symbol = String

    /** The type of identification for a program component. */
    sealed trait Type {
      val externalId: Option[UUID @ExternalID]
    }
    object Type {

      /** Program components identified by their unique identifier.
        *
        * @param id the unique identifier of the program component
        * @param externalId the external identifier corresponding to the program
        *                   component
        */
      sealed case class Static(
        id: UUID @Identifier,
        override val externalId: Option[UUID @ExternalID]
      ) extends Type

      /** Program components identified by their symbol.
        *
        * @param name the name of the symbol
        * @param externalId the external identifier corresponding to the program
        *                   component
        */
      sealed case class Dynamic(
        name: DependencyInfo.Symbol,
        override val externalId: Option[UUID @ExternalID]
      ) extends Type

      // === Utility Functions ================================================

      /** Creates a static dependency on an IR node.
        *
        * @param ir the IR node to create a dependency on
        * @return a static dependency on `ir`
        */
      def asStatic(ir: IR): Static = {
        Static(ir.getId, ir.getExternalId)
      }

      /** Creates a dynamic dependency on an IR node.
        *
        * @param ir the IR node to create a dependency on
        * @return a dynamic dependency on `ir`
        */
      def asDynamic(ir: Name): Dynamic = {
        Dynamic(ir.name, ir.getExternalId)
      }
    }
  }
}
