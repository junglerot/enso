package org.enso.interpreter;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Scope;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.debug.DebuggerTags;
import com.oracle.truffle.api.instrumentation.ProvidedTags;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.nodes.RootNode;
import org.enso.interpreter.builder.FileDetector;
import org.enso.interpreter.node.ProgramRootNode;
import org.enso.interpreter.runtime.Context;
import org.enso.polyglot.RuntimeOptions;
import org.enso.polyglot.LanguageInfo;
import org.graalvm.options.OptionDescriptors;

import java.util.Collections;

/**
 * The root of the Enso implementation.
 *
 * <p>This class contains all of the services needed by a Truffle language to enable interoperation
 * with other guest languages on the same VM. This ensures that Enso is usable via the polyglot API,
 * and hence that it can both call other languages seamlessly, and be called from other languages.
 *
 * <p>See {@link TruffleLanguage} for more information on the lifecycle of a language.
 */
@TruffleLanguage.Registration(
    id = LanguageInfo.ID,
    name = LanguageInfo.NAME,
    implementationName = LanguageInfo.IMPLEMENTATION,
    version = LanguageInfo.VERSION,
    defaultMimeType = LanguageInfo.MIME_TYPE,
    characterMimeTypes = {LanguageInfo.MIME_TYPE},
    contextPolicy = TruffleLanguage.ContextPolicy.SHARED,
    fileTypeDetectors = FileDetector.class)
@ProvidedTags({
  DebuggerTags.AlwaysHalt.class,
  StandardTags.CallTag.class,
  StandardTags.ExpressionTag.class,
  StandardTags.RootTag.class,
  StandardTags.TryBlockTag.class
})
public final class Language extends TruffleLanguage<Context> {
  /**
   * Creates a new Enso context.
   *
   * @param env the language execution environment
   * @return a new Enso context
   */
  @Override
  protected Context createContext(Env env) {
    return new Context(this, env);
  }

  /**
   * Checks if a given object is native to Enso.
   *
   * @param object the object to check
   * @return {@code true} if {@code object} belongs to Enso, {@code false} otherwise
   */
  @Override
  protected boolean isObjectOfLanguage(Object object) {
    return false;
  }

  /**
   * Checks if this Enso execution environment is accessible in a multithreaded context.
   *
   * @param thread the thread to check access for
   * @param singleThreaded whether or not execution is single threaded
   * @return whether or not thread access is allowed
   */
  @Override
  protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) {
    return super.isThreadAccessAllowed(thread, singleThreaded);
  }

  /**
   * Parses Enso source code ready for execution.
   *
   * @param request the source to parse, plus contextual information
   * @return a ready-to-execute node representing the code provided in {@code request}
   */
  @Override
  protected CallTarget parse(ParsingRequest request) {
    RootNode root = new ProgramRootNode(this, request.getSource());
    return Truffle.getRuntime().createCallTarget(root);
  }

  /**
   * Gets the current Enso execution context.
   *
   * @return the current execution context
   */
  public Context getCurrentContext() {
    return getCurrentContext(Language.class);
  }

  /**
   * Returns the supported options descriptors, for use by Graal's engine. `
   *
   * @return The supported options descriptors
   */
  @Override
  protected OptionDescriptors getOptionDescriptors() {
    return RuntimeOptions.OPTION_DESCRIPTORS;
  }

  /**
   * Returns the top scope of the requested context.
   *
   * @param context the context holding the top scope.
   * @return a singleton collection containing the context's top scope.
   */
  @Override
  protected Iterable<Scope> findTopScopes(Context context) {
    return Collections.singleton(context.compiler().topScope().getScope());
  }
}
