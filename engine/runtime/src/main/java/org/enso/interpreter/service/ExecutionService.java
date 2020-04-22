package org.enso.interpreter.service;

import com.oracle.truffle.api.instrumentation.EventBinding;
import com.oracle.truffle.api.instrumentation.ExecutionEventListener;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.source.SourceSection;
import org.enso.interpreter.instrument.IdExecutionInstrument;
import org.enso.interpreter.node.callable.FunctionCallInstrumentationNode;
import org.enso.interpreter.runtime.Context;
import org.enso.interpreter.runtime.Module;
import org.enso.interpreter.runtime.callable.atom.AtomConstructor;
import org.enso.interpreter.runtime.callable.function.Function;
import org.enso.interpreter.runtime.scope.ModuleScope;

import java.io.File;
import org.enso.text.buffer.Rope;
import org.enso.text.editing.JavaEditorAdapter;
import org.enso.text.editing.model;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A service allowing externally-triggered code execution, registered by an instance of the
 * language.
 */
public class ExecutionService {
  private final Context context;
  private final IdExecutionInstrument idExecutionInstrument;
  private InteropLibrary interopLibrary = InteropLibrary.getFactory().getUncached();

  /**
   * Creates a new instance of this service.
   *
   * @param context the language context to use.
   * @param idExecutionInstrument an instance of the {@link IdExecutionInstrument} to use in the
   *     course of executions.
   */
  public ExecutionService(Context context, IdExecutionInstrument idExecutionInstrument) {
    this.idExecutionInstrument = idExecutionInstrument;
    this.context = context;
  }

  private Optional<FunctionCallInstrumentationNode.FunctionCall> prepareFunctionCall(
      String moduleName, String consName, String methodName) {
    Optional<Module> moduleMay = context.getCompiler().topScope().getModule(moduleName);
    if (!moduleMay.isPresent()) {
      return Optional.empty();
    }
    ModuleScope scope = moduleMay.get().getScope(context);
    Optional<AtomConstructor> atomConstructorMay = scope.getConstructor(consName);
    if (!atomConstructorMay.isPresent()) {
      return Optional.empty();
    }
    AtomConstructor atomConstructor = atomConstructorMay.get();
    Function function = scope.lookupMethodDefinition(atomConstructor, methodName);
    if (function == null) {
      return Optional.empty();
    }
    FunctionCallInstrumentationNode.FunctionCall call =
        new FunctionCallInstrumentationNode.FunctionCall(
            function, context.getBuiltins().unit(), new Object[] {atomConstructor.newInstance()});
    return Optional.of(call);
  }

  /**
   * Executes a function with given arguments, represented as runtime language-level objects.
   *
   * @param call the call metadata.
   * @param valueCallback the consumer for expression value events.
   * @param funCallCallback the consumer for function call events.
   */
  public void execute(
      FunctionCallInstrumentationNode.FunctionCall call,
      Consumer<IdExecutionInstrument.ExpressionValue> valueCallback,
      Consumer<IdExecutionInstrument.ExpressionCall> funCallCallback)
      throws UnsupportedMessageException, ArityException, UnsupportedTypeException {

    SourceSection src = call.getFunction().getSourceSection();
    if (src == null) {
      return;
    }
    EventBinding<ExecutionEventListener> listener =
        idExecutionInstrument.bind(
            call.getFunction().getCallTarget(),
            src.getCharIndex(),
            src.getCharLength(),
            valueCallback,
            funCallCallback);
    interopLibrary.execute(call);
    listener.dispose();
  }

  /**
   * Executes a method described by its name, constructor it's defined on and the module it's
   * defined in.
   *
   * @param modulePath the path to the module where the method is defined.
   * @param consName the name of the constructor the method is defined on.
   * @param methodName the method name.
   * @param valueCallback the consumer for expression value events.
   * @param funCallCallback the consumer for function call events.
   */
  public void execute(
      File modulePath,
      String consName,
      String methodName,
      Consumer<IdExecutionInstrument.ExpressionValue> valueCallback,
      Consumer<IdExecutionInstrument.ExpressionCall> funCallCallback)
      throws UnsupportedMessageException, ArityException, UnsupportedTypeException {
    Optional<FunctionCallInstrumentationNode.FunctionCall> callMay =
        context
            .getModuleNameForFile(modulePath)
            .flatMap(
                moduleName -> prepareFunctionCall(moduleName.toString(), consName, methodName));
    if (!callMay.isPresent()) {
      return;
    }
    execute(callMay.get(), valueCallback, funCallCallback);
  }

  /**
   * Sets a module at a given path to use a literal source.
   *
   * If a module does not exist it will be created.
   *
   * @param path the module path.
   * @param contents the sources to use for it.
   */
  public void setModuleSources(File path, String contents) {
    Optional<Module> module = context.getModuleForFile(path);
    if (!module.isPresent()) {
      module = context.createModuleForFile(path);
    }
    module.ifPresent(mod -> mod.setLiteralSource(contents));
  }

  /**
   * Resets a module to use on-disk sources.
   *
   * @param path the module path.
   */
  public void resetModuleSources(File path) {
    Optional<Module> module = context.getModuleForFile(path);
    module.ifPresent(Module::unsetLiteralSource);
  }

  /**
   * Applies modifications to literal module sources.
   *
   * @param path the module to edit.
   * @param edits the edits to apply.
   */
  public void modifyModuleSources(File path, List<model.TextEdit> edits) {
    Optional<Module> moduleMay = context.getModuleForFile(path);
    if (!moduleMay.isPresent()) {
      return;
    }
    Module module = moduleMay.get();
    if (module.getLiteralSource() == null) {
      return;
    }
    Optional<Rope> editedSource = JavaEditorAdapter.applyEdits(module.getLiteralSource(), edits);
    editedSource.ifPresent(module::setLiteralSource);
  }
}
