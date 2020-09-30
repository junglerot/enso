package org.enso.interpreter.node.expression.builtin.interop.generic;

import com.oracle.truffle.api.interop.*;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.BranchProfile;
import org.enso.interpreter.Constants;
import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.node.expression.builtin.text.util.ToJavaStringNode;
import org.enso.interpreter.runtime.data.Array;
import org.enso.interpreter.runtime.data.text.Text;
import org.enso.interpreter.runtime.error.PanicException;

@BuiltinMethod(
    type = "Polyglot",
    name = "invoke",
    description = "Invokes a polyglot method by name, dispatching by the target argument.")
public class InvokeNode extends Node {
  private @Child InteropLibrary library =
      InteropLibrary.getFactory().createDispatched(Constants.CacheSizes.BUILTIN_INTEROP_DISPATCH);
  private @Child ToJavaStringNode toJavaStringNode = ToJavaStringNode.build();
  private final BranchProfile err = BranchProfile.create();

  Object execute(Object _this, Object target, Text name, Array arguments) {
    try {
      return library.invokeMember(target, toJavaStringNode.execute(name), arguments.getItems());
    } catch (UnsupportedMessageException
        | ArityException
        | UnsupportedTypeException
        | UnknownIdentifierException e) {
      err.enter();
      throw new PanicException(e.getMessage(), this);
    }
  }
}
