package org.enso.table.data.column.storage.datetime;

import java.time.ZonedDateTime;
import org.enso.table.data.column.builder.object.Builder;
import org.enso.table.data.column.builder.object.DateTimeBuilder;
import org.enso.table.data.column.operation.map.MapOpStorage;
import org.enso.table.data.column.operation.map.UnaryIntegerOp;
import org.enso.table.data.column.operation.map.datetime.DateTimeIsInOp;
import org.enso.table.data.column.storage.ObjectStorage;
import org.enso.table.data.column.storage.SpecializedStorage;
import org.enso.table.data.column.storage.type.DateTimeType;
import org.enso.table.data.column.storage.type.StorageType;

public final class DateTimeStorage extends SpecializedStorage<ZonedDateTime> {
  /**
   * @param data the underlying data
   * @param size the number of items stored
   */
  public DateTimeStorage(ZonedDateTime[] data, int size) {
    super(data, size, ops);
  }

  private static final MapOpStorage<ZonedDateTime, SpecializedStorage<ZonedDateTime>> ops =
      buildOps();

  private static MapOpStorage<ZonedDateTime, SpecializedStorage<ZonedDateTime>> buildOps() {
    MapOpStorage<ZonedDateTime, SpecializedStorage<ZonedDateTime>> t =
        ObjectStorage.buildObjectOps();
    t.add(new DateTimeIsInOp<>(ZonedDateTime.class));
    t.add(
        new UnaryIntegerOp<>(Maps.YEAR) {
          @Override
          protected long doOperation(ZonedDateTime date) {
            return (long) date.getYear();
          }
        });
    t.add(
        new UnaryIntegerOp<>(Maps.MONTH) {
          @Override
          protected long doOperation(ZonedDateTime date) {
            return (long) date.getMonthValue();
          }
        });
    t.add(
        new UnaryIntegerOp<>(Maps.DAY) {
          @Override
          protected long doOperation(ZonedDateTime date) {
            return (long) date.getDayOfMonth();
          }
        });
    return t;
  }

  @Override
  protected SpecializedStorage<ZonedDateTime> newInstance(ZonedDateTime[] data, int size) {
    return new DateTimeStorage(data, size);
  }

  @Override
  protected ZonedDateTime[] newUnderlyingArray(int size) {
    return new ZonedDateTime[size];
  }

  @Override
  public StorageType getType() {
    return DateTimeType.INSTANCE;
  }

  @Override
  public Builder createDefaultBuilderOfSameType(int capacity) {
    return new DateTimeBuilder(capacity);
  }
}
