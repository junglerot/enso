package org.enso.interpreter.node.expression.builtin.interop.java;

import com.oracle.truffle.api.dsl.CachedContext;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import org.enso.interpreter.Language;
import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.runtime.Context;

@BuiltinMethod(type = "Java", name = "lookup_class", description = "Looks up a Java symbol.")
public abstract class LookupClassNode extends Node {
  static LookupClassNode build() {
    return LookupClassNodeGen.create();
  }

  @Specialization
  Object doExecute(Object _this, String name, @CachedContext(Language.class) Context ctx) {
    return ctx.getEnvironment().lookupHostSymbol(name);
  }

  abstract Object execute(Object _this, String name);
}
