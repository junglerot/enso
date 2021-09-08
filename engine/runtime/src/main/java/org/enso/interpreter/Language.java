package org.enso.interpreter;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.InstrumentInfo;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLogger;
import com.oracle.truffle.api.debug.DebuggerTags;
import com.oracle.truffle.api.instrumentation.ProvidedTags;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.nodes.RootNode;
import org.enso.distribution.DistributionManager;
import org.enso.distribution.Environment;
import org.enso.distribution.locking.LockManager;
import org.enso.distribution.locking.ThreadSafeFileLockManager;
import org.enso.interpreter.epb.EpbLanguage;
import org.enso.interpreter.instrument.IdExecutionInstrument;
import org.enso.interpreter.instrument.NotificationHandler.Forwarder;
import org.enso.interpreter.instrument.NotificationHandler.TextMode$;
import org.enso.interpreter.node.ProgramRootNode;
import org.enso.interpreter.runtime.Context;
import org.enso.interpreter.runtime.tag.IdentifiedTag;
import org.enso.interpreter.service.ExecutionService;
import org.enso.interpreter.util.FileDetector;
import org.enso.lockmanager.client.ConnectedLockManager;
import org.enso.polyglot.LanguageInfo;
import org.enso.polyglot.RuntimeOptions;
import org.graalvm.options.OptionDescriptors;

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
    dependentLanguages = {EpbLanguage.ID},
    fileTypeDetectors = FileDetector.class,
    services = ExecutionService.class)
@ProvidedTags({
  DebuggerTags.AlwaysHalt.class,
  StandardTags.CallTag.class,
  StandardTags.ExpressionTag.class,
  StandardTags.RootTag.class,
  StandardTags.TryBlockTag.class,
  IdentifiedTag.class
})
public final class Language extends TruffleLanguage<Context> {
  private IdExecutionInstrument idExecutionInstrument;

  /**
   * Creates a new Enso context.
   *
   * <p>This method is meant to be fast, and hence should not perform any long-running logic.
   *
   * @param env the language execution environment
   * @return a new Enso context
   */
  @Override
  protected Context createContext(Env env) {
    var notificationHandler = new Forwarder();
    boolean isInteractiveMode = env.getOptions().get(RuntimeOptions.INTERACTIVE_MODE_KEY);
    boolean isTextMode = !isInteractiveMode;
    if (isTextMode) {
      notificationHandler.addListener(TextMode$.MODULE$);
    }

    TruffleLogger logger = env.getLogger(Language.class);

    var environment = new Environment() {};
    var distributionManager = new DistributionManager(environment);

    LockManager lockManager;
    ConnectedLockManager connectedLockManager = null;

    if (isInteractiveMode) {
      logger.finest(
          "Detected interactive mode, will try to connect to a lock manager managed by it.");
      connectedLockManager = new ConnectedLockManager();
      lockManager = connectedLockManager;
    } else {
      logger.finest("Detected text mode, using a standalone lock manager.");
      lockManager = new ThreadSafeFileLockManager(distributionManager.paths().locks());
    }

    Context context =
        new Context(
            this, getLanguageHome(), env, notificationHandler, lockManager, distributionManager);
    InstrumentInfo idValueListenerInstrument =
        env.getInstruments().get(IdExecutionInstrument.INSTRUMENT_ID);
    idExecutionInstrument = env.lookup(idValueListenerInstrument, IdExecutionInstrument.class);
    env.registerService(
        new ExecutionService(
            context, idExecutionInstrument, notificationHandler, connectedLockManager));
    return context;
  }

  /**
   * Initialize the context.
   *
   * @param context the language context
   */
  @Override
  protected void initializeContext(Context context) {
    context.initialize();
  }

  /**
   * Finalize the context.
   *
   * @param context the language context
   */
  @Override
  protected void finalizeContext(Context context) {
    context.shutdown();
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
    return true;
  }

  /**
   * Parses Enso source code ready for execution.
   *
   * @param request the source to parse, plus contextual information
   * @return a ready-to-execute node representing the code provided in {@code request}
   */
  @Override
  protected CallTarget parse(ParsingRequest request) {
    RootNode root = ProgramRootNode.build(this, request.getSource());
    return Truffle.getRuntime().createCallTarget(root);
  }

  /** {@inheritDoc} */
  @Override
  protected OptionDescriptors getOptionDescriptors() {
    return RuntimeOptions.OPTION_DESCRIPTORS;
  }

  /**
   * Returns the top scope of the requested contenxt.
   *
   * @param context the context holding the top scope
   * @return the language's top scope
   */
  @Override
  protected Object getScope(Context context) {
    return context.getTopScope();
  }

  /** @return a reference to the execution instrument */
  public IdExecutionInstrument getIdExecutionInstrument() {
    return idExecutionInstrument;
  }
}
