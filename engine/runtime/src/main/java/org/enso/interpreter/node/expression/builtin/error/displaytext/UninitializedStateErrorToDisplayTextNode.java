package org.enso.interpreter.node.expression.builtin.error.displaytext;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.Node;
import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.node.expression.builtin.text.util.TypeToDisplayTextNode;
import org.enso.interpreter.runtime.callable.atom.Atom;
import org.enso.interpreter.runtime.callable.atom.AtomConstructor;
import org.enso.interpreter.runtime.callable.atom.StructsLibrary;
import org.enso.interpreter.runtime.data.text.Text;

@BuiltinMethod(type = "Uninitialized_State", name = "to_display_text")
public abstract class UninitializedStateErrorToDisplayTextNode extends Node {
  static UninitializedStateErrorToDisplayTextNode build() {
    return UninitializedStateErrorToDisplayTextNodeGen.create();
  }

  abstract Text execute(Object self);

  @Specialization
  Text doAtom(
      Atom self,
      @Cached TypeToDisplayTextNode displayTypeNode,
      @CachedLibrary(limit = "3") StructsLibrary structs) {
    return Text.create("State is not initialized for type ")
        .add(displayTypeNode.execute(structs.getField(self, 0)))
        .add(".");
  }

  @Specialization
  Text doConstructor(AtomConstructor self) {
    return Text.create("Uninitialized state error.");
  }
}
