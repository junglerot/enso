package org.enso.interpreter.node.callable;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import org.enso.interpreter.node.callable.InvokeCallableNode;
import org.enso.interpreter.node.callable.dispatch.IndirectInvokeFunctionNode;
import org.enso.interpreter.node.callable.thunk.ThunkExecutorNode;
import org.enso.interpreter.runtime.callable.UnresolvedSymbol;
import org.enso.interpreter.runtime.callable.argument.CallArgumentInfo;
import org.enso.interpreter.runtime.callable.argument.Thunk;
import org.enso.interpreter.runtime.callable.atom.AtomConstructor;
import org.enso.interpreter.runtime.callable.function.Function;
import org.enso.interpreter.runtime.error.NotInvokableException;
import org.enso.interpreter.runtime.state.Stateful;

import java.util.concurrent.locks.Lock;

/**
 * Invokes any callable with given arguments.
 *
 * <p>This is a slow-path node for the uncached flow.
 */
@GenerateUncached
public abstract class IndirectInvokeCallableNode extends Node {

  /**
   * Executes the callable with given arguments.
   *
   * @param callable the callable to call.
   * @param callerFrame current stack frame.
   * @param state current monadic state.
   * @param arguments arguments to pass to the callable.
   * @param schema names and ordering of the arguments.
   * @param defaultsExecutionMode whether defaults are suspended for this call.
   * @param argumentsExecutionMode whether arguments are preexecuted for this call.
   * @param isTail is the call happening in a tail position.
   * @return the result of executing the callable.
   */
  public abstract Stateful execute(
      Object callable,
      MaterializedFrame callerFrame,
      Object state,
      Object[] arguments,
      CallArgumentInfo[] schema,
      InvokeCallableNode.DefaultsExecutionMode defaultsExecutionMode,
      InvokeCallableNode.ArgumentsExecutionMode argumentsExecutionMode,
      boolean isTail);

  @Specialization
  Stateful invokeFunction(
      Function function,
      MaterializedFrame callerFrame,
      Object state,
      Object[] arguments,
      CallArgumentInfo[] schema,
      InvokeCallableNode.DefaultsExecutionMode defaultsExecutionMode,
      InvokeCallableNode.ArgumentsExecutionMode argumentsExecutionMode,
      boolean isTail,
      @Cached IndirectInvokeFunctionNode invokeFunctionNode) {
    return invokeFunctionNode.execute(
        function,
        callerFrame,
        state,
        arguments,
        schema,
        defaultsExecutionMode,
        argumentsExecutionMode,
        isTail);
  }

  @Specialization
  Stateful invokeConstructor(
      AtomConstructor constructor,
      MaterializedFrame callerFrame,
      Object state,
      Object[] arguments,
      CallArgumentInfo[] schema,
      InvokeCallableNode.DefaultsExecutionMode defaultsExecutionMode,
      InvokeCallableNode.ArgumentsExecutionMode argumentsExecutionMode,
      boolean isTail,
      @Cached IndirectInvokeFunctionNode invokeFunctionNode) {
    return invokeFunction(
        constructor.getConstructorFunction(),
        callerFrame,
        state,
        arguments,
        schema,
        defaultsExecutionMode,
        argumentsExecutionMode,
        isTail,
        invokeFunctionNode);
  }

  @Specialization
  public Stateful invokeDynamicSymbol(
      UnresolvedSymbol symbol,
      MaterializedFrame callerFrame,
      Object state,
      Object[] arguments,
      CallArgumentInfo[] schema,
      InvokeCallableNode.DefaultsExecutionMode defaultsExecutionMode,
      InvokeCallableNode.ArgumentsExecutionMode argumentsExecutionMode,
      boolean isTail,
      @Cached IndirectInvokeFunctionNode invokeFunctionNode,
      @Cached ThunkExecutorNode thisExecutor,
      @Cached MethodResolverNode methodResolverNode) {
    Integer thisArg = InvokeCallableNode.thisArgumentPosition(schema);
    boolean canApplyThis = thisArg != null;
    int thisArgumentPosition = thisArg == null ? 0 : thisArg;
    if (canApplyThis) {
      Object selfArgument = arguments[thisArgumentPosition];
      if (argumentsExecutionMode.shouldExecute()) {
        Stateful selfResult = thisExecutor.executeThunk((Thunk) selfArgument, state, false);
        selfArgument = selfResult.getValue();
        state = selfResult.getState();
        arguments[thisArgumentPosition] = selfArgument;
      }
      Function function = methodResolverNode.execute(symbol, selfArgument);
      return invokeFunctionNode.execute(
          function,
          callerFrame,
          state,
          arguments,
          schema,
          defaultsExecutionMode,
          argumentsExecutionMode,
          isTail);
    } else {
      throw new RuntimeException("Currying without `this` argument is not yet supported.");
    }
  }

  @Fallback
  public Stateful invokeGeneric(
      Object callable,
      MaterializedFrame callerFrame,
      Object state,
      Object[] arguments,
      CallArgumentInfo[] schema,
      InvokeCallableNode.DefaultsExecutionMode defaultsExecutionMode,
      InvokeCallableNode.ArgumentsExecutionMode argumentsExecutionMode,
      boolean isTail) {
    throw new NotInvokableException(callable, this);
  }
}
