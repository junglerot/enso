package org.enso.compiler.codegen

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.frame.FrameSlot
import com.oracle.truffle.api.source.{Source, SourceSection}
import org.enso.compiler.core.IR
import org.enso.compiler.core.IR.Module.Scope.Import
import org.enso.compiler.core.IR.{Error, IdentifiedLocation, Pattern}
import org.enso.compiler.data.{BindingsMap, CompilerConfig}
import org.enso.compiler.exception.{BadPatternMatch, CompilerError}
import org.enso.compiler.pass.analyse.AliasAnalysis.Graph.{Scope => AliasScope}
import org.enso.compiler.pass.analyse.AliasAnalysis.{Graph => AliasGraph}
import org.enso.compiler.pass.analyse.{
  AliasAnalysis,
  BindingAnalysis,
  DataflowAnalysis,
  TailCall
}
import org.enso.compiler.pass.optimise.ApplicationSaturation
import org.enso.compiler.pass.resolve.{
  ExpressionAnnotations,
  GlobalNames,
  MethodDefinitions,
  Patterns
}
import org.enso.interpreter.epb.EpbParser
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
import org.enso.interpreter.node.controlflow.caseexpr._
import org.enso.interpreter.node.expression.atom.{
  ConstantNode,
  QualifiedAccessorNode
}
import org.enso.interpreter.node.expression.constant._
import org.enso.interpreter.node.expression.foreign.ForeignMethodCallNode
import org.enso.interpreter.node.expression.literal.LiteralNode
import org.enso.interpreter.node.scope.{AssignmentNode, ReadLocalVariableNode}
import org.enso.interpreter.node.{
  BaseNode,
  ClosureRootNode,
  MethodRootNode,
  ExpressionNode => RuntimeExpression
}
import org.enso.interpreter.runtime.Context
import org.enso.interpreter.runtime.callable.{
  UnresolvedConversion,
  UnresolvedSymbol
}
import org.enso.interpreter.runtime.callable.argument.{
  ArgumentDefinition,
  CallArgument
}
import org.enso.interpreter.runtime.callable.atom.Atom
import org.enso.interpreter.runtime.callable.atom.AtomConstructor
import org.enso.interpreter.runtime.callable.function.{
  FunctionSchema,
  Function => RuntimeFunction
}
import org.enso.interpreter.runtime.data.text.Text
import org.enso.interpreter.runtime.scope.{
  FramePointer,
  LocalScope,
  ModuleScope
}
import org.enso.interpreter.{Constants, Language}

import java.math.BigInteger
import org.enso.compiler.core.IR.Name.Special
import org.enso.interpreter.runtime.data.Type

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.jdk.OptionConverters._
import scala.jdk.CollectionConverters._

/** This is an implementation of a codegeneration pass that lowers the Enso
  * [[IR]] into the truffle [[org.enso.compiler.core.Core.Node]] structures that
  * are actually executed.
  *
  * It should be noted that, as is, there is no support for cross-module links,
  * with each lowering pass operating solely on a single module.
  *
  * @param context        the language context instance for which this is executing
  * @param source         the source code that corresponds to the text for which code
  *                       is being generated
  * @param moduleScope    the scope of the module for which code is being generated
  * @param compilerConfig the configuration for the compiler
  */
class IrToTruffle(
  val context: Context,
  val source: Source,
  val moduleScope: ModuleScope,
  val compilerConfig: CompilerConfig
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
    * @param ir         the IR to generate code for
    * @param localScope the scope in which the inline input exists
    * @param scopeName  the name of `localScope`
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
    generateMethods()
    generateReExportBindings(module)
    module
      .unsafeGetMetadata(
        BindingAnalysis,
        "No binding analysis at the point of codegen."
      )
      .resolvedExports
      .foreach { exp =>
        moduleScope.addExport(exp.module.unsafeAsModule().getScope)
      }
    val imports = module.imports
    val methodDefs = module.bindings.collect {
      case method: IR.Module.Scope.Definition.Method.Explicit => method
    }

    // Register the imports in scope
    imports.foreach {
      case poly @ Import.Polyglot(i: Import.Polyglot.Java, _, _, _, _) =>
        this.moduleScope.registerPolyglotSymbol(
          poly.getVisibleName,
          context.getEnvironment.lookupHostSymbol(i.getJavaName)
        )
      case i: Import.Module =>
        this.moduleScope.addImport(
          context.getCompiler.processImport(i.name.name, i.location, source)
        )
      case _: Error =>
    }

    val typeDefs = module.bindings.collect {
      case tp: IR.Module.Scope.Definition.Type => tp
    }

    typeDefs.foreach { tpDef =>
      // Register the atoms and their constructors in scope
      val atomDefs = tpDef.members
      val atomConstructors =
        atomDefs.map(cons => moduleScope.getConstructors.get(cons.name.name))
      atomConstructors
        .zip(atomDefs)
        .foreach { case (atomCons, atomDefn) =>
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
          val localScope = new LocalScope(
            None,
            scopeInfo.graph,
            scopeInfo.graph.rootScope,
            dataflowInfo
          )

          val argFactory =
            new DefinitionArgumentProcessor(
              scope = localScope
            )
          val argDefs =
            new Array[ArgumentDefinition](atomDefn.arguments.size)
          val argumentExpressions =
            new ArrayBuffer[(RuntimeExpression, RuntimeExpression)]

          for (idx <- atomDefn.arguments.indices) {
            val unprocessedArg = atomDefn.arguments(idx)
            val arg            = argFactory.run(unprocessedArg, idx)
            val occInfo = unprocessedArg
              .unsafeGetMetadata(
                AliasAnalysis,
                "No occurrence on an argument definition."
              )
              .unsafeAs[AliasAnalysis.Info.Occurrence]
            val slot = localScope.createVarSlot(occInfo.id)
            argDefs(idx) = arg
            val readArg =
              ReadArgumentNode.build(idx, arg.getDefaultValue.orElse(null))
            val assignmentArg = AssignmentNode.build(readArg, slot)
            val argRead       = ReadLocalVariableNode.build(new FramePointer(0, slot))
            argumentExpressions.append((assignmentArg, argRead))
          }

          val (assignments, reads) = argumentExpressions.unzip
          if (!atomCons.isInitialized) {
            atomCons.initializeFields(
              localScope,
              assignments.toArray,
              reads.toArray,
              argDefs: _*
            )
          }
        }
      val tp = moduleScope.getTypes.get(tpDef.name.name)
      tp.generateGetters(language, atomConstructors.asJava)
    }

    // Register the method definitions in scope
    methodDefs.foreach(methodDef => {
      val scopeInfo = methodDef
        .unsafeGetMetadata(
          AliasAnalysis,
          s"Missing scope information for method " +
          s"`${methodDef.typeName.map(_.name + ".").getOrElse("")}${methodDef.methodName.name}`."
        )
        .unsafeAs[AliasAnalysis.Info.Scope.Root]
      val dataflowInfo = methodDef.unsafeGetMetadata(
        DataflowAnalysis,
        "Method definition missing dataflow information."
      )

      val consOpt =
        methodDef.methodReference.typePointer match {
          case None =>
            Some(moduleScope.getAssociatedType)
          case Some(tpePointer) =>
            tpePointer
              .getMetadata(MethodDefinitions)
              .map { res =>
                res.target match {
                  case BindingsMap.ResolvedType(module, tp) =>
                    module.unsafeAsModule().getScope.getTypes.get(tp.name)
                  case BindingsMap.ResolvedModule(module) =>
                    module.unsafeAsModule().getScope.getAssociatedType
                  case BindingsMap.ResolvedConstructor(_, _) =>
                    throw new CompilerError(
                      "Impossible, should be caught by MethodDefinitions pass"
                    )
                  case BindingsMap.ResolvedPolyglotSymbol(_, _) =>
                    throw new CompilerError(
                      "Impossible polyglot symbol, should be caught by MethodDefinitions pass."
                    )
                  case _: BindingsMap.ResolvedMethod =>
                    throw new CompilerError(
                      "Impossible here, should be caught by MethodDefinitions pass."
                    )
                }
              }
        }

      consOpt.foreach { cons =>
        val expressionProcessor = new ExpressionProcessor(
          cons.getName ++ Constants.SCOPE_SEPARATOR ++ methodDef.methodName.name,
          scopeInfo.graph,
          scopeInfo.graph.rootScope,
          dataflowInfo
        )

        val function = methodDef.body match {
          case fn: IR.Function if isBuiltinMethod(fn.body) =>
            // For builtin types that own the builtin method we only check that
            // the method has been registered during the initialization of builtins
            // and not attempt to register it in the scope (can't redefined methods).
            // For non-builtin types (or modules) that own the builtin method
            // we have to look up the function and register it in the scope.
            val x              = methodDef.body.asInstanceOf[IR.Function.Lambda].body
            val fullMethodName = x.asInstanceOf[IR.Literal.Text]

            val builtinNameElements = fullMethodName.text.split('.')
            if (builtinNameElements.length != 2) {
              throw new CompilerError(
                s"Unknwon builtin method ${fullMethodName.text}"
              )
            }
            val methodName      = builtinNameElements(1)
            val methodOwnerName = builtinNameElements(0)

            val builtinFunction = context.getBuiltins
              .getBuiltinFunction(methodOwnerName, methodName, language)
            builtinFunction.toScala
              .map(Some(_))
              .toRight(
                new CompilerError(
                  s"Unable to find Truffle Node for method ${cons.getName}.${methodDef.methodName.name}"
                )
              )
              .left
              .flatMap(l =>
                // Builtin Types Number and Integer have methods only for documentation purposes
                if (
                  cons == context.getBuiltins.number().getNumber ||
                  cons == context.getBuiltins.number().getInteger
                ) Right(None)
                else Left(l)
              )
              .map(_.filterNot(_ => cons.isBuiltin))
          case fn: IR.Function =>
            val bodyBuilder =
              new expressionProcessor.BuildFunctionBody(fn.arguments, fn.body)
            val rootNode = MethodRootNode.build(
              language,
              expressionProcessor.scope,
              moduleScope,
              () => bodyBuilder.bodyNode(),
              makeSection(moduleScope, methodDef.location),
              cons,
              methodDef.methodName.name
            )
            val callTarget = Truffle.getRuntime.createCallTarget(rootNode)
            val arguments  = bodyBuilder.args()
            Right(
              Some(
                new RuntimeFunction(
                  callTarget,
                  null,
                  new FunctionSchema(arguments: _*)
                )
              )
            )
          case _ =>
            Left(
              new CompilerError(
                "Method bodies must be functions at the point of codegen."
              )
            )
        }
        function match {
          case Left(failure) =>
            throw failure
          case Right(Some(fun)) =>
            moduleScope.registerMethod(cons, methodDef.methodName.name, fun)
          case _ =>
          // Don't register dummy function nodes
        }
      }
    })

    val conversionDefs = module.bindings.collect {
      case conversion: IR.Module.Scope.Definition.Method.Conversion =>
        conversion
    }

    // Register the conversion definitions in scope
    conversionDefs.foreach(methodDef => {
      val scopeInfo = methodDef
        .unsafeGetMetadata(
          AliasAnalysis,
          s"Missing scope information for conversion " +
          s"`${methodDef.typeName.map(_.name + ".").getOrElse("")}${methodDef.methodName.name}`."
        )
        .unsafeAs[AliasAnalysis.Info.Scope.Root]
      val dataflowInfo = methodDef.unsafeGetMetadata(
        DataflowAnalysis,
        "Method definition missing dataflow information."
      )

      val toOpt =
        methodDef.methodReference.typePointer match {
          case Some(tpePointer) =>
            getTypeResolution(tpePointer)
          case None =>
            Some(moduleScope.getAssociatedType)
        }
      val fromOpt = getTypeResolution(methodDef.sourceTypeName)
      toOpt.zip(fromOpt).foreach { case (toType, fromType) =>
        val expressionProcessor = new ExpressionProcessor(
          toType.getName ++ Constants.SCOPE_SEPARATOR ++ methodDef.methodName.name,
          scopeInfo.graph,
          scopeInfo.graph.rootScope,
          dataflowInfo
        )

        val function = methodDef.body match {
          case fn: IR.Function =>
            val bodyBuilder =
              new expressionProcessor.BuildFunctionBody(fn.arguments, fn.body)
            val rootNode = MethodRootNode.build(
              language,
              expressionProcessor.scope,
              moduleScope,
              () => bodyBuilder.bodyNode(),
              makeSection(moduleScope, methodDef.location),
              toType,
              methodDef.methodName.name
            )
            val callTarget = Truffle.getRuntime.createCallTarget(rootNode)
            val arguments  = bodyBuilder.args()
            new RuntimeFunction(
              callTarget,
              null,
              new FunctionSchema(arguments: _*)
            )
          case _ =>
            throw new CompilerError(
              "Conversion bodies must be functions at the point of codegen."
            )
        }
        moduleScope.registerConversionMethod(toType, fromType, function)
      }
    })
  }

  // ==========================================================================
  // === Utility Functions ====================================================
  // ==========================================================================

  /** Checks if the expression has a @Builtin_Method annotation
    *
    * @param expression the expression to check
    * @return 'true' if 'expression' has @Builtin_Method annotation, otherwise 'false'
    */
  private def isBuiltinMethod(expression: IR.Expression): Boolean = {
    expression
      .getMetadata(ExpressionAnnotations)
      .exists(
        _.annotations.exists(_.name == ExpressionAnnotations.builtinMethodName)
      )
  }

  /** Creates a source section from a given location in the module's code.
    *
    * @param module the module that owns/provides the source code
    * @param location the location to turn into a section
    * @return the source section corresponding to `location`
    */
  private def makeSection(
    module: ModuleScope,
    location: Option[IdentifiedLocation]
  ): SourceSection = {
    location
      .map(loc => {
        val m = module.getModule()
        if (m.isModuleSource(source)) {
          module.getModule().createSection(loc.start, loc.length)
        } else {
          source.createSection(loc.start, loc.length)
        }
      })
      .getOrElse(source.createUnavailableSection())
  }

  private def getTypeResolution(expr: IR): Option[Type] =
    expr.getMetadata(MethodDefinitions).map { res =>
      res.target match {
        case BindingsMap.ResolvedType(definitionModule, tp) =>
          definitionModule
            .unsafeAsModule()
            .getScope
            .getTypes
            .get(tp.name)
        case BindingsMap.ResolvedModule(module) =>
          module.unsafeAsModule().getScope.getAssociatedType
        case BindingsMap.ResolvedConstructor(_, _) =>
          throw new CompilerError(
            "Impossible here, should be caught by MethodDefinitions pass."
          )
        case BindingsMap.ResolvedPolyglotSymbol(_, _) =>
          throw new CompilerError(
            "Impossible polyglot symbol, should be caught by MethodDefinitions pass."
          )
        case _: BindingsMap.ResolvedMethod =>
          throw new CompilerError(
            "Impossible here, should be caught by MethodDefinitions pass."
          )
      }
    }

  private def getTailStatus(
    expression: IR.Expression
  ): BaseNode.TailStatus = {
    val isTailPosition =
      expression.getMetadata(TailCall).contains(TailCall.TailPosition.Tail)
    val isTailAnnotated = TailCall.isTailAnnotated(expression)
    if (isTailPosition) {
      if (isTailAnnotated) {
        BaseNode.TailStatus.TAIL_LOOP
      } else {
        BaseNode.TailStatus.TAIL_DIRECT
      }
    } else {
      BaseNode.TailStatus.NOT_TAIL
    }
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

  private def generateMethods(): Unit = {
    generateEnsoProjectMethod()
  }

  private def generateEnsoProjectMethod(): Unit = {
    val name = BindingsMap.Generated.ensoProjectMethodName
    val pkg  = context.getPackageOf(moduleScope.getModule.getSourceFile)
    val body = Truffle.getRuntime.createCallTarget(
      new EnsoProjectNode(language, context, pkg)
    )
    val schema = new FunctionSchema(
      new ArgumentDefinition(
        0,
        Constants.Names.SELF_ARGUMENT,
        ArgumentDefinition.ExecutionMode.EXECUTE
      )
    )
    val fun = new RuntimeFunction(body, null, schema)
    moduleScope.registerMethod(moduleScope.getAssociatedType, name, fun)
  }

  private def generateReExportBindings(module: IR.Module): Unit = {
    def mkConsGetter(constructor: AtomConstructor): RuntimeFunction = {
      new RuntimeFunction(
        Truffle.getRuntime.createCallTarget(
          new QualifiedAccessorNode(language, constructor)
        ),
        null,
        new FunctionSchema(
          new ArgumentDefinition(
            0,
            Constants.Names.SELF_ARGUMENT,
            ArgumentDefinition.ExecutionMode.EXECUTE
          )
        )
      )
    }

    def mkTypeGetter(tp: Type): RuntimeFunction = {
      new RuntimeFunction(
        Truffle.getRuntime.createCallTarget(
          new ConstantNode(language, tp)
        ),
        null,
        new FunctionSchema(
          new ArgumentDefinition(
            0,
            Constants.Names.SELF_ARGUMENT,
            ArgumentDefinition.ExecutionMode.EXECUTE
          )
        )
      )
    }

    val bindingsMap = module.unsafeGetMetadata(
      BindingAnalysis,
      "No binding analysis at the point of codegen."
    )
    bindingsMap.exportedSymbols.foreach {
      case (name, resolution :: _) =>
        if (resolution.module.unsafeAsModule() != moduleScope.getModule) {
          resolution match {
            case BindingsMap.ResolvedType(module, tp) =>
              val runtimeTp =
                module.unsafeAsModule().getScope.getTypes.get(tp.name)
              val fun = mkTypeGetter(runtimeTp)
              moduleScope.registerMethod(
                moduleScope.getAssociatedType,
                name,
                fun
              )
            case BindingsMap.ResolvedConstructor(definitionModule, cons) =>
              val runtimeCons = definitionModule
                .unsafeAsModule()
                .getScope
                .getConstructors
                .get(cons.name)
              val fun = mkConsGetter(runtimeCons)
              moduleScope.registerMethod(
                moduleScope.getAssociatedType,
                name,
                fun
              )
            case BindingsMap.ResolvedModule(module) =>
              val runtimeCons =
                module.unsafeAsModule().getScope.getAssociatedType
              val fun = mkTypeGetter(runtimeCons)
              moduleScope.registerMethod(
                moduleScope.getAssociatedType,
                name,
                fun
              )
            case BindingsMap.ResolvedMethod(module, method) =>
              val actualModule = module.unsafeAsModule()
              val fun = actualModule.getScope.getMethods
                .get(actualModule.getScope.getAssociatedType)
                .get(method.name)
              moduleScope.registerMethod(
                moduleScope.getAssociatedType,
                name,
                fun
              )
            case BindingsMap.ResolvedPolyglotSymbol(_, _) =>
          }
        }
      case _ => throw new CompilerError("Unreachable")
    }
  }

  // ==========================================================================
  // === Expression Processor =================================================
  // ==========================================================================

  /** This class is responsible for performing codegen of [[IR]] constructs that
    * are Enso program expressions.
    *
    * @param scope     the scope in which the code generation is occurring
    * @param scopeName the name of `scope`
    */
  sealed private class ExpressionProcessor(
    val scope: LocalScope,
    val scopeName: String
  ) {

    private var currentVarName = "<anonymous>"

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
    def run(ir: IR.Expression): RuntimeExpression = run(ir, false)

    private def run(ir: IR.Expression, binding: Boolean): RuntimeExpression = {
      val runtimeExpression = ir match {
        case block: IR.Expression.Block     => processBlock(block)
        case literal: IR.Literal            => processLiteral(literal)
        case app: IR.Application            => processApplication(app)
        case name: IR.Name                  => processName(name)
        case function: IR.Function          => processFunction(function, binding)
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

      runtimeExpression.setTailStatus(getTailStatus(ir))
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
          makeSection(moduleScope, block.location),
          currentVarName,
          false,
          false
        )

        val callTarget = Truffle.getRuntime.createCallTarget(defaultRootNode)
        setLocation(CreateThunkNode.build(callTarget), block.location)
      } else {
        val statementExprs = block.expressions.map(this.run).toArray
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
            .makeSyntaxError(
              Text.create(
                "Type operators are not currently supported at runtime."
              )
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

          if (allCasesValid) {
            val cases = maybeCases
              .collect { case Right(x) =>
                x
              }
              .toArray[BranchNode]

            // Note [Pattern Match Fallbacks]
            val matchExpr = CaseNode.build(
              scrutineeNode,
              cases
            )
            setLocation(matchExpr, location)
          } else {
            val invalidBranches = maybeCases.collect { case Left(x) =>
              x
            }

            val message = invalidBranches.map(_.message).mkString(", ")

            val error = context.getBuiltins
              .error()
              .makeCompileError(Text.create(message))

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

      val childProcessor = this.createChild("case_branch", scopeInfo.scope)

      branch.pattern match {
        case named @ Pattern.Name(_, _, _, _) =>
          val arg = List(genArgFromMatchField(named))

          val branchCodeNode = childProcessor.processFunctionBody(
            arg,
            branch.expression,
            branch.location
          )

          val branchNode =
            CatchAllBranchNode.build(branchCodeNode.getCallTarget)

          Right(branchNode)
        case cons @ Pattern.Constructor(constructor, _, _, _, _) =>
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
            branch.location
          )

          constructor match {
            case err: IR.Error.Resolution =>
              Left(BadPatternMatch.NonVisibleConstructor(err.name))
            case _ =>
              constructor.getMetadata(Patterns) match {
                case None =>
                  Left(BadPatternMatch.NonVisibleConstructor(constructor.name))
                case Some(
                      BindingsMap.Resolution(BindingsMap.ResolvedModule(mod))
                    ) =>
                  Right(
                    ObjectEqualityBranchNode.build(
                      branchCodeNode.getCallTarget,
                      mod.unsafeAsModule().getScope.getAssociatedType
                    )
                  )
                case Some(
                      BindingsMap.Resolution(
                        BindingsMap.ResolvedConstructor(mod, cons)
                      )
                    ) =>
                  val atomCons =
                    mod.unsafeAsModule().getScope.getConstructors.get(cons.name)
                  val r = if (atomCons == context.getBuiltins.bool().getTrue) {
                    BooleanBranchNode.build(true, branchCodeNode.getCallTarget)
                  } else if (atomCons == context.getBuiltins.bool().getFalse) {
                    BooleanBranchNode.build(false, branchCodeNode.getCallTarget)
                  } else {
                    ConstructorBranchNode.build(
                      atomCons,
                      branchCodeNode.getCallTarget
                    )
                  }
                  Right(r)
                case Some(
                      BindingsMap.Resolution(BindingsMap.ResolvedType(mod, tp))
                    ) =>
                  val tpe =
                    mod.unsafeAsModule().getScope.getTypes.get(tp.name)
                  val any         = context.getBuiltins.any
                  val array       = context.getBuiltins.array
                  val vector      = context.getBuiltins.vector
                  val date        = context.getBuiltins.date
                  val dateTime    = context.getBuiltins.dateTime
                  val timeOfDay   = context.getBuiltins.timeOfDay
                  val timeZone    = context.getBuiltins.timeZone
                  val file        = context.getBuiltins.file
                  val builtinBool = context.getBuiltins.bool.getType
                  val number      = context.getBuiltins.number
                  val polyglot    = context.getBuiltins.polyglot
                  val text        = context.getBuiltins.text
                  val branch = if (tpe == builtinBool) {
                    BooleanConstructorBranchNode.build(
                      builtinBool,
                      branchCodeNode.getCallTarget
                    )
                  } else if (tpe == text) {
                    TextBranchNode.build(text, branchCodeNode.getCallTarget)
                  } else if (tpe == number.getInteger) {
                    IntegerBranchNode.build(
                      number,
                      branchCodeNode.getCallTarget
                    )
                  } else if (tpe == number.getDecimal) {
                    DecimalBranchNode.build(tpe, branchCodeNode.getCallTarget)
                  } else if (tpe == number.getNumber) {
                    NumberBranchNode.build(number, branchCodeNode.getCallTarget)
                  } else if (tpe == array) {
                    ArrayBranchNode.build(tpe, branchCodeNode.getCallTarget)
                  } else if (tpe == vector) {
                    VectorBranchNode.build(tpe, branchCodeNode.getCallTarget)
                  } else if (tpe == date) {
                    DateBranchNode.build(tpe, branchCodeNode.getCallTarget)
                  } else if (tpe == dateTime) {
                    DateTimeBranchNode.build(tpe, branchCodeNode.getCallTarget)
                  } else if (tpe == timeOfDay) {
                    TimeOfDayBranchNode.build(tpe, branchCodeNode.getCallTarget)
                  } else if (tpe == timeZone) {
                    TimeZoneBranchNode.build(tpe, branchCodeNode.getCallTarget)
                  } else if (tpe == file) {
                    FileBranchNode.build(tpe, branchCodeNode.getCallTarget)
                  } else if (tpe == polyglot) {
                    PolyglotBranchNode.build(tpe, branchCodeNode.getCallTarget)
                  } else if (tpe == any) {
                    CatchAllBranchNode.build(branchCodeNode.getCallTarget)
                  } else {
                    ObjectEqualityBranchNode.build(
                      branchCodeNode.getCallTarget,
                      tpe
                    )
                  }
                  Right(branch)
                case Some(
                      BindingsMap.Resolution(
                        BindingsMap.ResolvedPolyglotSymbol(_, _)
                      )
                    ) =>
                  throw new CompilerError(
                    "Impossible polyglot symbol here, should be caught by Patterns resolution pass."
                  )
                case Some(
                      BindingsMap.Resolution(
                        BindingsMap.ResolvedMethod(_, _)
                      )
                    ) =>
                  throw new CompilerError(
                    "Impossible method here, should be caught by Patterns resolution pass."
                  )
              }
          }
        case literalPattern: Pattern.Literal =>
          val branchCodeNode = childProcessor.processFunctionBody(
            Nil,
            branch.expression,
            branch.location
          )

          literalPattern.literal match {
            case num: IR.Literal.Number =>
              num.numericValue match {
                case doubleVal: Double =>
                  Right(
                    NumericLiteralBranchNode.build(
                      doubleVal,
                      branchCodeNode.getCallTarget
                    )
                  )
                case longVal: Long =>
                  Right(
                    NumericLiteralBranchNode.build(
                      longVal,
                      branchCodeNode.getCallTarget
                    )
                  )
                case bigIntVal: BigInteger =>
                  Right(
                    NumericLiteralBranchNode.build(
                      bigIntVal,
                      branchCodeNode.getCallTarget
                    )
                  )
                case _ =>
                  throw new CompilerError(
                    "Invalid literal numeric value"
                  )
              }
            case text: IR.Literal.Text =>
              Right(
                StringLiteralBranchNode.build(
                  text.text,
                  branchCodeNode.getCallTarget
                )
              )
          }
        case _: Pattern.Documentation =>
          throw new CompilerError(
            "Branch documentation should be desugared at an earlier stage."
          )
        case IR.Error.Pattern(
              _,
              IR.Error.Pattern.WrongArity(name, expected, actual),
              _,
              _
            ) =>
          Left(BadPatternMatch.WrongArgCount(name, expected, actual))

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
        AssignmentNode.build(this.run(binding.expression, true), slot),
        binding.location
      )
    }

    /** Generates code for an Enso function.
      *
      * @param function the function to generate code for
      * @param binding whether the function is right inside a binding
      * @return the truffle nodes corresponding to `function`
      */
    private def processFunction(
      function: IR.Function,
      binding: Boolean
    ): RuntimeExpression = {
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
        binding
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
        case IR.Name.Literal(nameStr, _, _, _, _) =>
          val useInfo = name
            .unsafeGetMetadata(
              AliasAnalysis,
              "No occurrence on variable usage."
            )
            .unsafeAs[AliasAnalysis.Info.Occurrence]

          val slot   = scope.getFramePointer(useInfo.id)
          val global = name.getMetadata(GlobalNames)
          if (slot.isDefined) {
            ReadLocalVariableNode.build(slot.get)
          } else if (global.isDefined) {
            val resolution = global.get.target
            resolution match {
              case tp: BindingsMap.ResolvedType =>
                ConstantObjectNode.build(
                  tp.module.unsafeAsModule().getScope.getTypes.get(tp.tp.name)
                )
              case BindingsMap.ResolvedConstructor(definitionModule, cons) =>
                val c = definitionModule
                  .unsafeAsModule()
                  .getScope
                  .getConstructors
                  .get(cons.name)
                if (c == null) {
                  throw new CompilerError(s"Constructor for $cons is null")
                }
                ConstructorNode.build(c)
              case BindingsMap.ResolvedModule(module) =>
                ConstantObjectNode.build(
                  module.unsafeAsModule().getScope.getAssociatedType
                )
              case BindingsMap.ResolvedPolyglotSymbol(module, symbol) =>
                ConstantObjectNode.build(
                  module
                    .unsafeAsModule()
                    .getScope
                    .getPolyglotSymbols
                    .get(symbol.name)
                )
              case BindingsMap.ResolvedMethod(_, method) =>
                throw new CompilerError(
                  s"Impossible here, ${method.name} should be caught when translating application"
                )
            }
          } else if (nameStr == Constants.Names.FROM_MEMBER) {
            ConstantObjectNode.build(UnresolvedConversion.build(moduleScope))
          } else {
            DynamicSymbolNode.build(
              UnresolvedSymbol.build(nameStr, moduleScope)
            )
          }
        case IR.Name.Self(location, _, passData, _) =>
          processName(
            IR.Name.Literal(
              Constants.Names.SELF_ARGUMENT,
              isMethod = false,
              location,
              passData
            )
          )
        case IR.Name.Special(name, _, _, _) =>
          val fun = name match {
            case Special.NewRef    => context.getBuiltins.special().getNewRef
            case Special.ReadRef   => context.getBuiltins.special().getReadRef
            case Special.WriteRef  => context.getBuiltins.special().getWriteRef
            case Special.RunThread => context.getBuiltins.special().getRunThread
            case Special.JoinThread =>
              context.getBuiltins.special().getJoinThread
          }
          ConstantObjectNode.build(fun)
        case _: IR.Name.Annotation =>
          throw new CompilerError(
            "Annotation should not be present at codegen time."
          )
        case _: IR.Name.Blank =>
          throw new CompilerError(
            "Blanks should not be present at codegen time."
          )
        case _: IR.Name.MethodReference =>
          throw new CompilerError(
            "Method references should not be present at codegen time."
          )
        case _: IR.Name.Qualified =>
          throw new CompilerError(
            "Qualified names should not be present at codegen time."
          )
        case err: IR.Error.Resolution => processError(err)
        case err: IR.Error.Conversion => processError(err)
      }

      setLocation(nameExpr, name.location)
    }

    /** Generates code for an Enso literal.
      *
      * @param literal the literal to generate code for
      * @return the truffle nodes corresponding to `literal`
      */
    @throws[CompilerError]
    def processLiteral(literal: IR.Literal): RuntimeExpression =
      literal match {
        case lit @ IR.Literal.Number(_, _, location, _, _) =>
          val node = lit.numericValue match {
            case l: Long       => LiteralNode.build(l)
            case d: Double     => LiteralNode.build(d)
            case b: BigInteger => LiteralNode.build(b)
          }
          setLocation(node, location)
        case IR.Literal.Text(text, location, _, _) =>
          setLocation(LiteralNode.build(text), location)
      }

    /** Generates a runtime implementation for compile error nodes.
      *
      * @param error the IR representing a compile error.
      * @return a runtime node representing the error.
      */
    def processError(error: IR.Error): RuntimeExpression = {
      val payload: Atom = error match {
        case Error.InvalidIR(_, _, _) =>
          throw new CompilerError("Unexpected Invalid IR during codegen.")
        case err: Error.Syntax =>
          context.getBuiltins
            .error()
            .makeSyntaxError(Text.create(err.message))
        case err: Error.Redefined.Binding =>
          context.getBuiltins
            .error()
            .makeCompileError(Text.create(err.message))
        case err: Error.Redefined.Method =>
          context.getBuiltins
            .error()
            .makeCompileError(Text.create(err.message))
        case err: Error.Redefined.MethodClashWithAtom =>
          context.getBuiltins
            .error()
            .makeCompileError(Text.create(err.message))
        case err: Error.Redefined.Conversion =>
          context.getBuiltins
            .error()
            .makeCompileError(Text.create(err.message))
        case err: Error.Redefined.Type =>
          context.getBuiltins
            .error()
            .makeCompileError(Text.create(err.message))
        case err: Error.Redefined.SelfArg =>
          context.getBuiltins
            .error()
            .makeCompileError(Text.create(err.message))
        case err: Error.Unexpected.TypeSignature =>
          context.getBuiltins
            .error()
            .makeCompileError(Text.create(err.message))
        case err: Error.Resolution =>
          context.getBuiltins
            .error()
            .makeCompileError(Text.create(err.message))
        case err: Error.Conversion =>
          context.getBuiltins
            .error()
            .makeCompileError(Text.create(err.message))
        case _: Error.Pattern =>
          throw new CompilerError(
            "Impossible here, should be handled in the pattern match."
          )
        case _: Error.ImportExport =>
          throw new CompilerError(
            "Impossible here, should be handled in import/export processing"
          )
      }
      setLocation(ErrorNode.build(payload), error.location)
    }

    /** Processes function arguments, generates arguments reads and creates
      * a node to represent the whole method body.
      *
      * @param arguments the argument definitions
      * @param body      the body definition
      * @return a node for the final shape of function body and pre-processed
      *         argument definitions.
      */
    class BuildFunctionBody(
      val arguments: List[IR.DefinitionArgument],
      val body: IR.Expression
    ) {
      private val argFactory = new DefinitionArgumentProcessor(scopeName, scope)
      private lazy val slots = computeSlots()
      private lazy val bodyN = computeBodyNode()

      def args(): Array[ArgumentDefinition] = slots._2
      def bodyNode(): BlockNode             = bodyN

      private def computeBodyNode(): BlockNode = {
        val (argSlots, _, argExpressions) = slots

        val bodyExpr = body match {
          case IR.Foreign.Definition(lang, code, _, _, _) =>
            buildForeignBody(
              lang,
              code,
              arguments.map(_.name.name),
              argSlots
            )
          case _ => ExpressionProcessor.this.run(body)
        }
        BlockNode.build(argExpressions.toArray, bodyExpr)
      }

      private def computeSlots(): (
        List[FrameSlot],
        Array[ArgumentDefinition],
        ArrayBuffer[RuntimeExpression]
      ) = {
        val seenArgNames   = mutable.Set[String]()
        val argDefinitions = new Array[ArgumentDefinition](arguments.size)
        val argExpressions = new ArrayBuffer[RuntimeExpression]
        // Note [Rewriting Arguments]
        val argSlots = arguments.zipWithIndex.map {
          case (unprocessedArg, idx) =>
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
              throw new IllegalStateException(
                s"A duplicate argument name, $argName, was found during codegen."
              )
            } else seenArgNames.add(argName)
            slot
        }
        (argSlots, argDefinitions, argExpressions)
      }
    }

    private def buildForeignBody(
      language: EpbParser.ForeignLanguage,
      code: String,
      argumentNames: List[String],
      argumentSlots: List[FrameSlot]
    ): RuntimeExpression = {
      val src = EpbParser.buildSource(language, code, scopeName)
      val foreignCt = context.getEnvironment
        .parseInternal(src, argumentNames: _*)
      val argumentReaders = argumentSlots
        .map(slot => ReadLocalVariableNode.build(new FramePointer(0, slot)))
        .toArray[RuntimeExpression]
      ForeignMethodCallNode.build(argumentReaders, foreignCt)
    }

    /** Generates code for an Enso function body.
      *
      * @param arguments the arguments to the function
      * @param body      the body of the function
      * @param location  the location at which the function exists in the source
      * @param binding whether the function is right inside a binding
      * @return a truffle node representing the described function
      */
    def processFunctionBody(
      arguments: List[IR.DefinitionArgument],
      body: IR.Expression,
      location: Option[IdentifiedLocation],
      binding: Boolean = false
    ): CreateFunctionNode = {
      val bodyBuilder = new BuildFunctionBody(arguments, body)
      val fnRootNode = ClosureRootNode.build(
        language,
        scope,
        moduleScope,
        bodyBuilder.bodyNode(),
        makeSection(moduleScope, location),
        scopeName,
        false,
        binding
      )
      val callTarget = Truffle.getRuntime.createCallTarget(fnRootNode)

      val expr = CreateFunctionNode.build(callTarget, bodyBuilder.args())

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
        case IR.Application.Prefix(fn, Nil, true, _, _, _) =>
          run(fn)
        case app: IR.Application.Prefix =>
          processApplicationWithArgs(app)
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
                .makeSyntaxError(
                  Text.create(
                    "Typeset literals are not yet supported at runtime."
                  )
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

    private def processApplicationWithArgs(
      application: IR.Application.Prefix
    ): RuntimeExpression = {
      val IR.Application.Prefix(fn, args, hasDefaultsSuspended, loc, _, _) =
        application
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
          createOptimised(moduleScope)(scope)(callArgs.toList)
        case _ =>
          ApplicationNode.build(
            this.run(fn),
            callArgs.toArray,
            defaultsExecutionMode
          )
      }

      setLocation(appNode, loc)
    }

  }

  // ==========================================================================
  // === Call Argument Processor ==============================================
  // ==========================================================================

  /** Performs codegen for call-site arguments in Enso.
    *
    * @param scope     the scope in which the function call exists
    * @param scopeName the name of `scope`
    */
  sealed private class CallArgumentProcessor(
    val scope: LocalScope,
    val scopeName: String
  ) {

    // === Runner =============================================================

    /** Executes codegen on the call-site argument.
      *
      * @param arg      the argument definition
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
              _,
              _
            ) =>
          val scopeInfo = arg
            .unsafeGetMetadata(
              AliasAnalysis,
              "No scope attached to a call argument."
            )
            .unsafeAs[AliasAnalysis.Info.Scope.Child]

          val shouldSuspend = value match {
            case _: IR.Name           => false
            case _: IR.Literal.Text   => false
            case _: IR.Literal.Number => false
            case _                    => true
          }

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
            argumentExpression.setTailStatus(getTailStatus(value))

            val displayName =
              s"argument<${name.map(_.name).getOrElse(String.valueOf(position))}>"

            val section = value.location
              .map(loc => source.createSection(loc.start, loc.length))
              .orNull

            val callTarget = Truffle.getRuntime.createCallTarget(
              ClosureRootNode.build(
                language,
                childScope,
                moduleScope,
                argumentExpression,
                section,
                displayName,
                true,
                false
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
    * @param scope     the scope in which the function is defined
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
              s"<default::$scopeName::${arg.name}>",
              false,
              false
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
