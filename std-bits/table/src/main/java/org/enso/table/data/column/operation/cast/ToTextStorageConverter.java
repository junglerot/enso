package org.enso.table.data.column.operation.cast;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;
import org.enso.polyglot.common_utils.Core_Date_Utils;
import org.enso.table.data.column.builder.StringBuilder;
import org.enso.table.data.column.storage.BoolStorage;
import org.enso.table.data.column.storage.Storage;
import org.enso.table.data.column.storage.StringStorage;
import org.enso.table.data.column.storage.datetime.DateStorage;
import org.enso.table.data.column.storage.datetime.DateTimeStorage;
import org.enso.table.data.column.storage.datetime.TimeOfDayStorage;
import org.enso.table.data.column.storage.numeric.AbstractLongStorage;
import org.enso.table.data.column.storage.numeric.DoubleStorage;
import org.enso.table.data.column.storage.type.AnyObjectType;
import org.enso.table.data.column.storage.type.TextType;
import org.graalvm.polyglot.Context;

public class ToTextStorageConverter implements StorageConverter<String> {
  private final TextType targetType;

  public ToTextStorageConverter(TextType textType) {
    targetType = textType;
  }

  @Override
  public Storage<String> cast(Storage<?> storage, CastProblemAggregator problemAggregator) {
    if (storage instanceof StringStorage stringStorage) {
      if (canAvoidCopying(stringStorage)) {
        return retypeStringStorage(stringStorage);
      } else {
        return adaptStringStorage(stringStorage, problemAggregator);
      }
    }
    if (storage instanceof AbstractLongStorage longStorage) {
      return castLongStorage(longStorage, problemAggregator);
    } else if (storage instanceof DoubleStorage doubleStorage) {
      return castDoubleStorage(doubleStorage, problemAggregator);
    } else if (storage instanceof BoolStorage boolStorage) {
      return castBoolStorage(boolStorage, problemAggregator);
    } else if (storage instanceof TimeOfDayStorage timeOfDayStorage) {
      return castDateTimeStorage(timeOfDayStorage, this::convertTime, problemAggregator);
    } else if (storage instanceof DateStorage dateStorage) {
      return castDateTimeStorage(dateStorage, this::convertDate, problemAggregator);
    } else if (storage instanceof DateTimeStorage dateTimeStorage) {
      return castDateTimeStorage(dateTimeStorage, this::convertDateTime, problemAggregator);
    } else if (storage.getType() instanceof AnyObjectType) {
      return castFromMixed(storage, problemAggregator);
    } else {
      throw new IllegalStateException(
          "No known strategy for casting storage " + storage + " to Text.");
    }
  }

  public Storage<String> castFromMixed(
      Storage<?> mixedStorage, CastProblemAggregator problemAggregator) {
    Context context = Context.getCurrent();
    StringBuilder builder = new StringBuilder(mixedStorage.size(), targetType);
    for (int i = 0; i < mixedStorage.size(); i++) {
      Object o = mixedStorage.getItemBoxed(i);
      switch (o) {
        case null -> builder.appendNulls(1);
        case LocalTime d -> builder.append(adapt(convertTime(d), problemAggregator));
        case LocalDate d -> builder.append(adapt(convertDate(d), problemAggregator));
        case ZonedDateTime d -> builder.append(adapt(convertDateTime(d), problemAggregator));
        case Boolean b -> builder.append(adapt(convertBoolean(b), problemAggregator));
        default -> builder.append(adapt(o.toString(), problemAggregator));
      }

      context.safepoint();
    }

    return builder.seal();
  }

  private final DateTimeFormatter dateFormatter = Core_Date_Utils.defaultLocalDateFormatter;
  private final DateTimeFormatter timeFormatter = Core_Date_Utils.defaultLocalTimeFormatter;
  private final DateTimeFormatter dateTimeFormatter = Core_Date_Utils.defaultZonedDateTimeFormatter;

  private String convertDate(LocalDate date) {
    return date.format(dateFormatter);
  }

  private String convertTime(LocalTime time) {
    return time.format(timeFormatter);
  }

  private String convertDateTime(ZonedDateTime dateTime) {
    return dateTime.format(dateTimeFormatter);
  }

  private String convertBoolean(Boolean b) {
    return b ? "True" : "False";
  }

  private Storage<String> castLongStorage(
      AbstractLongStorage longStorage, CastProblemAggregator problemAggregator) {
    Context context = Context.getCurrent();
    StringBuilder builder = new StringBuilder(longStorage.size(), targetType);
    for (int i = 0; i < longStorage.size(); i++) {
      if (longStorage.isNa(i)) {
        builder.appendNulls(1);
      } else {
        long value = longStorage.getItem(i);
        builder.append(adapt(Long.toString(value), problemAggregator));
      }

      context.safepoint();
    }

    return builder.seal();
  }

  private Storage<String> castBoolStorage(
      BoolStorage boolStorage, CastProblemAggregator problemAggregator) {
    Context context = Context.getCurrent();
    StringBuilder builder = new StringBuilder(boolStorage.size(), targetType);
    for (int i = 0; i < boolStorage.size(); i++) {
      if (boolStorage.isNa(i)) {
        builder.appendNulls(1);
      } else {
        boolean value = boolStorage.getItem(i);
        builder.append(adapt(convertBoolean(value), problemAggregator));
      }

      context.safepoint();
    }

    return builder.seal();
  }

  private Storage<String> castDoubleStorage(
      DoubleStorage doubleStorage, CastProblemAggregator problemAggregator) {
    Context context = Context.getCurrent();
    StringBuilder builder = new StringBuilder(doubleStorage.size(), targetType);
    for (int i = 0; i < doubleStorage.size(); i++) {
      if (doubleStorage.isNa(i)) {
        builder.appendNulls(1);
      } else {
        double value = doubleStorage.getItemAsDouble(i);
        builder.append(adapt(Double.toString(value), problemAggregator));
      }

      context.safepoint();
    }

    return builder.seal();
  }

  private <T> Storage<String> castDateTimeStorage(
      Storage<T> storage, Function<T, String> converter, CastProblemAggregator problemAggregator) {
    Context context = Context.getCurrent();
    StringBuilder builder = new StringBuilder(storage.size(), targetType);
    for (int i = 0; i < storage.size(); i++) {
      if (storage.isNa(i)) {
        builder.appendNulls(1);
      } else {
        T value = storage.getItemBoxed(i);
        String converted = converter.apply(value);
        builder.append(adapt(converted, problemAggregator));
      }

      context.safepoint();
    }

    return builder.seal();
  }

  private String adapt(String value, CastProblemAggregator problemAggregator) {
    String adapted = adaptWithoutWarning(value);

    // If the value was truncated, report the data loss.
    // (We can use the codepoint lengths here because truncation on grapheme length will still
    // change the codepoint
    // length too, and this check is simply faster.)
    if (adapted.length() < value.length()) {
      problemAggregator.reportTextTooLong(value);
    }

    return adapted;
  }

  private String adaptWithoutWarning(String value) {
    return targetType.adapt(value);
  }

  private Storage<String> adaptStringStorage(
      StringStorage stringStorage, CastProblemAggregator problemAggregator) {
    Context context = Context.getCurrent();
    StringBuilder builder = new StringBuilder(stringStorage.size(), targetType);
    for (int i = 0; i < stringStorage.size(); i++) {
      if (stringStorage.isNa(i)) {
        builder.appendNulls(1);
      } else {
        String value = stringStorage.getItem(i);
        // Adapting an existing string storage into a new type is done without warnings.
        builder.append(adaptWithoutWarning(value));
      }

      context.safepoint();
    }

    return builder.seal();
  }

  private boolean canAvoidCopying(StringStorage stringStorage) {
    if (targetType.fitsExactly(stringStorage.getType())) {
      return true;
    }

    long maxLength = Long.MIN_VALUE;
    long minLength = Long.MAX_VALUE;
    for (int i = 0; i < stringStorage.size(); i++) {
      String value = stringStorage.getItem(i);
      if (value == null) {
        continue;
      }

      long length = value.length();
      if (length > maxLength) {
        maxLength = length;
      }
      if (length < minLength) {
        minLength = length;
      }
    }

    if (targetType.fixedLength()) {
      boolean effectivelyFixedLength = minLength == maxLength;
      return effectivelyFixedLength && targetType.maxLength() == maxLength;
    } else {
      return targetType.maxLength() == -1 || maxLength <= targetType.maxLength();
    }
  }

  /**
   * Creates a new storage re-using the existing array.
   *
   * <p>This can only be done if the values do not need any adaptations, checked by {@code
   * canAvoidCopying}.
   */
  private Storage<String> retypeStringStorage(StringStorage stringStorage) {
    return new StringStorage(stringStorage.getData(), stringStorage.size(), targetType);
  }
}
