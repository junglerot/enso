package org.enso.table.data.column.operation.cast;

import org.enso.table.data.column.builder.DateBuilder;
import org.enso.table.data.column.storage.datetime.DateStorage;
import org.enso.table.data.column.storage.datetime.DateTimeStorage;
import org.enso.table.data.column.storage.Storage;
import org.enso.table.data.column.storage.type.AnyObjectType;

import java.time.LocalDate;
import java.time.ZonedDateTime;

public class ToDateStorageConverter implements StorageConverter<LocalDate> {
  public Storage<LocalDate> cast(Storage<?> storage, CastProblemBuilder problemBuilder) {
    if (storage instanceof DateStorage dateStorage) {
      return dateStorage;
    } else if (storage instanceof DateTimeStorage dateTimeStorage) {
      return convertDateTimeStorage(dateTimeStorage);
    } else if (storage.getType() instanceof AnyObjectType) {
      return castFromMixed(storage, problemBuilder);
    } else {
      throw new IllegalStateException("No known strategy for casting storage " + storage + " to Date.");
    }
  }

  public Storage<LocalDate> castFromMixed(Storage<?> mixedStorage, CastProblemBuilder problemBuilder) {
    DateBuilder builder = new DateBuilder(mixedStorage.size());
    for (int i = 0; i < mixedStorage.size(); i++) {
      Object o = mixedStorage.getItemBoxed(i);
      switch (o) {
        case null -> builder.appendNulls(1);
        case LocalDate d -> builder.append(d);
        case ZonedDateTime d -> builder.append(convertDateTime(d));
        default -> {
          problemBuilder.reportConversionFailure(o);
          builder.appendNulls(1);
        }
      }
    }

    return builder.seal();
  }

  private LocalDate convertDateTime(ZonedDateTime dateTime) {
    return dateTime.toLocalDate();
  }

  private Storage<LocalDate> convertDateTimeStorage(DateTimeStorage dateTimeStorage) {
    DateBuilder builder = new DateBuilder(dateTimeStorage.size());
    for (int i = 0; i < dateTimeStorage.size(); i++) {
      ZonedDateTime dateTime = dateTimeStorage.getItem(i);
      builder.append(convertDateTime(dateTime));
    }
    return builder.seal();
  }
}
