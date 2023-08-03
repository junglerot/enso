package org.enso.interpreter.node.expression.builtin.number.utils;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.NeverDefault;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.CountingConditionProfile;
import java.math.BigInteger;
import org.enso.interpreter.runtime.number.EnsoBigInteger;

@ReportPolymorphism
@NodeInfo(description = "Takes a big integer and casts it to a long, if the operation is safe.")
public class ToEnsoNumberNode extends Node {
  private static ToEnsoNumberNode uncached;
  private final CountingConditionProfile fitsProfile;

  private ToEnsoNumberNode(CountingConditionProfile fitsProfile) {
    this.fitsProfile = fitsProfile;
  }

  /** @return a new instance of this node. */
  @NeverDefault
  public static ToEnsoNumberNode create() {
    return new ToEnsoNumberNode(CountingConditionProfile.create());
  }

  @NeverDefault
  public static ToEnsoNumberNode getUncached() {
    if (uncached == null) {
      uncached = new ToEnsoNumberNode(CountingConditionProfile.getUncached());
    }
    return uncached;
  }

  /**
   * Execute the node.
   *
   * @param bigInteger the big integer to downcast.
   * @return a long with the same value if possible, the original integer wrapped in {@link
   *     org.enso.interpreter.runtime.number.EnsoBigInteger} otherwise.
   */
  public Object execute(BigInteger bigInteger) {
    if (fitsProfile.profile(BigIntegerOps.fitsInLong(bigInteger))) {
      return toLong(bigInteger);
    }
    return new EnsoBigInteger(bigInteger);
  }

  @CompilerDirectives.TruffleBoundary
  private static long toLong(BigInteger bigInteger) {
    return bigInteger.longValue();
  }
}
