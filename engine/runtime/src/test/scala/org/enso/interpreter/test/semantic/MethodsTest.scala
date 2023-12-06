package org.enso.interpreter.test.semantic

import org.enso.interpreter.test.{
  InterpreterContext,
  InterpreterException,
  InterpreterTest
}

class MethodsTest extends InterpreterTest {
  override def subject: String = "Methods"

  override def specify(implicit
    interpreterContext: InterpreterContext
  ): Unit = {
    "be defined in the global scope and dispatched to" in {
      val code =
        """
          |type Foo
          |Foo.bar = number -> number + 1
          |main = Foo.bar 10
          |""".stripMargin
      eval(code) shouldEqual 11
    }

    "execute `self` argument once" in {
      val code =
        """import Standard.Base.IO
          |import Standard.Base.Nothing
          |
          |Nothing.Nothing.foo = 0
          |
          |main = (IO.println "foo").foo
          |""".stripMargin
      eval(code)
      consumeOut shouldEqual List("foo")
    }

    "be callable with dot operator" in {
      val code =
        """
          |type Foo
          |Foo.bar = number -> number + 1
          |main = Foo.bar 10
          |""".stripMargin
      eval(code) shouldEqual 11
    }

    "be chainable with dot operator" in {
      val code =
        """
          |type Foo
          |type Bar
          |type Baz
          |
          |Foo.bar = Bar
          |Bar.baz = x -> Baz
          |Baz.spam = y -> y + 25
          |
          |main = Foo.bar.baz 54 . spam 2
          |""".stripMargin
      eval(code) shouldEqual 27
    }

    "behave like parenthesised when called with non-spaced dot operator" in {
      val code =
        """
          |type Foo
          |type Bar
          |
          |Foo.bar = a -> b -> a + b
          |Bar.constant = 10
          |
          |main = Foo.bar Bar.constant Bar.constant
          |
          |""".stripMargin
      eval(code) shouldEqual 20
    }

    "be able to be defined without arguments" in {
      val code =
        """
          |type Foo
          |Foo.bar = 1
          |main = Foo.bar + 5
          |""".stripMargin
      eval(code) shouldEqual 6
    }

    "be definable as blocks without arguments" in {
      val code =
        """import Standard.Base.Any.Any
          |
          |Any.method self =
          |    x = self * self
          |    y = x * 2
          |    y + 1
          |
          |main = 3.method
          |""".stripMargin
      eval(code) shouldEqual 19
    }

    "throw an exception when non-existent" in {
      val code =
        """
          |from Standard.Base.Errors.Common import all
          |
          |main = 7.foo
          |""".stripMargin
      the[InterpreterException] thrownBy eval(
        code
      ) should have message "Method `foo` of type Integer could not be found."
    }

    "be callable for any type when defined on Any" in {
      val code =
        """import Standard.Base.Any.Any
          |import Standard.Base.IO
          |import Standard.Base.Nothing
          |
          |type Foo
          |type Bar
          |type Baz
          |
          |Any.method self = case self of
          |    Foo -> 1
          |    Bar -> 2
          |    Baz -> 3
          |    _ -> 0
          |
          |main =
          |    IO.println Foo.method
          |    IO.println Bar.method
          |    IO.println Baz.method
          |    IO.println Nothing.method
          |    IO.println 123.method
          |    IO.println (x -> x).method
          |""".stripMargin
      eval(code)
      consumeOut shouldEqual List("1", "2", "3", "0", "0", "0")
    }

    "be callable for any type when defined on Any (resolved as a type name)" in {
      import annotation.unused
      @unused val code =
        """import Standard.Base.Any.Any
          |
          |Any.method self = 1
          |
          |main =
          |    2.method
          |""".stripMargin
//      eval(code) shouldEqual 1
      pending
    }

    "be callable on types when static" in {
      val code =
        """
          |type Foo
          |    Mk_Foo a
          |
          |    new a = Foo.Mk_Foo a
          |
          |main = Foo.new 123
          |""".stripMargin
      eval(code).toString shouldEqual "(Mk_Foo 123)"
    }

    "be callable on types when non-static, with additional self arg" in {
      val code =
        """import Standard.Base.IO
          |
          |type Foo
          |    Mk_Foo a
          |
          |    inc self = Foo.Mk_Foo self.a+1
          |
          |main =
          |    IO.println (Foo.inc (Foo.Mk_Foo 12))
          |    IO.println (Foo.Mk_Foo 13).inc
          |    IO.println (.inc self=Foo self=(Foo.Mk_Foo 14))
          |    IO.println (Foo.inc self=(Foo.Mk_Foo 15))
          |""".stripMargin
      eval(code)
      consumeOut.shouldEqual(
        List("(Mk_Foo 13)", "(Mk_Foo 14)", "(Mk_Foo 15)", "(Mk_Foo 16)")
      )
    }

    "be callable on builtin types when non-static, with additional self arg" in {
      val code =
        """import Standard.Base.IO
          |import Standard.Base.Data.Array.Array
          |import Standard.Base.Data.Text.Text
          |
          |main =
          |    a = [1].to_array
          |    t_1 = "foo"
          |    t_2 = "bar"
          |
          |    IO.println (Array.length a)
          |    IO.println (Text.+ t_1 t_2)
          |""".stripMargin
      eval(code)
      consumeOut.shouldEqual(List("1", "foobar"))
    }

    "not be callable on instances when static" in {
      val code =
        """
          |from Standard.Base.Errors.Common import all
          |
          |type Foo
          |    Mk_Foo a
          |
          |    new a = Foo.Mk_Foo a
          |
          |main = Foo.Mk_Foo 123 . new 123
          |""".stripMargin
      the[InterpreterException] thrownBy eval(
        code
      ) should have message "Method `new` of type Foo could not be found."
    }

    "not be callable on Nothing when non-static" in {
      val code =
        """
          |import Standard.Base.Nothing.Nothing
          |from Standard.Base.Errors.Common import all
          |
          |main = Nothing.is_nothing Nothing
          |""".stripMargin
      the[InterpreterException] thrownBy eval(
        code
      ) should have message "Type error: expected a function, but got True."
    }

  }
}
