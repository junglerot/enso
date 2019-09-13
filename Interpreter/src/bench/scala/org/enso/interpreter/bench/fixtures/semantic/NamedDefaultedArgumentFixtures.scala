package org.enso.interpreter.bench.fixtures.semantic

import org.enso.interpreter.test.semantic.LanguageRunner

class NamedDefaultedArgumentFixtures extends LanguageRunner {
  val hundredMillion: Long = 100000000

  val sumTCOWithNamedArgumentsCode =
    """
      |{ |sumTo|
      |  summator = { |acc, current|
      |      ifZero: [current, acc, @summator [current = current - 1, acc = acc + current]]
      |  };
      |  res = @summator [current = sumTo, acc = 0];
      |  res
      |}
    """.stripMargin

  val sumTCOWithNamedArguments = eval(sumTCOWithNamedArgumentsCode)

  val sumTCOWithDefaultedArgumentsCode =
    """
      |{ |sumTo|
      |  summator = { |acc = 0, current|
      |      ifZero: [current, acc, @summator [current = current - 1, acc = acc + current]]
      |  };
      |  res = @summator [current = sumTo];
      |  res
      |}
    """.stripMargin

  val sumTCOWithDefaultedArguments = eval(sumTCOWithDefaultedArgumentsCode)

}
