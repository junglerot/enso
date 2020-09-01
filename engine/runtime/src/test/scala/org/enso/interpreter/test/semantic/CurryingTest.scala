package org.enso.interpreter.test.semantic

import org.enso.interpreter.test.{InterpreterTest, InterpreterContext}

class CurryingTest extends InterpreterTest {
  override def subject: String = "Functions"

  override def specify(
    implicit interpreterContext: InterpreterContext
  ): Unit = {

    "allow partial application" in {
      val code =
        """
          |main =
          |    apply = v -> f -> f v
          |    adder = a -> b -> a + b
          |    plusOne = apply (f = adder 1)
          |    result = plusOne 10
          |    result
          |""".stripMargin

      eval(code) shouldEqual 11
    }

    "allow default arguments to be suspended" in {
      val code =
        """
          |main =
          |    fn = w -> x -> (y = 10) -> (z = 20) -> w + x + y + z
          |
          |    fn1 = fn ...
          |    fn2 = fn1 1 2 ...
          |    fn3 = fn2 3 ...
          |
          |    fn3.call
          |""".stripMargin

      eval(code) shouldEqual 26
    }

    "be callable with arguments" in {
      val code =
        """
          |main =
          |    fn = w -> x -> (y = 10) -> (z = 20) -> w + x + y + z
          |
          |    fn.call 1 2 (z = 10)
          |""".stripMargin

      eval(code) shouldEqual 23
    }

    "allow defaults to be suspended in application chains" in {
      val code =
        """
          |main =
          |    fn = w -> x -> (y = 10) -> (z = 20) -> w + x + y + z
          |    id = x -> x
          |
          |    (fn 3 (id 6) ...) 3
          |""".stripMargin

      eval(code) shouldEqual 32
    }

    "allow default arguments to be suspended in method call syntax" in {
      val code =
        """from Builtins import all
          |
          |Unit.fn = w -> x -> (y = 10) -> (z = 20) -> w + x + y + z
          |
          |main =
          |    fn1 = Unit.fn ...
          |    fn2 = fn1 1 2 ...
          |    fn3 = fn2 3 ...
          |
          |    fn3.call
          |""".stripMargin

      eval(code) shouldEqual 26
    }
  }
}
