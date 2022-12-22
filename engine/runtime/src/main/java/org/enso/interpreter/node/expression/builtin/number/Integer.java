package org.enso.interpreter.node.expression.builtin.number;

import org.enso.interpreter.dsl.BuiltinType;
import org.enso.interpreter.node.expression.builtin.Builtin;

@BuiltinType(name = "Standard.Base.Data.Numbers.Integer")
public class Integer extends Builtin {
  @Override
  protected Class<? extends Builtin> getSuperType() {
    return Number.class;
  }

  @Override
  protected boolean containsValues() {
    return true;
  }
}
