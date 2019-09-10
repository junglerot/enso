package org.enso.interpreter.fixtures

import org.enso.interpreter.Constants
import org.enso.interpreter.LanguageRunner

class RecursionFixtures extends LanguageRunner {
  val hundredMillion: Long = 100000000
  val million: Long        = 1000000
  val thousand: Long       = 1000
  val hundred: Long        = 100

  // Currently unused as we know this is very slow.
  val mutRecursiveCode =
    """
    |summator = { |acc, current|
    |    ifZero: [current, acc, @summator [acc + current, current - 1]]
    |}
    |
    |{ |sumTo|
    |  res = @summator [0, sumTo];
    |  res
    |}
    |"""

  val sumTCOCode =
    """
      |{ |sumTo|
      |  summator = { |acc, current|
      |      ifZero: [current, acc, @summator [acc + current, current - 1]]
      |  };
      |  res = @summator [0, sumTo];
      |  res
      |}
    """.stripMargin

  val sumTCO = ctx.eval(Constants.LANGUAGE_ID, sumTCOCode)

  val sumTCOFoldLikeCode =
    """
      |{ |sumTo|
      |  summator = { |acc, i, f| ifZero: [i, acc, @summator [@f [acc, i], i - 1, f]] };
      |  res = @summator [0, sumTo, {|x, y| x + y }];
      |  res
      |}
      |""".stripMargin

  val sumTCOFoldLike = eval(sumTCOFoldLikeCode)

  val sumRecursiveCode =
    """
      |{ |sumTo|
      |  summator = { |i| ifZero: [i, 0, i + (@summator [i - 1])] };
      |  res = @summator [sumTo];
      |  res
      |}
    """.stripMargin

  val sumRecursive = ctx.eval(Constants.LANGUAGE_ID, sumRecursiveCode)

  val oversaturatedRecursiveCallTCOCode =
    """
      |{ |sumTo|
      |  summator = { |acc, i, f| ifZero: [i, acc, @summator [@f [acc, i], i - 1, f]] };
      |  res = @summator [0, sumTo, {|x| { |y| x + y }}];
      |  res
      |}
      |""".stripMargin

  val oversaturatedRecursiveCall =
    ctx.eval(Constants.LANGUAGE_ID, oversaturatedRecursiveCallTCOCode);

}
