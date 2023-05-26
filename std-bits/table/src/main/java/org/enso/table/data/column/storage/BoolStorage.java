package org.enso.table.data.column.storage;

import org.enso.base.polyglot.Polyglot_Utils;
import org.enso.table.data.column.builder.object.BoolBuilder;
import org.enso.table.data.column.builder.object.Builder;
import org.enso.table.data.column.operation.map.MapOpStorage;
import org.enso.table.data.column.operation.map.MapOperation;
import org.enso.table.data.column.operation.map.MapOperationProblemBuilder;
import org.enso.table.data.column.operation.map.UnaryMapOperation;
import org.enso.table.data.column.operation.map.bool.BooleanIsInOp;
import org.enso.table.data.column.storage.type.BooleanType;
import org.enso.table.data.column.storage.type.StorageType;
import org.enso.table.data.index.Index;
import org.enso.table.data.mask.OrderMask;
import org.enso.table.data.mask.SliceRange;
import org.enso.table.error.UnexpectedColumnTypeException;
import org.enso.table.error.UnexpectedTypeException;
import org.graalvm.polyglot.Value;

import java.util.BitSet;
import java.util.List;
import java.util.function.IntFunction;

/** A boolean column storage. */
public final class BoolStorage extends Storage<Boolean> {
  private static final MapOpStorage<Boolean, BoolStorage> ops = buildOps();
  private final BitSet values;
  private final BitSet isMissing;
  private final int size;
  private final boolean negated;

  public BoolStorage(BitSet values, BitSet isMissing, int size, boolean negated) {
    this.values = values;
    this.isMissing = isMissing;
    this.size = size;
    this.negated = negated;
  }

  public static BoolStorage makeEmpty(int size) {
    BitSet isMissing = new BitSet(size);
    isMissing.set(0, size);
    return new BoolStorage(new BitSet(), isMissing, size, false);
  }

  @Override
  public int size() {
    return size;
  }

  /**
   * @inheritDoc
   */
  @Override
  public int countMissing() {
    return isMissing.cardinality();
  }

  @Override
  public StorageType getType() {
    return BooleanType.INSTANCE;
  }

  @Override
  public Boolean getItemBoxed(int idx) {
    return isMissing.get(idx) ? null : getItem(idx);
  }

  public boolean getItem(long idx) {
    return negated != values.get((int) idx);
  }

  @Override
  public boolean isNa(long idx) {
    return isMissing.get((int) idx);
  }

  @Override
  public boolean isOpVectorized(String name) {
    return ops.isSupported(name);
  }

  @Override
  protected Storage<?> runVectorizedMap(
      String name, Object argument, MapOperationProblemBuilder problemBuilder) {
    return ops.runMap(name, this, argument, problemBuilder);
  }

  @Override
  protected Storage<?> runVectorizedZip(
      String name, Storage<?> argument, MapOperationProblemBuilder problemBuilder) {
    return ops.runZip(name, this, argument, problemBuilder);
  }

  public BitSet getValues() {
    return values;
  }

  public BitSet getIsMissing() {
    return isMissing;
  }

  /**
   * Creates a new BoolStorage in which all missing values have been replaced by arg.
   *
   * <p>It works by setting the new isMissing to an empty bitset and changing the values bitset
   * accordingly. If `arg` is true, new values are `values || isMissing` and if `arg` is false, new
   * values are `values && (~isMissing)`.
   */
  private BoolStorage fillMissingBoolean(boolean arg) {
    final var newValues = (BitSet) values.clone();
    if (arg) {
      newValues.or(isMissing);
    } else {
      newValues.andNot(isMissing);
    }
    return new BoolStorage(newValues, new BitSet(), size, negated);
  }

  @Override
  public Storage<?> fillMissing(Value arg) {
    if (arg.isBoolean()) {
      return fillMissingBoolean(arg.asBoolean());
    } else {
      return super.fillMissing(arg);
    }
  }

  @Override
  public BoolStorage mask(BitSet mask, int cardinality) {
    BitSet newMissing = new BitSet();
    BitSet newValues = new BitSet();
    int resultIx = 0;
    for (int i = 0; i < size; i++) {
      if (mask.get(i)) {
        if (isMissing.get(i)) {
          newMissing.set(resultIx++);
        } else if (values.get(i)) {
          newValues.set(resultIx++);
        } else {
          // We don't set any bits, but still increment the counter to indicate that we have just
          // 'inserted' a false value.
          resultIx++;
        }
      }
    }
    return new BoolStorage(newValues, newMissing, cardinality, negated);
  }

  @Override
  public BoolStorage applyMask(OrderMask mask) {
    int[] positions = mask.getPositions();
    BitSet newNa = new BitSet();
    BitSet newVals = new BitSet();
    for (int i = 0; i < positions.length; i++) {
      if (positions[i] == Index.NOT_FOUND || isMissing.get(positions[i])) {
        newNa.set(i);
      } else if (values.get(positions[i])) {
        newVals.set(i);
      }
    }
    return new BoolStorage(newVals, newNa, positions.length, negated);
  }

  @Override
  public BoolStorage countMask(int[] counts, int total) {
    BitSet newNa = new BitSet();
    BitSet newVals = new BitSet();
    int pos = 0;
    for (int i = 0; i < counts.length; i++) {
      if (isMissing.get(i)) {
        newNa.set(pos, pos + counts[i]);
      } else if (values.get(i)) {
        newVals.set(pos, pos + counts[i]);
      }
      pos += counts[i];
    }
    return new BoolStorage(newVals, newNa, total, negated);
  }

  public boolean isNegated() {
    return negated;
  }

  public Storage<?> iif(Value when_true, Value when_false, StorageType resultStorageType) {
    var on_true = makeRowProvider(when_true);
    var on_false = makeRowProvider(when_false);
    Builder builder = Builder.getForType(resultStorageType, size);
    for (int i = 0; i < size; i++) {
      if (isMissing.get(i)) {
        builder.append(null);
      } else if (getItem(i)) {
        builder.append(on_true.apply(i));
      } else {
        builder.append(on_false.apply(i));
      }
    }
    return builder.seal();
  }

  private static IntFunction<Object> makeRowProvider(Value value) {
    if (value.isHostObject() && value.asHostObject() instanceof Storage<?> s) {
      return i -> (Object) s.getItemBoxed(i);
    }
    var converted = Polyglot_Utils.convertPolyglotValue(value);
    return i -> converted;
  }

  private static MapOpStorage<Boolean, BoolStorage> buildOps() {
    MapOpStorage<Boolean, BoolStorage> ops = new MapOpStorage<>();
    ops.add(
            new UnaryMapOperation<>(Maps.NOT) {
              @Override
              protected BoolStorage run(BoolStorage storage) {
                return new BoolStorage(
                    storage.values, storage.isMissing, storage.size, !storage.negated);
              }
            })
        .add(
            new MapOperation<>(Maps.EQ) {
              @Override
              public BoolStorage runMap(
                  BoolStorage storage, Object arg, MapOperationProblemBuilder problemBuilder) {
                if (arg == null) {
                  return BoolStorage.makeEmpty(storage.size);
                } else if (arg instanceof Boolean v) {
                  if (v) {
                    return storage;
                  } else {
                    return new BoolStorage(
                        storage.values, storage.isMissing, storage.size, !storage.negated);
                  }
                } else {
                  return new BoolStorage(new BitSet(), storage.isMissing, storage.size, false);
                }
              }

              @Override
              public BoolStorage runZip(
                  BoolStorage storage, Storage<?> arg, MapOperationProblemBuilder problemBuilder) {
                BitSet out = new BitSet();
                BitSet missing = new BitSet();
                for (int i = 0; i < storage.size; i++) {
                  if (!storage.isNa(i) && i < arg.size() && !arg.isNa(i)) {
                    if (((Boolean) storage.getItem(i)).equals(arg.getItemBoxed(i))) {
                      out.set(i);
                    }
                  } else {
                    missing.set(i);
                  }
                }
                return new BoolStorage(out, missing, storage.size, false);
              }
            })
        .add(
            new MapOperation<>(Maps.AND) {
              @Override
              public BoolStorage runMap(
                  BoolStorage storage, Object arg, MapOperationProblemBuilder problemBuilder) {
                if (arg == null) {
                  return BoolStorage.makeEmpty(storage.size);
                } else if (arg instanceof Boolean v) {
                  if (v) {
                    return storage;
                  } else {
                    return new BoolStorage(new BitSet(), storage.isMissing, storage.size, false);
                  }
                } else {
                  throw new UnexpectedTypeException("a Boolean");
                }
              }

              @Override
              public BoolStorage runZip(
                  BoolStorage storage, Storage<?> arg, MapOperationProblemBuilder problemBuilder) {
                if (arg instanceof BoolStorage v) {
                  BitSet missing = v.isMissing.get(0, storage.size);
                  missing.or(storage.isMissing);
                  BitSet out = v.values.get(0, storage.size);
                  boolean negated;
                  if (storage.negated && v.negated) {
                    out.or(storage.values);
                    negated = true;
                  } else if (storage.negated) {
                    out.andNot(storage.values);
                    negated = false;
                  } else if (v.negated) {
                    out.flip(0, storage.size);
                    out.and(storage.values);
                    negated = false;
                  } else {
                    out.and(storage.values);
                    negated = false;
                  }
                  return new BoolStorage(out, missing, storage.size, negated);
                } else {
                  throw new UnexpectedColumnTypeException("Boolean");
                }
              }
            })
        .add(
            new MapOperation<>(Maps.OR) {
              @Override
              public BoolStorage runMap(
                  BoolStorage storage, Object arg, MapOperationProblemBuilder problemBuilder) {
                if (arg == null) {
                  return BoolStorage.makeEmpty(storage.size);
                } else if (arg instanceof Boolean v) {
                  if (v) {
                    return new BoolStorage(new BitSet(), storage.isMissing, storage.size, true);
                  } else {
                    return storage;
                  }
                } else {
                  throw new UnexpectedTypeException("a Boolean");
                }
              }

              @Override
              public BoolStorage runZip(
                  BoolStorage storage, Storage<?> arg, MapOperationProblemBuilder problemBuilder) {
                if (arg instanceof BoolStorage v) {
                  BitSet missing = v.isMissing.get(0, storage.size);
                  missing.or(storage.isMissing);
                  BitSet out = v.values.get(0, storage.size);
                  boolean negated;
                  if (storage.negated && v.negated) {
                    out.and(storage.values);
                    negated = true;
                  } else if (storage.negated) {
                    out.flip(0, storage.size);
                    out.and(storage.values);
                    negated = true;
                  } else if (v.negated) {
                    out.flip(0, storage.size);
                    out.or(storage.values);
                    negated = false;
                  } else {
                    out.or(storage.values);
                    negated = false;
                  }
                  return new BoolStorage(out, missing, storage.size, negated);
                } else {
                  throw new UnexpectedColumnTypeException("Boolean");
                }
              }
            })
        .add(
            new UnaryMapOperation<>(Maps.IS_NOTHING) {
              @Override
              public BoolStorage run(BoolStorage storage) {
                return new BoolStorage(storage.isMissing, new BitSet(), storage.size, false);
              }
            })
        .add(new BooleanIsInOp());
    return ops;
  }

  /** Creates a mask that selects elements corresponding to true entries in the passed storage. */
  public static BitSet toMask(BoolStorage storage) {
    BitSet mask = new BitSet();
    mask.or(storage.getValues());
    if (storage.isNegated()) {
      mask.flip(0, storage.size());
    }
    mask.andNot(storage.getIsMissing());
    return mask;
  }

  @Override
  public BoolStorage slice(int offset, int limit) {
    int newSize = Math.min(size - offset, limit);
    return new BoolStorage(
        values.get(offset, offset + limit),
        isMissing.get(offset, offset + limit),
        newSize,
        negated);
  }

  @Override
  public Builder createDefaultBuilderOfSameType(int capacity) {
    return new BoolBuilder(capacity);
  }

  @Override
  public BoolStorage slice(List<SliceRange> ranges) {
    int newSize = SliceRange.totalLength(ranges);
    BitSet newValues = new BitSet(newSize);
    BitSet newMissing = new BitSet(newSize);
    int offset = 0;
    for (SliceRange range : ranges) {
      int length = range.end() - range.start();
      for (int i = 0; i < length; ++i) {
        newValues.set(offset + i, values.get(range.start() + i));
        newMissing.set(offset + i, isMissing.get(range.start() + i));
      }
      offset += length;
    }

    return new BoolStorage(newValues, newMissing, newSize, negated);
  }
}
