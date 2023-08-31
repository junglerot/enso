package org.enso.compiler.test.semantic

import org.enso.compiler.core.ir.{Module, Warning}
import org.enso.compiler.core.ir.expression.errors
import org.enso.compiler.core.ir.module.scope.Import
import org.enso.compiler.data.BindingsMap
import org.enso.compiler.pass.analyse.BindingAnalysis
import org.enso.interpreter.runtime
import org.enso.interpreter.runtime.EnsoContext
import org.enso.pkg.QualifiedName
import org.enso.polyglot.{LanguageInfo, MethodNames, RuntimeOptions}
import org.graalvm.polyglot.{Context, Engine}
import org.scalatest.BeforeAndAfter
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.io.ByteArrayOutputStream
import java.nio.file.Paths

/** Tests a single package with multiple modules for import/export resolution.
  * Checks whether the exported symbols and defined entities metadata of the modules
  * are correct.
  */
class ImportExportTest
    extends AnyWordSpecLike
    with Matchers
    with BeforeAndAfter {
  private val out = new ByteArrayOutputStream()

  private val engine = Engine
    .newBuilder("enso")
    .allowExperimentalOptions(true)
    .build()

  private val ctx = Context
    .newBuilder(LanguageInfo.ID)
    .engine(engine)
    .allowExperimentalOptions(true)
    .allowAllAccess(true)
    .allowCreateThread(false)
    .out(out)
    .err(out)
    .option(RuntimeOptions.LOG_LEVEL, "WARNING")
    .option(RuntimeOptions.DISABLE_IR_CACHES, "true")
    .logHandler(System.err)
    .option(
      RuntimeOptions.LANGUAGE_HOME_OVERRIDE,
      Paths
        .get("../../test/micro-distribution/component")
        .toFile
        .getAbsolutePath
    )
    .option(RuntimeOptions.EDITION_OVERRIDE, "0.0.0-dev")
    .build()

  private val langCtx: EnsoContext = ctx
    .getBindings(LanguageInfo.ID)
    .invokeMember(MethodNames.TopScope.LEAK_CONTEXT)
    .asHostObject[EnsoContext]()

  private val namespace   = "my_pkg"
  private val packageName = "My_Package"
  private val packageQualifiedName =
    QualifiedName.fromString(namespace + "." + packageName)

  langCtx.getPackageRepository.registerSyntheticPackage(namespace, packageName)

  implicit private class CreateModule(moduleCode: String) {
    def createModule(moduleName: QualifiedName): runtime.Module = {
      val module = new runtime.Module(moduleName, null, moduleCode)
      langCtx.getPackageRepository.registerModuleCreatedInRuntime(module)
      langCtx.getCompiler.run(module)
      module
    }
  }

  implicit private class UnwrapBindingMap(moduleIr: Module) {
    def unwrapBindingMap: BindingsMap = {
      moduleIr.unsafeGetMetadata(BindingAnalysis, "Should be present")
    }
  }

  before {
    ctx.enter()
  }

  after {
    ctx.leave()
    out.reset()
  }

  "Import resolution with just two modules" should {
    "resolve one import symbol from a module" in {
      val moduleCode =
        """
          |type Other_Module_Type
          |    Constructor
          |""".stripMargin
      val moduleIr = moduleCode
        .createModule(
          packageQualifiedName.createChild("Other_Module")
        )
        .getIr
      moduleIr.unwrapBindingMap.definedEntities.size shouldEqual 1
      moduleIr.unwrapBindingMap.definedEntities.head.name shouldEqual "Other_Module_Type"
      val otherTypeDefinedEntity =
        moduleIr.unwrapBindingMap.definedEntities.head
          .asInstanceOf[BindingsMap.Type]
      otherTypeDefinedEntity.members.size shouldEqual 1
      otherTypeDefinedEntity.members.head.name shouldEqual "Constructor"

      val mainCode =
        s"""
           |from $namespace.$packageName.Other_Module import Other_Module_Type
           |
           |main = Other_Module_Type.Constructor
           |""".stripMargin
      val mainIr =
        mainCode.createModule(packageQualifiedName.createChild("Main")).getIr
      mainIr.imports.size shouldEqual 1
      mainIr.imports.head match {
        case importErr: errors.ImportExport =>
          fail(
            s"Import should be resolved, but instead produced errors.ImportExport with ${importErr.reason.message}"
          )
        case _ => ()
      }
      val mainBindingsMap = mainIr.unwrapBindingMap
      mainBindingsMap.resolvedImports.size shouldEqual 1
      mainBindingsMap
        .resolvedImports(0)
        .target
        .isInstanceOf[BindingsMap.ResolvedModule] shouldBe true
    }

    "resolve two types from a module" in {
      val moduleIr =
        """
          |type Other_Module_Type
          |type Another_Type
          |""".stripMargin
          .createModule(packageQualifiedName.createChild("Other_Module"))
          .getIr
      moduleIr.unwrapBindingMap.definedEntities.size shouldEqual 2
      moduleIr.unwrapBindingMap
        .definedEntities(0)
        .name shouldEqual "Other_Module_Type"
      moduleIr.unwrapBindingMap
        .definedEntities(1)
        .name shouldEqual "Another_Type"

      val mainIr =
        s"""
           |from $namespace.$packageName.Other_Module import Other_Module_Type, Another_Type
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr
      mainIr.unwrapBindingMap.resolvedImports.size shouldEqual 1
      mainIr.unwrapBindingMap
        .resolvedImports(0)
        .target
        .isInstanceOf[BindingsMap.ResolvedModule] shouldBe true
    }

    "result in error when importing mixture of existing and non-existing symbols" in {
      """
        |type Existing_Type
        |    Constructor
        |""".stripMargin
        .createModule(packageQualifiedName.createChild("Other_Module"))
      val mainIr =
        s"""
           |from $namespace.$packageName.Other_Module import Existing_Type, Non_Existing_Symbol
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr
      mainIr.imports.size shouldEqual 1
      mainIr.imports.head.isInstanceOf[errors.ImportExport] shouldBe true
    }

    "result in error when importing a method from type" in {
      """
        |type Other_Type
        |    method self = 42
        |""".stripMargin
        .createModule(packageQualifiedName.createChild("Other_Module"))
      val mainIr =
        s"""
           |from $namespace.$packageName.Other_Module.Other_Type import method
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr
      mainIr.imports.size shouldEqual 1
      mainIr.imports.head
        .asInstanceOf[errors.ImportExport]
        .reason
        .asInstanceOf[
          errors.ImportExport.NoSuchConstructor
        ] shouldEqual errors.ImportExport
        .NoSuchConstructor("Other_Type", "method")
    }

    "result in multiple errors when importing more methods from type" in {
      """
        |type Other_Type
        |    method self = 42
        |""".stripMargin
        .createModule(packageQualifiedName.createChild("Other_Module"))
      val mainIr =
        s"""
           |from $namespace.$packageName.Other_Module.Other_Type import method, other_method
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr
      mainIr.imports
        .take(2)
        .map(_.asInstanceOf[errors.ImportExport].reason) shouldEqual List(
        errors.ImportExport.NoSuchConstructor("Other_Type", "method"),
        errors.ImportExport.NoSuchConstructor("Other_Type", "other_method")
      )
    }

    // TODO[pm]: will be addressed in https://github.com/enso-org/enso/issues/6729
    "resolve static method from a module" ignore {
      """
        |static_method =
        |    42
        |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))
      val bIr =
        s"""
           |import $namespace.$packageName.A_Module.static_method
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("B_Module"))
          .getIr
      val mainIr =
        s"""
           |from $namespace.$packageName.A_Module import static_method
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr

      mainIr.imports.head.isInstanceOf[errors.ImportExport] shouldBe false
      bIr.imports.head.isInstanceOf[errors.ImportExport] shouldBe false
      val mainBindingMap = mainIr.unwrapBindingMap
      val bBindingMap    = bIr.unwrapBindingMap
      mainBindingMap.resolvedImports.size shouldEqual 2
      mainBindingMap
        .resolvedImports(0)
        .target
        .asInstanceOf[BindingsMap.ResolvedModule]
        .module
        .getName
        .item shouldEqual "A_Module"
      mainBindingMap
        .resolvedImports(1)
        .target
        .asInstanceOf[BindingsMap.ResolvedMethod]
        .method
        .name shouldEqual "static_method"
      // In B_Module, we only have ResolvedMethod in the resolvedImports, there is no ResolvedModule
      // But that does not matter.
      bBindingMap.resolvedImports.size shouldEqual 1
      bBindingMap
        .resolvedImports(0)
        .target
        .asInstanceOf[BindingsMap.ResolvedMethod]
        .method
        .name shouldEqual "static_method"
    }

    "result in error when trying to import mix of constructors and methods from a type" in {
      """
        |type Other_Module_Type
        |    Constructor_1
        |    Constructor_2 val1 val2
        |    method self = 42
        |""".stripMargin
        .createModule(packageQualifiedName.createChild("Other_Module"))
      val mainIr =
        s"""
           |from $namespace.$packageName.Other_Module.Other_Module_Type import Constructor_1, method, Constructor_2, non_existing_method
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr
      mainIr.imports
        .take(2)
        .map(_.asInstanceOf[errors.ImportExport].reason) shouldEqual List(
        errors.ImportExport.NoSuchConstructor("Other_Module_Type", "method"),
        errors.ImportExport
          .NoSuchConstructor("Other_Module_Type", "non_existing_method")
      )
    }

    "result in error when trying to import all from a non-type" in {
      """
        |static_method =
        |    42
        |""".stripMargin
        .createModule(packageQualifiedName.createChild("Other_Module"))
      val mainIr =
        s"""
           |from $namespace.$packageName.Other_Module.static_method import all
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr
      mainIr.imports.head
        .asInstanceOf[errors.ImportExport]
        .reason
        .asInstanceOf[errors.ImportExport.ModuleDoesNotExist]
        .name should include("static_method")
    }

    "result in error when trying to import anything from a non-existing symbol" in {
      """
        |# Left blank on purpose
        |""".stripMargin
        .createModule(packageQualifiedName.createChild("Other_Module"))
      val mainIr =
        s"""
           |from $namespace.$packageName.Other_Module.Non_Existing_Symbol import all
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr
      mainIr.imports.head
        .asInstanceOf[errors.ImportExport]
        .reason
        .asInstanceOf[errors.ImportExport.ModuleDoesNotExist]
        .name should include("Non_Existing_Symbol")
    }

    // TODO[pm]: Fix in https://github.com/enso-org/enso/issues/6669
    "resolve all symbols from transitively exported type" ignore {
      """
        |type A_Type
        |    A_Constructor
        |    a_method self = 1
        |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))
      s"""
         |import $namespace.$packageName.A_Module.A_Type
         |export $namespace.$packageName.A_Module.A_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("B_Module"))
      val mainIr =
        s"""
           |from $namespace.$packageName.B_Module.A_Type import all
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr
      mainIr.imports.size shouldEqual 1
      mainIr.imports.head.isInstanceOf[errors.ImportExport] shouldBe false
      val mainBindingMap = mainIr.unwrapBindingMap
      val resolvedImportTargets =
        mainBindingMap.resolvedImports.map(_.target)
      resolvedImportTargets
        .collect { case c: BindingsMap.ResolvedConstructor => c }
        .map(_.cons.name) should contain theSameElementsAs List("A_Constructor")
    }

    // TODO[pm]: Fix in https://github.com/enso-org/enso/issues/6669
    "resolve constructor from transitively exported type" ignore {
      """
        |type A_Type
        |    A_Constructor
        |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))
      s"""
         |import $namespace.$packageName.A_Module.A_Type
         |export $namespace.$packageName.A_Module.A_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("B_Module"))
      val mainIr =
        s"""
           |from $namespace.$packageName.B_Module.A_Type import A_Constructor
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr
      mainIr.imports.size shouldEqual 1
      mainIr.imports.head.isInstanceOf[errors.ImportExport] shouldBe false
      val mainBindingMap = mainIr.unwrapBindingMap
      val resolvedImportTargets =
        mainBindingMap.resolvedImports.map(_.target)
      resolvedImportTargets
        .collect { case c: BindingsMap.ResolvedConstructor => c }
        .map(_.cons.name) should contain theSameElementsAs List("A_Constructor")
    }

    "export is not transitive" in {
      s"""
         |import $namespace.$packageName.A_Module.A_Type
         |export $namespace.$packageName.A_Module.A_Type
         |
         |type A_Type
         |    a_method self = 1
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))
      s"""
         |import $namespace.$packageName.A_Module.A_Type
         |
         |type B_Type
         |    b_method self = 2
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("B_Module"))
      val mainModule =
        s"""
           |from $namespace.$packageName.B_Module import A_Type
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))

      mainModule.getIr.imports.head
        .isInstanceOf[errors.ImportExport] shouldBe true
      mainModule.getIr.imports.head
        .asInstanceOf[errors.ImportExport]
        .reason
        .asInstanceOf[errors.ImportExport.SymbolDoesNotExist]
        .symbolName shouldEqual "A_Type"
    }
  }

  "Import resolution from another library honor Main" should {
    "resolve Api from Main" in {
      val mainIr = """
                     |from Test.Logical_Export import Api
                     |
                     |main =
                     |    element = Api.Element.Element.create
                     |    element.describe
                     |""".stripMargin
        .createModule(packageQualifiedName.createChild("Main"))
        .getIr

      mainIr.imports.size shouldEqual 1
      val in = mainIr.imports.head
        .asInstanceOf[Import.Module]

      in.name.name.toString() should include("Test.Logical_Export.Main")
      in.onlyNames.get.map(_.name.toString()) shouldEqual List("Api")

      val errors = mainIr.preorder.filter(x => x.isInstanceOf[Error])
      errors.size shouldEqual 0
    }

    "don't expose Impl from Main" in {
      val mainIr = """
                     |from Test.Logical_Export import Impl
                     |
                     |main = Impl
                     |""".stripMargin
        .createModule(packageQualifiedName.createChild("Main"))
        .getIr

      mainIr.imports.head
        .asInstanceOf[errors.ImportExport]
        .reason
        .message should include(
        "The symbol Impl (module or type) does not exist in module Test.Logical_Export.Main."
      )
    }
  }

  "Ambiguous symbol resolution" should {
    "generate warning when importing same type twice with different import statements" in {
      s"""
         |type A_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))
      val mainIr =
        s"""
           |import $namespace.$packageName.A_Module.A_Type
           |from $namespace.$packageName.A_Module import A_Type
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main_Module"))
          .getIr
      mainIr.imports.size shouldEqual 2
      val origImport = mainIr.imports(0)
      val warn = mainIr
        .imports(1)
        .diagnostics
        .collect({ case w: Warning.DuplicatedImport => w })
      warn.size shouldEqual 1
      warn.head.originalImport shouldEqual origImport
      warn.head.symbolName shouldEqual "A_Type"
      warn.head.originalImport shouldEqual origImport
    }

    "generate warning when importing same type twice with one-symbol import and all-symbol import" in {
      s"""
         |type A_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))
      val mainIr =
        s"""
           |import $namespace.$packageName.A_Module.A_Type
           |from $namespace.$packageName.A_Module import all
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main_Module"))
          .getIr
      mainIr.imports.size shouldEqual 2
      val origImport = mainIr.imports(0)
      val warn = mainIr
        .imports(1)
        .diagnostics
        .collect({ case w: Warning.DuplicatedImport => w })
      warn.size shouldEqual 1
      warn.head.originalImport shouldEqual origImport
      warn.head.symbolName shouldEqual "A_Type"
      warn.head.originalImport shouldEqual origImport
    }

    "generate warning when importing same type twice with two all-symbol imports" in {
      s"""
         |type A_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))
      val mainIr =
        s"""
           |from $namespace.$packageName.A_Module import all
           |from $namespace.$packageName.A_Module import all
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main_Module"))
          .getIr
      mainIr.imports.size shouldEqual 2
      val origImport = mainIr.imports(0)
      val warn = mainIr
        .imports(1)
        .diagnostics
        .collect({ case w: Warning.DuplicatedImport => w })
      warn.size shouldEqual 1
      warn.head.originalImport shouldEqual origImport
      warn.head.symbolName shouldEqual "A_Type"
      warn.head.originalImport shouldEqual origImport
    }

    "generate two warnings when importing same type twice with two all-symbol imports" in {
      s"""
         |type A_Type
         |type AA_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))
      val mainIr =
        s"""
           |from $namespace.$packageName.A_Module import all
           |from $namespace.$packageName.A_Module import all
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main_Module"))
          .getIr
      mainIr.imports.size shouldEqual 2
      val origImport = mainIr.imports(0)
      val warn = mainIr
        .imports(1)
        .diagnostics
        .collect({ case w: Warning.DuplicatedImport => w })
      warn.size shouldEqual 2
      warn.foreach(_.originalImport shouldEqual origImport)
      warn.exists(_.symbolName == "A_Type") shouldEqual true
      warn.exists(_.symbolName == "AA_Type") shouldEqual true
    }

    "work when importing two different types with hiding" in {
      s"""
         |type A_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))
      s"""
         |type A_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("B_Module"))
      val mainIr =
        s"""
           |from $namespace.$packageName.A_Module import all hiding A_Type
           |from $namespace.$packageName.B_Module import all
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main_Module"))
          .getIr
      mainIr.imports.size shouldEqual 2
      mainIr.imports.foreach(
        _.isInstanceOf[errors.ImportExport] shouldEqual false
      )
    }

    "result in error when importing same type twice with two one-symbol import and renamed import" in {
      s"""
         |type A_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))
      s"""
         |type B_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("B_Module"))
      val mainIr =
        s"""
           |from $namespace.$packageName.A_Module import A_Type
           |import $namespace.$packageName.B_Module.B_Type as A_Type
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main_Module"))
          .getIr
      mainIr.imports.size shouldEqual 2
      val origImport = mainIr.imports(0)
      val ambiguousImport = mainIr
        .imports(1)
        .asInstanceOf[errors.ImportExport]
        .reason
        .asInstanceOf[errors.ImportExport.AmbiguousImport]
      ambiguousImport.symbolName shouldEqual "A_Type"
      ambiguousImport.originalImport shouldEqual origImport
    }

    "generate warning when importing same type twice in one import statement" in {
      s"""
         |type A_Type
         |static_method = 42
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))
      val mainIr =
        s"""
           |from $namespace.$packageName.A_Module import A_Type, static_method, A_Type
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main_Module"))
          .getIr
      val warns = mainIr
        .imports(0)
        .diagnostics
        .collect({ case w: Warning.DuplicatedImport => w })
      warns.size shouldEqual 1
      warns.head.symbolName shouldEqual "A_Type"
    }

    "generate error when importing different type twice with one-symbol import and renamed polyglot import" in {
      s"""
         |type A_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))
      val mainIr =
        s"""
           |from $namespace.$packageName.A_Module import A_Type
           |polyglot java import org.enso.example.TestClass as A_Type
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main_Module"))
          .getIr
      mainIr.imports.size shouldEqual 2
      val origImport = mainIr.imports(0)
      val ambiguousImport = mainIr
        .imports(1)
        .asInstanceOf[errors.ImportExport]
        .reason
        .asInstanceOf[errors.ImportExport.AmbiguousImport]
      ambiguousImport.symbolName shouldEqual "A_Type"
      ambiguousImport.originalImport shouldEqual origImport
    }

    "generate warning when importing same type twice with two renamed polyglot imports" in {
      s"""
         |type A_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))
      val mainIr =
        s"""
           |polyglot java import org.enso.example.TestClass as A_Type
           |polyglot java import org.enso.example.TestClass as A_Type
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main_Module"))
          .getIr
      mainIr.imports.size shouldEqual 2
      val origImport = mainIr.imports(0)
      val warns = mainIr
        .imports(1)
        .diagnostics
        .collect({ case w: Warning.DuplicatedImport => w })
      warns.size shouldEqual 1
      warns.head.symbolName shouldEqual "A_Type"
      warns.head.originalImport shouldEqual origImport
    }

    "result in error when importing different polyglot types with two renamed polyglot imports" in {
      val mainIr =
        s"""
           |polyglot java import org.enso.example.TestClass as A_Type
           |polyglot java import org.enso.example.TestClass.StaticInnerClass as A_Type
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main_Module"))
          .getIr
      mainIr.imports.size shouldEqual 2
      val origImport = mainIr.imports(0)
      val ambiguousImport = mainIr
        .imports(1)
        .asInstanceOf[errors.ImportExport]
        .reason
        .asInstanceOf[errors.ImportExport.AmbiguousImport]
      ambiguousImport.symbolName shouldEqual "A_Type"
      ambiguousImport.originalImport shouldEqual origImport
    }

    "generate warnings when importing same type multiple times with one-symbol import statements" in {
      s"""
         |type A_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))
      val mainIr =
        s"""
           |import $namespace.$packageName.A_Module.A_Type
           |import $namespace.$packageName.A_Module.A_Type
           |import $namespace.$packageName.A_Module.A_Type
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main_Module"))
          .getIr
      mainIr.imports.size shouldEqual 3
      val origImport = mainIr.imports(0)
      val allWarns =
        mainIr.imports.flatMap(_.diagnostics.collect({
          case w: Warning.DuplicatedImport => w
        }))
      allWarns.size shouldEqual 2
      allWarns.foreach(_.symbolName shouldEqual "A_Type")
      allWarns.foreach(_.originalImport shouldEqual origImport)
    }

    "generate warnings when importing same type multiple times with import-all statements" in {
      s"""
         |type A_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))
      val mainIr =
        s"""
           |from $namespace.$packageName.A_Module import all
           |from $namespace.$packageName.A_Module import all
           |from $namespace.$packageName.A_Module import all
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main_Module"))
          .getIr
      mainIr.imports.size shouldEqual 3
      val origImport = mainIr.imports(0)
      val allWarns =
        mainIr.imports.flatMap(_.diagnostics.collect({
          case w: Warning.DuplicatedImport => w
        }))
      allWarns.size shouldEqual 2
      allWarns.foreach(_.symbolName shouldEqual "A_Type")
      allWarns.foreach(_.originalImport shouldEqual origImport)
    }
  }

  "Import resolution for three modules" should {

    "not resolve symbol that is not explicitly exported" in {
      """
        |type A_Type
        |    A_Constructor
        |    instance_method self = 42
        |
        |static_method =
        |    local_var = 42
        |    local_var
        |
        |# Is not really a variable - it is a method returning a constant, so
        |# it is also considered a static module method
        |glob_var = 42
        |
        |# This is also a static method
        |foreign js js_function = \"\"\"
        |    return 42
        |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))

      s"""
         |from $namespace.$packageName.A_Module import all
         |from $namespace.$packageName.A_Module export static_method
         |
         |type B_Type
         |    B_Constructor val
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("B_Module"))
        .getIr

      val mainIr =
        s"""
           |from $namespace.$packageName.B_Module import A_Type
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr
      mainIr.imports.head.isInstanceOf[errors.ImportExport] shouldBe true
      mainIr.imports.head
        .asInstanceOf[errors.ImportExport]
        .reason
        .message should include("A_Type")
    }

    "resolve all symbols (types and static module methods) from the module" in {
      s"""
         |type A_Type
         |    A_Constructor
         |    instance_method self = 42
         |
         |static_method =
         |    local_var = 42
         |    local_var
         |
         |# Is not really a variable - it is a method returning a constant, so
         |# it is also considered a static module method
         |glob_var = 42
         |
         |# This is also a static method
         |foreign js js_function = \"\"\"
         |    return 42
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))

      s"""
         |from $namespace.$packageName.A_Module import all
         |from $namespace.$packageName.A_Module export static_method
         |
         |type B_Type
         |    B_Constructor val
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("B_Module"))
        .getIr

      val mainIr =
        s"""
           |from $namespace.$packageName.A_Module import all
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr
      mainIr.imports.size shouldEqual 1
      mainIr.imports.head.isInstanceOf[errors.ImportExport] shouldBe false
    }

    "resolve re-exported symbol" in {
      """
        |type A_Type
        |    A_Constructor
        |    instance_method self = 42
        |
        |static_method =
        |    local_var = 42
        |    local_var
        |
        |# Is not really a variable - it is a method returning a constant, so
        |# it is also considered a static module method
        |glob_var = 42
        |
        |# This is also a static method
        |foreign js js_function = \"\"\"
        |    return 42
        |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))

      s"""
         |from $namespace.$packageName.A_Module import all
         |from $namespace.$packageName.A_Module export static_method
         |
         |type B_Type
         |    B_Constructor val
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("B_Module"))
        .getIr

      val mainIr =
        s"""
           |from $namespace.$packageName.B_Module import static_method
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr
      mainIr.imports.size shouldEqual 1
      mainIr.imports.head.isInstanceOf[errors.ImportExport] shouldBe false
    }

    "resolve re-exported symbol along with all other symbols" in {
      """
        |type A_Type
        |    A_Constructor
        |    instance_method self = 42
        |
        |static_method =
        |    local_var = 42
        |    local_var
        |
        |# Is not really a variable - it is a method returning a constant, so
        |# it is also considered a static module method
        |glob_var = 42
        |
        |# This is also a static method
        |foreign js js_function = \"\"\"
        |    return 42
        |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))

      s"""
         |from $namespace.$packageName.A_Module import all
         |from $namespace.$packageName.A_Module export static_method
         |
         |type B_Type
         |    B_Constructor val
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("B_Module"))
        .getIr

      val mainIr =
        s"""
           |from $namespace.$packageName.B_Module import all
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr
      mainIr.imports.size shouldEqual 1
      mainIr.imports.head.isInstanceOf[errors.ImportExport] shouldBe false
    }
  }
}
