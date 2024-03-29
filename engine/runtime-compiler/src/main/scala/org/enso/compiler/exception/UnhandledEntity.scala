package org.enso.compiler.exception

/** This exception is thrown when compiler internal processing encounters an
  * entity that it doesn't know how to deal with.
  *
  * @param entity the unhandled entity
  * @param methodName the method throwing the exception
  */
class UnhandledEntity(val entity: Any, methodName: String)
    extends RuntimeException(
      "Fatal: Unhandled entity in " + methodName + " = " + entity.toString
    ) {}
