package org.enso.interpreter.node.expression.builtin.interop.generic;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.profiles.BranchProfile;
import org.enso.interpreter.Constants;
import org.enso.interpreter.Language;
import org.enso.interpreter.node.expression.builtin.BuiltinRootNode;
import org.enso.interpreter.runtime.callable.argument.ArgumentDefinition;
import org.enso.interpreter.runtime.callable.function.Function;
import org.enso.interpreter.runtime.callable.function.FunctionSchema.CallStrategy;
import org.enso.interpreter.runtime.error.PanicException;
import org.enso.interpreter.runtime.error.RuntimeError;
import org.enso.interpreter.runtime.state.Stateful;
import org.enso.interpreter.runtime.type.TypesGen;

@NodeInfo(
    shortName = "Polyglot.get_array_element",
    description = "Gets an element by index from a polyglot array.")
public class GetArrayElementNode extends BuiltinRootNode {
  private GetArrayElementNode(Language language) {
    super(language);
  }

  private @Child InteropLibrary library =
      InteropLibrary.getFactory().createDispatched(Constants.CacheSizes.BUILTIN_INTEROP_DISPATCH);
  private final BranchProfile err = BranchProfile.create();

  /**
   * Creates a function wrapping this node.
   *
   * @param language the current language instance
   * @return a function wrapping this node
   */
  public static Function makeFunction(Language language) {
    return Function.fromBuiltinRootNode(
        new GetArrayElementNode(language),
        CallStrategy.ALWAYS_DIRECT,
        new ArgumentDefinition(0, "this", ArgumentDefinition.ExecutionMode.EXECUTE),
        new ArgumentDefinition(1, "array", ArgumentDefinition.ExecutionMode.EXECUTE),
        new ArgumentDefinition(2, "index", ArgumentDefinition.ExecutionMode.EXECUTE));
  }

  /**
   * Executes the node.
   *
   * @param frame current execution frame.
   * @return the result of converting input into a string.
   */
  @Override
  public Stateful execute(VirtualFrame frame) {
    Object array = Function.ArgumentsHelper.getPositionalArguments(frame.getArguments())[1];
    Object state = Function.ArgumentsHelper.getState(frame.getArguments());
    try {
      long index =
          TypesGen.expectLong(
              Function.ArgumentsHelper.getPositionalArguments(frame.getArguments())[2]);

      Object res = library.readArrayElement(array, index);
      return new Stateful(state, res);
    } catch (UnsupportedMessageException
        | InvalidArrayIndexException
        | UnexpectedResultException e) {
      err.enter();
      throw new PanicException(e.getMessage(), this);
    }
  }

  /**
   * Returns a language-specific name for this node.
   *
   * @return the name of this node
   */
  @Override
  public String getName() {
    return "Polyglot.get_array_element";
  }
}
