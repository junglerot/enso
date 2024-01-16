package org.enso.interpreter.node.expression.builtin.error;

import java.util.List;
import org.enso.interpreter.dsl.BuiltinType;
import org.enso.interpreter.node.expression.builtin.Builtin;
import org.enso.interpreter.runtime.data.atom.Atom;
import org.enso.interpreter.runtime.data.atom.AtomNewInstanceNode;

@BuiltinType
public final class NumberParseError extends Builtin {
  @Override
  protected final List<Cons> getDeclaredConstructors() {
    return List.of(new Cons("Error", "text"));
  }

  public final Atom newInstance(Object... params) {
    return AtomNewInstanceNode.getUncached().newInstance(getConstructors()[0], params);
  }
}
