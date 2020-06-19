package org.enso.interpreter.test.semantic

import org.enso.interpreter.test.{InterpreterTest, InterpreterContext}

class LambdaTest extends InterpreterTest {
  override def subject: String = "Lambdas"

  override def specify(
    implicit interpreterContext: InterpreterContext
  ): Unit = {

    "take arguments and use them in their bodies" in {
      val code =
        """
          |main = x -> x * x
          |""".stripMargin

      val function = eval(code)
      function.call(1) shouldEqual 1
      function.call(4) shouldEqual 16
    }

    "be able to access arguments from outer scopes" in {
      val code =
        """
          |main = a ->
          |    add = a -> b -> a + b
          |    adder = b -> add a b
          |    adder 2
      """.stripMargin

      eval(code).call(3) shouldEqual 5
    }

    "be callable directly without assignment" in {
      val code =
        """
          |main = (x -> y -> x * y) 5 6
          |""".stripMargin
      eval(code) shouldEqual 30
    }

    "work with recursion" in {
      val code =
        """
          |main =
          |    sumTo = x -> ifZero x 0 (x + (sumTo (x-1)))
          |    sumTo 10
      """.stripMargin

      eval(code) shouldEqual 55
    }

    "accept more arguments than needed and pass them to the result upon execution" in {
      val code =
        """
          |main =
          |    f = x -> z -> x + z
          |    f 1 2
          |""".stripMargin

      eval(code) shouldEqual 3
    }

    "allow oversaturation and execute until completion" in {
      val code =
        """
          |main =
          |    f = x -> y -> w -> z -> x * y + w + z
          |    f 3 3 10 1
          |""".stripMargin

      eval(code) shouldEqual 20
    }

    "be able to return atoms that are evaluated with oversaturated args" in {
      val code =
        """
          |main =
          |    f = x -> Cons
          |    myCons = f 1 2 3
          |    case myCons of
          |        Cons h t -> h + t
          |""".stripMargin

      eval(code) shouldEqual 5
    }

    "support the use of oversaturated args in methods" in {
      val code =
        """
          |Unit.myMethod = 1
          |
          |main =
          |    f = x -> myMethod
          |    t = f 10 Unit
          |    t
          |""".stripMargin

      eval(code) shouldEqual 1
    }

    "recurse with closing over lexical scope" in {
      val code =
        """
          |main =
          |    summator = current ->
          |        ifZero current 0 ((x -> summator (current - 1)) 0)
          |    res = summator 0
          |    res
          |""".stripMargin

      eval(code) shouldEqual 0
    }

    "call fully saturated returned lambdas" in {
      val code =
        """
          |main =
          |    fn = a -> b ->
          |        IO.println (a + b)
          |        (x = a) -> a + 1
          |
          |    fn 1 2
          |""".stripMargin

      eval(code) shouldEqual 2

      consumeOut shouldEqual List("3")
    }

    "call fully saturated lambdas returned with TCO" in {
      val code =
        """
          |Number.if_then_else = ~t -> ~f -> ifZero this t f
          |
          |main =
          |    lam = (x = 10) -> x
          |    fn = a -> if a then lam else fn (a-1)
          |
          |    fn 10
          |""".stripMargin

      eval(code) shouldEqual 10
    }
  }
}
