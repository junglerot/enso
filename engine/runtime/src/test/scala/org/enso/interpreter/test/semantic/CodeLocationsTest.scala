package org.enso.interpreter.test.semantic
import org.enso.interpreter.node.callable.{ApplicationNode, SequenceLiteralNode}
import org.enso.interpreter.node.callable.function.CreateFunctionNode
import org.enso.interpreter.node.callable.thunk.ForceNode
import org.enso.interpreter.node.controlflow.MatchNode
import org.enso.interpreter.node.expression.literal.IntegerLiteralNode
import org.enso.interpreter.node.scope.{AssignmentNode, ReadLocalVariableNode}
import org.enso.interpreter.test.InterpreterTest

class CodeLocationsTest extends InterpreterTest {
  def debugPrintCodeLocations(code: String): Unit = {
    var off = 0
    code.linesIterator.toList.foreach { line =>
      val chars: List[Any] = line.toList.map { c =>
          s" ${if (c == ' ') '_' else c} "
        } :+ '↵'
      val ixes = off.until(off + chars.length).map { i =>
        if (i.toString.length == 1) s" $i " else s"$i "
      }
      println(ixes.mkString(""))
      println(chars.mkString(""))
      println()
      off += line.length + 1
    }
  }

  "Code Locations" should "be correct in simple arithmetic expressions" in
  withLocationsInstrumenter { instrumenter =>
    val code = "main = 2 + 45 * 20"
    instrumenter.assertNodeExists(7, 11, classOf[ApplicationNode])
    instrumenter.assertNodeExists(11, 7, classOf[ApplicationNode])
    instrumenter.assertNodeExists(11, 2, classOf[IntegerLiteralNode])
    eval(code)
    ()
  }

  "Code locations" should "be correct with parenthesized expressions" in
  withLocationsInstrumenter { instrumenter =>
    val code = "main = (2 + 45) * 20"
    instrumenter.assertNodeExists(7, 13, classOf[ApplicationNode])
    instrumenter.assertNodeExists(8, 6, classOf[ApplicationNode])
    eval(code)
    ()
  }

  "Code Locations" should "be correct in applications and method calls" in
  withLocationsInstrumenter { instrumenter =>
    val code = "main = (2 - 2).ifZero (Cons 5 6) 0"
    instrumenter.assertNodeExists(7, 27, classOf[ApplicationNode])
    instrumenter.assertNodeExists(23, 8, classOf[ApplicationNode])
    eval(code)
    ()
  }

  "Code Locations" should "be correct in assignments and variable reads" in
  withLocationsInstrumenter { instrumenter =>
    val code =
      """
        |main =
        |    x = 2 + 2 * 2
        |    y = x * x
        |    IO.println y
        |""".stripMargin
    instrumenter.assertNodeExists(12, 13, classOf[AssignmentNode])
    instrumenter.assertNodeExists(30, 9, classOf[AssignmentNode])
    instrumenter.assertNodeExists(34, 1, classOf[ReadLocalVariableNode])
    instrumenter.assertNodeExists(38, 1, classOf[ReadLocalVariableNode])
    instrumenter.assertNodeExists(55, 1, classOf[ReadLocalVariableNode])
    eval(code)
    ()
  }

  "Code Locations" should "be correct for deeply nested functions" in
  withLocationsInstrumenter { instrumenter =>
    val code =
      """
        |Unit.method =
        |    foo = a -> b ->
        |        IO.println a
        |        add = a -> b -> a + b
        |        add a b
        |    foo 10 20
        |
        |main = Unit.method
        |""".stripMargin

    instrumenter.assertNodeExists(80, 5, classOf[ApplicationNode])
    instrumenter.assertNodeExists(98, 1, classOf[ReadLocalVariableNode])
    instrumenter.assertNodeExists(94, 7, classOf[ApplicationNode])
    instrumenter.assertNodeExists(106, 9, classOf[ApplicationNode])
    eval(code)
    ()
  }

  "Code Locations" should "be correct inside pattern matches" in
  withLocationsInstrumenter { instrumenter =>
    val code =
      """
        |main =
        |    x = Cons 1 2
        |    y = Nil
        |
        |    add = a -> b -> a + b
        |
        |    foo = x -> case x of
        |        Cons a b ->
        |            z = add a b
        |            x = z * z
        |            x
        |        _ -> 5 * 5
        |
        |    foo x + foo y
        |""".stripMargin
    instrumenter.assertNodeExists(80, 109, classOf[MatchNode])
    instrumenter.assertNodeExists(126, 7, classOf[ApplicationNode])
    instrumenter.assertNodeExists(146, 9, classOf[AssignmentNode])
    instrumenter.assertNodeExists(183, 5, classOf[ApplicationNode])
    eval(code)
    ()
  }

  "Code locations" should "be correct for lambdas" in
  withLocationsInstrumenter { instrumenter =>
    val code =
      """
        |main =
        |    f = a -> b -> a + b
        |    g = x -> y ->
        |        z = x * y
        |        z + z
        |
        |    f 1 (g 2 3)
        |""".stripMargin
    instrumenter.assertNodeExists(16, 15, classOf[CreateFunctionNode])
    instrumenter.assertNodeExists(40, 42, classOf[CreateFunctionNode])
    eval(code)
    ()
  }

  "Code locations" should "be correct for defaulted arguments" in
  withLocationsInstrumenter { instrumenter =>
    val code =
      """
        |main =
        |    bar = x -> x + x * x
        |    foo = x -> (y = bar x) -> x + y
        |    foo 0
        |""".stripMargin

    instrumenter.assertNodeExists(53, 5, classOf[ApplicationNode])
    instrumenter.assertNodeExists(53, 3, classOf[ReadLocalVariableNode])
    instrumenter.assertNodeExists(57, 1, classOf[ReadLocalVariableNode])
    eval(code)
    ()
  }

  "Code locations" should "be correct for lazy arguments" in
  withLocationsInstrumenter { instrumenter =>
    val code =
      """
        |main =
        |    bar = a -> ~b -> ~c -> b
        |
        |    bar 0 10 0
        |""".stripMargin
    instrumenter.assertNodeExists(35, 1, classOf[ForceNode])
    eval(code)
    ()
  }

  "Code locations" should "be correct for vector literals" in
  withLocationsInstrumenter { instrumenter =>
    val code = "main = [11, 2 + 2, 31 * 42, [1,2,3] ]"
    instrumenter.assertNodeExists( // outer list
      7,
      30,
      classOf[SequenceLiteralNode]
    )
    instrumenter.assertNodeExists( // inner list
      28,
      7,
      classOf[SequenceLiteralNode]
    )
    instrumenter.assertNodeExists(19, 7, classOf[ApplicationNode]) // 31 * 42
    eval(code)
  }
}
