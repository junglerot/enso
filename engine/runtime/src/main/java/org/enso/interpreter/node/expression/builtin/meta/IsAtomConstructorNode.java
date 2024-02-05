package org.enso.interpreter.node.expression.builtin.meta;

import com.oracle.truffle.api.nodes.Node;
import org.enso.interpreter.dsl.AcceptsError;
import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.runtime.type.TypesGen;

@BuiltinMethod(
    type = "Meta",
    name = "is_atom_constructor",
    description = "Checks if the argument is a constructor.",
    autoRegister = false)
public class IsAtomConstructorNode extends Node {
  boolean execute(@AcceptsError Object value) {
    return TypesGen.isAtomConstructor(value);
  }
}
