package org.enso.interpreter.node.expression.builtin.meta;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.NeverDefault;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.node.callable.InvokeCallableNode;
import org.enso.interpreter.node.callable.dispatch.InvokeFunctionNode;
import org.enso.interpreter.runtime.EnsoContext;
import org.enso.interpreter.runtime.callable.Annotation;
import org.enso.interpreter.runtime.callable.argument.ArgumentDefinition;
import org.enso.interpreter.runtime.callable.argument.CallArgumentInfo;
import org.enso.interpreter.runtime.callable.function.Function;
import org.enso.interpreter.runtime.callable.function.FunctionSchema;
import org.enso.interpreter.runtime.data.EnsoObject;
import org.enso.interpreter.runtime.data.atom.Atom;
import org.enso.interpreter.runtime.data.atom.StructsLibrary;
import org.enso.interpreter.runtime.data.vector.ArrayLikeHelpers;
import org.enso.interpreter.runtime.error.PanicException;
import org.enso.interpreter.runtime.state.State;

@BuiltinMethod(
    type = "Meta",
    name = "atom_with_hole_builtin",
    description = "Creates a new atom with given constructor and fields.",
    autoRegister = false)
public abstract class AtomWithAHoleNode extends Node {

  static AtomWithAHoleNode build() {
    return AtomWithAHoleNodeGen.create();
  }

  public static boolean isHole(Object v) {
    return v instanceof HoleInAtom;
  }

  abstract Object execute(VirtualFrame frame, Object factory, State state);

  @NeverDefault
  static InvokeCallableNode callWithHole() {
    return InvokeCallableNode.build(
        new CallArgumentInfo[] {new CallArgumentInfo()},
        InvokeCallableNode.DefaultsExecutionMode.EXECUTE,
        InvokeCallableNode.ArgumentsExecutionMode.PRE_EXECUTED);
  }

  @Specialization
  Object doExecute(
      VirtualFrame frame,
      Object factory,
      State state,
      @Cached("callWithHole()") InvokeCallableNode iop,
      @Cached SwapAtomFieldNode swapNode) {
    var ctx = EnsoContext.get(this);
    var lazy = new HoleInAtom();
    var result = iop.execute(factory, frame, state, new Object[] {lazy});
    if (result instanceof Atom atom) {
      var index = swapNode.findHoleIndex(atom, lazy);
      if (index >= 0) {
        var function = swapNode.createFn(lazy);
        lazy.init(atom, index, function);
        return lazy;
      }
    }
    throw new PanicException(ctx.getBuiltins().error().makeUninitializedStateError(result), this);
  }

  @ExportLibrary(InteropLibrary.class)
  static final class HoleInAtom implements EnsoObject {
    Atom result;
    int index;
    Function function;

    HoleInAtom() {}

    void init(Atom result, int index, Function function) {
      this.result = result;
      this.index = index;
      this.function = function;
    }

    @ExportMessage
    boolean hasMembers() {
      return true;
    }

    @ExportMessage
    boolean isMemberReadable(String member) {
      return switch (member) {
        case "value", "fill" -> true;
        default -> false;
      };
    }

    @ExportMessage
    boolean isMemberInvocable(String member) {
      return switch (member) {
        case "fill" -> true;
        default -> false;
      };
    }

    @ExportMessage
    Object getMembers(boolean includeInternal) {
      return ArrayLikeHelpers.wrapStrings("value", "fill");
    }

    @ExportMessage
    Object readMember(String name) throws UnknownIdentifierException {
      if ("value".equals(name)) {
        return result;
      }
      if ("fill".equals(name)) {
        return function;
      }
      throw UnknownIdentifierException.create(name);
    }

    @ExportMessage
    Object invokeMember(
        String name,
        Object[] args,
        @Cached(value = "buildWithArity(1)", allowUncached = true) InvokeFunctionNode invoke)
        throws UnknownIdentifierException {
      if ("fill".equals(name)) {
        if (args.length == 0) {
          return function;
        }
        var ctx = EnsoContext.get(invoke);
        return invoke.execute(function, null, State.create(ctx), args);
      }
      throw UnknownIdentifierException.create(name);
    }

    @ExportMessage
    String toDisplayString(boolean pure) {
      return "Meta.atom_with_hole";
    }
  }

  static final class SwapAtomFieldNode extends RootNode {
    private final FunctionSchema schema;
    @CompilerDirectives.CompilationFinal private int lastIndex = -1;
    @Child private StructsLibrary structs = StructsLibrary.getFactory().createDispatched(10);

    private SwapAtomFieldNode() {
      super(null);
      this.schema =
          new FunctionSchema(
              FunctionSchema.CallerFrameAccess.NONE,
              new ArgumentDefinition[] {
                new ArgumentDefinition(
                    0, "lazy", null, null, ArgumentDefinition.ExecutionMode.EXECUTE),
                new ArgumentDefinition(
                    1, "value", null, null, ArgumentDefinition.ExecutionMode.EXECUTE)
              },
              new boolean[] {true, false},
              new CallArgumentInfo[0],
              new Annotation[0]);
    }

    @NeverDefault
    static SwapAtomFieldNode create() {
      return new SwapAtomFieldNode();
    }

    int findHoleIndex(Atom atom, HoleInAtom lazy) {
      if (lastIndex >= 0 && lastIndex < atom.getConstructor().getArity()) {
        if (structs.getField(atom, lastIndex) == lazy) {
          return lastIndex;
        }
      }
      int index = findHoleIndexLoop(atom, lazy);
      if (index == -1) {
        return -1;
      }
      if (lastIndex == -1) {
        lastIndex = index;
        CompilerDirectives.transferToInterpreterAndInvalidate();
        return index;
      } else {
        if (lastIndex != -2) {
          CompilerDirectives.transferToInterpreterAndInvalidate();
          lastIndex = -2;
        }
      }
      return index;
    }

    @CompilerDirectives.TruffleBoundary
    private int findHoleIndexLoop(Atom atom, HoleInAtom lazy) {
      for (int i = 0; i < atom.getConstructor().getArity(); i++) {
        if (structs.getField(atom, i) == lazy) {
          return i;
        }
      }
      return -1;
    }

    Function createFn(HoleInAtom lazy) {
      var preArgs = new Object[] {lazy, null};
      return new Function(getCallTarget(), null, schema, preArgs, new Object[] {});
    }

    @Override
    public Object execute(VirtualFrame frame) {
      var args = Function.ArgumentsHelper.getPositionalArguments(frame.getArguments());
      if (args[0] instanceof HoleInAtom lazy) {
        var field = structs.getField(lazy.result, lazy.index);
        var newValue = args[1];
        if (field == lazy) {
          structs.setField(lazy.result, lazy.index, newValue);
        }
        return newValue;
      }
      return EnsoContext.get(this).getBuiltins().nothing();
    }
  }
}
