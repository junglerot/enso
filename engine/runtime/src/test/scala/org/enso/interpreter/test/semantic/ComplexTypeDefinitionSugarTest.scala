package org.enso.interpreter.test.semantic

import org.enso.interpreter.test.{InterpreterContext, InterpreterTest}

class ComplexTypeDefinitionSugarTest extends InterpreterTest {

  override def subject: String = "Complex type definitions"

  override def specify(implicit
    interpreterContext: InterpreterContext
  ): Unit = {

    "work properly with simple method definitions" in {
      val code =
        """
          |type My_Type
          |    Atom_One
          |    Atom_Two
          |
          |    is_atom_one self = case self of
          |        My_Type.Atom_One -> 10
          |        My_Type.Atom_Two -> -10
          |
          |main =
          |    r_1 = My_Type.Atom_One.is_atom_one
          |    r_2 = My_Type.Atom_Two.is_atom_one
          |    r_1 + r_2
          |""".stripMargin

      eval(code) shouldEqual 0
    }

    "work properly with sugared method definitions" in {
      val code =
        """
          |type My_Type
          |    Atom_One
          |    Atom_Two
          |
          |    is_atom_one self n = case self of
          |        My_Type.Atom_One -> 10 + n
          |        My_Type.Atom_Two -> -10 - n
          |
          |main =
          |    r_1 = My_Type.Atom_One.is_atom_one 5
          |    r_2 = My_Type.Atom_Two.is_atom_one 10
          |    r_1 + r_2
          |""".stripMargin

      eval(code) shouldEqual -5
    }

    "work properly with atoms with fields" in {
      val code =
        """
          |type My_Type
          |    My_Atom a
          |
          |    is_equal self n = case self of
          |        My_Type.My_Atom a -> n - a
          |
          |main =
          |    (My_Type.My_Atom 5).is_equal 5
          |""".stripMargin

      eval(code) shouldEqual 0
    }

    "work with methods appearing to be suspended blocks" in {
      val code =
        """import Standard.Base.IO
          |
          |type Foo
          |    Bar
          |    x =
          |        IO.println "foobar"
          |
          |main = Foo.x
          |""".stripMargin

      eval(code)
      consumeOut shouldEqual List("foobar")
    }

    "work with lambda consolidation" in {
      val code =
        """
          |foo x =
          |    y -> x + y
          |
          |main = foo 1 2
          |""".stripMargin

      eval(code) shouldEqual 3
    }
  }
}
