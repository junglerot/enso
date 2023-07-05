//! Graph Controller.
//!
//! This controller provides access to a specific graph. It lives under a module controller, as
//! each graph belongs to some module.

use crate::model::traits::*;
use crate::prelude::*;

use crate::model::module::NodeMetadata;

use ast::crumbs::Located;
use ast::macros::DocumentationCommentInfo;
use double_representation::connection;
use double_representation::context_switch::ContextSwitchExpression;
use double_representation::definition;
use double_representation::definition::DefinitionProvider;
use double_representation::graph::GraphInfo;
use double_representation::identifier::generate_name;
use double_representation::import;
use double_representation::module;
use double_representation::name::project;
use double_representation::name::QualifiedName;
use double_representation::node;
use double_representation::node::MainLine;
use double_representation::node::NodeAst;
use double_representation::node::NodeInfo;
use double_representation::node::NodeLocation;
use engine_protocol::language_server;
use parser::Parser;
use span_tree::action::Action;
use span_tree::action::Actions;
use span_tree::generate::Context as SpanTreeContext;
use span_tree::PortId;
use span_tree::SpanTree;


// ==============
// === Export ===
// ==============

pub mod executed;
pub mod widget;

pub use double_representation::graph::Id;
pub use double_representation::graph::LocationHint;



// ==============
// === Errors ===
// ==============

/// Error raised when node with given ID was not found in the graph's body.
#[derive(Clone, Copy, Debug, Fail)]
#[fail(display = "Node with Id {} was not found.", _0)]
pub struct NodeNotFound(ast::Id);

/// Error raised when an endpoint with given Node ID and Port ID was not found in the graph's body.
#[derive(Clone, Copy, Debug, Fail)]
#[fail(display = "Port with ID {:?} was not found.", _0)]
pub struct EndpointNotFound(Endpoint);

/// Error raised when an attempt to set node's expression to a binding has been made.
#[derive(Clone, Debug, Fail)]
#[fail(display = "Illegal string `{}` given for node expression. It must not be a binding.", _0)]
pub struct BindingExpressionNotAllowed(String);

/// Expression AST cannot be used to produce a node. Means a bug in parser and id-giving code.
#[derive(Clone, Copy, Debug, Fail)]
#[fail(display = "Internal error: failed to create a new node.")]
pub struct FailedToCreateNode;

#[allow(missing_docs)]
#[derive(Clone, Copy, Debug, Fail)]
#[fail(display = "Source node {} has no pattern, so it cannot form connections.", node)]
pub struct NoPatternOnNode {
    pub node: node::Id,
}

#[allow(missing_docs)]
#[derive(Clone, Copy, Debug, Fail)]
#[fail(display = "AST node is missing ID.")]
pub struct MissingAstId;



// ====================
// === Notification ===
// ====================

/// A notification about changes of a specific graph in a module.
#[derive(Copy, Clone, Debug, Eq, PartialEq)]
pub enum Notification {
    /// The content should be fully reloaded.
    Invalidate,
    /// The graph node's ports need updating, e.g., types, names.
    PortsUpdate,
}



// ============
// === Node ===
// ============

/// Description of the node with all information available to the graph controller.
#[derive(Clone, Debug)]
pub struct Node {
    /// Information based on AST, from double_representation module.
    pub info:     NodeInfo,
    /// Information about this node stored in the module's metadata.
    pub metadata: Option<NodeMetadata>,
}

impl Node {
    /// Get the node's id.
    pub fn id(&self) -> double_representation::node::Id {
        self.main_line.id()
    }

    /// Get the node's position.
    pub fn position(&self) -> Option<model::module::Position> {
        self.metadata.as_ref().and_then(|m| m.position)
    }

    /// Check if node has a specific position set in metadata.
    pub fn has_position(&self) -> bool {
        self.metadata.as_ref().map_or(false, |m| m.position.is_some())
    }
}

impl Deref for Node {
    type Target = NodeInfo;

    fn deref(&self) -> &Self::Target {
        &self.info
    }
}



// ===================
// === NewNodeInfo ===
// ===================

/// Describes the node to be added.
#[derive(Clone, Debug)]
pub struct NewNodeInfo {
    /// Expression to be placed on the node
    pub expression:        String,
    /// Documentation comment to be attached before the node.
    pub doc_comment:       Option<String>,
    /// Visual node position in the graph scene.
    pub metadata:          Option<NodeMetadata>,
    /// ID to be given to the node.
    pub id:                Option<ast::Id>,
    /// Where line created by adding this node should appear.
    pub location_hint:     LocationHint,
    /// Introduce variable name for the node, making it into an assignment line.
    pub introduce_pattern: bool,
}

impl NewNodeInfo {
    /// New node with given expression added at the end of the graph's blocks.
    pub fn new_pushed_back(expression: impl Str) -> NewNodeInfo {
        NewNodeInfo {
            expression:        expression.into(),
            doc_comment:       None,
            metadata:          default(),
            id:                default(),
            location_hint:     LocationHint::End,
            introduce_pattern: default(),
        }
    }
}



// ===================
// === Connections ===
// ===================

/// Reference to the port (i.e. the span tree node).
pub type PortRef<'a> = span_tree::node::Ref<'a>;

// === Connection ===

/// Connection within the graph, described using a pair of ports at given nodes.
#[allow(missing_docs)]
#[derive(Clone, Copy, Debug, Default, Eq, Hash, PartialEq)]
pub struct Connection {
    pub source: Endpoint,
    pub target: Endpoint,
}

// === Endpoint

/// Connection endpoint - a port within a node.
#[allow(missing_docs)]
#[derive(Clone, Copy, Debug, Default, Eq, Hash, PartialEq)]
pub struct Endpoint {
    pub node: node::Id,
    pub port: PortId,
}

impl Endpoint {
    /// Create endpoint with empty `var_crumbs`.
    pub fn new(node: double_representation::node::Id, port: PortId) -> Self {
        Endpoint { node, port }
    }

    /// Create an endpoint pointing to whole node pattern or expression.
    pub fn root(node_id: node::Id) -> Self {
        Endpoint { node: node_id, port: PortId::Root }
    }

    /// Create a target endpoint from given node info, pointing at a port under given AST crumbs.
    /// Returns error if the crumbs do not point at any valid AST node.
    pub fn target_at(node: &Node, crumbs: impl ast::crumbs::IntoCrumbs) -> FallibleResult<Self> {
        let expression = node.info.expression();
        let port_ast = expression.get_traversing(&crumbs.into_crumbs())?;
        Ok(Endpoint { node: node.info.id(), port: PortId::Ast(port_ast.id.ok_or(MissingAstId)?) })
    }
}

// === NodeTrees ===

/// Stores node's span trees: one for inputs (expression) and optionally another one for outputs
/// (pattern).
#[derive(Clone, Debug, Default)]
pub struct NodeTrees {
    /// Describes node inputs, i.e. its expression.
    pub inputs:  SpanTree,
    /// Describes node outputs, i.e. its pattern. `None` if a node is not an assignment.
    pub outputs: Option<SpanTree>,
}

impl NodeTrees {
    #[allow(missing_docs)]
    pub fn new(node: &NodeInfo, context: &impl SpanTreeContext) -> Option<NodeTrees> {
        let inputs = SpanTree::new(&node.expression(), context).ok()?;
        let outputs = node.pattern().map(|pat| SpanTree::new(pat, context)).transpose().ok()?;
        Some(NodeTrees { inputs, outputs })
    }
}


// === Connections ===

/// Describes connections in the graph. For convenience also includes information about port
/// structure of the involved nodes.
#[derive(Clone, Debug, Default)]
pub struct Connections {
    /// Span trees for all nodes that have connections.
    pub trees:       HashMap<node::Id, NodeTrees>,
    /// The connections between nodes in the graph.
    pub connections: Vec<Connection>,
}

impl Connections {
    /// Describes a connection for given double representation graph.
    pub fn new(graph: &GraphInfo, context: &impl SpanTreeContext) -> Connections {
        let trees = graph
            .nodes()
            .iter()
            .filter_map(|node| Some((node.id(), NodeTrees::new(node, context)?)))
            .collect();
        let connections = graph.connections().iter().map(Self::convert_connection).collect();
        Connections { trees, connections }
    }

    /// Converts Endpoint from double representation to port-based representation.
    fn convert_endpoint(endpoint: &double_representation::connection::Endpoint) -> Endpoint {
        Endpoint { node: endpoint.node, port: PortId::Ast(endpoint.port.item) }
    }

    /// Converts Connection from double representation to port-based representation.
    fn convert_connection(
        connection: &double_representation::connection::Connection,
    ) -> Connection {
        Connection {
            source: Self::convert_endpoint(&connection.source),
            target: Self::convert_endpoint(&connection.target),
        }
    }

    /// Return all connections that involve the given node.
    pub fn with_node(&self, node: node::Id) -> impl Iterator<Item = Connection> {
        self.connections
            .iter()
            .filter(move |conn| conn.source.node == node || conn.target.node == node)
            .copied()
            .collect_vec()
            .into_iter()
    }
}



// =================
// === Utilities ===
// =================

/// Suggests a variable name for storing results of the given expression.
///
/// Name will try to express result of an infix operation (`sum` for `a+b`), kind of literal
/// (`number` for `5`) and target function name for prefix chain.
///
/// The generated name is not unique and might collide with already present identifiers.
pub fn name_for_ast(ast: &Ast) -> String {
    use ast::*;
    match ast.shape() {
        Shape::Tree(tree) if let Some(name) = &tree.descriptive_name => name.to_string(),
        Shape::Var(ident) => ident.name.clone(),
        Shape::Cons(ident) => ident.name.to_lowercase(),
        Shape::Number(_) => "number".into(),
        Shape::Opr(opr) => match opr.name.as_ref() {
            "+" => "sum",
            "*" => "product",
            "-" => "difference",
            "/" => "quotient",
            _ => "operator",
        }
        .into(),
        _ =>
            if let Some(infix) = ast::opr::GeneralizedInfix::try_new(ast) {
                name_for_ast(infix.opr.ast())
            } else if let Some(prefix) = ast::prefix::Chain::from_ast(ast) {
                name_for_ast(&prefix.func)
            } else {
                "var".into()
            },
    }
}



// ====================
// === EndpointInfo ===
// ====================

/// Helper structure for controller that describes known information about a connection's endpoint.
///
/// Also provides a number of utility functions for connection operations.
#[derive(Clone, Debug)]
pub struct EndpointInfo {
    /// Ast of the relevant node piece (expression or the pattern).
    pub ast:       Ast,
    /// Span tree for the relevant node side (outputs or inputs).
    ///
    /// TODO[PG]: Replace span-tree operations (set/erase) with direct AST operations using PortId.
    /// That way we can get rid of crumbs/span-trees completely.
    /// https://github.com/enso-org/enso/issues/6834
    pub span_tree: SpanTree,
    /// The endpoint port location within the span tree.
    /// TODO[PG]: Replace with PortId, see above.
    pub crumbs:    span_tree::Crumbs,
}

impl EndpointInfo {
    /// Construct information about endpoint. Ast must be the node's expression or pattern.
    pub fn new(
        endpoint: &Endpoint,
        ast: Ast,
        context: &impl SpanTreeContext,
    ) -> FallibleResult<EndpointInfo> {
        let span_tree = SpanTree::new(&ast, context)?;
        let node = span_tree
            .root_ref()
            .find_node(|n| n.port_id == Some(endpoint.port))
            .ok_or(EndpointNotFound(*endpoint))?;
        let crumbs = node.crumbs;
        Ok(EndpointInfo { ast, span_tree, crumbs })
    }

    /// Ast being the exact endpoint target. Might be more granular than a span tree port.
    pub fn target_ast(&self) -> FallibleResult<&Ast> {
        self.ast.get_traversing(&self.span_tree_node()?.ast_crumbs)
    }

    /// Obtains a reference to the span tree node of this endpoint.
    pub fn span_tree_node(&self) -> FallibleResult<span_tree::node::Ref> {
        self.span_tree.get_node(&self.crumbs)
    }

    /// Sets AST at the given port. Returns new root Ast.
    pub fn set(&self, ast_to_set: Ast) -> FallibleResult<Ast> {
        self.span_tree_node()?.set(&self.ast, ast_to_set)
    }

    /// Erases given port. Returns new root Ast.
    pub fn erase(&self) -> FallibleResult<Ast> {
        self.span_tree_node()?.erase(&self.ast)
    }
}



// ======================
// === RequiredImport ===
// ======================

/// An import that is needed for the picked suggestion.
#[derive(Debug, Clone)]
pub enum RequiredImport {
    /// A specific entry needs to be imported.
    Entry(Rc<enso_suggestion_database::Entry>),
    /// An entry with a specific name needs to be imported.
    Name(QualifiedName),
}

/// Whether the import is temporary or permanent. See [`Handle::add_required_imports`]
/// documentation.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ImportType {
    /// The import is used for suggestion preview and can be removed after switching to the other
    /// suggestion (or closing the component browser).
    Temporary,
    /// The import is permanent.
    Permanent,
}



// ==================
// === Controller ===
// ==================

/// Handle providing graph controller interface.
#[derive(Clone, CloneRef, Debug)]
#[allow(missing_docs)]
pub struct Handle {
    /// Identifier of the graph accessed through this controller.
    pub id:            Rc<Id>,
    pub module:        model::Module,
    pub suggestion_db: Rc<model::SuggestionDatabase>,
    project_name:      project::QualifiedName,
    parser:            Parser,
}

impl Handle {
    /// Creates a new controller. Does not check if id is valid.
    pub fn new_unchecked(
        module: model::Module,
        suggestion_db: Rc<model::SuggestionDatabase>,
        parser: Parser,
        id: Id,
        project_name: project::QualifiedName,
    ) -> Handle {
        let id = Rc::new(id);
        Handle { id, module, suggestion_db, parser, project_name }
    }

    /// Create a new graph controller. Given ID should uniquely identify a definition in the
    /// module. Fails if ID cannot be resolved.
    pub fn new(
        module: model::Module,
        suggestion_db: Rc<model::SuggestionDatabase>,
        parser: Parser,
        id: Id,
        project_name: project::QualifiedName,
    ) -> FallibleResult<Handle> {
        let ret = Self::new_unchecked(module, suggestion_db, parser, id, project_name);
        // Get and discard definition info, we are just making sure it can be obtained.
        let _ = ret.definition()?;
        Ok(ret)
    }

    /// Create a graph controller for the given method.
    ///
    /// Fails if the module is inaccessible or if the module does not contain the given method.
    #[profile(Task)]
    pub async fn new_method(
        project: &model::Project,
        method: &language_server::MethodPointer,
    ) -> FallibleResult<controller::Graph> {
        let method = method.clone();
        let root_id = project.project_content_root_id();
        let module_path = model::module::Path::from_method(root_id, &method)?;
        let module = project.module(module_path).await?;
        let definition = module.lookup_method(project.qualified_name(), &method)?;
        Self::new(
            module,
            project.suggestion_db(),
            project.parser(),
            definition,
            project.qualified_name(),
        )
    }

    /// Get the double representation description of the graph.
    pub fn graph_info(&self) -> FallibleResult<GraphInfo> {
        self.definition().map(|definition| GraphInfo::from_definition(definition.item))
    }

    /// Returns double rep information about all nodes in the graph.
    pub fn all_node_infos(&self) -> FallibleResult<Vec<NodeInfo>> {
        let graph = self.graph_info()?;
        Ok(graph.nodes())
    }

    /// Retrieves double rep information about node with given ID.
    pub fn node_info(&self, id: ast::Id) -> FallibleResult<NodeInfo> {
        let nodes = self.all_node_infos()?;
        let node = nodes.into_iter().find(|node_info| node_info.id() == id);
        node.ok_or_else(|| NodeNotFound(id).into())
    }

    /// Gets information about node with given id.
    ///
    /// Note that it is more efficient to use `get_nodes` to obtain all information at once,
    /// rather then repeatedly call this method.
    pub fn node(&self, id: ast::Id) -> FallibleResult<Node> {
        let info = self.node_info(id)?;
        let metadata = self.module.node_metadata(id).ok();
        Ok(Node { info, metadata })
    }

    /// Check whether the given ID corresponds to an existing node.
    pub fn node_exists(&self, id: ast::Id) -> bool {
        self.node_info(id).is_ok()
    }

    /// Returns information about all the nodes currently present in this graph.
    pub fn nodes(&self) -> FallibleResult<Vec<Node>> {
        let node_infos = self.all_node_infos()?;
        let mut nodes = Vec::new();
        for info in node_infos {
            let metadata = self.module.node_metadata(info.id()).ok();
            nodes.push(Node { info, metadata })
        }
        Ok(nodes)
    }

    /// Returns information about all the connections between graph's nodes.
    ///
    /// The context is used to create all span trees and possible affects the tree structure (so
    /// port ids depend on context).
    ///
    /// To obtain connection using only the locally available data, one may invoke this method
    /// passing `self` (i.e. the call target) as the context.
    pub fn connections(&self, context: &impl SpanTreeContext) -> FallibleResult<Connections> {
        let graph = self.graph_info()?;
        Ok(Connections::new(&graph, context))
    }

    /// Suggests a name for a variable that shall store the node value.
    ///
    /// Analyzes the expression, e.g. result for "a+b" shall be named "sum".
    /// The caller should make sure that obtained name won't collide with any symbol usage before
    /// actually introducing it. See `variable_name_for`.
    pub fn variable_name_base_for(node: &NodeAst) -> String {
        name_for_ast(&node.expression())
    }

    /// Identifiers introduced or referred to in the current graph's scope.
    ///
    /// Introducing identifier not included on this list should have no side-effects on the name
    /// resolution in the code in this graph.
    pub fn used_names(&self) -> FallibleResult<Vec<Located<String>>> {
        use double_representation::alias_analysis;
        let def = self.definition()?;
        let body = def.body();
        let usage = if matches!(body.shape(), ast::Shape::Block(_)) {
            alias_analysis::analyze_crumbable(body.item)
        } else if let Some(main_line) = MainLine::from_ast(&body) {
            alias_analysis::analyze_ast(main_line.ast())
        } else {
            // Generally speaking - impossible. But if there is no node in the definition
            // body, then there is nothing that could use any symbols, so nothing is used.
            default()
        };
        Ok(usage.all_identifiers())
    }

    /// Suggests a variable name for storing results of the given node. Name will get a number
    /// appended to avoid conflicts with other identifiers used in the graph.
    pub fn variable_name_for(&self, node: &NodeInfo) -> FallibleResult<ast::known::Var> {
        let base_name = Self::variable_name_base_for(node);
        let used_names = self.used_names()?;
        let used_names = used_names.iter().map(|name| name.item.as_str());
        let name = generate_name(base_name.as_str(), used_names)?.as_var()?;
        Ok(ast::known::Var::new(name, None))
    }

    /// Converts node to an assignment, where the whole value is bound to a single identifier.
    /// Modifies the node, discarding any previously set pattern.
    /// Returns the identifier with the node's expression value.
    pub fn introduce_name_on(&self, id: node::Id) -> FallibleResult<ast::known::Var> {
        let node = self.node(id)?;
        let name = self.variable_name_for(&node.info)?;
        self.set_pattern_on(id, name.ast().clone())?;
        Ok(name)
    }

    /// Set a new pattern on the node with given id. Discards any previously set pattern.
    pub fn set_pattern_on(&self, id: node::Id, pattern: Ast) -> FallibleResult {
        self.update_node(id, |mut node| {
            node.set_pattern(pattern);
            node
        })
    }

    /// Obtains information for new connection's target endpoint.
    pub fn target_info(
        &self,
        connection: &Connection,
        context: &impl SpanTreeContext,
    ) -> FallibleResult<EndpointInfo> {
        let target_node = self.node_info(connection.target.node)?;
        let target_node_ast = target_node.expression();
        EndpointInfo::new(&connection.target, target_node_ast, context)
    }

    /// Obtains information about new connection's source endpoint.
    pub fn source_info(
        &self,
        connection: &Connection,
        context: &impl SpanTreeContext,
    ) -> FallibleResult<EndpointInfo> {
        let mut source = connection.source;

        let use_whole_pattern = source.port == PortId::Root;
        let pattern = if use_whole_pattern {
            let pattern = self.introduce_pattern_if_missing(connection.source.node)?;
            let id = pattern.id.ok_or(EndpointNotFound(source))?;
            source.port = PortId::Ast(id);
            pattern
        } else {
            let source_node = self.node_info(connection.source.node)?;
            // For subports we would not have any idea what pattern to introduce. So we fail.
            source_node.pattern().ok_or(NoPatternOnNode { node: connection.source.node })?.clone()
        };
        EndpointInfo::new(&source, pattern, context)
    }

    /// If the node has no pattern, introduces a new pattern with a single variable name.
    pub fn introduce_pattern_if_missing(&self, node: node::Id) -> FallibleResult<Ast> {
        let source_node = self.node_info(node)?;
        if let Some(pat) = source_node.pattern() {
            Ok(pat.clone())
        } else {
            self.introduce_name_on(node).map(|var| var.into())
        }
    }

    /// Add a necessary unqualified import (`from module import name`) to the module, such that
    /// the provided fully qualified name is imported and available in the module.
    pub fn add_import_if_missing(&self, qualified_name: QualifiedName) -> FallibleResult {
        self.add_required_imports(
            iter::once(RequiredImport::Name(qualified_name)),
            ImportType::Permanent,
        )
    }

    /// Add imports to the module, but avoid their duplication. Temporary imports added by passing
    /// [`ImportType::Temporary`] can be removed by calling [`Self::clear_temporary_imports`].
    /// Temporary imports are used for suggestion preview and are removed when the previewed
    /// suggesiton is switched or cancelled.
    #[profile(Debug)]
    pub fn add_required_imports<'a>(
        &self,
        import_requirements: impl Iterator<Item = RequiredImport>,
        import_type: ImportType,
    ) -> FallibleResult {
        let module_path = self.module.path();
        let project_name = self.project_name.clone_ref();
        let module_qualified_name = module_path.qualified_module_name(project_name);
        let imports = import_requirements
            .filter_map(|requirement| {
                let defined_in = module_qualified_name.as_ref();
                let entry = match requirement {
                    RequiredImport::Entry(entry) => entry,
                    RequiredImport::Name(name) =>
                        self.suggestion_db.lookup_by_qualified_name(&name).ok()?.1,
                };
                Some(entry.required_imports(&self.suggestion_db, defined_in))
            })
            .flatten();
        let mut module = double_representation::module::Info { ast: self.module.ast() };
        for entry_import in imports {
            let already_imported =
                module.iter_imports().any(|existing| entry_import.covered_by(&existing));
            let import: import::Info = entry_import.into();
            let import_id = import.id();
            let already_inserted = module.contains_import(import_id);
            let need_to_insert = !already_imported;
            let old_import_became_permanent =
                import_type == ImportType::Permanent && already_inserted;
            let need_to_update_md = need_to_insert || old_import_became_permanent;
            if need_to_insert {
                module.add_import(&self.parser, import);
            }
            if need_to_update_md {
                self.module.with_import_metadata(
                    import_id,
                    Box::new(|import_metadata| {
                        import_metadata.is_temporary = import_type == ImportType::Temporary;
                    }),
                )?;
            }
        }
        self.module.update_ast(module.ast)
    }

    /// Remove temporary imports added by [`Self::add_required_imports`].
    pub fn clear_temporary_imports(&self) {
        let mut module = double_representation::module::Info { ast: self.module.ast() };
        let import_metadata = self.module.all_import_metadata();
        let metadata_to_remove = import_metadata
            .into_iter()
            .filter_map(|(id, import_metadata)| {
                import_metadata.is_temporary.then(|| {
                    if let Err(e) = module.remove_import_by_id(id) {
                        warn!("Failed to remove import because of: {e:?}");
                    }
                    id
                })
            })
            .collect_vec();
        if let Err(e) = self.module.update_ast(module.ast) {
            warn!("Failed to update module ast when removing imports because of: {e:?}");
        }
        for id in metadata_to_remove {
            if let Err(e) = self.module.remove_import_metadata(id) {
                warn!("Failed to remove import metadata for import id {id} because of: {e:?}");
            }
        }
    }

    /// Reorders lines so the former node is placed after the latter. Does nothing, if the latter
    /// node is already placed after former.
    ///
    /// Additionally all dependent node the `node_to_be_after` being before its new line are also
    /// moved after it, keeping their order.
    pub fn place_node_and_dependencies_lines_after(
        &self,
        node_to_be_before: node::Id,
        node_to_be_after: node::Id,
    ) -> FallibleResult {
        let graph = self.graph_info()?;
        let definition_ast = &graph.body().item;
        let dependent_nodes = connection::dependent_nodes_in_def(definition_ast, node_to_be_after);

        let node_to_be_before = graph.locate_node(node_to_be_before)?;
        let node_to_be_after = graph.locate_node(node_to_be_after)?;
        let dependent_nodes = dependent_nodes
            .iter()
            .map(|id| graph.locate_node(*id))
            .collect::<Result<Vec<_>, _>>()?;

        if node_to_be_after.index < node_to_be_before.index {
            let should_be_at_end = |line: &ast::BlockLine<Option<Ast>>| {
                let mut itr = std::iter::once(&node_to_be_after).chain(&dependent_nodes);
                if let Some(line_ast) = &line.elem {
                    itr.any(|node| node.node.contains_line(line_ast))
                } else {
                    false
                }
            };

            let mut lines = graph.block_lines();
            let range = NodeLocation::range(node_to_be_after.index, node_to_be_before.index);
            lines[range].sort_by_key(should_be_at_end);
            self.update_definition_ast(|mut def| {
                def.set_block_lines(lines)?;
                Ok(def)
            })?;
        }
        Ok(())
    }

    /// Create connection in graph.
    pub fn connect(
        &self,
        connection: &Connection,
        context: &impl SpanTreeContext,
    ) -> FallibleResult {
        let _transaction_guard = self.get_or_open_transaction("Connect");
        let source_info = self.source_info(connection, context)?;
        let target_info = self.target_info(connection, context)?;
        let source_identifier = source_info.target_ast()?.clone();
        let updated_target_node_expr = target_info.set(source_identifier.with_new_id())?;
        self.set_expression_ast(connection.target.node, updated_target_node_expr)?;

        // Reorder node lines, so the connection target is after connection source.
        let source_node = connection.source.node;
        let target_node = connection.target.node;
        self.place_node_and_dependencies_lines_after(source_node, target_node)
    }

    /// Remove the connections from the graph.
    pub fn disconnect(
        &self,
        connection: &Connection,
        context: &impl SpanTreeContext,
    ) -> FallibleResult {
        let _transaction_guard = self.get_or_open_transaction("Disconnect");
        let info = self.target_info(connection, context)?;
        let port = info.span_tree_node()?;
        let updated_expression = if port.is_action_available(Action::Erase) {
            info.erase()
        } else {
            info.set(Ast::blank())
        }?;

        self.set_expression_ast(connection.target.node, updated_expression)?;
        Ok(())
    }

    /// Obtain the definition information for this graph from the module's AST.
    pub fn definition(&self) -> FallibleResult<definition::ChildDefinition> {
        let module_ast = self.module.ast();
        module::locate(&module_ast, &self.id)
    }

    /// The span of the definition of this graph in the module's AST.
    pub fn definition_span(&self) -> FallibleResult<enso_text::Range<enso_text::Byte>> {
        let def = self.definition()?;
        self.module.ast().range_of_descendant_at(&def.crumbs)
    }

    /// The location of the last byte of the definition of this graph in the module's AST.
    pub fn definition_end_location(&self) -> FallibleResult<enso_text::Location<enso_text::Byte>> {
        let module_ast = self.module.ast();
        let module_repr: enso_text::Rope = module_ast.repr().into();
        let def_span = self.definition_span()?;
        Ok(module_repr.offset_to_location_snapped(def_span.end))
    }

    /// Updates the AST of the definition of this graph.
    #[profile(Debug)]
    pub fn update_definition_ast<F>(&self, f: F) -> FallibleResult
    where F: FnOnce(definition::DefinitionInfo) -> FallibleResult<definition::DefinitionInfo> {
        let ast_so_far = self.module.ast();
        let definition = self.definition()?;
        let new_definition = f(definition.item)?;
        info!("Applying graph changes onto definition");
        let new_ast = new_definition.ast.into();
        let new_module = ast_so_far.set_traversing(&definition.crumbs, new_ast)?;
        self.module.update_ast(new_module)
    }

    /// Parses given text as a node expression.
    pub fn parse_node_expression(&self, expression_text: impl Str) -> FallibleResult<Ast> {
        let node_ast = self.parser.parse_line_ast(expression_text.as_ref())?;
        if ast::opr::is_assignment(&node_ast) {
            Err(BindingExpressionNotAllowed(expression_text.into()).into())
        } else {
            Ok(node_ast)
        }
    }

    /// Creates a proper description of a documentation comment in the context of this graph.
    pub fn documentation_comment_from_pretty_text(
        &self,
        pretty_text: &str,
    ) -> Option<DocumentationCommentInfo> {
        let indent = self.definition().ok()?.indent();
        let doc_repr = DocumentationCommentInfo::text_to_repr(indent, pretty_text);
        let doc_line = self.parser.parse_line(doc_repr).ok()?;
        DocumentationCommentInfo::new(&doc_line.as_ref(), indent)
    }

    /// Adds a new node to the graph and returns information about created node.
    pub fn add_node(&self, node: NewNodeInfo) -> FallibleResult<ast::Id> {
        info!("Adding node with expression `{}`", node.expression);
        let expression_ast = self.parse_node_expression(&node.expression)?;
        let main_line = MainLine::from_ast(&expression_ast).ok_or(FailedToCreateNode)?;
        let documentation = node
            .doc_comment
            .as_ref()
            .and_then(|pretty_text| self.documentation_comment_from_pretty_text(pretty_text));

        let mut node_info = NodeInfo { documentation, main_line };
        if let Some(desired_id) = node.id {
            node_info.set_id(desired_id)
        }
        if node.introduce_pattern && node_info.pattern().is_none() {
            let var = self.variable_name_for(&node_info)?;
            node_info.set_pattern(var.into());
        }

        self.update_definition_ast(|definition| {
            let mut graph = GraphInfo::from_definition(definition);
            graph.add_node(&node_info, node.location_hint)?;
            Ok(graph.source)
        })?;

        if let Some(initial_metadata) = node.metadata {
            self.module.set_node_metadata(node_info.id(), initial_metadata)?;
        }

        Ok(node_info.id())
    }

    /// Removes the node with given Id.
    pub fn remove_node(&self, id: ast::Id) -> FallibleResult {
        info!("Removing node {id}");
        self.update_definition_ast(|definition| {
            let mut graph = GraphInfo::from_definition(definition);
            graph.remove_node(id)?;
            Ok(graph.source)
        })?;

        // It's fine if there were no metadata.
        let _ = self.module.remove_node_metadata(id);
        Ok(())
    }

    /// Sets the given's node expression.
    #[profile(Debug)]
    pub fn set_expression(&self, id: ast::Id, expression_text: impl Str) -> FallibleResult {
        info!("Setting node {id} expression to `{}`", expression_text.as_ref());
        let new_expression_ast = self.parse_node_expression(expression_text)?;
        self.set_expression_ast(id, new_expression_ast)
    }

    /// Sets the given's node expression.
    #[profile(Debug)]
    pub fn set_expression_ast(&self, id: ast::Id, expression: Ast) -> FallibleResult {
        info!("Setting node {id} expression to `{}`", expression.repr());
        self.update_definition_ast(|definition| {
            let mut graph = GraphInfo::from_definition(definition);
            graph.edit_node(id, expression)?;
            Ok(graph.source)
        })
    }

    /// Updates the given node's expression by rewriting a part of it, as specified by span crumbs.
    ///
    /// This will not modify AST IDs of any part of the expression that is not selected by the
    /// crumbs.
    #[profile(Debug)]
    pub fn set_expression_span(
        &self,
        id: ast::Id,
        crumbs: &span_tree::Crumbs,
        expression_text: impl Str,
        context: &impl SpanTreeContext,
    ) -> FallibleResult {
        info!("Setting Expression Span {crumbs:?} node {id}  to \"{}\".", expression_text.as_ref());
        let node_ast = self.node_info(id)?.expression();
        let node_span_tree: SpanTree = SpanTree::new(&node_ast, context)?;
        let port = node_span_tree.get_node(crumbs)?;
        let new_node_ast = if expression_text.as_ref().is_empty() {
            if port.is_action_available(Action::Erase) {
                port.erase(&node_ast)?
            } else {
                port.set(&node_ast, Ast::blank())?
            }
        } else {
            let new_expression_ast = self.parse_node_expression(expression_text)?;
            port.set(&node_ast, new_expression_ast)?
        };
        self.set_expression_ast(id, new_node_ast)
    }

    /// Set node's position.
    pub fn set_node_position(
        &self,
        node_id: ast::Id,
        position: impl Into<model::module::Position>,
    ) -> FallibleResult {
        let _transaction_guard = self.get_or_open_transaction("Set node position");
        self.module.with_node_metadata(
            node_id,
            Box::new(|md| {
                md.position = Some(position.into());
            }),
        )
    }

    /// Mark the node as skipped by prepending "SKIP" macro call to its AST.
    pub fn set_node_action_skip(&self, node_id: ast::Id, skip: bool) -> FallibleResult {
        self.update_node(node_id, |mut node| {
            node.set_skip(skip);
            node
        })?;
        Ok(())
    }

    /// Mark the node as frozen by prepending "FREEZE" macro call to its AST.
    pub fn set_node_action_freeze(&self, node_id: ast::Id, freeze: bool) -> FallibleResult {
        self.update_node(node_id, |mut node| {
            node.set_freeze(freeze);
            node
        })?;
        Ok(())
    }

    /// Sets or clears the context switch expression for the specified node.
    pub fn set_node_context_switch(
        &self,
        node_id: ast::Id,
        expr: Option<ContextSwitchExpression>,
    ) -> FallibleResult {
        self.update_node(node_id, |mut node| {
            if let Some(expr) = expr {
                node.set_context_switch(expr);
            } else {
                node.clear_context_switch_expression();
            }
            node
        })?;
        Ok(())
    }

    /// Collapses the selected nodes.
    ///
    /// Lines corresponding to the selection will be extracted to a new method definition.
    #[profile(Task)]
    pub fn collapse(
        &self,
        nodes: impl IntoIterator<Item = node::Id>,
        new_method_name_base: &str,
    ) -> FallibleResult<node::Id> {
        let _transaction_guard = self.get_or_open_transaction("Collapse nodes");
        analytics::remote_log_event("graph::collapse");
        use double_representation::refactorings::collapse::collapse;
        use double_representation::refactorings::collapse::Collapsed;
        let nodes = nodes.into_iter().map(|id| self.node(id)).collect::<Result<Vec<_>, _>>()?;
        info!("Collapsing {nodes:?}.");
        let collapsed_positions = nodes
            .iter()
            .filter_map(|node| node.metadata.as_ref().and_then(|metadata| metadata.position));
        let ast = self.module.ast();
        let mut module = module::Info { ast };
        let introduced_name = module.generate_name(new_method_name_base)?;
        let node_ids = nodes.iter().map(|node| node.info.id());
        let graph = self.graph_info()?;
        let module_name = self.module.name().to_owned();
        let collapsed = collapse(&graph, node_ids, introduced_name, &self.parser, module_name)?;
        let Collapsed { new_method, updated_definition, collapsed_node } = collapsed;

        let graph = self.graph_info()?;
        let my_name = graph.source.name.item;
        module.add_method(new_method, module::Placement::Before(my_name), &self.parser)?;
        module.update_definition(&self.id, |_| Ok(updated_definition))?;
        self.module.update_ast(module.ast)?;
        let position = Some(model::module::Position::mean(collapsed_positions));
        let metadata = NodeMetadata { position, ..default() };
        self.module.set_node_metadata(collapsed_node, metadata)?;
        Ok(collapsed_node)
    }

    /// Updates the given node in the definition.
    ///
    /// The function `F` is called with the information with the state of the node so far and
    pub fn update_node<F>(&self, id: ast::Id, f: F) -> FallibleResult
    where F: FnOnce(NodeInfo) -> NodeInfo {
        self.update_definition_ast(|definition| {
            let mut graph = GraphInfo::from_definition(definition);
            graph.update_node(id, |node| {
                let new_node = f(node);
                info!("Setting node {id} line to `{}`", new_node.repr());
                Some(new_node)
            })?;
            Ok(graph.source)
        })?;
        Ok(())
    }

    /// Subscribe to updates about changes in this graph.
    pub fn subscribe(&self) -> impl Stream<Item = Notification> {
        let module_sub = self.module.subscribe().map(|notification| match notification.kind {
            model::module::NotificationKind::Invalidate
            | model::module::NotificationKind::CodeChanged { .. }
            | model::module::NotificationKind::MetadataChanged
            | model::module::NotificationKind::Reloaded => Notification::Invalidate,
        });
        let db_sub = self.suggestion_db.subscribe().map(|notification| match notification {
            model::suggestion_database::Notification::Updated => Notification::PortsUpdate,
        });
        futures::stream::select(module_sub, db_sub)
    }
}

impl model::undo_redo::Aware for Handle {
    fn undo_redo_repository(&self) -> Rc<model::undo_redo::Repository> {
        self.module.undo_redo_repository()
    }
}



// ============
// === Test ===
// ============

#[cfg(test)]
pub mod tests {
    use super::*;

    use crate::executor::test_utils::TestWithLocalPoolExecutor;
    use crate::model::module::Position;
    use crate::model::module::TextChange;
    use crate::model::suggestion_database;
    use crate::test::mock::data;

    use ast::crumbs::*;
    use ast::test_utils::expect_shape;
    use double_representation::name::project;
    use engine_protocol::language_server::MethodPointer;
    use enso_text::index::*;
    use parser::Parser;
    use span_tree::generate::context::CalledMethodInfo;
    use span_tree::generate::MockContext;
    use span_tree::PortId;

    /// Returns information about all the connections between graph's nodes.
    pub fn connections(graph: &Handle) -> FallibleResult<Connections> {
        graph.connections(&span_tree::generate::context::Empty)
    }

    /// All the data needed to set up and run the graph controller in mock environment.
    #[derive(Clone, Debug)]
    pub struct MockData {
        pub module_path:  model::module::Path,
        pub graph_id:     Id,
        pub project_name: project::QualifiedName,
        pub code:         String,
        pub id_map:       ast::IdMap,
        pub suggestions:  HashMap<suggestion_database::entry::Id, suggestion_database::Entry>,
    }

    impl MockData {
        /// Creates a mock data with the `main` function being an inline definition with a single
        /// node.
        pub fn new() -> Self {
            MockData {
                module_path:  data::module_path(),
                graph_id:     data::graph_id(),
                project_name: data::project_qualified_name(),
                code:         data::CODE.to_owned(),
                id_map:       default(),
                suggestions:  default(),
            }
        }

        /// Creates a mock data with the main function being an inline definition.
        ///
        /// The single node's expression is taken as the argument.
        pub fn new_inline(main_body: impl AsRef<str>) -> Self {
            let definition_name = crate::test::mock::data::DEFINITION_NAME;
            MockData {
                code: format!("{} = {}", definition_name, main_body.as_ref()),
                ..Self::new()
            }
        }

        pub fn module_data(&self) -> model::module::test::MockData {
            model::module::test::MockData {
                code: self.code.clone(),
                path: self.module_path.clone(),
                id_map: self.id_map.clone(),
                ..default()
            }
        }

        /// Create a graph controller from the current mock data.
        pub fn graph(&self) -> Handle {
            let parser = Parser::new();
            let urm = Rc::new(model::undo_redo::Repository::new());
            let module = self.module_data().plain(&parser, urm);
            let id = self.graph_id.clone();
            let db = self.suggestion_db();
            let project_name = self.project_name.clone_ref();
            Handle::new(module, db, parser, id, project_name).unwrap()
        }

        pub fn method(&self) -> MethodPointer {
            self.module_path.method_pointer(self.project_name.clone(), self.graph_id.to_string())
        }

        pub fn suggestion_db(&self) -> Rc<model::SuggestionDatabase> {
            use model::suggestion_database::SuggestionDatabase;
            let entries = self.suggestions.iter();
            Rc::new(SuggestionDatabase::new_from_entries(entries))
        }
    }

    impl Default for MockData {
        fn default() -> Self {
            Self::new()
        }
    }

    #[derive(Debug, Deref, DerefMut)]
    pub struct Fixture {
        pub data:  MockData,
        #[deref]
        #[deref_mut]
        pub inner: TestWithLocalPoolExecutor,
    }

    impl Fixture {
        pub fn set_up() -> Fixture {
            let data = MockData::new();
            let inner = TestWithLocalPoolExecutor::set_up();
            Self { data, inner }
        }

        pub fn run<Test, Fut>(&mut self, test: Test)
        where
            Test: FnOnce(Handle) -> Fut + 'static,
            Fut: Future<Output = ()>, {
            let graph = self.data.graph();
            self.run_task(async move { test(graph).await })
        }
    }

    #[test]
    fn node_operations() {
        Fixture::set_up().run(|graph| async move {
            let uid = graph.all_node_infos().unwrap()[0].id();
            let pos = Position { vector: Vector2::new(0.0, 0.0) };
            let updater = Box::new(|data: &mut NodeMetadata| data.position = Some(pos));
            graph.module.with_node_metadata(uid, updater).unwrap();
            assert_eq!(graph.module.node_metadata(uid).unwrap().position, Some(pos));
        })
    }

    #[test]
    fn graph_controller_notification_relay() {
        Fixture::set_up().run(|graph| async move {
            let mut sub = graph.subscribe();
            let change = TextChange { range: (12.byte()..12.byte()).into(), text: "2".into() };
            graph.module.apply_code_change(change, &graph.parser, default()).unwrap();
            assert_eq!(Some(Notification::Invalidate), sub.next().await);
        });
    }

    #[test]
    fn suggestion_db_updates_graph_values() {
        Fixture::set_up().run(|graph| async move {
            let mut sub = graph.subscribe();
            let update = language_server::types::SuggestionDatabaseUpdatesEvent {
                updates:         vec![],
                current_version: default(),
            };
            graph.suggestion_db.apply_update_event(update);
            assert_eq!(Some(Notification::PortsUpdate), sub.next().await);
        });
    }

    #[test]
    fn graph_controller_inline_definition() {
        let mut test = Fixture::set_up();
        const EXPRESSION: &str = "2+2";
        test.data.code = format!("main = {EXPRESSION}");
        test.run(|graph| async move {
            let nodes = graph.nodes().unwrap();
            let (node,) = nodes.expect_tuple();
            assert_eq!(node.info.expression().repr(), EXPRESSION);
            let id = node.info.id();
            let node = graph.node(id).unwrap();
            assert_eq!(node.info.expression().repr(), EXPRESSION);
        })
    }

    #[test]
    fn graph_controller_block_definition() {
        let mut test = Fixture::set_up();
        test.data.code = r"
main =
    foo = 2
    print foo"
            .to_string();
        test.run(|graph| async move {
            let nodes = graph.nodes().unwrap();
            let (node1, node2) = nodes.expect_tuple();
            assert_eq!(node1.info.expression().repr(), "2");
            assert_eq!(node2.info.expression().repr(), "print foo");
        })
    }

    #[test]
    fn graph_controller_parse_expression() {
        let mut test = Fixture::set_up();
        test.run(|graph| async move {
            let foo = graph.parse_node_expression("foo").unwrap();
            assert_eq!(expect_shape::<ast::Var>(&foo), &ast::Var { name: "foo".into() });

            assert!(graph.parse_node_expression("Vec").is_ok());
            assert!(graph.parse_node_expression("5").is_ok());
            assert!(graph.parse_node_expression("5+5").is_ok());
            assert!(graph.parse_node_expression("a+5").is_ok());
            assert!(graph.parse_node_expression("a=5").is_err());
        })
    }

    #[test]
    fn graph_controller_used_names_in_inline_def() {
        let mut test = Fixture::set_up();
        test.data.code = "main = foo".into();
        test.run(|graph| async move {
            let expected_name = Located::new_root("foo".to_owned());
            let used_names = graph.used_names().unwrap();
            assert_eq!(used_names, vec![expected_name]);
        })
    }

    #[test]
    fn graph_controller_nested_definition() {
        let mut test = Fixture::set_up();
        test.data.code = r"main =
    foo a =
        bar b = 5
    print foo"
            .into();
        test.data.graph_id = definition::Id::new_plain_names(["main", "foo"]);
        test.run(|graph| async move {
            let expression = "new_node";
            graph.add_node(NewNodeInfo::new_pushed_back(expression)).unwrap();
            let expected_program = r"main =
    foo a =
        bar b = 5
        new_node
    print foo";
            model::module::test::expect_code(&*graph.module, expected_program);
        })
    }

    #[test]
    fn collapsing_nodes_avoids_name_conflicts() {
        // Checks that generated name avoid collision with other methods defined in the module
        // and with symbols used that could be shadowed by the extracted method's name.
        let mut test = Fixture::set_up();
        let code = r"
func2 = 454

main =
    a = 10
    b = 20
    c = a + b
    d = c + d
    a + func1";

        let expected_code = "
func2 = 454

func3 a =
    b = 20
    c = a + b
    d = c + d

main =
    a = 10
    Mock_Module.func3 a
    a + func1";

        test.data.code = code.to_owned();
        test.run(move |graph| async move {
            let nodes = graph.nodes().unwrap();
            let selected_nodes = nodes[1..4].iter().map(|node| node.info.id());
            graph.collapse(selected_nodes, "func").unwrap();
            model::module::test::expect_code(&*graph.module, expected_code);
        })
    }

    #[test]
    fn collapsing_nodes() {
        let mut test = Fixture::set_up();
        let code = r"
main =
    a = 10
    b = 20
    a + c";

        let expected_code = "
func1 =
    a = 10
    b = 20
    a

main =
    a = Mock_Module.func1
    a + c";

        test.data.code = code.to_owned();
        test.run(move |graph| async move {
            let nodes = graph.nodes().unwrap();
            assert_eq!(nodes.len(), 3);
            graph
                .module
                .set_node_metadata(nodes[0].info.id(), NodeMetadata {
                    position: Some(Position::new(100.0, 200.0)),
                    ..default()
                })
                .unwrap();
            graph
                .module
                .set_node_metadata(nodes[1].info.id(), NodeMetadata {
                    position: Some(Position::new(150.0, 300.0)),
                    ..default()
                })
                .unwrap();

            let selected_nodes = nodes[0..2].iter().map(|node| node.info.id());
            let collapsed_node = graph.collapse(selected_nodes, "func").unwrap();
            model::module::test::expect_code(&*graph.module, expected_code);

            let nodes_after = graph.nodes().unwrap();
            assert_eq!(nodes_after.len(), 2);
            let collapsed_node_info = graph.node(collapsed_node).unwrap();
            let collapsed_node_pos = collapsed_node_info.metadata.and_then(|m| m.position);
            assert_eq!(collapsed_node_pos, Some(Position::new(125.0, 250.0)));
        })
    }

    #[test]
    fn graph_controller_doubly_nested_definition() {
        // Tests editing nested definition that requires transforming inline expression into
        // into a new block.
        let mut test = Fixture::set_up();
        // Not using multi-line raw string literals, as we don't want IntelliJ to automatically
        // strip the trailing whitespace in the lines.
        test.data.code = "main =\n    foo a =\n        bar b = 5\n    print foo".into();
        test.data.graph_id = definition::Id::new_plain_names(["main", "foo", "bar"]);
        test.run(|graph| async move {
            let expression = "new_node";
            graph.add_node(NewNodeInfo::new_pushed_back(expression)).unwrap();
            let expected_program = "main =\n    foo a =\n        bar b = \
                                    \n            5\n            new_node\n    print foo";

            model::module::test::expect_code(&*graph.module, expected_program);
        })
    }

    #[test]
    fn graph_controller_node_operations_node() {
        let mut test = Fixture::set_up();
        const PROGRAM: &str = r"
main =
    foo = 2
    print foo";
        test.data.code = PROGRAM.into();
        test.run(|graph| async move {
            // === Initial nodes ===
            let nodes = graph.nodes().unwrap();
            for node in &nodes {
                debug!("{}", node.repr())
            }
            let (node1, node2) = nodes.expect_tuple();
            assert_eq!(node1.info.expression().repr(), "2");
            assert_eq!(node2.info.expression().repr(), "print foo");


            // === Add node ===
            let id = ast::Id::new_v4();
            let position = Some(model::module::Position::new(10.0, 20.0));
            let metadata = NodeMetadata { position, ..default() };
            let info = NewNodeInfo {
                expression:        "a+b".into(),
                doc_comment:       None,
                metadata:          Some(metadata),
                id:                Some(id),
                location_hint:     LocationHint::End,
                introduce_pattern: false,
            };
            graph.add_node(info.clone()).unwrap();
            let expected_program = r"
main =
    foo = 2
    print foo
    a+b";

            model::module::test::expect_code(&*graph.module, expected_program);
            let nodes = graph.nodes().unwrap();
            let (_, _, node3) = nodes.expect_tuple();
            assert_eq!(node3.info.id(), id);
            assert_eq!(node3.info.expression().repr(), "a+b");
            let pos = node3.metadata.unwrap().position;
            assert_eq!(pos, position);
            assert!(graph.module.node_metadata(id).is_ok());


            // === Edit node ===
            graph.set_expression(id, "bar baz").unwrap();
            let (_, _, node3) = graph.nodes().unwrap().expect_tuple();
            assert_eq!(node3.info.id(), id);
            assert_eq!(node3.info.expression().repr(), "bar baz");
            assert_eq!(node3.metadata.unwrap().position, position);


            // === Remove node ===
            graph.remove_node(node3.info.id()).unwrap();
            let nodes = graph.nodes().unwrap();
            let (node1, node2) = nodes.expect_tuple();
            assert_eq!(node1.info.expression().repr(), "2");
            assert_eq!(node2.info.expression().repr(), "print foo");
            assert!(graph.module.node_metadata(id).is_err());

            model::module::test::expect_code(&*graph.module, PROGRAM);


            // === Test adding node with automatically generated pattern ===
            let info_w_pattern = NewNodeInfo { introduce_pattern: true, ..info };
            graph.add_node(info_w_pattern).unwrap();
            let expected_program = r"
main =
    foo = 2
    print foo
    sum1 = a+b";
            model::module::test::expect_code(&*graph.module, expected_program);
        })
    }

    #[test]
    fn graph_controller_connections_listing() {
        let mut test = Fixture::set_up();
        const PROGRAM: &str = r"
main =
    [x,y] = get_pos
    print x
    z = print $ foo y
    print z
    foo
        print z";
        test.data.code = PROGRAM.into();
        let id_map = &mut test.data.id_map;
        let x_src = id_map.generate(13..14);
        let y_src = id_map.generate(15..16);
        let z_src = id_map.generate(44..45);
        let x_dst = id_map.generate(38..39);
        let y_dst = id_map.generate(60..61);
        let z_dst1 = id_map.generate(72..73);
        let z_dst2 = id_map.generate(96..97);

        test.run(move |graph| async move {
            let connections = connections(&graph).unwrap();


            let (node0, node1, node2, node3, node4) = graph.nodes().unwrap().expect_tuple();
            assert_eq!(node0.info.expression().repr(), "get_pos");
            assert_eq!(node1.info.expression().repr(), "print x");
            assert_eq!(node2.info.expression().repr(), "print $ foo y");
            assert_eq!(node3.info.expression().repr(), "print z");

            let c = &connections.connections[0];
            assert_eq!(c.source.node, node0.info.id());
            assert_eq!(c.source.port, PortId::Ast(x_src));
            assert_eq!(c.target.node, node1.info.id());
            assert_eq!(c.target.port, PortId::Ast(x_dst));

            let c = &connections.connections[1];
            assert_eq!(c.source.node, node0.info.id());
            assert_eq!(c.source.port, PortId::Ast(y_src));
            assert_eq!(c.target.node, node2.info.id());
            assert_eq!(c.target.port, PortId::Ast(y_dst));

            let c = &connections.connections[2];
            assert_eq!(c.source.node, node2.info.id());
            assert_eq!(c.source.port, PortId::Ast(z_src));
            assert_eq!(c.target.node, node3.info.id());
            assert_eq!(c.target.port, PortId::Ast(z_dst1));

            let c = &connections.connections[3];
            assert_eq!(c.source.node, node2.info.id());
            assert_eq!(c.source.port, PortId::Ast(z_src));
            assert_eq!(c.target.node, node4.info.id());
            assert_eq!(c.target.port, PortId::Ast(z_dst2));
        })
    }

    #[test]
    fn graph_controller_create_connection() {
        /// A case for creating connection test. The field's names are short to be able to write
        /// nice-to-read table of cases without very long lines (see `let cases` below).
        #[derive(Clone, Debug)]
        struct Case {
            /// A pattern (the left side of assignment operator) of source node.
            src:      &'static str,
            /// An expression of target node.
            dst:      &'static str,
            /// Crumbs of source and target ports (i.e. SpanTree nodes)
            ports:    (Range<usize>, Range<usize>),
            /// Expected target expression after connecting.
            expected: &'static str,
        }

        impl Case {
            fn run(&self) {
                let mut test = Fixture::set_up();
                let src_prefix = "main = \n    ";
                let main_prefix = format!("{src_prefix}{} = foo\n    ", self.src);
                let main = format!("{}{}", main_prefix, self.dst);
                let expected = format!("{}{}", main_prefix, self.expected);
                let this = self.clone();

                let (src_port, dst_port) = self.ports.clone();
                let src_port = src_port.start + src_prefix.len()..src_port.end + src_prefix.len();
                let dst_port = dst_port.start + main_prefix.len()..dst_port.end + main_prefix.len();
                let src_port = PortId::Ast(test.data.id_map.generate(src_port));
                let dst_port = PortId::Ast(test.data.id_map.generate(dst_port));
                test.data.code = main;

                test.run(move |graph| async move {
                    let (node0, node1) = graph.nodes().unwrap().expect_tuple();
                    let source = Endpoint::new(node0.info.id(), src_port);
                    let target = Endpoint::new(node1.info.id(), dst_port);
                    let connection = Connection { source, target };
                    graph.connect(&connection, &span_tree::generate::context::Empty).unwrap();
                    let new_main = graph.definition().unwrap().ast.repr();
                    assert_eq!(new_main, expected, "Case {this:?}");
                })
            }
        }

        let cases = &[Case { src: "x", dst: "foo", expected: "x", ports: (0..1, 0..3) }, Case {
            src:      "Vec x y",
            dst:      "1 + 2 + 3",
            expected: "x + 2 + 3",
            ports:    (4..5, 0..1),
        }];
        for case in cases {
            case.run()
        }
    }

    #[test]
    fn graph_controller_create_connection_reordering() {
        let mut test = Fixture::set_up();
        const PROGRAM: &str = r"main =
    sum = _ + _
    a = 1
    b = 3";
        const EXPECTED: &str = r"main =
    a = 1
    b = 3
    sum = _ + b";
        test.data.code = PROGRAM.into();
        test.run(|graph| async move {
            assert!(connections(&graph).unwrap().connections.is_empty());
            let (node0, _node1, node2) = graph.nodes().unwrap().expect_tuple();
            let connection_to_add = Connection {
                source: Endpoint::root(node2.info.id()),
                target: Endpoint::target_at(&node0, [InfixCrumb::RightOperand]).unwrap(),
            };
            graph.connect(&connection_to_add, &span_tree::generate::context::Empty).unwrap();
            let new_main = graph.definition().unwrap().ast.repr();
            assert_eq!(new_main, EXPECTED);
        })
    }

    #[test]
    fn graph_controller_create_connection_reordering_with_dependency() {
        let mut test = Fixture::set_up();
        const PROGRAM: &str = r"main =
    sum = _ + _
    IO.println sum
    a = 1
    b = sum + 2
    c = 3
    d = 4";
        const EXPECTED: &str = r"main =
    a = 1
    c = 3
    sum = _ + c
    IO.println sum
    b = sum + 2
    d = 4";
        test.data.code = PROGRAM.into();
        test.run(|graph| async move {
            let (node0, _node1, _node2, _node3, node4, _) = graph.nodes().unwrap().expect_tuple();
            let connection_to_add = Connection {
                source: Endpoint::root(node4.info.id()),
                target: Endpoint::target_at(&node0, [InfixCrumb::RightOperand]).unwrap(),
            };
            graph.connect(&connection_to_add, &span_tree::generate::context::Empty).unwrap();
            let new_main = graph.definition().unwrap().ast.repr();
            assert_eq!(new_main, EXPECTED);
        })
    }

    #[test]
    fn graph_controller_create_connection_introducing_var() {
        let mut test = Fixture::set_up();
        const PROGRAM: &str = r"main =
    calculate
    print _
    calculate1 = calculate2
    calculate3 calculate5 = calculate5 calculate4";
        test.data.code = PROGRAM.into();
        // Note: we expect that name `calculate5` will be introduced. There is no conflict with a
        // function argument, as it just shadows outer variable.
        const EXPECTED: &str = r"main =
    calculate5 = calculate
    print calculate5
    calculate1 = calculate2
    calculate3 calculate5 = calculate5 calculate4";
        test.run(|graph| async move {
            assert!(connections(&graph).unwrap().connections.is_empty());
            let (node0, node1, _) = graph.nodes().unwrap().expect_tuple();
            let connection_to_add = Connection {
                source: Endpoint::root(node0.info.id()),
                target: Endpoint::target_at(&node1, [PrefixCrumb::Arg]).unwrap(),
            };
            graph.connect(&connection_to_add, &span_tree::generate::context::Empty).unwrap();
            let new_main = graph.definition().unwrap().ast.repr();
            assert_eq!(new_main, EXPECTED);
        })
    }

    #[test]
    fn suggested_names() {
        let parser = Parser::new();
        let cases = [
            ("a+b", "sum"),
            ("a-b", "difference"),
            ("a*b", "product"),
            ("a/b", "quotient"),
            ("read 'foo.csv'", "read"),
            ("Read 'foo.csv'", "read"),
            ("574", "number"),
            ("'Hello'", "text"),
            ("'Hello", "text"),
            ("\"Hello\"", "text"),
            ("\"Hello", "text"),
        ];

        for (code, expected_name) in &cases {
            let ast = parser.parse_line_ast(*code).unwrap();
            let node_info = NodeInfo::from_main_line_ast(&ast).unwrap();
            let name = Handle::variable_name_base_for(&node_info);
            assert_eq!(&name, expected_name);
        }
    }

    #[test]
    fn disconnect() {
        #[derive(Clone, Debug)]
        struct Case {
            dest_node_expr:     &'static str,
            dest_node_expected: &'static str,
            info:               Option<CalledMethodInfo>,
        }


        impl Case {
            fn run(&self) {
                let mut test = Fixture::set_up();
                const MAIN_PREFIX: &str = "main = \n    var = foo\n    ";
                test.data.code = format!("{}{}", MAIN_PREFIX, self.dest_node_expr);
                let expected = format!("{}{}", MAIN_PREFIX, self.dest_node_expected);
                let this = self.clone();
                test.run(|graph| async move {
                    let error_message = format!("{this:?}");
                    let ctx = match this.info.clone() {
                        Some(info) => {
                            let nodes = graph.nodes().expect(&error_message);
                            let dest_node_id = nodes.last().expect(&error_message).id();
                            MockContext::new_single(dest_node_id, info)
                        }
                        None => MockContext::default(),
                    };
                    let connections = graph.connections(&ctx).expect(&error_message);
                    let connection = connections.connections.first().expect(&error_message);
                    graph.disconnect(connection, &ctx).expect(&error_message);
                    let new_main = graph.definition().expect(&error_message).ast.repr();
                    assert_eq!(new_main, expected, "{error_message}");
                })
            }
        }

        let info = || {
            Some(CalledMethodInfo {
                parameters: vec![
                    span_tree::ArgumentInfo::named("arg1"),
                    span_tree::ArgumentInfo::named("arg2"),
                    span_tree::ArgumentInfo::named("arg3"),
                ],
                ..default()
            })
        };

        #[rustfmt::skip]
        let cases = &[
            Case { info: None, dest_node_expr: "var + a", dest_node_expected: "_ + a" },
            Case { info: None, dest_node_expr: "a + var", dest_node_expected: "a + _" },
            Case { info: None, dest_node_expr: "var + b + c", dest_node_expected: "b + c" },
            Case { info: None, dest_node_expr: "a + var + c", dest_node_expected: "a + c" },
            Case { info: None, dest_node_expr: "a + b + var", dest_node_expected: "a + b" },
            Case { info: None, dest_node_expr: "foo var", dest_node_expected: "foo _" },
            Case { info: None, dest_node_expr: "foo var a", dest_node_expected: "foo a" },
            Case { info: None, dest_node_expr: "foo a var", dest_node_expected: "foo a" },
            Case { info: info(), dest_node_expr: "foo var", dest_node_expected: "foo" },
            Case { info: info(), dest_node_expr: "foo var a", dest_node_expected: "foo arg2=a" },
            Case { info: info(), dest_node_expr: "foo a var", dest_node_expected: "foo a" },
            Case { info: info(), dest_node_expr: "foo arg2=var a", dest_node_expected: "foo a" },
            Case { info: info(), dest_node_expr: "foo arg1=var a", dest_node_expected: "foo arg2=a" },
            Case {
                info: info(),
                dest_node_expr: "foo arg2=var a c",
                dest_node_expected: "foo a arg3=c"
            },
            Case {
                info: None,
                dest_node_expr:     "f\n        bar a var",
                dest_node_expected: "f\n        bar a",
            },
        ];
        for case in cases {
            case.run();
        }
    }

    /// A regression test case for removing arguments. See
    /// https://github.com/enso-org/enso/issues/6228 for the issue's description.
    #[test]
    fn disconnect_issue_6228() {
        struct Case {
            initial_code:  &'static str,
            expected_code: &'static str,
        }

        impl Case {
            fn run(self) {
                let mut test = Fixture::set_up();
                const MAIN_PREFIX: &str = "main = \n    uri = \"bla\"\n    headers = []\n    ";
                test.data.code = format!("{}{}", MAIN_PREFIX, self.initial_code);
                let expected = format!("{}{}", MAIN_PREFIX, self.expected_code);
                let info = CalledMethodInfo {
                    parameters: vec![
                        span_tree::ArgumentInfo::named("uri"),
                        span_tree::ArgumentInfo::named("method"),
                        span_tree::ArgumentInfo::named("headers"),
                        // The last argument has a default value. It's presence is crucial to
                        // reproduce the bug.
                        span_tree::ArgumentInfo::named("parse"),
                    ],
                    ..default()
                };
                test.run(|graph| async move {
                    let nodes = graph.nodes().unwrap();
                    let dest_node_id = nodes.last().unwrap().id();
                    let ctx = MockContext::new_single(dest_node_id, info);
                    let connections = graph.connections(&ctx).unwrap();
                    let connection = connections.connections.first().unwrap();
                    graph.disconnect(connection, &ctx).unwrap();
                    let new_main = graph.definition().unwrap().ast.repr();
                    assert_eq!(new_main, expected);
                })
            }
        }

        let cases = vec![
            Case {
                initial_code:  "Data.fetch uri m headers",
                expected_code: "Data.fetch method=m headers=headers",
            },
            Case {
                initial_code:  "Data.fetch uri m headers True",
                expected_code: "Data.fetch method=m headers=headers parse=True",
            },
        ];

        for case in cases {
            case.run();
        }
    }
}
