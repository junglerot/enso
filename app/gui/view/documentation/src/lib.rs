//! Documentation view visualization generating and presenting Enso Documentation under
//! the documented node.

#![recursion_limit = "1024"]
// === Features ===
#![feature(drain_filter)]
#![feature(option_result_contains)]
// === Standard Linter Configuration ===
#![deny(non_ascii_idents)]
#![warn(unsafe_code)]
#![allow(clippy::bool_to_int_with_if)]
#![allow(clippy::let_and_return)]
// === Non-Standard Linter Configuration ===
#![warn(missing_copy_implementations)]
#![warn(missing_debug_implementations)]
#![warn(missing_docs)]
#![warn(trivial_casts)]
#![warn(trivial_numeric_casts)]
#![warn(unused_import_braces)]
#![warn(unused_qualifications)]

use ensogl::display::shape::*;
use ensogl::prelude::*;
use ensogl::system::web::traits::*;

use enso_frp as frp;
use enso_suggestion_database::documentation_ir::EntryDocumentation;
use enso_suggestion_database::documentation_ir::LinkedDocPage;
use ensogl::animation::physics::inertia::Spring;
use ensogl::application::Application;
use ensogl::data::color;
use ensogl::display;
use ensogl::display::scene::Scene;
use ensogl::display::shape::primitive::StyleWatch;
use ensogl::display::DomSymbol;
use ensogl::system::web;
use ensogl::Animation;
use ensogl_component::shadow;
use ensogl_derive_theme::FromTheme;
use ensogl_hardcoded_theme::application::component_browser::documentation as theme;
use graph_editor::component::visualization;
use ide_view_graph_editor as graph_editor;


// ==============
// === Export ===
// ==============

pub mod html;



// =================
// === Constants ===
// =================

/// The caption is hidden if its height is less than this value.
const MIN_CAPTION_HEIGHT: f32 = 1.0;
/// Delay before updating the displayed documentation.
const DISPLAY_DELAY_MS: i32 = 0;


// === Style ===

#[derive(Debug, Clone, Copy, Default, FromTheme)]
#[base_path = "theme"]
#[allow(missing_docs)]
pub struct Style {
    width: f32,
    height: f32,
    background: color::Rgba,
    caption_height: f32,
    caption_animation_spring_multiplier: f32,
    corner_radius: f32,
}


// =============
// === Model ===
// =============

/// Model of Native visualization that generates documentation for given Enso code and embeds
/// it in a HTML container.
#[derive(Clone, CloneRef, Debug)]
#[allow(missing_docs)]
pub struct Model {
    outer_dom:      DomSymbol,
    caption_dom:    DomSymbol,
    inner_dom:      DomSymbol,
    /// The purpose of this overlay is stop propagating mouse events under the documentation panel
    /// to EnsoGL shapes, and pass them to the DOM instead.
    overlay:        Rectangle,
    display_object: display::object::Instance,
    event_handlers: Rc<RefCell<Vec<web::EventListenerHandle>>>,
}

impl Model {
    /// Constructor.
    fn new(scene: &Scene) -> Self {
        let display_object = display::object::Instance::new();
        let outer_div = web::document.create_div_or_panic();
        let outer_dom = DomSymbol::new(&outer_div);
        let inner_div = web::document.create_div_or_panic();
        let inner_dom = DomSymbol::new(&inner_div);
        let overlay = Rectangle::new().build(|r| {
            r.set_color(INVISIBLE_HOVER_COLOR);
        });
        let caption_div = web::document.create_div_or_panic();
        let caption_dom = DomSymbol::new(&caption_div);
        caption_dom.set_inner_html(&html::caption_html());

        // FIXME : StyleWatch is unsuitable here, as it was designed as an internal tool for shape
        // system (#795)
        let styles = StyleWatch::new(&scene.style_sheet);

        outer_dom.dom().set_style_or_warn("white-space", "normal");
        outer_dom.dom().set_style_or_warn("overflow-y", "auto");
        outer_dom.dom().set_style_or_warn("overflow-x", "auto");
        outer_dom.dom().set_style_or_warn("pointer-events", "auto");
        shadow::add_to_dom_element(&outer_dom, &styles);

        inner_dom.dom().set_attribute_or_warn("class", "scrollable");
        inner_dom.dom().set_style_or_warn("white-space", "normal");
        inner_dom.dom().set_style_or_warn("overflow-y", "auto");
        inner_dom.dom().set_style_or_warn("overflow-x", "auto");
        inner_dom.dom().set_style_or_warn("pointer-events", "auto");

        display_object.add_child(&outer_dom);
        outer_dom.add_child(&caption_dom);
        outer_dom.add_child(&inner_dom);
        display_object.add_child(&overlay);
        scene.dom.layers.node_searcher.manage(&outer_dom);
        scene.dom.layers.node_searcher.manage(&inner_dom);
        scene.dom.layers.node_searcher.manage(&caption_dom);

        Model {
            outer_dom,
            inner_dom,
            caption_dom,
            overlay,
            display_object,
            event_handlers: default(),
        }
        .init()
    }

    fn init(self) -> Self {
        self.load_css_stylesheet();
        self
    }

    /// Add `<style>` tag with the stylesheet to the `outer_dom`.
    fn load_css_stylesheet(&self) {
        let stylesheet = include_str!("../assets/styles.css");
        let element = web::document.create_element_or_panic("style");
        element.set_inner_html(stylesheet);
        self.outer_dom.append_or_warn(&element);
    }

    /// Set size of the documentation view.
    fn set_size(&self, size: Vector2) {
        self.overlay.set_size(size);
        self.overlay.set_xy(Vector2(-size.x / 2.0, -size.y / 2.0));
        self.outer_dom.set_dom_size(Vector2(size.x, size.y));
    }

    /// Display the documentation and scroll to the qualified name if needed.
    fn display_doc(&self, docs: EntryDocumentation, display_doc: &frp::Source<EntryDocumentation>) {
        let linked_pages = docs.linked_doc_pages();
        let html = html::render(&docs);
        self.inner_dom.dom().set_inner_html(&html);
        self.set_link_handlers(linked_pages, display_doc);
        // Scroll to the top of the page.
        self.inner_dom.dom().set_scroll_top(0);
    }

    /// Setup event handlers for links on the documentation page.
    fn set_link_handlers(
        &self,
        linked_pages: Vec<LinkedDocPage>,
        display_doc: &frp::Source<EntryDocumentation>,
    ) {
        let new_handlers = linked_pages.into_iter().filter_map(|page| {
            let content = page.page.clone_ref();
            let anchor = html::anchor_name(&page.name);
            if let Some(element) = web::document.get_element_by_id(&anchor) {
                let closure: web::JsEventHandler = web::Closure::new(f_!([display_doc, content] {
                    display_doc.emit(content.clone_ref());
                }));
                Some(web::add_event_listener(&element, "click", closure))
            } else {
                None
            }
        });
        let _ = self.event_handlers.replace(new_handlers.collect());
    }

    /// Load an HTML file into the documentation view when user is waiting for data to be received.
    /// TODO(#5214): This should be replaced with a EnsoGL spinner.
    fn load_waiting_screen(&self) {
        let spinner = include_str!("../assets/spinner.html");
        self.inner_dom.dom().set_inner_html(spinner)
    }

    fn update_style(&self, style: Style) {
        self.set_size(Vector2(style.width, style.height));
        self.overlay.set_corner_radius(style.corner_radius);
        self.outer_dom.set_style_or_warn("border-radius", format!("{}px", style.corner_radius));
        self.inner_dom.set_style_or_warn("border-radius", format!("{}px", style.corner_radius));
        let bg_color = style.background.to_javascript_string();
        self.outer_dom.set_style_or_warn("background-color", bg_color);
        self.set_caption_height(0.0, &style);
    }

    fn set_caption_height(&self, height: f32, style: &Style) {
        let panel_size = Vector2(style.width, style.height);
        self.inner_dom.set_dom_size(Vector2(panel_size.x, panel_size.y - height));
        self.inner_dom.set_xy(Vector2(0.0, -height / 2.0));
        self.caption_dom.set_dom_size(Vector2(panel_size.x, height));
        self.caption_dom.set_xy(Vector2(0.0, panel_size.y / 2.0 - height / 2.0));
        if height < MIN_CAPTION_HEIGHT {
            self.outer_dom.remove_child(&self.caption_dom);
        } else {
            self.outer_dom.add_child(&self.caption_dom);
        }
    }
}



// ===========
// === FRP ===
// ===========

ensogl::define_endpoints! {
    Input {
        /// Display documentation of the specific entry from the suggestion database.
        display_documentation (EntryDocumentation),
        show_hovered_item_preview_caption(bool),
    }
    Output {
        /// Indicates whether the documentation panel has been selected through clicking into
        /// it, or deselected by clicking somewhere else.
        is_selected(bool),
        /// Indicates whether the documentation panel has been hovered.
        is_hovered(bool),
    }
}


// ============
// === View ===
// ============

/// View of the visualization that renders the given documentation as a HTML page.
///
/// The documentation can be provided in two formats: it can be code of the entity (type, method,
/// function etc) with doc comments, or the docstring only - in the latter case
/// however we're unable to summarize methods and atoms of types.
///
/// The default format is the docstring.
#[derive(Clone, CloneRef, Debug, Deref)]
#[allow(missing_docs)]
pub struct View {
    #[deref]
    pub model:             Model,
    pub visualization_frp: visualization::instance::Frp,
    pub frp:               Frp,
}

impl View {
    /// Definition of this visualization.
    pub fn definition() -> visualization::Definition {
        let path = visualization::Path::builtin("Documentation View");
        visualization::Definition::new(
            visualization::Signature::new_for_any_type(path, visualization::Format::Json),
            |app| Ok(Self::new(app).into()),
        )
    }

    /// Constructor.
    pub fn new(app: &Application) -> Self {
        let scene = &app.display.default_scene;
        let frp = Frp::new();
        let visualization_frp = visualization::instance::Frp::new(&frp.network);
        let model = Model::new(scene);
        model.load_waiting_screen();
        Self { model, visualization_frp, frp }.init(app)
    }

    fn init(self, app: &Application) -> Self {
        let network = &self.frp.network;
        let model = &self.model;
        let scene = &app.display.default_scene;
        let overlay = &model.overlay;
        let visualization = &self.visualization_frp;
        let frp = &self.frp;
        let display_delay = frp::io::timer::Timeout::new(network);
        let caption_anim = Animation::<f32>::new(network);
        let style_frp = StyleWatchFrp::new(&scene.style_sheet);
        let style = Style::from_theme(network, &style_frp);
        frp::extend! { network
            init <- source_();

            // === Displaying documentation ===

            docs <- any(...);
            docs <+ frp.display_documentation;
            display_delay.restart <+ frp.display_documentation.constant(DISPLAY_DELAY_MS);
            display_docs <- display_delay.on_expired.map2(&docs,|_,docs| docs.clone_ref());
            display_docs_callback <- source();
            display_docs <- any(&display_docs, &display_docs_callback);
            eval display_docs([model, display_docs_callback]
                (docs) model.display_doc(docs.clone_ref(), &display_docs_callback)
            );


            // === Hovered item preview caption ===

            spring_muliplier <- style.update.map(|s| s.caption_animation_spring_multiplier);
            caption_anim.set_spring <+ spring_muliplier.map(|m| Spring::default() * m);
            show_caption <- frp.show_hovered_item_preview_caption.on_true();
            hide_caption <- frp.show_hovered_item_preview_caption.on_false();
            caption_anim.target <+ show_caption.constant(1.0);
            caption_anim.target <+ hide_caption.constant(0.0);
            _eval <- all_with(&caption_anim.value, &style.update, f!((value, style) {
                model.set_caption_height(value * style.caption_height, style)
            }));


            // === Size ===

            size <- style.update.map(|s| Vector2(s.width, s.height));
            eval size((size) model.set_size(*size));

            // === Style ===

            eval style.update((style) model.update_style(*style));


            // === Activation ===

            mouse_down_target <- scene.mouse.frp_deprecated.down.map(f_!(scene.mouse.target.get()));
            selected <- mouse_down_target.map(f!([model,visualization] (target){
                if !model.overlay.is_this_target(*target) {
                    visualization.deactivate.emit(());
                    false
                } else {
                    visualization.activate.emit(());
                    true
                }
            }));
            is_selected_changed <= selected.map2(&frp.output.is_selected, |&new,&old| {
                (new != old).as_some(new)
            });
            frp.source.is_selected <+ is_selected_changed;


            // === Mouse Cursor ===

            app.frp.show_system_cursor <+ overlay.events_deprecated.mouse_over;
            app.frp.hide_system_cursor <+ overlay.events_deprecated.mouse_out;


            // === Hover ===

            frp.source.is_hovered <+ model.overlay.events_deprecated.mouse_over.constant(true);
            frp.source.is_hovered <+ model.overlay.events_deprecated.mouse_out.constant(false);
        }
        init.emit(());
        style.init.emit(());
        self
    }
}

impl From<View> for visualization::Instance {
    fn from(t: View) -> Self {
        Self::new(&t, &t.visualization_frp, &t.frp.network, Some(t.model.outer_dom.clone_ref()))
    }
}

impl display::Object for View {
    fn display_object(&self) -> &display::object::Instance {
        &self.model.display_object
    }
}
