package org.enso.interpreter.node.expression.builtin.number.decimal;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.node.expression.builtin.number.utils.BigIntegerOps;
import org.enso.interpreter.runtime.error.TypeError;
import org.enso.interpreter.runtime.number.EnsoBigInteger;

@BuiltinMethod(type = "Decimal", name = "<=", description = "Comparison of numbers.")
public abstract class LessOrEqualNode extends Node {

  abstract boolean execute(double _this, Object that);

  static LessOrEqualNode build() {
    return LessOrEqualNodeGen.create();
  }

  @Specialization
  boolean doDouble(double _this, double that) {
    return _this <= that;
  }

  @Specialization
  boolean doLong(double _this, long that) {
    return _this <= (double) that;
  }

  @Specialization
  boolean doBigInteger(double _this, EnsoBigInteger that) {
    return _this <= BigIntegerOps.toDouble(that.getValue());
  }

  @Fallback
  boolean doOther(double _this, Object that) {
    throw new TypeError("Unexpected type provided for argument `that` in Decimal.<=", this);
  }
}
