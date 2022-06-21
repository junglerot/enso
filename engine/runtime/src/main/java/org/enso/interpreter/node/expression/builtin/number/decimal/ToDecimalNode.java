package org.enso.interpreter.node.expression.builtin.number.decimal;

import com.oracle.truffle.api.nodes.Node;
import org.enso.interpreter.dsl.BuiltinMethod;

@BuiltinMethod(type = "Decimal", name = "to_decimal", description = "Identity on decimals")
public class ToDecimalNode extends Node {
  double execute(double self) {
    return self;
  }
}
