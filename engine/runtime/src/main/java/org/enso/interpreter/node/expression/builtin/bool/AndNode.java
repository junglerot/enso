package org.enso.interpreter.node.expression.builtin.bool;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.ConditionProfile;
import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.dsl.MonadicState;
import org.enso.interpreter.dsl.Suspend;
import org.enso.interpreter.node.BaseNode;
import org.enso.interpreter.node.callable.thunk.ThunkExecutorNode;
import org.enso.interpreter.runtime.state.Stateful;

@BuiltinMethod(
    type = "Boolean",
    name = "&&",
    description = "Computes the logical conjunction of two booleans")
public abstract class AndNode extends Node {

  ConditionProfile conditionProfile = ConditionProfile.createCountingProfile();

  public static AndNode build() {
    return AndNodeGen.create();
  }

  abstract Stateful execute(@MonadicState Object state, boolean _this, @Suspend Object that);

  @Specialization
  Stateful executeBool(
      Object state,
      boolean _this,
      Object that,
      @Cached("build()") ThunkExecutorNode rhsThunkExecutorNode) {
    if (conditionProfile.profile(!_this)) {
      return new Stateful(state, false);
    }
    return rhsThunkExecutorNode.executeThunk(that, state, BaseNode.TailStatus.TAIL_DIRECT);
  }
}
