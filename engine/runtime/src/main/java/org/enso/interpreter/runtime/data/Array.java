package org.enso.interpreter.runtime.data;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import org.enso.interpreter.dsl.Builtin;
import org.enso.interpreter.node.expression.builtin.error.InvalidArrayIndexError;
import org.enso.interpreter.runtime.Context;
import org.enso.interpreter.runtime.library.dispatch.TypesLibrary;

import java.util.Arrays;

/** A primitive boxed array type for use in the runtime. */
@ExportLibrary(InteropLibrary.class)
@ExportLibrary(TypesLibrary.class)
@Builtin(pkg = "mutable", stdlibName = "Standard.Base.Data.Array.Array")
public class Array implements TruffleObject {
  public static class InvalidIndexException extends RuntimeException {
    private final long index;
    private final Array array;

    public InvalidIndexException(long index, Array array) {
      this.index = index;
      this.array = array;
    }

    public long getIndex() {
      return index;
    }

    public Array getArray() {
      return array;
    }
  }

  private final Object[] items;

  /**
   * Creates a new array
   *
   * @param items the element values
   */
  @Builtin.Method(expandVarargs = 4, description = "Creates an array with given elements.")
  public Array(Object... items) {
    this.items = items;
  }

  /**
   * Creates an uninitialized array of the given size.
   *
   * @param size the size of the created array.
   */
  @Builtin.Method(description = "Creates an uninitialized array of a given size.")
  public Array(long size) {
    this.items = new Object[(int) size];
  }

  /** @return the elements of this array as a java array. */
  public Object[] getItems() {
    return items;
  }

  /**
   * Marks the object as array-like for Polyglot APIs.
   *
   * @return {@code true}
   */
  @ExportMessage
  public boolean hasArrayElements() {
    return true;
  }

  /**
   * Handles reading an element by index through the polyglot API.
   *
   * @param index the index to read
   * @return the element value at the provided index
   * @throws InvalidArrayIndexException when the index is out of bounds.
   */
  @ExportMessage
  public Object readArrayElement(long index) throws InvalidArrayIndexException {
    if (index >= items.length || index < 0) {
      throw InvalidArrayIndexException.create(index);
    }
    return items[(int) index];
  }

  /** @return the size of this array */
  @Builtin.Method(description = "Returns the size of this array.")
  public long length() {
    return this.getItems().length;
  }

  /** @return an empty array */
  @Builtin.Method(description = "Creates an empty Array")
  public static Object empty() {
    return new Array();
  }

  /** @return an identity array */
  @Builtin.Method(description = "Identity on arrays, implemented for protocol completeness.")
  public Object toArray() {
    return this;
  }

  /**
   * Exposes the size of this collection through the polyglot API.
   *
   * @return the size of this array
   */
  @ExportMessage
  long getArraySize() {
    return items.length;
  }

  @Builtin.Method(name = "at", description = "Gets an array element at the given index.")
  @Builtin.WrapException(from = InvalidIndexException.class, to = InvalidArrayIndexError.class)
  public Object get(long index) {
    if (index < 0 || index >= items.length) {
      throw new InvalidIndexException(index, this);
    }
    return getItems()[(int) index];
  }

  /**
   * Exposes an index validity check through the polyglot API.
   *
   * @param index the index to check
   * @return {@code true} if the index is valid, {@code false} otherwise.
   */
  @ExportMessage
  boolean isArrayElementReadable(long index) {
    return index < getArraySize() && index >= 0;
  }

  @ExportMessage
  String toDisplayString(boolean b) {
    return toString();
  }

  @Override
  public String toString() {
    return Arrays.toString(items);
  }

  @ExportMessage
  boolean hasType() {
    return true;
  }

  @ExportMessage
  Type getType(@CachedLibrary("this") TypesLibrary thisLib) {
    return Context.get(thisLib).getBuiltins().array();
  }
}
