//! The module containing [`ComponentBrowserSearcher`] presenter. See [`crate::presenter`]
//! documentation to know more about presenters in general.

use crate::prelude::*;

use crate::controller::searcher::Mode;
use crate::controller::searcher::Notification;
use crate::executor::global::spawn_stream_handler;
use crate::model::undo_redo::Transaction;
use crate::presenter;
use crate::presenter::graph::AstNodeId;
use crate::presenter::graph::ViewNodeId;
use crate::presenter::searcher::SearcherPresenter;

use enso_frp as frp;
use enso_suggestion_database::documentation_ir::EntryDocumentation;
use enso_text as text;
use ide_view as view;
use ide_view::component_browser;
use ide_view::component_browser::component_list_panel::grid as component_grid;
use ide_view::documentation::breadcrumbs::BreadcrumbId;
use ide_view::graph_editor::NodeId;
use ide_view::project::SearcherParams;


// ==============
// === Export ===
// ==============

pub mod provider;



// ==============
// === Errors ===
// ==============

#[allow(missing_docs)]
#[derive(Copy, Clone, Debug, Fail)]
#[fail(display = "No component group with the index {:?}.", _0)]
pub struct NoSuchComponent(component_grid::EntryId);



// =============
// === Model ===
// =============

#[derive(Clone, Debug)]
struct Model {
    controller: controller::Searcher,
    graph_controller: controller::Graph,
    graph_presenter: presenter::Graph,
    project: view::project::View,
    provider: Rc<RefCell<Option<provider::Component>>>,
    input_view: ViewNodeId,
    view: component_browser::View,
    mode: Immutable<Mode>,
    transaction: Rc<Transaction>,
    visualization_was_enabled: bool,
}

impl Model {
    #[profile(Debug)]
    #[allow(clippy::too_many_arguments)]
    fn new(
        controller: controller::Searcher,
        graph_controller: &controller::Graph,
        graph_presenter: &presenter::Graph,
        project: view::project::View,
        input_view: ViewNodeId,
        view: component_browser::View,
        mode: Mode,
        transaction: Rc<Transaction>,
        visualization_was_enabled: bool,
    ) -> Self {
        let provider = default();
        let graph_controller = graph_controller.clone_ref();

        let graph_presenter = graph_presenter.clone_ref();
        let mode = Immutable(mode);
        Self {
            controller,
            graph_controller,
            graph_presenter,
            project,
            view,
            provider,
            input_view,
            mode,
            transaction,
            visualization_was_enabled,
        }
    }

    #[profile(Debug)]
    fn input_changed(&self, new_input: &str, cursor_position: text::Byte) {
        if let Err(err) = self.controller.set_input(new_input.to_owned(), cursor_position) {
            error!("Error while setting new searcher input: {err}.");
        }
    }

    /// Should be called if a suggestion is selected but not used yet.
    fn suggestion_selected(&self, entry_id: Option<component_grid::EntryId>) {
        if let Err(error) = self.controller.preview_by_index(entry_id) {
            error!("Failed to preview searcher input (selected suggestion: {entry_id:?}) because of error: {error}.");
        }
    }

    fn update_preview(&self) {
        if let Err(error) = self.controller.preview_input() {
            error!("Failed to preview searcher preview because of error: {error}.")
        }
    }

    fn suggestion_accepted(
        &self,
        id: component_grid::EntryId,
    ) -> Option<(ViewNodeId, text::Range<text::Byte>, ImString)> {
        let new_code = self.controller.use_suggestion_by_index(id);
        match new_code {
            Ok(text::Change { range, text }) => Some((self.input_view, range, text.into())),
            Err(err) => {
                error!("Error while applying suggestion: {err}.");
                None
            }
        }
    }

    fn abort_editing(&self) {
        self.transaction.ignore();
        self.controller.abort_editing();
        if let Mode::EditNode { original_node_id, .. } = *self.mode {
            self.reenable_visualization_if_needed();

            self.graph_presenter.assign_node_view_explicitly(self.input_view, original_node_id);
            // Force view update so resets to the old expression.
            self.graph_presenter.force_view_update.emit(original_node_id);
        }
        let node_id = self.mode.node_id();
        if let Err(err) = self.graph_controller.remove_node(node_id) {
            error!("Error while removing a temporary node: {err}.");
        }
    }

    fn breadcrumb_selected(&self, id: BreadcrumbId) {
        self.controller.select_breadcrumb(id);
    }

    fn module_entered(&self, entry: component_grid::EntryId) {
        if let Err(error) = self.controller.enter_entry(entry) {
            error!("Failed to enter entry in Component Browser: {error}")
        }
    }

    fn update_breadcrumbs(&self, target_entry: component_grid::EntryId) {
        let breadcrumbs = self.controller.update_breadcrumbs(target_entry);
        if let Some(breadcrumbs) = breadcrumbs {
            let browser = &self.view;
            let breadcrumbs_count = breadcrumbs.len();
            let without_icon =
                breadcrumbs[0..breadcrumbs_count - 1].iter().map(|crumb| crumb.view_without_icon());
            let with_icon =
                breadcrumbs[breadcrumbs_count - 1..].iter().map(|crumb| crumb.view_with_icon());
            let all = without_icon.chain(with_icon).collect_vec();
            browser.model().documentation.breadcrumbs.set_entries(all);
        }
    }

    fn reenable_visualization_if_needed(&self) {
        if let Mode::EditNode { original_node_id, .. } = *self.mode {
            if let Some(node_view) = self.graph_presenter.view_id_of_ast_node(original_node_id) {
                self.project.graph().model.with_node(node_view, |node| {
                    if self.visualization_was_enabled {
                        node.enable_visualization();
                    }
                });
            }
        }
    }

    fn expression_accepted(&self, entry_id: Option<component_grid::EntryId>) -> Option<AstNodeId> {
        if let Some(entry_id) = entry_id {
            self.suggestion_accepted(entry_id);
        }
        if !self.controller.is_input_empty() {
            match self.controller.commit_node() {
                Ok(ast_id) => {
                    if let Mode::EditNode { original_node_id, edited_node_id } = *self.mode {
                        self.reenable_visualization_if_needed();

                        self.graph_presenter
                            .assign_node_view_explicitly(self.input_view, original_node_id);
                        if let Err(err) = self.graph_controller.remove_node(edited_node_id) {
                            error!("Error while removing a temporary node: {err}.");
                        }
                        // Sync the view with the presenter state, updating the actual error and
                        // pending state (as temporary node could receive some errors while
                        // previewing suggestion).
                        self.graph_presenter.force_view_update.emit(original_node_id);
                    }
                    Some(ast_id)
                }
                Err(err) => {
                    error!("Error while committing node expression: {err}.");
                    None
                }
            }
        } else {
            // if input is empty or contains spaces only, we cannot update the node (there is no
            // valid AST to assign). Because it is an expected thing, we also do not report error.
            None
        }
    }

    fn documentation_of_component(&self, id: component_grid::EntryId) -> EntryDocumentation {
        self.controller.documentation_for_entry(id)
    }

    fn docs_for_breadcrumb(&self) -> Option<EntryDocumentation> {
        self.controller.documentation_for_selected_breadcrumb()
    }

    fn should_select_first_entry(&self) -> bool {
        self.controller.is_filtering() || self.controller.is_input_empty()
    }

    fn on_entry_for_docs_selected(&self, id: Option<component_grid::EntryId>) {
        if let Some(id) = id {
            self.update_breadcrumbs(id);
        }
    }
}

/// The Searcher presenter, synchronizing state between searcher view and searcher controller.
///
/// The presenter should be created for one instantiated searcher controller (when a node starts
/// being edited). Alternatively, the [`setup_controller`] method covers constructing the controller
/// and the presenter.
#[derive(Debug)]
pub struct ComponentBrowserSearcher {
    _network: frp::Network,
    model:    Rc<Model>,
}


impl SearcherPresenter for ComponentBrowserSearcher {
    #[profile(Task)]
    fn setup_searcher(
        ide_controller: controller::Ide,
        project_controller: controller::Project,
        graph_controller: controller::ExecutedGraph,
        graph_presenter: &presenter::Graph,
        view: view::project::View,
        parameters: SearcherParams,
    ) -> FallibleResult<Self> {
        // We get the position for searcher before initializing the input node, because the
        // added node will affect the AST, and the position will become incorrect.
        let position_in_code = graph_controller.graph().definition_end_location()?;
        let graph = graph_controller.graph();
        let transaction = graph.get_or_open_transaction("Open searcher");

        let mode = Self::init_input_node(parameters, graph_presenter, view.graph(), &graph)?;

        let mut visualization_was_enabled = false;
        if let Mode::EditNode { original_node_id, .. } = mode {
            if let Some(target_node_view) = graph_presenter.view_id_of_ast_node(original_node_id) {
                view.graph().model.with_node(target_node_view, |node| {
                    visualization_was_enabled = node.visualization_enabled.value();
                    if visualization_was_enabled {
                        node.disable_visualization();
                    }
                    node.show_preview();
                });
            }
        }


        let searcher_controller = controller::Searcher::new_from_graph_controller(
            ide_controller,
            &project_controller.model,
            graph_controller,
            mode,
            parameters.cursor_position,
            position_in_code,
        )?;

        // Clear input on a new node. By default this will be set to whatever is used as the default
        // content of the new node.
        if let Mode::NewNode { source_node, .. } = mode {
            if source_node.is_none() {
                if let Err(e) = searcher_controller.set_input("".to_string(), text::Byte(0)) {
                    error!("Failed to clear input when creating searcher for a new node: {e:?}.");
                }
            }
        }

        let input = parameters.input;
        Ok(Self::new(
            searcher_controller,
            &graph,
            graph_presenter,
            view,
            input,
            mode,
            transaction,
            visualization_was_enabled,
        ))
    }

    fn expression_accepted(
        self: Box<Self>,
        _node_id: NodeId,
        entry_id: Option<component_grid::EntryId>,
    ) -> Option<AstNodeId> {
        self.model.expression_accepted(entry_id)
    }


    fn abort_editing(self: Box<Self>) {
        self.model.abort_editing();
    }

    fn input_view(&self) -> ViewNodeId {
        self.model.input_view
    }
}

impl ComponentBrowserSearcher {
    #[profile(Task)]
    #[allow(clippy::too_many_arguments)]
    fn new(
        controller: controller::Searcher,
        graph_controller: &controller::Graph,
        graph_presenter: &presenter::Graph,
        view: view::project::View,
        input_view: ViewNodeId,
        mode: Mode,
        transaction: Rc<Transaction>,
        visualization_was_enabled: bool,
    ) -> Self {
        let searcher_view = view.searcher().clone_ref();
        let model = Rc::new(Model::new(
            controller,
            graph_controller,
            graph_presenter,
            view,
            input_view,
            searcher_view,
            mode,
            transaction,
            visualization_was_enabled,
        ));
        let network = frp::Network::new("presenter::Searcher");

        let graph = &model.project.graph().frp;
        let browser = &model.view;

        frp::extend! { network
            on_input_changed <- model.project.searcher_input_changed.map(f!([model]((expr, selections)) {
                let cursor_position = selections.last().map(|sel| sel.end).unwrap_or_default();
                model.input_changed(expr, cursor_position);
            }));

            action_list_changed <- any_mut::<()>();
            // When the searcher input is changed, we need to update immediately the list of
            // entries in the component browser (as opposed to waiting for a `NewActionList` event
            // which is delivered asynchronously). This is because the input may be accepted
            // before the asynchronous event is delivered and to accept the correct entry the list
            // must be up-to-date.
            action_list_changed <+ model.project.searcher_input_changed.constant(());

            eval_ model.project.request_dump_suggestion_database(model.controller.dump_database_as_json());
            eval_ model.project.toggle_component_browser_private_entries_visibility (
                model.controller.reload_list());
        }

        let grid = &browser.model().list.model().grid;
        let breadcrumbs = &browser.model().documentation.breadcrumbs;
        let documentation = &browser.model().documentation;
        frp::extend! { network
            init <- source_();
            eval_ action_list_changed ([model, grid] {
                model.provider.take();
                let list = model.controller.components();
                let provider = provider::Component::provide_new_list(&list, &grid);
                *model.provider.borrow_mut() = Some(provider);
            });
            grid.select_first_entry <+ action_list_changed.filter(f_!(model.should_select_first_entry()));
            input_edit <- grid.suggestion_accepted.filter_map(f!((e) model.suggestion_accepted(*e)));
            graph.edit_node_expression <+ input_edit;

            docs_params <- all(&action_list_changed, &grid.active);
            docs <- docs_params.filter_map(f!([model]((_, entry)) {
                entry.map(|entry_id| model.documentation_of_component(entry_id))
            }));
            docs_from_breadcrumbs <- breadcrumbs.selected.map(f!((selected){
                model.breadcrumb_selected(*selected);
                model.docs_for_breadcrumb()
            })).unwrap();
            docs <- any(docs,docs_from_breadcrumbs);
            documentation.frp.display_documentation <+ docs;
            eval grid.active ((entry) model.on_entry_for_docs_selected(*entry));

            no_selection <- any(...);
            no_selection <+ init.constant(true);
            no_selection <+ grid.active.on_change().map(|e| e.is_none());
            eval_ grid.suggestion_accepted([]analytics::remote_log_event("component_browser::suggestion_accepted"));
            update_preview <- on_input_changed.gate(&no_selection);
            eval_ update_preview(model.update_preview());
            eval grid.active((entry) model.suggestion_selected(*entry));
            eval grid.module_entered((id) model.module_entered(*id));
        }
        init.emit(());

        let weak_model = Rc::downgrade(&model);
        let notifications = model.controller.subscribe();
        spawn_stream_handler(weak_model, notifications, move |notification, _| {
            match notification {
                Notification::NewComponentList => action_list_changed.emit(()),
            };
            std::future::ready(())
        });

        Self { model, _network: network }
    }
}
