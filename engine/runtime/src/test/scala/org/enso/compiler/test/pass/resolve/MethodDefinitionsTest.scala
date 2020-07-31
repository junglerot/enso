package org.enso.compiler.test.pass.resolve

import org.enso.compiler.Passes
import org.enso.compiler.context.{FreshNameSupply, ModuleContext}
import org.enso.compiler.core.IR
import org.enso.compiler.data.BindingsMap
import org.enso.compiler.data.BindingsMap.Cons
import org.enso.compiler.pass.resolve.MethodDefinitions
import org.enso.compiler.pass.{PassConfiguration, PassGroup, PassManager}
import org.enso.compiler.test.CompilerTest

class MethodDefinitionsTest extends CompilerTest {

  // === Test Setup ===========================================================

  def mkModuleContext: ModuleContext =
    buildModuleContext(
      freshNameSupply = Some(new FreshNameSupply)
    )

  val passes = new Passes

  val precursorPasses: PassGroup =
    passes.getPrecursors(MethodDefinitions).get

  val passConfiguration: PassConfiguration = PassConfiguration()

  implicit val passManager: PassManager =
    new PassManager(List(precursorPasses), passConfiguration)

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
      MethodDefinitions.runModule(ir, context)
    }
  }

  // === The Tests ============================================================

  "Method definition resolution" should {
    implicit val ctx: ModuleContext = mkModuleContext

    val ir =
      """
        |type Foo a b c
        | 
        |Foo.my_method a b c = a + b + c
        |
        |my_method = 10
        |
        |Test_Module.other_method = 11
        |
        |Does_Not_Exist.method = 32
        |""".stripMargin.preprocessModule.analyse

    "attach resolved atoms to the method definitions" in {
      ir.bindings(1)
        .asInstanceOf[IR.Module.Scope.Definition.Method.Explicit]
        .methodReference
        .typePointer
        .getMetadata(MethodDefinitions) shouldEqual Some(
        BindingsMap.Resolution(
          BindingsMap.ResolvedConstructor(
            ctx.module,
            Cons("Foo", 3)
          )
        )
      )
      ir.bindings(2)
        .asInstanceOf[IR.Module.Scope.Definition.Method.Explicit]
        .methodReference
        .typePointer
        .getMetadata(MethodDefinitions) shouldEqual Some(
        BindingsMap.Resolution(
          BindingsMap.ResolvedModule(ctx.module)
        )
      )
      ir.bindings(3)
        .asInstanceOf[IR.Module.Scope.Definition.Method.Explicit]
        .methodReference
        .typePointer
        .getMetadata(MethodDefinitions) shouldEqual Some(
        BindingsMap.Resolution(
          BindingsMap.ResolvedModule(ctx.module)
        )
      )
      ir.bindings(4)
        .asInstanceOf[IR.Module.Scope.Definition.Method.Explicit]
        .methodReference
        .typePointer shouldBe a[IR.Error.Resolution]
    }
  }
}
