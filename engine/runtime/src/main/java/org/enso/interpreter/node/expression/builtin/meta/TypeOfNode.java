package org.enso.interpreter.node.expression.builtin.meta;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.Node;
import org.enso.interpreter.dsl.AcceptsError;
import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.epb.runtime.PolyglotProxy;
import org.enso.interpreter.runtime.EnsoContext;
import org.enso.interpreter.runtime.builtin.Builtins;
import org.enso.interpreter.runtime.data.text.Text;
import org.enso.interpreter.runtime.error.DataflowError;
import org.enso.interpreter.runtime.error.PanicException;
import org.enso.interpreter.runtime.error.PanicSentinel;
import org.enso.interpreter.runtime.error.Warning;
import org.enso.interpreter.runtime.error.WithWarnings;
import org.enso.interpreter.runtime.library.dispatch.TypesLibrary;
import org.enso.interpreter.runtime.number.EnsoBigInteger;

@BuiltinMethod(
    type = "Meta",
    name = "type_of",
    description = "Returns the type of a value.",
    autoRegister = false)
public abstract class TypeOfNode extends Node {

  public abstract Object execute(@AcceptsError Object value);

  public static TypeOfNode build() {
    return TypeOfNodeGen.create();
  }

  @Specialization
  Object doDouble(double value) {
    return EnsoContext.get(this).getBuiltins().number().getDecimal();
  }

  @Specialization
  Object doLong(long value) {
    return EnsoContext.get(this).getBuiltins().number().getInteger();
  }

  @Specialization
  Object doBigInteger(EnsoBigInteger value) {
    return EnsoContext.get(this).getBuiltins().number().getInteger();
  }

  @Specialization
  Object doString(String value) {
    return EnsoContext.get(this).getBuiltins().text();
  }

  @Specialization
  Object doPanicException(PanicException value) {
    return EnsoContext.get(this).getBuiltins().panic();
  }

  @Specialization
  Object doPanicSentinel(PanicSentinel value) {
    return EnsoContext.get(this).getBuiltins().panic();
  }

  @Specialization
  Object doWarning(WithWarnings value) {
    return execute(value.getValue());
  }

  @Specialization(
      guards = {
        "interop.hasArrayElements(proxy)",
        "!interop.isString(proxy)", // R string value is an array and a string
        "!types.hasType(proxy)",
        "!interop.hasMetaObject(proxy)"
      })
  Object doPolyglotArray(
      PolyglotProxy proxy,
      @CachedLibrary(limit = "3") InteropLibrary interop,
      @CachedLibrary(limit = "3") TypesLibrary types) {
    return EnsoContext.get(this).getBuiltins().array();
  }

  @Specialization(
      guards = {
        "interop.isString(proxy)",
        "!types.hasType(proxy)",
        "!interop.hasMetaObject(proxy)"
      })
  Object doPolyglotString(
      PolyglotProxy proxy,
      @CachedLibrary(limit = "3") InteropLibrary interop,
      @CachedLibrary(limit = "3") TypesLibrary types) {
    return EnsoContext.get(this).getBuiltins().text();
  }

  @Specialization(
      guards = {
        "interop.isNumber(proxy)",
        "!types.hasType(proxy)",
        "!interop.hasMetaObject(proxy)"
      })
  Object doPolyglotNumber(
      PolyglotProxy proxy,
      @CachedLibrary(limit = "3") InteropLibrary interop,
      @CachedLibrary(limit = "3") TypesLibrary types) {
    Builtins builtins = EnsoContext.get(this).getBuiltins();
    if (interop.fitsInInt(proxy)) {
      return builtins.number().getInteger();
    } else if (interop.fitsInDouble(proxy)) {
      return builtins.number().getDecimal();
    } else {
      return EnsoContext.get(this).getBuiltins().number();
    }
  }

  @Specialization(guards = {"interop.isTime(value)", "interop.isDate(value)"})
  Object doDateTime(Object value, @CachedLibrary(limit = "3") InteropLibrary interop) {
    return EnsoContext.get(this).getBuiltins().dateTime();
  }

  @Specialization(
      guards = {"interop.isTimeZone(value)", "!interop.isDate(value)", "!interop.isTime(value)"})
  Object doTimeZone(Object value, @CachedLibrary(limit = "3") InteropLibrary interop) {
    EnsoContext ctx = EnsoContext.get(this);
    return ctx.getBuiltins().timeZone();
  }

  @Specialization(guards = {"interop.isDate(value)", "!interop.isTime(value)"})
  Object doDate(Object value, @CachedLibrary(limit = "3") InteropLibrary interop) {
    EnsoContext ctx = EnsoContext.get(this);
    return ctx.getBuiltins().date();
  }

  @Specialization(guards = {"interop.isTime(value)", "!interop.isDate(value)"})
  Object doTime(Object value, @CachedLibrary(limit = "3") InteropLibrary interop) {
    EnsoContext ctx = EnsoContext.get(this);
    return ctx.getBuiltins().timeOfDay();
  }

  @Specialization(guards = "interop.isDuration(value)")
  Object doDuration(Object value, @CachedLibrary(limit = "3") InteropLibrary interop) {
    EnsoContext ctx = EnsoContext.get(this);
    return ctx.getBuiltins().duration();
  }

  @Specialization(
      guards = {
        "interop.hasMetaObject(value)",
        "!types.hasType(value)",
        "!interop.isDate(value)",
        "!interop.isTime(value)",
        "!interop.isTimeZone(value)"
      })
  Object doMetaObject(
      Object value,
      @CachedLibrary(limit = "3") InteropLibrary interop,
      @CachedLibrary(limit = "3") TypesLibrary types) {
    try {
      return interop.getMetaObject(value);
    } catch (UnsupportedMessageException e) {
      CompilerDirectives.transferToInterpreter();
      Builtins builtins = EnsoContext.get(this).getBuiltins();
      throw new PanicException(builtins.error().makeCompileError("invalid meta object"), this);
    }
  }

  @Specialization(guards = {"types.hasType(value)", "!interop.isNumber(value)"})
  Object doType(
      Object value,
      @CachedLibrary(limit = "3") InteropLibrary interop,
      @CachedLibrary(limit = "3") TypesLibrary types) {
    return types.getType(value);
  }

  @Fallback
  @CompilerDirectives.TruffleBoundary
  Object doAny(Object value) {
    return DataflowError.withoutTrace(
        EnsoContext.get(this)
            .getBuiltins()
            .error()
            .makeCompileError("unknown type_of for " + value),
        this);
  }
}
