package org.enso.interpreter.node.expression.builtin.immutable;

import com.oracle.truffle.api.dsl.*;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.Node;
import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.runtime.EnsoContext;
import org.enso.interpreter.runtime.data.EnsoObject;
import org.enso.interpreter.runtime.data.vector.ArrayLikeHelpers;
import org.enso.interpreter.runtime.error.PanicException;

@BuiltinMethod(
    type = "Vector",
    name = "from_polyglot_array",
    description =
        "Creates a Vector by providing its underlying storage as a polyglot array. The underlying array should be guaranteed to never be mutated.",
    autoRegister = false)
public abstract class FromPolyglotArrayBuiltinVectorNode extends Node {

  static FromPolyglotArrayBuiltinVectorNode build() {
    return FromPolyglotArrayBuiltinVectorNodeGen.create();
  }

  abstract EnsoObject execute(Object arr);

  @Specialization(guards = "interop.hasArrayElements(arr)")
  EnsoObject doObject(Object arr, @CachedLibrary(limit = "1") InteropLibrary interop) {
    return ArrayLikeHelpers.asVectorFromArray(arr);
  }

  @Fallback
  EnsoObject doOther(Object arr) {
    var ctx = EnsoContext.get(this);
    throw new PanicException(
        ctx.getBuiltins().error().makeTypeError("polyglot array", arr, "array"), this);
  }
}
