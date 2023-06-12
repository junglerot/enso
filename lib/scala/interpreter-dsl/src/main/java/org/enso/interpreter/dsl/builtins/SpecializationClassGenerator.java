package org.enso.interpreter.dsl.builtins;

import java.util.List;
import javax.lang.model.element.ExecutableElement;

/**
 * Generator for a builtin method with specializations. The target class will always be abstract and
 * constructed via a static `build` method pattern. The generator will also infer parameters to
 * specialize on and generate methods for them.
 */
public class SpecializationClassGenerator extends MethodNodeClassGenerator {
  List<ExecutableElement> elements;

  public SpecializationClassGenerator(
      List<ExecutableElement> methodElements,
      ClassName builtinNode,
      ClassName ownerClazz,
      ClassName stdlibOwner) {
    super(builtinNode, ownerClazz, stdlibOwner);
    this.elements = methodElements;
  }

  @Override
  protected MethodGenerator methodsGen() {
    return new SpecializedMethodsGenerator(elements);
  }

  @Override
  protected boolean isAbstract() {
    return true;
  }
}
