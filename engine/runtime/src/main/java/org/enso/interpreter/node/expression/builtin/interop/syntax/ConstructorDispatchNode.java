package org.enso.interpreter.node.expression.builtin.interop.syntax;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.profiles.BranchProfile;
import org.enso.interpreter.Constants;
import org.enso.interpreter.Language;
import org.enso.interpreter.node.expression.builtin.BuiltinRootNode;
import org.enso.interpreter.runtime.callable.argument.ArgumentDefinition;
import org.enso.interpreter.runtime.callable.function.Function;
import org.enso.interpreter.runtime.error.PanicException;
import org.enso.interpreter.runtime.state.Stateful;
import org.enso.interpreter.runtime.type.TypesGen;

@NodeInfo(shortName = "<new>", description = "Instantiates a polyglot constructor.")
public class ConstructorDispatchNode extends BuiltinRootNode {

  private ConstructorDispatchNode(Language language) {
    super(language);
  }

  private @Child InteropLibrary library =
      InteropLibrary.getFactory().createDispatched(Constants.CacheSizes.BUILTIN_INTEROP_DISPATCH);
  private @Child HostValueToEnsoNode hostValueToEnsoNode = HostValueToEnsoNode.build();
  private final BranchProfile err = BranchProfile.create();

  /**
   * Creates a function wrapping this node.
   *
   * @param language the current language instance
   * @return a function wrapping this node
   */
  public static Function makeFunction(Language language) {
    return Function.fromBuiltinRootNode(
        new ConstructorDispatchNode(language),
        new ArgumentDefinition(0, "this", ArgumentDefinition.ExecutionMode.EXECUTE),
        new ArgumentDefinition(1, "arguments", ArgumentDefinition.ExecutionMode.EXECUTE));
  }

  /**
   * Executes the node.
   *
   * @param frame current execution frame.
   * @return the result of converting input into a string.
   */
  @Override
  public Stateful execute(VirtualFrame frame) {
    Object[] args = Function.ArgumentsHelper.getPositionalArguments(frame.getArguments());
    Object cons = args[0];
    Object state = Function.ArgumentsHelper.getState(frame.getArguments());
    try {
      Object[] arguments = TypesGen.expectArray(args[1]).getItems();
      Object res = hostValueToEnsoNode.execute(library.instantiate(cons, arguments));
      return new Stateful(state, res);
    } catch (UnsupportedMessageException
        | ArityException
        | UnsupportedTypeException
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
    return "<new>";
  }
}
