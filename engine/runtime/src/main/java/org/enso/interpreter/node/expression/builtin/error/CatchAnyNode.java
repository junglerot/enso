package org.enso.interpreter.node.expression.builtin.error;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.dsl.MonadicState;
import org.enso.interpreter.node.BaseNode;
import org.enso.interpreter.node.callable.InvokeCallableNode;
import org.enso.interpreter.runtime.callable.argument.CallArgumentInfo;
import org.enso.interpreter.runtime.state.Stateful;

@BuiltinMethod(
    type = "Any",
    name = "catch_primitive",
    description =
        "If called on an error, executes the provided handler on the error's payload. Otherwise acts as identity.")
public class CatchAnyNode extends Node {
  private @Child InvokeCallableNode invokeCallableNode;

  CatchAnyNode() {
    this.invokeCallableNode =
        InvokeCallableNode.build(
            new CallArgumentInfo[] {new CallArgumentInfo()},
            InvokeCallableNode.DefaultsExecutionMode.EXECUTE,
            InvokeCallableNode.ArgumentsExecutionMode.PRE_EXECUTED);
    this.invokeCallableNode.setTailStatus(BaseNode.TailStatus.TAIL_DIRECT);
  }

  Stateful execute(VirtualFrame frame, @MonadicState Object state, Object self, Object handler) {
    return new Stateful(state, self);
  }
}
