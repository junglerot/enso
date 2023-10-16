package org.enso.table.data.column.operation.map.numeric;

import java.util.BitSet;
import org.enso.table.data.column.operation.map.MapOperationProblemAggregator;
import org.enso.table.data.column.operation.map.UnaryMapOperation;
import org.enso.table.data.column.storage.Storage;
import org.enso.table.data.column.storage.numeric.LongStorage;
import org.enso.table.data.column.storage.type.IntegerType;
import org.graalvm.polyglot.Context;

/** An operation that takes a single argument of some type and returns an integer. */
public abstract class UnaryIntegerOp<T, I extends Storage<T>> extends UnaryMapOperation<T, I> {

  private static final IntegerType RESULT_TYPE = IntegerType.INT_64;

  public UnaryIntegerOp(String name) {
    super(name);
  }

  protected abstract long doOperation(T value);

  @Override
  protected Storage<?> runUnaryMap(I storage, MapOperationProblemAggregator problemAggregator) {
    Context context = Context.getCurrent();
    BitSet newMissing = new BitSet();
    long[] newVals = new long[storage.size()];
    for (int i = 0; i < storage.size(); i++) {
      if (!storage.isNa(i)) {
        newVals[i] = doOperation(storage.getItemBoxed(i));
      } else {
        newMissing.set(i);
      }

      context.safepoint();
    }

    return new LongStorage(newVals, newVals.length, newMissing, RESULT_TYPE);
  }
}
