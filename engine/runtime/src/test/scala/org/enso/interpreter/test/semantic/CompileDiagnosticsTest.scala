package org.enso.interpreter.test.semantic

import org.enso.interpreter.test.{InterpreterContext, InterpreterTest}

class CompileDiagnosticsTest extends InterpreterTest {
  override def subject: String = "Compile Error Reporting"

  override def specify(implicit
    interpreterContext: InterpreterContext
  ): Unit = {
    "surface ast-processing errors in the language" in {
      val code =
        """from Standard.Base.Error.Common import all
          |
          |main =
          |    x = Panic.catch_primitive () .convert_to_dataflow_error
          |    x.catch_primitive err->
          |        case err of
          |            Syntax_Error msg -> "Oopsie, it's a syntax error: " + msg
          |""".stripMargin
      eval(
        code
      ) shouldEqual "Oopsie, it's a syntax error: Parentheses can't be empty."
    }

    "surface parsing errors in the language" in {
      val code =
        """from Standard.Base.Error.Common import all
          |
          |main =
          |    x = Panic.catch_primitive @ caught_panic-> caught_panic.payload
          |    x.to_text
          |""".stripMargin
      eval(code) shouldEqual "(Syntax_Error 'Unrecognized token.')"
    }

    "surface redefinition errors in the language" in {
      val code =
        """from Standard.Base.Error.Common import all
          |
          |foo =
          |    x = 1
          |    x = 2
          |
          |main = Panic.catch_primitive here.foo caught_panic->caught_panic.payload.to_text
          |""".stripMargin
      eval(code) shouldEqual "(Compile_Error 'Variable x is being redefined.')"
    }

    "surface non-existent variable errors in the language" in {
      val code =
        """from Standard.Base.Error.Common import all
          |
          |foo =
          |    my_var = 10
          |    my_vra
          |
          |main = Panic.catch_primitive here.foo caught_panic-> caught_panic.payload.to_text
          |""".stripMargin
      eval(
        code
      ) shouldEqual "(Compile_Error 'Variable `my_vra` is not defined.')"
    }
  }
}
