package org.enso.interpreter.node.callable;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.ConditionProfile;
import org.enso.interpreter.node.BaseNode;
import org.enso.interpreter.node.callable.dispatch.IndirectInvokeFunctionNode;
import org.enso.interpreter.node.callable.resolver.HostMethodCallNode;
import org.enso.interpreter.node.callable.resolver.MethodResolverNode;
import org.enso.interpreter.node.callable.thunk.ThunkExecutorNode;
import org.enso.interpreter.node.expression.builtin.number.utils.ToEnsoNumberNode;
import org.enso.interpreter.runtime.EnsoContext;
import org.enso.interpreter.runtime.callable.UnresolvedSymbol;
import org.enso.interpreter.runtime.callable.argument.CallArgumentInfo;
import org.enso.interpreter.runtime.callable.function.Function;
import org.enso.interpreter.runtime.data.ArrayRope;
import org.enso.interpreter.runtime.data.text.Text;
import org.enso.interpreter.runtime.error.DataflowError;
import org.enso.interpreter.runtime.error.PanicSentinel;
import org.enso.interpreter.runtime.error.Warning;
import org.enso.interpreter.runtime.error.WithWarnings;
import org.enso.interpreter.runtime.library.dispatch.TypesLibrary;
import org.enso.interpreter.runtime.state.State;

@GenerateUncached
@ReportPolymorphism
@ImportStatic({HostMethodCallNode.PolyglotCallType.class, HostMethodCallNode.class})
public abstract class IndirectInvokeMethodNode extends Node {

  /**
   * @return a new indirect method invocation node
   */
  public static IndirectInvokeMethodNode build() {
    return IndirectInvokeMethodNodeGen.create();
  }

  public abstract Object execute(
      MaterializedFrame frame,
      State state,
      UnresolvedSymbol symbol,
      Object self,
      Object[] arguments,
      CallArgumentInfo[] schema,
      InvokeCallableNode.DefaultsExecutionMode defaultsExecutionMode,
      InvokeCallableNode.ArgumentsExecutionMode argumentsExecutionMode,
      BaseNode.TailStatus isTail,
      int thisArgumentPosition);

  @Specialization(guards = {"dispatch.hasType(self)", "!dispatch.hasSpecialDispatch(self)"})
  Object doFunctionalDispatch(
      MaterializedFrame frame,
      State state,
      UnresolvedSymbol symbol,
      Object self,
      Object[] arguments,
      CallArgumentInfo[] schema,
      InvokeCallableNode.DefaultsExecutionMode defaultsExecutionMode,
      InvokeCallableNode.ArgumentsExecutionMode argumentsExecutionMode,
      BaseNode.TailStatus isTail,
      int thisArgumentPosition,
      @Shared("typesLib") @CachedLibrary(limit = "10") TypesLibrary dispatch,
      @Shared("methodResolverNode") @Cached MethodResolverNode methodResolverNode,
      @Shared("indirectInvokeFunctionNode") @Cached IndirectInvokeFunctionNode invokeFunctionNode) {
    Function function = methodResolverNode.expectNonNull(self, dispatch.getType(self), symbol);
    return invokeFunctionNode.execute(
        function,
        frame,
        state,
        arguments,
        schema,
        defaultsExecutionMode,
        argumentsExecutionMode,
        isTail);
  }

  @Specialization
  Object doDataflowError(
      MaterializedFrame frame,
      State state,
      UnresolvedSymbol symbol,
      DataflowError self,
      Object[] arguments,
      CallArgumentInfo[] schema,
      InvokeCallableNode.DefaultsExecutionMode defaultsExecutionMode,
      InvokeCallableNode.ArgumentsExecutionMode argumentsExecutionMode,
      BaseNode.TailStatus isTail,
      int thisArgumentPosition,
      @Shared("methodResolverNode") @Cached MethodResolverNode methodResolverNode,
      @Shared("indirectInvokeFunctionNode") @Cached IndirectInvokeFunctionNode invokeFunctionNode,
      @Cached ConditionProfile profile) {
    Function function =
        methodResolverNode.executeResolution(
            EnsoContext.get(this).getBuiltins().dataflowError(), symbol);
    if (profile.profile(function == null)) {
      return self;
    } else {
      return invokeFunctionNode.execute(
          function,
          frame,
          state,
          arguments,
          schema,
          defaultsExecutionMode,
          argumentsExecutionMode,
          isTail);
    }
  }

  @Specialization
  Object doWarning(
      MaterializedFrame frame,
      State state,
      UnresolvedSymbol symbol,
      WithWarnings self,
      Object[] arguments,
      CallArgumentInfo[] schema,
      InvokeCallableNode.DefaultsExecutionMode defaultsExecutionMode,
      InvokeCallableNode.ArgumentsExecutionMode argumentsExecutionMode,
      BaseNode.TailStatus isTail,
      int thisArgumentPosition,
      @Cached IndirectInvokeMethodNode childDispatch) {
    arguments[thisArgumentPosition] = self.getValue();
    ArrayRope<Warning> warnings = self.getReassignedWarningsAsRope(this, false);
    Object result =
        childDispatch.execute(
            frame,
            state,
            symbol,
            self.getValue(),
            arguments,
            schema,
            defaultsExecutionMode,
            argumentsExecutionMode,
            isTail,
            thisArgumentPosition);
    return WithWarnings.appendTo(EnsoContext.get(this), result, warnings);
  }

  @Specialization
  Object doPanicSentinel(
      MaterializedFrame frame,
      State state,
      UnresolvedSymbol symbol,
      PanicSentinel self,
      Object[] arguments,
      CallArgumentInfo[] schema,
      InvokeCallableNode.DefaultsExecutionMode defaultsExecutionMode,
      InvokeCallableNode.ArgumentsExecutionMode argumentsExecutionMode,
      BaseNode.TailStatus isTail,
      int thisArgumentPosition) {
    throw self;
  }

  @Specialization(
      guards = {
        "!methods.hasType(self)",
        "!methods.hasSpecialDispatch(self)",
        "polyglotCallType != NOT_SUPPORTED",
        "polyglotCallType != CONVERT_TO_TEXT"
      })
  Object doPolyglot(
      MaterializedFrame frame,
      State state,
      UnresolvedSymbol symbol,
      Object self,
      Object[] arguments,
      CallArgumentInfo[] schema,
      InvokeCallableNode.DefaultsExecutionMode defaultsExecutionMode,
      InvokeCallableNode.ArgumentsExecutionMode argumentsExecutionMode,
      BaseNode.TailStatus isTail,
      int thisArgumentPosition,
      @Shared("typesLib") @CachedLibrary(limit = "10") TypesLibrary methods,
      @Shared("interopLib") @CachedLibrary(limit = "10") InteropLibrary interop,
      @Bind("getPolyglotCallType(self, symbol, interop)")
          HostMethodCallNode.PolyglotCallType polyglotCallType,
      @Cached ThunkExecutorNode argExecutor,
      @Cached HostMethodCallNode hostMethodCallNode) {
    Object[] args = new Object[arguments.length - 1];
    for (int i = 0; i < arguments.length - 1; i++) {
      var r =
          argExecutor.executeThunk(frame, arguments[i + 1], state, BaseNode.TailStatus.NOT_TAIL);
      if (r instanceof DataflowError) {
        return r;
      }
      args[i] = r;
    }
    return hostMethodCallNode.execute(polyglotCallType, symbol.getName(), self, args);
  }

  @Specialization(
      guards = {
        "!methods.hasType(self)",
        "!methods.hasSpecialDispatch(self)",
        "getPolyglotCallType(self, symbol, interop) == CONVERT_TO_BIG_INT"
      })
  Object doConvertNumber(
      MaterializedFrame frame,
      State state,
      UnresolvedSymbol symbol,
      Object self,
      Object[] arguments,
      CallArgumentInfo[] schema,
      InvokeCallableNode.DefaultsExecutionMode defaultsExecutionMode,
      InvokeCallableNode.ArgumentsExecutionMode argumentsExecutionMode,
      BaseNode.TailStatus isTail,
      int thisArgumentPosition,
      @Shared("typesLib") @CachedLibrary(limit = "10") TypesLibrary methods,
      @Shared("interopLib") @CachedLibrary(limit = "10") InteropLibrary interop,
      @Cached ToEnsoNumberNode toEnsoNumberNode) {
    try {
      var big = interop.asBigInteger(self);
      var number = toEnsoNumberNode.execute(big);
      return execute(
          frame,
          state,
          symbol,
          number,
          arguments,
          schema,
          defaultsExecutionMode,
          argumentsExecutionMode,
          isTail,
          thisArgumentPosition);
    } catch (UnsupportedMessageException ex) {
      throw CompilerDirectives.shouldNotReachHere(ex);
    }
  }

  @Specialization(
      guards = {
        "!methods.hasType(self)",
        "!methods.hasSpecialDispatch(self)",
        "getPolyglotCallType(self, symbol, interop) == CONVERT_TO_TEXT"
      })
  Object doConvertText(
      MaterializedFrame frame,
      State state,
      UnresolvedSymbol symbol,
      Object self,
      Object[] arguments,
      CallArgumentInfo[] schema,
      InvokeCallableNode.DefaultsExecutionMode defaultsExecutionMode,
      InvokeCallableNode.ArgumentsExecutionMode argumentsExecutionMode,
      BaseNode.TailStatus isTail,
      int thisArgumentPosition,
      @Shared("typesLib") @CachedLibrary(limit = "10") TypesLibrary methods,
      @Shared("methodResolverNode") @Cached MethodResolverNode methodResolverNode,
      @Shared("interopLib") @CachedLibrary(limit = "10") InteropLibrary interop,
      @Shared("indirectInvokeFunctionNode") @Cached IndirectInvokeFunctionNode invokeFunctionNode) {
    try {
      var str = interop.asString(self);
      var text = Text.create(str);
      var ctx = EnsoContext.get(this);
      var textType = ctx.getBuiltins().text();
      var function = methodResolverNode.expectNonNull(text, textType, symbol);
      arguments[0] = text;
      return invokeFunctionNode.execute(
          function,
          frame,
          state,
          arguments,
          schema,
          defaultsExecutionMode,
          argumentsExecutionMode,
          isTail);
    } catch (UnsupportedMessageException ex) {
      throw CompilerDirectives.shouldNotReachHere(ex);
    }
  }

  @ExplodeLoop
  @Specialization(
      guards = {
        "!types.hasType(self)",
        "!types.hasSpecialDispatch(self)",
        "getPolyglotCallType(self, symbol, interop) == NOT_SUPPORTED"
      })
  Object doFallback(
      MaterializedFrame frame,
      State state,
      UnresolvedSymbol symbol,
      Object self,
      Object[] arguments,
      CallArgumentInfo[] schema,
      InvokeCallableNode.DefaultsExecutionMode defaultsExecutionMode,
      InvokeCallableNode.ArgumentsExecutionMode argumentsExecutionMode,
      BaseNode.TailStatus isTail,
      int thisArgumentPosition,
      @Shared("methodResolverNode") @Cached MethodResolverNode methodResolverNode,
      @Shared("typesLib") @CachedLibrary(limit = "10") TypesLibrary types,
      @Shared("interopLib") @CachedLibrary(limit = "10") InteropLibrary interop,
      @Shared("indirectInvokeFunctionNode") @Cached IndirectInvokeFunctionNode invokeFunctionNode) {
    Function function =
        methodResolverNode.expectNonNull(self, EnsoContext.get(this).getBuiltins().any(), symbol);
    return invokeFunctionNode.execute(
        function,
        frame,
        state,
        arguments,
        schema,
        defaultsExecutionMode,
        argumentsExecutionMode,
        isTail);
  }
}
