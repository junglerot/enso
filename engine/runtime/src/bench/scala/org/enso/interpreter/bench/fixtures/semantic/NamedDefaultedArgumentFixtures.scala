package org.enso.interpreter.bench.fixtures.semantic

import org.enso.interpreter.test.InterpreterRunner

class NamedDefaultedArgumentFixtures extends InterpreterRunner {
  val hundredMillion: Long = 100000000

  val sumTCOWithNamedArgumentsCode =
    """
      |main = sumTo ->
      |    summator = acc current ->
      |        ifZero current acc (summator (current = current - 1) (acc = acc + current))
      |
      |    res = summator current=sumTo acc=0
      |    res
    """.stripMargin
  val sumTCOWithNamedArguments = getMain(sumTCOWithNamedArgumentsCode)

  val sumTCOWithDefaultedArgumentsCode =
    """
      |main = sumTo ->
      |    summator = (acc = 0) current ->
      |        ifZero current acc (summator (current = current - 1) (acc = acc + current))
      |
      |    res = summator (current = sumTo)
      |    res
    """.stripMargin
  val sumTCOWithDefaultedArguments = getMain(sumTCOWithDefaultedArgumentsCode)

}
