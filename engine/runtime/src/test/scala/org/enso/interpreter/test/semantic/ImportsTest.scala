package org.enso.interpreter.test.semantic

import org.enso.interpreter.test.{InterpreterException, PackageTest}

class ImportsTest extends PackageTest {
  "Atoms and methods" should "be available for import" in {
    evalTestProject("TestSimpleImports") shouldEqual 20
  }

  "Methods defined together with atoms" should "be visible even if not imported" in {
    evalTestProject("TestNonImportedOwnMethods") shouldEqual 10
  }

  "Overloaded methods" should "not be visible when not imported" in {
    the[InterpreterException] thrownBy evalTestProject(
      "TestNonImportedOverloads"
    ) should have message "Method `method` of X could not be found."
  }

  "Import statements" should "report errors when they cannot be resolved" in {
    the[InterpreterException] thrownBy evalTestProject(
      "Test_Bad_Imports"
    ) should have message "Compilation aborted due to errors."
    val outLines = consumeOut
    outLines(2) should include(
      "Package containing the module Surely_This.Does_Not_Exist.My_Module " +
      "could not be loaded: The package could not be resolved: The library " +
      "`Surely_This.Does_Not_Exist` is not defined within the edition."
    )
    outLines(3) should include(
      "The module Enso_Test.Test_Bad_Imports.Oopsie does not exist."
    )
  }

  "Symbols from imported modules" should "not be visible when imported qualified" in {
    the[InterpreterException] thrownBy evalTestProject(
      "Test_Qualified_Error"
    ) should have message "Compilation aborted due to errors."
    consumeOut
      .filterNot(_.contains("Compiler encountered"))
      .filterNot(_.contains("In module"))
      .head should include("The name `X` could not be found.")
  }

  "Symbols from imported modules" should "not be visible when hidden" in {
    the[InterpreterException] thrownBy evalTestProject(
      "Test_Hiding_Error"
    ) should have message "Compilation aborted due to errors."
    consumeOut
      .filterNot(_.contains("Compiler encountered"))
      .filterNot(_.contains("In module"))
      .head should include("The name `X` could not be found.")
  }

  "Symbols from imported modules" should "be visible even when others are hidden" in {
    evalTestProject("Test_Hiding_Success") shouldEqual 20
  }

  "Imported modules" should "be renamed with renaming imports" in {
    evalTestProject("Test_Rename") shouldEqual 20
  }

  "Imported modules" should "not be visible under original name when renamed" in {
    the[InterpreterException] thrownBy evalTestProject(
      "Test_Rename_Error"
    ) should have message "Compilation aborted due to errors."
    consumeOut
      .filterNot(_.contains("Compiler encountered"))
      .filterNot(_.contains("In module"))
      .head should include("The name `Atom` could not be found.")

  }

  "Exports system" should "detect cycles" in {
    the[InterpreterException] thrownBy (evalTestProject(
      "Cycle_Test"
    )) should have message "Compilation aborted due to errors."
    consumeOut should contain("Export statements form a cycle:")
  }

  "Import statements" should "allow for importing submodules" in {
    evalTestProject("TestSubmodules") shouldEqual 42
    val outLines = consumeOut
    outLines(0) shouldEqual "(Foo 10)"
    outLines(1) shouldEqual "(C 52)"
    outLines(2) shouldEqual "20"
    outLines(3) shouldEqual "(C 10)"
  }

  "Compiler" should "detect name conflicts preventing users from importing submodules" in {
    the[InterpreterException] thrownBy (evalTestProject(
      "TestSubmodulesNameConflict"
    )) should have message "Method `c_mod_method` of C could not be found."
    val outLines = consumeOut
    outLines(2) should include
    "Declaration of type C shadows module local.TestSubmodulesNameConflict.A.B.C making it inaccessible via a qualified name."
  }
}
