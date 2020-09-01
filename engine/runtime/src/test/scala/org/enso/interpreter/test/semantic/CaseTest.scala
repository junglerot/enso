package org.enso.interpreter.test.semantic

import org.enso.interpreter.test.{
  InterpreterTest,
  InterpreterContext,
  InterpreterException
}

class CaseTest extends InterpreterTest {
  override def subject = "Case expressions"

  override def specify(
    implicit interpreterContext: InterpreterContext
  ): Unit = {
    "result in an error if the matched constructor isn't visible" in {
      val code =
        """
          |from Builtins import all
          |
          |main =
          |    x = Cons a Nil
          |    case x of
          |        Cons2 a b -> a + b
          |        Nil2 -> 0
          |""".stripMargin

      val msg =
        "Compile_Error Cons2 is not visible in this scope, Nil2 is not visible in this scope"
      the[InterpreterException] thrownBy eval(code) should have message msg
    }

    "result in an error if the wrong number of fields are provided" in {
      val code =
        """
          |from Builtins import all
          |
          |main =
          |    x = Cons a Nil
          |    case x of
          |        Cons a -> a
          |""".stripMargin

      val msg = "Compile_Error Cannot match on Cons using 1 field (expecting 2)"
      the[InterpreterException] thrownBy eval(code) should have message msg
    }
  }
}
