package org.enso.interpreter.node.expression.builtin.number.bigInteger;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.node.expression.builtin.number.utils.BigIntegerOps;
import org.enso.interpreter.runtime.error.TypeError;
import org.enso.interpreter.runtime.number.EnsoBigInteger;

@BuiltinMethod(type = "Big_Integer", name = ">", description = "Comparison of numbers.")
public abstract class GreaterNode extends Node {

  abstract boolean execute(EnsoBigInteger _this, Object that);

  static GreaterNode build() {
    return GreaterNodeGen.create();
  }

  @Specialization
  boolean doDouble(EnsoBigInteger _this, double that) {
    return BigIntegerOps.toDouble(_this.getValue()) > that;
  }

  @Specialization
  boolean doLong(EnsoBigInteger _this, long that) {
    return _this.getValue().signum() > 0;
  }

  @Specialization
  boolean doBigInteger(EnsoBigInteger _this, EnsoBigInteger that) {
    return BigIntegerOps.compare(_this.getValue(), that.getValue()) > 0;
  }

  @Fallback
  boolean doOther(EnsoBigInteger _this, Object that) {
    throw new TypeError("Unexpected type provided for argument `that` in Integer.>", this);
  }
}
