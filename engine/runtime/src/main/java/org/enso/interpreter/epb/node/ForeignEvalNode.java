package org.enso.interpreter.epb.node;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.dsl.CachedContext;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import org.enso.interpreter.epb.EpbContext;
import org.enso.interpreter.epb.EpbLanguage;
import org.enso.interpreter.epb.EpbParser;
import org.enso.interpreter.epb.runtime.GuardedTruffleContext;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public abstract class ForeignEvalNode extends RootNode {
  private final EpbParser.Result code;
  private @Child ForeignFunctionCallNode foreign;
  private @Child ContextRewrapNode rewrapNode = ContextRewrapNodeGen.create();
  private final String[] argNames;

  ForeignEvalNode(EpbLanguage language, EpbParser.Result code, List<String> arguments) {
    super(language, new FrameDescriptor());
    this.code = code;
    argNames = arguments.toArray(new String[0]);
  }

  /**
   * Creates a new instance of this node
   *
   * @param language the current language instance
   * @param code the result of parsing EPB code
   * @param arguments argument names allowed in the function body
   * @return an instance of this node
   */
  public static ForeignEvalNode build(
      EpbLanguage language, EpbParser.Result code, List<String> arguments) {
    return ForeignEvalNodeGen.create(language, code, arguments);
  }

  @Specialization
  Object doExecute(
      VirtualFrame frame,
      @CachedContext(EpbLanguage.class) ContextReference<EpbContext> contextRef) {
    ensureParsed(contextRef);
    return foreign.execute(frame.getArguments());
  }

  private void ensureParsed(ContextReference<EpbContext> ctxRef) {
    if (foreign == null) {
      getLock().lock();
      try {
        if (foreign == null) {
          CompilerDirectives.transferToInterpreterAndInvalidate();
          switch (code.getLanguage()) {
            case JS:
              parseJs(ctxRef);
              break;
            case PY:
              parsePy(ctxRef);
              break;
            default:
              throw new IllegalStateException("Unsupported language resulted from EPB parsing");
          }
        }
      } finally {
        getLock().unlock();
      }
    }
  }

  private void parseJs(ContextReference<EpbContext> ctxRef) {
    EpbContext context = ctxRef.get();
    GuardedTruffleContext outer = context.getCurrentContext();
    GuardedTruffleContext inner = context.getInnerContext();
    Object p = inner.enter(this);
    try {
      String args = Arrays.stream(argNames).skip(1).collect(Collectors.joining(","));
      String wrappedSrc =
          "var poly_enso_eval=function("
              + args
              + "){\n"
              + code.getForeignSource()
              + "\n};poly_enso_eval";
      Source source = Source.newBuilder(code.getLanguage().getTruffleId(), wrappedSrc, "").build();
      CallTarget ct = ctxRef.get().getEnv().parseInternal(source);
      Object fn = rewrapNode.execute(ct.call(), inner, outer);
      foreign = insert(JsForeignNode.build(argNames.length, fn));
    } finally {
      inner.leave(this, p);
    }
  }

  private void parsePy(ContextReference<EpbContext> ctxRef) {
    EpbContext context = ctxRef.get();
    GuardedTruffleContext outer = context.getCurrentContext();
    GuardedTruffleContext inner = context.getInnerContext();
    Object p = inner.enter(this);
    try {
      String args =
          Arrays.stream(argNames)
              .map(arg -> arg.equals("this") ? "self" : arg)
              .collect(Collectors.joining(","));
      String head =
          "import polyglot\n"
              + "@polyglot.export_value\n"
              + "def poly_enso_py_eval("
              + args
              + "):\n";
      String indentLines =
          code.getForeignSource().lines().map(l -> "    " + l).collect(Collectors.joining("\n"));
      Source source =
          Source.newBuilder(code.getLanguage().getTruffleId(), head + indentLines, "").build();
      CallTarget ct = ctxRef.get().getEnv().parseInternal(source);
      ct.call();
      Object fn = ctxRef.get().getEnv().importSymbol("poly_enso_py_eval");
      Object contextWrapped = rewrapNode.execute(fn, inner, outer);
      foreign = insert(PyForeignNodeGen.create(contextWrapped));
    } finally {
      inner.leave(this, p);
    }
  }
}
