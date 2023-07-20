package org.enso.interpreter.node.controlflow.caseexpr;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.CountingConditionProfile;
import org.enso.interpreter.node.expression.builtin.meta.IsValueOfTypeNode;
import org.enso.interpreter.runtime.data.Type;

/** An implementation of the case expression specialised to working on types. */
@NodeInfo(shortName = "TypeMatch")
public class CatchTypeBranchNode extends BranchNode {

  private final Type expectedType;
  private @Child IsValueOfTypeNode isValueOfTypeNode = IsValueOfTypeNode.build();
  private final CountingConditionProfile profile = CountingConditionProfile.create();

  CatchTypeBranchNode(Type tpe, RootCallTarget functionNode, boolean terminalBranch) {
    super(functionNode, terminalBranch);
    this.expectedType = tpe;
  }

  /**
   * Creates a node to handle the case by-type.
   *
   * @param tpe type to match against
   * @param functionNode the function to execute in this case
   * @return a catch-all node
   */
  public static CatchTypeBranchNode build(
      Type tpe, RootCallTarget functionNode, boolean terminalBranch) {
    return new CatchTypeBranchNode(tpe, functionNode, terminalBranch);
  }

  public void execute(VirtualFrame frame, Object state, Object value) {
    if (profile.profile(isValueOfTypeNode.execute(expectedType, value))) {
      accept(frame, state, new Object[] {value});
    }
  }
}
