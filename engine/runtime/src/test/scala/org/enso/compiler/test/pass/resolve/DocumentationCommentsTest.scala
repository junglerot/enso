package org.enso.compiler.test.pass.resolve

import org.enso.compiler.Passes
import org.enso.compiler.context.{FreshNameSupply, InlineContext, ModuleContext}
import org.enso.compiler.core.Implicits.AsMetadata
import org.enso.compiler.core.IR
import org.enso.compiler.core.ir.Expression
import org.enso.compiler.core.ir.Function
import org.enso.compiler.core.ir.Module
import org.enso.compiler.core.ir.expression.{Case, Operator}
import org.enso.compiler.core.ir.module.scope.Definition
import org.enso.compiler.core.ir.module.scope.definition
import org.enso.compiler.pass.resolve.DocumentationComments
import org.enso.compiler.pass.{PassConfiguration, PassManager}
import org.enso.compiler.test.CompilerTest
import org.scalatest.Inside

class DocumentationCommentsTest extends CompilerTest with Inside {

  // === Test Setup ===========================================================

  val passConfig: PassConfiguration = PassConfiguration()

  implicit val passManager: PassManager =
    new PassManager(List(), passConfig)

  /** Resolves documentation comments in a module.
    *
    * @param ir the module
    */
  implicit class ResolveModule(ir: Module) {

    /** Resolves documentation comments for [[ir]].
      *
      * @param moduleContext the context in which to resolve
      * @return [[ir]], with documentation resolved
      */
    def resolve(implicit moduleContext: ModuleContext): Module = {
      DocumentationComments.runModule(ir, moduleContext)
    }
  }

  /** Resolves documentation comments in an expression.
    *
    * @param ir the expression
    */
  implicit class ResolveExpression(ir: Expression) {

    /** Resolves documentation comments for [[ir]].
      *
      * @param inlineContext the context in which to resolve
      * @return [[ir]], with documentation resolved
      */
    def resolve(implicit inlineContext: InlineContext): Expression = {
      DocumentationComments.runExpression(ir, inlineContext)
    }
  }

  /** Creates a defaulted module context.
    *
    * @return a defaulted module context
    */
  def mkModuleContext: ModuleContext = {
    buildModuleContext()
  }

  /** Creates a defaulted inline context.
    *
    * @return a defaulted inline context
    */
  def mkInlineContext: InlineContext = {
    buildInlineContext()
  }

  /** Gets documentation metadata from a node.
    * Throws an exception if missing.
    *
    * @param ir the ir to get the doc from.
    * @return the doc assigned to `ir`.
    */
  def getDoc(ir: IR): String = {
    val meta = ir.getMetadata(DocumentationComments)
//    meta.shouldBe(defined)
    meta.get.documentation
  }

  // === The Tests ============================================================

  "Documentation comments in the top scope" should {
    implicit val moduleContext: ModuleContext = mkModuleContext
    val ir =
      """
        |## My module documentation
        |
        |## This is doc for My_Atom
        |type My_Atom a b c
        |
        |## This is doc for my_method
        |MyAtom.my_method x = x + this
        |
        |""".stripMargin.preprocessModule.resolve

    "be associated with atoms and methods" in {
      ir.bindings.length shouldEqual 2
      ir.bindings.head shouldBe an[Definition.SugaredType]
      ir.bindings(1) shouldBe an[definition.Method]

      getDoc(ir.bindings.head) shouldEqual " This is doc for My_Atom"
      getDoc(ir.bindings(1)) shouldEqual " This is doc for my_method"
    }

    "be associated with modules" in {
      getDoc(ir) shouldEqual " My module documentation"
    }

    "not be associated with modules when not the first entity" in {
      implicit val moduleContext: ModuleContext = mkModuleContext
      val ir =
        """from Standard.Base import all
          |
          |## My module documentation
          |
          |## This is doc for My_Atom
          |type My_Atom a b c
          |
          |## This is doc for my_method
          |MyAtom.my_method x = x + this
          |""".stripMargin.preprocessModule.resolve

      ir.getMetadata(DocumentationComments) should not be defined
    }

    "be associated with modules when ambiguous" in {
      implicit val moduleContext: ModuleContext = mkModuleContext
      val ir =
        """## My module documentation
          |type My_Atom a b c
          |
          |## This is doc for my_method
          |MyAtom.my_method x = x + this
          |""".stripMargin.preprocessModule.resolve

      getDoc(ir) shouldEqual " My module documentation"

      val atom = ir.bindings.head
      atom.getMetadata(DocumentationComments) should not be defined
    }

    "treat imports as breakers" in {
      implicit val moduleContext: ModuleContext = mkModuleContext
      val ir =
        """import Foo.Bar.Baz
          |
          |## My module documentation
          |
          |## This is doc for My_Atom
          |type My_Atom a b c
          |
          |## This is doc for my_method
          |MyAtom.my_method x = x + this
          |""".stripMargin.preprocessModule.resolve

      ir.getMetadata(DocumentationComments) should not be defined
    }

    "treat polyglot imports as breakers" in {
      implicit val moduleContext: ModuleContext = mkModuleContext
      val ir =
        """polyglot java import java.util.Random
          |
          |## My module documentation
          |
          |## This is doc for My_Atom
          |type My_Atom a b c
          |
          |## This is doc for my_method
          |MyAtom.my_method x = x + this
          |""".stripMargin.preprocessModule.resolve

      ir.getMetadata(DocumentationComments) should not be defined
    }

    "treat exports as breakers" in {
      implicit val moduleContext: ModuleContext = mkModuleContext
      val ir =
        """export My_Atom
          |
          |## My module documentation
          |
          |## This is doc for My_Atom
          |type My_Atom a b c
          |
          |## This is doc for my_method
          |MyAtom.my_method x = x + this
          |""".stripMargin.preprocessModule.resolve

      ir.getMetadata(DocumentationComments) should not be defined
    }
  }

  "Documentation comments in blocks" should {
    "be associated with the documented expression in expression flow" in {
      implicit val inlineContext: InlineContext = mkInlineContext
      val ir =
        """
          |x -> y ->
          |    ## Do thing
          |    x + y
          |    ## Do another thing
          |    z = x * y
          |""".stripMargin.preprocessExpression.get.resolve
      val body = ir
        .asInstanceOf[Function.Lambda]
        .body
        .asInstanceOf[Function.Lambda]
        .body
        .asInstanceOf[Expression.Block]

      body.expressions.length shouldEqual 1
      getDoc(body.expressions.head) shouldEqual " Do thing"
      getDoc(body.returnValue) shouldEqual " Do another thing"
    }

    "be associated with the documented expression in module flow" in {
      implicit val moduleContext: ModuleContext = mkModuleContext
      val ir =
        """
          |method x =
          |    ## Do thing
          |    x + y
          |    ## Do another thing
          |    z = x * y
          |""".stripMargin.preprocessModule.resolve
      val body = ir.bindings.head
        .asInstanceOf[definition.Method.Binding]
        .body
        .asInstanceOf[Expression.Block]

      body.expressions.length shouldEqual 1
      getDoc(body.expressions.head) shouldEqual " Do thing"
      getDoc(body.returnValue) shouldEqual " Do another thing"
    }

    "be associated with the type ascriptions" in {
      implicit val moduleContext: ModuleContext = mkModuleContext
      val ir =
        """
          |method x =
          |    ## Id
          |    f : Any -> Any
          |    f x = x
          |
          |    ## Return thing
          |    f 1
          |""".stripMargin.preprocessModule.resolve
      val body = ir.bindings.head
        .asInstanceOf[definition.Method.Binding]
        .body
        .asInstanceOf[Expression.Block]

      body.expressions.length shouldEqual 2
      body.expressions.head shouldBe an[Operator.Binary]
      getDoc(body.expressions.head) shouldEqual " Id"
      getDoc(body.returnValue) shouldEqual " Return thing"
    }
  }

  "Documentation in complex type definitions" should {
    implicit val moduleContext: ModuleContext = mkModuleContext
    "assign docs to all entities" in {
      val ir =
        """## My Module documentation
          |
          |## the type Foo
          |type Foo
          |    ## the constructor Bar
          |    type Bar
          |
          |    ## the included Unit
          |    Unit
          |
          |    ## a method
          |    foo x =
          |        ## a statement
          |        IO.println "foo"
          |        ## the return
          |        0
          |""".stripMargin.preprocessModule.resolve
      val tp =
        ir.bindings.head.asInstanceOf[Definition.SugaredType]
      getDoc(tp) shouldEqual " the type Foo"
      val t1 = tp.body.head
      getDoc(t1) shouldEqual " the constructor Bar"
      val t2 = tp.body(1)
      getDoc(t2) shouldEqual " the included Unit"
      val method = tp.body(2).asInstanceOf[Function.Binding]
      getDoc(method) shouldEqual " a method"
      val block = method.body.asInstanceOf[Expression.Block]
      getDoc(block.expressions.head) shouldEqual " a statement"
      getDoc(block.returnValue) shouldEqual " the return"
    }
  }

  "Documentation" should {
    "be preserved after rewriting" in {
      implicit val passManager: PassManager =
        new Passes(defaultConfig).passManager
      implicit val moduleContext: ModuleContext =
        buildModuleContext(freshNameSupply = Some(new FreshNameSupply))

      val module =
        """## Module docs
          |
          |## The foo
          |foo : Integer
          |foo = 42""".stripMargin.preprocessModule
      val foo = module.bindings.head
      getDoc(foo) shouldEqual " The foo"
    }

    "be preserved after rewriting for all entities" in {
      implicit val passManager: PassManager =
        new Passes(defaultConfig).passManager

      implicit val moduleContext: ModuleContext =
        buildModuleContext(freshNameSupply = Some(new FreshNameSupply))

      val ir =
        """## Module Docs
          |
          |## the type Foo
          |type Foo
          |    ## the constructor Bar
          |    Bar
          |
          |    ## a method
          |    foo : Any -> Any
          |    foo x =
          |        ## a statement
          |        IO.println "foo"
          |        ## the return
          |        0
          |
          |    f = case _ of
          |        ## case 1
          |        Bar -> 100
          |        ## catchall
          |        _ -> 50
          |""".stripMargin.preprocessModule

      val t1 = ir.bindings.head
      getDoc(t1) shouldEqual " the type Foo"
      inside(ir.bindings(1)) { case method: definition.Method.Explicit =>
        getDoc(method) shouldEqual " a method"
        inside(method.body) { case lambda: Function.Lambda =>
          inside(lambda.body) { case block: Expression.Block =>
            getDoc(block.expressions.head) shouldEqual " a statement"
            getDoc(block.returnValue) shouldEqual " the return"
          }
        }
      }

      inside(ir.bindings(2)) { case method: definition.Method.Explicit =>
        inside(method.body) { case lambda: Function.Lambda =>
          inside(lambda.body) { case block: Expression.Block =>
            inside(block.returnValue) { case caseExpr: Case.Expr =>
              caseExpr.branches should have length 2
              getDoc(caseExpr.branches.head) shouldEqual " case 1"
              getDoc(caseExpr.branches(1)) shouldEqual " catchall"
            }
          }
        }
      }
    }
  }
}
