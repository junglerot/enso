package org.enso.interpreter.node.controlflow;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.ConditionProfile;
import org.enso.interpreter.runtime.callable.atom.Atom;
import org.enso.interpreter.runtime.callable.atom.AtomConstructor;

/** An implementation of the case expression specialised to working on constructors. */
@NodeInfo(shortName = "ConstructorMatch")
public abstract class ConstructorBranchNode extends BranchNode {
  private final AtomConstructor matcher;
  private final ConditionProfile profile = ConditionProfile.createCountingProfile();

  ConstructorBranchNode(AtomConstructor matcher, RootCallTarget branch) {
    super(branch);
    this.matcher = matcher;
  }

  /**
   * Creates a new node for handling matching on a case expression.
   *
   * @param matcher the expression to use for matching
   * @param branch the expression to be executed if (@code matcher} matches
   * @return a node for matching in a case expression
   */
  public static ConstructorBranchNode build(AtomConstructor matcher, RootCallTarget branch) {
    return ConstructorBranchNodeGen.create(matcher, branch);
  }

  @Specialization
  void doAtom(VirtualFrame frame, Object state, Atom target) {
    if (profile.profile(matcher == target.getConstructor())) {
      accept(frame, state, target.getFields());
    }
  }

  @Fallback
  void doFallback(VirtualFrame frame, Object state, Object target) {}
}
