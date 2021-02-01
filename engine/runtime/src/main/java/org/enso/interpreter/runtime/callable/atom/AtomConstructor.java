package org.enso.interpreter.runtime.callable.atom;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.CachedContext;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.RootNode;
import org.enso.interpreter.Language;
import org.enso.interpreter.node.ExpressionNode;
import org.enso.interpreter.node.callable.argument.ReadArgumentNode;
import org.enso.interpreter.node.expression.atom.GetFieldNode;
import org.enso.interpreter.node.expression.atom.InstantiateNode;
import org.enso.interpreter.node.expression.atom.QualifiedAccessorNode;
import org.enso.interpreter.node.expression.builtin.InstantiateAtomNode;
import org.enso.interpreter.runtime.Context;
import org.enso.interpreter.runtime.callable.UnresolvedSymbol;
import org.enso.interpreter.runtime.callable.argument.ArgumentDefinition;
import org.enso.interpreter.runtime.callable.function.Function;
import org.enso.interpreter.runtime.callable.function.FunctionSchema;
import org.enso.interpreter.runtime.library.dispatch.MethodDispatchLibrary;
import org.enso.interpreter.runtime.scope.ModuleScope;
import org.enso.pkg.QualifiedName;

/** A representation of an Atom constructor. */
@ExportLibrary(InteropLibrary.class)
@ExportLibrary(MethodDispatchLibrary.class)
public final class AtomConstructor implements TruffleObject {

  private final String name;
  private final ModuleScope definitionScope;
  private @CompilerDirectives.CompilationFinal Atom cachedInstance;
  private @CompilerDirectives.CompilationFinal Function constructorFunction;

  /**
   * Creates a new Atom constructor for a given name. The constructor is not valid until {@link
   * AtomConstructor#initializeFields(ArgumentDefinition...)} is called.
   *
   * @param name the name of the Atom constructor
   * @param definitionScope the scope in which this constructor was defined
   */
  public AtomConstructor(String name, ModuleScope definitionScope) {
    this.name = name;
    this.definitionScope = definitionScope;
  }

  /**
   * Sets the fields of this {@link AtomConstructor} and generates a constructor function.
   *
   * @param args the arguments this constructor will take
   * @return {@code this}, for convenience
   */
  public AtomConstructor initializeFields(ArgumentDefinition... args) {
    CompilerDirectives.transferToInterpreterAndInvalidate();
    this.constructorFunction = buildConstructorFunction(args);
    generateMethods(args);
    if (args.length == 0) {
      cachedInstance = new Atom(this);
    } else {
      cachedInstance = null;
    }
    return this;
  }

  /**
   * Generates a constructor function to be used for object instantiation from other Enso code.
   *
   * @param args the argument definitions for the constructor function to take
   * @return a {@link Function} taking the specified arguments and returning an instance for this
   *     {@link AtomConstructor}
   */
  private Function buildConstructorFunction(ArgumentDefinition[] args) {
    ExpressionNode[] argumentReaders = new ExpressionNode[args.length];
    for (int i = 0; i < args.length; i++) {
      argumentReaders[i] = ReadArgumentNode.build(i, args[i].getDefaultValue().orElse(null));
    }
    ExpressionNode instantiateNode = InstantiateNode.build(this, argumentReaders);
    RootNode rootNode = InstantiateAtomNode.build(null, name, instantiateNode);
    RootCallTarget callTarget = Truffle.getRuntime().createCallTarget(rootNode);
    return new Function(callTarget, null, new FunctionSchema(args));
  }

  private void generateMethods(ArgumentDefinition[] args) {
    generateQualifiedAccessor();
    for (ArgumentDefinition arg : args) {
      definitionScope.registerMethod(this, arg.getName(), generateGetter(arg.getPosition()));
    }
  }

  private void generateQualifiedAccessor() {
    QualifiedAccessorNode node = new QualifiedAccessorNode(null, this);
    RootCallTarget callTarget = Truffle.getRuntime().createCallTarget(node);
    Function function =
        new Function(
            callTarget,
            null,
            new FunctionSchema(
                new ArgumentDefinition(0, "this", ArgumentDefinition.ExecutionMode.EXECUTE)));
    definitionScope.registerMethod(
        definitionScope.getAssociatedType(), this.name.toLowerCase(), function);
  }

  private Function generateGetter(int position) {
    GetFieldNode node = new GetFieldNode(null, position);
    RootCallTarget callTarget = Truffle.getRuntime().createCallTarget(node);
    return new Function(
        callTarget,
        null,
        new FunctionSchema(
            new ArgumentDefinition(0, "this", ArgumentDefinition.ExecutionMode.EXECUTE)));
  }

  /**
   * Gets the name of the constructor.
   *
   * @return the name of the Atom constructor
   */
  public String getName() {
    return name;
  }

  /**
   * Gets the scope in which this constructor was defined.
   *
   * @return the scope in which this constructor was defined
   */
  public ModuleScope getDefinitionScope() {
    return definitionScope;
  }

  /**
   * Gets the number of arguments expected by the constructor.
   *
   * @return the number of args expected by the constructor.
   */
  public int getArity() {
    return constructorFunction.getSchema().getArgumentsCount();
  }

  /**
   * Creates a new runtime instance of the Atom represented by this constructor.
   *
   * @param arguments the runtime arguments to the constructor
   * @return a new instance of the atom represented by this constructor
   */
  // TODO [AA] Check where this can be called from user code.
  public Atom newInstance(Object... arguments) {
    if (cachedInstance != null) return cachedInstance;
    return new Atom(this, arguments);
  }

  /**
   * Creates a textual representation of this Atom constructor, useful for debugging.
   *
   * @return a textual representation of this Atom constructor
   */
  @Override
  public String toString() {
    return name;
  }

  /**
   * Gets the constructor function of this constructor.
   *
   * @return the constructor function of this constructor.
   */
  public Function getConstructorFunction() {
    return constructorFunction;
  }

  /**
   * Marks this object as instantiable through the polyglot APIs.
   *
   * @return {@code true}
   */
  @ExportMessage
  boolean isInstantiable() {
    return true;
  }

  /**
   * Handles instantiation through the polyglot APIs.
   *
   * @param arguments the field values for the new instance.
   * @return an instance of this constructor with expected fields.
   * @throws ArityException when the provided field count does match this constructor's field count.
   */
  @ExportMessage
  Atom instantiate(Object... arguments) throws ArityException {
    if (arguments.length != getArity()) {
      throw ArityException.create(getArity(), arguments.length);
    }
    if (cachedInstance != null) {
      return cachedInstance;
    }
    return newInstance(arguments);
  }

  @ExportMessage
  String toDisplayString(boolean allowSideEffects) {
    return "Constructor<" + name + ">";
  }

  /** @return the fully qualified name of this constructor. */
  public QualifiedName getQualifiedName() {
    if (this == this.getDefinitionScope().getAssociatedType()) {
      return definitionScope.getModule().getName();
    } else {
      return definitionScope.getModule().getName().createChild(getName());
    }
  }

  /** @return the fields defined by this constructor. */
  public ArgumentDefinition[] getFields() {
    return constructorFunction.getSchema().getArgumentInfos();
  }

  @ExportMessage
  boolean hasFunctionalDispatch() {
    return true;
  }

  @ExportMessage
  static class GetFunctionalDispatch {
    static final int CACHE_SIZE = 10;

    @CompilerDirectives.TruffleBoundary
    static Function doResolve(
        Context context, AtomConstructor cons, UnresolvedSymbol symbol) {
      return symbol.resolveFor(cons, context.getBuiltins().any());
    }

    @Specialization(
        guards = {
          "!context.isCachingDisabled()",
          "cachedSymbol == symbol",
          "_this == cachedConstructor",
          "function != null"
        },
        limit = "CACHE_SIZE")
    static Function resolveCached(
        AtomConstructor _this,
        UnresolvedSymbol symbol,
        @CachedContext(Language.class) Context context,
        @Cached("symbol") UnresolvedSymbol cachedSymbol,
        @Cached("_this") AtomConstructor cachedConstructor,
        @Cached("doResolve(context, cachedConstructor, cachedSymbol)")
            Function function) {
      return function;
    }

    @Specialization(replaces = "resolveCached")
    static Function resolve(
        AtomConstructor _this,
        UnresolvedSymbol symbol,
        @CachedContext(Language.class) Context context)
        throws MethodDispatchLibrary.NoSuchMethodException {
      Function function = doResolve(context, _this, symbol);
      if (function == null) {
        throw new MethodDispatchLibrary.NoSuchMethodException();
      }
      return function;
    }
  }
}
