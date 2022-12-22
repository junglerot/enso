package org.enso.interpreter.runtime.data;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.time.LocalTime;

import org.enso.interpreter.dsl.Builtin;
import org.enso.interpreter.runtime.EnsoContext;
import org.enso.interpreter.runtime.data.text.Text;
import org.enso.interpreter.runtime.library.dispatch.TypesLibrary;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@ExportLibrary(InteropLibrary.class)
@ExportLibrary(TypesLibrary.class)
@Builtin(pkg = "date", name = "Date", stdlibName = "Standard.Base.Data.Time.Date.Date")
public final class EnsoDate implements TruffleObject {
  private final LocalDate date;

  public EnsoDate(LocalDate date) {
    this.date = date;
  }

  @Builtin.Method(description = "Return current Date", autoRegister = false)
  @CompilerDirectives.TruffleBoundary
  public static EnsoDate now() {
    return new EnsoDate(LocalDate.now());
  }

  @Builtin.Method(name = "parse_builtin", description = "Constructs a new Date from text with optional pattern", autoRegister = false)
  @Builtin.Specialize
  @Builtin.WrapException(from = DateTimeParseException.class)
  @CompilerDirectives.TruffleBoundary
  public static EnsoDate parse(Text text, Object noneOrPattern) {
    var str = text.toString();
    if (noneOrPattern instanceof Text pattern) {
      var formatter = DateTimeFormatter.ofPattern(pattern.toString());
      return new EnsoDate(LocalDate.parse(str, formatter));
    } else {
      return new EnsoDate(LocalDate.parse(str));
    }
  }

  @Builtin.Method(name = "new_builtin", description = "Constructs a new Date from a year, month, and day", autoRegister = false)
  @Builtin.WrapException(from = DateTimeException.class)
  @CompilerDirectives.TruffleBoundary
  public static EnsoDate create(long year, long month, long day) {
    return new EnsoDate(LocalDate.of(Math.toIntExact(year), Math.toIntExact(month), Math.toIntExact(day)));
  }

  @Builtin.Method(name = "year", description = "Gets a value of year")
  public long year() {
    return date.getYear();
  }

  @Builtin.Method(name = "month", description = "Gets a value month")
  public long month() {
    return date.getMonthValue();
  }

  @Builtin.Method(name = "day", description = "Gets a value day")
  public long day() {
    return date.getDayOfMonth();
  }

  @Builtin.Method(name = "to_time_builtin", description = "Combine this day with time to create a point in time.")
  @CompilerDirectives.TruffleBoundary
  public EnsoDateTime toTime(EnsoTimeOfDay timeOfDay, EnsoTimeZone zone) {
    return new EnsoDateTime(date.atTime(timeOfDay.asTime()).atZone(zone.asTimeZone()));
  }

  @ExportMessage
  boolean isDate() {
    return true;
  }

  @ExportMessage
  LocalDate asDate() {
    return date;
  }

  @ExportMessage
  boolean isTime() {
    return false;
  }

  @ExportMessage
  LocalTime asTime() throws UnsupportedMessageException {
    throw UnsupportedMessageException.create();
  }

  @ExportMessage
  Type getMetaObject(@CachedLibrary("this") InteropLibrary thisLib) {
    return EnsoContext.get(thisLib).getBuiltins().date();
  }

  @ExportMessage
  boolean hasMetaObject() {
    return true;
  }

  @ExportMessage
  boolean hasType() {
    return true;
  }

  @ExportMessage
  Type getType(@CachedLibrary("this") TypesLibrary thisLib) {
    return EnsoContext.get(thisLib).getBuiltins().date();
  }

  @CompilerDirectives.TruffleBoundary
  @ExportMessage
  public final Object toDisplayString(boolean allowSideEffects) {
    return DateTimeFormatter.ISO_LOCAL_DATE.format(date);
  }
}
