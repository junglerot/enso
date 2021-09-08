package org.enso.runner

import java.io.InputStream
import java.io.OutputStream

import org.enso.loggingservice.{JavaLoggingLogHandler, LogLevel}
import org.enso.polyglot.debugger.{
  DebugServerInfo,
  DebuggerSessionManagerEndpoint
}
import org.enso.polyglot.{PolyglotContext, RuntimeOptions}
import org.graalvm.polyglot.Context

/** Utility class for creating Graal polyglot contexts.
  */
class ContextFactory {

  /** Creates a new Graal polyglot context.
    *
    * @param projectRoot root of the project the interpreter is being run in
    *                    (or empty if ran outside of any projects)
    * @param in the input stream for standard in
    * @param out the output stream for standard out
    * @param repl the Repl manager to use for this context
    * @param logLevel the log level for this context
    * @param strictErrors whether or not to use strict errors
    * @return configured Context instance
    */
  def create(
    projectRoot: String = "",
    in: InputStream,
    out: OutputStream,
    repl: Repl,
    logLevel: LogLevel,
    strictErrors: Boolean = false
  ): PolyglotContext = {
    val context = Context
      .newBuilder()
      .allowExperimentalOptions(true)
      .allowAllAccess(true)
      .option(RuntimeOptions.PROJECT_ROOT, projectRoot)
      .option(RuntimeOptions.STRICT_ERRORS, strictErrors.toString)
      .option(RuntimeOptions.DISABLE_IR_CACHES, "true")
      .option(DebugServerInfo.ENABLE_OPTION, "true")
      .option("js.foreign-object-prototype", "true")
      .out(out)
      .in(in)
      .serverTransport { (uri, peer) =>
        if (uri.toString == DebugServerInfo.URI) {
          new DebuggerSessionManagerEndpoint(repl, peer)
        } else null
      }
      .option(
        RuntimeOptions.LOG_LEVEL,
        JavaLoggingLogHandler.getJavaLogLevelFor(logLevel).getName
      )
      .logHandler(
        JavaLoggingLogHandler.create(JavaLoggingLogHandler.defaultLevelMapping)
      )
      .build
    new PolyglotContext(context)
  }
}
