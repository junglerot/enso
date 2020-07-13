package org.enso.compiler.codegen

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.source.{Source, SourceSection}
import org.enso.compiler.core.IR
import org.enso.compiler.core.IR.Module.Scope.Import
import org.enso.compiler.core.IR.{Error, IdentifiedLocation, Pattern}
import org.enso.compiler.exception.{BadPatternMatch, CompilerError}
import org.enso.compiler.pass.analyse.AliasAnalysis.Graph.{Scope => AliasScope}
import org.enso.compiler.pass.analyse.AliasAnalysis.{Graph => AliasGraph}
import org.enso.compiler.pass.analyse.{
  AliasAnalysis,
  DataflowAnalysis,
  TailCall
}
import org.enso.compiler.pass.optimise.ApplicationSaturation
import org.enso.interpreter.node.callable.argument.ReadArgumentNode
import org.enso.interpreter.node.callable.function.{
  BlockNode,
  CreateFunctionNode
}
import org.enso.interpreter.node.callable.thunk.{CreateThunkNode, ForceNode}
import org.enso.interpreter.node.callable.{
  ApplicationNode,
  InvokeCallableNode,
  SequenceLiteralNode
}
import org.enso.interpreter.node.controlflow._
import org.enso.interpreter.node.expression.constant.{
  ConstantObjectNode,
  ConstructorNode,
  DynamicSymbolNode,
  ErrorNode
}
import org.enso.interpreter.node.expression.literal.{
  IntegerLiteralNode,
  TextLiteralNode
}
import org.enso.interpreter.node.scope.{AssignmentNode, ReadLocalVariableNode}
import org.enso.interpreter.node.{
  ClosureRootNode,
  ExpressionNode => RuntimeExpression
}
import org.enso.interpreter.runtime.Context
import org.enso.interpreter.runtime.callable.UnresolvedSymbol
import org.enso.interpreter.runtime.callable.argument.{
  ArgumentDefinition,
  CallArgument
}
import org.enso.interpreter.runtime.callable.atom.AtomConstructor
import org.enso.interpreter.runtime.callable.function.{
  FunctionSchema,
  Function => RuntimeFunction
}
import org.enso.interpreter.runtime.error.{
  DuplicateArgumentNameException,
  VariableDoesNotExistException
}
import org.enso.interpreter.runtime.scope.{LocalScope, ModuleScope}
import org.enso.interpreter.{Constants, Language}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.jdk.OptionConverters._

/** This is an implementation of a codegeneration pass that lowers the Enso
  * [[IR]] into the truffle [[org.enso.compiler.core.Core.Node]] structures that
  * are actually executed.
  *
  * It should be noted that, as is, there is no support for cross-module links,
  * with each lowering pass operating solely on a single module.
  *
  * @param context the language context instance for which this is executing
  * @param source the source code that corresponds to the text for which code
  *               is being generated
  * @param moduleScope the scope of the module for which code is being generated
  */
class IrToTruffle(
  val context: Context,
  val source: Source,
  val moduleScope: ModuleScope
) {

  val language: Language = context.getLanguage

  // ==========================================================================
  // === Top-Level Runners ====================================================
  // ==========================================================================

  /** Executes the codegen pass on the input [[IR]].
    *
    * Please note that the IR passed to this function should not contain _any_
    * errors (members of [[IR.Error]]). These must be dealt with and reported
    * before codegen runs, as they will cause a compiler error.
    *
    * In future, this restriction will be relaxed to admit errors that are
    * members of [[IR.Diagnostic.Kind.Interactive]], such that we can display
    * these to users during interactive execution.
    *
    * @param ir the IR to generate code for
    */
  def run(ir: IR.Module): Unit = processModule(ir)

  /** Executes the codegen pass on an inline input.
    *
    * @param ir the IR to generate code for
    * @param localScope the scope in which the inline input exists
    * @param scopeName the name of `localScope`
    * @return an truffle expression representing `ir`
    */
  def runInline(
    ir: IR.Expression,
    localScope: LocalScope,
    scopeName: String
  ): RuntimeExpression = {
    new ExpressionProcessor(localScope, scopeName).runInline(ir)
  }

  // ==========================================================================
  // === IR Processing Functions ==============================================
  // ==========================================================================

  /** Generates truffle nodes from the top-level definitions of an Enso module
    * and registers these definitions in scope in the compiler.
    *
    * It does not directly return any constructs, but instead registers these
    * constructs for later access in the compiler and language context.
    *
    * @param module the module for which code should be generated
    */
  private def processModule(module: IR.Module): Unit = {
    val imports = module.imports
    val atomDefs = module.bindings.collect {
      case atom: IR.Module.Scope.Definition.Atom => atom
    }
    val methodDefs = module.bindings.collect {
      case method: IR.Module.Scope.Definition.Method => method
    }

    // Register the imports in scope
    imports.foreach {
      case Import.Polyglot(Import.Polyglot.Java(pkg, cls), _, _, _) =>
        val fullName = s"$pkg.$cls"
        this.moduleScope.registerPolyglotSymbol(
          cls,
          context.getEnvironment.lookupHostSymbol(fullName)
        )
      case i: Import.Module =>
        this.moduleScope.addImport(context.getCompiler.processImport(i.name))
      case _: Error =>
    }

    // Register the atoms and their constructors in scope
    val atomConstructors =
      atomDefs.map(t => new AtomConstructor(t.name.name, moduleScope))
    atomConstructors.foreach(moduleScope.registerConstructor)

    atomConstructors
      .zip(atomDefs)
      .foreach {
        case (atomCons, atomDefn) =>
          val scopeInfo = atomDefn
            .unsafeGetMetadata(
              AliasAnalysis,
              "No root scope on an atom definition."
            )
            .unsafeAs[AliasAnalysis.Info.Scope.Root]

          val dataflowInfo = atomDefn.unsafeGetMetadata(
            DataflowAnalysis,
            "No dataflow information associated with an atom."
          )

          val argFactory =
            new DefinitionArgumentProcessor(
              scope = new LocalScope(
                None,
                scopeInfo.graph,
                scopeInfo.graph.rootScope,
                dataflowInfo
              )
            )
          val argDefs =
            new Array[ArgumentDefinition](atomDefn.arguments.size)

          for (idx <- atomDefn.arguments.indices) {
            argDefs(idx) = argFactory.run(atomDefn.arguments(idx), idx)
          }

          atomCons.initializeFields(argDefs: _*)
      }

    // Register the method definitions in scope
    methodDefs.foreach(methodDef => {
      val scopeInfo = methodDef
        .unsafeGetMetadata(
          AliasAnalysis,
          "Missing scope information for method."
        )
        .unsafeAs[AliasAnalysis.Info.Scope.Root]
      val dataflowInfo = methodDef.unsafeGetMetadata(
        DataflowAnalysis,
        "Method definition missing dataflow information."
      )

      val typeName =
        if (methodDef.typeName.name == Constants.Names.CURRENT_MODULE) {
          moduleScope.getAssociatedType.getName
        } else {
          methodDef.typeName.name
        }

      val expressionProcessor = new ExpressionProcessor(
        typeName ++ Constants.SCOPE_SEPARATOR ++ methodDef.methodName.name,
        scopeInfo.graph,
        scopeInfo.graph.rootScope,
        dataflowInfo
      )

      val funNode = methodDef.body match {
        case fn: IR.Function =>
          expressionProcessor.processFunctionBody(
            fn.arguments,
            fn.body,
            methodDef.location,
            Some(methodDef.methodName.name)
          )
        case _ =>
          throw new CompilerError(
            "Method bodies must be functions at the point of codegen."
          )
      }

      val function = new RuntimeFunction(
        funNode.getCallTarget,
        null,
        new FunctionSchema(
          FunctionSchema.CallStrategy.CALL_LOOP,
          funNode.getArgs: _*
        )
      )

      val cons = moduleScope
        .getConstructor(typeName)
        .orElseThrow(() =>
          new VariableDoesNotExistException(methodDef.typeName.name)
        )
      moduleScope.registerMethod(cons, methodDef.methodName.name, function)
    })
  }

  // ==========================================================================
  // === Utility Functions ====================================================
  // ==========================================================================

  /** Creates a source section from a given location in the code.
    *
    * @param location the location to turn into a section
    * @return the source section corresponding to `location`
    */
  private def makeSection(
    location: Option[IdentifiedLocation]
  ): SourceSection = {
    location
      .map(loc => source.createSection(loc.start, loc.length))
      .getOrElse(source.createUnavailableSection())
  }

  /** Sets the source section for a given expression node to the provided
    * location.
    *
    * @param expr     the expression to set the location for
    * @param location the location to assign to `expr`
    * @tparam T the type of `expr`
    * @return `expr` with its location set to `location`
    */
  private def setLocation[T <: RuntimeExpression](
    expr: T,
    location: Option[IdentifiedLocation]
  ): T = {
    location.foreach { loc =>
      expr.setSourceLocation(loc.start, loc.length)
      loc.id.foreach { id => expr.setId(id) }
    }
    expr
  }

  // ==========================================================================
  // === Expression Processor =================================================
  // ==========================================================================

  /** This class is responsible for performing codegen of [[IR]] constructs that
    * are Enso program expressions.
    *
    * @param scope the scope in which the code generation is occurring
    * @param scopeName the name of `scope`
    */
  sealed private class ExpressionProcessor(
    val scope: LocalScope,
    val scopeName: String
  ) {

    private var currentVarName = "anonymous"

    // === Construction =======================================================

    /** Constructs an [[ExpressionProcessor]] instance with a defaulted local
      * scope.
      *
      * @param scopeName the name to attribute to the default local scope.
      */
    def this(
      scopeName: String,
      graph: AliasGraph,
      scope: AliasScope,
      dataflowInfo: DataflowAnalysis.Metadata
    ) = {
      this(
        new LocalScope(None, graph, scope, dataflowInfo),
        scopeName
      )
    }

    /** Creates an instance of [[ExpressionProcessor]] that operates in a child
      * scope of `this`.
      *
      * @param name the name of the child scope
      * @return an expression processor operating on a child scope
      */
    def createChild(
      name: String,
      scope: AliasScope
    ): ExpressionProcessor = {
      new ExpressionProcessor(this.scope.createChild(scope), name)
    }

    // === Runner =============================================================

    /** Runs the code generation process on the provided piece of [[IR]].
      *
      * @param ir the IR to generate code for
      * @return a truffle expression that represents the same program as `ir`
      */
    def run(ir: IR.Expression): RuntimeExpression = {
      val tailMeta = ir.unsafeGetMetadata(
        TailCall,
        "Missing tail call information on method."
      )

      val runtimeExpression = ir match {
        case block: IR.Expression.Block     => processBlock(block)
        case literal: IR.Literal            => processLiteral(literal)
        case app: IR.Application            => processApplication(app)
        case name: IR.Name                  => processName(name)
        case function: IR.Function          => processFunction(function)
        case binding: IR.Expression.Binding => processBinding(binding)
        case caseExpr: IR.Case              => processCase(caseExpr)
        case typ: IR.Type                   => processType(typ)
        case _: IR.Empty =>
          throw new CompilerError(
            "Empty IR nodes should not exist during code generation."
          )
        case _: IR.Comment =>
          throw new CompilerError(
            "Comments should not be present during codegen."
          )
        case err: IR.Error => processError(err)
        case IR.Foreign.Definition(_, _, _, _, _) =>
          throw new CompilerError(
            s"Foreign expressions not yet implemented: $ir."
          )
      }

      runtimeExpression.setTail(tailMeta)
      runtimeExpression
    }

    /** Executes the expression processor on a piece of code that has been
      * written inline.
      *
      * @param ir the IR to generate code for
      * @return a truffle expression that represents the same program as `ir`
      */
    def runInline(ir: IR.Expression): RuntimeExpression = {
      val expression = run(ir)
      expression
    }

    // === Processing =========================================================

    /** Performs code generation for an Enso block expression.
      *
      * @param block the block to generate code for
      * @return the truffle nodes corresponding to `block`
      */
    def processBlock(block: IR.Expression.Block): RuntimeExpression = {
      if (block.suspended) {
        val scopeInfo = block
          .unsafeGetMetadata(
            AliasAnalysis,
            "Missing scope information on block."
          )
          .unsafeAs[AliasAnalysis.Info.Scope.Child]

        val childFactory = this.createChild("suspended-block", scopeInfo.scope)
        val childScope   = childFactory.scope

        val blockNode = childFactory.processBlock(block.copy(suspended = false))

        val defaultRootNode = ClosureRootNode.build(
          language,
          childScope,
          moduleScope,
          blockNode,
          null,
          s"default::$scopeName",
          null
        )

        val callTarget = Truffle.getRuntime.createCallTarget(defaultRootNode)
        setLocation(CreateThunkNode.build(callTarget), block.location)
      } else {
        val statementExprs = block.expressions.map(this.run(_)).toArray
        val retExpr        = this.run(block.returnValue)

        val blockNode = BlockNode.build(statementExprs, retExpr)
        setLocation(blockNode, block.location)
      }
    }

    /** Performs code generation for an Enso type operator.
      *
      * @param value the type operation to generate code for
      * @return the truffle nodes corresponding to `value`
      */
    def processType(value: IR.Type): RuntimeExpression = {
      setLocation(
        ErrorNode.build(
          context.getBuiltins
            .error()
            .syntaxError()
            .newInstance(
              "Type operators are not currently supported at runtime."
            )
        ),
        value.location
      )
    }

    /** Performs code generation for an Enso case expression.
      *
      * @param caseExpr the case expression to generate code for
      * @return the truffle nodes corresponding to `caseExpr`
      */
    def processCase(caseExpr: IR.Case): RuntimeExpression =
      caseExpr match {
        case IR.Case.Expr(scrutinee, branches, location, _, _) =>
          val scrutineeNode = this.run(scrutinee)

          val maybeCases    = branches.map(processCaseBranch)
          val allCasesValid = maybeCases.forall(_.isRight)

          // TODO [AA] This is until we can resolve this statically in the
          //  compiler. Doing so requires fixing issues around cyclical imports.
          if (allCasesValid) {
            val cases = maybeCases
              .collect {
                case Right(x) => x
              }
              .toArray[BranchNode]

            // Note [Pattern Match Fallbacks]
            val matchExpr = CaseNode.build(scrutineeNode, cases)
            setLocation(matchExpr, location)
          } else {
            val invalidBranches = maybeCases.collect {
              case Left(x) => x
            }

            val message = invalidBranches.map(_.message).mkString(", ")

            val error = context.getBuiltins
              .error()
              .compileError()
              .newInstance(message)

            setLocation(ErrorNode.build(error), caseExpr.location)
          }
        case IR.Case.Branch(_, _, _, _, _) =>
          throw new CompilerError("A CaseBranch should never occur here.")
      }

    /** Performs code generation for an Enso case branch.
      *
      * @param branch the case branch to generate code for
      * @return the truffle nodes correspondingg to `caseBranch` or an error if
      *         the match is invalid
      */
    def processCaseBranch(
      branch: IR.Case.Branch
    ): Either[BadPatternMatch, BranchNode] = {
      val scopeInfo = branch
        .unsafeGetMetadata(
          AliasAnalysis,
          "No scope information on a case branch."
        )
        .unsafeAs[AliasAnalysis.Info.Scope.Child]

      val branchIsTail = branch.unsafeGetMetadata(
        TailCall,
        "Case branch is missing tail position information."
      )

      val childProcessor = this.createChild("case_branch", scopeInfo.scope)

      branch.pattern match {
        case named @ Pattern.Name(_, _, _, _) =>
          val arg = List(genArgFromMatchField(named))

          val branchCodeNode = childProcessor.processFunctionBody(
            arg,
            branch.expression,
            branch.location,
            None
          )

          val branchNode = CatchAllBranchNode.build(branchCodeNode)
          branchNode.setTail(branchIsTail)

          Right(branchNode)
        case cons @ Pattern.Constructor(constructor, fields, _, _, _) =>
          if (!cons.isDesugared) {
            throw new CompilerError(
              "Nested patterns desugaring must have taken place by the " +
              "point of code generation."
            )
          }

          val fieldNames   = cons.unsafeFieldsAsNamed
          val fieldsAsArgs = fieldNames.map(genArgFromMatchField)

          val branchCodeNode = childProcessor.processFunctionBody(
            fieldsAsArgs,
            branch.expression,
            branch.location,
            None
          )

          moduleScope.getConstructor(constructor.name).toScala match {
            case Some(atomCons) =>
              val numExpectedArgs = atomCons.getArity
              val numProvidedArgs = fields.length

              if (numProvidedArgs != numExpectedArgs) {
                Left(
                  BadPatternMatch.WrongArgCount(
                    constructor.name,
                    numExpectedArgs,
                    numProvidedArgs
                  )
                )
              } else {
                val bool = context.getBuiltins.bool()
                val branchNode: BranchNode =
                  if (atomCons == bool.getTrue) {
                    BooleanBranchNode.build(true, branchCodeNode)
                  } else if (atomCons == bool.getFalse) {
                    BooleanBranchNode.build(false, branchCodeNode)
                  } else {
                    ConstructorBranchNode.build(atomCons, branchCodeNode)
                  }
                branchNode.setTail(branchIsTail)

                Right(branchNode)
              }
            case None =>
              Left(BadPatternMatch.NonVisibleConstructor(constructor.name))
          }
        case _: Pattern.Documentation =>
          throw new CompilerError(
            "Branch documentation should be desugared at an earlier stage."
          )
      }
    }

    /* Note [Pattern Match Fallbacks]
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * Enso in its current state has no coverage checking for constructors on
     * pattern matches as it has no sense of what constructors contribute to
     * make a 'type'. This means that, in absence of a user-provided fallback or
     * catch-all case in a pattern match, the interpreter has to ensure that
     * it has one to catch that error.
     */

    /** Generates an argument from a field of a pattern match.
      *
      * @param name the pattern field to generate from
      * @return `name` as a function definition argument.
      */
    def genArgFromMatchField(name: Pattern.Name): IR.DefinitionArgument = {
      IR.DefinitionArgument.Specified(
        name.name,
        None,
        suspended = false,
        name.location,
        passData    = name.name.passData,
        diagnostics = name.name.diagnostics
      )
    }

    /** Generates code for an Enso binding expression.
      *
      * @param binding the binding to generate code for
      * @return the truffle nodes corresponding to `binding`
      */
    def processBinding(binding: IR.Expression.Binding): RuntimeExpression = {
      val occInfo = binding
        .unsafeGetMetadata(
          AliasAnalysis,
          "Binding with missing occurrence information."
        )
        .unsafeAs[AliasAnalysis.Info.Occurrence]

      currentVarName = binding.name.name

      val slot = scope.createVarSlot(occInfo.id)

      setLocation(
        AssignmentNode.build(this.run(binding.expression), slot),
        binding.location
      )
    }

    /** Generates code for an Enso function.
      *
      * @param function the function to generate code for
      * @return the truffle nodes corresponding to `function`
      */
    def processFunction(function: IR.Function): RuntimeExpression = {
      val scopeInfo = function
        .unsafeGetMetadata(AliasAnalysis, "No scope info on a function.")
        .unsafeAs[AliasAnalysis.Info.Scope.Child]

      if (function.body.isInstanceOf[IR.Function]) {
        throw new CompilerError(
          "Lambda found directly as function body. It looks like Lambda " +
          "Consolidation hasn't run."
        )
      }

      val scopeName = if (function.canBeTCO) {
        currentVarName
      } else {
        "case_expression"
      }

      val child = this.createChild(scopeName, scopeInfo.scope)

      val fn = child.processFunctionBody(
        function.arguments,
        function.body,
        function.location,
        None
      )

      fn
    }

    /** Generates code for an Enso name.
      *
      * @param name the name to generate code for
      * @return the truffle nodes corresponding to `name`
      */
    def processName(name: IR.Name): RuntimeExpression = {
      val nameExpr = name match {
        case IR.Name.Literal(nameStr, _, _, _) =>
          val useInfo = name
            .unsafeGetMetadata(
              AliasAnalysis,
              "No occurrence on variable usage."
            )
            .unsafeAs[AliasAnalysis.Info.Occurrence]

          val slot       = scope.getFramePointer(useInfo.id)
          val atomCons   = moduleScope.getConstructor(nameStr).toScala
          val polySymbol = moduleScope.lookupPolyglotSymbol(nameStr).toScala
          if (nameStr == Constants.Names.CURRENT_MODULE) {
            ConstructorNode.build(moduleScope.getAssociatedType)
          } else if (slot.isDefined) {
            ReadLocalVariableNode.build(slot.get)
          } else if (atomCons.isDefined) {
            ConstructorNode.build(atomCons.get)
          } else if (polySymbol.isDefined) {
            ConstantObjectNode.build(polySymbol.get)
          } else {
            DynamicSymbolNode.build(
              UnresolvedSymbol.build(nameStr, moduleScope)
            )
          }
        case IR.Name.Here(_, _, _) =>
          ConstructorNode.build(moduleScope.getAssociatedType)
        case IR.Name.This(location, passData, _) =>
          processName(
            IR.Name.Literal(Constants.Names.THIS_ARGUMENT, location, passData)
          )
        case _: IR.Name.Blank =>
          throw new CompilerError(
            "Blanks should not be present at codegen time."
          )
        case _: IR.Name.MethodReference =>
          throw new CompilerError(
            "Method references should not be present at codegen time."
          )
      }

      setLocation(nameExpr, name.location)
    }

    /** Generates code for an Enso literal.
      *
      * @param literal the literal to generate code for
      * @return the truffle nodes corresponding to `literal`
      */
    def processLiteral(literal: IR.Literal): RuntimeExpression =
      literal match {
        case IR.Literal.Number(value, location, _, _) =>
          setLocation(IntegerLiteralNode.build(value.toLong), location)
        case IR.Literal.Text(text, location, _, _) =>
          setLocation(TextLiteralNode.build(text), location)
      }

    /**
      * Generates a runtime implementation for compile error nodes.
      *
      * @param error the IR representing a compile error.
      * @return a runtime node representing the error.
      */
    def processError(error: IR.Error): RuntimeExpression = {
      val payload: AnyRef = error match {
        case Error.InvalidIR(_, _, _) =>
          throw new CompilerError("Unexpected Invalid IR during codegen.")
        case err: Error.Syntax =>
          context.getBuiltins.error().syntaxError().newInstance(err.message)
        case err: Error.Redefined.Binding =>
          context.getBuiltins.error().compileError().newInstance(err.message)
        case err: Error.Redefined.Method =>
          context.getBuiltins.error().compileError().newInstance(err.message)
        case err: Error.Redefined.Atom =>
          context.getBuiltins.error().compileError().newInstance(err.message)
        case err: Error.Redefined.ThisArg =>
          context.getBuiltins.error().compileError().newInstance(err.message)
        case err: Error.Unexpected.TypeSignature =>
          context.getBuiltins.error().compileError().newInstance(err.message)
      }
      setLocation(ErrorNode.build(payload), error.location)
    }

    /** Generates code for an Enso function body.
      *
      * @param arguments the arguments to the function
      * @param body the body of the function
      * @param location the location at which the function exists in the source
      * @param name the name of the function
      * @return a truffle node representing the described function
      */
    def processFunctionBody(
      arguments: List[IR.DefinitionArgument],
      body: IR.Expression,
      location: Option[IdentifiedLocation],
      name: Option[String]
    ): CreateFunctionNode = {
      val argFactory = new DefinitionArgumentProcessor(scopeName, scope)

      val argDefinitions = new Array[ArgumentDefinition](arguments.size)
      val argExpressions = new ArrayBuffer[RuntimeExpression]
      val seenArgNames   = mutable.Set[String]()

      // Note [Rewriting Arguments]
      for ((unprocessedArg, idx) <- arguments.view.zipWithIndex) {
        val arg = argFactory.run(unprocessedArg, idx)
        argDefinitions(idx) = arg

        val occInfo = unprocessedArg
          .unsafeGetMetadata(
            AliasAnalysis,
            "No occurrence on an argument definition."
          )
          .unsafeAs[AliasAnalysis.Info.Occurrence]

        val slot = scope.createVarSlot(occInfo.id)
        val readArg =
          ReadArgumentNode.build(idx, arg.getDefaultValue.orElse(null))
        val assignArg = AssignmentNode.build(readArg, slot)

        argExpressions.append(assignArg)

        val argName = arg.getName

        if (seenArgNames contains argName) {
          throw new DuplicateArgumentNameException(argName)
        } else seenArgNames.add(argName)
      }

      val bodyIsTail = body.unsafeGetMetadata(
        TailCall,
        "Function body missing tail call information."
      )

      val bodyExpr = this.run(body)

      val fnBodyNode = BlockNode.build(argExpressions.toArray, bodyExpr)
      val fnRootNode = ClosureRootNode.build(
        language,
        scope,
        moduleScope,
        fnBodyNode,
        makeSection(location),
        scopeName,
        name
          .map(moduleScope.getModule.getName.createChild)
          .map(_.toString)
          .orNull
      )
      val callTarget = Truffle.getRuntime.createCallTarget(fnRootNode)

      val expr = CreateFunctionNode.build(callTarget, argDefinitions)

      fnBodyNode.setTail(bodyIsTail)

      setLocation(expr, location)
    }

    /* Note [Rewriting Arguments]
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~
     * While it would be tempting to handle function arguments as a special case
     * of a lookup, it is instead far simpler to rewrite them such that they
     * just become bindings in the function local scope. This occurs for both
     * explicitly passed argument values, and those that have been defaulted.
     *
     * For each argument, the following algorithm is executed:
     *
     * 1. Argument Conversion: Arguments are converted into their definitions so
     *    as to provide a compact representation of all known information about
     *    that argument.
     * 2. Frame Conversion: A variable slot is created in the function's local
     *    frame to contain the value of the function argument.
     * 3. Read Provision: A `ReadArgumentNode` is generated to allow that
     *    function argument to be treated purely as a local variable access. See
     *    Note [Handling Argument Defaults] for more information on how this
     *    works.
     * 4. Value Assignment: A `AssignmentNode` is created to connect the
     *    argument value to the frame slot created in Step 2.
     * 5. Body Rewriting: The expression representing the argument is written
     *    into the function body, thus allowing it to be read simply.
     */

    /** Generates code for an Enso function application.
      *
      * @param application the function application to generate code for
      * @return the truffle nodes corresponding to `application`
      */
    def processApplication(application: IR.Application): RuntimeExpression =
      application match {
        case IR.Application.Prefix(fn, args, hasDefaultsSuspended, loc, _, _) =>
          val callArgFactory = new CallArgumentProcessor(scope, scopeName)

          val arguments = args
          val callArgs  = new ArrayBuffer[CallArgument]()

          for ((unprocessedArg, position) <- arguments.view.zipWithIndex) {
            val arg = callArgFactory.run(unprocessedArg, position)
            callArgs.append(arg)
          }

          val defaultsExecutionMode = if (hasDefaultsSuspended) {
            InvokeCallableNode.DefaultsExecutionMode.IGNORE
          } else {
            InvokeCallableNode.DefaultsExecutionMode.EXECUTE
          }

          val appNode = application.getMetadata(ApplicationSaturation) match {
            case Some(
                  ApplicationSaturation.CallSaturation.Exact(createOptimised)
                ) =>
              createOptimised(callArgs.toList)
            case _ =>
              ApplicationNode.build(
                this.run(fn),
                callArgs.toArray,
                defaultsExecutionMode
              )
          }

          setLocation(appNode, loc)
        case IR.Application.Force(expr, location, _, _) =>
          setLocation(ForceNode.build(this.run(expr)), location)
        case IR.Application.Literal.Sequence(items, location, _, _) =>
          val itemNodes = items.map(run).toArray
          setLocation(SequenceLiteralNode.build(itemNodes), location)
        case _: IR.Application.Literal.Typeset =>
          setLocation(
            ErrorNode.build(
              context.getBuiltins
                .error()
                .syntaxError()
                .newInstance(
                  "Typeset literals are not yet supported at runtime."
                )
            ),
            application.location
          )
        case op: IR.Application.Operator.Binary =>
          throw new CompilerError(
            s"Explicit operators not supported during codegen but $op found"
          )
        case sec: IR.Application.Operator.Section =>
          throw new CompilerError(
            s"Explicit operator sections not supported during codegen but " +
            s"$sec found"
          )
      }
  }

  // ==========================================================================
  // === Call Argument Processor ==============================================
  // ==========================================================================

  /** Performs codegen for call-site arguments in Enso.
    *
    * @param scope the scope in which the function call exists
    * @param scopeName the name of `scope`
    */
  sealed private class CallArgumentProcessor(
    val scope: LocalScope,
    val scopeName: String
  ) {

    // === Runner =============================================================

    /** Executes codegen on the call-site argument.
      *
      * @param arg the argument definition
      * @param position the position of the argument at the call site
      * @return a truffle construct corresponding to the argument definition
      *         `arg`
      */
    def run(arg: IR.CallArgument, position: Int): CallArgument =
      arg match {
        case IR.CallArgument.Specified(
              name,
              value,
              _,
              shouldBeSuspended,
              _,
              _
            ) =>
          val scopeInfo = arg
            .unsafeGetMetadata(
              AliasAnalysis,
              "No scope attached to a call argument."
            )
            .unsafeAs[AliasAnalysis.Info.Scope.Child]

          val shouldSuspend = shouldBeSuspended.getOrElse(
            throw new CompilerError(
              "Demand analysis information missing from call argument."
            )
          )

          val childScope = if (shouldSuspend) {
            scope.createChild(scopeInfo.scope)
          } else {
            // Note [Scope Flattening]
            scope.createChild(scopeInfo.scope, flattenToParent = true)
          }
          val argumentExpression =
            new ExpressionProcessor(childScope, scopeName).run(value)

          val result = if (!shouldSuspend) {
            argumentExpression
          } else {
            val argExpressionIsTail = value.unsafeGetMetadata(
              TailCall,
              "Argument with missing tail call information."
            )

            argumentExpression.setTail(argExpressionIsTail)

            val displayName =
              s"call_argument<${name.getOrElse(String.valueOf(position))}>"

            val section = value.location
              .map(loc => source.createSection(loc.start, loc.end))
              .orNull

            val callTarget = Truffle.getRuntime.createCallTarget(
              ClosureRootNode.build(
                language,
                childScope,
                moduleScope,
                argumentExpression,
                section,
                displayName,
                null
              )
            )

            CreateThunkNode.build(callTarget)
          }

          new CallArgument(name.map(_.name).orNull, result)
      }
  }

  /* Note [Scope Flattening]
   * ~~~~~~~~~~~~~~~~~~~~~~~
   * Given that we represent _all_ function arguments as thunks at runtime, we
   * account for this during alias analysis by allocating new scopes for the
   * function arguments as they are passed. However, in the case of an argument
   * that is _already_ suspended, we want to pass this directly. However, we do
   * not have demand information at the point of alias analysis, and so we have
   * allocated a new scope for it regardless.
   *
   * As a result, we flatten that scope back into the parent during codegen to
   * work around the differences between the semantic meaning of the language
   * and the runtime representation of function arguments.
   */

  // ==========================================================================
  // === Definition Argument Processor ========================================
  // ==========================================================================

  /** Performs codegen for definition-site arguments in Enso.
    *
    * @param scope the scope in which the function is defined
    * @param scopeName the name of `scope`
    */
  sealed private class DefinitionArgumentProcessor(
    val scopeName: String = "<root>",
    val scope: LocalScope
  ) {

    // === Runner =============================================================

    /* Note [Handling Suspended Defaults]
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * Suspended defaults need to be wrapped in a thunk to ensure that they
     * behave properly with regards to the expected semantics of lazy arguments.
     *
     * Were they not wrapped in a thunk, they would be evaluated eagerly, and
     * hence the point at which the default would be evaluated would differ from
     * the point at which a passed-in argument would be evaluated.
     */

    /** Executes the code generator on the provided definition-site argument.
      *
      * @param inputArg the argument to generate code for
      * @param position the position of `arg` at the function definition site
      * @return a truffle entity corresponding to the definition of `arg` for a
      *         given function
      */
    def run(
      inputArg: IR.DefinitionArgument,
      position: Int
    ): ArgumentDefinition =
      inputArg match {
        case arg: IR.DefinitionArgument.Specified =>
          val defaultExpression = arg.defaultValue
            .map(new ExpressionProcessor(scope, scopeName).run(_))
            .orNull

          // Note [Handling Suspended Defaults]
          val defaultedValue = if (arg.suspended && defaultExpression != null) {
            val defaultRootNode = ClosureRootNode.build(
              language,
              scope,
              moduleScope,
              defaultExpression,
              null,
              s"default::$scopeName::${arg.name}",
              null
            )

            CreateThunkNode.build(
              Truffle.getRuntime.createCallTarget(defaultRootNode)
            )
          } else {
            defaultExpression
          }

          val executionMode = if (arg.suspended) {
            ArgumentDefinition.ExecutionMode.PASS_THUNK
          } else {
            ArgumentDefinition.ExecutionMode.EXECUTE
          }

          new ArgumentDefinition(
            position,
            arg.name.name,
            defaultedValue,
            executionMode
          )
      }
  }
}
