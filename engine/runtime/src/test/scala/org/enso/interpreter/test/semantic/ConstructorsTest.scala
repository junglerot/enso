package org.enso.interpreter.test.semantic

import org.enso.interpreter.test.{
  InterpreterContext,
  InterpreterException,
  InterpreterTest
}

class ConstructorsTest extends InterpreterTest {

  override def subject: String = "Constructors & Pattern Matching"

  override def specify(implicit
    interpreterContext: InterpreterContext
  ): Unit = {
    "dispatch to the proper match branch" in {
      val patternMatchingCode =
        """from Builtins import all
          |
          |main =
          |    x = Cons 1 Nil
          |    case x of
          |        Cons h t -> h
          |        Nil -> 0
      """.stripMargin
      eval(patternMatchingCode) shouldEqual 1
    }

    "work with recursion" in {
      val testCode =
        """from Builtins import all
          |
          |main =
          |    genList = i -> if i == 0 then Nil else Cons i (genList (i - 1))
          |    sumList = list -> case list of
          |        Cons h t -> h + sumList t
          |        Nil -> 0
          |
          |    sumList (genList 10)
      """.stripMargin
      eval(testCode) shouldEqual 55
    }

    "behave correctly in non-tail positions" in {
      val testCode =
        """from Builtins import all
          |
          |main =
          |    add = x -> y -> x + y
          |    testCons = Cons 1 2
          |
          |    result = case testCons of
          |        Cons x y -> add x y
          |
          |    result + 1
      """.stripMargin
      eval(testCode) shouldEqual 4
    }

    "accept a catch-all fallback clause" in {
      val testCode =
        """from Builtins import all
          |
          |main =
          |    nil = Nil
          |    case nil of
          |        Cons _ _ -> 0
          |        _ -> 1
      """.stripMargin
      eval(testCode) shouldEqual 1
    }

    "throw an exception when match fails" in {
      val testCode =
        """from Builtins import all
          |
          |main =
          |    nil = Nil
          |    case nil of
          |        Cons h t -> 0
      """.stripMargin
      the[InterpreterException] thrownBy eval(testCode)
        .call() should have message "Inexhaustive_Pattern_Match_Error Nil"
    }

    "be usable in code, with arbitrary definition order" in {
      val testCode =
        """from Builtins import all
          |
          |type Cons2 a b
          |
          |Unit.genList = i -> if i == 0 then Nil2 else Cons2 i (genList Unit (i - 1))
          |
          |type Nil2
          |
          |Unit.sumList = list -> case list of
          |  Cons2 h t -> h + sumList Unit t
          |  Nil2 -> 0
          |
          |main = sumList Unit (genList Unit 10)
      """.stripMargin
      eval(testCode) shouldEqual 55
    }
  }
}
