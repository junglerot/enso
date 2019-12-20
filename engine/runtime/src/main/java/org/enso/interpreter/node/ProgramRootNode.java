package org.enso.interpreter.node;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import org.enso.interpreter.Language;
import org.enso.interpreter.node.callable.dispatch.CallOptimiserNode;
import org.enso.interpreter.node.expression.atom.InstantiateNode;
import org.enso.interpreter.runtime.Context;
import org.enso.interpreter.runtime.callable.function.Function;
import org.enso.interpreter.runtime.scope.LocalScope;
import org.enso.interpreter.runtime.scope.ModuleScope;

import java.io.File;
import java.util.Optional;

/**
 * This node handles static transformation of the input AST before execution and represents the root
 * of an Enso program.
 *
 * <p>As much of the static transformation and analysis functionality required by the interpreter
 * must have access to the interpreter, it must take place as part of the interpreter context. As a
 * result, this node handles the transformations and re-writes
 */
@NodeInfo(shortName = "ProgramRoot", description = "The root of an Enso program's execution")
public class ProgramRootNode extends RootNode {
  private final Source sourceCode;
  private @CompilerDirectives.CompilationFinal ModuleScope moduleScope;

  /**
   * Constructs the root node.
   *
   * @param language the language instance in which this will execute
   * @param sourceCode the code to compile and execute
   */
  public ProgramRootNode(Language language, Source sourceCode) {
    super(language);
    this.sourceCode = sourceCode;
  }

  /**
   * Executes the static analysis passes before executing the resultant program.
   *
   * @param frame the stack frame to execute in
   * @return the result of executing this node
   */
  @Override
  public Object execute(VirtualFrame frame) {
    if (moduleScope == null) {
      CompilerDirectives.transferToInterpreterAndInvalidate();
      Context context = lookupContextReference(Language.class).get();
      moduleScope = context.createScope(sourceCode.getName());
      context.compiler().run(this.sourceCode, moduleScope);
    }
    // Note [Static Passes]
    return moduleScope;
  }

  /* Note [Static Passes]
   * ~~~~~~~~~~~~~~~~~~~~
   * Almost all of the static analysis functionality required by the interpreter requires access to
   * the interpreter to execute small amounts of code. This is for purposes such as:
   * - Type-level computation and evaluation during typechecking.
   * - Compile-Time Function Evaluation (CTFE) for optimisation.
   * - Various other re-write mechanisms that involve code execution.
   *
   * The contract expected from a Truffle Language states that there is to be no access to the
   * interpreter context during parsing, which is the most natural time to perform these
   * transformation passes. As a result, we have to perform them inside the interpreter once parsing
   * is completed.
   *
   * To that end, we have a special kind of root node. It is constructed with the input AST only,
   * and when executed it takes the input source and executes a sequence of analyses and
   * transformations such that the end result is a registration of all defined symbols in the
   * Language Context.
   *
   */

}
