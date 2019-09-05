package org.enso.interpreter.builder;

import org.enso.interpreter.*;
import org.enso.interpreter.node.ExpressionNode;
import org.enso.interpreter.node.callable.function.CreateFunctionNode;
import org.enso.interpreter.runtime.Context;
import org.enso.interpreter.runtime.callable.argument.ArgumentDefinition;
import org.enso.interpreter.runtime.callable.atom.AtomConstructor;
import org.enso.interpreter.runtime.callable.function.ArgumentSchema;
import org.enso.interpreter.runtime.callable.function.Function;
import org.enso.interpreter.runtime.error.VariableDoesNotExistException;
import org.enso.interpreter.runtime.scope.ModuleScope;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * A {@code GlobalScopeExpressionFactory} is responsible for converting the top-level definitions of
 * an Enso program into AST nodes for the interpreter to evaluate.
 */
public class ModuleScopeExpressionFactory implements AstGlobalScopeVisitor<ExpressionNode> {
  private final Language language;
  private final ModuleScope moduleScope;

  /**
   * Creates a factory for the given language.
   *
   * @param language the name of the language for which this factory is creating nodes
   * @param moduleScope the scope in which bindings created by this factory should be registered
   */
  public ModuleScopeExpressionFactory(Language language, ModuleScope moduleScope) {
    this.language = language;
    this.moduleScope = moduleScope;
  }

  /**
   * Executes the factory on a global expression.
   *
   * @param expr the expression to execute on
   * @return a runtime node representing the top-level expression
   */
  public ExpressionNode run(AstGlobalScope expr) {
    return expr.visit(this);
  }

  /**
   * Processes definitions in the language global scope.
   *
   * @param imports any imports requested by this module
   * @param typeDefs any type definitions defined in the global scope
   * @param bindings any bindings made in the global scope
   * @param executableExpression the executable expression for the program
   * @return a runtime node representing the whole top-level program scope
   */
  @Override
  public ExpressionNode visitGlobalScope(
      List<AstImport> imports,
      List<AstTypeDef> typeDefs,
      List<AstMethodDef> bindings,
      AstExpression executableExpression)
      throws IOException {

    Context context = language.getCurrentContext();

    for (AstImport imp : imports) {
      moduleScope.addImport(context.requestParse(imp.name()));
    }

    List<AtomConstructor> constructors =
        typeDefs.stream()
            .map(type -> new AtomConstructor(type.name(), moduleScope))
            .collect(Collectors.toList());

    constructors.forEach(moduleScope::registerConstructor);

    IntStream.range(0, constructors.size())
        .forEach(
            idx -> {
              ArgDefinitionFactory argFactory = new ArgDefinitionFactory(language, moduleScope);
              AstTypeDef type = typeDefs.get(idx);
              ArgumentDefinition[] argDefs = new ArgumentDefinition[type.getArguments().size()];

              for (int i = 0; i < type.getArguments().size(); ++i) {
                argDefs[i] = type.getArguments().get(i).visit(argFactory, i);
              }

              constructors.get(idx).initializeFields(argDefs);
            });

    for (AstMethodDef method : bindings) {
      AtomConstructor constructor =
          moduleScope
              .getConstructor(method.typeName())
              .orElseThrow(() -> new VariableDoesNotExistException(method.typeName()));
      ExpressionFactory expressionFactory =
          new ExpressionFactory(
              language,
              method.typeName() + Constants.SCOPE_SEPARATOR + method.methodName(),
              moduleScope);
      CreateFunctionNode funNode =
          expressionFactory.processFunctionBody(
              method.fun().getArguments(), method.fun().getStatements(), method.fun().ret());
      funNode.markTail();
      Function function =
          new Function(funNode.getCallTarget(), null, new ArgumentSchema(funNode.getArgs()));
      moduleScope.registerMethod(constructor, method.methodName(), function);
    }

    ExpressionFactory factory = new ExpressionFactory(this.language, moduleScope);
    return factory.run(executableExpression);
  }
}
