package org.enso.interpreter.node.controlflow;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.profiles.BranchProfile;
import org.enso.interpreter.node.ExpressionNode;
import org.enso.interpreter.runtime.callable.atom.Atom;
import org.enso.interpreter.runtime.callable.function.Function;
import org.enso.interpreter.runtime.error.RuntimeError;
import org.enso.interpreter.runtime.error.TypeError;

/**
 * A node representing a pattern match on an arbitrary runtime value.
 *
 * <p>Has a scrutinee node and a collection of {@link CaseNode}s. The case nodes get executed one by
 * one, until one throws an {@link BranchSelectedException}, the value of which becomes the result
 * of this pattern match.
 */
@NodeChild(value = "scrutinee", type = ExpressionNode.class)
public abstract class MatchNode extends ExpressionNode {
  @Children private final CaseNode[] cases;
  @Child private CaseNode fallback;
  private final BranchProfile typeErrorProfile = BranchProfile.create();

  MatchNode(CaseNode[] cases, CaseNode fallback) {
    this.cases = cases;
    this.fallback = fallback;
  }

  /**
   * Creates an instance of this node.
   *
   * @param cases the case branches
   * @param fallback the fallback branch
   * @param scrutinee the value being scrutinised
   * @return a node representing a pattern match
   */
  public static MatchNode build(CaseNode[] cases, CaseNode fallback, ExpressionNode scrutinee) {
    return MatchNodeGen.create(cases, fallback, scrutinee);
  }

  /**
   * Sets whether or not the pattern match is tail-recursive.
   *
   * @param isTail whether or not the expression is tail-recursive
   */
  @Override
  @ExplodeLoop
  public void setTail(boolean isTail) {
    for (CaseNode caseNode : cases) {
      caseNode.setTail(isTail);
    }
    fallback.setTail(isTail);
  }

  @Specialization
  Object doError(VirtualFrame frame, RuntimeError error) {
    return error;
  }

  // TODO[MK]: The atom, number and function cases are very repetitive and should be refactored.
  // It poses some engineering challenge – the approaches tried so far included passing the only
  // changing line as a lambda and introducing a separate node between this and the CaseNodes.
  // Both attempts resulted in a performance drop.

  @ExplodeLoop
  @Specialization
  Object doAtom(VirtualFrame frame, Atom atom) {
    try {
      for (CaseNode caseNode : cases) {
        caseNode.executeAtom(frame, atom);
      }
      fallback.executeAtom(frame, atom);
      CompilerDirectives.transferToInterpreter();
      throw new RuntimeException("Impossible behavior.");

    } catch (BranchSelectedException e) {
      // Note [Branch Selection Control Flow]
      frame.setObject(getStateFrameSlot(), e.getResult().getState());
      return e.getResult().getValue();
    } catch (UnexpectedResultException e) {
      typeErrorProfile.enter();
      throw new TypeError("Expected an Atom, got " + e.getResult(), this);
    }
  }

  @ExplodeLoop
  @Specialization
  Object doFunction(VirtualFrame frame, Function function) {
    try {
      for (CaseNode caseNode : cases) {
        caseNode.executeFunction(frame, function);
      }
      fallback.executeFunction(frame, function);
      CompilerDirectives.transferToInterpreter();
      throw new RuntimeException("Impossible behavior.");

    } catch (BranchSelectedException e) {
      // Note [Branch Selection Control Flow]
      frame.setObject(getStateFrameSlot(), e.getResult().getState());
      return e.getResult().getValue();
    } catch (UnexpectedResultException e) {
      typeErrorProfile.enter();
      throw new TypeError("Expected an Atom, got " + e.getResult(), this);
    }
  }

  @ExplodeLoop
  @Specialization
  Object doNumber(VirtualFrame frame, long number) {
    try {

      for (CaseNode caseNode : cases) {
        caseNode.executeNumber(frame, number);
      }
      fallback.executeNumber(frame, number);
      CompilerDirectives.transferToInterpreter();
      throw new RuntimeException("Impossible behavior.");

    } catch (BranchSelectedException e) {
      // Note [Branch Selection Control Flow]
      frame.setObject(getStateFrameSlot(), e.getResult().getState());
      return e.getResult().getValue();
    } catch (UnexpectedResultException e) {
      typeErrorProfile.enter();
      throw new TypeError("Expected an Atom: " + e.getResult(), this);
    }
  }

  /* Note [Branch Selection Control Flow]
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   * Truffle provides no easy way to return control flow from multiple paths. This is entirely due
   * to Java's (current) lack of support for case-expressions.
   *
   * As a result, this implementation resorts to using an exception to short-circuit evaluation on
   * a successful match. This short-circuiting also allows us to hand the result of evaluation back
   * out to the caller (here) wrapped in the exception.
   *
   * The main alternative to this was desugaring to a nested-if, which would've been significantly
   * harder to maintain, and also resulted in significantly higher code complexity.
   */
}
