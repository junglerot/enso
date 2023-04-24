//! Definition of the node input port component.

use crate::prelude::*;
use enso_text::index::*;
use enso_text::unit::*;
use ensogl::display::shape::*;
use ensogl::display::traits::*;

use crate::component::type_coloring;
use crate::node;
use crate::node::input::port;
use crate::node::input::widget;
use crate::node::profiling;
use crate::view;
use crate::Type;
use crate::WidgetUpdates;

use enso_frp as frp;
use enso_frp;
use ensogl::application::Application;
use ensogl::data::color;
use ensogl::display;
use ensogl::gui::cursor;
use ensogl::Animation;
use ensogl_component::text;
use ensogl_component::text::buffer::selection::Selection;
use ensogl_component::text::FromInContextSnapped;
use ensogl_hardcoded_theme as theme;



// =================
// === Constants ===
// =================

/// An offset from the port area position to the text position.
pub const TEXT_OFFSET: f32 = 10.0;

/// Width of a single glyph
// TODO: avoid using hardcoded value. See https://www.pivotaltracker.com/story/show/183567623.
pub const GLYPH_WIDTH: f32 = 7.224_609_4;

/// Enable visual port debug mode and additional port creation logging.
pub const DEBUG: bool = false;

/// Visual port offset for debugging purposes. Applied hierarchically. Applied only when `DEBUG` is
/// set to `true`.
pub const DEBUG_PORT_OFFSET: f32 = 5.0;

/// Skip creating ports on all operations. For example, in expression `foo bar`, `foo` is considered
/// an operation.
const SKIP_OPERATIONS: bool = true;
const PORT_PADDING_X: f32 = 4.0;

/// Text size used for input area text.
pub const TEXT_SIZE: f32 = 12.0;



// ================
// === SpanTree ===
// ================

pub use span_tree::Crumb;
pub use span_tree::Crumbs;

/// Specialized `SpanTree` for the input ports model.
pub type SpanTree = span_tree::SpanTree<port::Model>;

/// Mutable reference to port inside of a `SpanTree`.
pub type PortRefMut<'a> = span_tree::node::RefMut<'a, port::Model>;



// ==================
// === Expression ===
// ==================

/// Specialized version of `node::Expression`, containing the port information.
#[derive(Clone, Default)]
#[allow(missing_docs)]
pub struct Expression {
    /// Visual code representation. It can contain names of missing arguments, and thus can differ
    /// from `code`.
    pub viz_code:  ImString,
    pub code:      ImString,
    pub span_tree: SpanTree,
}

impl Deref for Expression {
    type Target = SpanTree;
    fn deref(&self) -> &Self::Target {
        &self.span_tree
    }
}

impl DerefMut for Expression {
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.span_tree
    }
}

impl Debug for Expression {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "Expression({})", self.code)
    }
}


// === Pretty printing debug adapter ===

/// Debug adapter used for pretty-printing the `Expression` span tree. Can be used to print the
/// expression with detailed span-tree information. This printer is normally too verbose to be
/// a default `Debug` implementation of `Expression`, so it is hidden behind a separate adapter
/// and can be chosen by calling `expression.tree_pretty_printer()`.
pub struct ExpressionTreePrettyPrint<'a> {
    expression: &'a Expression,
}

impl<'a> Debug for ExpressionTreePrettyPrint<'a> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> fmt::Result {
        let printed = self.expression.span_tree.debug_print(&self.expression.code);
        f.write_str(&printed)
    }
}

impl Expression {
    /// Wrap the expression into a pretty-printing adapter that implements `Debug` and prints
    /// detailed span-tree information. See [`SpanTree::debug_print`] method for more details.
    ///
    /// Note that this printer emits multi-line output. In order for those lines to be properly
    /// aligned, it should be always printed on a new line.
    pub fn tree_pretty_printer(&self) -> ExpressionTreePrettyPrint<'_> {
        ExpressionTreePrettyPrint { expression: self }
    }
}


// === Conversions ===

/// Helper struct used for `Expression` conversions.
#[derive(Debug, Default)]
struct ExprConversion {
    prev_tok_local_index:  Byte,
    /// Index of the last traverse parent node in the `SpanTree`.
    last_parent_tok_index: Byte,
}

impl ExprConversion {
    fn new(last_parent_tok_index: Byte) -> Self {
        let prev_tok_local_index = default();
        Self { prev_tok_local_index, last_parent_tok_index }
    }
}

impl From<node::Expression> for Expression {
    /// Traverses the `SpanTree` and constructs `viz_code` based on `code` and the `SpanTree`
    /// structure. It also computes `port::Model` values in the `viz_code` representation.
    #[profile(Debug)]
    fn from(t: node::Expression) -> Self {
        // The length difference between `code` and `viz_code` so far.
        let mut shift = 0.byte();
        let mut span_tree: SpanTree = t.input_span_tree.map(|()| port::Model::default());
        let mut viz_code = String::new();
        let code = t.code;
        span_tree.root_ref_mut().dfs_with_layer_data(ExprConversion::default(), |node, info| {
            let is_expected_arg = node.is_expected_argument();
            let span = node.span();
            // TODO: remove unwrap. (https://www.pivotaltracker.com/story/show/183567590)
            let mut size = Byte::try_from(span.size()).unwrap();
            let mut index = span.start;
            let offset_from_prev_tok = node.offset - info.prev_tok_local_index.to_diff();
            info.prev_tok_local_index = size + node.offset;
            viz_code += &" ".repeat(offset_from_prev_tok.as_usize());
            if node.children.is_empty() {
                viz_code += &code.as_str()[enso_text::Range::new(index, index + size)];
            }
            index += shift;
            if is_expected_arg {
                if let Some(name) = node.name() {
                    size = name.len().into();
                    index += 1.byte();
                    shift += 1.byte() + size;
                    viz_code += " ";
                    viz_code += name;
                }
            }
            let port = node.payload_mut();
            port.local_index = index - info.last_parent_tok_index;
            port.index = index.into();
            port.length = size.into();
            ExprConversion::new(index)
        });
        Self { viz_code: viz_code.into(), code, span_tree }
    }
}



// =============
// === Model ===
// =============

/// Internal model of the port area.
#[derive(Debug)]
pub struct Model {
    app:             Application,
    display_object:  display::object::Instance,
    ports:           display::object::Instance,
    header:          display::object::Instance,
    /// Text label used for displaying the ports. Contains both expression text and inserted
    /// argument placeholders. The style is adjusted based on port types.
    ports_label:     text::Text,
    /// Text label used during edit mode. Contains only the expression text without any
    /// modifications. Handles user input in edit mode.
    edit_mode_label: text::Text,
    expression:      RefCell<Expression>,
    id_crumbs_map:   RefCell<HashMap<ast::Id, Crumbs>>,
    widgets_map:     RefCell<HashMap<WidgetBind, Crumbs>>,
    styles:          StyleWatch,
    styles_frp:      StyleWatchFrp,
}

#[derive(Debug, Clone, Hash, PartialEq, Eq)]
struct WidgetBind {
    call_id:       ast::Id,
    argument_name: String,
}

impl Model {
    /// Constructor.
    #[profile(Debug)]
    pub fn new(app: &Application) -> Self {
        let display_object = display::object::Instance::new();
        let ports = display::object::Instance::new();
        let header = display::object::Instance::new();
        let app = app.clone_ref();
        let edit_mode_label = app.new_view::<text::Text>();
        let ports_label = app.new_view::<text::Text>();
        let id_crumbs_map = default();
        let expression = default();
        let styles = StyleWatch::new(&app.display.default_scene.style_sheet);
        let styles_frp = StyleWatchFrp::new(&app.display.default_scene.style_sheet);
        let widgets_map = default();
        display_object.add_child(&ports);
        ports.add_child(&header);
        Self {
            app,
            display_object,
            ports,
            header,
            edit_mode_label,
            ports_label,
            expression,
            id_crumbs_map,
            widgets_map,
            styles,
            styles_frp,
        }
        .init()
    }

    /// React to edit mode change. Shows and hides appropriate child views according to current
    /// mode. Sets cursor position when entering edit mode.
    pub fn set_edit_mode(&self, edit_mode_active: bool) {
        if edit_mode_active {
            // When transitioning to edit mode, we need to find the code location that corresponds
            // to the code at mouse position. First we search for the port at that position, then
            // find the right character index within that port.

            let expression = self.expression.borrow();
            let clicked_label_location = self.ports_label.location_at_mouse_position();
            let clicked_char_index =
                expression.viz_code.char_indices().nth(clicked_label_location.offset.into());
            let location_to_set = clicked_char_index.and_then(|char_index| {
                let loc_offset = char_index.0.byte().to_diff();
                let clicked_port = expression.span_tree.root_ref().leaf_iter().find(|node| {
                    let range = node.payload.range();
                    range.contains(&loc_offset)
                })?;

                let byte_offset_within_port = loc_offset - clicked_port.payload.index;
                let byte_offset_within_port = byte_offset_within_port.min(clicked_port.size);
                let final_code_byte_offset = clicked_port.span_offset + byte_offset_within_port;

                let final_code_column: Column =
                    expression.code[..final_code_byte_offset.into()].chars().count().into();
                let final_code_location = clicked_label_location.with_offset(final_code_column);
                Some(final_code_location)
            });

            self.edit_mode_label.set_content(expression.code.clone());
            self.display_object.remove_child(&self.ports);
            self.display_object.remove_child(&self.ports_label);
            self.display_object.add_child(&self.edit_mode_label);
            if let Some(location) = location_to_set {
                self.edit_mode_label.set_cursor(location);
            } else {
                // If we were unable to find a port under current mouse position, set the edit label
                // cursor at the mouse position immediately after setting its content to the raw
                // expression code.
                self.edit_mode_label.set_cursor_at_mouse_position();
            }
        } else {
            self.display_object.remove_child(&self.edit_mode_label);
            self.display_object.add_child(&self.ports);
            self.display_object.add_child(&self.ports_label);
            // When we exit the edit mode, clear the label. That way we don't have any extra glyphs
            // to process during rendering in non-edit mode.
            self.edit_mode_label.set_content("");
        }
        self.edit_mode_label.deprecated_set_focus(edit_mode_active);
    }

    #[profile(Debug)]
    fn init(self) -> Self {
        // TODO: Depth sorting of labels to in front of the mouse pointer. Temporary solution.
        //   It needs to be more flexible once we have proper depth management.
        //   See https://www.pivotaltracker.com/story/show/183567632.
        let scene = &self.app.display.default_scene;
        self.set_label_layer(&scene.layers.label);

        let text_color = self.styles.get_color(theme::graph_editor::node::text);
        self.ports_label.set_property_default(text_color);
        self.ports_label.set_property_default(text::Size(TEXT_SIZE));

        self.edit_mode_label.set_single_line_mode(true);
        self.edit_mode_label.disable_command("cursor_move_up");
        self.edit_mode_label.disable_command("cursor_move_down");
        self.edit_mode_label.disable_command("add_cursor_at_mouse_position");
        self.edit_mode_label.set_property_default(text_color);
        self.edit_mode_label.set_property_default(text::Size(TEXT_SIZE));
        self.edit_mode_label.remove_all_cursors();

        let ports_origin = Vector2(TEXT_OFFSET, 0.0);
        let label_origin = Vector2(TEXT_OFFSET, TEXT_SIZE / 2.0);
        self.ports.set_xy(ports_origin);
        self.ports_label.set_xy(label_origin);
        self.edit_mode_label.set_xy(label_origin);
        self.set_edit_mode(false);

        self
    }

    /// Return a list of Node's input ports.
    pub fn ports(&self) -> Vec<port::Model> {
        let expression = self.expression.borrow();
        let mut ports = Vec::new();
        expression.span_tree.root_ref().dfs(|n| ports.push(n.payload.clone()));
        ports
    }


    fn set_label_layer(&self, layer: &display::scene::Layer) {
        self.edit_mode_label.add_to_scene_layer(layer);
        self.ports_label.add_to_scene_layer(layer);
    }

    /// Run the provided function on the target port if exists.
    fn with_port_mut(&self, crumbs: &Crumbs, f: impl FnOnce(PortRefMut)) {
        let mut expression = self.expression.borrow_mut();
        if let Ok(node) = expression.span_tree.root_ref_mut().get_descendant(crumbs) {
            f(node)
        }
    }

    /// Traverse all `SpanTree` leaves of the given port and emit hover style to set their colors.
    fn set_port_hover(&self, target: &Switch<Crumbs>) {
        self.with_port_mut(&target.value, |t| t.set_hover(target.is_on()))
    }

    /// Update expression type for the particular `ast::Id`.
    #[profile(Debug)]
    fn set_expression_usage_type(&self, crumbs: &Crumbs, tp: &Option<Type>) {
        if let Ok(port) = self.expression.borrow().span_tree.root_ref().get_descendant(crumbs) {
            port.set_usage_type(tp)
        }
    }

    /// Apply widget updates to widgets in this input area.
    fn apply_widget_updates(&self, updates: &WidgetUpdates) {
        let expression = self.expression.borrow();
        let widgets_map = self.widgets_map.borrow();
        let WidgetUpdates { call_id, updates } = updates;
        for update in updates.iter() {
            let argument_name = update.argument_name.to_string();
            let widget_id = WidgetBind { call_id: *call_id, argument_name };
            let crumbs = widgets_map.get(&widget_id);

            let root = expression.span_tree.root_ref();
            let port = crumbs.and_then(|crumbs| root.get_descendant(crumbs).ok());
            let widget = port.and_then(|port| port.payload.widget.clone_ref());

            // When a widget is found, update it. Failing to find a widget is not an error, as it
            // might be a widget that was removed from the expression while the request was pending.
            // If it comes back, the widget data will be requested again.
            if let Some(widget) = widget {
                widget.set_metadata(update.meta.clone());
            }
        }
    }

    #[profile(Debug)]
    fn set_label_on_new_expression(&self, expression: &Expression) {
        self.ports_label.set_content(expression.viz_code.clone());
    }

    #[profile(Debug)]
    fn build_port_shapes_on_new_expression(
        &self,
        expression: &mut Expression,
        area_frp: &FrpEndpoints,
        call_info: &CallInfoMap,
    ) {
        let mut is_header = true;

        let mut id_crumbs_map = HashMap::new();
        let mut widgets_map = HashMap::new();
        let builder = PortLayerBuilder::empty(&self.ports);
        let code = &expression.viz_code;

        expression.span_tree.root_ref_mut().dfs_with_layer_data(builder, |mut node, builder| {
            let skip_opr = if SKIP_OPERATIONS {
                node.is_operation() && !is_header
            } else {
                let crumb = ast::Crumb::Infix(ast::crumbs::InfixCrumb::Operator);
                node.ast_crumbs.last().map(|t| t == &crumb) == Some(true)
            };

            let not_a_port = node.is_positional_insertion_point()
                || node.is_chained()
                || (node.is_root() && !node.children.is_empty())
                || skip_opr
                || node.is_token()
                || node.is_named_argument()
                || builder.parent_parensed;

            if let Some(id) = node.ast_id {
                if DEBUG {
                    debug!("New id mapping: {id} -> {:?}", node.crumbs);
                }
                id_crumbs_map.insert(id, node.crumbs.clone_ref());
            }

            if DEBUG {
                let indent = " ".repeat(4 * builder.depth);
                let skipped = if not_a_port { "(skip)" } else { "" };
                debug!(
                    "{indent}[{},{}] {skipped} {:?} (tp: {:?}) (id: {:?})",
                    node.payload.index,
                    node.payload.length,
                    node.kind.variant_name(),
                    node.tp(),
                    node.ast_id
                );
            }

            let range_before_start = node.payload.index - node.payload.local_index;
            let range_before_end = node.payload.index;
            let range_before = enso_text::Range::new(range_before_start, range_before_end);
            let local_char_offset = code[range_before].chars().count();

            let new_parent = if not_a_port {
                builder.parent.clone_ref()
            } else {
                let crumbs = node.crumbs.clone_ref();
                let port = &mut node;

                let index = local_char_offset + builder.shift;
                let size = code[port.payload.range()].chars().count();
                let unit = GLYPH_WIDTH;
                let width = unit * size as f32;
                let width_padded = width + 2.0 * PORT_PADDING_X;
                let height = 18.0;
                let size = Vector2(width, height);
                let padded_size = Vector2(width_padded, height);
                let position_x = unit * index as f32;

                let port_shape = port.payload.init_shape(size, node::HEIGHT);

                port_shape.set_x(position_x);
                if DEBUG {
                    port_shape.set_y(DEBUG_PORT_OFFSET);
                }

                if is_header {
                    is_header = false;
                    self.header.add_child(&port_shape);
                } else {
                    builder.parent.add_child(&port_shape);
                }

                // TODO: StyleWatch is unsuitable here, as it was designed as an internal tool for
                //   shape system. (https://www.pivotaltracker.com/story/show/183567648)
                let style_sheet = &self.app.display.default_scene.style_sheet;
                let styles = StyleWatch::new(style_sheet);
                let styles_frp = &self.styles_frp;
                let any_type_sel_color = styles_frp.get_color(theme::code::types::any::selection);
                let port_network = &port.network;

                frp::extend! { port_network

                    // === Aliases ===

                    let mouse_over_raw = port_shape.hover.events_deprecated.mouse_over.clone_ref();
                    let mouse_out      = port_shape.hover.events_deprecated.mouse_out.clone_ref();
                    let mouse_down_raw = port_shape.hover.events_deprecated.mouse_down_primary.clone_ref();


                    // === Body Hover ===

                    // This is meant to be on top of FRP network. Read more about `Node` docs to
                    // learn more about the architecture and the importance of the hover
                    // functionality.

                    // Please note, that this is computed first in order to compute `ports_visible`
                    // when needed, and thus it has to be run before the following lines.
                    area_frp.source.body_hover <+ bool(&mouse_out,&mouse_over_raw);

                    // TODO[WD] for FRP3: Consider the following code. Here, we have to first
                    //     handle `bg_down` and then `mouse_down`. Otherwise, `mouse_down` may
                    //     trigger some events and can change `ports_visible` status, and thus make
                    //     the `bg_down` emitted unnecessarily. For example, after plugging in
                    //     connections to selected port, the `ports_visible` will be set to `false`,
                    //     and `bg_down` will be emitted, causing the node to be selected. This can
                    //     be solved by solving in the FRP engine all children first, and then their
                    //     children (then both `bg_down` and `mouse_down` will be resolved before
                    //     the `ports_visible` changes).
                    bg_down    <- mouse_down_raw.gate_not(&area_frp.ports_visible);
                    mouse_down <- mouse_down_raw.gate(&area_frp.ports_visible);
                    mouse_over <- mouse_over_raw.gate(&area_frp.ports_visible);
                    area_frp.source.on_background_press <+ bg_down;


                    // === Press ===

                    area_frp.source.on_port_press <+ mouse_down.map(f_!([crumbs] crumbs.clone_ref()));

                    // === Hover ===

                    hovered <- bool(&mouse_out,&mouse_over);
                    hover   <- hovered.map (f!([crumbs](t) Switch::new(crumbs.clone_ref(),*t)));
                    area_frp.source.on_port_hover <+ hover;


                    // === Pointer Style ===

                    let port_shape_hover = port_shape.hover.clone_ref();
                    pointer_style_out   <- mouse_out.map(|_| default());

                    init_color         <- source::<()>();
                    any_type_sel_color <- all_with(&any_type_sel_color,&init_color,
                        |c,_| color::Lcha::from(c));
                    tp                 <- all_with(&port.tp,&area_frp.set_ports_active,
                        |tp,(_,edge_tp)| tp.clone().or_else(||edge_tp.clone()));
                    tp_color           <- tp.map(
                        f!([styles](tp) tp.map_ref(|tp| type_coloring::compute(tp,&styles))));
                    tp_color           <- all_with(&tp_color,&any_type_sel_color,
                        |tp_color,any_type_sel_color| tp_color.unwrap_or(*any_type_sel_color));
                    in_profiling_mode  <- area_frp.view_mode.map(|m| matches!(m,view::Mode::Profiling));
                    pointer_color_over <- in_profiling_mode.switch(&tp_color,&any_type_sel_color);
                    pointer_style_over <- pointer_color_over.map(move |color|
                        cursor::Style::new_highlight(&port_shape_hover,padded_size,Some(color))
                    );
                    pointer_style_over <- pointer_style_over.sample(&mouse_over);

                    pointer_style_hover <- any(pointer_style_over,pointer_style_out);
                    pointer_styles      <- all[
                        pointer_style_hover,
                        self.ports_label.pointer_style,
                        self.edit_mode_label.pointer_style
                    ];
                    pointer_style       <- pointer_styles.fold();
                    area_frp.source.pointer_style <+ pointer_style;
                }

                let port_range = port.span();
                let port_code = &expression.code[port_range];
                if let Some((widget_bind, widget)) = self.init_port_widget(port, size, call_info) {
                    widgets_map.insert(widget_bind, crumbs.clone_ref());
                    widget.set_x(position_x);
                    builder.parent.add_child(&widget);
                    if port.is_argument() {
                        debug!("Setting current value while range is {port_range:?}, code is \"{port_code}\" \
                            and full expression is \"{}\".", expression.code);
                        widget.set_current_value(Some(port_code.into()));
                    } else {
                        widget.set_current_value(None);
                    }
                    widget.set_visible(true);

                    let port_network = &port.network;
                    frp::extend! { port_network
                        code_update <- widget.value_changed.map(f!([crumbs](value) {
                            let expression = value.clone().unwrap_or_default();
                            (crumbs.clone_ref(), expression)
                        }));
                        area_frp.source.on_port_code_update <+ code_update;
                        area_frp.source.request_import <+ widget.request_import;
                        widget.set_read_only <+ area_frp.set_read_only;
                    }
                }

                init_color.emit(());

                port_shape.display_object().clone_ref()
            };

            if let Some(parent_frp) = &builder.parent_frp {
                frp::extend! { port_network
                    node.frp.set_active           <+ parent_frp.set_active;
                    node.frp.set_hover            <+ parent_frp.set_hover;
                    node.frp.set_parent_connected <+ parent_frp.set_parent_connected;
                }
            }
            let new_parent_frp = Some(node.frp.output.clone_ref());
            let new_shift = if !not_a_port { 0 } else { builder.shift + local_char_offset };
            let parenthesized = node.parenthesized();
            builder.nested(new_parent, new_parent_frp, parenthesized, new_shift)
        });
        *self.id_crumbs_map.borrow_mut() = id_crumbs_map;
        *self.widgets_map.borrow_mut() = widgets_map;
        area_frp.set_view_mode.emit(area_frp.view_mode.value());
    }

    fn init_port_widget(
        &self,
        port: &mut PortRefMut,
        port_size: Vector2<f32>,
        call_info: &CallInfoMap,
    ) -> Option<(WidgetBind, widget::View)> {
        let call_id = port.kind.call_id().filter(|id| call_info.has_target(id))?;
        let argument_name = port.kind.argument_name()?.to_owned();

        let widget_bind = WidgetBind { call_id, argument_name };


        // Try getting the previous widget by exact target/argument ID first, which is
        // necessary when the argument expression was replaced. This lookup can fail
        // when the target expression was replaced, but the widget argument expression
        // wasn't. In that case, try to reuse the widget from old argument node under
        // the same ast ID.
        let prev_widgets_map = self.widgets_map.borrow();
        let prev_id_crumbs_map = self.id_crumbs_map.borrow();
        let prev_crumbs = prev_widgets_map
            .get(&widget_bind)
            .or_else(|| port.ast_id.as_ref().and_then(|id| prev_id_crumbs_map.get(id)));
        let prev_widget = prev_crumbs.and_then(|crumbs| {
            let prev_expression = self.expression.borrow();
            let prev_root = prev_expression.span_tree.root_ref();
            let prev_node = prev_root.get_descendant(crumbs).ok()?;
            let prev_widget = prev_node.payload.widget.as_ref()?.clone_ref();
            Some(prev_widget)
        });

        let widget = match prev_widget {
            Some(prev_widget) => port.payload.use_existing_widget(prev_widget),
            None => port.payload.init_widget(&self.app),
        };

        let tag_values = port.kind.tag_values().unwrap_or_default().to_vec();
        let tp = port.kind.tp().cloned();
        widget.set_node_data(widget::NodeData { tag_values, port_size, tp });

        Some((widget_bind, widget))
    }

    /// Initializes FRP network for every port. Please note that the networks are connected
    /// hierarchically (children get events from parents), so it is easier to init all networks
    /// this way, rather than delegate it to every port.
    #[profile(Debug)]
    fn init_port_frp_on_new_expression(
        &self,
        expression: &mut Expression,
        area_frp: &FrpEndpoints,
    ) {
        let model = &self;

        let parent_tp: Option<frp::Stream<Option<Type>>> = None;
        expression.root_ref_mut().dfs_with_layer_data(parent_tp, |node, parent_tp| {
            let frp = &node.frp;
            let port_network = &frp.network;
            let is_token = node.is_token();
            let crumbs = node.crumbs.clone();


            // === Type Computation ===
            let parent_tp = parent_tp.clone().unwrap_or_else(|| {
                frp::extend! { port_network
                    empty_parent_tp <- source::<Option<Type>>();
                }
                empty_parent_tp.into()
            });
            frp::extend! { port_network
                final_tp <- all_with3(&parent_tp,&frp.set_definition_type,&frp.set_usage_type,
                    move |parent_tp,def_tp,usage_tp| {
                        usage_tp.clone().or_else(||
                            if is_token {parent_tp.clone()} else {def_tp.clone()}
                        )
                    }
                );
                frp.source.tp <+ final_tp;

                area_frp.source.on_port_type_change <+ frp.tp.map(move |t|(crumbs.clone(),t.clone()));
            }


            // === Code Coloring ===

            let styles = model.styles.clone_ref();
            let styles_frp = model.styles_frp.clone_ref();

            if node.children.is_empty() {
                let is_expected_arg = node.is_expected_argument();

                use theme::code::syntax;
                let selected_color = styles_frp.get_color(theme::code::types::selected);
                let std_base_color = styles_frp.get_color(syntax::base);
                let std_disabled_color = styles_frp.get_color(syntax::disabled);
                let std_expected_color = styles_frp.get_color(syntax::expected);
                let std_editing_color = styles_frp.get_color(syntax::base);
                let profiled_base_color = styles_frp.get_color(syntax::profiling::base);
                let profiled_disabled_color = styles_frp.get_color(syntax::profiling::disabled);
                let profiled_expected_color = styles_frp.get_color(syntax::profiling::expected);
                let profiled_editing_color = styles_frp.get_color(syntax::profiling::base);

                frp::extend! { port_network
                    in_profiling_mode <- area_frp.view_mode.map(|m| m.is_profiling());
                    finished          <- area_frp.set_profiling_status.map(|s| s.is_finished());
                    profiled          <- in_profiling_mode && finished;
                    selected          <- frp.set_hover || frp.set_parent_connected;

                    init_colors         <- source::<()>();
                    std_base_color      <- all(std_base_color,init_colors)._0();
                    profiled_base_color <- all(profiled_base_color,init_colors)._0();

                    profiling_color <- finished.switch(&std_base_color,&profiled_base_color);
                    normal_color    <- frp.tp.map(f!([styles](t)
                        color::Rgba::from(type_coloring::compute_for_code(t.as_ref(),&styles))));
                    base_color      <- in_profiling_mode.switch(&normal_color,&profiling_color);

                    disabled_color <- profiled.switch(&std_disabled_color,&profiled_disabled_color);
                    expected_color <- profiled.switch(&std_expected_color,&profiled_expected_color);
                    editing_color  <- profiled.switch(&std_editing_color,&profiled_editing_color);
                    // TODO: `label_color` should be animated, when when we can set text colors
                    //  more efficiently. (See https://www.pivotaltracker.com/story/show/183567665)
                    label_color <- all_with8(
                        &area_frp.editing,
                        &selected,
                        &area_frp.set_disabled,
                        &editing_color,
                        &selected_color,
                        &disabled_color,
                        &expected_color,
                        &base_color,
                        move |&editing,
                              &selected,
                              &disabled,
                              &editing_color,
                              &selected_color,
                              &disabled_color,
                              &expected_color,
                              &base_color| {
                            if editing {
                                color::Lcha::from(editing_color)
                            } else if selected {
                                color::Lcha::from(selected_color)
                            } else if disabled {
                                color::Lcha::from(disabled_color)
                            } else if is_expected_arg {
                                color::Lcha::from(expected_color)
                            } else {
                                color::Lcha::from(base_color)
                            }
                        },
                    );
                }

                let index = node.payload.index;
                let length = node.payload.length;
                let label = model.ports_label.clone_ref();
                frp::extend! { port_network
                    eval label_color ([label](color) {
                        let range = enso_text::Range::new(index, index + length);
                        // TODO: remove unwrap. (https://www.pivotaltracker.com/story/show/183567590)
                        let range = enso_text::Range::<Byte>::try_from(range).unwrap();
                        label.set_property(range,color::Rgba::from(color));
                    });
                }

                init_colors.emit(());
                area_frp.set_view_mode(area_frp.view_mode.value());
            }


            // === Highlight Coloring ===

            if let Some(port_shape) = &node.payload.shape {
                let viz_color = color::Animation::new(port_network);
                let any_type_sel_color = styles_frp.get_color(theme::code::types::any::selection);

                frp::extend! { port_network
                    normal_viz_color <- all_with(&frp.tp,&frp.set_connected,
                        f!([styles](port_tp,(_,edge_tp)) {
                            let tp = port_tp.as_ref().or(edge_tp.as_ref());
                            select_color(&styles,tp)
                        }));
                    init_color          <- source::<()>();
                    profiling_viz_color <- all_with(&any_type_sel_color,&init_color,
                        |c,_| color::Lcha::from(c));
                    profiling           <- area_frp.view_mode.map(|m| m.is_profiling());
                    connected_viz_color <- profiling.switch(&normal_viz_color,&profiling_viz_color);
                    is_connected        <- frp.set_connected.map(|(is_connected,_)| *is_connected);
                    transparent         <- init_color.constant(color::Lcha::transparent());
                    viz_color_target    <- is_connected.switch(&transparent,&connected_viz_color);

                    // We need to make sure that the network contains correct values before we
                    // connect the `viz_color` animation. The reason is that the animation will
                    // start from the first value that it receives, and during initialization of the
                    // network, while some nodes are still set to their defaults, this first  value
                    // would be incorrect, causing the animation in some cases to start from black
                    // (the default color) and animating towards the color that we really want to
                    // set.
                    init_color.emit(());

                    viz_color.target    <+ viz_color_target;
                    eval viz_color.value ((t)
                        port_shape.viz.color.set(color::Rgba::from(t).into())
                    );
                }
            }
            Some(frp.tp.clone_ref().into())
        });

        area_frp.set_view_mode(area_frp.view_mode.value());
    }

    /// This function first assigns the new expression to the model and then emits the definition
    /// type signals to all port FRP networks.
    ///
    /// As a design note, it is important to first assign the expression to the model, as the FRP
    /// signals can cause other parts of the network to fire, which may query the expression types.
    /// For example, firing the `port::set_definition_type` will fire `on_port_type_change`, which
    /// may require some edges to re-color, which consequently will require to checking the current
    /// expression types.
    #[profile(Debug)]
    fn init_new_expression(
        &self,
        expression: Expression,
        area_frp: &FrpEndpoints,
        call_info: &CallInfoMap,
    ) {
        *self.expression.borrow_mut() = expression;
        let expression = self.expression.borrow();
        expression.root_ref().dfs_with_layer_data((), |node, _| {
            node.frp.set_definition_type(node.tp().cloned().map(|t| t.into()));
            let call_id = node.kind.call_id();
            let widget_request =
                call_id.and_then(|call_id| Some((call_id, call_info.target(&call_id)?)));
            if let Some(widget_request) = widget_request {
                area_frp.source.requested_widgets.emit(widget_request);
            }
        });
    }

    /// Set a displayed expression, updating the input ports. `is_editing` indicates whether the
    /// expression is being edited by the user.
    #[profile(Debug)]
    fn set_expression(&self, new_expression: impl Into<node::Expression>, area_frp: &FrpEndpoints) {
        let mut new_expression = Expression::from(new_expression.into());
        if DEBUG {
            debug!("set expression: \n{:?}", new_expression.tree_pretty_printer());
        }

        let call_info = CallInfoMap::scan_expression(&new_expression);
        self.set_label_on_new_expression(&new_expression);
        self.build_port_shapes_on_new_expression(&mut new_expression, area_frp, &call_info);
        self.init_port_frp_on_new_expression(&mut new_expression, area_frp);
        self.init_new_expression(new_expression.clone(), area_frp, &call_info);
    }
}

fn select_color(styles: &StyleWatch, tp: Option<&Type>) -> color::Lcha {
    let opt_color = tp.as_ref().map(|tp| type_coloring::compute(tp, styles));
    opt_color.unwrap_or_else(|| styles.get_color(theme::code::types::any::selection).into())
}



// ===========
// === FRP ===
// ===========

ensogl::define_endpoints! {
    Input {
        /// Set the node expression.
        set_expression (node::Expression),

        /// Edit the node expression: if the node is currently edited, the given range will be
        /// replaced with the string, and the text cursor will be placed after the inserted string.
        ///
        /// If the node is **not** edited, nothing changes.
        edit_expression (text::Range<Byte>, ImString),

        /// Set the mode in which the cursor will indicate that editing of the node is possible.
        set_edit_ready_mode (bool),

        /// Enable or disable node editing.
        set_editing (bool),

        /// Set or unset hover over the node. Port area is unable to determine hover by itself, as
        /// the hover may sometimes happen on the node background and the area still needs to be
        /// notified about it, for example in order to display the right cursor style in edit ready
        /// mode.
        set_hover (bool),

        /// Disable the node (aka "skip mode").
        set_disabled (bool),

        /// Set the connection status of the port indicated by the breadcrumbs. The optional type
        /// is the type of the edge that was connected or disconnected if the edge was typed.
        set_connected (Crumbs,Option<Type>,bool),

        /// Set the expression USAGE type. This is not the definition type, which can be set with
        /// `set_expression` instead. In case the usage type is set to None, ports still may be
        /// colored if the definition type was present.
        set_expression_usage_type (Crumbs,Option<Type>),

        /// Update widget metadata for widgets already present in this input area.
        update_widgets   (WidgetUpdates),

        /// Enable / disable port hovering. The optional type indicates the type of the active edge
        /// if any. It is used to highlight ports if they are missing type information or if their
        /// types are polymorphic.
        set_ports_active (bool,Option<Type>),

        set_view_mode        (view::Mode),
        set_profiling_status (profiling::Status),

        /// Set read-only mode for input ports.
        set_read_only (bool),
    }

    Output {
        pointer_style       (cursor::Style),
        width               (f32),
        expression_edit     (ImString, Vec<Selection<Byte>>),

        editing             (bool),
        ports_visible       (bool),
        body_hover          (bool),
        on_port_press       (Crumbs),
        on_port_hover       (Switch<Crumbs>),
        on_port_type_change (Crumbs,Option<Type>),
        on_port_code_update (Crumbs,ImString),
        on_background_press (),
        view_mode           (view::Mode),
        /// A set of widgets attached to a method requires metadata to be queried. The tuple
        /// contains the ID of the call expression the widget is attached to, and the ID of that
        /// call's target expression (`self` or first argument).
        requested_widgets    (ast::Id, ast::Id),
        request_import       (ImString),
    }
}



// ============
// === Area ===
// ============

/// Input ports area.
///
/// ## Origin
/// Please note that the origin of the node is on its left side, centered vertically. To learn more
/// about this design decision, please read the docs for the [`node::Node`].
#[derive(Clone, CloneRef, Debug)]
pub struct Area {
    #[allow(missing_docs)]
    pub frp:          Frp,
    pub(crate) model: Rc<Model>,
}

impl Deref for Area {
    type Target = Frp;
    fn deref(&self) -> &Self::Target {
        &self.frp
    }
}

impl Area {
    /// Constructor.
    #[profile(Debug)]
    pub fn new(app: &Application) -> Self {
        let model = Rc::new(Model::new(app));
        let frp = Frp::new();
        let network = &frp.network;
        let selection_color = Animation::new(network);

        frp::extend! { network
            init <- source::<()>();
            set_editing <- all(frp.set_editing, init)._0();

            // === Body Hover ===
            // This is meant to be on top of FRP network. Read more about `Node` docs to
            // learn more about the architecture and the importance of the hover
            // functionality.

            frp.output.source.body_hover <+ frp.set_hover;


            // === Cursor setup ===

            eval set_editing((is_editing) model.set_edit_mode(*is_editing));

            // Prevent text selection from being created right after entering edit mode. Otherwise,
            // a selection would be created between the current mouse position (the position at
            // which we clicked) and initial cursor position within edit mode label (the code
            // position corresponding to clicked port).
            start_editing <- set_editing.on_true();
            stop_editing  <- set_editing.on_false();
            start_editing_delayed     <- start_editing.debounce();
            reenable_selection_update <- any(&start_editing_delayed, &stop_editing);
            selection_update_enabled  <- bool(&start_editing, &reenable_selection_update);
            eval selection_update_enabled([model] (enabled) {
                let cmd_start = "start_newest_selection_end_follow_mouse";
                let cmd_stop = "stop_newest_selection_end_follow_mouse";
                model.edit_mode_label.set_command_enabled(cmd_start, *enabled);
                model.edit_mode_label.set_command_enabled(cmd_stop, *enabled);
            });


            // === Show / Hide Phantom Ports ===

            let ports_active = &frp.set_ports_active;
            edit_or_ready   <- frp.set_edit_ready_mode || set_editing;
            reacts_to_hover <- all_with(&edit_or_ready, ports_active, |e, (a, _)| *e && !a);
            port_vis        <- all_with(&edit_or_ready, ports_active, |e, (a, _)| !e && *a);
            frp.output.source.ports_visible <+ port_vis;
            frp.output.source.editing       <+ set_editing;


            // === Label Hover ===

            label_hovered <- reacts_to_hover && frp.output.body_hover;
            not_editing  <- set_editing.not();
            model.ports_label.set_hover <+ label_hovered && not_editing;
            model.edit_mode_label.set_hover <+ label_hovered && set_editing;

            // === Port Hover ===

            eval frp.on_port_hover ((t) model.set_port_hover(t));

            eval frp.set_connected ([model]((crumbs,edge_tp,is_connected)) {
                model.with_port_mut(crumbs,|n|n.set_connected(is_connected,edge_tp));
                model.with_port_mut(crumbs,|n|n.set_parent_connected(is_connected));
            });


            // === Properties ===

            label_width <- set_editing.switch(
                &model.ports_label.width,
                &model.edit_mode_label.width
            );
            frp.output.source.width <+ label_width.map(|t| t + 2.0 * TEXT_OFFSET);


            // === Expression ===

            let frp_endpoints = &frp.output;
            eval frp.set_expression([frp_endpoints, model](expr) model.set_expression(expr, &frp_endpoints));
            legit_edit <- frp.input.edit_expression.gate(&set_editing);
            model.edit_mode_label.select <+ legit_edit.map(|(range, _)| (range.start.into(), range.end.into()));
            model.edit_mode_label.insert <+ legit_edit._1();
            expression_changed_by_user <- model.edit_mode_label.content.gate(&set_editing);
            frp.output.source.expression_edit <+ model.edit_mode_label.selections.map2(
                &expression_changed_by_user,
                f!([model](selection, full_content) {
                    let full_content = full_content.into();
                    let to_byte = |loc| text::Byte::from_in_context_snapped(&model.edit_mode_label, loc);
                    let selections = selection.iter().map(|sel| sel.map(to_byte)).collect_vec();
                    (full_content, selections)
                })
            );
            frp.output.source.on_port_code_update <+ expression_changed_by_user.map(|e| {
                // Treat edit mode update as a code modification at the span tree root.
                (default(), e.into())
            });


            // === Expression Type ===

            eval frp.set_expression_usage_type (((a,b)) model.set_expression_usage_type(a,b));

            // === Widgets ===

            eval frp.update_widgets ((a) model.apply_widget_updates(a));

            // === View Mode ===

            frp.output.source.view_mode <+ frp.set_view_mode;

            in_profiling_mode <- frp.view_mode.map(|m| m.is_profiling());
            finished          <- frp.set_profiling_status.map(|s| s.is_finished());
            profiled          <- in_profiling_mode && finished;

            use theme::code::syntax;
            let std_selection_color      = model.styles_frp.get_color(syntax::selection);
            let profiled_selection_color = model.styles_frp.get_color(syntax::profiling::selection);
            let std_base_color           = model.styles_frp.get_color(syntax::base);
            let profiled_base_color      = model.styles_frp.get_color(syntax::profiling::base);

            selection_color_rgba <- profiled.switch(&std_selection_color,&profiled_selection_color);

            selection_color.target          <+ selection_color_rgba.map(|c| color::Lcha::from(c));
            model.ports_label.set_selection_color <+ selection_color.value.map(|c| color::Lch::from(c));

            std_base_color      <- all(std_base_color,init)._0();
            profiled_base_color <- all(profiled_base_color,init)._0();
            base_color          <- profiled.switch(&std_base_color,&profiled_base_color);
            eval base_color ((color) model.ports_label.set_property_default(color));
        }

        init.emit(());

        Self { frp, model }
    }

    /// An offset from node position to a specific port.
    pub fn port_offset(&self, crumbs: &[Crumb]) -> Option<Vector2<f32>> {
        let expr = self.model.expression.borrow();
        expr.root_ref().get_descendant(crumbs).ok().map(|node| {
            let unit = GLYPH_WIDTH;
            let range_before = enso_text::Range::new(ByteDiff(0), node.payload.index);
            let char_offset = expr.viz_code[range_before].chars().count();
            let char_count = expr.viz_code[node.payload.range()].chars().count();
            let width = unit * (char_count as f32);
            let x = width / 2.0 + unit * (char_offset as f32);
            Vector2::new(TEXT_OFFSET + x, 0.0)
        })
    }

    /// A type of the specified port.
    pub fn port_type(&self, crumbs: &Crumbs) -> Option<Type> {
        let expression = self.model.expression.borrow();
        expression.span_tree.root_ref().get_descendant(crumbs).ok().and_then(|t| t.tp.value())
    }

    /// A crumb by AST ID.
    pub fn get_crumbs_by_id(&self, id: ast::Id) -> Option<Crumbs> {
        self.model.id_crumbs_map.borrow().get(&id).cloned()
    }

    /// Set a scene layer for text rendering.
    pub fn set_label_layer(&self, layer: &display::scene::Layer) {
        self.model.set_label_layer(layer);
    }
}



// ==========================
// === Expression Setting ===
// ==========================

/// Helper struct used to keep information about the current expression layer when building visual
/// port representation. A "layer" is a visual layer in terms of span tree. For example, given
/// expression `img.blur (foo (bar baz))`, we've got several layers, like the whole expression,
/// `img.blur`, `foo (bar baz)`, or `(bar baz)`. The layer builder keeps information passed from the
/// parent layer when building the nested one.
#[derive(Clone, Debug)]
struct PortLayerBuilder {
    parent_frp:      Option<port::FrpEndpoints>,
    /// Parent port display object.
    parent:          display::object::Instance,
    /// Information whether the parent port was a parensed expression.
    parent_parensed: bool,
    /// The number of chars the expression should be shifted. For example, consider
    /// `(foo bar)`, where expression `foo bar` does not get its own port, and thus a 1 char
    /// shift should be applied when considering its children.
    shift:           usize,
    /// The depth at which the current expression is, where root is at depth 0.
    depth:           usize,
}

impl PortLayerBuilder {
    /// Constructor.
    #[profile(Debug)]
    fn new(
        parent: impl display::Object,
        parent_frp: Option<port::FrpEndpoints>,
        parent_parensed: bool,
        shift: usize,
        depth: usize,
    ) -> Self {
        let parent = parent.display_object().clone_ref();
        Self { parent_frp, parent, parent_parensed, shift, depth }
    }

    fn empty(parent: impl display::Object) -> Self {
        Self::new(parent, default(), default(), default(), default())
    }

    /// Create a nested builder with increased depth and updated `parent_frp`.
    #[profile(Debug)]
    fn nested(
        &self,
        parent: display::object::Instance,
        new_parent_frp: Option<port::FrpEndpoints>,
        parent_parensed: bool,
        shift: usize,
    ) -> Self {
        let depth = self.depth + 1;
        let parent_frp = new_parent_frp.or_else(|| self.parent_frp.clone());
        Self::new(parent, parent_frp, parent_parensed, shift, depth)
    }
}

impl display::Object for Area {
    fn display_object(&self) -> &display::object::Instance {
        &self.model.display_object
    }
}

/// ===================
/// === CallInfoMap ===
/// ===================

#[derive(Debug, Deref)]
struct CallInfoMap {
    /// The map from node's call_id to call information.
    call_info: HashMap<ast::Id, CallInfo>,
}

/// Information about the call expression, which are derived from the span tree.
#[derive(Debug, Default)]
struct CallInfo {
    /// The AST ID associated with `self` argument span of the call expression.
    target_id: Option<ast::Id>,
}

impl CallInfoMap {
    fn scan_expression(expression: &SpanTree) -> Self {
        let mut call_info: HashMap<ast::Id, CallInfo> = HashMap::new();
        expression.root_ref().dfs(|node| {
            if let Some(call_id) = node.kind.call_id() {
                let mut entry = call_info.entry(call_id).or_default();
                if entry.target_id.is_none() || node.kind.is_this() {
                    entry.target_id = node.ast_id;
                }
            }
        });

        Self { call_info }
    }

    fn has_target(&self, call_id: &ast::Id) -> bool {
        self.call_info.get(call_id).map_or(false, |info| info.target_id.is_some())
    }

    fn target(&self, call_id: &ast::Id) -> Option<ast::Id> {
        self.call_info.get(call_id).and_then(|info| info.target_id)
    }
}
