package org.enso.interpreter.node.expression.builtin.text;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.node.expression.builtin.text.util.ToJavaStringNode;
import org.enso.interpreter.runtime.data.text.Text;

@BuiltinMethod(
    type = "Prim_Text_Helper",
    name = "optimize",
    description = "Forces flattening of a text value, for testing purposes.",
    autoRegister = false)
public abstract class OptimizeNode extends Node {
  private @Child ToJavaStringNode toJavaStringNode = ToJavaStringNode.build();

  static OptimizeNode build() {
    return OptimizeNodeGen.create();
  }

  abstract Object execute(Object text);

  @Specialization
  Text doText(Text text) {
    toJavaStringNode.execute(text);
    return text;
  }

  @Fallback
  Object doOther(Object that) {
    return that;
  }
}
