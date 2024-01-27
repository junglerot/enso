package org.enso.interpreter.node.expression.builtin.number.decimal;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.CachedLibrary;
import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.node.expression.builtin.number.utils.BigIntegerOps;
import org.enso.interpreter.node.expression.builtin.number.utils.ToEnsoNumberNode;
import org.enso.interpreter.runtime.number.EnsoBigInteger;

@BuiltinMethod(type = "Float", name = "-", description = "Subtraction of numbers.")
public abstract class SubtractNode extends FloatNode {
  abstract double execute(double own, Object that);

  static SubtractNode build() {
    return SubtractNodeGen.create();
  }

  @Specialization
  double doDouble(double self, double that) {
    return self - that;
  }

  @Specialization
  double doLong(double self, long that) {
    return self - that;
  }

  @Specialization
  double doBigInteger(double self, EnsoBigInteger that) {
    return self - BigIntegerOps.toDouble(that.getValue());
  }

  @Specialization(guards = "isForeignNumber(iop, that)")
  double doInterop(
      double self,
      TruffleObject that,
      @CachedLibrary(limit = INTEROP_LIMIT) InteropLibrary iop,
      @Cached ToEnsoNumberNode toEnsoNumberNode,
      @Cached SubtractNode delegate) {
    return delegate.execute(self, handleInterop(false, self, that, iop, toEnsoNumberNode));
  }

  @Fallback
  double doOther(double self, Object that) {
    throw panicOtherwise(self, that);
  }
}
