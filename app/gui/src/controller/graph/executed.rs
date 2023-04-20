//! A module with Executed Graph Controller.
//!
//! This controller provides operations on a specific graph with some execution context - these
//! operations usually involves retrieving values on nodes: that's are i.e. operations on
//! visualisations, retrieving types on ports, etc.

use crate::prelude::*;

use crate::model::execution_context::ComponentGroup;
use crate::model::execution_context::ComputedValueInfo;
use crate::model::execution_context::ComputedValueInfoRegistry;
use crate::model::execution_context::LocalCall;
use crate::model::execution_context::QualifiedMethodPointer;
use crate::model::execution_context::Visualization;
use crate::model::execution_context::VisualizationId;
use crate::model::execution_context::VisualizationUpdateData;

use double_representation::name::QualifiedName;
use engine_protocol::language_server::MethodPointer;
use span_tree::generate::context::CalledMethodInfo;
use span_tree::generate::context::Context;


// ==============
// === Export ===
// ==============

pub use crate::controller::graph::Connection;
pub use crate::controller::graph::Connections;



// ==============
// === Errors ===
// ==============

#[allow(missing_docs)]
#[derive(Debug, Fail, Clone, Copy)]
#[fail(display = "The node {} has not been evaluated yet.", _0)]
pub struct NotEvaluatedYet(double_representation::node::Id);

#[allow(missing_docs)]
#[derive(Debug, Fail, Clone, Copy)]
#[fail(display = "The node {} does not resolve to a method call.", _0)]
pub struct NoResolvedMethod(double_representation::node::Id);

#[allow(missing_docs)]
#[derive(Debug, Fail, Clone, Copy)]
#[fail(display = "Operation is not permitted in read only mode")]
pub struct ReadOnly;


// ====================
// === Notification ===
// ====================

/// Notification about change in the executed graph.
///
/// It may pertain either the state of the graph itself or the notifications from the execution.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Notification {
    /// The notification passed from the graph controller.
    Graph(controller::graph::Notification),
    /// The notification from the execution context about the computed value information
    /// being updated.
    ComputedValueInfo(model::execution_context::ComputedValueExpressions),
    /// Notification emitted when the node has been entered.
    EnteredNode(LocalCall),
    /// Notification emitted when the node was step out.
    SteppedOutOfNode(double_representation::node::Id),
}



// ==============
// === Handle ===
// ==============

/// Handle providing executed graph controller interface.
#[derive(Clone, CloneRef, Debug)]
pub struct Handle {
    /// A handle to basic graph operations.
    graph:         Rc<RefCell<controller::Graph>>,
    /// Execution Context handle, its call stack top contains `graph`'s definition.
    execution_ctx: model::ExecutionContext,
    /// The handle to project controller is necessary, as entering nodes might need to switch
    /// modules, and only the project can provide their controllers.
    project:       model::Project,
    /// The publisher allowing sending notification to subscribed entities. Note that its outputs
    /// is merged with publishers from the stored graph and execution controllers.
    notifier:      notification::Publisher<Notification>,
}

impl Handle {
    /// Create handle for the executed graph that will be running the given method.
    #[profile(Task)]
    pub async fn new(project: model::Project, method: MethodPointer) -> FallibleResult<Self> {
        let graph = controller::Graph::new_method(&project, &method).await?;
        let execution = project.create_execution_context(method.clone()).await?;
        Ok(Self::new_internal(graph, project, execution))
    }

    /// Create handle for given graph and execution context.
    ///
    /// The given graph and execution context must be for the same method. Prefer using `new`,
    /// unless writing test code.
    ///
    /// This takes a (shared) ownership of execution context which will be shared between all copies
    /// of this handle. It is held through `Rc` because the registry in the project controller needs
    /// to store a weak handle to the execution context as well (to be able to properly route some
    /// notifications, like visualization updates).
    ///
    /// However, in a typical setup, this controller handle (and its copies) shall be the only
    /// strong references to the execution context and it is expected that it will be dropped after
    /// the last copy of this controller is dropped.
    /// Then the context when being dropped shall remove itself from the Language Server.
    pub fn new_internal(
        graph: controller::Graph,
        project: model::Project,
        execution_ctx: model::ExecutionContext,
    ) -> Self {
        let graph = Rc::new(RefCell::new(graph));
        let notifier = default();
        Handle { graph, execution_ctx, project, notifier }
    }

    /// See [`model::ExecutionContext::when_ready`].
    pub fn when_ready(&self) -> StaticBoxFuture<Option<()>> {
        self.execution_ctx.when_ready()
    }

    /// See [`model::ExecutionContext::attach_visualization`].
    pub async fn attach_visualization(
        &self,
        visualization: Visualization,
    ) -> FallibleResult<impl Stream<Item = VisualizationUpdateData>> {
        self.execution_ctx.attach_visualization(visualization).await
    }

    /// See [`model::ExecutionContext::modify_visualization`].
    pub fn modify_visualization(
        &self,
        id: VisualizationId,
        method_pointer: Option<QualifiedMethodPointer>,
        arguments: Option<Vec<String>>,
    ) -> BoxFuture<FallibleResult> {
        self.execution_ctx.modify_visualization(id, method_pointer, arguments)
    }

    /// See [`model::ExecutionContext::detach_visualization`].
    #[profile(Detail)]
    pub async fn detach_visualization(&self, id: VisualizationId) -> FallibleResult<Visualization> {
        self.execution_ctx.detach_visualization(id).await
    }

    /// See [`model::ExecutionContext::detach_all_visualizations`].
    #[profile(Detail)]
    pub async fn detach_all_visualizations(&self) -> Vec<FallibleResult<Visualization>> {
        self.execution_ctx.detach_all_visualizations().await
    }

    /// See [`model::ExecutionContext::expression_info_registry`].
    pub fn computed_value_info_registry(&self) -> &ComputedValueInfoRegistry {
        self.execution_ctx.computed_value_info_registry()
    }

    /// See [`model::ExecutionContext::component_groups`].
    pub fn component_groups(&self) -> Rc<Vec<ComponentGroup>> {
        self.execution_ctx.component_groups()
    }

    /// Subscribe to updates about changes in this executed graph.
    ///
    /// The stream of notification contains both notifications from the graph and from the execution
    /// context.
    pub fn subscribe(&self) -> impl Stream<Item = Notification> {
        let registry = self.execution_ctx.computed_value_info_registry();
        let value_stream = registry.subscribe().map(Notification::ComputedValueInfo).boxed_local();
        let graph_stream = self.graph().subscribe().map(Notification::Graph).boxed_local();
        let self_stream = self.notifier.subscribe().boxed_local();

        // Note: [Argument Names-related invalidations]
        let db_stream = self
            .project
            .suggestion_db()
            .subscribe()
            .map(|notification| match notification {
                model::suggestion_database::Notification::Updated =>
                    Notification::Graph(controller::graph::Notification::PortsUpdate),
            })
            .boxed_local();
        let update_stream = registry
            .subscribe()
            .map(|_| Notification::Graph(controller::graph::Notification::PortsUpdate))
            .boxed_local();

        let streams = vec![value_stream, graph_stream, self_stream, db_stream, update_stream];
        futures::stream::select_all(streams)
    }

    // Note [Argument Names-related invalidations]
    // ===========================================
    // Currently the shape of span tree depends on how method calls are recognized and resolved.
    // This involves lookups in the metadata, computed values registry and suggestion database.
    // As such, any of these will lead to emission of invalidation signal, as span tree shape
    // affects the connection identifiers.
    //
    // This is inefficient and should be addressed in the future.
    // See: https://github.com/enso-org/ide/issues/787


    /// Get a type of the given expression as soon as it is available.
    pub fn expression_type(&self, id: ast::Id) -> StaticBoxFuture<Option<ImString>> {
        let registry = self.execution_ctx.computed_value_info_registry();
        registry.clone_ref().get_type(id)
    }

    /// Enter node by given node ID and method pointer.
    ///
    /// This will cause pushing a new stack frame to the execution context and changing the graph
    /// controller to point to a new definition.
    ///
    /// ### Errors
    /// - Fails if method graph cannot be created (see `graph_for_method` documentation).
    /// - Fails if the project is in read-only mode.
    pub async fn enter_method_pointer(&self, local_call: &LocalCall) -> FallibleResult {
        if self.project.read_only() {
            Err(ReadOnly.into())
        } else {
            debug!("Entering node {}.", local_call.call);
            let method_ptr = &local_call.definition;
            let graph = controller::Graph::new_method(&self.project, method_ptr);
            let graph = graph.await?;
            self.execution_ctx.push(local_call.clone()).await?;
            debug!("Replacing graph with {graph:?}.");
            self.graph.replace(graph);
            debug!("Sending graph invalidation signal.");
            self.notifier.publish(Notification::EnteredNode(local_call.clone())).await;

            Ok(())
        }
    }

    /// Attempts to get the computed value of the specified node.
    ///
    /// Fails if there's no information e.g. because node value hasn't been yet computed by the
    /// engine.
    pub fn node_computed_value(
        &self,
        node: double_representation::node::Id,
    ) -> FallibleResult<Rc<ComputedValueInfo>> {
        let registry = self.execution_ctx.computed_value_info_registry();
        let node_info = registry.get(&node).ok_or(NotEvaluatedYet(node))?;
        Ok(node_info)
    }

    /// Leave the current node. Reverse of `enter_node`.
    ///
    /// ### Errors
    /// - Fails if this execution context is already at the stack's root or if the parent graph
    /// cannot be retrieved.
    /// - Fails if the project is in read-only mode.
    pub async fn exit_node(&self) -> FallibleResult {
        if self.project.read_only() {
            Err(ReadOnly.into())
        } else {
            let frame = self.execution_ctx.pop().await?;
            let method = self.execution_ctx.current_method();
            let graph = controller::Graph::new_method(&self.project, &method).await?;
            self.graph.replace(graph);
            self.notifier.publish(Notification::SteppedOutOfNode(frame.call)).await;
            Ok(())
        }
    }

    /// Interrupt the program execution.
    pub async fn interrupt(&self) -> FallibleResult {
        self.execution_ctx.interrupt().await?;
        Ok(())
    }

    /// Restart the program execution.
    ///
    /// ### Errors
    /// - Fails if the project is in read-only mode.
    pub async fn restart(&self) -> FallibleResult {
        if self.project.read_only() {
            Err(ReadOnly.into())
        } else {
            self.execution_ctx.restart().await?;
            Ok(())
        }
    }

    /// Get the current call stack frames.
    pub fn call_stack(&self) -> Vec<LocalCall> {
        self.execution_ctx.stack_items().collect()
    }

    /// Get the controller for the currently active graph.
    ///
    /// Note that the controller returned by this method may change as the nodes are stepped into.
    pub fn graph(&self) -> controller::Graph {
        self.graph.borrow().clone_ref()
    }

    /// Get suggestion database from currently active graph.
    pub fn suggestion_db(&self) -> Rc<model::SuggestionDatabase> {
        self.graph.borrow().suggestion_db.clone()
    }

    /// Get parser from currently active graph.
    pub fn parser(&self) -> parser::Parser {
        self.graph.borrow().parser.clone()
    }


    /// Get a full qualified name of the module in the [`graph`]. The name is obtained from the
    /// module's path and the `project` name.
    pub fn module_qualified_name(&self, project: &dyn model::project::API) -> QualifiedName {
        self.graph().module.path().qualified_module_name(project.qualified_name())
    }

    /// Returns information about all the connections between graph's nodes.
    ///
    /// In contrast with the `controller::Graph::connections` this uses information received from
    /// the LS to enrich the generated span trees with function signatures (arity and argument
    /// names).
    pub fn connections(&self) -> FallibleResult<Connections> {
        self.graph.borrow().connections(self)
    }

    /// Create connection in graph.
    ///
    /// ### Errors
    /// - Fails if the project is in read-only mode.
    pub fn connect(&self, connection: &Connection) -> FallibleResult {
        if self.project.read_only() {
            Err(ReadOnly.into())
        } else {
            self.graph.borrow().connect(connection, self)
        }
    }

    /// Remove the connections from the graph. Returns an updated edge destination endpoint for
    /// disconnected edge, in case it is still used as destination-only edge. When `None` is
    /// returned, no update is necessary.
    ///
    /// ### Errors
    /// - Fails if the project is in read-only mode.
    pub fn disconnect(&self, connection: &Connection) -> FallibleResult<Option<span_tree::Crumbs>> {
        if self.project.read_only() {
            Err(ReadOnly.into())
        } else {
            self.graph.borrow().disconnect(connection, self)
        }
    }
}


// === Span Tree Context ===

/// Span Tree generation context for a graph that does not know about execution.
/// Provides information based on computed value registry, using metadata as a fallback.
impl Context for Handle {
    fn call_info(&self, id: ast::Id, _name: Option<&str>) -> Option<CalledMethodInfo> {
        let info = self.computed_value_info_registry().get(&id)?;
        let method_call = info.method_call.as_ref()?;
        let suggestion_db = self.project.suggestion_db();
        let maybe_entry = suggestion_db.lookup_by_method_pointer(method_call).map(|e| {
            let invocation_info = e.invocation_info(&suggestion_db, &self.parser());
            invocation_info.with_called_on_type(false)
        });

        // When the entry was not resolved but the `defined_on_type` has a `.type` suffix,
        // try resolving it again with the suffix stripped. This indicates that a method was
        // called on type, either because it is a static method, or because it uses qualified
        // method syntax.
        maybe_entry.or_else(|| {
            let defined_on_type = method_call.defined_on_type.strip_suffix(".type")?.to_owned();
            let method_call = MethodPointer { defined_on_type, ..method_call.clone() };
            let entry = suggestion_db.lookup_by_method_pointer(&method_call)?;
            let invocation_info = entry.invocation_info(&suggestion_db, &self.parser());
            Some(invocation_info.with_called_on_type(true))
        })
    }
}

impl model::undo_redo::Aware for Handle {
    fn undo_redo_repository(&self) -> Rc<model::undo_redo::Repository> {
        self.graph.borrow().undo_redo_repository()
    }
}



// ============
// === Test ===
// ============

#[cfg(test)]
pub mod tests {
    use super::*;

    use crate::model::execution_context::ExpressionId;
    use crate::test;

    use crate::test::mock::Fixture;
    use controller::graph::SpanTree;
    use engine_protocol::language_server::types::test::value_update_with_type;
    use wasm_bindgen_test::wasm_bindgen_test;
    use wasm_bindgen_test::wasm_bindgen_test_configure;

    wasm_bindgen_test_configure!(run_in_browser);



    #[derive(Debug, Default)]
    pub struct MockData {
        pub graph:  controller::graph::tests::MockData,
        pub module: model::module::test::MockData,
        pub ctx:    model::execution_context::plain::test::MockData,
    }

    impl MockData {
        pub fn controller(&self) -> Handle {
            let parser = parser::Parser::new();
            let repository = Rc::new(model::undo_redo::Repository::new());
            let module = self.module.plain(&parser, repository);
            let method = self.graph.method();
            let mut project = model::project::MockAPI::new();
            let ctx = Rc::new(self.ctx.create());
            let proj_name = test::mock::data::project_qualified_name();
            model::project::test::expect_name(&mut project, test::mock::data::PROJECT_NAME);
            model::project::test::expect_qualified_name(&mut project, &proj_name);
            model::project::test::expect_parser(&mut project, &parser);
            model::project::test::expect_module(&mut project, module);
            model::project::test::expect_execution_ctx(&mut project, ctx);
            // Root ID is needed to generate module path used to get the module.
            model::project::test::expect_root_id(&mut project, crate::test::mock::data::ROOT_ID);
            // Both graph controllers need suggestion DB to provide context to their span trees.
            let suggestion_db = self.graph.suggestion_db();
            model::project::test::expect_suggestion_db(&mut project, suggestion_db);
            let project = Rc::new(project);
            Handle::new(project.clone_ref(), method).boxed_local().expect_ok()
        }
    }

    // Test that checks that value computed notification is properly relayed by the executed graph.
    #[wasm_bindgen_test]
    fn dispatching_value_computed_notification() {
        use crate::test::mock::Fixture;
        // Setup the controller.
        let mut fixture = crate::test::mock::Unified::new().fixture();
        let Fixture { executed_graph, execution, executor, .. } = &mut fixture;

        // Generate notification.
        let updated_id = ExpressionId::new_v4();
        let typename = crate::test::mock::data::TYPE_NAME;
        let update = value_update_with_type(updated_id, typename);

        // Notification not yet send.
        let registry = executed_graph.computed_value_info_registry();
        let mut notifications = executed_graph.subscribe().boxed_local();
        notifications.expect_pending();
        assert!(registry.get(&updated_id).is_none());

        // Sending notification.
        execution.computed_value_info_registry().apply_updates(vec![update]);
        executor.run_until_stalled();

        // Observing that notification was relayed.
        // Both computed values update and graph invalidation are expected, in any order.
        notifications.expect_both(
            |notification| match notification {
                Notification::ComputedValueInfo(updated_ids) => {
                    assert_eq!(updated_ids, &vec![updated_id]);
                    let typename_in_registry = registry.get(&updated_id).unwrap().typename.clone();
                    let expected_typename = Some(ImString::new(typename));
                    assert_eq!(typename_in_registry, expected_typename);
                    true
                }
                _ => false,
            },
            |notification| match notification {
                Notification::Graph(graph_notification) => {
                    assert_eq!(graph_notification, &controller::graph::Notification::PortsUpdate);
                    true
                }
                _ => false,
            },
        );

        notifications.expect_pending();
    }

    // Test that moving nodes is possible in read-only mode.
    #[wasm_bindgen_test]
    fn read_only_mode_does_not_restrict_moving_nodes() {
        use model::module::Position;

        let fixture = crate::test::mock::Unified::new().fixture();
        let Fixture { executed_graph, graph, .. } = fixture;

        let nodes = executed_graph.graph().nodes().unwrap();
        let node = &nodes[0];

        let pos1 = Position::new(500.0, 250.0);
        let pos2 = Position::new(300.0, 150.0);

        graph.set_node_position(node.id(), pos1).unwrap();
        assert_eq!(graph.node(node.id()).unwrap().position(), Some(pos1));
        graph.set_node_position(node.id(), pos2).unwrap();
        assert_eq!(graph.node(node.id()).unwrap().position(), Some(pos2));
    }

    // Test that certain actions are forbidden in read-only mode.
    #[wasm_bindgen_test]
    fn read_only_mode() {
        fn run(code: &str, f: impl FnOnce(&Handle)) {
            let mut data = crate::test::mock::Unified::new();
            data.set_code(code);
            let fixture = data.fixture();
            fixture.read_only.set(true);
            let Fixture { executed_graph, .. } = fixture;
            f(&executed_graph);
        }


        // === Editing the node. ===

        let default_code = r#"
main =
    foo = 2 * 2
"#;
        run(default_code, |executed| {
            let nodes = executed.graph().nodes().unwrap();
            let node = &nodes[0];
            assert!(executed.graph().set_expression(node.info.id(), "5 * 20").is_err());
        });


        // === Collapsing nodes. ===

        let code = r#"
main =
    foo = 2
    bar = foo + 6
    baz = 2 + foo + bar
    caz = baz / 2 * baz
"#;
        run(code, |executed| {
            let nodes = executed.graph().nodes().unwrap();
            // Collapse two middle nodes.
            let nodes_range = vec![nodes[1].id(), nodes[2].id()];
            assert!(executed.graph().collapse(nodes_range, "extracted").is_err());
        });


        // === Connecting nodes. ===

        let code = r#"
main =
    2 + 2
    5 * 5
"#;
        run(code, |executed| {
            let nodes = executed.graph().nodes().unwrap();
            let sum_node = &nodes[0];
            let product_node = &nodes[1];

            assert_eq!(sum_node.expression().to_string(), "2 + 2");
            assert_eq!(product_node.expression().to_string(), "5 * 5");

            let context = &span_tree::generate::context::Empty;
            let sum_tree = SpanTree::<()>::new(&sum_node.expression(), context).unwrap();
            let sum_input =
                sum_tree.root_ref().leaf_iter().find(|n| n.is_argument()).unwrap().crumbs;
            let connection = Connection {
                source:      controller::graph::Endpoint::new(product_node.id(), []),
                destination: controller::graph::Endpoint::new(sum_node.id(), sum_input),
            };

            assert!(executed.connect(&connection).is_err());
        });
    }
}
