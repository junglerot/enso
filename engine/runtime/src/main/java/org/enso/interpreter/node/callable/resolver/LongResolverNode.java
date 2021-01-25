package org.enso.interpreter.node.callable.resolver;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.CachedContext;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import org.enso.interpreter.Language;
import org.enso.interpreter.runtime.Context;
import org.enso.interpreter.runtime.callable.UnresolvedSymbol;
import org.enso.interpreter.runtime.callable.function.Function;

@GenerateUncached
@ReportPolymorphism
public abstract class LongResolverNode extends BaseResolverNode {
  public abstract Function execute(UnresolvedSymbol symbol, long self);

  @Specialization(
      guards = {"!context.isCachingDisabled()", "cachedSymbol == symbol", "function != null"},
      limit = "CACHE_SIZE")
  Function resolveCached(
      UnresolvedSymbol symbol,
      long _this,
      @CachedContext(Language.class) Context context,
      @Cached("symbol") UnresolvedSymbol cachedSymbol,
      @Cached("resolveMethodOnLong(context, cachedSymbol)") Function function) {
    return function;
  }

  @Specialization(replaces = "resolveCached")
  Function resolve(
      UnresolvedSymbol symbol, long _this, @CachedContext(Language.class) Context context) {
    Function function = resolveMethodOnLong(context, symbol);
    return throwIfNull(context, function, _this, symbol);
  }
}
