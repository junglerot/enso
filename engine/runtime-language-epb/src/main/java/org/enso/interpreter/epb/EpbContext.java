package org.enso.interpreter.epb;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.TruffleContext;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.Node;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.enso.interpreter.epb.runtime.GuardedTruffleContext;

/**
 * A context for {@link EpbLanguage}. Provides access to both isolated Truffle contexts used in
 * polyglot execution.
 */
public class EpbContext {

  private static final TruffleLanguage.ContextReference<EpbContext> REFERENCE =
      TruffleLanguage.ContextReference.create(EpbLanguage.class);

  private static final String INNER_OPTION = "isEpbInner";
  private final boolean isInner;
  private final TruffleLanguage.Env env;
  private @CompilerDirectives.CompilationFinal GuardedTruffleContext innerContext;
  private final GuardedTruffleContext currentContext;

  /**
   * Creates a new instance of this context.
   *
   * @param env the current language environment.
   */
  public EpbContext(TruffleLanguage.Env env) {
    this.env = env;
    isInner = env.getConfig().get(INNER_OPTION) != null;
    currentContext = new GuardedTruffleContext(env.getContext(), isInner);
  }

  /**
   * Initializes the context.No-op in the inner context. Spawns the inner context if called from the
   * outer context. Shielded against double initialization.
   *
   * @param preInitializeLanguages comma separated list of languages to immediately initialize
   */
  public void initialize(String preInitializeLanguages) {
    if (!isInner) {
      if (innerContext == null) {
        innerContext =
            new GuardedTruffleContext(
                env.newInnerContextBuilder()
                    .initializeCreatorContext(true)
                    .inheritAllAccess(true)
                    .config(INNER_OPTION, "yes")
                    .build(),
                true);
      }
      initializeLanguages(env, innerContext, preInitializeLanguages);
    }
  }

  private static void initializeLanguages(
      TruffleLanguage.Env environment, GuardedTruffleContext innerContext, String langs) {
    if (langs == null || langs.isEmpty()) {
      return;
    }
    var log = environment.getLogger(EpbContext.class);
    log.log(Level.FINE, "Initializing languages {0}", langs);
    var cdl = new CountDownLatch(1);
    var run =
        (Consumer<TruffleContext>)
            (context) -> {
              var lock = innerContext.enter(null);
              try {
                log.log(Level.FINEST, "Entering initialization thread");
                cdl.countDown();
                for (var l : langs.split(",")) {
                  log.log(Level.FINEST, "Initializing language {0}", l);
                  long then = System.currentTimeMillis();
                  var res = context.initializeInternal(null, l);
                  long took = System.currentTimeMillis() - then;
                  log.log(
                      Level.FINE,
                      "Done initializing language {0} with {1} in {2} ms",
                      new Object[] {l, res, took});
                }
              } finally {
                innerContext.leave(null, lock);
              }
            };
    var init = innerContext.createThread(environment, run);
    log.log(Level.FINEST, "Starting initialization thread");
    init.start();
    try {
      cdl.await();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
    log.log(Level.FINEST, "Initializing on background");
  }

  /**
   * @param node the location of context access. Pass {@code null} if not in a node.
   * @return the proper context instance for the current {@link
   *     com.oracle.truffle.api.TruffleContext}.
   */
  public static EpbContext get(Node node) {
    return REFERENCE.get(node);
  }

  /**
   * Checks if this context corresponds to the inner Truffle context.
   *
   * @return true if run in the inner Truffle context, false otherwise.
   */
  public boolean isInner() {
    return isInner;
  }

  /**
   * @return the inner Truffle context handle if called from the outer context, or null if called in
   *     the inner context.
   */
  public GuardedTruffleContext getInnerContext() {
    return innerContext;
  }

  /** @return returns the currently entered Truffle context handle. */
  public GuardedTruffleContext getCurrentContext() {
    return currentContext;
  }

  /** @return the language environment associated with this context. */
  public TruffleLanguage.Env getEnv() {
    return env;
  }
}
