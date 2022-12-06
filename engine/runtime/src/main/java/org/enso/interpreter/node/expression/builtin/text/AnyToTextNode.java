package org.enso.interpreter.node.expression.builtin.text;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.nodes.Node;
import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.runtime.callable.atom.Atom;
import org.enso.interpreter.runtime.callable.atom.AtomConstructor;
import org.enso.interpreter.runtime.data.text.Text;

@BuiltinMethod(type = "Any", name = "to_text", description = "Generic text conversion.")
public abstract class AnyToTextNode extends Node {
  private static final int DISPATCH_CACHE = 3;
  private @Child InteropLibrary displays =
      InteropLibrary.getFactory().createDispatched(DISPATCH_CACHE);
  private @Child InteropLibrary strings =
      InteropLibrary.getFactory().createDispatched(DISPATCH_CACHE);

  public static AnyToTextNode build() {
    return AnyToTextNodeGen.create();
  }

  public abstract Text execute(Object self);

  @Specialization
  Text doAtom(Atom at) {
    if (at.getFields().length == 0) {
      return consName(at.getConstructor());
    } else {
      return doComplexAtom(at);
    }
  }

  @Fallback
  Text doOther(Object object) {
    try {
      return Text.create(showObject(object));
    } catch (UnsupportedMessageException e) {
      CompilerDirectives.transferToInterpreter();
      return Text.create(object.toString());
    }
  }

  @CompilerDirectives.TruffleBoundary
  private Text consName(AtomConstructor constructor) {
    return Text.create(constructor.getDisplayName());
  }

  @CompilerDirectives.TruffleBoundary
  private Text doComplexAtom(Atom atom) {
    Text res = Text.create("(", consName(atom.getConstructor()));
    res = Text.create(res, " ");
    try {
      res = Text.create(res, showObject(atom.getFields()[0]));
    } catch (UnsupportedMessageException e) {
      res = Text.create(res, atom.getFields()[0].toString());
    }
    for (int i = 1; i < atom.getFields().length; i++) {
      res = Text.create(res, " ");
      try {
        res = Text.create(res, strings.asString(displays.toDisplayString(atom.getFields()[i])));
      } catch (UnsupportedMessageException e) {
        res = Text.create(res, atom.getFields()[i].toString());
      }
    }
    res = Text.create(res, ")");
    return res;
  }

  @CompilerDirectives.TruffleBoundary
  private String showObject(Object child) throws UnsupportedMessageException {
    if (child == null) {
      // TODO [RW] This is a temporary workaround to make it possible to display errors related to
      // https://www.pivotaltracker.com/story/show/181652974
      // Most likely it should be removed once that is implemented.
      return "null";
    } else if (child instanceof Boolean) {
      return (boolean) child ? "True" : "False";
    } else {
      return strings.asString(displays.toDisplayString(child));
    }
  }
}
