package org.enso.interpreter.test.semantic

import org.enso.interpreter.test.{InterpreterException, InterpreterTest}

class PatternMatchTest extends InterpreterTest {

  // === Test Setup ===========================================================
  val subject = "Pattern matching"

  // === The Tests ============================================================

  subject should "work for simple patterns" in {
    val code =
      """
        |main =
        |    f = case _ of
        |        Cons a _ -> a
        |        Nil -> -10
        |
        |    (10.Cons Nil . f) - Nil.f
        |""".stripMargin

    eval(code) shouldEqual 20
  }

  subject should "work for anonymous catch-all patterns" in {
    val code =
      """
        |type MyAtom a
        |
        |main =
        |    f = case _ of
        |        MyAtom a -> a
        |        _ -> -100
        |
        |    (50.MyAtom . f) + Nil.f
        |""".stripMargin

    eval(code) shouldEqual -50
  }

  subject should "work for named catch-all patterns" in {
    val code =
      """
        |type MyAtom a
        |
        |main =
        |    f = case _ of
        |        MyAtom a -> a
        |        a -> a + 5
        |
        |    (50.MyAtom . f) + 30.f
        |""".stripMargin

    eval(code) shouldEqual 85
  }

  subject should "work without assignment" in {
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

  subject should "work for level one nested patterns" in {
    val code =
      """
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

  subject should "work for deeply nested patterns" in {
    val code =
      """
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

  subject should "correctly result in errors for incomplete matches" in {
    val code =
      """
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

  subject should "work for pattern matches in pattern matches" in {
    val code =
      """
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
