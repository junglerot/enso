package org.enso.interpreter.runtime.data.text;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import org.enso.interpreter.node.expression.builtin.text.util.ToJavaStringNode;
import org.enso.interpreter.runtime.Context;
import org.enso.interpreter.runtime.data.Type;
import org.enso.interpreter.runtime.library.dispatch.TypesLibrary;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/** The main runtime type for Enso's Text. */
@ExportLibrary(InteropLibrary.class)
@ExportLibrary(TypesLibrary.class)
public class Text implements TruffleObject {
  private volatile Object contents;
  private volatile boolean isFlat;
  private final Lock lock = new ReentrantLock();

  private Text(String string) {
    this.contents = string;
    this.isFlat = true;
  }

  private Text(ConcatRope contents) {
    this.contents = contents;
    this.isFlat = false;
  }

  /**
   * Wraps a string in an instance of Text.
   *
   * @param string the string to wrap.
   * @return a Text corresponding to the original string.
   */
  public static Text create(String string) {
    return new Text(string);
  }

  /**
   * Creates a new Text by concatenating two other texts.
   *
   * @param t1 the left operand.
   * @param t2 the right operand.
   * @return a Text representing concatenation of t1 and t2.
   */
  public static Text create(Text t1, Text t2) {
    return new Text(new ConcatRope(t1.contents, t2.contents));
  }

  /**
   * Creates a new Text by concatenating a text and a string.
   *
   * @param t1 the left operand.
   * @param t2 the right operand.
   * @return a Text representing concatenation of t1 and t2.
   */
  public static Text create(Text t1, String t2) {
    return new Text(new ConcatRope(t1.contents, t2));
  }

  /**
   * Creates a new Text by concatenating a text and a string.
   *
   * @param t1 the left operand.
   * @param t2 the right operand.
   * @return a Text representing concatenation of t1 and t2.
   */
  public static Text create(String t1, Text t2) {
    return new Text(new ConcatRope(t1, t2.contents));
  }

  /**
   * Creates a new Text by concatenating two strings.
   *
   * @param t1 the left operand.
   * @param t2 the right operand.
   * @return a Text representing concatenation of t1 and t2.
   */
  public static Text create(String t1, String t2) {
    return new Text(new ConcatRope(t1, t2));
  }

  /**
   * Adds a string to this text.
   *
   * @param other the string add.
   * @return the concatenation of this and the requested string.
   */
  public Text add(String other) {
    return new Text(new ConcatRope(this.contents, other));
  }

  /**
   * Adds a text to this text.
   *
   * @param other the text add.
   * @return the concatenation of this and the requested text.
   */
  public Text add(Text other) {
    return new Text(new ConcatRope(this.contents, other.contents));
  }

  @ExportMessage
  boolean isString() {
    return true;
  }

  @ExportMessage
  String asString(@Cached("build()") @Cached.Shared("strings") ToJavaStringNode toJavaStringNode) {
    return toJavaStringNode.execute(this);
  }

  @ExportMessage
  String toDisplayString(
      boolean allowSideEffects,
      @Cached("build()") @Cached.Shared("strings") ToJavaStringNode toJavaStringNode) {
    String str = toJavaStringNode.execute(this);
    // TODO This should be more extensible
    String replaced =
        str.replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
            .replace("\u0007", "\\a")
            .replace("\u0008", "\\b")
            .replace("\u000c", "\\f")
            .replace("\r", "\\r")
            .replace("\u000B", "\\v")
            .replace("\u001B", "\\e");
    return "'" + replaced + "'";
  }

  /** @return true if this text wraps a string literal and does not require any optimization. */
  public boolean isFlat() {
    return isFlat;
  }

  /** @param flat the new value of the isFlat flag. */
  public void setFlat(boolean flat) {
    isFlat = flat;
  }

  /** @return the contents of this text. */
  public Object getContents() {
    return contents;
  }

  /**
   * Sets the contents of this text.
   *
   * @param contents the new contents.
   */
  public void setContents(Object contents) {
    this.contents = contents;
  }

  /** @return the lock required for modification of this text. */
  public Lock getLock() {
    return lock;
  }

  @Override
  public String toString() {
    if (isFlat) {
      return (String) this.contents;
    } else {
      return ToJavaStringNode.inplaceFlatten(this);
    }
  }

  @ExportMessage
  boolean hasType() {
    return true;
  }

  @ExportMessage
  Type getType(@CachedLibrary("this") TypesLibrary thisLib) {
    return Context.get(thisLib).getBuiltins().text();
  }
}
