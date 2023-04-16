package org.enso.interpreter.node.expression.builtin.number.smallInteger;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.runtime.EnsoContext;
import org.enso.interpreter.runtime.error.DataflowError;
import org.enso.interpreter.runtime.number.EnsoBigInteger;

@BuiltinMethod(type = "Small_Integer", name = ">", description = "Comparison of numbers.")
public abstract class GreaterNode extends Node {

  abstract Object execute(long self, Object that);

  static GreaterNode build() {
    return GreaterNodeGen.create();
  }

  @Specialization
  boolean doLong(long self, long that) {
    return self > that;
  }

  @Specialization
  boolean doDouble(long self, double that) {
    return (double) self > that;
  }

  @Specialization
  boolean doBigInteger(long self, EnsoBigInteger that) {
    return that.getValue().signum() < 0;
  }

  @Fallback
  Object doOther(long self, Object that) {
    var builtins = EnsoContext.get(this).getBuiltins();
    var incomparableValsErr = builtins.error().makeIncomparableValues(self, that);
    return DataflowError.withoutTrace(incomparableValsErr, this);
  }
}
