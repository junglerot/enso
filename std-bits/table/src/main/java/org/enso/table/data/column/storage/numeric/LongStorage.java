package org.enso.table.data.column.storage.numeric;

import java.math.BigInteger;
import java.util.BitSet;
import java.util.List;
import org.enso.base.polyglot.NumericConverter;
import org.enso.table.data.column.builder.BigIntegerBuilder;
import org.enso.table.data.column.builder.NumericBuilder;
import org.enso.table.data.column.storage.Storage;
import org.enso.table.data.column.storage.type.IntegerType;
import org.enso.table.data.column.storage.type.StorageType;
import org.enso.table.data.index.Index;
import org.enso.table.data.mask.OrderMask;
import org.enso.table.data.mask.SliceRange;
import org.enso.table.problems.ProblemAggregator;
import org.enso.table.util.BitSets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/** A column storing 64-bit integers. */
public final class LongStorage extends AbstractLongStorage {
  // TODO [RW] at some point we will want to add separate storage classes for byte, short and int,
  // for more compact storage and more efficient handling of smaller integers; for now we will be
  // handling this just by checking the bounds
  private final long[] data;
  private final BitSet isMissing;
  private final int size;

  private final IntegerType type;

  /**
   * @param data the underlying data
   * @param size the number of items stored
   * @param isMissing a bit set denoting at index {@code i} whether or not the value at index {@code
   *     i} is missing.
   * @param type the type specifying the bit-width of integers that are allowed in this storage
   */
  public LongStorage(long[] data, int size, BitSet isMissing, IntegerType type) {
    this.data = data;
    this.isMissing = isMissing;
    this.size = size;
    this.type = type;
  }

  public static LongStorage fromArray(long[] data) {
    return new LongStorage(data, data.length, new BitSet(), IntegerType.INT_64);
  }

  public static LongStorage makeEmpty(int size, IntegerType type) {
    BitSet isMissing = new BitSet(size);
    isMissing.set(0, size);
    return new LongStorage(new long[0], size, isMissing, type);
  }

  public LongStorage(long[] data, IntegerType type) {
    this(data, data.length, new BitSet(), type);
  }

  /** @inheritDoc */
  @Override
  public int size() {
    return size;
  }

  /** @inheritDoc */
  @Override
  public int countMissing() {
    return isMissing.cardinality();
  }

  /**
   * @param idx an index
   * @return the data item contained at the given index.
   */
  public long getItem(int idx) {
    return data[idx];
  }

  @Override
  public Long getItemBoxed(int idx) {
    return isMissing.get(idx) ? null : data[idx];
  }

  /** @inheritDoc */
  @Override
  public IntegerType getType() {
    return type;
  }

  /** @inheritDoc */
  @Override
  public boolean isNa(long idx) {
    return isMissing.get((int) idx);
  }

  private Storage<?> fillMissingDouble(double arg, ProblemAggregator problemAggregator) {
    final var builder = NumericBuilder.createDoubleBuilder(size, problemAggregator);
    long rawArg = Double.doubleToRawLongBits(arg);
    Context context = Context.getCurrent();
    for (int i = 0; i < size(); i++) {
      if (isMissing.get(i)) {
        builder.appendRawNoGrow(rawArg);
      } else {
        double coerced = data[i];
        builder.appendRawNoGrow(Double.doubleToRawLongBits(coerced));
      }

      context.safepoint();
    }

    return builder.seal();
  }

  private Storage<?> fillMissingLong(long arg, ProblemAggregator problemAggregator) {
    final var builder =
        NumericBuilder.createLongBuilder(size, IntegerType.INT_64, problemAggregator);
    Context context = Context.getCurrent();
    for (int i = 0; i < size(); i++) {
      if (isMissing.get(i)) {
        builder.appendRawNoGrow(arg);
      } else {
        builder.appendRawNoGrow(data[i]);
      }

      context.safepoint();
    }

    return builder.seal();
  }

  private Storage<?> fillMissingBigInteger(
      BigInteger bigInteger, ProblemAggregator problemAggregator) {
    final var builder = new BigIntegerBuilder(size, problemAggregator);
    Context context = Context.getCurrent();
    for (int i = 0; i < size(); i++) {
      if (isMissing.get(i)) {
        builder.appendRawNoGrow(bigInteger);
      } else {
        builder.appendRawNoGrow(BigInteger.valueOf(data[i]));
      }

      context.safepoint();
    }

    return builder.seal();
  }

  @Override
  public Storage<?> fillMissing(
      Value arg, StorageType commonType, ProblemAggregator problemAggregator) {
    if (arg.isNumber()) {
      if (NumericConverter.isCoercibleToLong(arg.as(Object.class))) {
        return fillMissingLong(arg.asLong(), problemAggregator);
      } else if (NumericConverter.isBigInteger(arg)) {
        return fillMissingBigInteger(arg.asBigInteger(), problemAggregator);
      } else {
        return fillMissingDouble(arg.asDouble(), problemAggregator);
      }
    }

    return super.fillMissing(arg, commonType, problemAggregator);
  }

  @Override
  public Storage<Long> mask(BitSet mask, int cardinality) {
    BitSet newMissing = new BitSet();
    long[] newData = new long[cardinality];
    int resIx = 0;
    Context context = Context.getCurrent();
    for (int i = 0; i < size; i++) {
      if (mask.get(i)) {
        if (isMissing.get(i)) {
          newMissing.set(resIx++);
        } else {
          newData[resIx++] = data[i];
        }
      }

      context.safepoint();
    }
    return new LongStorage(newData, cardinality, newMissing, type);
  }

  @Override
  public Storage<Long> applyMask(OrderMask mask) {
    int[] positions = mask.getPositions();
    long[] newData = new long[positions.length];
    BitSet newMissing = new BitSet();
    Context context = Context.getCurrent();
    for (int i = 0; i < positions.length; i++) {
      if (positions[i] == Index.NOT_FOUND || isMissing.get(positions[i])) {
        newMissing.set(i);
      } else {
        newData[i] = data[positions[i]];
      }

      context.safepoint();
    }
    return new LongStorage(newData, positions.length, newMissing, type);
  }

  @Override
  public Storage<Long> countMask(int[] counts, int total) {
    long[] newData = new long[total];
    BitSet newMissing = new BitSet();
    int pos = 0;
    Context context = Context.getCurrent();
    for (int i = 0; i < counts.length; i++) {
      if (isMissing.get(i)) {
        newMissing.set(pos, pos + counts[i]);
        pos += counts[i];
      } else {
        for (int j = 0; j < counts[i]; j++) {
          newData[pos++] = data[i];
        }
      }

      context.safepoint();
    }
    return new LongStorage(newData, total, newMissing, type);
  }

  @Override
  public BitSet getIsMissing() {
    return isMissing;
  }

  public long[] getRawData() {
    return data;
  }

  @Override
  public LongStorage slice(int offset, int limit) {
    int newSize = Math.min(size - offset, limit);
    long[] newData = new long[newSize];
    System.arraycopy(data, offset, newData, 0, newSize);
    BitSet newMask = isMissing.get(offset, offset + limit);
    return new LongStorage(newData, newSize, newMask, type);
  }

  @Override
  public LongStorage appendNulls(int count) {
    BitSet newMissing = BitSets.makeDuplicate(isMissing);
    newMissing.set(size, size + count);
    long[] newData = new long[size + count];
    System.arraycopy(data, 0, newData, 0, size);
    return new LongStorage(newData, size + count, newMissing, type);
  }

  @Override
  public LongStorage slice(List<SliceRange> ranges) {
    int newSize = SliceRange.totalLength(ranges);
    long[] newData = new long[newSize];
    BitSet newMissing = new BitSet(newSize);
    int offset = 0;
    Context context = Context.getCurrent();
    for (SliceRange range : ranges) {
      int length = range.end() - range.start();
      System.arraycopy(data, range.start(), newData, offset, length);
      for (int i = 0; i < length; ++i) {
        newMissing.set(offset + i, isMissing.get(range.start() + i));
        context.safepoint();
      }
      offset += length;
    }

    return new LongStorage(newData, newSize, newMissing, type);
  }

  /** Widening to a bigger type can be done without copying the data. */
  @Override
  public LongStorage widen(IntegerType widerType) {
    assert widerType.fits(type);
    return new LongStorage(data, size, isMissing, widerType);
  }
}
