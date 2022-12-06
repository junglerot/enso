package org.enso.interpreter.node.expression.builtin.error;

import com.oracle.truffle.api.exception.AbstractTruffleException;
import org.enso.interpreter.dsl.BuiltinType;
import org.enso.interpreter.node.expression.builtin.UniquelyConstructibleBuiltin;
import org.enso.interpreter.runtime.EnsoContext;
import org.enso.interpreter.runtime.callable.atom.Atom;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.format.DateTimeParseException;
import java.util.List;

@BuiltinType
public class PolyglotError extends UniquelyConstructibleBuiltin {
  @Override
  protected String getConstructorName() {
    return "Error";
  }

  @Override
  protected List<String> getConstructorParamNames() {
    return List.of("cause");
  }

  public Atom wrap(AbstractTruffleException e) {
    return newInstance(e);
  }

  public Atom wrap(EnsoContext c, IOException e) {
    return newInstance(c.getEnvironment().asGuestValue(e));
  }

  public Atom wrap(EnsoContext c, DateTimeException e) {
    return newInstance(c.getEnvironment().asGuestValue(e));
  }

  public Atom wrap(EnsoContext c, DateTimeParseException e) {
    return newInstance(c.getEnvironment().asGuestValue(e));
  }
}
