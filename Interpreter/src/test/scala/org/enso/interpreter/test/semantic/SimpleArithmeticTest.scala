package org.enso.interpreter.test.semantic

import org.enso.interpreter.test.InterpreterTest

class SimpleArithmeticTest extends InterpreterTest {
  "1 + 1" should "equal 2" in {
    eval("1 + 1") shouldEqual 2
  }
  "2 + (2 * 2)" should "equal 6" in {
    eval("2 + (2 * 2)") shouldEqual 6
  }
}
