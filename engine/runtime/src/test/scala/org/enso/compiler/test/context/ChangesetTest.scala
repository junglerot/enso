package org.enso.compiler.test.context

import org.enso.compiler.context.Changeset
import org.enso.compiler.core.IR
import org.enso.compiler.test.CompilerTest
import org.enso.text.editing.model.{Position, Range, TextEdit}

class ChangesetTest extends CompilerTest {

  "DiffChangeset" should {

    "single literal whole" in {
      val code = """x = 1 + 2"""
      val edit = TextEdit(Range(Position(0, 8), Position(0, 9)), "42")

      val ir  = code.toIrExpression.get.asInstanceOf[IR.Expression.Binding]
      val rhs = ir.expression.asInstanceOf[IR.Application.Operator.Binary]
      val two = rhs.right.value

      invalidated(edit, code, ir) should contain theSameElementsAs Seq(
        two.getId
      )
    }

    "single literal left" in {
      val code = """x = 1 + 2"""
      val edit = TextEdit(Range(Position(0, 8), Position(0, 8)), "9")

      val ir  = code.toIrExpression.get.asInstanceOf[IR.Expression.Binding]
      val rhs = ir.expression.asInstanceOf[IR.Application.Operator.Binary]
      val two = rhs.right.value

      invalidated(edit, code, ir) should contain theSameElementsAs Seq(
        two.getId
      )
    }

    "single literal right" in {
      val code = """x = 1 + 2"""
      val edit = TextEdit(Range(Position(0, 9), Position(0, 9)), "9")

      val ir  = code.toIrExpression.get.asInstanceOf[IR.Expression.Binding]
      val rhs = ir.expression.asInstanceOf[IR.Application.Operator.Binary]
      val two = rhs.right.value

      invalidated(edit, code, ir) should contain theSameElementsAs Seq(
        two.getId
      )
    }

    "single literal partial" in {
      val code = """x1 = 42"""
      val edit = TextEdit(Range(Position(0, 1), Position(0, 2)), "")

      val ir = code.toIrExpression.get.asInstanceOf[IR.Expression.Binding]
      val x  = ir.name

      invalidated(edit, code, ir) should contain theSameElementsAs Seq(
        x.getId
      )
    }

    "single literal inside" in {
      val code = """baz = 42"""
      val edit = TextEdit(Range(Position(0, 1), Position(0, 2)), "oo")

      val ir = code.toIrExpression.get.asInstanceOf[IR.Expression.Binding]
      val x  = ir.name

      invalidated(edit, code, ir) should contain theSameElementsAs Seq(
        x.getId
      )
    }

    "application and literal" in {
      val code = """x = 1 + 2"""
      val edit = TextEdit(Range(Position(0, 6), Position(0, 9)), "- 42")

      val ir   = code.toIrExpression.get.asInstanceOf[IR.Expression.Binding]
      val rhs  = ir.expression.asInstanceOf[IR.Application.Operator.Binary]
      val plus = rhs.operator
      val two  = rhs.right.value

      invalidated(edit, code, ir) should contain theSameElementsAs Seq(
        plus.getId,
        two.getId
      )
    }

    "binding and literal" in {
      val code = """x = 1 + 2"""
      val edit = TextEdit(Range(Position(0, 0), Position(0, 5)), "y = 42")

      val ir  = code.toIrExpression.get.asInstanceOf[IR.Expression.Binding]
      val rhs = ir.expression.asInstanceOf[IR.Application.Operator.Binary]
      val x   = ir.name
      val one = rhs.left.value

      invalidated(edit, code, ir) should contain theSameElementsAs Seq(
        x.getId,
        one.getId
      )
    }

    "binding and space" in {
      val code = """x = 1 + 2"""
      val edit = TextEdit(Range(Position(0, 0), Position(0, 4)), "y = ")

      val ir = code.toIrExpression.get.asInstanceOf[IR.Expression.Binding]
      val x  = ir.name
      val one =
        ir.expression.asInstanceOf[IR.Application.Operator.Binary].left.value

      invalidated(edit, code, ir) should contain theSameElementsAs Seq(
        x.getId,
        one.getId
      )
    }

    "multiline single line" in {
      val code =
        """x ->
          |    y = 5
          |    y + x""".stripMargin.linesIterator.mkString("\n")
      val edit = TextEdit(Range(Position(2, 4), Position(2, 9)), "x")

      val ir = code.toIrExpression.get.asInstanceOf[IR.Function.Lambda]
      val secondLine =
        ir.body.children(1).asInstanceOf[IR.Application.Operator.Binary]
      val y    = secondLine.left.value
      val plus = secondLine.operator
      val x    = secondLine.right.value

      invalidated(edit, code, ir) should contain theSameElementsAs Seq(
        y.getId,
        plus.getId,
        x.getId
      )
    }

    "multiline across lines" in {
      val code =
        """x ->
          |    z = 1
          |    y = z
          |    y + x""".stripMargin.linesIterator.mkString("\n")
      val edit = TextEdit(Range(Position(2, 8), Position(3, 7)), s"42\n    y -")

      val ir         = code.toIrExpression.get.asInstanceOf[IR.Function.Lambda]
      val secondLine = ir.body.children(1).asInstanceOf[IR.Expression.Binding]
      val z          = secondLine.expression
      val thirdLine =
        ir.body.children(2).asInstanceOf[IR.Application.Operator.Binary]
      val y    = thirdLine.left.value
      val plus = thirdLine.operator

      invalidated(edit, code, ir) should contain theSameElementsAs Seq(
        z.getId,
        y.getId,
        plus.getId
      )
    }
  }

  def invalidated(edit: TextEdit, code: String, ir: IR): Seq[IR.Identifier] =
    new Changeset(code, ir).invalidated(edit).map(_.internalId)
}
