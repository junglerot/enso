package org.enso.compiler.test.pass.resolve

import org.enso.compiler.Passes
import org.enso.compiler.context.{FreshNameSupply, ModuleContext}
import org.enso.compiler.core.IR
import org.enso.compiler.data.BindingsMap.{
//  Cons,
  Resolution,
//  ResolvedConstructor,
  ResolvedModule
}
import org.enso.compiler.pass.resolve.GlobalNames
import org.enso.compiler.pass.{PassConfiguration, PassGroup, PassManager}
import org.enso.compiler.phase.ExportsResolution
import org.enso.compiler.test.CompilerTest
import org.enso.interpreter.runtime.Module
import org.enso.interpreter.runtime.ModuleTestUtils

class GlobalNamesTest extends CompilerTest {

  // === Test Setup ===========================================================

  def mkModuleContext: (ModuleContext, Module) =
    buildModuleContextModule(
      freshNameSupply = Some(new FreshNameSupply)
    )

  val passes = new Passes(defaultConfig)

  val group1 = passes.moduleDiscoveryPasses
  val group2 = new PassGroup(
    passes.globalTypingPasses.passes ++
    passes.functionBodyPasses.passes.takeWhile(_ != GlobalNames)
  )

  val passConfiguration: PassConfiguration = PassConfiguration()

  implicit val passManager: PassManager =
    new PassManager(List(group1, group2), passConfiguration)

  /** Adds an extension method to analyse an Enso module.
    *
    * @param ir the ir to analyse
    */
  implicit class AnalyseModule(ir: IR.Module) {

    /** Performs tail call analysis on [[ir]].
      *
      * @param context the module context in which analysis takes place
      * @return [[ir]], with tail call analysis metadata attached
      */
    def analyse(implicit context: ModuleContext) = {
      GlobalNames.runModule(ir, context)
    }
  }

  // === The Tests ============================================================

  "Method definition resolution" should {
    val both: (ModuleContext, Module) = mkModuleContext
    implicit val ctx: ModuleContext   = both._1

    val code         = """
                 |main =
                 |    x1 = My_Cons 1 2 3
                 |    x2 = Constant
                 |    x3 = constant
                 |    x4 = Add_One 1
                 |    x5 = add_one 1
                 |    y = add_one
                 |    x6 = y 1
                 |    x7 = Test_Module.My_Cons 1 2 3
                 |    x8 = Does_Not_Exist 32
                 |    0
                 |
                 |type My
                 |    My_Cons a b c
                 |
                 |constant = 2
                 |
                 |add_one x = x + 1
                 |
                 |""".stripMargin
    val parsed       = code.toIrModule
    val moduleMapped = passManager.runPassesOnModule(parsed, ctx, group1)
    ModuleTestUtils.unsafeSetIr(both._2, moduleMapped)

    new ExportsResolution().run(List(both._2))
    val allPrecursors = passManager.runPassesOnModule(moduleMapped, ctx, group2)
    val ir            = allPrecursors.analyse

    val bodyExprs = ir
      .bindings(0)
      .asInstanceOf[IR.Module.Scope.Definition.Method.Explicit]
      .body
      .asInstanceOf[IR.Function.Lambda]
      .body
      .asInstanceOf[IR.Expression.Block]
      .expressions
      .map(expr => expr.asInstanceOf[IR.Expression.Binding].expression)

    "not resolve uppercase method names to applications with no arguments" in {
      val expr = bodyExprs(1)
      expr shouldBe an[IR.Error.Resolution]
    }

    "resolve method names to applications" in {
      val expr = bodyExprs(2)
      expr shouldBe an[IR.Application.Prefix]
      val app = expr.asInstanceOf[IR.Application.Prefix]
      app.function.asInstanceOf[IR.Name.Literal].name shouldEqual "constant"
      app.arguments.length shouldEqual 1
      app.arguments(0).value.getMetadata(GlobalNames) shouldEqual Some(
        Resolution(ResolvedModule(ctx.moduleReference()))
      )
    }

    "not resolve uppercase method names to applications with arguments" in {
      val expr = bodyExprs(3)
      expr shouldBe an[IR.Application.Prefix]
      val app = expr.asInstanceOf[IR.Application.Prefix]
      app.function shouldBe an[IR.Error.Resolution]
    }

    "resolve method names in applications by adding the self argument" in {
      val expr = bodyExprs(4)
      expr shouldBe an[IR.Application.Prefix]
      val app = expr.asInstanceOf[IR.Application.Prefix]
      app.function.asInstanceOf[IR.Name.Literal].name shouldEqual "add_one"
      app.arguments.length shouldEqual 2
      app.arguments(0).value.getMetadata(GlobalNames) shouldEqual Some(
        Resolution(ResolvedModule(ctx.moduleReference()))
      )
    }

    "resolve method names in partial applications by adding the self argument" in {
      val expr = bodyExprs(5)
      expr shouldBe an[IR.Application.Prefix]
      val app = expr.asInstanceOf[IR.Application.Prefix]
      app.function.asInstanceOf[IR.Name.Literal].name shouldEqual "add_one"
      app.arguments.length shouldEqual 1
      app.arguments(0).value.getMetadata(GlobalNames) shouldEqual Some(
        Resolution(ResolvedModule(ctx.moduleReference()))
      )
    }

    "indicate resolution failures" in {
      val app = bodyExprs(8).asInstanceOf[IR.Application.Prefix]
      app.function shouldBe an[IR.Error.Resolution]
    }
  }

  "Undefined names" should {
    "be detected and reported" in {
      implicit val ctx: ModuleContext = mkModuleContext._1

      val ir =
        """
          |my_func (fn = foobar) (w = fn) =
          |    x = [1, 2, y]
          |    x.map (+ 5)
          |    x.fold .to_json
          |    x + z
          |""".stripMargin.preprocessModule.analyse
      val unresolved = ir.preorder.collect {
        case IR.Error.Resolution(
              name,
              _: IR.Error.Resolution.ResolverError,
              _,
              _
            ) =>
          name
      }
      unresolved.map(_.name) shouldEqual List("foobar", "y", "z")
    }

    "should include here" in {
      implicit val ctx: ModuleContext = mkModuleContext._1

      val ir =
        """
          |my_func a = 10 + a
          |
          |main =
          |    here.my_func 1
          |""".stripMargin.preprocessModule.analyse
      val unresolved = ir.preorder.collect {
        case IR.Error.Resolution(
              name,
              _: IR.Error.Resolution.ResolverError,
              _,
              _
            ) =>
          name
      }
      unresolved.map(_.name) shouldEqual List("here")
    }
  }
}
