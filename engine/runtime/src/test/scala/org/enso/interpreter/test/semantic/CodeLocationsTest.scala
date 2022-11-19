package org.enso.interpreter.test.semantic
import org.enso.interpreter.node.callable.function.CreateFunctionNode
import org.enso.interpreter.node.callable.thunk.ForceNode
import org.enso.interpreter.node.callable.ApplicationNode
import org.enso.interpreter.node.controlflow.caseexpr.CaseNode
import org.enso.interpreter.node.expression.literal.LiteralNode
import org.enso.interpreter.node.scope.{AssignmentNode, ReadLocalVariableNode}
import org.enso.interpreter.test.{InterpreterContext, InterpreterTest}
import org.enso.polyglot.MethodNames

class CodeLocationsTest extends InterpreterTest {

  override def subject: String = "Code Locations"

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

  override def specify(implicit
    interpreterContext: InterpreterContext
  ): Unit = {

    "be correct in simple arithmetic expressions" in
    withLocationsInstrumenter { instrumenter =>
      val code = "main = 2 + 45 * 20"
      instrumenter.assertNodeExists(7, 11, classOf[ApplicationNode])
      instrumenter.assertNodeExists(11, 7, classOf[ApplicationNode])
      instrumenter.assertNodeExists(11, 2, classOf[LiteralNode])
      eval(code)
      ()
    }

    "be correct with parenthesized expressions" in
    withLocationsInstrumenter { instrumenter =>
      val code = "main = (2 + 45) * 20"
      instrumenter.assertNodeExists(7, 13, classOf[ApplicationNode])
      instrumenter.assertNodeExists(8, 6, classOf[ApplicationNode])
      eval(code)
      ()
    }

    "be correct in applications and method calls" in
    withLocationsInstrumenter { instrumenter =>
      val code =
        """import Standard.Base.Data.List.List
          |
          |main = (2-2 == 0).if_then_else (List.Cons 5 6) 0
          |""".stripMargin.linesIterator.mkString("\n")
      instrumenter.assertNodeExists(44, 41, classOf[ApplicationNode])
      instrumenter.assertNodeExists(69, 13, classOf[ApplicationNode])
      eval(code)
      ()
    }

    "be correct in assignments and variable reads" in
    withLocationsInstrumenter { instrumenter =>
      val code =
        """
          |import Standard.Base.IO
          |
          |main =
          |    x = 2 + 2 * 2
          |    y = x * x
          |    IO.println y
          |""".stripMargin.linesIterator.mkString("\n")
      instrumenter.assertNodeExists(37, 13, classOf[AssignmentNode])
      instrumenter.assertNodeExists(55, 9, classOf[AssignmentNode])
      instrumenter.assertNodeExists(59, 1, classOf[ReadLocalVariableNode])
      instrumenter.assertNodeExists(63, 1, classOf[ReadLocalVariableNode])
      instrumenter.assertNodeExists(80, 1, classOf[ReadLocalVariableNode])
      eval(code)
      ()
    }

    "be correct for deeply nested functions" in
    withLocationsInstrumenter { instrumenter =>
      val code =
        """
          |import Standard.Base.Nothing
          |import Standard.Base.IO
          |
          |Nothing.method =
          |    foo = a -> b ->
          |        IO.println a
          |        add = a -> b -> a + b
          |        add a b
          |    foo 10 20
          |
          |main = Nothing.method
          |""".stripMargin.linesIterator.mkString("\n")

      instrumenter.assertNodeExists(137, 5, classOf[ApplicationNode])
      instrumenter.assertNodeExists(155, 1, classOf[ReadLocalVariableNode])
      instrumenter.assertNodeExists(151, 7, classOf[ApplicationNode])
      instrumenter.assertNodeExists(163, 9, classOf[ApplicationNode])
      eval(code)
      ()
    }

    "be correct inside pattern matches" in
    withLocationsInstrumenter { instrumenter =>
      val code =
        """
          |import Standard.Base.Data.List.List
          |
          |main =
          |    x = List.Cons 1 2
          |    y = List.Nil
          |
          |    add = a -> b -> a + b
          |
          |    foo = x -> case x of
          |        List.Cons a b ->
          |            z = add a b
          |            x = z * z
          |            x
          |        _ -> 5 * 5
          |
          |    foo x + foo y
          |""".stripMargin.linesIterator.mkString("\n")
      instrumenter.assertNodeExists(127, 0, 114, 1, classOf[CaseNode])
      instrumenter.assertNodeExists(178, 7, classOf[ApplicationNode])
      instrumenter.assertNodeExists(198, 9, classOf[AssignmentNode])
      instrumenter.assertNodeExists(235, 5, classOf[ApplicationNode])
      eval(code)
      ()
    }

    "be correct for lambdas" in
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
          |""".stripMargin.linesIterator.mkString("\n")
      instrumenter.assertNodeExists(16, 15, classOf[CreateFunctionNode])
      instrumenter.assertNodeExists(40, 42, classOf[CreateFunctionNode])
      eval(code)
      ()
    }

    "be correct for defaulted arguments" in
    withLocationsInstrumenter { instrumenter =>
      val code =
        """
          |main =
          |    bar = x -> x + x * x
          |    foo = x -> (y = bar x) -> x + y
          |    foo 0
          |""".stripMargin.linesIterator.mkString("\n")

      instrumenter.assertNodeExists(53, 5, classOf[ApplicationNode])
      instrumenter.assertNodeExists(53, 3, classOf[ReadLocalVariableNode])
      instrumenter.assertNodeExists(57, 1, classOf[ReadLocalVariableNode])
      eval(code)
      ()
    }

    "be correct for lazy arguments" in
    withLocationsInstrumenter { instrumenter =>
      val code =
        """
          |main =
          |    bar = a -> ~b -> ~c -> b
          |
          |    bar 0 10 0
          |""".stripMargin.linesIterator.mkString("\n")
      instrumenter.assertNodeExists(35, 1, classOf[ForceNode])
      eval(code)
      ()
    }

    "be correct for negated literals" in
    withLocationsInstrumenter { instrumenter =>
      val code = "main = (-1)"
      instrumenter.assertNodeExists(7, 1, 4, 2, classOf[LiteralNode])
      eval(code)
    }

    "be correct for negated expressions" in
    withLocationsInstrumenter { instrumenter =>
      val code =
        """
          |main =
          |    f = 1
          |    -f
          |""".stripMargin.linesIterator.mkString("\n")
      instrumenter.assertNodeExists(22, 1, 2, 1, classOf[ApplicationNode])
      eval(code)
    }

    "be correct in sugared method definitions" in
    withLocationsInstrumenter { instrumenter =>
      val code =
        """
          |Test.foo a b = a * b - a
          |
          |main = Test.foo 2 3
          |""".stripMargin.linesIterator.mkString("\n")

      val mod    = interpreterContext.executionContext.evalModule(code, "Test")
      val tpe    = mod.getAssociatedType
      val method = mod.getMethod(tpe, "foo").get
      method.value.invokeMember(
        MethodNames.Function.GET_SOURCE_START
      ) shouldEqual 1
      method.value.invokeMember(
        MethodNames.Function.GET_SOURCE_LENGTH
      ) should (
        equal(24) or
        equal(25)
      )

      instrumenter.assertNodeExists(16, 9, classOf[ApplicationNode])

      eval(code)
    }

    "be correct in sugared function definitions" in
    withLocationsInstrumenter { instrumenter =>
      val code =
        """|main =
           |    f a b = a - b
           |    f 10 20
           |""".stripMargin.linesIterator.mkString("\n")

      instrumenter.assertNodeExists(11, 1, 13, 0, classOf[AssignmentNode])
      instrumenter.assertNodeExists(19, 1, 5, 0, classOf[ApplicationNode])
      eval(code)
    }

    "be correct in the presence of comments" in
    withLocationsInstrumenter { instrumenter =>
      val code =
        """
          |# this is a comment
          |#this too
          |## But this is a doc.
          |main = # define main
          |    y = 1 # assign one to `y`
          |    x = 2 # assign two to #x
          |    # perform the addition
          |    x + y # the addition is performed here
          |""".stripMargin.linesIterator.mkString("\n")
      instrumenter.assertNodeExists(82, 1, classOf[LiteralNode])
      instrumenter.assertNodeExists(164, 5, classOf[ApplicationNode])
      eval(code) shouldEqual 3
    }

    "be correct in nested pattern matches" in withLocationsInstrumenter {
      instrumenter =>
        val code =
          """
            |import Standard.Base.Data.List.List
            |
            |type MyAtom
            |
            |main =
            |    f = case _ of
            |        List.Cons (List.Cons MyAtom List.Nil) List.Nil -> 100
            |        _ -> 50
            |    f (List.Cons (List.Cons MyAtom List.Nil) List.Nil)
            |""".stripMargin.linesIterator.mkString("\n")

        instrumenter.assertNodeExists(66, 87, classOf[CaseNode])
        instrumenter.assertNodeExists(71, 1, classOf[ReadLocalVariableNode])
        instrumenter.assertNodeExists(134, 3, classOf[LiteralNode])

        eval(code) shouldEqual 100
    }
  }
}
