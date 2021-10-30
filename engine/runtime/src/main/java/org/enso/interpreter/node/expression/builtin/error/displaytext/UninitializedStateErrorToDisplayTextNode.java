package org.enso.interpreter.node.expression.builtin.error.displaytext;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.node.expression.builtin.text.util.TypeToDisplayTextNode;
import org.enso.interpreter.runtime.callable.atom.Atom;
import org.enso.interpreter.runtime.callable.atom.AtomConstructor;
import org.enso.interpreter.runtime.data.text.Text;

@BuiltinMethod(type = "Uninitialized_State_Error", name = "to_display_text")
public abstract class UninitializedStateErrorToDisplayTextNode extends Node {
  static UninitializedStateErrorToDisplayTextNode build() {
    return UninitializedStateErrorToDisplayTextNodeGen.create();
  }

  abstract Text execute(Object _this);

  @Specialization
  Text doAtom(Atom _this, @Cached TypeToDisplayTextNode displayTypeNode) {
    return Text.create("State is not initialized for type ")
        .add(displayTypeNode.execute(_this.getFields()[0]))
        .add(".");
  }

  @Specialization
  Text doConstructor(AtomConstructor _this) {
    return Text.create("Uninitialized state error.");
  }
}
