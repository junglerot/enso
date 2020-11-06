package org.enso.interpreter.test.semantic

import org.enso.interpreter.test.{
  InterpreterContext,
  InterpreterException,
  InterpreterTest
}

class NamedArgumentsTest extends InterpreterTest {
  override def subject: String = "Named and Default Arguments"

  override def specify(implicit
    interpreterContext: InterpreterContext
  ): Unit = {

    "be used in function bodies" in {
      val code =
        """from Builtins import all
          |
          |Nothing.a = 10
          |Nothing.addTen = b -> a Nothing + b
          |
          |main = addTen Nothing (b = 10)
      """.stripMargin

      eval(code) shouldEqual 20
    }

    "be passed when given out of order" in {
      val code =
        """from Builtins import all
          |
          |Nothing.subtract = a -> b -> a - b
          |
          |main = subtract Nothing (b = 10) (a = 5)
    """.stripMargin

      eval(code) shouldEqual -5
    }

    "be passed with values from the scope" in {
      val code =
        """
          |main =
          |    a = 10
          |    addTen = num -> num + a
          |    res = addTen (num = a)
          |    res
    """.stripMargin

      eval(code) shouldEqual 20
    }

    "be definable" in {
      val code =
        """from Builtins import all
          |
          |Nothing.addNum = a -> (num = 10) -> a + num
          |
          |main = addNum Nothing 5
    """.stripMargin

      eval(code) shouldEqual 15
    }

    "be able to default to complex expressions" in {
      val code =
        """from Builtins import all
          |
          |Nothing.add = a -> b -> a + b
          |Nothing.doThing = a -> (b = add Nothing 1 2) -> a + b
          |
          |main = doThing Nothing 10
          |""".stripMargin

      eval(code) shouldEqual 13
    }

    "be able to close over their outer scope" in {
      val code =
        """
          |main =
          |    id = x -> x
          |    apply = val -> (fn = id) -> fn val
          |    res = apply (val = 1)
          |    res
          |""".stripMargin

      eval(code) shouldEqual 1
    }

    "be used in functions when no arguments are supplied" in {
      val code =
        """from Builtins import all
          |
          |Nothing.addTogether = (a = 5) -> (b = 6) -> a + b
          |
          |main = addTogether Nothing
    """.stripMargin

      eval(code) shouldEqual 11
    }

    "be overridable by name" in {
      val code =
        """from Builtins import all
          |
          |Nothing.addNum = a -> (num = 10) -> a + num
          |
          |main = addNum Nothing 1 (num = 1)
    """.stripMargin

      eval(code) shouldEqual 2
    }

    "overridable by position" in {
      val code =
        """from Builtins import all
          |
          |Nothing.addNum = a -> (num = 10) -> a + num
          |
          |main = addNum Nothing 1 2
          |""".stripMargin

      eval(code) shouldEqual 3
    }

    "work in a recursive context" in {
      val code =
        """from Builtins import all
          |
          |Nothing.summer = sumTo ->
          |  summator = (acc = 0) -> current ->
          |      if current == 0 then acc else summator (current = current - 1) (acc = acc + current)
          |  res = summator (current = sumTo)
          |  res
          |
          |main = summer Nothing 100
    """.stripMargin

      eval(code) shouldEqual 5050
    }

    "only be scoped to their definitions" in {
      val code =
        """
          |main =
          |    foo = x -> y -> x - y
          |    bar = y -> x -> x - y
          |    baz = f -> f (x=10) (y=11)
          |    a = baz foo
          |    b = baz bar
          |    a - b
          |""".stripMargin

      eval(code) shouldEqual 0
    }

    "be applied in a sequence compatible with Eta-expansions" in {
      val code =
        """from Builtins import all
          |
          |Nothing.foo = a -> b -> c -> a -> a
          |main = foo Nothing 20 (a = 10) 0 0
          |""".stripMargin

      eval(code) shouldEqual 10
    }

    "be able to depend on prior arguments" in {
      val code =
        """from Builtins import all
          |
          |Nothing.doubleOrAdd = a -> (b = a) -> a + b
          |
          |main = doubleOrAdd Nothing 5
          |""".stripMargin

      eval(code) shouldEqual 10
    }

    "not be able to depend on later arguments" in {
      val code =
        """from Builtins import all
          |
          |Nothing.badArgFn = a -> (b = c) -> (c = a) -> a + b + c
          |
          |main = badArgFn Nothing 3
          |""".stripMargin

      an[InterpreterException] should be thrownBy eval(code)
    }

    "be usable with constructors" in {
      val code =
        """
          |type Cons2 head rest
          |type Nil2
          |
          |main =
          |    genList = i -> if i == 0 then Nil2 else Cons2 (rest = genList i-1) head=i
          |
          |    sum = list -> case list of
          |        Cons2 h t -> h + t.sum
          |        Nil2 -> 0
          |
          |    10.genList.sum
        """.stripMargin

      eval(code) shouldEqual 55
    }

    "be usable and overridable in constructors" in {
      val code =
        """
          |type Nil2
          |type Cons2 head (rest = Nil2)
          |
          |main =
          |    genList = i -> if i == 0 then Nil2 else Cons2 (rest = genList i-1) head=i
          |
          |    sum = list -> case list of
          |        Cons2 h t -> h + t.sum
          |        Nil2 -> 0
          |
          |    5.genList.sum
        """.stripMargin

      eval(code) shouldEqual 15
    }

    "be resolved dynamically in constructors" in {
      val code =
        """
          |type Cons2 head (rest = Nil2)
          |type Nil2
          |
          |main = Cons2 5
          |""".stripMargin

      eval(code).toString shouldEqual "(Cons2 5 Nil2)"
    }

    "work with constructors" in {
      val code =
        """from Builtins import all
          |
          |type Cons2 head (rest = Nil2)
          |type Nil2
          |
          |Nothing.sumList = list -> case list of
          |  Cons2 h t -> h + Nothing.sumList t
          |  Nil2 -> 0
          |
          |main = Nothing.sumList (Cons2 10)
        """.stripMargin

      eval(code) shouldEqual 10
    }
  }
}
