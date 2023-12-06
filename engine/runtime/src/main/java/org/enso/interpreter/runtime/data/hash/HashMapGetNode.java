package org.enso.interpreter.runtime.data.hash;

import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.dsl.Suspend;
import org.enso.interpreter.node.BaseNode.TailStatus;
import org.enso.interpreter.node.callable.thunk.ThunkExecutorNode;
import org.enso.interpreter.runtime.EnsoContext;
import org.enso.interpreter.runtime.state.State;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnknownKeyException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.Node;

@BuiltinMethod(
    type = "Map",
    name = "get_builtin",
    description = """
        Gets a value from the map on the specified key, or the given default.
        """,
    autoRegister = false,
    inlineable = true
)
@GenerateUncached
public abstract class HashMapGetNode extends Node {

  public static HashMapGetNode build() {
    return HashMapGetNodeGen.create();
  }

  public abstract Object execute(VirtualFrame frame, State state, Object self, Object key, @Suspend Object defaultValue);

  @Specialization(guards = "interop.hasHashEntries(self)", limit = "3")
  Object hashMapGet(
    VirtualFrame frame,
    State state, Object self, Object key, Object defaultValue,
    @CachedLibrary("self") InteropLibrary interop,
    @Shared @Cached("build()") ThunkExecutorNode thunkExecutorNode
  ) {
      try {
        return interop.readHashValue(self, key);
      } catch (UnknownKeyException e) {
        return thunkExecutorNode.executeThunk(frame, defaultValue, state, TailStatus.NOT_TAIL);
      } catch (UnsupportedMessageException e) {
        var ctx = EnsoContext.get(interop);
        throw ctx.raiseAssertionPanic(interop, null, e);
      }
  }

  @Fallback
  Object fallback(VirtualFrame frame, State state, Object self, Object key, Object defaultValue,
      @Shared @Cached("build()") ThunkExecutorNode thunkExecutorNode) {
    return thunkExecutorNode.executeThunk(frame, defaultValue, state, TailStatus.NOT_TAIL);
  }
}
