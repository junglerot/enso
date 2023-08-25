package org.enso.interpreter.epb.node;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import org.enso.interpreter.epb.EpbContext;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.NodeField;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.source.Source;

@NodeField(name = "foreignFunction", type = Object.class)
public abstract class PyForeignNode extends ForeignFunctionCallNode {
  @Child
  private CoercePrimitiveNode coercePrimitiveNode = CoercePrimitiveNode.build();
  @CompilerDirectives.CompilationFinal
  private Object fnPythonDate;
  @Child
  private InteropLibrary nodePythonDate;
  @CompilerDirectives.CompilationFinal
  private Object fnPythonTime;
  @Child
  private InteropLibrary nodePythonTime;
  @CompilerDirectives.CompilationFinal
  private Object fnPythonZone;
  @Child
  private InteropLibrary nodePythonZone;
  @CompilerDirectives.CompilationFinal
  private Object fnPythonCombine;
  @Child
  private InteropLibrary nodePythonCombine;

  abstract Object getForeignFunction();

  private Object wrapPythonDate(LocalDate date) throws UnsupportedTypeException, ArityException, UnsupportedMessageException {
    if (nodePythonDate == null) {
      CompilerDirectives.transferToInterpreterAndInvalidate();
      var ctx = EpbContext.get(this);
      var src = Source.newBuilder("python", """
      from datetime import date
      date
      """, "convert_date.py").build();

      fnPythonDate = ctx.getEnv().parsePublic(src).call();
      nodePythonDate = insert(InteropLibrary.getFactory().create(fnPythonDate));
    }
    return nodePythonDate.execute(fnPythonDate, date.getYear(), date.getMonthValue(), date.getDayOfMonth());
  }

  private Object wrapPythonTime(LocalTime time) throws UnsupportedTypeException, ArityException, UnsupportedMessageException {
    if (nodePythonTime == null) {
      CompilerDirectives.transferToInterpreterAndInvalidate();
      var ctx = EpbContext.get(this);
      var src = Source.newBuilder("python", """
      from datetime import time
      time
      """, "convert_time.py").build();
      fnPythonTime = ctx.getEnv().parsePublic(src).call();
      nodePythonTime = insert(InteropLibrary.getFactory().create(fnPythonTime));
    }
    return nodePythonTime.execute(fnPythonTime, time.getHour(), time.getMinute(), time.getSecond(), time.getNano() / 1000);
  }

  private Object wrapPythonZone(ZoneId zone, LocalTime time, LocalDate date)
          throws UnsupportedTypeException, ArityException, UnsupportedMessageException {
    var ctx = EpbContext.get(this);
    if (nodePythonZone == null) {
      CompilerDirectives.transferToInterpreterAndInvalidate();
      var src = Source.newBuilder("python", """
      from datetime import timezone, timedelta, tzinfo

      class EnsoTzInfo(tzinfo):
        def __init__(self, rules):
          self._rules = rules

        def utcoffset(self, when):
          when = when.replace(tzinfo=None)
          d = timedelta(seconds=self._rules.offset(when))
          return d

        def tzname(self, dt):
          return self._rules.name(dt)

        def dst(self, dt):
          return self._rules.dst(dt);

      def conv(rules):
          return EnsoTzInfo(rules)

      conv
      """, "convert_time_zone.py").build();

      fnPythonZone = ctx.getEnv().parsePublic(src).call();
      nodePythonZone = insert(InteropLibrary.getFactory().create(fnPythonZone));
    }
    return nodePythonZone.execute(fnPythonZone, new ZoneWrapper(zone));
  }

  private Object combinePythonDateTimeZone(Object date, Object time, Object zone)
          throws UnsupportedTypeException, ArityException, UnsupportedMessageException {
    if (nodePythonCombine == null) {
      CompilerDirectives.transferToInterpreterAndInvalidate();
      var ctx = EpbContext.get(this);
      var src = Source.newBuilder("python", """
      from datetime import datetime
      datetime.combine
      """, "convert_combine.py").build();

      fnPythonCombine = ctx.getEnv().parsePublic(src).call();
      nodePythonCombine = insert(InteropLibrary.getFactory().create(fnPythonCombine));
    }

    if (zone != null) {
      return nodePythonCombine.execute(fnPythonCombine, date, time, zone);
    } else {
      return nodePythonCombine.execute(fnPythonCombine, date, time);
    }
  }

  @Specialization
  public Object doExecute(
          Object[] arguments,
          @CachedLibrary("foreignFunction") InteropLibrary interopLibrary,
          @CachedLibrary(limit = "3") InteropLibrary iop
  ) throws InteropException {
    for (int i = 0; i < arguments.length; i++) {
      var javaTime = iop.isTime(arguments[i]) ? iop.asTime(arguments[i]) : null;
      var time = javaTime != null ? wrapPythonTime(javaTime) : null;
      var javaDate = iop.isDate(arguments[i]) ? iop.asDate(arguments[i]) : null;
      var date = javaDate != null ? wrapPythonDate(javaDate) : null;
      var zone = iop.isTimeZone(arguments[i]) ? wrapPythonZone(iop.asTimeZone(arguments[i]), javaTime, javaDate) : null;
      if (date != null && time != null) {
        arguments[i] = combinePythonDateTimeZone(date, time, zone);
      } else if (date != null) {
        arguments[i] = date;
      } else if (time != null) {
        arguments[i] = time;
      } else if (zone != null) {
        arguments[i] = zone;
      }
    }
    return coercePrimitiveNode.execute(interopLibrary.execute(getForeignFunction(), arguments));
  }

  @ExportLibrary(InteropLibrary.class)
  static final class ZoneWrapper implements TruffleObject {

    private final ZoneId zone;

    ZoneWrapper(ZoneId zone) {
      this.zone = zone;
    }

    @ExportMessage
    boolean hasMembers() {
      return true;
    }

    @ExportMessage
    boolean isMemberInvocable(String member) {
      return switch (member) {
        case "dst", "name", "offset" ->
          true;
        default ->
          false;
      };
    }

    @ExportMessage
    Object getMembers(boolean includeInternal) throws UnsupportedMessageException {
      return this;
    }

    @ExportMessage
    @CompilerDirectives.TruffleBoundary
    Object invokeMember(
      String name, Object[] args,
      @CachedLibrary(limit = "3") InteropLibrary iop
    ) throws UnknownIdentifierException, UnsupportedMessageException {
      var date = iop.asDate(args[0]);
      var time = iop.asTime(args[0]);
      var when = date.atTime(time);
      return switch (name) {
        case "dst" -> {
          var instant = when.toInstant(zone.getRules().getOffset(when));
          var std = zone.getRules().getStandardOffset(instant);
          var now = zone.getRules().getOffset(instant);
          yield now.getTotalSeconds() - std.getTotalSeconds();
        }
        case "name" -> zone.getId();
        case "offset" ->
          zone.getRules().getOffset(when).getTotalSeconds();
        default ->
          throw UnknownIdentifierException.create(name);
      };
    }
  }
}
