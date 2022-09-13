package org.enso.interpreter.runtime.data;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.enso.interpreter.dsl.Builtin;
import org.enso.interpreter.node.expression.builtin.error.PolyglotError;
import org.enso.interpreter.node.expression.builtin.interop.syntax.HostValueToEnsoNode;
import org.enso.interpreter.node.expression.builtin.interop.syntax.HostValueToEnsoNodeGen;
import org.enso.interpreter.runtime.Context;
import org.enso.interpreter.runtime.callable.function.Function;
import org.enso.interpreter.runtime.error.DataflowError;
import org.enso.interpreter.runtime.error.PanicException;
import org.enso.interpreter.runtime.library.dispatch.TypesLibrary;

@ExportLibrary(InteropLibrary.class)
@ExportLibrary(TypesLibrary.class)
@Builtin(pkg = "immutable", stdlibName = "Standard.Base.Data.Vector.Vector")
public final class Vector implements TruffleObject {
  private final Object storage;

  private Vector(Object storage) {
    if (CompilerDirectives.inInterpreter()) {
      if (!InteropLibrary.getUncached().hasArrayElements(storage)) {
        throw new IllegalStateException("Vector needs array-like delegate, but got: " + storage);
      }
    }
    this.storage = storage;
  }

  @Builtin.Method(
      name = "new_builtin",
      description = "Creates new Vector with given length and provided elements.")
  @Builtin.Specialize
  public static Object newFromFunction(long length, Function fun, InteropLibrary interop) {
    Object[] target = new Object[Math.toIntExact(length)];
    for (int i = 0; i < target.length; i++) {
      try {
        final Object value = interop.execute(fun, (long) i);
        if (value instanceof DataflowError) {
          return value;
        }
        target[i] = value;
      } catch (ArityException | UnsupportedTypeException | UnsupportedMessageException e) {
        throw raise(RuntimeException.class, e);
      }
    }
    return new Vector(new Array(target));
  }

  @Builtin.Method(
      name = "to_array",
      description = "Returns an Array representation of this Vector.")
  public final Object toArray() {
    return this.storage;
  }

  @Builtin.Method(description = "Returns the length of this Vector.")
  @Builtin.Specialize
  @Builtin.WrapException(from = UnsupportedMessageException.class, to = PanicException.class)
  public final long length(InteropLibrary interop) throws UnsupportedMessageException {
    return interop.getArraySize(storage);
  }

  //
  // messages for the InteropLibrary
  //

  /**
   * Marks the object as array-like for Polyglot APIs.
   *
   * @return {@code true}
   */
  @ExportMessage
  public boolean hasArrayElements() {
    return true;
  }

  @ExportMessage
  public long getArraySize(@CachedLibrary(limit = "3") InteropLibrary interop)
      throws UnsupportedMessageException {
    return interop.getArraySize(storage);
  }

  /**
   * Handles reading an element by index through the polyglot API.
   *
   * @param index the index to read
   * @return the element value at the provided index
   * @throws InvalidArrayIndexException when the index is out of bounds.
   */
  @ExportMessage
  public Object readArrayElement(
      long index,
      @CachedLibrary(limit = "3") InteropLibrary interop,
      @Cached HostValueToEnsoNode toEnso)
      throws InvalidArrayIndexException, UnsupportedMessageException {
    var v = interop.readArrayElement(storage, index);
    return toEnso.execute(v);
  }

  public static Vector fromArray(Object arr) {
    return new Vector(arr);
  }

  /**
   * Exposes an index validity check through the polyglot API.
   *
   * @param index the index to check
   * @return {@code true} if the index is valid, {@code false} otherwise.
   */
  @ExportMessage
  boolean isArrayElementReadable(long index, @CachedLibrary(limit = "3") InteropLibrary interop) {
    try {
      var size = interop.getArraySize(storage);
      return index < size && index >= 0;
    } catch (UnsupportedMessageException e) {
      return false;
    }
  }

  @ExportMessage
  boolean isArrayElementModifiable(long index) {
    return false;
  }

  @ExportMessage
  final void writeArrayElement(long index, Object value)
      throws UnsupportedMessageException, UnsupportedTypeException, InvalidArrayIndexException {
    throw UnsupportedMessageException.create();
  }

  @ExportMessage
  boolean isArrayElementInsertable(long index) {
    return false;
  }

  @ExportMessage
  boolean isArrayElementRemovable(long index) {
    return false;
  }

  @ExportMessage
  final void removeArrayElement(long index) throws UnsupportedMessageException {
    throw UnsupportedMessageException.create();
  }

  @ExportMessage
  @CompilerDirectives.TruffleBoundary
  String toDisplayString(boolean allowSideEffects) {
    final InteropLibrary iop = InteropLibrary.getUncached();
    StringBuilder sb = new StringBuilder();
    try {
      sb.append('[');
      String sep = "";
      long len = length(iop);
      for (long i = 0; i < len; i++) {
        sb.append(sep);

        Object at = readArrayElement(i, iop, HostValueToEnsoNode.getUncached());
        Object str = iop.toDisplayString(at, allowSideEffects);
        if (iop.isString(str)) {
          sb.append(iop.asString(str));
        } else {
          sb.append("_");
        }
        sep = ", ";
      }
      sb.append(']');
    } catch (InvalidArrayIndexException | UnsupportedMessageException ex) {
      StringWriter w = new StringWriter();
      ex.printStackTrace(new PrintWriter(w));
      sb.append("...\n").append(w.toString());
    }
    return sb.toString();
  }

  //
  // methods for TypesLibrary
  //

  @ExportMessage
  boolean hasType() {
    return true;
  }

  @ExportMessage
  Type getType(@CachedLibrary("this") TypesLibrary thisLib) {
    return Context.get(thisLib).getBuiltins().vector();
  }

  //
  // helper methods
  //

  @Override
  @CompilerDirectives.TruffleBoundary
  public String toString() {
    return toDisplayString(false);
  }

  @SuppressWarnings("unchecked")
  private static <E extends Exception> E raise(Class<E> clazz, Throwable t) throws E {
    throw (E) t;
  }
}
