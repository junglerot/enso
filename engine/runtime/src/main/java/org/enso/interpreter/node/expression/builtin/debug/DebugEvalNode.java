package org.enso.interpreter.node.expression.builtin.debug;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.node.BaseNode;
import org.enso.interpreter.node.expression.builtin.text.util.ExpectTextNode;
import org.enso.interpreter.node.expression.debug.EvalNode;
import org.enso.interpreter.runtime.callable.CallerInfo;
import org.enso.interpreter.runtime.state.State;

/** Root node for the builtin Debug.eval function. */
@BuiltinMethod(
    type = "Debug",
    name = "eval",
    description = "Evaluates an expression passed as a Text argument, in the caller frame.",
    autoRegister = false)
public class DebugEvalNode extends Node {
  private @Child EvalNode evalNode = EvalNode.build();
  private @Child ExpectTextNode expectTextNode = ExpectTextNode.build();

  DebugEvalNode() {
    evalNode.setTailStatus(BaseNode.TailStatus.TAIL_DIRECT);
  }

  Object execute(
      VirtualFrame requestOwnStackFrame, CallerInfo callerInfo, State state, Object expression) {
    return evalNode.execute(callerInfo, state, expectTextNode.execute(expression));
  }
}
