package org.enso.interpreter.runtime.number;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import java.math.BigInteger;
import org.enso.interpreter.runtime.EnsoContext;
import org.enso.interpreter.runtime.data.EnsoObject;
import org.enso.interpreter.runtime.data.Type;
import org.enso.interpreter.runtime.library.dispatch.TypesLibrary;

/** Internal wrapper for a {@link BigInteger}. */
@ExportLibrary(InteropLibrary.class)
@ExportLibrary(TypesLibrary.class)
public final class EnsoBigInteger implements EnsoObject {
  private final BigInteger value;

  /**
   * Wraps a {@link BigInteger}.
   *
   * @param value the value to wrap.
   */
  public EnsoBigInteger(BigInteger value) {
    assert (value.bitLength() > 63);
    this.value = value;
  }

  /**
   * @return the contained {@link BigInteger}.
   */
  public BigInteger getValue() {
    return value;
  }

  @Override
  @CompilerDirectives.TruffleBoundary
  public String toString() {
    return value.toString();
  }

  @CompilerDirectives.TruffleBoundary
  @ExportMessage
  String toDisplayString(boolean allowSideEffects) {
    return value.toString();
  }

  @ExportMessage
  boolean isNumber() {
    return true;
  }

  @ExportMessage
  final boolean fitsInByte() {
    return false;
  }

  @ExportMessage
  final boolean fitsInShort() {
    return false;
  }

  @ExportMessage
  final boolean fitsInInt() {
    return false;
  }

  @ExportMessage
  final boolean fitsInLong() {
    return false;
  }

  @ExportMessage
  final boolean fitsInFloat() {
    return false;
  }

  @ExportMessage
  final boolean fitsInDouble() {
    return false;
  }

  @ExportMessage
  final boolean fitsInBigInteger() {
    return true;
  }

  @ExportMessage
  final byte asByte() throws UnsupportedMessageException {
    throw UnsupportedMessageException.create();
  }

  @ExportMessage
  final short asShort() throws UnsupportedMessageException {
    throw UnsupportedMessageException.create();
  }

  @ExportMessage
  final int asInt() throws UnsupportedMessageException {
    throw UnsupportedMessageException.create();
  }

  @ExportMessage
  final long asLong() throws UnsupportedMessageException {
    throw UnsupportedMessageException.create();
  }

  @ExportMessage
  final float asFloat() throws UnsupportedMessageException {
    throw UnsupportedMessageException.create();
  }

  @ExportMessage
  public final double asDouble() throws UnsupportedMessageException {
    throw UnsupportedMessageException.create();
  }

  @ExportMessage
  public final BigInteger asBigInteger() {
    return value;
  }

  @ExportMessage
  Type getMetaObject(@CachedLibrary("this") InteropLibrary thisLib) {
    return EnsoContext.get(thisLib).getBuiltins().number().getInteger();
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
  Type getType(@CachedLibrary("this") TypesLibrary thisLib, @Cached("1") int ignore) {
    return EnsoContext.get(thisLib).getBuiltins().number().getInteger();
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof EnsoBigInteger otherBigInt) {
      return value.equals(otherBigInt.value);
    } else {
      return false;
    }
  }
}
