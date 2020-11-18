package org.enso.interpreter.node.expression.builtin.number.decimal;

import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.ConditionProfile;
import java.math.BigDecimal;
import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.node.expression.builtin.number.utils.BigIntegerOps;
import org.enso.interpreter.runtime.number.EnsoBigInteger;

@BuiltinMethod(
    type = "Decimal",
    name = "floor",
    description = "Decimal floor, converting to a small or big integer depending on size.")
public class FloorNode extends Node {
  private final ConditionProfile fitsProfile = ConditionProfile.createCountingProfile();

  Object execute(double _this) {
    double floor = Math.floor(_this);
    if (fitsProfile.profile(BigIntegerOps.fitsInLong(floor))) {
      return (long) floor;
    } else {
      return new EnsoBigInteger(BigDecimal.valueOf(floor).toBigIntegerExact());
    }
  }
}
