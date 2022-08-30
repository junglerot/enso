package org.enso.interpreter.node.callable.resolver;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import org.checkerframework.checker.units.qual.C;
import org.enso.interpreter.runtime.Context;
import org.enso.interpreter.runtime.builtin.Number;
import org.enso.interpreter.runtime.callable.UnresolvedSymbol;
import org.enso.interpreter.runtime.callable.function.Function;
import org.enso.interpreter.runtime.data.Type;
import org.enso.interpreter.runtime.error.PanicException;
import org.enso.interpreter.runtime.library.dispatch.TypesLibrary;
import org.enso.interpreter.runtime.number.EnsoBigInteger;

@GenerateUncached
@ReportPolymorphism
public abstract class MethodResolverNode extends Node {
  protected static final int CACHE_SIZE = 10;

  Context getContext() {
    return Context.get(this);
  }

  public abstract Function execute(Type type, UnresolvedSymbol symbol);

  public Function expectNonNull(Object self, Type type, UnresolvedSymbol symbol) {
    var result = execute(type, symbol);
    if (result == null) {
      throw new PanicException(
          Context.get(this).getBuiltins().error().makeNoSuchMethodError(self, symbol), this);
    }
    return result;
  }

  @Specialization(
      guards = {
        "!getContext().isInlineCachingDisabled()",
        "cachedSymbol == symbol",
        "cachedType == type"
      },
      limit = "CACHE_SIZE")
  Function resolveCached(
      Type type,
      UnresolvedSymbol symbol,
      @Cached("symbol") UnresolvedSymbol cachedSymbol,
      @Cached("type") Type cachedType,
      @Cached("resolveUncached(cachedType, cachedSymbol)") Function function) {
    return function;
  }

  @Specialization(replaces = "resolveCached")
  @CompilerDirectives.TruffleBoundary
  Function resolveUncached(Type self, UnresolvedSymbol symbol) {
    return symbol.resolveFor(self);
  }
}
