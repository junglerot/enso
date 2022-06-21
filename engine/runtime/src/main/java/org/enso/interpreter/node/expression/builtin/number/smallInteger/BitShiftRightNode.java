package org.enso.interpreter.node.expression.builtin.number.smallInteger;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.node.expression.builtin.number.utils.BigIntegerOps;
import org.enso.interpreter.runtime.Context;
import org.enso.interpreter.runtime.builtin.Builtins;
import org.enso.interpreter.runtime.callable.atom.Atom;
import org.enso.interpreter.runtime.error.PanicException;
import org.enso.interpreter.runtime.number.EnsoBigInteger;

@BuiltinMethod(type = "Small_Integer", name = "bit_shift_r", description = "Bitwise right-shift.")
public abstract class BitShiftRightNode extends Node {
  abstract Object execute(Object self, Object that);

  static BitShiftRightNode build() {
    return BitShiftRightNodeGen.create();
  }

  @Specialization
  Object doBigInteger(long self, long that, @Cached("build()") BitShiftNode bitShiftNode) {
    return bitShiftNode.execute(self, -1L * that);
  }

  @Specialization
  Object doBigInteger(
      long self, EnsoBigInteger that, @Cached("build()") BitShiftNode bitShiftNode) {
    return bitShiftNode.execute(self, new EnsoBigInteger(BigIntegerOps.negate(that.getValue())));
  }

  @Specialization
  Object doAtomThis(Atom self, Object that) {
    Builtins builtins = Context.get(this).getBuiltins();
    Atom integer = builtins.number().getInteger().newInstance();
    throw new PanicException(builtins.error().makeTypeError(integer, self, "this"), this);
  }

  @Fallback
  Object doOther(Object self, Object that) {
    Builtins builtins = Context.get(this).getBuiltins();
    Atom integer = builtins.number().getInteger().newInstance();
    throw new PanicException(builtins.error().makeTypeError(integer, that, "that"), this);
  }
}
