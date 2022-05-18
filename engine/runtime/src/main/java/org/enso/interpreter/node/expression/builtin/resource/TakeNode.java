package org.enso.interpreter.node.expression.builtin.resource;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.runtime.Context;
import org.enso.interpreter.runtime.data.ManagedResource;

@BuiltinMethod(
    type = "Managed_Resource",
    name = "take",
    description =
        "Takes the value held by the managed resource and removes the finalization callbacks,"
            + " effectively making the underlying resource unmanaged again.")
public abstract class TakeNode extends Node {

  static TakeNode build() {
    return TakeNodeGen.create();
  }

  abstract Object execute(Object _this);

  @Specialization
  Object doTake(ManagedResource _this) {
    Context.get(this).getResourceManager().take(_this);
    return _this.getResource();
  }
}
