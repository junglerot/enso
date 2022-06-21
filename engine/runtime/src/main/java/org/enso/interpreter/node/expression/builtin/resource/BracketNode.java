package org.enso.interpreter.node.expression.builtin.resource;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.BranchProfile;
import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.dsl.MonadicState;
import org.enso.interpreter.dsl.Suspend;
import org.enso.interpreter.node.BaseNode;
import org.enso.interpreter.node.callable.InvokeCallableNode;
import org.enso.interpreter.node.callable.thunk.ThunkExecutorNode;
import org.enso.interpreter.runtime.callable.argument.CallArgumentInfo;
import org.enso.interpreter.runtime.state.Stateful;
import org.enso.interpreter.runtime.type.TypesGen;

/**
 * The basic bracket construct for resource management.
 *
 * <p>Even though it could be implemented in Enso, using lower-level primitives, implementing it in
 * Java gives us the best correctness guarantees, even in the presence of serious interpreter
 * failures.
 */
@BuiltinMethod(
    type = "Resource",
    name = "bracket",
    description =
        "Takes a computation acquiring a resource, a function taking the resource and closing it,"
            + " and a function performing arbitrary operations on the resource. Ensures closing"
            + " the resource, even if an exception is raised in the computation.")
public abstract class BracketNode extends Node {

  private @Child ThunkExecutorNode invokeConstructorNode = ThunkExecutorNode.build();

  private @Child InvokeCallableNode invokeDestructorNode =
      InvokeCallableNode.build(
          new CallArgumentInfo[] {new CallArgumentInfo()},
          InvokeCallableNode.DefaultsExecutionMode.EXECUTE,
          InvokeCallableNode.ArgumentsExecutionMode.PRE_EXECUTED);

  private @Child InvokeCallableNode invokeActionNode =
      InvokeCallableNode.build(
          new CallArgumentInfo[] {new CallArgumentInfo()},
          InvokeCallableNode.DefaultsExecutionMode.EXECUTE,
          InvokeCallableNode.ArgumentsExecutionMode.PRE_EXECUTED);

  static BracketNode build() {
    return BracketNodeGen.create();
  }

  abstract Stateful execute(
      @MonadicState Object state,
      VirtualFrame frame,
      Object self,
      @Suspend Object constructor,
      Object destructor,
      Object action);

  @Specialization
  Stateful doBracket(
      Object state,
      VirtualFrame frame,
      Object self,
      Object constructor,
      Object destructor,
      Object action,
      @Cached BranchProfile initializationFailedWithDataflowErrorProfile) {
    Stateful resourceStateful =
        invokeConstructorNode.executeThunk(constructor, state, BaseNode.TailStatus.NOT_TAIL);
    Object resource = resourceStateful.getValue();
    if (TypesGen.isDataflowError(resource)) {
      initializationFailedWithDataflowErrorProfile.enter();
      return resourceStateful;
    }
    state = resourceStateful.getState();
    try {
      Stateful result = invokeActionNode.execute(action, frame, state, new Object[] {resource});
      state = result.getState();
      return result;
    } finally {
      invokeDestructorNode.execute(destructor, frame, state, new Object[] {resource});
    }
  }
}
