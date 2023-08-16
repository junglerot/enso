package org.enso.table.data.column.storage.numeric;

import java.util.BitSet;
import java.util.List;
import org.enso.table.data.column.builder.Builder;
import org.enso.table.data.column.builder.NumericBuilder;
import org.enso.table.data.column.operation.map.MapOperationProblemBuilder;
import org.enso.table.data.column.operation.map.MapOperationStorage;
import org.enso.table.data.column.operation.map.UnaryMapOperation;
import org.enso.table.data.column.operation.map.numeric.DoubleBooleanOp;
import org.enso.table.data.column.operation.map.numeric.DoubleComparison;
import org.enso.table.data.column.operation.map.numeric.DoubleIsInOp;
import org.enso.table.data.column.operation.map.numeric.DoubleLongMapOpWithSpecialNumericHandling;
import org.enso.table.data.column.operation.map.numeric.DoubleNumericOp;
import org.enso.table.data.column.operation.map.numeric.DoubleRoundOp;
import org.enso.table.data.column.storage.BoolStorage;
import org.enso.table.data.column.storage.Storage;
import org.enso.table.data.column.storage.type.FloatType;
import org.enso.table.data.column.storage.type.StorageType;
import org.enso.table.data.index.Index;
import org.enso.table.data.mask.OrderMask;
import org.enso.table.data.mask.SliceRange;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/** A column containing floating point numbers. */
public final class DoubleStorage extends NumericStorage<Double> {
  private final long[] data;
  private final BitSet isMissing;
  private final int size;
  private static final MapOperationStorage<Double, DoubleStorage> ops = buildOps();

  /**
   * @param data the underlying data
   * @param size the number of items stored
   * @param isMissing a bit set denoting at index {@code i} whether the value at index {@code i} is
   *     missing.
   */
  public DoubleStorage(long[] data, int size, BitSet isMissing) {
    this.data = data;
    this.isMissing = isMissing;
    this.size = size;
  }

  public static DoubleStorage makeEmpty(int size) {
    BitSet isMissing = new BitSet(size);
    isMissing.set(0, size);
    return new DoubleStorage(new long[0], size, isMissing);
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
  public double getItem(long idx) {
    return Double.longBitsToDouble(data[(int) idx]);
  }

  @Override
  public Double getItemBoxed(int idx) {
    return isMissing.get(idx) ? null : Double.longBitsToDouble(data[idx]);
  }

  @Override
  public boolean isUnaryOpVectorized(String name) {
    return ops.isSupportedUnary(name);
  }

  @Override
  public Storage<?> runVectorizedUnaryMap(String name, MapOperationProblemBuilder problemBuilder) {
    return ops.runUnaryMap(name, this, problemBuilder);
  }

  /** @inheritDoc */
  @Override
  public StorageType getType() {
    return FloatType.FLOAT_64;
  }

  /** @inheritDoc */
  @Override
  public boolean isNa(long idx) {
    return isMissing.get((int) idx);
  }

  @Override
  public boolean isBinaryOpVectorized(String op) {
    return ops.isSupportedBinary(op);
  }

  @Override
  public Storage<?> runVectorizedBinaryMap(
      String name, Object argument, MapOperationProblemBuilder problemBuilder) {
    return ops.runBinaryMap(name, this, argument, problemBuilder);
  }

  @Override
  public boolean isTernaryOpVectorized(String op) {
    return ops.isSupportedTernary(op);
  }

  @Override
  public Storage<?> runVectorizedTernaryMap(
      String name, Object argument0, Object argument1, MapOperationProblemBuilder problemBuilder) {
    return ops.runTernaryMap(name, this, argument0, argument1, problemBuilder);
  }

  @Override
  public Storage<?> runVectorizedZip(
      String name, Storage<?> argument, MapOperationProblemBuilder problemBuilder) {
    return ops.runZip(name, this, argument, problemBuilder);
  }

  private Storage<?> fillMissingDouble(double arg) {
    final var builder = NumericBuilder.createDoubleBuilder(size());
    long rawArg = Double.doubleToRawLongBits(arg);
    Context context = Context.getCurrent();
    for (int i = 0; i < size(); i++) {
      if (isMissing.get(i)) {
        builder.appendRawNoGrow(rawArg);
      } else {
        builder.appendRawNoGrow(data[i]);
      }

      context.safepoint();
    }
    return builder.seal();
  }

  @Override
  public Storage<?> fillMissing(Value arg) {
    if (arg.isNumber()) {
      if (arg.fitsInLong()) {
        return fillMissingDouble(arg.asLong());
      } else if (arg.fitsInDouble()) {
        return fillMissingDouble(arg.asDouble());
      }
    }

    return super.fillMissing(arg);
  }

  @Override
  public Storage<Double> mask(BitSet mask, int cardinality) {
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
    return new DoubleStorage(newData, cardinality, newMissing);
  }

  @Override
  public Storage<Double> applyMask(OrderMask mask) {
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
    return new DoubleStorage(newData, positions.length, newMissing);
  }

  @Override
  public Storage<Double> countMask(int[] counts, int total) {
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
    return new DoubleStorage(newData, total, newMissing);
  }

  public BitSet getIsMissing() {
    return isMissing;
  }

  public long[] getRawData() {
    return data;
  }

  private static MapOperationStorage<Double, DoubleStorage> buildOps() {
    MapOperationStorage<Double, DoubleStorage> ops = new MapOperationStorage<>();
    ops.add(
            new DoubleNumericOp(Maps.ADD) {
              @Override
              protected double doDouble(
                  double a, double b, int ix, MapOperationProblemBuilder problemBuilder) {
                return a + b;
              }
            })
        .add(
            new DoubleNumericOp(Maps.SUB) {
              @Override
              protected double doDouble(
                  double a, double b, int ix, MapOperationProblemBuilder problemBuilder) {
                return a - b;
              }
            })
        .add(
            new DoubleNumericOp(Maps.MUL) {
              @Override
              protected double doDouble(
                  double a, double b, int ix, MapOperationProblemBuilder problemBuilder) {
                return a * b;
              }
            })
        .add(
            new DoubleNumericOp(Maps.DIV) {
              @Override
              protected double doDouble(
                  double a, double b, int ix, MapOperationProblemBuilder problemBuilder) {
                if (b == 0.0) {
                  problemBuilder.reportDivisionByZero(ix);
                }
                return a / b;
              }
            })
        .add(
            new DoubleNumericOp(Maps.MOD) {
              @Override
              protected double doDouble(
                  double a, double b, int ix, MapOperationProblemBuilder problemBuilder) {
                if (b == 0.0) {
                  problemBuilder.reportDivisionByZero(ix);
                }
                return a % b;
              }
            })
        .add(
            new DoubleNumericOp(Maps.POWER) {
              @Override
              protected double doDouble(
                  double a, double b, int ix, MapOperationProblemBuilder problemBuilder) {
                return Math.pow(a, b);
              }
            })
        .add(
            new DoubleLongMapOpWithSpecialNumericHandling(Maps.TRUNCATE) {
              @Override
              protected long doOperation(double a) {
                return (long) a;
              }
            })
        .add(
            new DoubleLongMapOpWithSpecialNumericHandling(Maps.CEIL) {
              @Override
              protected long doOperation(double a) {
                return (long) Math.ceil(a);
              }
            })
        .add(
            new DoubleLongMapOpWithSpecialNumericHandling(Maps.FLOOR) {
              @Override
              protected long doOperation(double a) {
                return (long) Math.floor(a);
              }
            })
        .add(new DoubleRoundOp(Maps.ROUND))
        .add(
            new DoubleComparison(Maps.LT) {
              @Override
              protected boolean doDouble(double a, double b) {
                return a < b;
              }
            })
        .add(
            new DoubleComparison(Maps.LTE) {
              @Override
              protected boolean doDouble(double a, double b) {
                return a <= b;
              }
            })
        .add(
            new DoubleBooleanOp(Maps.EQ) {
              @Override
              public BoolStorage runBinaryMap(
                  DoubleStorage storage, Object arg, MapOperationProblemBuilder problemBuilder) {
                if (arg != null) {
                  problemBuilder.reportFloatingPointEquality(-1);
                }
                return super.runBinaryMap(storage, arg, problemBuilder);
              }

              @Override
              public BoolStorage runZip(
                  DoubleStorage storage,
                  Storage<?> arg,
                  MapOperationProblemBuilder problemBuilder) {
                if (arg.countMissing() < arg.size()) {
                  problemBuilder.reportFloatingPointEquality(-1);
                }
                return super.runZip(storage, arg, problemBuilder);
              }

              @Override
              protected boolean doDouble(double a, double b) {
                return a == b;
              }

              @Override
              protected boolean doObject(double a, Object o) {
                return false;
              }
            })
        .add(
            new DoubleComparison(Maps.GT) {
              @Override
              protected boolean doDouble(double a, double b) {
                return a > b;
              }
            })
        .add(
            new DoubleComparison(Maps.GTE) {
              @Override
              protected boolean doDouble(double a, double b) {
                return a >= b;
              }
            })
        .add(
            new UnaryMapOperation<>(Maps.IS_NOTHING) {
              @Override
              public BoolStorage runUnaryMap(
                  DoubleStorage storage, MapOperationProblemBuilder problemBuilder) {
                return new BoolStorage(storage.isMissing, new BitSet(), storage.size, false);
              }
            })
        .add(
            new UnaryMapOperation<>(Maps.IS_NAN) {
              @Override
              public BoolStorage runUnaryMap(
                  DoubleStorage storage, MapOperationProblemBuilder problemBuilder) {
                BitSet nans = new BitSet();
                Context context = Context.getCurrent();
                for (int i = 0; i < storage.size; i++) {
                  if (!storage.isNa(i) && Double.isNaN(storage.getItem(i))) {
                    nans.set(i);
                  }

                  context.safepoint();
                }
                return new BoolStorage(nans, storage.isMissing, storage.size, false);
              }
            })
        .add(
            new UnaryMapOperation<>(Maps.IS_INFINITE) {
              @Override
              public BoolStorage runUnaryMap(
                  DoubleStorage storage, MapOperationProblemBuilder problemBuilder) {
                BitSet infintes = new BitSet();
                Context context = Context.getCurrent();
                for (int i = 0; i < storage.size; i++) {
                  if (!storage.isNa(i) && Double.isInfinite(storage.getItem(i))) {
                    infintes.set(i);
                  }

                  context.safepoint();
                }
                return new BoolStorage(infintes, storage.isMissing, storage.size, false);
              }
            })
        .add(new DoubleIsInOp());
    return ops;
  }

  @Override
  public Storage<Double> slice(int offset, int limit) {
    int newSize = Math.min(size - offset, limit);
    long[] newData = new long[newSize];
    System.arraycopy(data, offset, newData, 0, newSize);
    BitSet newMask = isMissing.get(offset, offset + limit);
    return new DoubleStorage(newData, newSize, newMask);
  }

  @Override
  public Builder createDefaultBuilderOfSameType(int capacity) {
    return NumericBuilder.createDoubleBuilder(capacity);
  }

  @Override
  public Storage<Double> slice(List<SliceRange> ranges) {
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

    return new DoubleStorage(newData, newSize, newMissing);
  }
}
