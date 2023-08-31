package org.enso.compiler.context

import org.enso.compiler.core.ir.Name

/** This class provides a supply of fresh names guaranteed not to exist in this
  * program.
  */
class FreshNameSupply {
  private var counter: Long = 0

  private def mkName(
    numId: Long,
    isMethod: Boolean
  ): Name.Literal = {
    Name.Literal(
      s"<internal-${numId}>",
      isMethod,
      None
    )
  }

  /** Generates a name guaranteed not to exist in this program.
    *
    * @param isMethod whether or not the name should represent a method name.
    * @return a new name
    */
  def newName(
    isMethod: Boolean = false
  ): Name.Literal = {
    val num = counter
    counter += 1

    mkName(num, isMethod)
  }
}
