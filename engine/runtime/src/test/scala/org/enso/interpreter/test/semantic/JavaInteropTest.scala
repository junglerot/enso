package org.enso.interpreter.test.semantic

import org.enso.interpreter.test.InterpreterTest

class JavaInteropTest extends InterpreterTest {
  "Java interop" should "allow calling static methods from java classes" in {
    val code =
      """
        |main =
        |    class = Java.lookup_class "org.enso.example.TestClass"
        |    method = Polyglot.get_member class "add"
        |    Polyglot.execute method [1, 2]
        |""".stripMargin

    eval(code) shouldEqual 3
  }

  "Java interop" should "allow instantiating objects and calling methods on them" in {
    val code =
      """
        |main =
        |    class = Java.lookup_class "org.enso.example.TestClass"
        |    instance = Polyglot.new class [x -> x * 2]
        |    Polyglot.invoke instance "callFunctionAndIncrement" [10]
        |""".stripMargin
    eval(code) shouldEqual 21
  }

  "Java interop" should "allow listing available members of an object" in {
    val code =
      """
        |main =
        |    class = Java.lookup_class "org.enso.example.TestClass"
        |    instance = Polyglot.new class []
        |    members = Polyglot.get_members instance
        |    IO.println (Polyglot.get_array_size members)
        |    IO.println (Polyglot.get_array_element members 0)
        |    IO.println (Polyglot.get_array_element members 1)
        |    IO.println (Polyglot.get_array_element members 2)
        |""".stripMargin
    eval(code)
    val count :: methods = consumeOut
    count shouldEqual "3"
    methods.toSet shouldEqual Set(
      "method1",
      "method2",
      "callFunctionAndIncrement"
    )
  }
}
