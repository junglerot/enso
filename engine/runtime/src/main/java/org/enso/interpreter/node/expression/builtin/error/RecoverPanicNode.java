package org.enso.interpreter.node.expression.builtin.error;

import com.oracle.truffle.api.TruffleException;
import com.oracle.truffle.api.dsl.CachedContext;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import org.enso.interpreter.Language;
import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.dsl.MonadicState;
import org.enso.interpreter.dsl.Suspend;
import org.enso.interpreter.node.BaseNode;
import org.enso.interpreter.node.callable.thunk.ThunkExecutorNode;
import org.enso.interpreter.runtime.Context;
import org.enso.interpreter.runtime.error.DataflowError;
import org.enso.interpreter.runtime.error.PanicException;
import org.enso.interpreter.runtime.state.Stateful;

@BuiltinMethod(
    type = "Panic",
    name = "recover",
    description = "Executes an action and converts any Panic thrown by it into an Error")
public abstract class RecoverPanicNode extends Node {
  private @Child ThunkExecutorNode thunkExecutorNode = ThunkExecutorNode.build();

  static RecoverPanicNode build() {
    return RecoverPanicNodeGen.create();
  }

  abstract Stateful execute(@MonadicState Object state, Object _this, @Suspend Object action);

  @Specialization
  Stateful doExecute(
      @MonadicState Object state,
      Object _this,
      Object action,
      @CachedContext(Language.class) Context ctx) {
    try {
      return thunkExecutorNode.executeThunk(action, state, BaseNode.TailStatus.NOT_TAIL);
    } catch (PanicException e) {
      return new Stateful(
          state, DataflowError.withTrace(e.getExceptionObject(), this, e.getStackTrace()));
    } catch (Throwable e) {
      if (ctx.getEnvironment().isHostException(e)) {
        Object cause = ((TruffleException) e).getExceptionObject();
        return new Stateful(
            state,
            DataflowError.withTrace(
                ctx.getBuiltins().error().makePolyglotError(cause), this, e.getStackTrace()));
      }
      throw e;
    }
  }
}
