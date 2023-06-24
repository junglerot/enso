package org.enso.table.data.column.builder;

import org.enso.base.polyglot.NumericConverter;
import org.enso.table.data.column.operation.cast.ToFloatStorageConverter;
import org.enso.table.data.column.storage.BoolStorage;
import org.enso.table.data.column.storage.numeric.DoubleStorage;
import org.enso.table.data.column.storage.numeric.LongStorage;
import org.enso.table.data.column.storage.Storage;
import org.enso.table.data.column.storage.type.BooleanType;
import org.enso.table.data.column.storage.type.FloatType;
import org.enso.table.data.column.storage.type.IntegerType;
import org.enso.table.data.column.storage.type.StorageType;
import org.enso.table.util.BitSets;

import java.util.BitSet;
import java.util.Objects;

/**
 * A builder for floating point columns.
 */
public class DoubleBuilder extends NumericBuilder {
  DoubleBuilder(BitSet isMissing, long[] data, int currentSize) {
    super(isMissing, data, currentSize);
  }

  @Override
  public void writeTo(Object[] items) {
    for (int i = 0; i < currentSize; i++) {
      if (isMissing.get(i)) {
        items[i] = null;
      } else {
        items[i] = Double.longBitsToDouble(data[i]);
      }
    }
  }

  @Override
  public boolean canRetypeTo(StorageType type) {
    return false;
  }

  @Override
  public TypedBuilder retypeTo(StorageType type) {
    throw new UnsupportedOperationException();
  }

  @Override
  public StorageType getType() {
    return FloatType.FLOAT_64;
  }

  @Override
  public void appendNoGrow(Object o) {
    if (o == null) {
      isMissing.set(currentSize++);
    } else {
      double value = NumericConverter.coerceToDouble(o);
      data[currentSize++] = Double.doubleToRawLongBits(value);
    }
  }

  @Override
  public boolean accepts(Object o) {
    return NumericConverter.isCoercibleToDouble(o);
  }

  @Override
  public void appendBulkStorage(Storage<?> storage) {
    if (Objects.equals(storage.getType(), FloatType.FLOAT_64)) {
      if (storage instanceof DoubleStorage doubleStorage) {
        int n = doubleStorage.size();
        ensureFreeSpaceFor(n);
        System.arraycopy(doubleStorage.getRawData(), 0, data, currentSize, n);
        BitSets.copy(doubleStorage.getIsMissing(), isMissing, currentSize, n);
        currentSize += n;
      } else {
        throw new IllegalStateException(
            "Unexpected storage implementation for type DOUBLE: "
                + storage
                + ". This is a bug in the Table library.");
      }
    } else if (Objects.equals(storage.getType(), IntegerType.INT_64)) {
      if (storage instanceof LongStorage longStorage) {
        int n = longStorage.size();
        BitSets.copy(longStorage.getIsMissing(), isMissing, currentSize, n);
        for (int i = 0; i < n; i++) {
          data[currentSize++] = Double.doubleToRawLongBits((double) longStorage.getItem(i));
        }
      } else {
        throw new IllegalStateException(
            "Unexpected storage implementation for type LONG: "
                + storage
                + ". This is a bug in the Table library.");
      }
    } else if (Objects.equals(storage.getType(), BooleanType.INSTANCE)) {
      if (storage instanceof BoolStorage boolStorage) {
        int n = boolStorage.size();
        for (int i = 0; i < n; i++) {
          if (boolStorage.isNa(i)) {
            isMissing.set(currentSize++);
          } else {
            double x = ToFloatStorageConverter.booleanAsDouble(boolStorage.getItem(i));
            data[currentSize++] = Double.doubleToRawLongBits(x);
          }
        }
      } else {
        throw new IllegalStateException(
            "Unexpected storage implementation for type BOOLEAN: "
                + storage
                + ". This is a bug in the Table library.");
      }
    } else {
      throw new StorageTypeMismatch(getType(), storage.getType());
    }
  }

  /**
   * Append a new double to this builder.
   *
   * @param data the double to append
   */
  public void appendDouble(double data) {
    if (currentSize >= this.data.length) {
      grow();
    }
    appendRawNoGrow(Double.doubleToRawLongBits(data));
  }

  @Override
  public Storage<Double> seal() {
    return new DoubleStorage(data, currentSize, isMissing);
  }
}
