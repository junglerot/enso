package org.enso.interpreter.node.expression.builtin.meta;

import com.oracle.truffle.api.nodes.Node;
import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.runtime.data.atom.AtomConstructor;
import org.enso.interpreter.runtime.data.text.Text;

@BuiltinMethod(
    type = "Meta",
    name = "get_constructor_name",
    description = "Gets the name of a constructor.",
    autoRegister = false)
public class GetConstructorNameNode extends Node {
  Text execute(AtomConstructor cons) {
    return Text.create(cons.getName());
  }
}
