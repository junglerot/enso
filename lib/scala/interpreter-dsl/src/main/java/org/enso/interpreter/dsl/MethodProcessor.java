package org.enso.interpreter.dsl;

import org.enso.interpreter.dsl.model.MethodDefinition;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.stream.Collectors;
import org.openide.util.lookup.ServiceProvider;

/** The processor used to generate code from the {@link BuiltinMethod} annotation. */
@SupportedAnnotationTypes("org.enso.interpreter.dsl.BuiltinMethod")
@SupportedSourceVersion(SourceVersion.RELEASE_11)
@ServiceProvider(service = Processor.class)
public class MethodProcessor extends AbstractProcessor {

  /**
   * Processes annotated elements, generating code for each of them.
   *
   * @param annotations annotation being processed this round.
   * @param roundEnv additional round information.
   * @return {@code true}
   */
  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    for (TypeElement annotation : annotations) {
      Set<? extends Element> annotatedElements = roundEnv.getElementsAnnotatedWith(annotation);
      for (Element elt : annotatedElements) {
        TypeElement element = (TypeElement) elt;
        ExecutableElement executeMethod =
            element.getEnclosedElements().stream()
                .filter(
                    x -> {
                      if (!(x instanceof ExecutableElement)) return false;
                      Name name = x.getSimpleName();
                      return name.contentEquals("execute");
                    })
                .map(x -> (ExecutableElement) x)
                .findFirst()
                .orElseGet(
                    () -> {
                      processingEnv
                          .getMessager()
                          .printMessage(Diagnostic.Kind.ERROR, "No execute method found.", element);
                      return null;
                    });
        if (executeMethod == null) continue;
        String pkgName =
            processingEnv.getElementUtils().getPackageOf(element).getQualifiedName().toString();
        MethodDefinition def = new MethodDefinition(pkgName, element, executeMethod);
        if (!def.validate(processingEnv)) {
          continue;
        }
        try {
          generateCode(def);
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }

    return true;
  }

  private final List<String> necessaryImports =
      Arrays.asList(
          "com.oracle.truffle.api.frame.VirtualFrame",
          "com.oracle.truffle.api.nodes.NodeInfo",
          "com.oracle.truffle.api.nodes.RootNode",
          "com.oracle.truffle.api.nodes.UnexpectedResultException",
          "com.oracle.truffle.api.profiles.BranchProfile",
          "com.oracle.truffle.api.profiles.ConditionProfile",
          "org.enso.interpreter.Language",
          "org.enso.interpreter.node.expression.builtin.BuiltinRootNode",
          "org.enso.interpreter.runtime.callable.argument.ArgumentDefinition",
          "org.enso.interpreter.runtime.callable.function.Function",
          "org.enso.interpreter.runtime.callable.function.FunctionSchema",
          "org.enso.interpreter.runtime.Context",
          "org.enso.interpreter.runtime.data.ArrayRope",
          "org.enso.interpreter.runtime.error.PanicException",
          "org.enso.interpreter.runtime.error.Warning",
          "org.enso.interpreter.runtime.error.WithWarnings",
          "org.enso.interpreter.runtime.state.Stateful",
          "org.enso.interpreter.runtime.type.TypesGen");

  private void generateCode(MethodDefinition methodDefinition) throws IOException {
    JavaFileObject gen =
        processingEnv.getFiler().createSourceFile(methodDefinition.getQualifiedName());

    Set<String> allImports = new HashSet<>(necessaryImports);
    allImports.addAll(methodDefinition.getImports());

    try (PrintWriter out = new PrintWriter(gen.openWriter())) {
      out.println("package " + methodDefinition.getPackageName() + ";");
      out.println();

      allImports.forEach(pkg -> out.println("import " + pkg + ";"));

      out.println();

      out.println("@NodeInfo(");
      out.println("  shortName = \"" + methodDefinition.getDeclaredName() + "\",");
      out.println("  description = \"" + methodDefinition.getDescription() + "\")");
      out.println("public class " + methodDefinition.getClassName() + " extends BuiltinRootNode {");
      out.println("  private @Child " + methodDefinition.getOriginalClassName() + " bodyNode;");

      out.println();

      for (MethodDefinition.ArgumentDefinition arg : methodDefinition.getArguments()) {
        if (!arg.isState() && !arg.isFrame() && !arg.isCallerInfo()) {
          String condName = mkArgumentInternalVarName(arg) + "ConditionProfile";
          String branchName = mkArgumentInternalVarName(arg) + "BranchProfile";
          out.println(
              "  private final ConditionProfile "
                  + condName
                  + " = ConditionProfile.createCountingProfile();");
          out.println("  private final BranchProfile " + branchName + " = BranchProfile.create();");
          if (!arg.isThis() && !arg.acceptsWarning()) {
            String warningName = mkArgumentInternalVarName(arg) + "WarningProfile";
            out.println(
                "  private final BranchProfile " + warningName + " = BranchProfile.create();");
          }
        }
      }
      out.println("  private final BranchProfile anyWarningsProfile = BranchProfile.create();");

      out.println("  private " + methodDefinition.getClassName() + "(Language language) {");
      out.println("    super(language);");
      out.println("    bodyNode = " + methodDefinition.getConstructorExpression() + ";");
      out.println("  }");

      out.println();

      String functionBuilderMethod =
          methodDefinition.needsCallerInfo()
              ? "fromBuiltinRootNodeWithCallerFrameAccess"
              : "fromBuiltinRootNode";

      out.println("  public static Function makeFunction(Language language) {");
      out.println("    return Function." + functionBuilderMethod + "(");
      out.println(
          "        new "
              + methodDefinition.getClassName()
              + "(language)"
              + (methodDefinition.getArguments().size() > 0 ? "," : ""));
      List<String> argumentDefs = new ArrayList<>();
      for (MethodDefinition.ArgumentDefinition arg : methodDefinition.getArguments()) {
        if (arg.isPositional()) {
          String executionMode = arg.isSuspended() ? "PASS_THUNK" : "EXECUTE";
          argumentDefs.add(
              "        new ArgumentDefinition("
                  + arg.getPosition()
                  + ", \""
                  + arg.getName()
                  + "\", ArgumentDefinition.ExecutionMode."
                  + executionMode
                  + ")");
        }
      }
      out.println(String.join(",\n", argumentDefs) + ");");
      out.println("  }");

      out.println();

      out.println("  @Override");
      out.println("  public Stateful execute(VirtualFrame frame) {");
      out.println("    Object state = Function.ArgumentsHelper.getState(frame.getArguments());");
      if (methodDefinition.needsCallerInfo()) {
        out.println(
            "    CallerInfo callerInfo = Function.ArgumentsHelper.getCallerInfo(frame.getArguments());");
      }
      out.println(
          "    Object[] arguments = Function.ArgumentsHelper.getPositionalArguments(frame.getArguments());");
      List<String> callArgNames = new ArrayList<>();
      boolean warningsPossible =
          generateWarningsCheck(out, methodDefinition.getArguments(), "arguments");
      for (MethodDefinition.ArgumentDefinition argumentDefinition :
          methodDefinition.getArguments()) {
        if (argumentDefinition.isState()) {
          callArgNames.add("state");
        } else if (argumentDefinition.isFrame()) {
          callArgNames.add("frame");
        } else if (argumentDefinition.isCallerInfo()) {
          callArgNames.add("callerInfo");
        } else {
          callArgNames.add(mkArgumentInternalVarName(argumentDefinition));
          generateArgumentRead(out, argumentDefinition, "arguments");
        }
      }
      String executeCall = "bodyNode.execute(" + String.join(", ", callArgNames) + ")";
      if (warningsPossible) {
        out.println("    if (anyWarnings) {");
        out.println("      anyWarningsProfile.enter();");
        if (methodDefinition.modifiesState()) {
          out.println("      Stateful result = " + executeCall + ";");
          out.println(
              "      Object newValue = WithWarnings.appendTo(result.getValue(), gatheredWarnings);");
          out.println("      return new Stateful(result.getState(), newValue);");
        } else {
          out.println("      Object result = " + executeCall + ";");
          out.println(
              "      return new Stateful(state, WithWarnings.appendTo(result, gatheredWarnings));");
        }
        out.println("    } else {");
        if (methodDefinition.modifiesState()) {
          out.println("      return " + executeCall + ";");
        } else {
          out.println("      return new Stateful(state, " + executeCall + ");");
        }
        out.println("    }");
      } else {
        if (methodDefinition.modifiesState()) {
          out.println("    return " + executeCall + ";");
        } else {
          out.println("    return new Stateful(state, " + executeCall + ");");
        }
      }
      out.println("  }");

      out.println();

      out.println("  @Override");
      out.println("  public String getName() {");
      out.println("    return \"" + methodDefinition.getDeclaredName() + "\";");
      out.println("  }");

      out.println();

      out.println("  @Override");
      out.println("  public boolean isCloningAllowed() {");
      out.println("    return true;");
      out.println("  }");

      out.println();

      out.println("  @Override");
      out.println("  protected boolean isCloneUninitializedSupported() {");
      out.println("    return true;");
      out.println("  }");

      out.println();

      out.println("  @Override");
      out.println("  protected RootNode cloneUninitialized() {");
      out.println("    return new " + methodDefinition.getClassName() + "(Language.get(this));");
      out.println("  }");

      out.println();

      out.println("}");
    }
  }

  private void generateArgumentRead(
      PrintWriter out, MethodDefinition.ArgumentDefinition arg, String argsArray) {
    if (!arg.requiresCast()) {
      generateUncastedArgumentRead(out, arg, argsArray);
    } else if (arg.getName().equals("this") && arg.getPosition() == 0) {
      generateUncheckedArgumentRead(out, arg, argsArray);
    } else {
      generateCheckedArgumentRead(out, arg, argsArray);
    }

    if (!arg.acceptsError()) {

      String varName = mkArgumentInternalVarName(arg);
      String condProfile = mkArgumentInternalVarName(arg) + "ConditionProfile";
      out.println(
          "    if ("
              + condProfile
              + ".profile(TypesGen.isDataflowError("
              + varName
              + "))) {\n"
              + "      return new Stateful(state, "
              + varName
              + ");\n"
              + "    }");
      if (!(arg.getName().equals("this") && arg.getPosition() == 0)) {
        String branchProfile = mkArgumentInternalVarName(arg) + "BranchProfile";
        out.println(
            "    else if (TypesGen.isPanicSentinel("
                + varName
                + ")) {\n"
                + "      "
                + branchProfile
                + ".enter();\n"
                + "      throw TypesGen.asPanicSentinel("
                + varName
                + ");\n"
                + "    }");
      }
    }
  }

  private void generateUncastedArgumentRead(
      PrintWriter out, MethodDefinition.ArgumentDefinition arg, String argsArray) {
    String varName = mkArgumentInternalVarName(arg);
    out.println(
        "    "
            + arg.getTypeName()
            + " "
            + varName
            + " = "
            + argsArray
            + "["
            + arg.getPosition()
            + "];");
  }

  private void generateUncheckedArgumentRead(
      PrintWriter out, MethodDefinition.ArgumentDefinition arg, String argsArray) {
    String castName = "TypesGen.as" + capitalize(arg.getTypeName());
    String varName = mkArgumentInternalVarName(arg);
    out.println(
        "    "
            + arg.getTypeName()
            + " "
            + varName
            + " = "
            + castName
            + "("
            + argsArray
            + "["
            + arg.getPosition()
            + "]);");
  }

  private void generateCheckedArgumentRead(
      PrintWriter out, MethodDefinition.ArgumentDefinition arg, String argsArray) {
    String castName = "TypesGen.expect" + capitalize(arg.getTypeName());
    String varName = mkArgumentInternalVarName(arg);
    out.println("    " + arg.getTypeName() + " " + varName + ";");
    out.println("    try {");
    out.println(
        "      " + varName + " = " + castName + "(" + argsArray + "[" + arg.getPosition() + "]);");
    out.println("    } catch (UnexpectedResultException e) {");
    out.println("      var builtins = Context.get(this).getBuiltins();");
    out.println(
        "      var expected = builtins.fromTypeSystem(TypesGen.getName(arguments["
            + arg.getPosition()
            + "]));");
    out.println(
        "      var error = builtins.error().makeTypeError(expected, arguments["
            + arg.getPosition()
            + "], \""
            + varName
            + "\");");
    out.println("      throw new PanicException(error,this);");
    out.println("    }");
  }

  private boolean generateWarningsCheck(
      PrintWriter out, List<MethodDefinition.ArgumentDefinition> arguments, String argumentsArray) {
    List<MethodDefinition.ArgumentDefinition> argsToCheck =
        arguments.stream()
            .filter(arg -> !arg.acceptsWarning() && !arg.isThis())
            .collect(Collectors.toList());
    if (argsToCheck.isEmpty()) {
      return false;
    } else {
      out.println("    boolean anyWarnings = false;");
      out.println("    ArrayRope<Warning> gatheredWarnings = new ArrayRope<>();");
      for (var arg : argsToCheck) {
        out.println(
            "    if ("
                + arrayRead(argumentsArray, arg.getPosition())
                + " instanceof WithWarnings) {");
        out.println("      " + mkArgumentInternalVarName(arg) + "WarningProfile.enter();");
        out.println("      anyWarnings = true;");
        out.println(
            "      WithWarnings withWarnings = (WithWarnings) "
                + arrayRead(argumentsArray, arg.getPosition())
                + ";");
        out.println(
            "      "
                + arrayRead(argumentsArray, arg.getPosition())
                + " = withWarnings.getValue();");
        out.println(
            "      gatheredWarnings = gatheredWarnings.prepend(withWarnings.getReassignedWarnings(this));");
        out.println("    }");
      }
      return true;
    }
  }

  private String warningCheck(MethodDefinition.ArgumentDefinition arg) {
    return "(" + mkArgumentInternalVarName(arg) + " instanceof WithWarnings)";
  }

  private String mkArgumentInternalVarName(MethodDefinition.ArgumentDefinition arg) {
    return "arg" + arg.getPosition();
  }

  private String arrayRead(String array, int index) {
    return array + "[" + index + "]";
  }

  private String capitalize(String name) {
    return name.substring(0, 1).toUpperCase() + name.substring(1);
  }
}
