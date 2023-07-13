package org.enso.base;

import org.enso.base.time.Date_Time_Utils;
import org.enso.base.time.Date_Utils;
import org.enso.base.time.TimeUtilsBase;
import org.enso.base.time.Time_Of_Day_Utils;
import org.enso.polyglot.common_utils.Core_Date_Utils;
import org.graalvm.polyglot.Value;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalField;
import java.time.temporal.TemporalUnit;
import java.time.temporal.WeekFields;
import java.util.Locale;

/**
 * Utils for standard library operations on Time.
 */
public class Time_Utils {
  public enum AdjustOp {
    PLUS,
    MINUS
  }

  /**
   * Creates a DateTimeFormatter from a format string, supporting building standard formats.
   *
   * @param format format string
   * @param locale locale needed for custom formats
   * @return DateTimeFormatter
   */
  public static DateTimeFormatter make_formatter(String format, Locale locale) {
    return Core_Date_Utils.make_formatter(format, locale);
  }

  /**
   * Creates a DateTimeFormatter from a format string, supporting building standard formats.
   * For Enso format, return the default output formatter.
   *
   * @param format format string
   * @param locale locale needed for custom formats
   * @return DateTimeFormatter
   */
  public static DateTimeFormatter make_output_formatter(String format, Locale locale) {
    return format.equals("ENSO_ZONED_DATE_TIME")
        ? Time_Utils.default_output_date_time_formatter()
        : Core_Date_Utils.make_formatter(format, locale);
  }

  /**
   * Given a format string, returns true if it is a format that is based on ISO date time.
   *
   * @param format format string
   * @return True if format is based on ISO date time
   */
  public static boolean is_iso_datetime_based(String format) {
    return switch (format) {
      case "ENSO_ZONED_DATE_TIME", "ISO_ZONED_DATE_TIME", "ISO_OFFSET_DATE_TIME", "ISO_LOCAL_DATE_TIME" -> true;
      default -> false;
    };
  }

  /**
   * @return default DateTimeFormatter for parsing a Date_Time.
   */
  public static DateTimeFormatter default_date_time_formatter() {
    return Core_Date_Utils.defaultZonedDateTimeFormatter();
  }

  /**
   * @return default DateTimeFormatter for parsing a Date.
   */
  public static DateTimeFormatter default_date_formatter() {
    return Core_Date_Utils.defaultLocalDateFormatter();
  }

  /**
   * @return default DateTimeFormatter for parsing a Time_Of_Day.
   */
  public static DateTimeFormatter default_time_of_day_formatter() {
    return Core_Date_Utils.defaultLocalTimeFormatter();
  }

  /**
   * @return default Date Time formatter for writing a Date_Time.
   */
  public static DateTimeFormatter default_output_date_time_formatter() {
    return new DateTimeFormatterBuilder().append(DateTimeFormatter.ISO_LOCAL_DATE)
        .appendLiteral(' ')
        .append(DateTimeFormatter.ISO_LOCAL_TIME)
        .toFormatter();
  }

  /**
   * Replace space with T in ISO date time string to make it compatible with ISO format.
   *
   * @param dateString Raw date time string with either space or T as separator
   * @return ISO format date time string
   */
  public static String normalise_iso_datetime(String dateString) {
    return Core_Date_Utils.normaliseISODateTime(dateString);
  }

  /**
   * Format a LocalDate instance using a formatter.
   *
   * @param date the LocalDate instance to format.
   * @param formatter the formatter to use.
   * @return formatted LocalDate instance.
   */
  public static String date_format(LocalDate date, DateTimeFormatter formatter) {
    return formatter.format(date);
  }

  /**
   * Format a ZonedDateTime instance using a formatter.
   *
   * @param dateTime the ZonedDateTime instance to format.
   * @param formatter the formatter to use.
   * @return formatted ZonedDateTime instance.
   */
  public static String date_time_format(ZonedDateTime dateTime, DateTimeFormatter formatter) {
    return formatter.format(dateTime);
  }

  /**
   * Format a LocalTime instance using a formatter.
   *
   * @param localTime the LocalTime instance to format.
   * @param formatter the formatter to use.
   * @return formatted LocalTime instance.
   */
  public static String time_of_day_format(LocalTime localTime, DateTimeFormatter formatter) {
    return formatter.format(localTime);
  }

  public static String time_of_day_format_with_locale(LocalTime localTime, Object format, Locale locale) {
    return make_output_formatter(format.toString(), locale).format(localTime);
  }

  public static LocalDate date_adjust(LocalDate date, AdjustOp op, Period period) {
    return switch (op) {
      case PLUS -> date.plus(period);
      case MINUS -> date.minus(period);
    };
  }

  public static ZonedDateTime datetime_adjust(ZonedDateTime datetime, AdjustOp op, Period period) {
    return switch (op) {
      case PLUS -> datetime.plus(period);
      case MINUS -> datetime.minus(period);
    };
  }

  public static int get_field_as_localdate(LocalDate date, TemporalField field) {
    return date.get(field);
  }

  public static int get_field_as_zoneddatetime(ZonedDateTime date, TemporalField field) {
    return date.get(field);
  }

  public static boolean is_leap_year(LocalDate date) {
    return date.isLeapYear();
  }

  public static int length_of_month(LocalDate date) {
    return date.lengthOfMonth();
  }

  public static long week_of_year_localdate(LocalDate date, Locale locale) {
    return WeekFields.of(locale).weekOfYear().getFrom(date);
  }

  public static long week_of_year_zoneddatetime(ZonedDateTime date, Locale locale) {
    return WeekFields.of(locale).weekOfYear().getFrom(date);
  }

  /**
   * Obtains an instance of ZonedDateTime from text using custom format string.
   *
   * <p>First tries to parse text as ZonedDateTime, then falls back to parsing LocalDateTime adding
   * system default timezone.
   *
   * @param text the string to parse.
   * @param formatter the formatter to use.
   * @return parsed ZonedDateTime instance.
   */
  public static ZonedDateTime parse_date_time(String text, DateTimeFormatter formatter) {
    return Core_Date_Utils.parseZonedDateTime(text, formatter);
  }

  /**
   * Obtains an instance of LocalDate from text using the formatter.
   *
   * @param text the string to parse.
   * @param formatter the formatter to use.
   * @return parsed LocalDate instance.
   */
  public static LocalDate parse_date(String text, DateTimeFormatter formatter) {
    return Core_Date_Utils.parseLocalDate(text, formatter);
  }

  /**
   * Obtains an instance of LocalTime from a text string using the formatter.
   *
   * @param text the string to parse.
   * @param formatter the formatter to use.
   * @return parsed LocalTime instance.
   */
  public static LocalTime parse_time_of_day(String text,DateTimeFormatter formatter) {
    return LocalTime.parse(text, formatter);
  }

  /**
   * Normally this method could be done in Enso by pattern matching, but currently matching on Time
   * types is not supported, so this is a workaround.
   *
   * <p>TODO once the related issue is fixed, this workaround may be replaced with pattern matching
   * in Enso; <a href="https://github.com/enso-org/enso/issues/4597">Pivotal issue.</a>
   */
  public static TimeUtilsBase utils_for(Value value) {
    boolean isDate = value.isDate();
    boolean isTime = value.isTime();
    if (isDate && isTime) return Date_Time_Utils.INSTANCE;
    if (isDate) return Date_Utils.INSTANCE;
    if (isTime) return Time_Of_Day_Utils.INSTANCE;
    throw new IllegalArgumentException("Unexpected argument type: " + value);
  }

  public static ZoneOffset get_datetime_offset(ZonedDateTime datetime) {
    return datetime.getOffset();
  }

  /**
   * Counts days within the range from start (inclusive) to end (exclusive).
   */
  public static long days_between(LocalDate start, LocalDate end) {
    return ChronoUnit.DAYS.between(start, end);
  }

  /**
   * Counts months within the range from start (inclusive) to end (exclusive).
   */
  public static long months_between(LocalDate start, LocalDate end) {
    return ChronoUnit.MONTHS.between(start, end);
  }

  /**
   * Constructs a Date_Time from a Date, Time_Of_Day and Zone.
   */
  public static ZonedDateTime make_zoned_date_time(LocalDate date, LocalTime time, ZoneId zone) {
    return ZonedDateTime.of(date, time, zone);
  }

  /**
   * Constructs a new time instant referring to the same moment in time but in a different time zone.
   */
  public static ZonedDateTime with_zone_same_instant(ZonedDateTime dateTime, ZoneId zone) {
    return dateTime.withZoneSameInstant(zone);
  }

  /**
   * This wrapper function is needed to ensure that EnsoDate gets converted to LocalDate correctly.
   * <p>
   * The {@code ChronoUnit::between} takes a value of type Temporal which does not trigger a polyglot conversion.
   */
  public static long unit_date_difference(TemporalUnit unit, LocalDate start, LocalDate end) {
    return unit.between(start, end);
  }

  /**
   * This wrapper function is needed to ensure that EnsoTimeOfDay gets converted to LocalTime correctly.
   * <p>
   * The {@code ChronoUnit::between} takes a value of type Temporal which does not trigger a polyglot conversion.
   */
  public static long unit_time_difference(TemporalUnit unit, LocalTime start, LocalTime end) {
    return unit.between(start, end);
  }

  /**
   * This wrapper function is needed to ensure that EnsoDateTime gets converted to ZonedDateTime correctly.
   * <p>
   * The {@code ChronoUnit::between} takes a value of type Temporal which does not trigger a polyglot conversion.
   */
  public static long unit_datetime_difference(TemporalUnit unit, ZonedDateTime start, ZonedDateTime end) {
    return unit.between(start, end);
  }
}
