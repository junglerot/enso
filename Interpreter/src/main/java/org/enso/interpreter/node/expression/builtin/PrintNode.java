package org.enso.interpreter.node.expression.builtin;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.dsl.CachedContext;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.RootNode;
import org.enso.interpreter.Language;
import org.enso.interpreter.node.ExpressionNode;
import org.enso.interpreter.runtime.Builtins;
import org.enso.interpreter.runtime.Context;
import org.enso.interpreter.runtime.callable.argument.ArgumentDefinition;
import org.enso.interpreter.runtime.callable.function.ArgumentSchema;
import org.enso.interpreter.runtime.callable.function.Function;

import java.io.PrintStream;

/** Allows for printing arbitrary values to the standard output. */
@NodeInfo(shortName = "IO.println", description = "Root of the IO.println method.")
public abstract class PrintNode extends RootNode {
  PrintNode(Language language) {
    super(language);
  }

  @Specialization
  Object doPrint(VirtualFrame frame, @CachedContext(Language.class) Context ctx) {
    doPrint(ctx.getOut(), Function.ArgumentsHelper.getPositionalArguments(frame.getArguments())[1]);

    return ctx.getUnit().newInstance();
  }

  @CompilerDirectives.TruffleBoundary
  private void doPrint(PrintStream out, Object object) {
    out.println(object);
  }

  /**
   * Creates a {@link Function} object ignoring its first argument and printing the second to the
   * standard output.
   *
   * @param language the current {@link Language} instance
   * @return a {@link Function} object wrapping the behavior of this node
   */
  public static Function makeFunction(Language language) {
    return Function.fromRootNode(
        PrintNodeGen.create(language),
        new ArgumentDefinition(0, "this", ArgumentDefinition.ExecutionMode.EXECUTE),
        new ArgumentDefinition(1, "value", ArgumentDefinition.ExecutionMode.EXECUTE));
  }
}
