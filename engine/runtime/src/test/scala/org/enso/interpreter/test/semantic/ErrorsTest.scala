package org.enso.interpreter.test.semantic

import org.enso.interpreter.test.{
  InterpreterTest,
  InterpreterContext,
  InterpreterException
}

class ErrorsTest extends InterpreterTest {
  override def subject: String = "Errors and Panics"

  override def specify(
    implicit interpreterContext: InterpreterContext
  ): Unit = {

    "be thrown and stop evaluation" in {
      val code =
        """
          |type Foo
          |type Bar
          |type Baz
          |
          |main =
          |    IO.println Foo
          |    Panic.throw Bar
          |    IO.println Baz
          |""".stripMargin

      val exception = the[InterpreterException] thrownBy eval(code)
      exception.isGuestException shouldEqual true
      exception.getGuestObject.toString shouldEqual "Bar"
      consumeOut shouldEqual List("Foo")
    }

    "be recoverable and transformed into errors" in {
      val code =
        """
          |type MyError
          |
          |main =
          |    thrower = x -> Panic.throw x
          |    caught = Panic.recover (thrower MyError)
          |    IO.println caught
          |""".stripMargin

      noException shouldBe thrownBy(eval(code))
      consumeOut shouldEqual List("Error:MyError")
    }

    "propagate through pattern matches" in {
      val code =
        """
          |type MyError
          |
          |main =
          |    brokenVal = Error.throw MyError
          |    matched = case brokenVal of
          |        Unit -> 1
          |        _ -> 0
          |
          |    IO.println matched
          |""".stripMargin
      noException shouldBe thrownBy(eval(code))
      consumeOut shouldEqual List("Error:MyError")
    }

    "be catchable by a user-provided special handling function" in {
      val code =
        """
          |main =
          |    intError = Error.throw 1
          |    intError.catch (x -> x + 3)
          |""".stripMargin
      eval(code) shouldEqual 4
    }

    "accept a constructor handler in catch function" in {
      val code =
        """
          |type MyCons err
          |
          |main =
          |    unitErr = Error.throw Unit
          |    IO.println (unitErr.catch MyCons)
          |""".stripMargin
      eval(code)
      consumeOut shouldEqual List("MyCons Unit")
    }

    "accept a method handle in catch function" in {
      val code =
        """
          |type MyRecovered x
          |type MyError x
          |
          |MyError.recover = case this of
          |    MyError x -> MyRecovered x
          |
          |main =
          |    myErr = Error.throw (MyError 20)
          |    IO.println(myErr.catch recover)
          |""".stripMargin
      eval(code)
      consumeOut shouldEqual List("MyRecovered 20")
    }

    "make the catch method an identity for non-error values" in {
      val code = "main = 10.catch (x -> x + 1)"
      eval(code) shouldEqual 10
    }
  }
}
