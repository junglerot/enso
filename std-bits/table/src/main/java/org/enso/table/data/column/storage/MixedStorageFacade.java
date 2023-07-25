package org.enso.table.data.column.storage;

import java.util.BitSet;
import java.util.List;
import org.enso.table.data.column.builder.Builder;
import org.enso.table.data.column.builder.MixedBuilder;
import org.enso.table.data.column.operation.map.MapOperationProblemBuilder;
import org.enso.table.data.column.storage.type.AnyObjectType;
import org.enso.table.data.column.storage.type.StorageType;
import org.enso.table.data.mask.OrderMask;
import org.enso.table.data.mask.SliceRange;

/**
 * Wraps a storage of any type and alters its reported storage to be of type AnyObject.
 *
 * <p>This is used to ensure that we can change a column's type to Mixed without changing its
 * underlying storage unnecessarily.
 */
public class MixedStorageFacade extends Storage<Object> {
  private final Storage<?> underlyingStorage;

  public MixedStorageFacade(Storage<?> storage) {
    underlyingStorage = storage;
  }

  @Override
  public int size() {
    return underlyingStorage.size();
  }

  @Override
  public int countMissing() {
    return underlyingStorage.countMissing();
  }

  @Override
  public StorageType getType() {
    return AnyObjectType.INSTANCE;
  }

  @Override
  public StorageType inferPreciseType() {
    return underlyingStorage.inferPreciseType();
  }

  @Override
  public boolean isNa(long idx) {
    return underlyingStorage.isNa(idx);
  }

  @Override
  public Object getItemBoxed(int idx) {
    return underlyingStorage.getItemBoxed(idx);
  }

  @Override
  public boolean isUnaryOpVectorized(String name) {
    return underlyingStorage.isUnaryOpVectorized(name);
  }

  @Override
  public Storage<?> runVectorizedUnaryMap(String name, MapOperationProblemBuilder problemBuilder) {
    return underlyingStorage.runVectorizedUnaryMap(name, problemBuilder);
  }

  @Override
  public boolean isBinaryOpVectorized(String name) {
    return underlyingStorage.isBinaryOpVectorized(name);
  }

  @Override
  public Storage<?> runVectorizedBinaryMap(
      String name, Object argument, MapOperationProblemBuilder problemBuilder) {
    return underlyingStorage.runVectorizedBinaryMap(name, argument, problemBuilder);
  }

  @Override
  public Storage<?> runVectorizedZip(
      String name, Storage<?> argument, MapOperationProblemBuilder problemBuilder) {
    return underlyingStorage.runVectorizedZip(name, argument, problemBuilder);
  }

  @Override
  public Storage<Object> mask(BitSet mask, int cardinality) {
    Storage<?> newStorage = underlyingStorage.mask(mask, cardinality);
    return new MixedStorageFacade(newStorage);
  }

  @Override
  public Storage<Object> applyMask(OrderMask mask) {
    Storage<?> newStorage = underlyingStorage.applyMask(mask);
    return new MixedStorageFacade(newStorage);
  }

  @Override
  public Storage<Object> countMask(int[] counts, int total) {
    Storage<?> newStorage = underlyingStorage.countMask(counts, total);
    return new MixedStorageFacade(newStorage);
  }

  @Override
  public Storage<Object> slice(int offset, int limit) {
    Storage<?> newStorage = underlyingStorage.slice(offset, limit);
    return new MixedStorageFacade(newStorage);
  }

  @Override
  public Builder createDefaultBuilderOfSameType(int capacity) {
    return new MixedBuilder(capacity);
  }

  @Override
  public Storage<Object> slice(List<SliceRange> ranges) {
    Storage<?> newStorage = underlyingStorage.slice(ranges);
    return new MixedStorageFacade(newStorage);
  }

  @Override
  public Storage<?> tryGettingMoreSpecializedStorage() {
    return underlyingStorage.tryGettingMoreSpecializedStorage();
  }
}
