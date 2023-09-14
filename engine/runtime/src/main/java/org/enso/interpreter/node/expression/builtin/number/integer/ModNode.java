package org.enso.interpreter.node.expression.builtin.number.integer;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.CachedLibrary;
import java.math.BigInteger;
import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.node.expression.builtin.number.utils.BigIntegerOps;
import org.enso.interpreter.runtime.EnsoContext;
import org.enso.interpreter.runtime.error.DataflowError;
import org.enso.interpreter.runtime.number.EnsoBigInteger;

@BuiltinMethod(type = "Integer", name = "%", description = "Modulo division of numbers.")
public abstract class ModNode extends IntegerNode {

  abstract Object execute(Object self, Object that);

  static ModNode build() {
    return ModNodeGen.create();
  }

  @Specialization
  Object doLong(long self, long that) {
    try {
      return self % that;
    } catch (ArithmeticException e) {
      return DataflowError.withoutTrace(
          EnsoContext.get(this).getBuiltins().error().getDivideByZeroError(), this);
    }
  }

  @Specialization
  double doDouble(long self, double that) {
    // No need to try-catch, as floating-point modulo returns NaN for division by zero instead of
    // throwing.
    return self % that;
  }

  @TruffleBoundary
  @Specialization
  Object doBigInteger(long self, EnsoBigInteger that) {
    var selfBigInt = BigInteger.valueOf(self);
    try {
      return toEnsoNumberNode.execute(BigIntegerOps.modulo(selfBigInt, that.getValue()));
    } catch (ArithmeticException e) {
      return DataflowError.withoutTrace(
          EnsoContext.get(this).getBuiltins().error().getDivideByZeroError(), this);
    }
  }

  @Specialization
  Object doLong(EnsoBigInteger self, long that) {
    try {
      return toEnsoNumberNode.execute(BigIntegerOps.modulo(self.getValue(), that));
    } catch (ArithmeticException e) {
      return DataflowError.withoutTrace(
          EnsoContext.get(this).getBuiltins().error().getDivideByZeroError(), this);
    }
  }

  @Specialization
  double doDouble(EnsoBigInteger self, double that) {
    // No need to trap, as floating-point modulo returns NaN for division by zero instead of
    // throwing.
    return BigIntegerOps.toDouble(self.getValue()) % that;
  }

  @Specialization
  Object doBigInteger(EnsoBigInteger self, EnsoBigInteger that) {
    try {
      return toEnsoNumberNode.execute(BigIntegerOps.modulo(self.getValue(), that.getValue()));
    } catch (ArithmeticException e) {
      return DataflowError.withoutTrace(
          EnsoContext.get(this).getBuiltins().error().getDivideByZeroError(), this);
    }
  }

  @Specialization(guards = "isForeignNumber(iop, that)")
  Object doInterop(
      Object self,
      TruffleObject that,
      @CachedLibrary(limit = "3") InteropLibrary iop,
      @Cached ModNode delegate) {
    return super.doInterop(self, that, iop, delegate);
  }

  @Fallback
  Object doOther(Object self, Object that) {
    throw throwTypeErrorIfNotInt(self, that);
  }
}
