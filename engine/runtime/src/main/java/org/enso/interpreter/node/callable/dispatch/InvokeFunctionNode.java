package org.enso.interpreter.node.callable.dispatch;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.enso.interpreter.Constants;
import org.enso.interpreter.node.BaseNode;
import org.enso.interpreter.node.callable.InvokeCallableNode;
import org.enso.interpreter.node.callable.argument.ArgumentSorterNode;
import org.enso.interpreter.runtime.callable.argument.CallArgumentInfo;
import org.enso.interpreter.runtime.callable.function.Function;
import org.enso.interpreter.runtime.callable.function.FunctionSchema;
import org.enso.interpreter.runtime.state.Stateful;

/**
 * This class represents the protocol for remapping the arguments provided at a call site into the
 * positional order expected by the definition of the {@link Function}.
 */
@NodeInfo(shortName = "ArgumentSorter")
@ImportStatic({CallArgumentInfo.ArgumentMappingBuilder.class})
public abstract class InvokeFunctionNode extends BaseNode {

  private @CompilationFinal(dimensions = 1) CallArgumentInfo[] schema;
  private final InvokeCallableNode.DefaultsExecutionMode defaultsExecutionMode;
  private final InvokeCallableNode.ArgumentsExecutionMode argumentsExecutionMode;

  /**
   * Creates a node that performs the argument organisation for the provided schema.
   *
   * @param schema information about the call arguments in positional order
   * @param defaultsExecutionMode the defaults execution mode for this function invocation
   * @param argumentsExecutionMode the arguments execution mode for this function invocation
   */
  public InvokeFunctionNode(
      CallArgumentInfo[] schema,
      InvokeCallableNode.DefaultsExecutionMode defaultsExecutionMode,
      InvokeCallableNode.ArgumentsExecutionMode argumentsExecutionMode) {
    this.schema = schema;
    this.defaultsExecutionMode = defaultsExecutionMode;
    this.argumentsExecutionMode = argumentsExecutionMode;
  }

  @Specialization(
      guards = "function.getSchema() == cachedSchema",
      limit = Constants.CacheSizes.ARGUMENT_SORTER_NODE)
  Stateful invokeCached(
      Function function,
      VirtualFrame callerFrame,
      Object state,
      Object[] arguments,
      @Cached("function.getSchema()") FunctionSchema cachedSchema,
      @Cached("generate(cachedSchema, getSchema())")
          CallArgumentInfo.ArgumentMapping argumentMapping,
      @Cached("build(cachedSchema, argumentMapping, getArgumentsExecutionMode())")
          ArgumentSorterNode mappingNode,
      @Cached(
              "build(cachedSchema, argumentMapping, getDefaultsExecutionMode(), getArgumentsExecutionMode(), isTail())")
          CurryNode curryNode) {
    ArgumentSorterNode.MappedArguments mappedArguments =
        mappingNode.execute(function, state, arguments);
    return curryNode.execute(
        callerFrame,
        function,
        mappedArguments.getState(),
        mappedArguments.getSortedArguments(),
        mappedArguments.getOversaturatedArguments());
  }

  /**
   * Generates an argument mapping and executes a function with properly ordered arguments. Does not
   * perform any caching and is thus a slow-path operation.
   *
   * @param function the function to execute.
   * @param callerFrame the caller frame to pass to the function
   * @param state the state to pass to the function
   * @param arguments the arguments to reorder and supply to the {@code function}.
   * @return the result of calling {@code function} with the supplied {@code arguments}.
   */
  @Specialization(replaces = "invokeCached")
  Stateful invokeUncached(
      Function function, VirtualFrame callerFrame, Object state, Object[] arguments) {
    CallArgumentInfo.ArgumentMapping argumentMapping =
        CallArgumentInfo.ArgumentMappingBuilder.generate(function.getSchema(), getSchema());
    ArgumentSorterNode mappingNode =
        ArgumentSorterNode.build(
            function.getSchema(), argumentMapping, getArgumentsExecutionMode());
    CurryNode curryNode =
        CurryNode.build(
            function.getSchema(),
            argumentMapping,
            getDefaultsExecutionMode(),
            getArgumentsExecutionMode(),
            isTail());
    return invokeCached(
        function,
        callerFrame,
        state,
        arguments,
        function.getSchema(),
        argumentMapping,
        mappingNode,
        curryNode);
  }

  /**
   * Executes the {@link InvokeFunctionNode} to apply the function to given arguments.
   *
   * @param callable the function to call
   * @param callerFrame the caller frame to pass to the function
   * @param state the state to pass to the function
   * @param arguments the arguments being passed to {@code function}
   * @return the result of executing the {@code function} with reordered {@code arguments}
   */
  public abstract Stateful execute(
      Function callable, VirtualFrame callerFrame, Object state, Object[] arguments);

  CallArgumentInfo[] getSchema() {
    return schema;
  }

  InvokeCallableNode.DefaultsExecutionMode getDefaultsExecutionMode() {
    return this.defaultsExecutionMode;
  }

  InvokeCallableNode.ArgumentsExecutionMode getArgumentsExecutionMode() {
    return argumentsExecutionMode;
  }

  /**
   * Creates an instance of this node.
   *
   * @param schema the call-site arguments schema.
   * @param defaultsExecutionMode the default arguments handling mode for this call-site.
   * @param argumentsExecutionMode the lazy arguments handling mode for this call-site.
   * @return an instance of this node.
   */
  public static InvokeFunctionNode build(
      CallArgumentInfo[] schema,
      InvokeCallableNode.DefaultsExecutionMode defaultsExecutionMode,
      InvokeCallableNode.ArgumentsExecutionMode argumentsExecutionMode) {
    return InvokeFunctionNodeGen.create(schema, defaultsExecutionMode, argumentsExecutionMode);
  }
}
