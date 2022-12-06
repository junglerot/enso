package org.enso.interpreter.node.expression.builtin.error;

import org.enso.interpreter.dsl.BuiltinType;
import org.enso.interpreter.node.expression.builtin.UniquelyConstructibleBuiltin;

import java.util.List;

@BuiltinType
public class UnsupportedArgumentTypes extends UniquelyConstructibleBuiltin {
  @Override
  protected String getConstructorName() {
    return "Error";
  }

  @Override
  protected List<String> getConstructorParamNames() {
    return List.of("arguments", "message");
  }
}
