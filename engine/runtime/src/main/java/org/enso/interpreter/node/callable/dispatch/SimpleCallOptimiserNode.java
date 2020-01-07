package org.enso.interpreter.node.callable.dispatch;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.enso.interpreter.node.callable.ExecuteCallNode;
import org.enso.interpreter.node.callable.ExecuteCallNodeGen;
import org.enso.interpreter.runtime.callable.CallerInfo;
import org.enso.interpreter.runtime.control.TailCallException;
import org.enso.interpreter.runtime.state.Stateful;

/**
 * Optimistic version of {@link CallOptimiserNode} for the non tail call recursive case. Tries to
 * just call the function. If that turns out to be a tail call, it replaces itself with a {@link
 * LoopingCallOptimiserNode}. Thanks to this design, the (much more common) case of calling a
 * function in a non-tail position does not force the overhead of loop.
 */
@NodeInfo(shortName = "SimpleCallOpt", description = "Handles non-tail-call execution")
public class SimpleCallOptimiserNode extends CallOptimiserNode {
  @Child private ExecuteCallNode executeCallNode = ExecuteCallNodeGen.create();
  @Child private CallOptimiserNode next = null;

  /**
   * Calls the provided {@code function} using the provided {@code arguments}.
   *
   * @param function the function to execute
   * @param callerInfo the caller info to pass to the function
   * @param state the state to pass to the function
   * @param arguments the arguments to {@code function}
   * @return the result of executing {@code function} using {@code arguments}
   */
  @Override
  public Stateful executeDispatch(
      Object function, CallerInfo callerInfo, Object state, Object[] arguments) {
    try {
      return executeCallNode.executeCall(function, callerInfo, state, arguments);
    } catch (TailCallException e) {
      if (next == null) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        next = insert(LoopingCallOptimiserNode.build());
      }
      return next.executeDispatch(
          e.getFunction(), e.getCallerInfo(), e.getState(), e.getArguments());
    }
  }
}
