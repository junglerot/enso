//! NOTE
//! This file is under a heavy development. It contains commented lines of code and some code may
//! be of poor quality. Expect drastic changes.

// === Features ===
#![feature(associated_type_defaults)]
#![feature(drain_filter)]
#![feature(entry_insert)]
#![feature(fn_traits)]
#![feature(option_result_contains)]
#![feature(specialization)]
#![feature(trait_alias)]
#![feature(type_alias_impl_trait)]
#![feature(unboxed_closures)]
// === Standard Linter Configuration ===
#![deny(non_ascii_idents)]
#![warn(unsafe_code)]
#![allow(clippy::bool_to_int_with_if)]
#![allow(clippy::let_and_return)]
#![allow(incomplete_features)] // To be removed, see: https://github.com/enso-org/ide/issues/1559
#![warn(missing_copy_implementations)]
#![warn(missing_debug_implementations)]
#![warn(missing_docs)]
#![warn(trivial_casts)]
#![warn(trivial_numeric_casts)]
#![warn(unsafe_code)]
#![warn(unused_import_braces)]
#![warn(unused_qualifications)]
#![recursion_limit = "1024"]

#[warn(missing_docs)]
pub mod component;

pub mod automation;
pub mod builtin;
pub mod data;
pub mod new_node_position;
#[warn(missing_docs)]
pub mod profiling;
#[warn(missing_docs)]
pub mod view;

#[warn(missing_docs)]
mod selection;

use crate::application::command::FrpNetworkProvider;
use crate::component::node;
use crate::component::type_coloring;
use crate::component::visualization;
use crate::component::visualization::instance::PreprocessorConfiguration;
use crate::component::visualization::MockDataGenerator3D;
use crate::data::enso;
pub use crate::node::profiling::Status as NodeProfilingStatus;

use application::tooltip;
use enso_config::ARGS;
use enso_frp as frp;
use ensogl::application;
use ensogl::application::shortcut;
use ensogl::application::Application;
use ensogl::data::color;
use ensogl::display;
use ensogl::display::navigation::navigator::Navigator;
use ensogl::display::object::Id;
use ensogl::display::shape::StyleWatch;
use ensogl::display::shape::StyleWatchFrp;
use ensogl::display::Scene;
use ensogl::gui::cursor;
use ensogl::prelude::*;
use ensogl::system::web;
use ensogl::system::web::traits::*;
use ensogl::Animation;
use ensogl::DEPRECATED_Animation;
use ensogl::Easing;
use ensogl_component::text;
use ensogl_component::text::buffer::selection::Selection;
use ensogl_component::tooltip::Tooltip;
use ensogl_hardcoded_theme as theme;
use ide_view_execution_mode_selector as execution_mode_selector;


// ===============
// === Prelude ===
// ===============

/// Commonly used utilities.
pub mod prelude {
    pub use ensogl::application::command::View;
    pub use ensogl::prelude::*;
}



// =================
// === Constants ===
// =================

const SNAP_DISTANCE_THRESHOLD: f32 = 10.0;
/// Time between key down and key up event to consider it a press and hold action as opposed to a
/// simple key press.
const VIZ_PREVIEW_MODE_TOGGLE_TIME_MS: f32 = 300.0;
/// Number of frames we expect to pass during the `VIZ_PREVIEW_MODE_TOGGLE_TIME_MS` interval.
/// Assumes 60fps. We use this value to check against dropped frames during the interval.
const VIZ_PREVIEW_MODE_TOGGLE_FRAMES: i32 =
    (VIZ_PREVIEW_MODE_TOGGLE_TIME_MS / 1000.0 * 60.0) as i32;
const MACOS_TRAFFIC_LIGHTS_CONTENT_WIDTH: f32 = 52.0;
const MACOS_TRAFFIC_LIGHTS_CONTENT_HEIGHT: f32 = 12.0;
/// Horizontal and vertical offset between traffic lights and window border
const MACOS_TRAFFIC_LIGHTS_SIDE_OFFSET: f32 = 13.0;
const MACOS_TRAFFIC_LIGHTS_VERTICAL_CENTER: f32 =
    -MACOS_TRAFFIC_LIGHTS_SIDE_OFFSET - MACOS_TRAFFIC_LIGHTS_CONTENT_HEIGHT / 2.0;
const MAX_ZOOM: f32 = 1.0;
/// Space between items in the top bar.
const TOP_BAR_ITEM_MARGIN: f32 = 10.0;

fn traffic_lights_gap_width() -> f32 {
    let platform_str = ARGS.groups.startup.options.platform.value.as_str();
    let platform = web::platform::Platform::try_from(platform_str);
    let is_macos = platform.map(|p| p.is_macos()).ok() == Some(true);
    if is_macos && !ARGS.groups.window.options.frame.value {
        MACOS_TRAFFIC_LIGHTS_CONTENT_WIDTH + MACOS_TRAFFIC_LIGHTS_SIDE_OFFSET
    } else {
        TOP_BAR_ITEM_MARGIN
    }
}


// =================
// === SharedVec ===
// =================

#[derive(CloneRef, Debug, Derivative)]
#[derivative(Default(bound = ""))]
#[derivative(Clone(bound = ""))]
#[allow(missing_docs)] // FIXME[everyone] Public-facing API should be documented.
pub struct SharedVec<T> {
    pub raw: Rc<RefCell<Vec<T>>>,
}

impl<T> SharedVec<T> {
    /// Constructor.
    pub fn new() -> Self {
        default()
    }

    /// Append an element to the back of a collection.
    pub fn push(&self, t: T) {
        self.raw.borrow_mut().push(t);
    }

    /// Remove the first instance of `item` from the vector if the item exists.
    pub fn remove_item(&self, t: &T)
    where T: PartialEq {
        self.raw.borrow_mut().remove_item(t);
    }

    /// Return `true` if the slice contains an element with the given value.
    pub fn contains(&self, t: &T) -> bool
    where T: PartialEq {
        self.raw.borrow().contains(t)
    }

    /// Return clone of the first element of the slice, or `None` if it is empty.
    pub fn first_cloned(&self) -> Option<T>
    where T: Clone {
        self.raw.borrow().first().cloned()
    }

    /// Return clone of the last element of the slice, or `None` if it is empty.
    pub fn last_cloned(&self) -> Option<T>
    where T: Clone {
        self.raw.borrow().last().cloned()
    }

    /// Replace the collection with the default value, and return the previous value.
    pub fn mem_take(&self) -> Vec<T> {
        mem::take(&mut self.raw.borrow_mut())
    }

    /// Return the number of items in the vector.
    pub fn len(&self) -> usize {
        self.raw.borrow().len()
    }

    /// Check if the container is empty.
    pub fn is_empty(&self) -> bool {
        self.raw.borrow().is_empty()
    }
}

impl<T: Clone> SharedVec<T> {
    /// Return a vector of all items stored in the collection in order.
    pub fn items(&self) -> Vec<T> {
        self.raw.borrow().clone()
    }
}



// =====================
// === SharedHashSet ===
// =====================

#[derive(Derivative, CloneRef)]
#[derivative(Debug(bound = "T:Eq+Hash+Debug, S:std::hash::BuildHasher"))]
#[allow(missing_docs)] // FIXME[everyone] Public-facing API should be documented.
pub struct SharedHashSet<T, S = std::collections::hash_map::RandomState> {
    pub raw: Rc<RefCell<HashSet<T, S>>>,
}

impl<T, S> Clone for SharedHashSet<T, S> {
    fn clone(&self) -> Self {
        let raw = self.raw.clone();
        Self { raw }
    }
}

impl<T, S> Default for SharedHashSet<T, S>
where
    T: Eq + Hash,
    S: Default + std::hash::BuildHasher,
{
    fn default() -> Self {
        let raw = default();
        Self { raw }
    }
}

impl<T, S> SharedHashSet<T, S>
where
    T: Eq + Hash,
    S: Default + std::hash::BuildHasher,
{
    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn new() -> Self {
        default()
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn mem_take(&self) -> HashSet<T, S> {
        mem::take(&mut *self.raw.borrow_mut())
    }
}

impl<T, S> SharedHashSet<T, S>
where
    T: Eq + Hash,
    S: std::hash::BuildHasher,
{
    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn insert(&self, t: T) -> bool {
        self.raw.borrow_mut().insert(t)
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn remove(&self, t: &T) -> bool {
        self.raw.borrow_mut().remove(t)
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn contains(&self, value: &T) -> bool {
        self.raw.borrow().contains(value)
    }
}

impl<T, S> SharedHashSet<T, S> {
    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn is_empty(&self) -> bool {
        self.raw.borrow().is_empty()
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn clear(&self) {
        self.raw.borrow_mut().clear()
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn for_each<F>(&self, f: F)
    where F: FnMut(&T) {
        self.raw.borrow_mut().iter().for_each(f)
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn replace_with(&self, t: HashSet<T, S>) {
        *self.raw.borrow_mut() = t;
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn keys(&self) -> Vec<T>
    where T: Clone {
        self.raw.borrow().iter().cloned().collect_vec()
    }
}



// =====================
// === SharedHashMap ===
// =====================

#[derive(Derivative, CloneRef)]
#[derivative(Debug(bound = "K:Eq+Hash+Debug, V:Debug, S:std::hash::BuildHasher"))]
#[allow(missing_docs)] // FIXME[everyone] Public-facing API should be documented.
pub struct SharedHashMap<K, V, S = std::collections::hash_map::RandomState> {
    pub raw: Rc<RefCell<HashMap<K, V, S>>>,
}

impl<K, V, S> Clone for SharedHashMap<K, V, S> {
    fn clone(&self) -> Self {
        let raw = self.raw.clone();
        Self { raw }
    }
}

impl<K, V, S> Default for SharedHashMap<K, V, S>
where
    K: Eq + Hash,
    S: Default + std::hash::BuildHasher,
{
    fn default() -> Self {
        let raw = default();
        Self { raw }
    }
}

impl<K, V, S> SharedHashMap<K, V, S>
where
    K: Eq + Hash,
    S: Default + std::hash::BuildHasher,
{
    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn new() -> Self {
        default()
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn mem_take(&self) -> HashMap<K, V, S> {
        mem::take(&mut *self.raw.borrow_mut())
    }
}

impl<K, V, S> SharedHashMap<K, V, S>
where
    K: Eq + Hash,
    S: std::hash::BuildHasher,
{
    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn insert(&self, k: K, v: V) -> Option<V> {
        self.raw.borrow_mut().insert(k, v)
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn get_copied(&self, k: &K) -> Option<V>
    where V: Copy {
        self.raw.borrow().get(k).copied()
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn get_cloned(&self, k: &K) -> Option<V>
    where V: Clone {
        self.raw.borrow().get(k).cloned()
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn get_cloned_ref(&self, k: &K) -> Option<V>
    where V: CloneRef {
        self.raw.borrow().get(k).map(|t| t.clone_ref())
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn remove(&self, k: &K) -> Option<V> {
        self.raw.borrow_mut().remove(k)
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn contains_key(&self, key: &K) -> bool {
        self.raw.borrow().contains_key(key)
    }
}

impl<K, V, S> SharedHashMap<K, V, S> {
    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn len(&self) -> usize {
        self.raw.borrow().len()
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn is_empty(&self) -> bool {
        self.raw.borrow().is_empty()
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn clear(&self) {
        self.raw.borrow_mut().clear()
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn for_each<F>(&self, f: F)
    where F: FnMut((&K, &V)) {
        self.raw.borrow_mut().iter().for_each(f)
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn keys(&self) -> Vec<K>
    where K: Clone {
        self.raw.borrow().keys().cloned().collect_vec()
    }

    /// Get the vector of map's keys and values.
    pub fn entries(&self) -> Vec<(K, V)>
    where
        K: Clone,
        V: CloneRef, {
        self.raw.borrow().iter().map(|(k, v)| (k.clone(), v.clone_ref())).collect_vec()
    }

    /// Get the vector of map's values.
    pub fn values(&self) -> Vec<V>
    where V: Clone {
        self.raw.borrow().values().cloned().collect_vec()
    }
}



// =================
// === FrpInputs ===
// =================

/// The information about data source hinted by node creation process. For example, when creating
/// node by dropping edge, the source port should be a source for newly created node.
///
/// This is information meant to be sent to searcher, which can, for example, auto- connect the
/// source to "this" port of new node.
#[derive(Clone, CloneRef, Copy, Debug, Default, Eq, PartialEq)]
pub struct NodeSource {
    #[allow(missing_docs)]
    pub node: NodeId,
}

ensogl::define_endpoints_2! {
    Input {
        // === General ===
        /// Cancel the operation being currently performed. Often mapped to the escape key.
        cancel(),


        // === Layout ===
        space_for_window_buttons (Vector2<f32>),


        // === Node Selection ===

        /// Node press event
        node_press(),
        /// Node press event
        node_release(),
        /// Enable nodes multi selection mode. It works like inverse mode for single node selection
        /// and like merge mode for multi node selection mode.
        enable_node_multi_select(),
        /// Disable nodes multi selection mode. It works like inverse mode for single node selection
        /// and like merge mode for multi node selection mode.
        disable_node_multi_select(),
        /// Toggle nodes multi selection mode. It works like inverse mode for single node selection
        /// and like merge mode for multi node selection mode.
        toggle_node_multi_select(),

        /// Enable nodes merge selection mode.
        enable_node_merge_select(),
        /// Disable nodes merge selection mode.
        disable_node_merge_select(),
        /// Toggles nodes merge selection mode.
        toggle_node_merge_select(),

        /// Enable nodes subtract selection mode.
        enable_node_subtract_select(),
        /// Disable nodes subtract selection mode.
        disable_node_subtract_select(),
        /// Toggle nodes subtract selection mode.
        toggle_node_subtract_select(),

        /// Enable nodes inverse selection mode.
        enable_node_inverse_select(),
        /// Disable nodes inverse selection mode.
        disable_node_inverse_select(),
        /// Toggle nodes inverse selection mode.
        toggle_node_inverse_select(),

        /// Set the node as selected. Ignores selection mode.
        // WARNING: not implemented
        select_node                  (NodeId),
        /// Set the node as deselected. Ignores selection mode.
        // WARNING: not implemented
        deselect_node                (NodeId),
        /// Set all nodes as selected. Ignores selection mode.
        select_all_nodes             (),


        // === Navigation ===

        /// Enter the last selected node.
        enter_selected_node(),
        /// Enter the node currently under the cursor.
        enter_hovered_node(),
        /// Steps out of the current node, popping the topmost stack frame from the crumb list.
        exit_node(),


        // === Node Editing ===

        /// Add a new node and place it in the origin of the workspace.
        add_node(),
        /// Start Node creation process.
        ///
        /// This event is the best to be emit in situations, when the user want to create node (in
        /// opposition to e.g. loading graph from file). It will create node and put it into edit
        /// mode. The node position may vary, depending on what is the best for the UX - for details
        /// see [`GraphEditorModel::create_node`] implementation.
        start_node_creation(),
        /// Start creation of a new Node connected to the port that is currently under the cursor.
        /// If the cursor is currently not over any node's port, this event will have no effect.
        ///
        /// The same as in the case of [`start_node_creation`], this event is intended to be
        /// emitted in situations when the user wants to interactively create a node via the UI (as
        /// opposed to e.g. when loading a graph from a file).
        start_node_creation_from_port(),




        /// Remove all selected nodes from the graph.
        remove_selected_nodes(),
        /// Remove all nodes from the graph.
        remove_all_nodes(),
        /// Enable mode in which the pressed node will be edited.
        edit_mode_on(),
        /// Disable mode in which the pressed node will be edited.
        edit_mode_off(),
        /// Stop node editing, whatever node is currently edited.
        stop_editing(),
        /// Collapse the selected nodes into a new node.
        collapse_selected_nodes(),
        /// Indicate whether this node had an error or not.
        set_node_error_status(NodeId,Option<node::error::Error>),
        /// Indicate whether this node has finished execution.
        set_node_profiling_status(NodeId,node::profiling::Status),


        // === Visualization ===

        /// Simulates a visualization open press event. In case the event will be shortly followed
        /// by `release_visualization_visibility`, the visualization will be shown permanently. In
        /// other case, it will be disabled as soon as the `release_visualization_visibility` is
        /// emitted.
        press_visualization_visibility(),
        /// Simulates a visualization open double press event. This event toggles the visualization
        /// fullscreen mode.
        double_press_visualization_visibility(),
        /// Simulates a visualization open release event. See `press_visualization_visibility` to
        /// learn more.
        release_visualization_visibility(),
        /// Cycle the visualization for the selected nodes.
        cycle_visualization_for_selected_node(),
        /// The visualization currently displayed as fullscreen is
        close_fullscreen_visualization(),


        // === Scene Navigation ===

        /// Stop the scene camera from moving around, locking the scene in place.
        /// Can be used, e.g., if there is a fullscreen visualisation active, or navigation should
        ///only work for a selected visualisation.
        set_navigator_disabled(bool),


        // === Modes ===

        toggle_profiling_mode(),


        // === Execution Environment ===

        set_execution_environment(ExecutionEnvironment),
        // TODO(#5930): Temporary shortcut for testing different execution environments
        toggle_execution_environment(),


        // === Debug ===

        /// Enable or disable debug-only features.
        set_debug_mode(bool),

        /// Push a hardcoded breadcrumb without notifying the controller.
        debug_push_breadcrumb(),
        /// Pop a breadcrumb without notifying the controller.
        debug_pop_breadcrumb(),
        /// Set a test visualization data for the selected nodes. Useful for testing visualizations
        /// during their development.
        debug_set_test_visualization_data_for_selected_node(),


        // === VCS Status ===

        set_node_vcs_status     ((NodeId, Option<node::vcs::Status>)),


        set_detached_edge_targets    (EdgeEndpoint),
        set_detached_edge_sources    (EdgeEndpoint),
        set_edge_source              ((EdgeId, EdgeEndpoint)),
        set_edge_target              ((EdgeId, EdgeEndpoint)),
        unset_edge_source            (EdgeId),
        unset_edge_target            (EdgeId),
        connect_nodes                ((EdgeEndpoint,EdgeEndpoint)),
        deselect_all_nodes           (),
        press_node_input             (EdgeEndpoint),
        press_node_output            (EdgeEndpoint),
        remove_all_node_edges        (NodeId),
        remove_all_node_input_edges  (NodeId),
        remove_all_node_output_edges (NodeId),
        remove_edge                  (EdgeId),
        remove_node                  (NodeId),
        edit_node                    (NodeId),
        collapse_nodes               ((Vec<NodeId>,NodeId)),
        set_node_expression          ((NodeId,node::Expression)),
        edit_node_expression         ((NodeId, text::Range<text::Byte>, ImString)),
        set_node_skip                ((NodeId,bool)),
        set_node_freeze              ((NodeId,bool)),
        /// Set whether the output context is explicitly enabled for a node: `Some(true/false)` for
        /// enabled/disabled; `None` for no context switch expression.
        set_node_context_switch      ((NodeId, Option<bool>)),
        set_node_comment             ((NodeId,node::Comment)),
        set_node_position            ((NodeId,Vector2)),
        set_expression_usage_type    ((NodeId,ast::Id,Option<Type>)),
        update_node_widgets          ((NodeId,WidgetUpdates)),
        cycle_visualization          (NodeId),
        set_visualization            ((NodeId, Option<visualization::Path>)),
        register_visualization       (Option<visualization::Definition>),
        set_visualization_data       ((NodeId, visualization::Data)),
        set_error_visualization_data ((NodeId, visualization::Data)),
        enable_visualization         (NodeId),
        disable_visualization        (NodeId),

        /// Remove from visualization registry all non-default visualizations.
        reset_visualization_registry (),
        /// Reload visualization registry
        reload_visualization_registry(),
        /// Show visualisation previews on nodes without delay.
        enable_quick_visualization_preview(),
        /// Show visualisation previews on nodes with delay.
        disable_quick_visualization_preview(),

        /// Drop an edge that is being dragged.
        drop_dragged_edge            (),

        /// Set the execution modes available to the graph.
        set_available_execution_modes          (Rc<Vec<execution_mode_selector::ExecutionMode>>),

    }

    Output {
        // === Debug Mode ===

        debug_mode                             (bool),

        // === Edge ===

        has_detached_edge                      (bool),
        on_edge_add                            (EdgeId),
        on_edge_drop                           (EdgeId),
        on_edge_drop_overlapping               (EdgeId),
        on_edge_drop_to_create_node            (EdgeId),
        on_edge_source_set                     ((EdgeId,EdgeEndpoint)),
        on_edge_source_set_with_target_not_set ((EdgeId,EdgeEndpoint)),
        on_edge_target_set_with_source_not_set ((EdgeId,EdgeEndpoint)),
        on_edge_target_set                     ((EdgeId,EdgeEndpoint)),
        on_edge_source_unset                   ((EdgeId,EdgeEndpoint)),
        on_edge_target_unset                   ((EdgeId,EdgeEndpoint)),

        /// Fires always when there is a new edge with source set but target not set. This could
        /// happen after the target was disconnected or the edge was created and its source was
        /// connected.
        on_edge_only_target_not_set (EdgeId),

        /// Fires always when there is a new edge with target set but source not set. This could
        /// happen after the source was disconnected or the edge was created and its target was
        /// connected.
        on_edge_only_source_not_set (EdgeId),

        on_edge_endpoint_unset      ((EdgeId,EdgeEndpoint)),
        on_edge_endpoint_set        ((EdgeId,EdgeEndpoint)),
        on_edge_endpoints_set       (EdgeId),
        on_some_edges_targets_unset (),
        on_some_edges_sources_unset (),
        on_all_edges_targets_set    (),
        on_all_edges_sources_set    (),
        on_all_edges_endpoints_set  (),
        some_edge_targets_unset     (bool),
        some_edge_sources_unset     (bool),
        some_edge_endpoints_unset   (bool),

        hover_node_input            (Option<EdgeEndpoint>),
        hover_node_output           (Option<EdgeEndpoint>),


        // === Other ===
        // FIXME: To be refactored

        node_added                 (NodeId, Option<NodeSource>, bool),
        node_removed               (NodeId),
        nodes_collapsed            ((Vec<NodeId>, NodeId)),
        node_hovered               (Option<Switch<NodeId>>),
        node_selected              (NodeId),
        node_deselected            (NodeId),
        node_position_set          ((NodeId,Vector2)),
        node_position_set_batched  ((NodeId,Vector2)),
        node_expression_set        ((NodeId,ImString)),
        node_expression_span_set   ((NodeId, span_tree::Crumbs, ImString)),
        node_expression_edited     ((NodeId,ImString,Vec<Selection<text::Byte>>)),
        node_comment_set           ((NodeId,String)),
        node_entered               (NodeId),
        node_exited                (),
        node_editing_started       (NodeId),
        node_editing_finished      (NodeId),
        node_action_context_switch ((NodeId, bool)),
        node_action_freeze         ((NodeId, bool)),
        node_action_skip           ((NodeId, bool)),
        node_edit_mode             (bool),
        nodes_labels_visible       (bool),


        /// `None` value as a visualization path denotes a disabled visualization.
        enabled_visualization_path              (NodeId,Option<visualization::Path>),
        visualization_shown                     (NodeId,visualization::Metadata),
        visualization_hidden                    (NodeId),
        visualization_fullscreen                (Option<NodeId>),
        is_fs_visualization_displayed           (bool),
        visualization_preprocessor_changed      ((NodeId,PreprocessorConfiguration)),
        visualization_registry_reload_requested (),

        widgets_requested                       (NodeId, ast::Id, ast::Id),
        request_import                          (ImString),

        on_visualization_select     (Switch<NodeId>),
        some_visualisation_selected (bool),

        node_being_edited (Option<NodeId>),
        node_editing (bool),

        view_mode (view::Mode),

        navigator_active (bool),
        file_dropped     (ensogl_drop_manager::File,Vector2<f32>),

        default_x_gap_between_nodes (f32),
        default_y_gap_between_nodes (f32),
        min_x_spacing_for_new_nodes (f32),

        /// The selected execution mode.
        execution_mode (execution_mode_selector::ExecutionMode),
        /// A press of the execution mode selector play button.
        execution_mode_play_button_pressed (),
    }
}

impl FrpNetworkProvider for GraphEditor {
    fn network(&self) -> &frp::Network {
        &self.model.network
    }
}



// ============
// === Node ===
// ============

#[derive(Clone, CloneRef, Debug, Deref)]
#[allow(missing_docs)] // FIXME[everyone] Public-facing API should be documented.
pub struct Node {
    #[deref]
    pub view:      component::Node,
    pub in_edges:  SharedHashSet<EdgeId>,
    pub out_edges: SharedHashSet<EdgeId>,
}

#[derive(Clone, CloneRef, Copy, Debug, Default, Eq, From, Hash, Into, PartialEq, Ord, PartialOrd)]
#[allow(missing_docs)] // FIXME[everyone] Public-facing API should be documented.
pub struct NodeId(pub Id);

impl Node {
    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn new(view: component::Node) -> Self {
        let in_edges = default();
        let out_edges = default();
        Self { view, in_edges, out_edges }
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn id(&self) -> NodeId {
        self.view.id().into()
    }

    /// Return all edges connected to this node. Ingoing and outgoing both.
    pub fn all_edges(self) -> Vec<EdgeId> {
        self.in_edges.keys().extended(self.out_edges.keys())
    }
}

impl display::Object for Node {
    fn display_object(&self) -> &display::object::Instance {
        self.view.display_object()
    }
}

impl Display for NodeId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        Display::fmt(&self.0, f)
    }
}



// ============
// === Edge ===
// ============

#[derive(Clone, CloneRef, Debug, Deref)]
#[allow(missing_docs)] // FIXME[everyone] Public-facing API should be documented.
pub struct Edge {
    #[deref]
    pub view: component::Edge,
    source:   Rc<RefCell<Option<EdgeEndpoint>>>,
    target:   Rc<RefCell<Option<EdgeEndpoint>>>,
}

#[derive(Clone, CloneRef, Copy, Debug, Default, Eq, From, Hash, Into, PartialEq)]
#[allow(missing_docs)] // FIXME[everyone] Public-facing API should be documented.
pub struct EdgeId(pub Id);

impl Edge {
    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn new(view: component::Edge) -> Self {
        let source = default();
        let target = default();
        Self { view, source, target }
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn id(&self) -> EdgeId {
        self.view.id().into()
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn target(&self) -> Option<EdgeEndpoint> {
        self.target.borrow().as_ref().map(|t| t.clone_ref())
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn source(&self) -> Option<EdgeEndpoint> {
        self.source.borrow().as_ref().map(|t| t.clone_ref())
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn has_source(&self) -> bool {
        self.source.borrow().is_some()
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn has_target(&self) -> bool {
        self.target.borrow().is_some()
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn set_source(&self, source: EdgeEndpoint) {
        *self.source.borrow_mut() = Some(source)
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn set_target(&self, target: EdgeEndpoint) {
        *self.target.borrow_mut() = Some(target)
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn take_source(&self) -> Option<EdgeEndpoint> {
        mem::take(&mut *self.source.borrow_mut())
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn take_target(&self) -> Option<EdgeEndpoint> {
        mem::take(&mut *self.target.borrow_mut())
    }
}

impl display::Object for Edge {
    fn display_object(&self) -> &display::object::Instance {
        self.view.display_object()
    }
}

impl Display for EdgeId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        Display::fmt(&self.0, f)
    }
}



// ============
// === Type ===
// ============

/// Typename information that may be associated with the given Port.
///
/// `None` means that type for the port is unknown.
#[derive(Clone, Debug, Default, Eq, Hash, PartialEq)]
pub struct Type(pub ImString);

impl Deref for Type {
    type Target = ImString;
    fn deref(&self) -> &Self::Target {
        &self.0
    }
}

impl Type {
    /// Check whether this is any type, the most generic type in Enso. The empty string is
    /// considered to be an empty type as well.
    pub fn is_any(&self) -> bool {
        self.as_str() == "Any" || self.is_empty()
    }

    /// If the type consists of a single identifier then we remove all module qualifiers:
    /// ```
    /// use ide_view_graph_editor::*;
    ///
    /// let input = Type::from("Foo.Bar.Baz.Vector".to_string());
    /// let expectation = Type::from("Vector".to_string());
    /// assert_eq!(input.abbreviate(), expectation);
    /// ```
    ///
    /// If the type contains multiple identifiers then we just abbreviate the first one:
    /// ```
    /// use ide_view_graph_editor::*;
    ///
    /// let input = Type::from("Foo.Bar.Baz.Vector Math.Number".to_string());
    /// let expectation = Type::from("Vector Math.Number".to_string());
    /// assert_eq!(input.abbreviate(), expectation);
    /// ```
    pub fn abbreviate(&self) -> Type {
        if let Some(up_to_whitespace) = self.split_whitespace().next() {
            if let Some(last_dot_index) = up_to_whitespace.rfind('.') {
                Type::from(self[last_dot_index + 1..].to_string())
            } else {
                // `self` contains no dot. We do not need to abbreaviate it.
                self.clone()
            }
        } else {
            // `self` was empty.
            Type::from("".to_string())
        }
    }
}

impl From<String> for Type {
    fn from(s: String) -> Self {
        Type(s.into())
    }
}

impl Display for Type {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.0)
    }
}



// =============================
// === OptionalMethodPointer ===
// =============================

/// Information about target definition for node entering.
// TODO [mwu]
//  As currently there is no good place to wrap Rc into a newtype that can be easily depended on
//  both by `ide-view` and `ide` crates, we put this as-is. Refactoring should be considered in the
//  future, once code organization and emerging patterns are more clear.
#[derive(Clone, Debug, Deref, PartialEq, Eq)]
pub struct MethodPointer(pub Rc<engine_protocol::language_server::MethodPointer>);

impl From<engine_protocol::language_server::MethodPointer> for MethodPointer {
    fn from(method_pointer: engine_protocol::language_server::MethodPointer) -> Self {
        Self(Rc::new(method_pointer))
    }
}



// =================
// === LocalCall ===
// =================

/// A specific function call occurring within another function's definition body.
/// It's closely related to the `LocalCall` type defined in `Language Server` types, but uses the
/// new type `MethodPointer` defined in `GraphEditor`.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LocalCall {
    /// An expression being a call to a method.
    pub call:       engine_protocol::language_server::ExpressionId,
    /// A pointer to the called method.
    pub definition: MethodPointer,
}



// ====================
// === EdgeEndpoint ===
// ====================

#[derive(Clone, CloneRef, Debug, Default, Eq, PartialEq)]
#[allow(missing_docs)] // FIXME[everyone] Public-facing API should be documented.
pub struct EdgeEndpoint {
    pub node_id: NodeId,
    pub port:    span_tree::Crumbs,
}

impl EdgeEndpoint {
    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn new(node_id: impl Into<NodeId>, port: span_tree::Crumbs) -> Self {
        let node_id = node_id.into();
        Self { node_id, port }
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn is_connected_to(&self, node_id: NodeId) -> bool {
        self.node_id == node_id
    }
}



// ============
// === Grid ===
// ============

/// Defines a snapping grid for nodes. The grid implementation is currently very simple. For each
/// node, the grid records its position and allows querying for positions close to the recorded
/// ones.
#[derive(Debug, Clone, Default)]
pub struct Grid {
    sorted_xs: Vec<f32>,
    sorted_ys: Vec<f32>,
}

impl Grid {
    /// Query the grid for a close position to the provided using the provided threshold distance.
    pub fn close_to(&self, position: Vector2<f32>, threshold: f32) -> Vector2<Option<f32>> {
        let x = Self::axis_close_to(&self.sorted_xs, position.x, threshold);
        let y = Self::axis_close_to(&self.sorted_ys, position.y, threshold);
        Vector2(x, y)
    }

    fn axis_close_to(axis: &[f32], pos: f32, threshold: f32) -> Option<f32> {
        match axis.binary_search_by(|t| t.partial_cmp(&pos).unwrap()) {
            Ok(ix) => Some(axis[ix]),
            Err(ix) => {
                let max = axis.len();
                let left_pos = if ix == 0 { None } else { Some(axis[ix - 1]) };
                let right_pos = if ix == max { None } else { Some(axis[ix]) };
                let left_dist = left_pos.map(|t| (pos - t).abs());
                let right_dist = right_pos.map(|t| (pos - t).abs());
                let left_check = left_dist.map(|t| t < threshold).unwrap_or_default();
                let right_check = right_dist.map(|t| t < threshold).unwrap_or_default();
                match (left_check, right_check) {
                    (false, false) => None,
                    (true, false) => left_pos,
                    (false, true) => right_pos,
                    (true, true) => {
                        let left_dist = left_dist.unwrap_or_default();
                        let right_dist = right_dist.unwrap_or_default();
                        if left_dist < right_dist {
                            left_pos
                        } else {
                            right_pos
                        }
                    }
                }
            }
        }
    }
}



// =====================
// === WidgetUpdates ===
// =====================

/// A structure describing a widget update batch for arguments of single function call.
#[derive(Debug, Default, Clone)]
pub struct WidgetUpdates {
    /// The function call expression ID.
    pub call_id: ast::Id,
    /// Update of a widget for each function argument.
    pub updates: Rc<Vec<WidgetUpdate>>,
}

/// A structure describing a widget update for specific argument of a function call.
#[derive(Debug)]
pub struct WidgetUpdate {
    /// The function argument name that this widget is for.
    pub argument_name: String,
    /// Widget metadata queried from the language server.
    pub meta:          Option<node::input::widget::Metadata>,
}



// =============
// === Nodes ===
// =============

#[derive(Debug, Clone, CloneRef, Default)]
#[allow(missing_docs)] // FIXME[everyone] Public-facing API should be documented.
pub struct Nodes {
    pub all:      SharedHashMap<NodeId, Node>,
    pub selected: SharedVec<NodeId>,
    pub grid:     Rc<RefCell<Grid>>,
}

impl Deref for Nodes {
    type Target = SharedHashMap<NodeId, Node>;
    fn deref(&self) -> &Self::Target {
        &self.all
    }
}

impl Nodes {
    /// Constructor.
    pub fn new() -> Self {
        default()
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn insert(&self, node_id: NodeId, node: Node) {
        self.all.insert(node_id, node);
        self.recompute_grid(default());
    }

    /// Calculate a Magnet Alignment grid used for nodes alignment.
    ///
    /// A grid consists of:
    ///  - Horizontal lines through each node's Y coordinate.
    ///  - Vertical lines through each node's X coordinate.
    ///
    ///  `blacklist` nodes are excluded from the calculation.
    fn recompute_grid(&self, blacklist: HashSet<NodeId>) {
        let mut sorted_xs = Vec::new();
        let mut sorted_ys = Vec::new();
        for (id, node) in &*self.all.raw.borrow() {
            if !blacklist.contains(id) {
                let position = node.position();
                sorted_xs.push(position.x);
                sorted_ys.push(position.y);
            }
        }
        sorted_xs.sort_unstable_by(|a, b| a.partial_cmp(b).unwrap());
        sorted_ys.sort_unstable_by(|a, b| a.partial_cmp(b).unwrap());
        *self.grid.borrow_mut() = Grid { sorted_xs, sorted_ys };
    }

    /// Same as [`check_grid_magnet_with_threshold`], but with default threshold.
    pub fn check_grid_magnet(&self, position: Vector2<f32>) -> Vector2<Option<f32>> {
        self.check_grid_magnet_with_threshold(position, SNAP_DISTANCE_THRESHOLD)
    }

    /// Return the nearest point in a Magnet Alignment grid. Returns `None` if the nearest point's
    /// coordinate is further than a `threshold`.
    ///
    /// See [`recompute_grid`] docs for grid description.
    pub fn check_grid_magnet_with_threshold(
        &self,
        position: Vector2<f32>,
        threshold: f32,
    ) -> Vector2<Option<f32>> {
        self.grid.borrow().close_to(position, threshold)
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn set_quick_preview(&self, quick: bool) {
        self.all.raw.borrow().values().for_each(|node| node.view.quick_preview_vis.emit(quick))
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn show_quick_actions(&self, quick: bool) {
        self.all
            .raw
            .borrow()
            .values()
            .for_each(|node| node.view.show_quick_action_bar_on_hover.emit(quick))
    }
}


// === Node Selection ===

impl Nodes {
    /// Mark node as selected and send FRP event to node about its selection status.
    pub fn select(&self, node_id: impl Into<NodeId>) {
        let node_id = node_id.into();
        if let Some(node) = self.get_cloned_ref(&node_id) {
            // Remove previous instances and add new selection at end of the list, indicating that
            // this node was selected last, superseding the previous selection.
            while self.selected.contains(&node_id) {
                self.selected.remove_item(&node_id)
            }
            self.selected.push(node_id);
            node.select.emit(());
        }
    }

    /// Mark node as deselected and send FRP event to node about its selection status.
    pub fn deselect(&self, node_id: impl Into<NodeId>) {
        let node_id = node_id.into();
        if let Some(node) = self.get_cloned_ref(&node_id) {
            self.selected.remove_item(&node_id);
            node.deselect.emit(());
        }
    }

    /// Mark all node as selected and send FRP events to nodes.
    pub fn select_all(&self) {
        for id in self.all.keys() {
            self.select(id);
        }
    }

    /// Return all nodes marked as selected.
    pub fn all_selected(&self) -> Vec<NodeId> {
        self.selected.items()
    }

    /// Return the node that was marked as selected last.
    pub fn last_selected(&self) -> Option<NodeId> {
        self.selected.last_cloned()
    }

    /// Return whether the given node is marked as selected.
    pub fn is_selected(&self, node: NodeId) -> bool {
        self.selected.contains(&node)
    }

    /// Call `deselect` for all nodes marked as selected.
    pub fn deselect_all(&self) {
        let selected = self.selected.raw.as_ref().clone();
        selected.into_inner().into_iter().for_each(|node_id| self.deselect(node_id))
    }
}



// =============
// === Edges ===
// =============

#[derive(Debug, Clone, CloneRef, Default)]
#[allow(missing_docs)] // FIXME[everyone] Public-facing API should be documented.
pub struct Edges {
    pub all:             SharedHashMap<EdgeId, Edge>,
    pub detached_source: SharedHashSet<EdgeId>,
    pub detached_target: SharedHashSet<EdgeId>,
}

impl Deref for Edges {
    type Target = SharedHashMap<EdgeId, Edge>;
    fn deref(&self) -> &Self::Target {
        &self.all
    }
}

impl Edges {
    /// Constructor.
    pub fn new() -> Self {
        default()
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn insert(&self, edge: Edge) {
        self.all.insert(edge.id(), edge);
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn detached_edges_iter(&self) -> impl Iterator<Item = EdgeId> {
        let detached_target = self.detached_target.raw.borrow();
        let detached_source = self.detached_source.raw.borrow();
        let mut detached = detached_target.iter().copied().collect_vec();
        let detached_source_iter = detached_source.iter().copied();
        detached.extend(detached_source_iter);
        detached.into_iter()
    }
}



#[derive(Debug, Clone, CloneRef, Default)]
struct Visualisations {
    /// This keeps track of the currently selected visualisation. There should only ever be one
    /// visualisations selected, however due to the way that the selection is determined, it can
    /// happen that while the FRP is resolved, temporarily, we have multiple visualisation in this
    /// set. This happens because the selection status is determined bottom up from each
    /// visualisation and the reported via FRP to the graph editor. That means if the status
    /// we might see the new selection status for a visualisation getting set before we see the
    /// previously selected visualisation report its deselection. If we ever have more than one
    /// visualisation in this set after the status updates have been resolved, that is a bug.
    selected: SharedHashSet<NodeId>,
}



#[derive(Debug, CloneRef, Derivative)]
#[derivative(Clone(bound = ""))]
#[allow(missing_docs)] // FIXME[everyone] Public-facing API should be documented.
pub struct TouchNetwork<T: frp::Data> {
    pub down:     frp::Source<T>,
    pub up:       frp::Stream<T>,
    pub is_down:  frp::Stream<bool>,
    pub selected: frp::Stream<T>,
}

impl<T: frp::Data> TouchNetwork<T> {
    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn new(network: &frp::Network, mouse: &frp::io::Mouse_DEPRECATED) -> Self {
        frp::extend! { network
            down          <- source::<T> ();
            is_down       <- bool(&mouse.up_primary,&down);
            was_down      <- is_down.previous();
            mouse_up      <- mouse.up_primary.gate(&was_down);
            pos_on_down   <- mouse.position.sample(&down);
            pos_on_up     <- mouse.position.sample(&mouse_up);
            should_select <- pos_on_up.map3(&pos_on_down,&mouse.distance,Self::check);
            up            <- down.sample(&mouse_up);
            selected      <- up.gate(&should_select);
        }
        Self { down, up, is_down, selected }
    }

    #[allow(clippy::trivially_copy_pass_by_ref)]
    fn check(end: &Vector2, start: &Vector2, diff: &f32) -> bool {
        (end - start).norm() <= diff * 2.0
    }
}

#[derive(Debug, Clone, CloneRef)]
#[allow(missing_docs)] // FIXME[everyone] Public-facing API should be documented.
pub struct TouchState {
    pub nodes:      TouchNetwork<NodeId>,
    pub background: TouchNetwork<()>,
}

impl TouchState {
    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn new(network: &frp::Network, mouse: &frp::io::Mouse_DEPRECATED) -> Self {
        let nodes = TouchNetwork::<NodeId>::new(network, mouse);
        let background = TouchNetwork::<()>::new(network, mouse);
        Self { nodes, background }
    }
}



#[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
pub fn is_sub_crumb_of(src: &[span_tree::Crumb], tgt: &[span_tree::Crumb]) -> bool {
    if src.len() < tgt.len() {
        return false;
    }
    for (s, t) in src.iter().zip(tgt.iter()) {
        if s != t {
            return false;
        }
    }
    true
}

#[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
pub fn crumbs_overlap(src: &[span_tree::Crumb], tgt: &[span_tree::Crumb]) -> bool {
    is_sub_crumb_of(src, tgt) || is_sub_crumb_of(tgt, src)
}



// ===================================
// === GraphEditorModelWithNetwork ===
// ===================================

#[derive(Debug, Clone, CloneRef)]
#[allow(missing_docs)] // FIXME[everyone] Public-facing API should be documented.
pub struct GraphEditorModelWithNetwork {
    pub model:   GraphEditorModel,
    pub network: frp::Network,
}

impl Deref for GraphEditorModelWithNetwork {
    type Target = GraphEditorModel;
    fn deref(&self) -> &Self::Target {
        &self.model
    }
}


impl GraphEditorModelWithNetwork {
    /// Constructor.
    pub fn new(app: &Application, cursor: cursor::Cursor, frp: &Frp) -> Self {
        let network = frp.network().clone_ref(); // FIXME make weak
        let model = GraphEditorModel::new(app, cursor, frp);
        Self { model, network }
    }

    fn is_node_connected_at_input(&self, node_id: NodeId, crumbs: &span_tree::Crumbs) -> bool {
        if let Some(node) = self.nodes.get_cloned(&node_id) {
            for in_edge_id in node.in_edges.raw.borrow().iter() {
                if let Some(edge) = self.edges.get_cloned(in_edge_id) {
                    if let Some(target) = edge.target() {
                        if target.node_id == node_id && target.port == crumbs {
                            return true;
                        }
                    }
                }
            }
        }
        false
    }

    /// Return a position of the node with provided id.
    pub fn get_node_position(&self, node_id: NodeId) -> Option<Vector3<f32>> {
        self.nodes.get_cloned_ref(&node_id).map(|node| node.position())
    }

    fn create_edge(
        &self,
        edge_click: &frp::Source<EdgeId>,
        edge_over: &frp::Source<EdgeId>,
        edge_out: &frp::Source<EdgeId>,
    ) -> EdgeId {
        let edge = Edge::new(component::Edge::new(&self.app));
        let edge_id = edge.id();
        self.add_child(&edge);
        self.edges.insert(edge.clone_ref());
        let network = &self.network;
        frp::extend! { network
            eval_ edge.view.frp.shape_events.mouse_down_primary (edge_click.emit(edge_id));
            eval_ edge.view.frp.shape_events.mouse_over (edge_over.emit(edge_id));
            eval_ edge.view.frp.shape_events.mouse_out (edge_out.emit(edge_id));
        }
        edge_id
    }

    fn new_edge_from_output(
        &self,
        edge_click: &frp::Source<EdgeId>,
        edge_over: &frp::Source<EdgeId>,
        edge_out: &frp::Source<EdgeId>,
    ) -> EdgeId {
        let edge_id = self.create_edge(edge_click, edge_over, edge_out);
        let first_detached = self.edges.detached_target.is_empty();
        self.edges.detached_target.insert(edge_id);
        if first_detached {
            self.frp.private.output.on_some_edges_targets_unset.emit(());
        }
        edge_id
    }

    fn new_edge_from_input(
        &self,
        edge_click: &frp::Source<EdgeId>,
        edge_over: &frp::Source<EdgeId>,
        edge_out: &frp::Source<EdgeId>,
    ) -> EdgeId {
        let edge_id = self.create_edge(edge_click, edge_over, edge_out);
        let first_detached = self.edges.detached_source.is_empty();
        self.edges.detached_source.insert(edge_id);
        if first_detached {
            self.frp.private.output.on_some_edges_sources_unset.emit(());
        }
        edge_id
    }
}


// === Node Creation ===

/// Describes the way used to request creation of a new node.
#[derive(Clone, Debug)]
#[allow(missing_docs)]
pub enum WayOfCreatingNode {
    /// "add_node" FRP event was emitted.
    AddNodeEvent,
    /// "start_node_creation" FRP event was emitted.
    StartCreationEvent,
    /// "start_node_creation_from_port" FRP event was emitted.
    StartCreationFromPortEvent { endpoint: EdgeEndpoint },
    /// add_node_button was clicked.
    ClickingButton,
    /// The edge was dropped on the stage.
    DroppingEdge { edge_id: EdgeId },
}

impl Default for WayOfCreatingNode {
    fn default() -> Self {
        Self::AddNodeEvent
    }
}

/// Context data required to create a new node.
#[derive(Debug)]
struct NodeCreationContext<'a> {
    pointer_style: &'a frp::Any<cursor::Style>,
    output_press:  &'a frp::Source<EdgeEndpoint>,
    input_press:   &'a frp::Source<EdgeEndpoint>,
    output:        &'a api::private::Output,
}

impl GraphEditorModelWithNetwork {
    #[profile(Objective)]
    fn create_node(
        &self,
        ctx: &NodeCreationContext,
        way: &WayOfCreatingNode,
        mouse_position: Vector2,
    ) -> (NodeId, Option<NodeSource>, bool) {
        let position = new_node_position::new_node_position(self, way, mouse_position);
        let node = self.new_node(ctx);
        node.set_xy(position);
        let should_edit = !matches!(way, WayOfCreatingNode::AddNodeEvent);
        if should_edit {
            node.view.set_expression(node::Expression::default());
        }
        let source = self.data_source_for_new_node(way);
        (node.id(), source, should_edit)
    }

    fn data_source_for_new_node(&self, way: &WayOfCreatingNode) -> Option<NodeSource> {
        use WayOfCreatingNode::*;
        let source_node = match way {
            AddNodeEvent => None,
            StartCreationEvent | ClickingButton => self.nodes.selected.first_cloned(),
            DroppingEdge { edge_id } => self.edge_source_node_id(*edge_id),
            StartCreationFromPortEvent { endpoint } => Some(endpoint.node_id),
        };
        source_node.map(|node| NodeSource { node })
    }

    #[profile(Debug)]
    fn new_node(&self, ctx: &NodeCreationContext) -> Node {
        let view = component::Node::new(&self.app, self.vis_registry.clone_ref());
        let node = Node::new(view);
        let node_model = node.model();
        let node_network = &node.frp().network();
        let node_id = node.id();
        self.add_child(&node);

        let touch = &self.touch_state;
        let model = &self.model;
        let NodeCreationContext { pointer_style, output_press, input_press, output } = ctx;

        frp::new_bridge_network! { [self.network, node_network] graph_node_bridge
            eval_ node.background_press(touch.nodes.down.emit(node_id));

            hovered <- node.output.hover.map (move |t| Some(Switch::new(node_id,*t)));
            output.node_hovered <+ hovered;

            eval node.comment ([model](comment)
                model.frp.private.output.node_comment_set.emit((node_id,comment.clone()))
            );

            node.set_output_expression_visibility <+ self.frp.nodes_labels_visible;

            pointer_style <+ node_model.input.frp.pointer_style;

            eval node_model.output.frp.on_port_press ([output_press](crumbs){
                let target = EdgeEndpoint::new(node_id,crumbs.clone());
                output_press.emit(target);
            });

            eval node_model.input.frp.on_port_press ([input_press](crumbs)
                let target = EdgeEndpoint::new(node_id,crumbs.clone());
                input_press.emit(target);
            );

            eval node_model.input.frp.on_port_hover ([model](t) {
                let crumbs = t.on();
                let target = crumbs.map(|c| EdgeEndpoint::new(node_id,c.clone()));
                model.frp.private.output.hover_node_input.emit(target);
            });

            eval node_model.output.frp.on_port_hover ([model](hover) {
               let output = hover.on().map(|crumbs| EdgeEndpoint::new(node_id,crumbs.clone()));
               model.frp.private.output.hover_node_output.emit(output);
            });

            let neutral_color = model.styles_frp.get_color(theme::code::types::any::selection);

            _eval <- all_with(&node_model.input.frp.on_port_type_change,&neutral_color,
                f!(((crumbs,_),neutral_color)
                    model.with_input_edge_id(node_id,crumbs,|id|
                        model.refresh_edge_color(id,neutral_color.into())
                    )
                ));

            _eval <- all_with(&node_model.input.frp.on_port_type_change,&neutral_color,
                f!(((crumbs,_),neutral_color)
                    model.with_output_edge_id(node_id,crumbs,|id|
                        model.refresh_edge_color(id,neutral_color.into())
                    )
                ));

            let is_editing = &node_model.input.frp.editing;
            expression_change_temporary <- node.on_expression_modified.gate(is_editing);
            expression_change_permanent <- node.on_expression_modified.gate_not(is_editing);

            temporary_expression <- expression_change_temporary.map2(
                &node_model.input.set_expression,
                move |(crumbs, code), expr| expr.code_with_replaced_span(crumbs, code)
            );
            eval temporary_expression([model] (code) {
                model.frp.private.output.node_expression_set.emit((node_id, code));
            });
            eval expression_change_permanent([model]((crumbs,code)) {
                let args = (node_id, crumbs.clone(), code.clone());
                model.frp.private.output.node_expression_span_set.emit(args)
            });

            eval node.requested_widgets([model]((call_id, target_id)) {
                let args = (node_id, *call_id, *target_id);
                model.frp.private.output.widgets_requested.emit(args)
            });

            let node_expression_edit = node.model().input.expression_edit.clone_ref();
            model.frp.private.output.node_expression_edited <+ node_expression_edit.map(
                move |(expr, selection)| (node_id, expr.clone_ref(), selection.clone())
            );
            model.frp.private.output.request_import <+ node.request_import;


            // === Actions ===

            model.frp.private.output.node_action_context_switch <+ node.view.context_switch.map(
                f!([] (active) (node_id, *active))
            );

            eval node.view.freeze ((is_frozen) {
                model.frp.private.output.node_action_freeze.emit((node_id,*is_frozen));
            });

            let set_node_disabled = &node.set_disabled;
            eval node.view.skip ([set_node_disabled,model](is_skipped) {
                model.frp.private.output.node_action_skip.emit((node_id,*is_skipped));
                set_node_disabled.emit(is_skipped);
            });


            // === Visualizations ===

            visualization_shown  <- node.visualization_visible.gate(&node.visualization_visible);
            visualization_hidden <- node.visualization_visible.gate_not(&node.visualization_visible);

            let vis_is_selected = node_model.visualization.frp.is_selected.clone_ref();

            selected    <- vis_is_selected.on_true();
            deselected  <- vis_is_selected.on_false();
            output.on_visualization_select <+ selected.constant(Switch::On(node_id));
            output.on_visualization_select <+ deselected.constant(Switch::Off(node_id));

            preprocessor_changed <-
                node_model.visualization.frp.preprocessor.map(move |preprocessor| {
                    (node_id,preprocessor.clone())
                });
            output.visualization_preprocessor_changed <+ preprocessor_changed.gate(&node.visualization_visible);


            metadata <- any(...);
            metadata <+ node_model.visualization.frp.preprocessor.map(visualization::Metadata::new);

            // Ensure the graph editor knows about internal changes to the visualisation. If the
            // visualisation changes that should indicate that the old one has been disabled and a
            // new one has been enabled.
            // TODO: Create a better API for updating the controller about visualisation changes
            // (see #896)
            output.visualization_hidden <+ visualization_hidden.constant(node_id);
            output.visualization_shown  <+
                visualization_shown.map2(&metadata,move |_,metadata| (node_id,metadata.clone()));


            init <- source::<()>();
            enabled_visualization_path <- init.all_with3(
                &node.visualization_enabled, &node.visualization_path,
                move |_init, is_enabled, path| (node_id, is_enabled.and_option(path.clone()))
            );
            output.enabled_visualization_path <+ enabled_visualization_path;


            // === View Mode ===

            node.set_view_mode <+ self.model.frp.view_mode;


            // === Profiling ===

            let profiling_min_duration              = &self.model.profiling_statuses.min_duration;
            node.set_profiling_min_global_duration <+ self.model.profiling_statuses.min_duration;
            node.set_profiling_min_global_duration(profiling_min_duration.value());
            let profiling_max_duration              = &self.model.profiling_statuses.max_duration;
            node.set_profiling_max_global_duration <+ self.model.profiling_statuses.max_duration;
            node.set_profiling_max_global_duration(profiling_max_duration.value());


            // === Execution Environment ===

            node.set_execution_environment <+ self.model.frp.set_execution_environment;
        }


        // === Panning camera to created node ===

        // Node position and bounding box are not available immediately after the node is created,
        // but only after the Node's display object is updated. Therefore, in order to pan the
        // camera to the bounding box of a newly created node, we need to wait until:
        //  1. the position of the newly created node becomes updated, and then
        //  2. the bounding box of the node becomes updated.
        // When the sequence is detected, and if the node is being edited, we pan the camera to it.
        // Regardless whether the node is being edited, we drop the network, as we don't want to
        // pan the camera for any later updates of the bounding box.
        let pan_network = frp::Network::new("network_for_camera_pan_to_new_node");
        let pan_network_container = RefCell::new(Some(pan_network.clone()));
        frp::new_bridge_network! { [self.network, node_network, pan_network] graph_node_pan_bridge
            pos_updated <- node.output.position.constant(true);
            bbox_updated_after_pos_updated <- node.output.bounding_box.gate(&pos_updated);
            let node_being_edited = &self.frp.node_being_edited;
            _eval <- bbox_updated_after_pos_updated.map2(node_being_edited, f!([model](_, node) {
                pan_network_container.replace(None);
                if *node == Some(node_id) {
                    model.pan_camera_to_node(node_id);
                }
            }));
        }

        node.set_view_mode(self.model.frp.view_mode.value());
        let initial_metadata = visualization::Metadata {
            preprocessor: node_model.visualization.frp.preprocessor.value(),
        };
        metadata.emit(initial_metadata);
        init.emit(());
        self.nodes.insert(node_id, node.clone_ref());
        node
    }
}



// ========================
// === GraphEditorModel ===
// ========================

#[derive(Debug, Clone, CloneRef)]
#[allow(missing_docs)] // FIXME[everyone] Public-facing API should be documented.
pub struct GraphEditorModel {
    pub display_object:      display::object::Instance,
    pub app:                 Application,
    pub breadcrumbs:         component::Breadcrumbs,
    pub cursor:              cursor::Cursor,
    pub nodes:               Nodes,
    pub edges:               Edges,
    pub vis_registry:        visualization::Registry,
    pub drop_manager:        ensogl_drop_manager::Manager,
    pub navigator:           Navigator,
    pub add_node_button:     Rc<component::add_node_button::AddNodeButton>,
    tooltip:                 Tooltip,
    touch_state:             TouchState,
    visualisations:          Visualisations,
    frp:                     Frp,
    profiling_statuses:      profiling::Statuses,
    profiling_button:        component::profiling::Button,
    styles_frp:              StyleWatchFrp,
    selection_controller:    selection::Controller,
    execution_mode_selector: execution_mode_selector::ExecutionModeSelector,
}


// === Public ===

impl GraphEditorModel {
    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn new(app: &Application, cursor: cursor::Cursor, frp: &Frp) -> Self {
        let network = frp.network();
        let scene = &app.display.default_scene;
        let display_object = display::object::Instance::new();
        let nodes = Nodes::new();
        let edges = Edges::new();
        let vis_registry = visualization::Registry::with_default_visualizations();
        let visualisations = default();
        let touch_state = TouchState::new(network, &scene.mouse.frp_deprecated);
        let breadcrumbs = component::Breadcrumbs::new(app.clone_ref());
        let execution_mode_selector = execution_mode_selector::ExecutionModeSelector::new(app);

        let app = app.clone_ref();
        let frp = frp.clone_ref();
        let navigator = Navigator::new(scene, &scene.camera());
        let tooltip = Tooltip::new(&app);
        let profiling_statuses = profiling::Statuses::new();
        let profiling_button = component::profiling::Button::new(&app);
        let add_node_button = Rc::new(component::add_node_button::AddNodeButton::new(&app));
        let drop_manager =
            ensogl_drop_manager::Manager::new(&scene.dom.root.clone_ref().into(), scene);
        let styles_frp = StyleWatchFrp::new(&scene.style_sheet);
        let selection_controller = selection::Controller::new(
            &frp,
            &app.cursor,
            &scene.mouse.frp_deprecated,
            &touch_state,
            &nodes,
        );

        Self {
            display_object,
            app,
            breadcrumbs,
            cursor,
            nodes,
            edges,
            vis_registry,
            drop_manager,
            tooltip,
            touch_state,
            visualisations,
            frp,
            navigator,
            profiling_statuses,
            profiling_button,
            add_node_button,
            styles_frp,
            selection_controller,
            execution_mode_selector,
        }
        .init()
    }

    fn init(self) -> Self {
        let x_offset = MACOS_TRAFFIC_LIGHTS_SIDE_OFFSET;

        self.add_child(&self.execution_mode_selector);

        self.add_child(&self.breadcrumbs);
        self.breadcrumbs.set_x(x_offset);

        self.scene().add_child(&self.tooltip);
        self.add_child(&self.profiling_button);
        self.add_child(&*self.add_node_button);
        self
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn all_nodes(&self) -> Vec<NodeId> {
        self.nodes.all.keys()
    }

    fn scene(&self) -> &Scene {
        &self.app.display.default_scene
    }
}


// === Add node ===
impl GraphEditorModel {
    /// Create a new node and return a unique identifier.
    pub fn add_node(&self) -> NodeId {
        self.frp.add_node.emit(());
        let (node_id, _, _) = self.frp.node_added.value();
        node_id
    }

    /// Create a new node and place it at a free place below `above` node.
    pub fn add_node_below(&self, above: NodeId) -> NodeId {
        let pos = new_node_position::under(self, above);
        self.add_node_at(pos)
    }

    /// Create a new node and place it at `pos`.
    pub fn add_node_at(&self, pos: Vector2) -> NodeId {
        let node_id = self.add_node();
        self.frp.set_node_position((node_id, pos));
        node_id
    }
}


// === Remove ===

impl GraphEditorModel {
    fn remove_edge<E: Into<EdgeId>>(&self, edge_id: E) {
        let edge_id = edge_id.into();
        if let Some(edge) = self.edges.remove(&edge_id) {
            if let Some(source) = edge.take_source() {
                if let Some(source_node) = self.nodes.get_cloned_ref(&source.node_id) {
                    source_node.out_edges.remove(&edge_id);
                }
            }

            if let Some(target) = edge.take_target() {
                self.set_input_connected(&target, None, false); // FIXME None
                if let Some(target_node) = self.nodes.get_cloned_ref(&target.node_id) {
                    target_node.in_edges.remove(&edge_id);
                }
            }
        }
    }

    fn set_input_connected(&self, target: &EdgeEndpoint, tp: Option<Type>, status: bool) {
        if let Some(node) = self.nodes.get_cloned(&target.node_id) {
            node.view.set_input_connected(&target.port, tp, status);
        }
    }

    fn set_edge_target_connection_status(&self, edge_id: EdgeId, status: bool) {
        self.with_edge_target(edge_id, |tgt| {
            self.set_endpoint_connection_status(edge_id, &tgt, status)
        });
    }

    fn set_endpoint_connection_status(&self, edge_id: EdgeId, target: &EdgeEndpoint, status: bool) {
        let tp = self.edge_source_type(edge_id);
        self.set_input_connected(target, tp, status);
    }

    fn enable_visualization(&self, node_id: impl Into<NodeId>) {
        let node_id = node_id.into();
        if let Some(node) = self.nodes.get_cloned_ref(&node_id) {
            node.enable_visualization();
        }
    }

    fn disable_visualization(&self, node_id: impl Into<NodeId>) {
        let node_id = node_id.into();
        if let Some(node) = self.nodes.get_cloned_ref(&node_id) {
            node.disable_visualization();
        }
    }

    fn enable_visualization_fullscreen(&self, node_id: impl Into<NodeId>) {
        let node_id = node_id.into();
        if let Some(node) = self.nodes.get_cloned_ref(&node_id) {
            node.model().visualization.frp.enable_fullscreen.emit(());
        }
    }

    fn disable_visualization_fullscreen(&self, node_id: impl Into<NodeId>) {
        let node_id = node_id.into();
        if let Some(node) = self.nodes.get_cloned_ref(&node_id) {
            node.model().visualization.frp.disable_fullscreen.emit(());
        }
    }

    /// Get the visualization on the node, if it is enabled.
    pub fn enabled_visualization(
        &self,
        node_id: impl Into<NodeId>,
    ) -> Option<visualization::Metadata> {
        let node = self.nodes.all.get_cloned_ref(&node_id.into())?;
        let frp = &node.model().visualization.frp;
        frp.visible.value().then(|| visualization::Metadata::new(&frp.preprocessor.value()))
    }

    /// Warning! This function does not remove connected edges. It needs to be handled by the
    /// implementation.
    fn remove_node(&self, node_id: impl Into<NodeId>) {
        let node_id = node_id.into();
        self.nodes.remove(&node_id);
        self.nodes.selected.remove_item(&node_id);
        self.frp.private.output.on_visualization_select.emit(Switch::Off(node_id));
    }

    fn node_in_edges(&self, node_id: impl Into<NodeId>) -> Vec<EdgeId> {
        let node_id = node_id.into();
        self.nodes.get_cloned_ref(&node_id).map(|node| node.in_edges.keys()).unwrap_or_default()
    }

    fn node_out_edges(&self, node_id: impl Into<NodeId>) -> Vec<EdgeId> {
        let node_id = node_id.into();
        self.nodes.get_cloned_ref(&node_id).map(|node| node.out_edges.keys()).unwrap_or_default()
    }

    fn node_in_and_out_edges(&self, node_id: impl Into<NodeId>) -> Vec<EdgeId> {
        let node_id = node_id.into();
        let mut edges = self.node_in_edges(node_id);
        edges.extend(&self.node_out_edges(node_id));
        edges
    }

    #[profile(Detail)]
    fn set_node_expression(&self, node_id: impl Into<NodeId>, expr: impl Into<node::Expression>) {
        let node_id = node_id.into();
        let expr = expr.into();
        if let Some(node) = self.nodes.get_cloned_ref(&node_id) {
            node.set_expression.emit(expr);
        }
        for edge_id in self.node_out_edges(node_id) {
            self.refresh_edge_source_size(edge_id);
        }
    }

    fn edit_node_expression(
        &self,
        node_id: impl Into<NodeId>,
        range: impl Into<text::Range<text::Byte>>,
        inserted_str: impl Into<ImString>,
    ) {
        let node_id = node_id.into();
        let range = range.into();
        let inserted_str = inserted_str.into();
        if let Some(node) = self.nodes.get_cloned_ref(&node_id) {
            node.edit_expression(range, inserted_str);
        }
    }

    fn set_node_skip(&self, node_id: impl Into<NodeId>, skip: &bool) {
        let node_id = node_id.into();
        if let Some(node) = self.nodes.get_cloned_ref(&node_id) {
            node.set_skip_macro(*skip);
        }
    }

    fn set_node_freeze(&self, node_id: impl Into<NodeId>, freeze: &bool) {
        let node_id = node_id.into();
        if let Some(node) = self.nodes.get_cloned_ref(&node_id) {
            node.set_freeze_macro(*freeze);
        }
    }

    fn set_node_context_switch(&self, node_id: impl Into<NodeId>, context_switch: &Option<bool>) {
        let node_id = node_id.into();
        if let Some(node) = self.nodes.get_cloned_ref(&node_id) {
            node.set_context_switch(*context_switch);
        }
    }

    fn set_node_comment(&self, node_id: impl Into<NodeId>, comment: impl Into<node::Comment>) {
        let node_id = node_id.into();
        let comment = comment.into();
        if let Some(node) = self.nodes.get_cloned_ref(&node_id) {
            node.set_comment.emit(comment);
        }
    }

    fn is_connection(&self, edge_id: impl Into<EdgeId>) -> bool {
        let edge_id = edge_id.into();
        match self.edges.get_cloned_ref(&edge_id) {
            None => false,
            Some(e) => e.has_source() && e.has_target(),
        }
    }
}


// === Connect ===

impl GraphEditorModel {
    fn edge_source_node_id(&self, edge_id: EdgeId) -> Option<NodeId> {
        let edge = self.edges.get_cloned_ref(&edge_id)?;
        let endpoint = edge.source()?;
        Some(endpoint.node_id)
    }

    fn set_edge_source(&self, edge_id: EdgeId, target: impl Into<EdgeEndpoint>) {
        let target = target.into();
        if let Some(edge) = self.edges.get_cloned_ref(&edge_id) {
            if let Some(node) = self.nodes.get_cloned_ref(&target.node_id) {
                node.out_edges.insert(edge_id);
                edge.set_source(target);
                edge.view.frp.source_attached.emit(true);
                // FIXME: both lines require edge to refresh. Let's make it more efficient.
                self.refresh_edge_position(edge_id);
                self.refresh_edge_source_size(edge_id);
            }
        }
    }

    fn remove_edge_source(&self, edge_id: EdgeId) {
        if let Some(edge) = self.edges.get_cloned_ref(&edge_id) {
            if let Some(source) = edge.take_source() {
                if let Some(node) = self.nodes.get_cloned_ref(&source.node_id) {
                    node.out_edges.remove(&edge_id);
                    edge.view.frp.source_attached.emit(false);
                    let first_detached = self.edges.detached_source.is_empty();
                    self.edges.detached_source.insert(edge_id);
                    // FIXME: both lines require edge to refresh. Let's make it more efficient.
                    self.refresh_edge_position(edge_id);
                    self.refresh_edge_source_size(edge_id);
                    if first_detached {
                        self.frp.private.output.on_some_edges_sources_unset.emit(());
                    }
                }
            }
        }
    }

    fn set_edge_target(&self, edge_id: EdgeId, target: impl Into<EdgeEndpoint>) {
        let target = target.into();
        if let Some(edge) = self.edges.get_cloned_ref(&edge_id) {
            if let Some(node) = self.nodes.get_cloned_ref(&target.node_id) {
                node.in_edges.insert(edge_id);
                edge.set_target(target);

                self.edges.detached_target.remove(&edge_id);
                let all_attached = self.edges.detached_target.is_empty();
                if all_attached {
                    self.frp.private.output.on_all_edges_targets_set.emit(());
                }

                edge.view.frp.target_attached.emit(true);
                edge.view.frp.redraw.emit(());
                self.refresh_edge_position(edge_id);
            };
        }
    }

    fn remove_edge_target(&self, edge_id: EdgeId) {
        if let Some(edge) = self.edges.get_cloned_ref(&edge_id) {
            if let Some(target) = edge.take_target() {
                if let Some(node) = self.nodes.get_cloned_ref(&target.node_id) {
                    node.in_edges.remove(&edge_id);
                    let first_detached = self.edges.detached_target.is_empty();
                    self.edges.detached_target.insert(edge_id);
                    edge.view.frp.target_attached.emit(false);
                    self.refresh_edge_position(edge_id);
                    if first_detached {
                        self.frp.private.output.on_some_edges_targets_unset.emit(());
                    }
                };
            }
        }
    }

    fn take_edges_with_detached_targets(&self) -> HashSet<EdgeId> {
        let edges = self.edges.detached_target.mem_take();
        self.check_edge_attachment_status_and_emit_events();
        edges
    }

    fn take_edges_with_detached_sources(&self) -> HashSet<EdgeId> {
        let edges = self.edges.detached_source.mem_take();
        self.check_edge_attachment_status_and_emit_events();
        edges
    }

    fn edges_with_detached_targets(&self) -> HashSet<EdgeId> {
        self.edges.detached_target.raw.borrow().clone()
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn clear_all_detached_edges(&self) -> Vec<EdgeId> {
        let source_edges = self.edges.detached_source.mem_take();
        source_edges.iter().for_each(|edge| {
            self.edges.all.remove(edge);
        });
        let target_edges = self.edges.detached_target.mem_take();
        target_edges.iter().for_each(|edge| {
            self.edges.all.remove(edge);
        });
        self.check_edge_attachment_status_and_emit_events();
        source_edges.into_iter().chain(target_edges).collect()
    }

    fn check_edge_attachment_status_and_emit_events(&self) {
        let no_detached_sources = self.edges.detached_source.is_empty();
        let no_detached_targets = self.edges.detached_target.is_empty();
        if no_detached_targets {
            self.frp.private.output.on_all_edges_targets_set.emit(());
        }
        if no_detached_sources {
            self.frp.private.output.on_all_edges_sources_set.emit(());
        }
    }

    fn overlapping_edges(&self, target: &EdgeEndpoint) -> Vec<EdgeId> {
        let mut overlapping = vec![];
        if let Some(node) = self.nodes.get_cloned_ref(&target.node_id) {
            for edge_id in node.in_edges.raw.borrow().clone().into_iter() {
                if let Some(edge) = self.edges.get_cloned_ref(&edge_id) {
                    if let Some(edge_target) = edge.target() {
                        if crumbs_overlap(&edge_target.port, &target.port) {
                            overlapping.push(edge_id);
                        }
                    }
                }
            }
        }
        overlapping
    }

    fn set_edge_freeze<T: Into<EdgeId>>(&self, edge_id: T, is_frozen: bool) {
        let edge_id = edge_id.into();
        if let Some(edge) = self.edges.get_cloned_ref(&edge_id) {
            edge.view.frp.set_disabled.emit(is_frozen);
        }
    }
}


// === Position ===

impl GraphEditorModel {
    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn set_node_position(&self, node_id: impl Into<NodeId>, position: Vector2) {
        let node_id = node_id.into();
        if let Some(node) = self.nodes.get_cloned_ref(&node_id) {
            node.modify_position(|t| {
                t.x = position.x;
                t.y = position.y;
            });
            for edge_id in self.node_in_and_out_edges(node_id) {
                self.refresh_edge_position(edge_id);
            }
        }
    }

    #[profile(Debug)]
    fn set_node_expression_usage_type(
        &self,
        node_id: impl Into<NodeId>,
        ast_id: ast::Id,
        maybe_type: Option<Type>,
    ) {
        let node_id = node_id.into();
        if let Some(node) = self.nodes.get_cloned_ref(&node_id) {
            if node.view.model().output.whole_expr_id().contains(&ast_id) {
                // TODO[ao]: we must update root output port according to the whole expression type
                //     due to a bug in engine https://github.com/enso-org/enso/issues/1038.
                let crumbs = span_tree::Crumbs::default();
                node.view.model().output.set_expression_usage_type(crumbs, maybe_type.clone());
                let enso_type = maybe_type.as_ref().map(|tp| enso::Type::new(&tp.0));
                node.view.model().visualization.frp.set_vis_input_type(enso_type);
            }
            let crumbs = node.view.model().get_crumbs_by_id(ast_id);
            if let Some(crumbs) = crumbs {
                node.view.set_expression_usage_type.emit((crumbs, maybe_type));
            }
        }
    }

    fn update_node_widgets(&self, node_id: NodeId, updates: &WidgetUpdates) {
        if let Some(node) = self.nodes.get_cloned_ref(&node_id) {
            node.view.update_widgets.emit(updates.clone());
        }
    }

    fn disable_grid_snapping_for(&self, node_ids: &[NodeId]) {
        self.nodes.recompute_grid(node_ids.iter().cloned().collect());
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn node_position(&self, node_id: impl Into<NodeId>) -> Vector2<f32> {
        let node_id = node_id.into();
        self.nodes.get_cloned_ref(&node_id).map(|node| node.position().xy()).unwrap_or_default()
    }

    /// Return the bounding box of the node identified by `node_id`, or a default bounding box if
    /// the node was not found.
    pub fn node_bounding_box(&self, node_id: impl Into<NodeId>) -> selection::BoundingBox {
        let node_id = node_id.into();
        let node = self.nodes.get_cloned_ref(&node_id);
        node.map(|node| node.bounding_box.value()).unwrap_or_default()
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn node_pos_mod(&self, node_id: impl Into<NodeId>, pos_diff: Vector2) -> (NodeId, Vector2) {
        let node_id = node_id.into();
        let new_position = if let Some(node) = self.nodes.get_cloned_ref(&node_id) {
            node.position().xy() + pos_diff
        } else {
            default()
        };
        (node_id, new_position)
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn refresh_edge_position(&self, edge_id: EdgeId) {
        self.refresh_edge_source_position(edge_id);
        self.refresh_edge_target_position(edge_id);
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn refresh_edge_source_size(&self, edge_id: EdgeId) {
        if let Some(edge) = self.edges.get_cloned_ref(&edge_id) {
            if let Some(edge_source) = edge.source() {
                if let Some(node) = self.nodes.get_cloned_ref(&edge_source.node_id) {
                    edge.view.frp.source_width.emit(node.model().width());
                    edge.view.frp.source_height.emit(node.model().height());
                    edge.view.frp.redraw.emit(());
                }
            }
        };
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn refresh_edge_color(&self, edge_id: EdgeId, neutral_color: color::Lcha) {
        if let Some(edge) = self.edges.get_cloned_ref(&edge_id) {
            let color = self.edge_color(edge_id, neutral_color);
            edge.view.frp.set_color.emit(color);
        };
    }

    fn refresh_all_edge_colors(&self, neutral_color: color::Lcha) {
        for edge_id in self.edges.keys() {
            self.refresh_edge_color(edge_id, neutral_color);
        }
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn refresh_edge_source_position(&self, edge_id: EdgeId) {
        if let Some(edge) = self.edges.get_cloned_ref(&edge_id) {
            if let Some(edge_source) = edge.source() {
                if let Some(node) = self.nodes.get_cloned_ref(&edge_source.node_id) {
                    edge.modify_position(|p| {
                        p.x = node.position().x + node.model().width() / 2.0;
                        p.y = node.position().y;
                    });
                }
            }
        };
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn refresh_edge_target_position(&self, edge_id: EdgeId) {
        if let Some(edge) = self.edges.get_cloned_ref(&edge_id) {
            if let Some(edge_target) = edge.target() {
                if let Some(node) = self.nodes.get_cloned_ref(&edge_target.node_id) {
                    let offset =
                        node.model().input.port_offset(&edge_target.port).unwrap_or_default();
                    let pos = node.position().xy() + offset;
                    edge.view.frp.target_position.emit(pos);
                    edge.view.frp.redraw.emit(());
                }
            }
        };
    }

    fn map_node<T>(&self, id: NodeId, f: impl FnOnce(Node) -> T) -> Option<T> {
        self.nodes.get_cloned_ref(&id).map(f)
    }

    fn map_edge<T>(&self, id: EdgeId, f: impl FnOnce(Edge) -> T) -> Option<T> {
        self.edges.get_cloned_ref(&id).map(f)
    }

    fn with_node<T>(&self, id: NodeId, f: impl FnOnce(Node) -> T) -> Option<T> {
        let out = self.map_node(id, f);
        out.map_none(|| warn!("Trying to access nonexistent node '{id}'"))
    }

    fn with_edge<T>(&self, id: EdgeId, f: impl FnOnce(Edge) -> T) -> Option<T> {
        let out = self.map_edge(id, f);
        out.map_none(|| warn!("Trying to access nonexistent edge '{id}'"))
    }

    fn with_edge_map_source<T>(&self, id: EdgeId, f: impl FnOnce(EdgeEndpoint) -> T) -> Option<T> {
        self.with_edge(id, |edge| {
            let edge = edge.source.borrow().deref().clone();
            edge.map(f)
        })
        .flatten()
    }

    fn with_edge_map_target<T>(&self, id: EdgeId, f: impl FnOnce(EdgeEndpoint) -> T) -> Option<T> {
        self.with_edge(id, |edge| edge.target.borrow().clone().map(f)).flatten()
    }

    fn edge_source(&self, id: EdgeId) -> Option<EdgeEndpoint> {
        self.with_edge_map_source(id, |endpoint| endpoint)
    }

    fn edge_target(&self, id: EdgeId) -> Option<EdgeEndpoint> {
        self.with_edge_map_target(id, |endpoint| endpoint)
    }

    // FIXME[WD]: This implementation is slow. Node should allow for easy mapping between Crumbs
    //            and edges. Should be part of https://github.com/enso-org/ide/issues/822.
    fn with_input_edge_id<T>(
        &self,
        id: NodeId,
        crumbs: &span_tree::Crumbs,
        f: impl FnOnce(EdgeId) -> T,
    ) -> Option<T> {
        self.with_node(id, move |node| {
            let mut target_edge_id = None;
            for edge_id in node.in_edges.keys() {
                self.with_edge(edge_id, |edge| {
                    let ok = edge.target().map(|tgt| tgt.port == crumbs) == Some(true);
                    if ok {
                        target_edge_id = Some(edge_id)
                    }
                });
            }
            target_edge_id.map(f)
        })
        .flatten()
    }

    // FIXME[WD]: This implementation is slow. Node should allow for easy mapping between Crumbs
    //            and edges. Should be part of https://github.com/enso-org/ide/issues/822.
    fn with_output_edge_id<T>(
        &self,
        id: NodeId,
        crumbs: &span_tree::Crumbs,
        f: impl FnOnce(EdgeId) -> T,
    ) -> Option<T> {
        self.with_node(id, move |node| {
            let mut target_edge_id = None;
            for edge_id in node.out_edges.keys() {
                self.with_edge(edge_id, |edge| {
                    let ok = edge.target().map(|tgt| tgt.port == crumbs) == Some(true);
                    if ok {
                        target_edge_id = Some(edge_id)
                    }
                });
            }
            target_edge_id.map(f)
        })
        .flatten()
    }

    fn with_edge_source<T>(&self, id: EdgeId, f: impl FnOnce(EdgeEndpoint) -> T) -> Option<T> {
        self.with_edge(id, |edge| {
            let source = edge.source.borrow().deref().clone();
            source
                .map(f)
                .map_none(|| warn!("Trying to access nonexistent source of the edge {id}."))
        })
        .flatten()
    }

    fn with_edge_target<T>(&self, id: EdgeId, f: impl FnOnce(EdgeEndpoint) -> T) -> Option<T> {
        self.with_edge(id, |edge| {
            let target = edge.target.borrow().deref().clone();
            target
                .map(f)
                .map_none(|| warn!("Trying to access nonexistent target of the edge {id}."))
        })
        .flatten()
    }

    fn with_edge_map_source_node<T>(
        &self,
        edge_id: EdgeId,
        f: impl FnOnce(Node, span_tree::Crumbs) -> T,
    ) -> Option<T> {
        self.with_edge_map_source(edge_id, |t| self.map_node(t.node_id, |node| f(node, t.port)))
            .flatten()
    }

    fn with_edge_map_target_node<T>(
        &self,
        edge_id: EdgeId,
        f: impl FnOnce(Node, span_tree::Crumbs) -> T,
    ) -> Option<T> {
        self.with_edge_map_target(edge_id, |t| self.map_node(t.node_id, |node| f(node, t.port)))
            .flatten()
    }

    fn edge_source_type(&self, edge_id: EdgeId) -> Option<Type> {
        self.with_edge_map_source_node(edge_id, |n, c| n.model().output.port_type(&c)).flatten()
    }

    fn edge_target_type(&self, edge_id: EdgeId) -> Option<Type> {
        self.with_edge_map_target_node(edge_id, |n, c| n.model().input.port_type(&c)).flatten()
    }

    fn edge_hover_type(&self) -> Option<Type> {
        let hover_tgt = self.frp.hover_node_input.value();
        hover_tgt.and_then(|tgt| {
            self.with_node(tgt.node_id, |node| node.model().input.port_type(&tgt.port)).flatten()
        })
    }

    /// Return a color for the edge.
    ///
    /// In profiling mode, this is just a neutral gray.
    ///
    /// In normal mode, the algorithm works as follow:
    /// 1. We query the type of the currently hovered port, if any.
    /// 2. In case the previous point returns None, we query the edge target type, if any.
    /// 3. In case the previous point returns None, we query the edge source type, if any.
    /// 4. In case the previous point returns None, we use the generic type (gray color).
    ///
    /// This might need to be more sophisticated in the case of polymorphic types. For example,
    /// consider the edge source type to be `(a,Number)`, and target to be `(Text,a)`. These unify
    /// to `(Text,Number)`.
    fn edge_color(&self, edge_id: EdgeId, neutral_color: color::Lcha) -> color::Lcha {
        // FIXME : StyleWatch is unsuitable here, as it was designed as an internal tool for shape
        // system (#795)
        let styles = StyleWatch::new(&self.scene().style_sheet);
        match self.frp.view_mode.value() {
            view::Mode::Normal => {
                let edge_type = self
                    .edge_hover_type()
                    .or_else(|| self.edge_target_type(edge_id))
                    .or_else(|| self.edge_source_type(edge_id));
                let opt_color = edge_type.map(|t| type_coloring::compute(&t, &styles));
                opt_color.unwrap_or(neutral_color)
            }
            view::Mode::Profiling => neutral_color,
        }
    }

    fn first_detached_edge(&self) -> Option<EdgeId> {
        self.edges.detached_edges_iter().next()
    }

    fn first_detached_edge_source_type(&self) -> Option<Type> {
        self.first_detached_edge().and_then(|edge_id| self.edge_source_type(edge_id))
    }

    #[allow(dead_code)]
    fn first_detached_edge_target_type(&self) -> Option<Type> {
        self.first_detached_edge().and_then(|edge_id| self.edge_target_type(edge_id))
    }

    /// Return a color for the first detached edge.
    pub fn first_detached_edge_color(&self, neutral_color: color::Lcha) -> Option<color::Lcha> {
        self.first_detached_edge().map(|t| self.edge_color(t, neutral_color))
    }

    #[allow(missing_docs)] // FIXME[everyone] All pub functions should have docs.
    pub fn has_edges_with_detached_targets(&self, node_id: NodeId) -> bool {
        let mut found = false;
        self.with_node(node_id, |node| {
            for edge_id in node.out_edges.keys() {
                if self.with_edge(edge_id, |edge| edge.has_target()) == Some(false) {
                    found = true;
                    break;
                }
            }
        });
        found
    }

    /// Pan the camera to fully fit the `target_bbox` (expressed in scene coordinates) into a
    /// rectangular viewport between `screen_min_xy` and `screen_max_xy` (in screen coordinates).
    /// If `target_bbox` does not fully fit in the viewport, prefer showing the top-left corner of
    /// `target_bbox` than the opposite one.
    fn pan_camera(
        &self,
        target_bbox: selection::BoundingBox,
        screen_min_xy: Vector2,
        screen_max_xy: Vector2,
    ) {
        use ensogl::display::navigation::navigator::PanEvent;
        let scene = &self.app.display.default_scene;
        let screen_to_scene_xy = |pos: Vector2| {
            let vec3 = Vector3(pos.x, pos.y, 0.0);
            scene.screen_to_scene_coordinates(vec3).xy()
        };
        let scene_min_xy = screen_to_scene_xy(screen_min_xy);
        let scene_max_xy = screen_to_scene_xy(screen_max_xy);
        let viewport = selection::BoundingBox::from_corners(scene_min_xy, scene_max_xy);
        let pan_left = some_if_negative(target_bbox.left() - viewport.left());
        let pan_right = some_if_positive(target_bbox.right() - viewport.right());
        let pan_up = some_if_positive(target_bbox.top() - viewport.top());
        let pan_down = some_if_negative(target_bbox.bottom() - viewport.bottom());
        let pan_x = pan_left.or(pan_right).unwrap_or_default();
        let pan_y = pan_up.or(pan_down).unwrap_or_default();
        let pan_xy = Vector2(pan_x, pan_y);
        self.navigator.emit_pan_event(PanEvent::new(-pan_xy * scene.camera().zoom()));
    }

    fn pan_camera_to_node(&self, node_id: NodeId) {
        use theme::graph_editor::screen_margin_when_panning_camera_to_node as pan_margin;
        self.with_node(node_id, |node| {
            let camera = &self.app.display.default_scene.camera();
            let screen_size_halved = Vector2::from(camera.screen()) / 2.0;
            let styles = &self.styles_frp;
            let top_margin = styles.get_number(pan_margin::top).value();
            let bottom_margin = styles.get_number(pan_margin::bottom).value();
            let left_margin = styles.get_number(pan_margin::left).value();
            let right_margin = styles.get_number(pan_margin::right).value();
            let viewport_max_y = screen_size_halved.y - top_margin;
            let viewport_min_y = -screen_size_halved.y + bottom_margin;
            let viewport_min_x = -screen_size_halved.x + left_margin;
            let viewport_max_x = screen_size_halved.x - right_margin;
            let viewport_min_xy = Vector2(viewport_min_x, viewport_min_y);
            let viewport_max_xy = Vector2(viewport_max_x, viewport_max_y);
            let node_bbox = node.bounding_box.value();
            self.pan_camera(node_bbox, viewport_min_xy, viewport_max_xy)
        });
    }
}


// === Utilities ===

fn some_if_positive(x: f32) -> Option<f32> {
    (x > 0.0).as_some(x)
}

fn some_if_negative(x: f32) -> Option<f32> {
    (x < 0.0).as_some(x)
}


// === Display object ===

impl display::Object for GraphEditorModel {
    fn display_object(&self) -> &display::object::Instance {
        &self.display_object
    }
}



// ===================
// === GraphEditor ===
// ===================

#[derive(Debug, Clone, CloneRef)]
#[allow(missing_docs)] // FIXME[everyone] Public-facing API should be documented.
pub struct GraphEditor {
    pub model: GraphEditorModelWithNetwork,
    pub frp:   Frp,
}

impl GraphEditor {
    /// Graph editor nodes.
    pub fn nodes(&self) -> &Nodes {
        &self.model.nodes
    }

    /// Graph editor edges.
    pub fn edges(&self) -> &Edges {
        &self.model.edges
    }
}

impl Deref for GraphEditor {
    type Target = api::Public;
    fn deref(&self) -> &Self::Target {
        &self.frp.public
    }
}

impl application::View for GraphEditor {
    fn label() -> &'static str {
        "GraphEditor"
    }

    fn new(app: &Application) -> Self {
        new_graph_editor(app)
    }

    fn app(&self) -> &Application {
        &self.model.app
    }

    fn default_shortcuts() -> Vec<application::shortcut::Shortcut> {
        use shortcut::ActionType::*;
        [
            (Press, "!node_editing", "tab", "start_node_creation"),
            (Press, "!node_editing", "enter", "start_node_creation"),
            // === Drag ===
            (Press, "", "left-mouse-button", "node_press"),
            (Release, "", "left-mouse-button", "node_release"),
            (Press, "!node_editing", "backspace", "remove_selected_nodes"),
            (Press, "!node_editing", "delete", "remove_selected_nodes"),
            (Press, "has_detached_edge", "escape", "drop_dragged_edge"),
            (Press, "", "cmd g", "collapse_selected_nodes"),
            // === Visualization ===
            (Press, "!node_editing", "space", "press_visualization_visibility"),
            (DoublePress, "!node_editing", "space", "double_press_visualization_visibility"),
            (Release, "!node_editing", "space", "release_visualization_visibility"),
            (Press, "", "cmd i", "reload_visualization_registry"),
            (Press, "is_fs_visualization_displayed", "space", "close_fullscreen_visualization"),
            (Press, "", "cmd", "enable_quick_visualization_preview"),
            (Release, "", "cmd", "disable_quick_visualization_preview"),
            // === Selection ===
            (Press, "", "shift", "enable_node_multi_select"),
            (Press, "", "shift left-mouse-button", "enable_node_multi_select"),
            (Release, "", "shift", "disable_node_multi_select"),
            (Release, "", "shift left-mouse-button", "disable_node_multi_select"),
            (Press, "", "shift ctrl", "toggle_node_merge_select"),
            (Release, "", "shift ctrl", "toggle_node_merge_select"),
            (Press, "", "shift alt", "toggle_node_subtract_select"),
            (Release, "", "shift alt", "toggle_node_subtract_select"),
            (Press, "", "shift ctrl alt", "toggle_node_inverse_select"),
            (Release, "", "shift ctrl alt", "toggle_node_inverse_select"),
            // === Navigation ===
            (
                Press,
                "!is_fs_visualization_displayed",
                "ctrl space",
                "cycle_visualization_for_selected_node",
            ),
            (DoublePress, "", "left-mouse-button", "enter_hovered_node"),
            (DoublePress, "", "left-mouse-button", "start_node_creation_from_port"),
            (Press, "", "right-mouse-button", "start_node_creation_from_port"),
            (Press, "!node_editing", "cmd enter", "enter_selected_node"),
            (Press, "", "alt enter", "exit_node"),
            // === Node Editing ===
            (Press, "", "cmd", "edit_mode_on"),
            (Release, "", "cmd", "edit_mode_off"),
            (Press, "", "cmd left-mouse-button", "edit_mode_on"),
            (Release, "", "cmd left-mouse-button", "edit_mode_off"),
            (Press, "node_editing", "cmd enter", "stop_editing"),
            // === Profiling Mode ===
            (Press, "", "cmd p", "toggle_profiling_mode"),
            // === Debug ===
            (Press, "debug_mode", "ctrl d", "debug_set_test_visualization_data_for_selected_node"),
            (Press, "debug_mode", "ctrl shift enter", "debug_push_breadcrumb"),
            (Press, "debug_mode", "ctrl shift up", "debug_pop_breadcrumb"),
            (Press, "debug_mode", "ctrl n", "add_node_at_cursor"),
            // TODO(#5930): Temporary shortcut for testing different execution environments
            (Press, "", "cmd shift c", "toggle_execution_environment"),
        ]
        .iter()
        .map(|(a, b, c, d)| Self::self_shortcut_when(*a, *c, *d, *b))
        .collect()
    }
}

/// Return the toggle status of the given enable/disable/toggle inputs as a stream of booleans.
pub fn enable_disable_toggle(
    network: &frp::Network,
    enable: &frp::Any,
    disable: &frp::Any,
    toggle: &frp::Any,
) -> frp::Stream<bool> {
    // FIXME: the clone_refs bellow should not be needed.
    let enable = enable.clone_ref();
    let disable = disable.clone_ref();
    let toggle = toggle.clone_ref();
    frp::extend! { network
        out        <- any(...);
        on_toggle  <- toggle.map2(&out,|_,t| !t);
        on_enable  <- enable.constant(true);
        on_disable <- disable.constant(false);
        out        <+ on_toggle;
        out        <+ on_enable;
        out        <+ on_disable;
    }
    out.into()
}

#[allow(unused_parens)]
fn new_graph_editor(app: &Application) -> GraphEditor {
    let world = &app.display;
    let scene = &world.default_scene;
    let cursor = &app.cursor;
    let frp = Frp::new();
    let model = GraphEditorModelWithNetwork::new(app, cursor.clone_ref(), &frp);
    let network = frp.network();
    let nodes = &model.nodes;
    let edges = &model.edges;
    let inputs = &model.frp;
    let mouse = &scene.mouse.frp_deprecated;
    let touch = &model.touch_state;
    let vis_registry = &model.vis_registry;
    let out = &frp.private.output;
    let selection_controller = &model.selection_controller;

    // ========================
    // === Scene Navigation ===
    // ========================

    frp::extend! { network
        no_vis_selected   <- out.some_visualisation_selected.on_false();
        some_vis_selected <- out.some_visualisation_selected.on_true();

        set_navigator_false  <- inputs.set_navigator_disabled.on_true();
        set_navigator_true   <- inputs.set_navigator_disabled.on_false();

        disable_navigator <- any_(&set_navigator_false,&some_vis_selected);
        enable_navigator  <- any_(&set_navigator_true,&no_vis_selected);

        model.navigator.frp.set_enabled <+ bool(&disable_navigator,&enable_navigator);

        out.navigator_active <+ model.navigator.frp.enabled;
    }



    // ===================
    // === Breadcrumbs ===
    // ===================

    frp::extend! { network

        // === Debugging ===

        eval_ inputs.debug_push_breadcrumb(model.breadcrumbs.debug_push_breadcrumb.emit(None));
        eval_ inputs.debug_pop_breadcrumb (model.breadcrumbs.debug_pop_breadcrumb.emit(()));
    }



    // =============================
    // === Node Level Navigation ===
    // =============================

    frp::extend! { network

        target_to_enter <- inputs.enter_hovered_node.map(f_!(scene.mouse.target.get()));

        // Go level up on background click.
        enter_on_background    <= target_to_enter.map(|target| target.is_background().as_some(()));
        out.node_exited <+ enter_on_background;

        // Go level down on node double click.
        enter_on_node <= target_to_enter.map(|target| target.is_symbol().as_some(()));
        output_port_is_hovered <- inputs.hover_node_output.map(Option::is_some);
        enter_node <- enter_on_node.gate_not(&output_port_is_hovered);
        node_switch_to_enter    <- out.node_hovered.sample(&enter_node).unwrap();
        node_to_enter           <- node_switch_to_enter.map(|switch| switch.on().cloned()).unwrap();
        out.node_entered <+ node_to_enter;
    }



    // ============================
    // === Project Name Editing ===
    // ============================


    // === Start project name edit ===
    frp::extend! { network
        edit_mode     <- bool(&inputs.edit_mode_off,&inputs.edit_mode_on);
        eval edit_mode ((edit_mode_on) model.breadcrumbs.ide_text_edit_mode.emit(edit_mode_on));
    }


    // === Commit project name edit ===

    frp::extend! { network
        deactivate_breadcrumbs <- any3_(&touch.background.down,
                                        &out.node_editing_started,
                                        &out.node_entered);
        eval_ deactivate_breadcrumbs(model.breadcrumbs.outside_press());
    }



    // =========================
    // === User Interactions ===
    // =========================

    // === Mouse Cursor Transform ===
    frp::extend! { network
        cursor_pos_in_scene <- cursor.frp.screen_position.map(f!((position)
            scene.screen_to_scene_coordinates(*position).xy()
        ));
    }


    // === Selection Target Redirection ===

    frp::extend! { network
        let scene = model.scene();

        mouse_up_target <- mouse.up_primary.map(f_!(model.scene().mouse.target.get()));
        background_up   <= mouse_up_target.map(
            |t| (t == &display::scene::PointerTargetId::Background).as_some(())
        );

        eval_ scene.background.mouse_down_primary (touch.background.down.emit(()));
    }


    // === Mouse Interactions ===

    frp::extend! { network

        node_pointer_style <- any_mut::<cursor::Style>();

        let node_input_touch  = TouchNetwork::<EdgeEndpoint>::new(network,mouse);
        let node_output_touch = TouchNetwork::<EdgeEndpoint>::new(network,mouse);

        on_output_connect_drag_mode   <- node_output_touch.down.constant(true);
        on_output_connect_follow_mode <- node_output_touch.selected.constant(false);
        on_input_connect_drag_mode    <- node_input_touch.down.constant(true);
        on_input_connect_follow_mode  <- node_input_touch.selected.constant(false);

        on_connect_drag_mode   <- any(on_output_connect_drag_mode,on_input_connect_drag_mode);
        on_connect_follow_mode <- any(on_output_connect_follow_mode,on_input_connect_follow_mode);
        connect_drag_mode      <- any(on_connect_drag_mode,on_connect_follow_mode);

        on_detached_edge    <- any(&inputs.on_some_edges_targets_unset,&inputs.on_some_edges_sources_unset);
        has_detached_edge   <- bool(&out.on_all_edges_endpoints_set,&on_detached_edge);
        out.has_detached_edge <+ has_detached_edge;

        eval node_input_touch.down ((target)   model.frp.press_node_input.emit(target));
        eval node_output_touch.down ((target)  model.frp.press_node_output.emit(target));
    }


    // === Edge interactions  ===

    frp::extend! { network
    edge_mouse_down <- source::<EdgeId>();
    edge_over       <- source::<EdgeId>();
    edge_out        <- source::<EdgeId>();
    edge_hover      <- source::<Option<EdgeId>>();

    eval  edge_over((edge_id) edge_hover.emit(Some(*edge_id)));
    eval_ edge_out(edge_hover.emit(None));

    edge_over_pos <- map2(&cursor_pos_in_scene,&edge_hover,|pos, edge_id|
        edge_id.map(|id| (id, *pos))
    ).unwrap();

    // We do not want edge hover to occur for detached edges.
    set_edge_hover <- edge_over_pos.gate_not(&has_detached_edge);

    eval set_edge_hover ([model]((edge_id,pos)) {
         if let Some(edge) = model.edges.get_cloned_ref(edge_id){
            edge.frp.hover_position.emit(Some(*pos));
            edge.frp.redraw.emit(());
        }
    });

    remove_split <- any(&edge_out,&edge_mouse_down);
    eval remove_split ([model](edge_id) {
         if let Some(edge) = model.edges.get_cloned_ref(edge_id){
            edge.frp.hover_position.emit(None);
            edge.frp.redraw.emit(());
        }
    });
    edge_click <- map2(&edge_mouse_down,&cursor_pos_in_scene,|edge_id,pos|(*edge_id,*pos));
    valid_edge_disconnect_click <- edge_click.gate_not(&has_detached_edge);

    edge_is_source_click <- valid_edge_disconnect_click.map(f!([model]((edge_id,pos)) {
        if let Some(edge) = model.edges.get_cloned_ref(edge_id){
            edge.port_to_detach_for_position(*pos) == component::edge::PortType::OutputPort
        } else {
            false
        }
    }));

    edge_source_click <- valid_edge_disconnect_click.gate(&edge_is_source_click);
    edge_target_click <- valid_edge_disconnect_click.gate_not(&edge_is_source_click);

    on_edge_source_unset <= edge_source_click.map(f!(((id,_)) model.with_edge_source(*id,|t|(*id,t))));
    on_edge_target_unset <= edge_target_click.map(f!(((id,_)) model.with_edge_target(*id,|t|(*id,t))));
    out.on_edge_source_unset <+ on_edge_source_unset;
    out.on_edge_target_unset <+ on_edge_target_unset;
    }


    // === Edge creation  ===

    frp::extend! { network

    output_down <- node_output_touch.down.constant(());
    input_down  <- node_input_touch.down.constant(());

    has_detached_edge_on_output_down <- has_detached_edge.sample(&inputs.hover_node_output);

    port_input_mouse_up  <- inputs.hover_node_input.sample(&mouse.up_primary).unwrap();
    port_output_mouse_up <- inputs.hover_node_output.sample(&mouse.up_primary).unwrap();

    attach_all_edge_inputs  <- any (port_input_mouse_up, inputs.press_node_input, inputs.set_detached_edge_targets);
    attach_all_edge_outputs <- any (port_output_mouse_up, inputs.press_node_output, inputs.set_detached_edge_sources);

    create_edge_from_output <- node_output_touch.down.gate_not(&has_detached_edge_on_output_down);
    create_edge_from_input  <- node_input_touch.down.map(|value| value.clone());

    on_new_edge    <- any(&output_down,&input_down);
    let selection_mode = selection::get_mode(network,inputs);
    keep_selection <- selection_mode.map(|t| *t != selection::Mode::Normal);
    deselect_edges <- on_new_edge.gate_not(&keep_selection);
    eval_ deselect_edges ( model.clear_all_detached_edges() );

    new_output_edge <- create_edge_from_output.map(f_!([model,edge_mouse_down,edge_over,edge_out] {
        Some(model.new_edge_from_output(&edge_mouse_down,&edge_over,&edge_out))
    })).unwrap();
    new_input_edge <- create_edge_from_input.map(f!([model,edge_mouse_down,edge_over,edge_out]((target)){
        if model.is_node_connected_at_input(target.node_id,&target.port) {
            return None
        };
        Some(model.new_edge_from_input(&edge_mouse_down,&edge_over,&edge_out))
    })).unwrap();

    out.on_edge_add <+ new_output_edge;
    new_edge_source <- new_output_edge.map2(&node_output_touch.down, move |id,target| (*id,target.clone()));
    out.on_edge_source_set <+ new_edge_source;

    out.on_edge_add <+ new_input_edge;
    new_edge_target <- new_input_edge.map2(&node_input_touch.down, move |id,target| (*id,target.clone()));
    out.on_edge_target_set <+ new_edge_target;
    }


    // === Edge Connect ===

    frp::extend! { network

        // Clicking on background either drops dragged edge or aborts node editing.
        let background_selected = &touch.background.selected;
        was_edge_detached_when_background_selected  <- has_detached_edge.sample(background_selected);
        clicked_to_drop_edge  <- was_edge_detached_when_background_selected.on_true();
        clicked_to_abort_edit <- was_edge_detached_when_background_selected.on_false();

        out.on_edge_source_set <+ inputs.set_edge_source;
        out.on_edge_target_set <+ inputs.set_edge_target;

        let endpoints            = inputs.connect_nodes.clone_ref();
        edge                    <- endpoints . map(f_!(model.new_edge_from_output(&edge_mouse_down,&edge_over,&edge_out)));
        new_edge_source         <- endpoints . _0() . map2(&edge, |t,id| (*id,t.clone()));
        new_edge_target         <- endpoints . _1() . map2(&edge, |t,id| (*id,t.clone()));
        out.on_edge_add      <+ edge;
        out.on_edge_source_set <+ new_edge_source;
        out.on_edge_target_set <+ new_edge_target;

        detached_edges_without_targets <= attach_all_edge_inputs.map(f_!(model.take_edges_with_detached_targets()));
        detached_edges_without_sources <= attach_all_edge_outputs.map(f_!(model.take_edges_with_detached_sources()));

        new_edge_target <- detached_edges_without_targets.map2(&attach_all_edge_inputs, |id,t| (*id,t.clone()));
        out.on_edge_target_set <+ new_edge_target;
        new_edge_source <- detached_edges_without_sources.map2(&attach_all_edge_outputs, |id,t| (*id,t.clone()));
        out.on_edge_source_set <+ new_edge_source;

        on_new_edge_source <- new_edge_source.constant(());
        on_new_edge_target <- new_edge_target.constant(());

        overlapping_edges            <= out.on_edge_target_set._1().map(f!((t) model.overlapping_edges(t)));
        out.on_edge_drop_overlapping <+ overlapping_edges;

        drop_on_bg_up  <- background_up.gate(&connect_drag_mode);
        drop_edges     <- any (drop_on_bg_up,clicked_to_drop_edge);

        edge_dropped_to_create_node <= drop_edges.map(f_!(model.edges_with_detached_targets()));
        out.on_edge_drop_to_create_node <+ edge_dropped_to_create_node;

        remove_all_detached_edges <- any (drop_edges, inputs.drop_dragged_edge);
        edge_to_remove_without_targets <= remove_all_detached_edges.map(f_!(model.take_edges_with_detached_targets()));
        edge_to_remove_without_sources <= remove_all_detached_edges.map(f_!(model.take_edges_with_detached_sources()));
        edge_to_remove <- any(edge_to_remove_without_targets,edge_to_remove_without_sources);
        eval edge_to_remove ((id) model.remove_edge(id));
    }

    // === Adding Node ===

    frp::extend! { network
        let node_added_with_button = model.add_node_button.clicked.clone_ref();

        input_start_node_creation_from_port <- inputs.hover_node_output.sample(
            &inputs.start_node_creation_from_port);
        start_node_creation_from_port <- input_start_node_creation_from_port.filter_map(
            |v| v.clone());
        removed_edges_on_node_creation_from_port <= start_node_creation_from_port.map(f_!(
            model.model.clear_all_detached_edges()));
        out.on_edge_drop <+ removed_edges_on_node_creation_from_port;

        input_add_node_way <- inputs.add_node.constant(WayOfCreatingNode::AddNodeEvent);
        input_start_creation_way <- inputs.start_node_creation.map(f_!([scene]
            // Only start node creation if nothing is focused. This is to prevent
            // creating nodes when we are editing texts and press enter.
            scene.focused_instance().is_none().then_some(WayOfCreatingNode::StartCreationEvent)
        )).unwrap();

        start_creation_from_port_way <- start_node_creation_from_port.map(
            |endpoint| WayOfCreatingNode::StartCreationFromPortEvent{endpoint: endpoint.clone()});
        add_with_button_way <- node_added_with_button.constant(WayOfCreatingNode::ClickingButton);
        add_with_edge_drop_way <- edge_dropped_to_create_node.map(
            |&edge_id| WayOfCreatingNode::DroppingEdge{edge_id});
        add_node_way <- any5 (
            &input_add_node_way,
            &input_start_creation_way,
            &start_creation_from_port_way,
            &add_with_button_way,
            &add_with_edge_drop_way,
        );

        new_node <- add_node_way.map2(&cursor_pos_in_scene, f!([model,node_pointer_style,out](way, mouse_pos) {
            let ctx = NodeCreationContext {
                pointer_style  : &node_pointer_style,
                output_press   : &node_output_touch.down,
                input_press    : &node_input_touch.down,
                output         : &out,
            };
            model.create_node(&ctx, way, *mouse_pos)
        }));
        out.node_added <+ new_node.map(|&(id, src, should_edit)| (id, src, should_edit));
        node_to_edit_after_adding <- new_node.filter_map(|&(id,_,cond)| cond.as_some(id));
                eval new_node ([model](&(id, _, should_edit)) {
            if should_edit {
                if let Some(node) = model.nodes.get_cloned_ref(&id) {
                    node.show_preview();
                }
            }
        });
    }


    // === Node Editing ===

    frp::extend! { network
        node_in_edit_mode     <- out.node_being_edited.map(|n| n.is_some());
        edit_mode             <- bool(&inputs.edit_mode_off,&inputs.edit_mode_on);
        clicked_node <- touch.nodes.down.gate(&edit_mode);
        clicked_and_edited_nodes <- clicked_node.map2(&out.node_being_edited, |n, c| (*n, *c));
        let not_being_edited_already = |(clicked, edited): &(NodeId, Option<NodeId>)| {
            if let Some(edited) = edited { edited != clicked } else { true }
        };
        node_to_edit          <- clicked_and_edited_nodes.filter(not_being_edited_already)._0();
        edit_node             <- any(node_to_edit, node_to_edit_after_adding, inputs.edit_node);
        stop_edit_on_bg_click <- clicked_to_abort_edit.gate(&node_in_edit_mode);
        stop_edit             <- any(&stop_edit_on_bg_click,&inputs.stop_editing);
        edit_switch           <- edit_node.gate(&node_in_edit_mode);
        node_being_edited     <- out.node_being_edited.map(|n| n.unwrap_or_default());

        // The "finish" events must be emitted before "start", to properly cover the "switch" case.
        out.node_editing_finished <+ node_being_edited.sample(&stop_edit);
        out.node_editing_finished <+ node_being_edited.sample(&edit_switch);
        out.node_editing_started  <+ edit_node;

        out.node_being_edited <+ out.node_editing_started.map(|n| Some(*n));
        out.node_being_edited <+ out.node_editing_finished.constant(None);
        out.node_editing      <+ out.node_being_edited.map(|t|t.is_some());

        out.node_edit_mode       <+ edit_mode;
        out.nodes_labels_visible <+ out.node_edit_mode || node_in_edit_mode;

        eval out.node_editing_started ([model] (id) {
            let _profiler = profiler::start_debug!(profiler::APP_LIFETIME, "node_editing_started");
            if let Some(node) = model.nodes.get_cloned_ref(id) {
                node.model().input.set_editing(true);
            }
        });
        eval out.node_editing_finished ([model](id) {
            let _profiler = profiler::start_debug!(profiler::APP_LIFETIME, "node_editing_finished");
            if let Some(node) = model.nodes.get_cloned_ref(id) {
                node.model().input.set_editing(false);
            }
        });
    }


    // === Edited node growth/shrink animation ===

    component::node::growth_animation::initialize_edited_node_animator(&model, &frp, scene);


    // === Event Propagation ===

    // See the docs of `Node` to learn about how the graph - nodes event propagation works.
    frp::extend! { network
        _eval <- all_with(&out.node_hovered,&edit_mode,f!([model](tgt,e)
            if let Some(tgt) = tgt {
                model.with_node(tgt.value,|t| t.model().input.set_edit_ready_mode(*e && tgt.is_on()));
            }
        ));
        _eval <- all_with(&out.node_hovered,&out.some_edge_targets_unset,f!([model](tgt,ok)
            if let Some(tgt) = tgt {
                let node_id        = tgt.value;
                let edge_tp        = model.first_detached_edge_source_type();
                let is_edge_source = model.has_edges_with_detached_targets(node_id);
                let is_active      = *ok && !is_edge_source && tgt.is_on();
                model.with_node(node_id,|t| t.model().input.set_ports_active(is_active,edge_tp));
            }
        ));
    }


    // === Node Actions ===

    frp::extend! { network
        freeze_edges <= out.node_action_freeze.map (f!([model]((node_id,is_frozen)) {
            let edges = model.node_in_edges(node_id);
            edges.into_iter().map(|edge_id| (edge_id,*is_frozen)).collect_vec()
        }));

        eval freeze_edges (((edge_id,is_frozen)) model.set_edge_freeze(edge_id,*is_frozen) );
    }

    //
    // // === Disabling self-connections ===
    //
    // frp::extend! { network
    //     node_to_disable <= out.on_edge_only_target_not_set.map(f!((id)
    // model.with_edge_source(*id,|t|t.node_id)));     eval node_to_disable ((id)
    // model.with_node(*id,|node| node.model.input.set_ports_active(false,None)));
    //
    // }


    // === Remove Node ===
    frp::extend! { network

    all_nodes       <= inputs.remove_all_nodes      . map(f_!(model.all_nodes()));
    selected_nodes  <= inputs.remove_selected_nodes . map(f_!(model.nodes.all_selected()));
    nodes_to_remove <- any (all_nodes, selected_nodes);
    eval nodes_to_remove ((node_id) inputs.remove_all_node_edges.emit(node_id));

    out.node_removed <+ nodes_to_remove;
    }


    // === Collapse Nodes ===
    frp::extend! { network
    // TODO [mwu] https://github.com/enso-org/ide/issues/760
    //   This is currently the provisional code to enable collapse nodes refactoring. While the APIs
    //   are as-intended, their behavior isn't. Please refer to the issue for details.
    let empty_id       = NodeId::default();
    let model_clone    = model.clone_ref();
    nodes_to_collapse <- inputs.collapse_selected_nodes . map(move |_|
        (model_clone.nodes.all_selected(),empty_id)
    );
    out.nodes_collapsed <+ nodes_to_collapse;
    }


    // === Set Node SKIP/FREEZE macros and context switch expression ===

    frp::extend! { network
        eval inputs.set_node_skip(((id, skip)) model.set_node_skip(id, skip));
        eval inputs.set_node_freeze(((id, freeze)) model.set_node_freeze(id, freeze));
        eval inputs.set_node_context_switch(((id, context_switch))
            model.set_node_context_switch(id, context_switch)
        );
    }


    // === Set Node Comment ===
    frp::extend! { network

    eval inputs.set_node_comment([model] ((id,comment)) model.set_node_comment(id,comment));
    }

    // === Set Node Error ===
    frp::extend! { network

    eval inputs.set_node_error_status([model]((node_id, error)) {
        if let Some(node) = model.nodes.get_cloned_ref(node_id) {
            node.set_error.emit(error)
        }
    });

    }


    // === Profiling ===

    frp::extend! { network

        eval inputs.set_node_profiling_status([model]((node_id,status)) {
            if let Some(node) = model.nodes.get_cloned_ref(node_id) {
                model.profiling_statuses.set(*node_id,*status);
                node.set_profiling_status(status);
            }
        });

    }



    // ==================
    // === Move Nodes ===
    // ==================
    frp::extend! { network

    mouse_pos <- mouse.position.map(|p| Vector2(p.x,p.y));

    // === Discovering drag targets ===

    let node_down      = touch.nodes.down.clone_ref();
    let node_is_down   = touch.nodes.is_down.clone_ref();
    node_in_edit_mode <- node_down.map2(&out.node_being_edited,|t,s| Some(*t) == *s);
    node_was_selected <- node_down.map(f!((id) model.nodes.selected.contains(id)));
    tgts_if_non_sel   <- node_down.map(|id|vec![*id]).gate_not(&node_was_selected);
    tgts_if_sel       <- node_down.map(f_!(model.nodes.selected.items())).gate(&node_was_selected);
    tgts_if_non_edit  <- any(tgts_if_non_sel,tgts_if_sel).gate_not(&node_in_edit_mode);
    tgts_if_edit      <- node_down.map(|_|default()).gate(&node_in_edit_mode);
    drag_tgts         <- any(tgts_if_non_edit,tgts_if_edit);
    any_drag_tgt      <- drag_tgts.map(|t|!t.is_empty());
    node_pos_on_down  <- node_down.map(f!((id) model.node_position(id)));
    mouse_pos_on_down <- mouse_pos.sample(&node_down);
    mouse_pos_diff    <- mouse_pos.map2(&mouse_pos_on_down,|t,s|t-s).gate(&node_is_down);
    node_pos_diff     <- mouse_pos_diff.map(f!([scene](t) t / scene.camera().zoom()));
    node_tgt_pos_rt   <- node_pos_diff.map2(&node_pos_on_down,|t,s|t+s);
    just_pressed      <- bool (&node_tgt_pos_rt,&node_pos_on_down);
    node_tgt_pos_rt   <- any  (&node_tgt_pos_rt,&node_pos_on_down);


    // === Snapping ===

    eval drag_tgts ((ids) model.disable_grid_snapping_for(ids));
    let node_tgt_pos_anim = DEPRECATED_Animation::<Vector2<f32>>::new(network);
    let x_snap_strength   = Easing::new(network);
    let y_snap_strength   = Easing::new(network);
    x_snap_strength.set_duration(300.0);
    y_snap_strength.set_duration(300.0);

    _eval <- node_tgt_pos_rt.map2(&just_pressed,
        f!([model,x_snap_strength,y_snap_strength,node_tgt_pos_anim](pos,just_pressed) {
            let snapped = model.nodes.check_grid_magnet(*pos);
            let x = snapped.x.unwrap_or(pos.x);
            let y = snapped.y.unwrap_or(pos.y);
            x_snap_strength.target(if snapped.x.is_none() { 0.0 } else { 1.0 });
            y_snap_strength.target(if snapped.y.is_none() { 0.0 } else { 1.0 });
            node_tgt_pos_anim.set_target_value(Vector2::new(x,y));
            if *just_pressed {
                node_tgt_pos_anim.set_target_value(*pos);
                x_snap_strength.skip();
                y_snap_strength.skip();
                node_tgt_pos_anim.skip();
            }
    }));

    node_tgt_pos <- all_with4
        ( &node_tgt_pos_rt
        , &node_tgt_pos_anim.value
        , &x_snap_strength.value
        , &y_snap_strength.value
        , |rt,snap,xw,yw| {
            let w     = Vector2(*xw,*yw);
            let w_inv = Vector2(1.0,1.0) - w;
            rt.component_mul(&w_inv) + snap.component_mul(&w)
        });


    // === Update All Target Nodes Positions ===

    main_tgt_pos_prev <- node_tgt_pos.previous();
    main_tgt_pos_diff <- node_tgt_pos.map2(&main_tgt_pos_prev,|t,s|t-s).gate_not(&just_pressed);
    drag_tgt          <= drag_tgts.sample(&main_tgt_pos_diff);
    tgt_new_pos       <- drag_tgt.map2(&main_tgt_pos_diff,f!((id,tx) model.node_pos_mod(id,*tx)));
    out.node_position_set <+ tgt_new_pos;


    // === Batch Update ===

    after_drag             <- touch.nodes.up.gate_not(&just_pressed);
    tgt_after_drag         <= drag_tgts.sample(&after_drag);
    tgt_after_drag_new_pos <- tgt_after_drag.map(f!([model](id)(*id,model.node_position(id))));
    out.node_position_set_batched <+ tgt_after_drag_new_pos;


    // === Mouse style ===

    node_down_on_drag   <- node_down.gate(&any_drag_tgt);
    cursor_on_drag_down <- node_down_on_drag.map(|_| cursor::Style::new_with_all_fields_default().press());
    cursor_on_drag_up   <- touch.nodes.up.map(|_| cursor::Style::default());
    pointer_on_drag     <- any (&cursor_on_drag_down,&cursor_on_drag_up);


    // === Set Node Position ===

    out.node_position_set         <+ inputs.set_node_position;
    out.node_position_set_batched <+ inputs.set_node_position;
    eval out.node_position_set (((id,pos)) model.set_node_position(id,*pos));

    }


    // === Set Expression Type ===
    frp::extend! { network

    node_to_refresh <- inputs.set_expression_usage_type.map(f!([model]((node_id,ast_id,maybe_type)) {
        model.set_node_expression_usage_type(*node_id,*ast_id,maybe_type.clone());
        *node_id
    }));
    edges_to_refresh <= node_to_refresh.map(f!([nodes](node_id)
         nodes.get_cloned_ref(node_id).map(|node| node.all_edges())
    )).unwrap();
    eval edges_to_refresh ((edge) model.refresh_edge_position(*edge));
    eval inputs.update_node_widgets(((node, updates)) model.update_node_widgets(*node, updates));
    }


    // === Move Edges ===

    frp::extend! { network

    detached_edge           <- any(&inputs.on_some_edges_targets_unset,&inputs.on_some_edges_sources_unset);
    update_edge             <- any(detached_edge,on_new_edge_source,on_new_edge_target);
    cursor_pos_on_update    <- cursor_pos_in_scene.sample(&update_edge);
    edge_refresh_cursor_pos <- any(cursor_pos_on_update,cursor_pos_in_scene);

    is_hovering_output <- inputs.hover_node_output.map(|target| target.is_some()).sampler();
    hover_node         <- inputs.hover_node_output.unwrap();

    edge_refresh_on_node_hover        <- all(edge_refresh_cursor_pos,hover_node).gate(&is_hovering_output);
    edge_refresh_cursor_pos_no_hover  <- edge_refresh_cursor_pos.gate_not(&is_hovering_output);
    edge_refresh_cursor_pos_on_hover  <- edge_refresh_on_node_hover._0();

    refresh_target      <- any(&edge_refresh_cursor_pos_on_hover,&edge_refresh_cursor_pos_no_hover);
    let refresh_source  = edge_refresh_cursor_pos_no_hover.clone_ref();
    snap_source_to_node <- edge_refresh_on_node_hover._1();

    eval refresh_target ([edges](position) {
       edges.detached_target.for_each(|id| {
            if let Some(edge) = edges.get_cloned_ref(id) {
                edge.view.frp.target_position.emit(position.xy());
                edge.view.frp.redraw.emit(());
            }
        });
    });

    eval refresh_source ([edges,model](position) {
        edges.detached_source.for_each(|edge_id| {
            if let Some(edge) = edges.get_cloned_ref(edge_id) {
                edge.view.frp.source_width.emit(cursor::DEFAULT_RADIUS);
                edge.view.frp.source_height.emit(cursor::DEFAULT_RADIUS);
                edge.view.frp.target_position.emit(-position.xy());
                edge.view.frp.redraw.emit(());
                edge.modify_position(|p| {
                    p.x = position.x;
                    p.y = position.y;
                });
                model.refresh_edge_position(*edge_id);
            }
        });
    });

    eval snap_source_to_node ([nodes,edges,model](target) {
        edges.detached_source.for_each(|edge_id| {
            if let Some(node) = nodes.get_cloned_ref(&target.node_id) {
                if let Some(edge) = edges.get_cloned_ref(edge_id) {
                    let node_width  = node.view.model().width();
                    let node_height = node.view.model().height();
                    let node_pos    = node.position();

                    edge.view.frp.source_width.emit(node_width);
                    edge.view.frp.source_height.emit(node_height);
                    edge.view.frp.target_position.emit(-node_pos.xy());
                    edge.view.frp.redraw.emit(());
                    edge.modify_position(|p| {
                        p.x = node_pos.x + node_width/2.0;
                        p.y = node_pos.y;
                    });
                    model.refresh_edge_position(*edge_id);
                }
            }
        });
    });

    }


    // === Vis Set ===
    frp::extend! { network

    def _update_vis_data = inputs.set_visualization.map(f!([nodes,vis_registry]((node_id,vis_path)) {
        match (&nodes.get_cloned_ref(node_id), vis_path) {
             (Some(node), Some(vis_path)) => {
                 let vis_definition = vis_registry.definition_from_path(vis_path);
                 node.model().visualization.frp.set_visualization.emit(vis_definition);
             },
             (Some(node), None) => node.model().visualization.frp.set_visualization.emit(None),
              _ => warn!("Failed to get node: {node_id:?}"),

        }
    }));
    }


    // === Vis Selection ===
    frp::extend! { network
        eval out.on_visualization_select ([model](switch) {
            if switch.is_on() {
                model.visualisations.selected.insert(switch.value);
            } else {
                model.visualisations.selected.remove(&switch.value);
            }
        });

        out.some_visualisation_selected <+  out.on_visualization_select.map(f_!([model] {
            !model.visualisations.selected.is_empty()
        }));
    };


    // === Vis Update Data ===

    frp::extend! { network
    // TODO remove this once real data is available.
    let sample_data_generator = MockDataGenerator3D::default();
    def _set_dumy_data = inputs.debug_set_test_visualization_data_for_selected_node.map(f!([nodes,inputs](_) {
        for node_id in &*nodes.selected.raw.borrow() {
            let data    = Rc::new(sample_data_generator.generate_data()); // FIXME: why rc?
            let content = serde_json::to_value(data).unwrap();
            let data    = visualization::Data::from(content);
            inputs.set_visualization_data.emit((*node_id,data));
        }
    }));

    eval inputs.set_visualization_data ([nodes]((node_id,data)) {
        if let Some(node) = nodes.get_cloned(node_id) {
            node.model().visualization.frp.set_data.emit(data);
        }
    });

    eval inputs.set_error_visualization_data ([nodes]((node_id,data)) {
        if let Some(node) = nodes.get_cloned(node_id) {
            node.model().error_visualization.send_data.emit(data);
        }
    });

    nodes_to_cycle <= inputs.cycle_visualization_for_selected_node.map(f_!(model.nodes.all_selected()));
    node_to_cycle  <- any(nodes_to_cycle,inputs.cycle_visualization);
    eval node_to_cycle ([model](node_id) {
        if let Some(node) = model.nodes.get_cloned_ref(node_id) {
            node.view.model().visualization.frp.cycle_visualization();
        }
    });


    // === Visualization toggle ===
    //
    // Algorithm:
    //     - Press key. If all selected nodes have enabled vis, disable them.
    //     - If not, enable vis on missing nodes.
    //     - Release key. If the time passed from key press was short, do nothing.
    //     - If it was long, disable vis which were disabled (preview mode).

    let viz_press_ev      = inputs.press_visualization_visibility.clone_ref();
    let viz_d_press_ev    = inputs.double_press_visualization_visibility.clone_ref();
    let viz_release_ev    = inputs.release_visualization_visibility.clone_ref();
    viz_pressed          <- bool(&viz_release_ev,&viz_press_ev);
    viz_was_pressed      <- viz_pressed.previous();
    viz_press            <- viz_press_ev.gate_not(&viz_was_pressed);
    viz_release          <- viz_release_ev.gate(&viz_was_pressed);
    viz_press_time       <- viz_press   . map(|_| {
            let time = web::window.performance_or_panic().now() as f32;
            let frame_counter = Rc::new(web::FrameCounter::start_counting());
            (time, Some(frame_counter))
        });
    viz_release_time     <- viz_release . map(|_| web::window.performance_or_panic().now() as f32);
    viz_preview_mode  <- viz_release_time.map2(&viz_press_time,|t1,(t0,counter)| {
        let diff = t1-t0;
        // We check the time between key down and key up. If the time is less than the threshold
        // then it was a key press and we do not want to enter preview mode. If it is longer then
        // it was a key hold and we want to enter preview mode.
        let long_enough = diff > VIZ_PREVIEW_MODE_TOGGLE_TIME_MS;
        // We also check the number of passed frames, since the time measure can be misleading, if
        // there were dropped frames. The visualisation might have just appeared while more than
        // the threshold time has passed.
        let enough_frames = if let Some(counter) = counter {
            let frames = counter.frames_since_start();
            frames > VIZ_PREVIEW_MODE_TOGGLE_FRAMES
        } else {
            false
        };
        long_enough && enough_frames
    });
    viz_preview_mode_end <- viz_release.gate(&viz_preview_mode).gate_not(&out.is_fs_visualization_displayed);
    viz_tgt_nodes        <- viz_press.gate_not(&out.is_fs_visualization_displayed).map(f_!(model.nodes.all_selected()));
    viz_tgt_nodes_off    <- viz_tgt_nodes.map(f!([model](node_ids) {
        node_ids.iter().cloned().filter(|node_id| {
            model.nodes.get_cloned_ref(node_id)
                .map(|node| !node.visualization_enabled.value())
                .unwrap_or_default()
        }).collect_vec()
    }));

    viz_tgt_nodes_all_on <- viz_tgt_nodes_off.map(|t| t.is_empty());
    viz_enable_by_press  <= viz_tgt_nodes.gate_not(&viz_tgt_nodes_all_on);
    viz_enable           <- any(viz_enable_by_press,inputs.enable_visualization);
    viz_disable_by_press <= viz_tgt_nodes.gate(&viz_tgt_nodes_all_on);
    viz_disable          <- any(viz_disable_by_press,inputs.disable_visualization);
    viz_preview_disable  <= viz_tgt_nodes_off.sample(&viz_preview_mode_end);
    viz_fullscreen_on    <= viz_d_press_ev.map(f_!(model.nodes.last_selected()));

    eval viz_enable          ((id) model.enable_visualization(id));
    eval viz_disable         ((id) model.disable_visualization(id));
    eval viz_preview_disable ((id) model.disable_visualization(id));
    eval viz_fullscreen_on   ((id) model.enable_visualization_fullscreen(id));

    viz_fs_to_close <- out.visualization_fullscreen.sample(&inputs.close_fullscreen_visualization);
    eval viz_fs_to_close ([model](vis) {
        if let Some(vis) = vis {
            model.disable_visualization_fullscreen(vis);
            model.enable_visualization(vis);
        }
    });

    out.visualization_fullscreen <+ viz_fullscreen_on.map(|id| Some(*id));
    out.visualization_fullscreen <+ inputs.close_fullscreen_visualization.constant(None);

    out.is_fs_visualization_displayed <+ out.visualization_fullscreen.map(Option::is_some);


    // === Register Visualization ===

    eval inputs.register_visualization ([vis_registry](handle) {
        if let Some(handle) = handle {
            vis_registry.add(handle);
        }
    });
    eval inputs.reset_visualization_registry ([vis_registry](()) {
        vis_registry.remove_all_visualizations();
        vis_registry.add_default_visualizations();
    });
    out.visualization_registry_reload_requested <+ inputs.reload_visualization_registry;


    // === Entering and Exiting Nodes ===

    node_to_enter           <= inputs.enter_selected_node.map(f_!(model.nodes.last_selected()));
    out.node_entered <+ node_to_enter;
    removed_edges_on_enter  <= out.node_entered.map(f_!(model.model.clear_all_detached_edges()));
    out.node_exited  <+ inputs.exit_node;
    removed_edges_on_exit   <= out.node_exited.map(f_!(model.model.clear_all_detached_edges()));
    out.on_edge_drop <+ any(removed_edges_on_enter,removed_edges_on_exit);



    // ================
    // === Node VCS ===
    // ================

    eval inputs.set_node_vcs_status(((node_id,status))
         model.with_node(*node_id, |node| node.set_vcs_status.emit(status))
     );



    // ==================
    // === Edge Binds ===
    // ==================

    // === Source / Target ===

    eval out.on_edge_source_set   (((id,tgt)) model.set_edge_source(*id,tgt));
    eval out.on_edge_target_set   (((id,tgt)) model.set_edge_target(*id,tgt));

    eval out.on_edge_target_set   (((id,tgt)) model.set_endpoint_connection_status(*id,tgt,true));
    eval out.on_edge_target_unset (((id,tgt)) model.set_endpoint_connection_status(*id,tgt,false));

    eval out.on_edge_source_unset (((id,_)) model.remove_edge_source(*id));
    eval out.on_edge_target_unset (((id,_)) model.remove_edge_target(*id));

    is_only_tgt_not_set <-
        out.on_edge_source_set.map(f!(((id,_)) model.with_edge_map_target(*id,|_|()).is_none()));
    out.on_edge_source_set_with_target_not_set <+ out.on_edge_source_set.gate(&is_only_tgt_not_set);
    out.on_edge_only_target_not_set <+ out.on_edge_source_set_with_target_not_set._0();
    out.on_edge_only_target_not_set <+ out.on_edge_target_unset._0();

    is_only_src_not_set <-
        out.on_edge_target_set.map(f!(((id,_)) model.with_edge_map_source(*id,|_|()).is_none()));
    out.on_edge_target_set_with_source_not_set <+ out.on_edge_target_set.gate(&is_only_src_not_set);
    out.on_edge_only_source_not_set <+ out.on_edge_target_set_with_source_not_set._0();
    out.on_edge_only_source_not_set <+ out.on_edge_source_unset._0();

    let neutral_color = model.model.styles_frp.get_color(theme::code::types::any::selection);
    eval out.on_edge_source_set ([model,neutral_color]((id, _))
        model.refresh_edge_color(*id,neutral_color.value().into()));
    eval out.on_edge_target_set ([model,neutral_color]((id, _))
        model.refresh_edge_color(*id,neutral_color.value().into()));
    eval out.on_edge_source_unset ([model,neutral_color]((id, _))
        model.refresh_edge_color(*id,neutral_color.value().into()));
    eval out.on_edge_target_unset ([model,neutral_color]((id, _))
        model.refresh_edge_color(*id,neutral_color.value().into()));
    eval neutral_color ((neutral_color) model.refresh_all_edge_colors(neutral_color.into()));

    edge_to_refresh_on_hover <= out.hover_node_input.map(f_!(model.edges_with_detached_targets()));
    eval edge_to_refresh_on_hover ([model,neutral_color](id)
        model.refresh_edge_color(*id,neutral_color.value().into()));


    some_edge_sources_unset   <- out.on_all_edges_sources_set ?? out.on_some_edges_sources_unset;
    some_edge_targets_unset   <- out.on_all_edges_targets_set ?? out.on_some_edges_targets_unset;
    some_edge_endpoints_unset <- out.some_edge_targets_unset  || out.some_edge_sources_unset;
    out.some_edge_sources_unset    <+ some_edge_sources_unset;
    out.some_edge_targets_unset    <+ some_edge_targets_unset;
    out.some_edge_endpoints_unset  <+ some_edge_endpoints_unset;
    out.on_all_edges_endpoints_set <+ out.some_edge_endpoints_unset.on_false();


    // === Endpoints ===

    edge_source_drop <= out.on_edge_drop.map(f!((id) model.edge_source(*id).map(|t|(*id,t))));
    edge_target_drop <= out.on_edge_drop.map(f!((id) model.edge_target(*id).map(|t|(*id,t))));

    edge_endpoint_set                 <- any(out.on_edge_source_set,out.on_edge_target_set)._0();
    both_endpoints_set                <- edge_endpoint_set.map(f!((id) model.is_connection(id)));
    new_edge_with_both_endpoints_set  <- edge_endpoint_set.gate(&both_endpoints_set);
    out.on_edge_endpoints_set  <+ new_edge_with_both_endpoints_set;
    out.on_edge_endpoint_set   <+ any(out.on_edge_source_set,out.on_edge_target_set);
    out.on_edge_endpoint_unset <+ any(out.on_edge_source_unset,out.on_edge_target_unset);
    out.on_edge_endpoint_unset <+ any(edge_source_drop,edge_target_drop);


    // === Drop ===

    eval out.on_edge_drop_overlapping ((id) model.remove_edge(id));
    eval out.on_edge_drop             ((id) model.remove_edge(id));



    // ===================
    // === Other Binds ===
    // ===================

    eval out.node_selected   ((id) model.nodes.select(id));
    eval out.node_deselected ((id) model.nodes.deselect(id));
    eval out.node_removed    ((id) model.remove_node(id));
    model.profiling_statuses.remove <+ out.node_removed;
    out.on_visualization_select <+ out.node_removed.map(|&id| Switch::Off(id));

    eval inputs.set_node_expression (((id, expr)) model.set_node_expression(id, expr));
    eval inputs.edit_node_expression (((id, range, ins)) model.edit_node_expression(id, range, ins));
    port_to_refresh <= inputs.set_node_expression.map(f!(((id, _))model.node_in_edges(id)));
    eval port_to_refresh ((id) model.set_edge_target_connection_status(*id,true));

    // === Remove implementation ===
    out.node_removed <+ inputs.remove_node;
    }


    // === Remove Edge ===
    frp::extend! { network

    rm_input_edges       <- any (inputs.remove_all_node_edges, inputs.remove_all_node_input_edges);
    rm_output_edges      <- any (inputs.remove_all_node_edges, inputs.remove_all_node_output_edges);
    input_edges_to_rm    <= rm_input_edges  . map(f!((node_id) model.node_in_edges(node_id)));
    output_edges_to_rm   <= rm_output_edges . map(f!((node_id) model.node_out_edges(node_id)));
    edges_to_rm          <- any (inputs.remove_edge, input_edges_to_rm, output_edges_to_rm);
    out.on_edge_drop <+ edges_to_rm;
    }



    // =====================
    // === Pointer Style ===
    // =====================

    frp::extend! { network

    cursor_style_edge_drag <- all_with(&out.some_edge_endpoints_unset,&out.view_mode,
        f!([model,neutral_color](some_edges_detached,_) {
            if *some_edges_detached {
                if let Some(color) = model.first_detached_edge_color(neutral_color.value().into()) {
                    cursor::Style::new_color(color).press()
                } else {
                    cursor::Style::new_color_no_animation(neutral_color.value().into()).press()
                }
            } else {
                default()
            }
        }));

    let breadcrumb_style = model.breadcrumbs.pointer_style.clone_ref();
    let selection_style  = selection_controller.cursor_style.clone_ref();

    pointer_style <- all
        [ pointer_on_drag
        , selection_style
        , node_pointer_style
        , cursor_style_edge_drag
        , breadcrumb_style
        ].fold();

    eval pointer_style ((style) cursor.frp.set_style.emit(style));

    }

    // ==============================
    // === Component Interactions ===
    // ==============================

    // === Nodes + Selection ===

    // Do not show quick actions on hover while doing an area selection.
    frp::extend! { network
        eval selection_controller.area_selection ((area_selection) nodes.show_quick_actions(!area_selection));
    }

    // === Visualisation + Selection ===

    // Do not allow area selection while we show a fullscreen visualisation.
    frp::extend! { network
        allow_area_selection <- out.is_fs_visualization_displayed.not();
        eval allow_area_selection ((area_selection)
            selection_controller.enable_area_selection.emit(area_selection)
        );
    }


    // ========================
    // === Focus management ===
    // ========================

    frp::extend! { network
        // Remove focus from any element when background is clicked.
        eval_ touch.background.down (model.display_object.blur_tree());
    }


    // ===============
    // === Tooltip ===
    // ===============

    frp::extend! { network

        model.tooltip.frp.set_style <+ app.frp.tooltip;

        quick_visualization_preview <- bool(&frp.disable_quick_visualization_preview,
                                            &frp.enable_quick_visualization_preview);
        eval quick_visualization_preview((value) model.nodes.set_quick_preview(*value));
    }



    // =====================
    // === Dropped Files ===
    // =====================

    use theme::graph_editor::default_y_gap_between_nodes as gap_path;
    let default_gap = model.styles_frp.get_number_or(gap_path, 0.0);
    let files_received = model.drop_manager.files_received().clone_ref();
    frp::extend! { network
        files_with_positions <- files_received.map2(&default_gap,
            move |drop_event_data,default_gap| {
                let files = &drop_event_data.files;
                let drop_posititon = drop_event_data.position;
                let single_offset = default_gap + node::HEIGHT;
                files.iter().enumerate().map(|(index,file)| {
                    let offset = Vector2(0.0, single_offset * index as f32);
                    (file.clone_ref(), drop_posititon + offset)
                }).collect_vec()
            }
        );
        file_dropped            <= files_with_positions;
        out.file_dropped <+ file_dropped;
    }



    // ==================
    // === View Modes ===
    // ==================

    let profiling_mode_transition = Animation::new(network);
    frp::extend! { network
        out.view_mode <+ frp.toggle_profiling_mode.map2(&frp.view_mode,|_,&mode| mode.switch());
        out.view_mode <+ model.profiling_button.view_mode;

        model.profiling_button.set_view_mode <+ out.view_mode.on_change();
        _eval <- all_with(&out.view_mode,&neutral_color,f!((_,neutral_color)
            model.refresh_all_edge_colors(neutral_color.into())));

        profiling_mode_transition.target <+ out.view_mode.map(|&mode| {
            match mode {
                view::Mode::Normal    => 0.0,
                view::Mode::Profiling => 1.0,
            }
        });
        eval profiling_mode_transition.value ((&v) scene.dom.layers.back.filter_grayscale(v));
    }



    // =========================
    // === Gap Between Nodes ===
    // =========================

    let style_sheet = &scene.style_sheet;
    let styles = StyleWatchFrp::new(style_sheet);
    let default_x_gap_path = ensogl_hardcoded_theme::graph_editor::default_x_gap_between_nodes;
    let default_y_gap_path = ensogl_hardcoded_theme::graph_editor::default_y_gap_between_nodes;
    let min_x_spacing_path = ensogl_hardcoded_theme::graph_editor::minimal_x_spacing_for_new_nodes;
    let default_x_gap = styles.get_number_or(default_x_gap_path, 0.0);
    let default_y_gap = styles.get_number_or(default_y_gap_path, 0.0);
    let min_x_spacing = styles.get_number_or(min_x_spacing_path, 0.0);
    frp::extend! { network
        frp.private.output.default_x_gap_between_nodes <+ default_x_gap;
        frp.private.output.default_y_gap_between_nodes <+ default_y_gap;
        frp.private.output.min_x_spacing_for_new_nodes <+ min_x_spacing;
    }
    frp.private.output.default_x_gap_between_nodes.emit(default_x_gap.value());
    frp.private.output.default_y_gap_between_nodes.emit(default_y_gap.value());
    frp.private.output.min_x_spacing_for_new_nodes.emit(min_x_spacing.value());


    // ================================
    // === Execution Mode Selection ===
    // ================================

    let execution_mode_selector = &model.execution_mode_selector;
    frp::extend! { network

        execution_mode_selector.set_available_execution_modes <+ frp.set_available_execution_modes;
        out.execution_mode <+ execution_mode_selector.selected_execution_mode;
        out.execution_mode_play_button_pressed <+ execution_mode_selector.play_press;

        // === Layout ===
        init <- source::<()>();
        size_update <- all(init,execution_mode_selector.size,inputs.space_for_window_buttons);
        eval size_update ([model]((_,size,gap_size)) {
            let y_offset = MACOS_TRAFFIC_LIGHTS_VERTICAL_CENTER;
            let traffic_light_width = traffic_lights_gap_width();

            let execution_mode_selector_x = gap_size.x + traffic_light_width;
            model.execution_mode_selector.set_x(execution_mode_selector_x);
            let breadcrumb_gap_width = execution_mode_selector_x + size.x + TOP_BAR_ITEM_MARGIN;
            model.breadcrumbs.gap_width(breadcrumb_gap_width);

            model.execution_mode_selector.set_y(y_offset + size.y / 2.0);
            model.breadcrumbs.set_y(y_offset + component::breadcrumbs::HEIGHT / 2.0);
        });
    }
    init.emit(());


    // ==================
    // === Debug Mode ===
    // ==================

    frp::extend! { network
        out.debug_mode <+ frp.set_debug_mode;

        limit_max_zoom <- frp.set_debug_mode.on_false();
        unlimit_max_zoom <- frp.set_debug_mode.on_true();
        eval_ limit_max_zoom (model.navigator.set_max_zoom(Some(MAX_ZOOM)));
        eval_ unlimit_max_zoom (model.navigator.set_max_zoom(None));
    }

    // Init defaults
    frp.edit_mode_off.emit(());
    frp.set_debug_mode.emit(false);

    GraphEditor { model, frp }
}



impl display::Object for GraphEditor {
    fn display_object(&self) -> &display::object::Instance {
        self.model.display_object()
    }
}



// =============================
// === Execution Environment ===
// =============================

// TODO(#5930): Move me once we synchronise the execution environment with the language server.
/// The execution environment which controls the global execution of functions with side effects.
///
/// For more information, see
/// https://github.com/enso-org/design/blob/main/epics/basic-libraries/write-action-control/design.md.
#[derive(Debug, Clone, CloneRef, Copy, Default)]
pub enum ExecutionEnvironment {
    /// Allows editing the graph, but the `Output` context is disabled, so it prevents accidental
    /// changes.
    #[default]
    Design,
    /// Unrestricted, live editing of data.
    Live,
}

impl ExecutionEnvironment {
    /// Returns whether the output context is enabled for this execution environment.
    pub fn output_context_enabled(&self) -> bool {
        match self {
            Self::Design => false,
            Self::Live => true,
        }
    }
}

impl Display for ExecutionEnvironment {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        let name = match self {
            Self::Design => "design",
            Self::Live => "live",
        };
        write!(f, "{name}")
    }
}



// =============
// === Tests ===
// =============

#[cfg(test)]
mod tests {
    use super::*;
    use application::test_utils::ApplicationExt;
    use ensogl::control::io::mouse::PrimaryButton;
    use ensogl::display::scene::test_utils::MouseExt;
    use node::test_utils::NodeModelExt;

    #[test]
    fn test_adding_node_by_internal_api() {
        let (_, graph_editor) = init();
        assert_eq!(graph_editor.nodes().len(), 0);
        graph_editor.add_node();
        assert_eq!(graph_editor.nodes().len(), 1);
        graph_editor.assert(Case { node_source: None, should_edit: false });
    }

    #[test]
    fn test_adding_node_by_shortcut() {
        test_adding_node(press_add_node_shortcut);
    }

    fn press_add_node_shortcut(editor: &GraphEditor) {
        editor.start_node_creation();
    }

    #[test]
    fn test_adding_node_by_adding_node_button() {
        test_adding_node(click_add_node_button);
    }

    fn click_add_node_button(editor: &GraphEditor) {
        let adding_node_button = &editor.model.add_node_button;
        adding_node_button.click();
    }

    fn test_adding_node(add_node: impl Fn(&GraphEditor)) {
        let (app, graph_editor) = init();
        assert_eq!(graph_editor.nodes().len(), 0);

        // Adding first node.
        let (node_1_id, node_1) = graph_editor.add_node_by(&add_node);
        graph_editor.assert(Case { node_source: None, should_edit: true });
        graph_editor.stop_editing();
        assert_eq!(graph_editor.nodes().len(), 1);

        // First node is created in the center of the screen.
        let node_1_pos = node_1.position();
        let screen_center = app.display.default_scene.screen_to_scene_coordinates(Vector3::zeros());
        assert_eq!(node_1_pos.xy(), screen_center.xy());

        // Adding second node with the first node selected.
        graph_editor.nodes().select(node_1_id);
        let (_, node_2) = graph_editor.add_node_by(&add_node);
        graph_editor.assert(Case { node_source: Some(node_1_id), should_edit: true });
        assert_eq!(graph_editor.nodes().len(), 2);

        // Second node is below the first and left-aligned to it.
        let node_2_pos = node_2.position();
        assert!(node_2_pos.y < node_1_pos.y);
        assert_eq!(node_2_pos.x, node_1_pos.x);
    }

    #[test]
    fn test_adding_node_by_dropping_edge() {
        let (app, graph_editor) = init();
        assert_eq!(graph_editor.nodes().len(), 0);
        // Adding a new node.
        let (node_1_id, node_1) = graph_editor.add_node_by_api();
        graph_editor.stop_editing();
        // Creating edge.
        let port = node_1.model().output_port_shape().expect("No output port.");
        port.events_deprecated.emit_mouse_down(PrimaryButton);
        port.events_deprecated.emit_mouse_up(PrimaryButton);
        assert_eq!(graph_editor.edges().len(), 1);
        // Dropping edge.
        let mouse = &app.display.default_scene.mouse;
        let click_pos = Vector2(300.0, 300.0);
        mouse.frp_deprecated.position.emit(click_pos);
        let click_on_background = |_: &GraphEditor| mouse.click_on_background();
        let (_, node_2) = graph_editor.add_node_by(&click_on_background);
        graph_editor.assert(Case { node_source: Some(node_1_id), should_edit: true });
        let node_pos = node_2.position();
        assert_eq!(node_pos.xy(), click_pos);
    }

    #[test]
    fn test_connecting_two_nodes() {
        let (_, ref graph_editor) = init();
        let edges = graph_editor.edges();
        assert!(graph_editor.nodes().is_empty());
        assert!(edges.is_empty());
        // Adding two nodes.
        let (node_id_1, node_1) = graph_editor.add_node_by_api();
        graph_editor.stop_editing();
        let (node_id_2, node_2) = graph_editor.add_node_by_api();
        graph_editor.stop_editing();
        // Creating edge.
        let port = node_1.model().output_port_shape().expect("No output port.");
        port.events_deprecated.emit_mouse_down(PrimaryButton);
        port.events_deprecated.emit_mouse_up(PrimaryButton);
        let edge_id = graph_editor.on_edge_add.value();
        let edge = edges.get_cloned_ref(&edge_id).expect("Edge was not added.");
        assert_eq!(edge.source().map(|e| e.node_id), Some(node_id_1));
        assert_eq!(edge.target().map(|e| e.node_id), None);
        assert_eq!(edges.len(), 1);
        // Connecting edge.
        // We need to enable ports. Normally it is done by hovering the node.
        node_2.model().input.frp.set_ports_active(true, None);
        let port = node_2.model().input_port_shape().expect("No input port.");
        port.hover.events_deprecated.emit_mouse_down(PrimaryButton);
        port.hover.events_deprecated.emit_mouse_up(PrimaryButton);
        assert_eq!(edge.source().map(|e| e.node_id), Some(node_id_1));
        assert_eq!(edge.target().map(|e| e.node_id), Some(node_id_2));
    }

    #[test]
    fn test_magnet_alignment_when_adding_node_by_shortcut() {
        test_magnet_alignment_when_adding_node(move_mouse_and_add_node_by_shortcut);
    }

    fn move_mouse_and_add_node_by_shortcut(
        scene: &Scene,
        editor: &GraphEditor,
        mouse_pos: Vector2,
    ) {
        scene.mouse.frp_deprecated.position.emit(mouse_pos);
        press_add_node_shortcut(editor);
    }

    #[test]
    fn test_magnet_alignment_when_adding_node_by_add_node_button() {
        test_magnet_alignment_when_adding_node(move_camera_and_click_add_node_button);
    }

    fn move_camera_and_click_add_node_button(
        scene: &Scene,
        editor: &GraphEditor,
        camera_pos: Vector2,
    ) {
        let camera = &scene.camera();
        camera.set_xy(camera_pos);
        camera.update(scene);
        click_add_node_button(editor);
    }

    fn test_magnet_alignment_when_adding_node(add_node_at: impl Fn(&Scene, &GraphEditor, Vector2)) {
        let (app, graph_editor) = init();
        let scene = &app.display.default_scene;
        let add_node_at = |editor: &GraphEditor, pos: Vector2| add_node_at(scene, editor, pos);

        // Create two nodes, with the 2nd one positioned below and far to the right from the 1st
        // one.
        let (_, node_1) = graph_editor.add_node_by_api_at_pos(Vector2(0.0, 0.0));
        let (_, node_2) = graph_editor.add_node_by_api_at_pos(Vector2(800.0, -100.0));

        // Create third node, placing it roughly below the 1st one and to the left of the 2nd one,
        // but slightly displaced from a position aligned to them both. Verify that the magnet
        // algorithm repositions the node such that it is aligned with existing nodes.
        let aligned_pos = Vector2(node_1.position().x, node_2.position().y);
        let displacement = Vector2(7.0, 8.0);
        test_node_added_with_displacement_gets_aligned(
            &graph_editor,
            aligned_pos,
            displacement,
            add_node_at,
        );

        // Create fourth node, placing it roughly to the right of the 1st node and above the 2nd
        // one, but slightly displaced from a position aligned to them both. Verify that the magnet
        // algorithm repositions the node such that it is aligned with existing nodes.
        let aligned_pos = Vector2(node_2.position().x, node_1.position().y);
        test_node_added_with_displacement_gets_aligned(
            &graph_editor,
            aligned_pos,
            displacement,
            add_node_at,
        );
    }

    fn test_node_added_with_displacement_gets_aligned(
        graph_editor: &GraphEditor,
        aligned_pos: Vector2,
        displacement: Vector2,
        add_node_at: impl Fn(&GraphEditor, Vector2),
    ) {
        let unaligned_pos = aligned_pos + displacement;
        let add_node_unaligned = |editor: &GraphEditor| add_node_at(editor, unaligned_pos);
        let (_, node) = graph_editor.add_node_by(&add_node_unaligned);
        graph_editor.stop_editing();
        assert_eq!(node.position().xy(), aligned_pos);
    }

    #[test]
    fn test_magnet_alignment_when_no_space_for_node_added_with_add_node_button() {
        let (app, graph_editor) = init();
        let scene = &app.display.default_scene;

        // Create 1st node.
        let (node_1_id, node_1) = graph_editor.add_node_by_api();
        graph_editor.stop_editing();

        // Create 2nd node below the 1st one and move it slightly to the right.
        graph_editor.nodes().select(node_1_id);
        let (node_2_id, node_2) = graph_editor.add_node_by(&press_add_node_shortcut);
        node_2.update_x(|x| x + 16.0);

        // Create 3rd node below the 2nd one and move it slightly down and far to the right.
        graph_editor.nodes().select(node_2_id);
        let (_, node_3) = graph_editor.add_node_by(&press_add_node_shortcut);
        node_2.update_xy(|pos| pos + Vector2(800.0, -7.0));

        // Create 4th node by clicking (+) button when camera is roughly centered at the 1st node.
        let small_displacement = Vector2(8.0, 9.0);
        let pos_near_node_1 = node_1.position().xy() + small_displacement;
        let add_node = |editor: &GraphEditor| {
            move_camera_and_click_add_node_button(scene, editor, pos_near_node_1)
        };
        let (_, node_4) = graph_editor.add_node_by(&add_node);
        let aligned_pos = Vector2(node_1.position().x, node_3.position().y);
        assert_eq!(node_4.position().xy(), aligned_pos);
    }


    // === Test utilities ===

    /// An assertion case used when adding new nodes. See [`GraphEditor::assert`] below.
    struct Case {
        /// A source node of the added node.
        node_source: Option<NodeId>,
        /// Should we start the node editing immediately after adding it?
        should_edit: bool,
    }

    impl GraphEditor {
        fn add_node_by<F: Fn(&GraphEditor)>(&self, add_node: &F) -> (NodeId, Node) {
            add_node(self);
            let (node_id, ..) = self.node_added.value();
            let node = self.nodes().get_cloned_ref(&node_id).expect("Node was not added.");
            node.set_expression(node::Expression::new_plain("some_not_empty_expression"));
            (node_id, node)
        }

        fn add_node_by_api(&self) -> (NodeId, Node) {
            let add_node = |editor: &GraphEditor| editor.add_node();
            self.add_node_by(&add_node)
        }

        fn add_node_by_api_at_pos(&self, position: Vector2) -> (NodeId, Node) {
            let (node_id, node) = self.add_node_by_api();
            self.stop_editing();
            node.set_xy(position);
            (node_id, node)
        }

        fn assert(&self, case: Case) {
            let (added_node, node_source, should_edit) = self.node_added.value();
            let node_being_edited = self.node_being_edited.value();
            assert_eq!(
                should_edit, case.should_edit,
                "Node editing state does not match expected."
            );
            assert_eq!(should_edit, node_being_edited.is_some());
            if let Some(node_being_edited) = node_being_edited {
                assert_eq!(node_being_edited, added_node, "Edited node does not match added one.");
            }
            let node_source = node_source.map(|source| source.node);
            assert_eq!(node_source, case.node_source, "Source node does not match expected.");
        }
    }

    fn init() -> (Application, GraphEditor) {
        let app = Application::new("root");
        app.set_screen_size_for_tests();
        let graph_editor = new_graph_editor(&app);
        let mouse = &app.display.default_scene.mouse;
        mouse.frp_deprecated.position.emit(Vector2::zeros());
        (app, graph_editor)
    }
}
