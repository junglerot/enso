//! Breadcrumbs of the Component Browser. It displays a stack of entered module names.
//!
//! To learn more about the Component Browser and its components, see the [Component Browser
//! Design Document](https://github.com/enso-org/design/blob/e6cffec2dd6d16688164f04a4ef0d9dff998c3e7/epics/component-browser/design.md).
//!
//! The Breadcrumbs component is displayed as a horizontal list composed of individual breadcrumb
//! entries (simple text labels) separated by [`entry::Model::Separator`] icons and an optional
//! [`entry::Model::Ellipsis`] icon at the end of the list. The ellipsis icon shows that the last
//! module has ancestors and can be further expanded.
//!
//! The user can select breadcrumbs either by mouse or by keyboard. After switching to a
//! higher-level breadcrumb, the lower-level breadcrumbs should become grayed out, letting the
//! user switch back fast.
//!
//! The implementation is based on the [`grid_view::GridView`] with a single row and a variable
//! number of columns. A custom entry type for the Grid View is implemented in the [`entry`]
//! module. Each entry has three different representations: a text label, a separator icon, and an
//! ellipsis icon, and it can switch between these representations if needed.

#![recursion_limit = "1024"]
// === Features ===
#![feature(option_result_contains)]
#![feature(derive_default_enum)]
#![feature(trait_alias)]
#![feature(hash_drain_filter)]
#![feature(bool_to_option)]
// === Standard Linter Configuration ===
#![deny(non_ascii_idents)]
#![warn(unsafe_code)]
// === Non-Standard Linter Configuration ===
#![warn(missing_copy_implementations)]
#![warn(missing_debug_implementations)]
#![warn(missing_docs)]
#![warn(trivial_casts)]
#![warn(trivial_numeric_casts)]
#![warn(unused_import_braces)]
#![warn(unused_qualifications)]

use ensogl_core::prelude::*;

use component_browser_theme::searcher::list_panel::breadcrumbs as theme;
use enso_frp as frp;
use ensogl_core::application::command::FrpNetworkProvider;
use ensogl_core::application::frp::API;
use ensogl_core::application::shortcut::Shortcut;
use ensogl_core::application::Application;
use ensogl_core::data::color;
use ensogl_core::display;
use ensogl_core::display::scene::layer::Layer;
use ensogl_core::display::shape::StyleWatchFrp;
use ensogl_core::gui::Widget;
use ensogl_core::Animation;
use ensogl_grid_view as grid_view;
use ensogl_grid_view::Viewport;
use ensogl_hardcoded_theme::application::component_browser as component_browser_theme;
use ensogl_text as text;
use entry::Entry;
use grid_view::Col;



mod entry;



// =================
// === Constants ===
// =================

/// We virtually divide the breadcrumbs into two regions – left and right. This constant
/// determines the proportion of these regions.

/// The border between these regions is considered a resting position of the selected entry when
/// breadcrumbs don't fit into the viewport. We don't scroll the viewport if the selected entry is
/// the last one or if it is placed in the right region of the viewport. This way, we avoid
/// unnecessary scrolling when the user selects some breadcrumb close to the end of the list.
const SCROLLING_THRESHOLD_FRACTION: f32 = 0.5;



// ====================
// === Type Aliases ===
// ====================

type GridView = grid_view::selectable::GridView<Entry>;
type Entries = Rc<RefCell<Vec<Breadcrumb>>>;
type BreadcrumbId = usize;



// ============
// === Mask ===
// ============

/// A rectangular mask used to crop the breadcrumbs' content when it doesn't fit in the size set
/// by [`Frp::set_size`]. The mask covers the visible portion of the breadcrumbs. See [Layer]
/// documentation to learn more about masking.
///
/// [Layer]: ensogl_core::display::scene::layer::Layer#masking-layers-with-arbitrary-shapes
mod mask {
    use super::*;
    use ensogl_core::display::shape::*;
    ensogl_core::define_shape_system! {
        pointer_events = false;
        (style: Style, corners_radius: f32) {
            let width = Var::<Pixels>::from("input_size.x");
            let height = Var::<Pixels>::from("input_size.y");
            Rect((width, height)).corners_radius(corners_radius).fill(color::Rgba::black()).into()
        }
    }
}



// ==============
// === Layers ===
// ==============

/// A set of layers used by the breadcrumbs.
#[derive(Debug, Clone, CloneRef)]
struct Layers {
    main: Layer,
    text: Layer,
    mask: Layer,
}

impl Layers {
    /// Constructor.
    pub fn new(base_layer: &Layer) -> Self {
        let logger = Logger::new("BreadcrumbsMask");
        let mask = Layer::new_with_cam(logger, &base_layer.camera());
        let main = base_layer.create_sublayer();
        let text = main.create_sublayer();
        main.set_mask(&mask);
        Layers { main, text, mask }
    }
}



// =============
// === Model ===
// =============

/// A breadcrumbs model.
#[derive(Debug, Clone, CloneRef)]
pub struct Model {
    display_object: display::object::Instance,
    grid:           GridView,
    entries:        Entries,
    network:        frp::Network,
    mask:           mask::View,
    show_ellipsis:  Rc<Cell<bool>>,
}

impl Model {
    /// Constructor.
    pub fn new(app: &Application) -> Self {
        let display_object = display::object::Instance::new(&app.logger);
        let mask = mask::View::new(&app.logger);
        display_object.add_child(&mask);
        let grid = GridView::new(app);
        grid.reset_entries(1, 0);
        display_object.add_child(&grid);
        let entries: Entries = default();
        let show_ellipsis = Rc::new(Cell::new(false));
        frp::new_network! { network
            init <- source_();
            requested_entry <- grid.model_for_entry_needed.map2(&grid.grid_size,
                f!([entries, show_ellipsis]((row, col), grid_size) {
                    let (_, cols) = grid_size;
                    (*row, *col, Self::entry_model(&entries, *col, show_ellipsis.get(), *cols))
                })
            );
            grid.model_for_entry <+ requested_entry;
        }
        let style = StyleWatchFrp::new(&app.display.default_scene.style_sheet);
        let params = Self::params_from_style(&style, &network, init.clone_ref());
        frp::extend! { network
            grid.set_entries_params <+ params;
        }
        init.emit(());
        Self { display_object, grid, entries, network, mask, show_ellipsis }
    }

    /// Prepare an [`frp::Sampler`] that emits the parameters for the grid view's entries on
    /// style sheet changes.
    fn params_from_style(
        style: &StyleWatchFrp,
        network: &frp::Network,
        init: frp::Source<()>,
    ) -> frp::Sampler<entry::Params> {
        frp::extend! { network
            let margin = style.get_number(theme::entry::margin);
            let hover_color = style.get_color(theme::entry::hover_color);
            let font = style.get_text(theme::entry::font);
            let text_padding = style.get_number(theme::entry::text_padding_left);
            let text_size = style.get_number(theme::entry::text_size);
            let selected_color = style.get_color(theme::entry::selected_color);
            let highlight_corners_radius = style.get_number(theme::entry::highlight_corners_radius);
            let greyed_out_color = style.get_color(theme::entry::greyed_out_color);
            greyed_out_start <- init.constant(None);
            text_params <- all4(&init, &text_padding,&text_size,&font);
            colors <- all4(&init, &hover_color,&selected_color,&greyed_out_color);
            params <- all_with6(&init,&margin,&text_params,&colors,&highlight_corners_radius,
                &greyed_out_start,
                |_,&margin,text_params,colors,&highlight_corners_radius,&greyed_out_start| {
                    let (_, text_padding,text_size,font) = text_params;
                    let (_, hover_color,selected_color,greyed_out_color) = colors;
                    entry::Params {
                        margin,
                        text_padding_left: *text_padding,
                        text_size: text::Size::from(*text_size),
                        hover_color:*hover_color,
                        font_name: ImString::new(font),
                        selected_color: *selected_color,
                        highlight_corners_radius,
                        greyed_out_color: *greyed_out_color,
                        greyed_out_start
                    }
                }
            );
            params_sampler <- params.sampler();
        }
        params_sampler
    }


    fn set_layers(&self, layers: Layers) {
        layers.mask.add_exclusive(&self.mask);
        layers.main.add_exclusive(&self.display_object);
        layers.main.add_exclusive(&self.grid);
        self.grid.set_text_layer(Some(layers.text.downgrade()));
        self.network.store(&layers);
    }

    /// Update the displayed height of the breadcrumbs entries.
    fn update_entries_height(&self, height: f32) {
        // The width of the entries is unimportant because each entry will adjust the columns' sizes
        // according to its content.
        let width = 0.0;
        self.grid.set_entries_size(Vector2(width, height));
    }

    /// Update the position and the viewport of the underlying Grid View. If the content does not
    /// fit into [`size`], it is cropped by the rectangular [`mask`] and shifted left. So that
    /// the user always sees the right (most important) part of the breadcrumbs.
    fn update_layout(&self, content_size: Vector2, size: Vector2) {
        self.mask.size.set(size);
        let grid_view_center = Vector2(size.x / 2.0, -size.y / 2.0);
        self.mask.set_position_xy(grid_view_center);
        let offset = self.offset(content_size, size);
        // Additional padding is added to the viewport width to avoid rare glitches when the last
        // entry is cropped because it is placed right on the border of the viewport. Even 1px seems
        // enough, but we add a bit more to be sure.
        let padding = 10.0;
        let right = offset + size.x + padding;
        let vp = Viewport { top: 0.0, bottom: -size.y, left: offset, right };
        self.grid.set_viewport(vp);
    }

    /// Calculate an offset of the breadcrumbs' content. If the content does not fit into [`size`],
    /// we shift it left so that the user always sees the right (most important) part of the
    /// breadcrumbs. Also, we keep the selected breadcrumb visible and easily reachable by
    /// placing it near the center of the viewport.
    fn offset(&self, content_size: Vector2, size: Vector2) -> f32 {
        let selected_col = self.grid.entry_selected.value().map(|(_, col)| col).unwrap_or(0);
        let last_col = self.column_of_the_last_entry().unwrap_or(0);
        let entry_pos = self.grid.entry_position(1, selected_col).x;
        let selected_is_not_last = selected_col < last_col;
        let disabled_scrolling_region_width = size.x * (1.0 - SCROLLING_THRESHOLD_FRACTION);
        let scrolling_threshold_pos = content_size.x - disabled_scrolling_region_width;
        let should_scroll = entry_pos < scrolling_threshold_pos;
        let content_truncated = content_size.x > size.x;
        let content_right = if content_truncated && selected_is_not_last && should_scroll {
            entry_pos + SCROLLING_THRESHOLD_FRACTION * size.x
        } else {
            content_size.x
        };
        let viewport_right = content_right.min(size.x);
        content_right - viewport_right
    }

    /// A model for the specific entry. Every second entry of the grid view is an actual
    /// breadcrumb. They are separated by the [`entry::Model::Separator`] entries and can have an
    /// optional [`entry::Model::Ellipsis`] icon as the last entry (if [`show_ellipsis`] is true).
    fn entry_model(
        entries: &Entries,
        col: Col,
        show_ellipsis: bool,
        number_of_cols: Col,
    ) -> entry::Model {
        let is_last = col == number_of_cols - 1;
        let is_not_first = col != 0;
        let is_separator_index = col % 2 == 1;
        if show_ellipsis && is_last && is_not_first {
            entry::Model::Ellipsis
        } else if is_separator_index {
            entry::Model::Separator
        } else if let Some(entry) = entries.borrow().get(col / 2) {
            let text: &ImString = entry.as_ref();
            entry::Model::Text(text.clone_ref())
        } else {
            tracing::error!("Requested entry is missing in the breadcrumbs ({col})");
            entry::Model::default()
        }
    }

    /// A count of columns in the grid view. It depends on the number of entries and whether the
    /// ellipsis icon is displayed.
    fn grid_columns(&self) -> Col {
        let entries_count = self.entries.borrow().len();
        let is_not_empty = entries_count != 0;
        let ellipsis_and_separator = if self.show_ellipsis.get() && is_not_empty { 2 } else { 0 };
        (entries_count * 2).saturating_sub(1) + ellipsis_and_separator
    }

    /// The column index of the last right-most displayed breadcrumb. Returns [`None`] if there
    /// are no breadcrumbs displayed.
    fn column_of_the_last_entry(&self) -> Option<Col> {
        if self.entries.borrow().is_empty() {
            None
        } else if self.show_ellipsis.get() {
            let ellipsis_and_separator = 2;
            Some(self.grid_columns().saturating_sub(1 + ellipsis_and_separator))
        } else {
            Some(self.grid_columns().saturating_sub(1))
        }
    }

    /// Enable or disable the showing of the ellipsis icon at the end of the breadcrumbs list.
    pub fn show_ellipsis(&self, show: bool) {
        if self.show_ellipsis.get() != show {
            self.show_ellipsis.set(show);
            let new_cols = self.grid_columns();
            self.grid.resize_grid(1, new_cols);
            self.grid.request_model_for_visible_entries();
        }
    }

    /// Mark entries as greyed out starting from supplied column index. Cancel greying out if
    /// [`None`] is provided.
    pub fn grey_out(&self, from: Option<Col>) {
        let mut params = self.grid.entries_params.value();
        params.greyed_out_start = from;
        self.grid.set_entries_params(params);
    }

    /// Push a new breadcrumb to the top of the stack. Immediately selects added breadcrumb.
    /// A newly added breadcrumb will be placed after the currently selected one. All inactive
    /// (greyed out) breadcrumbs will be removed.
    pub fn push(&self, breadcrumb: &Breadcrumb, selected: BreadcrumbId) {
        self.entries.borrow_mut().truncate(selected + 1);
        self.entries.borrow_mut().push(breadcrumb.clone_ref());
        let new_col_count = self.grid_columns();
        self.grid.resize_grid(1, new_col_count);
        self.grid.request_model_for_visible_entries();
        if let Some(last_entry) = self.column_of_the_last_entry() {
            self.grid.select_entry(Some((0, last_entry)));
        }
    }

    /// Move the selection to the previous breadcrumb. Stops at the first one. There is always at
    /// least one breadcrumb selected.
    pub fn move_up(&self) {
        if let Some((row, col)) = self.grid.entry_selected.value() {
            if col != 0 {
                self.grid.select_entry(Some((row, col.saturating_sub(2))));
            }
        } else if let Some(last) = self.column_of_the_last_entry() {
            self.grid.select_entry(Some((0, last)));
        }
    }

    /// Move the selection to the next breadcrumb. Stops at the last one. There is always at
    /// least one breadcrumb selected.
    pub fn move_down(&self) {
        if let Some((row, col)) = self.grid.entry_selected.value() {
            if let Some(last) = self.column_of_the_last_entry() {
                if col < last {
                    self.grid.select_entry(Some((row, col + 2)));
                }
            }
        }
    }

    /// Clear the breadcrumbs list.
    pub fn clear(&self) {
        self.entries.borrow_mut().clear();
        self.grey_out(None);
        self.grid.resize_grid(1, 0);
    }
}


// === Breadcrumb ===

im_string_newtype_without_serde! {
    /// A single breadcrumb.
    Breadcrumb
}



// ===========
// === FRP ===
// ===========

ensogl_core::define_endpoints_2! {
    Input {
        /// Select a specific breadcrumb, greying out breadcrumbs after it.
        select(BreadcrumbId),
        /// Add a new breadcrumb after the currently selected one.
        push(Breadcrumb),
        /// Set a list of displayed breadcrumbs, rewriting any previously added breadcrumbs.
        set_entries(Vec<Breadcrumb>),
        /// Enable or disable displaying of the ellipsis icon at the end of the list.
        show_ellipsis(bool),
        /// Remove all breadcrumbs.
        clear(),
        /// Set the size of the visible portion of the breadcrumbs. The widget will crop the
        /// breadcrumbs to this size and prioritize showing the right part of the list if it
        /// can't fit in completely.
        set_size(Vector2),
        /// Move the selection to the previous (higher-level) breadcrumb in the list.
        move_up(),
        /// Move the selection to the next (lower-level) breadcrumb in the list.
        move_down(),
    }
    Output {
        /// Currently selected breadcrumb.
        selected(BreadcrumbId)
    }
}



/// ==============
/// === Widget ===
/// ==============

#[derive(Debug, Clone, CloneRef, Deref)]
pub struct Breadcrumbs {
    #[deref]
    widget: Widget<Model, Frp>,
}

impl Breadcrumbs {
    /// Constructor.
    pub fn new(app: &Application) -> Self {
        let model = Rc::new(Model::new(app));
        let display_object = model.display_object.clone_ref();
        let frp = Frp::new();
        let network = frp.network();
        let input = &frp.private().input;
        let out = &frp.private().output;
        let grid = &model.grid;
        let style = StyleWatchFrp::new(&app.display.default_scene.style_sheet);
        let entries_height = style.get_number(theme::height);
        let scroll_anim = Animation::new(network);
        frp::extend! { network
            init <- source_();
            eval input.show_ellipsis((b) model.show_ellipsis(*b));
            selected_grid_col <- grid.entry_selected.filter_map(|l| *l);
            eval selected_grid_col(((_row, col)) model.grey_out(Some(col + 1)));
            eval_ input.clear(model.clear());
            selected <- selected_grid_col.map(|(_, col)| col / 2);
            _eval <- input.push.map2(&selected, f!((b, s) model.push(b, *s)));
            entries <= input.set_entries.map(f!((e) { model.clear(); e.clone() }));
            _eval <- entries.map2(&selected, f!((entry, s) model.push(entry, *s)));
            eval selected([](id) tracing::debug!("Selected breadcrumb: {id}"));
            out.selected <+ selected;

            scroll_anim.target <+ all_with3(&model.grid.content_size, &input.set_size, &model.grid
                .entry_selected,
                f!((content_size, size, _) {
                    model.update_layout(*content_size, *size);
                    model.offset(*content_size, *size)
                })
            );
            eval scroll_anim.value((offset) model.grid.set_position_x(-offset));
            eval_ input.move_up(model.move_up());
            eval_ input.move_down(model.move_down());
            entries_height <- all(&entries_height, &init)._0();
            eval entries_height((height) model.update_entries_height(*height));
        }
        init.emit(());

        let widget = Widget::new(app, frp, model, display_object);
        Self { widget }
    }

    /// Set the scene layer for the breadcrumbs.
    pub fn set_base_layer(&self, base_layer: &Layer) {
        self.widget.model().set_layers(Layers::new(base_layer));
    }
}

impl ensogl_core::application::View for Breadcrumbs {
    fn label() -> &'static str {
        "Breadcrumbs"
    }

    fn new(app: &Application) -> Self {
        Self::new(app)
    }

    fn app(&self) -> &Application {
        self.widget.app()
    }

    fn default_shortcuts() -> Vec<Shortcut> {
        use ensogl_core::application::shortcut::ActionType::*;
        (&[(Press, "shift enter", "move_up"), (Press, "ctrl shift enter", "move_down")])
            .iter()
            .map(|(a, b, c)| Self::self_shortcut(*a, *b, *c))
            .collect()
    }
}

impl FrpNetworkProvider for Breadcrumbs {
    fn network(&self) -> &frp::Network {
        self.widget.frp().network()
    }
}

impl display::Object for Breadcrumbs {
    fn display_object(&self) -> &display::object::Instance {
        self.widget.display_object()
    }
}
