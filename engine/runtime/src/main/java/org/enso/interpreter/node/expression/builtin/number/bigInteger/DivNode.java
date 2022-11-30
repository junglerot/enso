package org.enso.interpreter.node.expression.builtin.number.bigInteger;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.node.expression.builtin.number.utils.BigIntegerOps;
import org.enso.interpreter.node.expression.builtin.number.utils.ToEnsoNumberNode;
import org.enso.interpreter.runtime.EnsoContext;
import org.enso.interpreter.runtime.builtin.Builtins;
import org.enso.interpreter.runtime.error.DataflowError;
import org.enso.interpreter.runtime.error.PanicException;
import org.enso.interpreter.runtime.number.EnsoBigInteger;

@BuiltinMethod(type = "Big_Integer", name = "div", description = "Big integer integral division.")
public abstract class DivNode extends Node {
  private @Child ToEnsoNumberNode toEnsoNumberNode = ToEnsoNumberNode.build();

  abstract Object execute(EnsoBigInteger self, Object that);

  static DivNode build() {
    return DivNodeGen.create();
  }

  @Specialization
  Object doLong(EnsoBigInteger self, long that) {
    try {
      return toEnsoNumberNode.execute(BigIntegerOps.divide(self.getValue(), that));
    } catch (ArithmeticException e) {
      return DataflowError.withoutTrace(
          EnsoContext.get(this).getBuiltins().error().getDivideByZeroError(), this);
    }
  }

  @Specialization
  Object doBigInteger(EnsoBigInteger self, EnsoBigInteger that) {
    // No need to trap, as 0 is never represented as an EnsoBigInteger.
    return toEnsoNumberNode.execute(BigIntegerOps.divide(self.getValue(), that.getValue()));
  }

  @Fallback
  Object doOther(EnsoBigInteger self, Object that) {
    Builtins builtins = EnsoContext.get(this).getBuiltins();
    var integer = builtins.number().getInteger();
    throw new PanicException(builtins.error().makeTypeError(integer, that, "that"), this);
  }
}
