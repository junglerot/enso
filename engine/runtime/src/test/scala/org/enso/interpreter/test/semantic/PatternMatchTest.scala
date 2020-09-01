package org.enso.interpreter.test.semantic

import org.enso.interpreter.test.{
  InterpreterTest,
  InterpreterContext,
  InterpreterException
}

class PatternMatchTest extends InterpreterTest {

  override def subject = "Pattern Matching"

  override def specify(
    implicit interpreterContext: InterpreterContext
  ): Unit = {

    "work for simple patterns" in {
      val code =
        """from Builtins import all
          |
          |main =
          |    f = case _ of
          |        Builtins.Cons a _ -> a
          |        Builtins.Nil -> -10
          |
          |    (Builtins.Cons 10 Builtins.Nil . f) - Nil.f
          |""".stripMargin

      eval(code) shouldEqual 20
    }

    "work for anonymous catch-all patterns" in {
      val code =
        """from Builtins import all
          |
          |type MyAtom a
          |
          |main =
          |    f = case _ of
          |        MyAtom a -> a
          |        _ -> -100
          |
          |    (MyAtom 50 . f) + Nil.f
          |""".stripMargin

      eval(code) shouldEqual -50
    }

    "work for named catch-all patterns" in {
      val code =
        """
          |type MyAtom a
          |
          |main =
          |    f = case _ of
          |        MyAtom a -> a
          |        a -> a + 5
          |
          |    (MyAtom 50 . f) + 30.f
          |""".stripMargin

      eval(code) shouldEqual 85
    }

    "work without assignment" in {
      val code =
        """
          |type MyAtom
          |
          |main = case MyAtom of
          |    MyAtom -> 10
          |    _ -> - 10
          |""".stripMargin

      eval(code) shouldEqual 10
    }

    "work for level one nested patterns" in {
      val code =
        """from Builtins import all
          |
          |type MyAtom
          |
          |main =
          |    f = case _ of
          |        Cons MyAtom _ -> 30
          |        _ -> -30
          |
          |    f (Cons MyAtom Nil)
          |""".stripMargin

      eval(code) shouldEqual 30
    }

    "work for deeply nested patterns" in {
      val code =
        """from Builtins import all
          |
          |type MyAtom
          |
          |main =
          |    f = case _ of
          |        Cons (Cons MyAtom Nil) Nil -> 100
          |        Cons _ Nil -> 50
          |        y -> case y of
          |            Cons _ Nil -> 30
          |            _ -> 0
          |
          |    val1 = f (Cons MyAtom Nil)            # 50
          |    val2 = f (Cons (Cons MyAtom Nil) Nil) # 100
          |    val3 = f 40                           # 0
          |
          |    val1 + val2 + val3
          |""".stripMargin

      eval(code) shouldEqual 150
    }

    "correctly result in errors for incomplete matches" in {
      val code =
        """from Builtins import all
          |
          |type MyAtom
          |
          |main =
          |    f = case _ of
          |        Nil -> 30
          |
          |    f MyAtom
          |""".stripMargin

      val msg = "Inexhaustive_Pattern_Match_Error MyAtom"
      the[InterpreterException] thrownBy eval(code) should have message msg
    }

    "work for pattern matches in pattern matches" in {
      val code =
        """from Builtins import all
          |
          |type MyAtom a
          |type One a
          |type Two a
          |
          |main =
          |    f = case _ of
          |        MyAtom a -> case a of
          |            One Nil -> 50
          |            _ -> 30
          |        _ -> 20
          |
          |    f (MyAtom (One Nil))
          |""".stripMargin

      eval(code) shouldEqual 50
    }
  }
}
