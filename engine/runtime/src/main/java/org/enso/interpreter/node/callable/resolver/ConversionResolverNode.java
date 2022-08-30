package org.enso.interpreter.node.callable.resolver;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import org.enso.interpreter.runtime.Context;
import org.enso.interpreter.runtime.callable.UnresolvedConversion;
import org.enso.interpreter.runtime.callable.UnresolvedSymbol;
import org.enso.interpreter.runtime.callable.function.Function;
import org.enso.interpreter.runtime.data.Type;
import org.enso.interpreter.runtime.error.PanicException;

@GenerateUncached
@ReportPolymorphism
public abstract class ConversionResolverNode extends Node {
  static final int CACHE_SIZE = 10;

  Context getContext() {
    return Context.get(this);
  }

  public abstract Function execute(Type target, Type self, UnresolvedConversion conversion);

  public Function expectNonNull(
      Object self, Type target, Type type, UnresolvedConversion conversion) {
    var result = execute(target, type, conversion);
    if (result == null) {
      throw new PanicException(
          Context.get(this)
              .getBuiltins()
              .error()
              .makeNoSuchConversionError(target, self, conversion),
          this);
    }
    return result;
  }

  @Specialization(
      guards = {
        "!getContext().isInlineCachingDisabled()",
        "cachedConversion == conversion",
        "cachedSelfType == selfType",
        "cachedTarget == target"
      },
      limit = "CACHE_SIZE")
  Function resolveCached(
      Type target,
      Type selfType,
      UnresolvedConversion conversion,
      @Cached("conversion") UnresolvedConversion cachedConversion,
      @Cached("selfType") Type cachedSelfType,
      @Cached("target") Type cachedTarget,
      @Cached("resolveUncached(cachedTarget, cachedSelfType, cachedConversion)")
          Function function) {
    return function;
  }

  @Specialization(replaces = "resolveCached")
  @CompilerDirectives.TruffleBoundary
  Function resolveUncached(Type target, Type self, UnresolvedConversion conversion) {
    return conversion.resolveFor(target, self);
  }
}
