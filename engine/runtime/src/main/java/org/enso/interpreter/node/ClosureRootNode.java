package org.enso.interpreter.node;

import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.frame.FrameUtil;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.source.SourceSection;
import org.enso.interpreter.Language;
import org.enso.interpreter.runtime.callable.function.Function;
import org.enso.interpreter.runtime.scope.LocalScope;
import org.enso.interpreter.runtime.scope.ModuleScope;
import org.enso.interpreter.runtime.state.Stateful;

/**
 * This node represents the root of Enso closures and closure-like structures.
 *
 * <p>All new computations in Enso must be executed from within an {@link ClosureRootNode}, as
 * determined by the API provided by Truffle.
 */
@ReportPolymorphism
@NodeInfo(shortName = "Closure", description = "A root node for Enso closures.")
public class ClosureRootNode extends EnsoRootNode {

  @Child private ExpressionNode body;
  private final String qualifiedName;

  private ClosureRootNode(
      Language language,
      LocalScope localScope,
      ModuleScope moduleScope,
      ExpressionNode body,
      SourceSection section,
      String name,
      String qualifiedName) {
    super(language, localScope, moduleScope, name, section);
    this.body = body;
    this.qualifiedName = qualifiedName;
  }

  /**
   * Creates an instance of this node.
   *
   * @param language the language identifier
   * @param localScope a description of the local scope
   * @param moduleScope a description of the module scope
   * @param body the program body to be executed
   * @param section a mapping from {@code body} to the program source
   * @param name a name for the node
   * @return a node representing the specified closure
   */
  public static ClosureRootNode build(
      Language language,
      LocalScope localScope,
      ModuleScope moduleScope,
      ExpressionNode body,
      SourceSection section,
      String name,
      String qualifiedName) {
    return new ClosureRootNode(
        language, localScope, moduleScope, body, section, name, qualifiedName);
  }

  /**
   * Executes the node.
   *
   * @param frame the stack frame to execute in
   * @return the result of executing this node
   */
  @Override
  public Object execute(VirtualFrame frame) {
    Object state = Function.ArgumentsHelper.getState(frame.getArguments());
    frame.setObject(this.getStateFrameSlot(), state);
    Object result = body.executeGeneric(frame);
    state = FrameUtil.getObjectSafe(frame, this.getStateFrameSlot());
    return new Stateful(state, result);
  }

  /**
   * Returns a qualified name that uniquely identifies the node.
   *
   * @return a qualified name of this node.
   */
  @Override
  public String getQualifiedName() {
    return qualifiedName;
  }
}
