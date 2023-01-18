//! Documentation view visualization generating and presenting Enso Documentation under
//! the documented node.
//!
//! # Tailwind CSS
//!
//! This crate uses the [`Tailwind CSS`] framework to style the HTML code displayed inside the
//! documentation panel. [`Tailwind CSS`] is a utility-first CSS framework packed with classes like
//! `flex`, `w-1/2`, `h-32`, or `bg-gray-200`. It allows for defining any visual style by combining
//! these classes. The `build.rs` script runs the [`Tailwind CSS`] utility to generate a
//! CSS stylesheet by scanning the source code for class names and including needed CSS rules in the
//! output file. It means one can set `Tailwind` classes for any DOM element, and the stylesheet
//! will automatically update with needed CSS rules.
//!
//! The build script runs `npx tailwindcss`, so one should have [Node.js] installed. Installing the
//! `Tailwind` utility is not strictly required because the `npx` would download it
//! automatically if needed.
//!
//! [`Tailwind CSS`]: https://tailwindcss.com/

// === Features ===
#![feature(associated_type_bounds)]
#![feature(associated_type_defaults)]
#![feature(drain_filter)]
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

use ensogl::prelude::*;
use ensogl::system::web::traits::*;

use graph_editor::component::visualization;
use ide_view_graph_editor as graph_editor;

use enso_frp as frp;
use ensogl::application::Application;
use ensogl::display;
use ensogl::display::scene::Scene;
use ensogl::display::shape::primitive::StyleWatch;
use ensogl::display::DomSymbol;
use ensogl::system::web;
use ensogl::system::web::clipboard;
use ensogl_component::shadow;
use web::Closure;
use web::HtmlElement;
use web::JsCast;
use web::MouseEvent;

pub mod html;


// ==============
// === Export ===
// ==============

pub use visualization::container::overlay;



// =================
// === Constants ===
// =================

/// Width of Documentation panel.
pub const VIEW_WIDTH: f32 = 300.0;
/// Height of Documentation panel.
pub const VIEW_HEIGHT: f32 = 300.0;

/// Content in the documentation view when there is no data available.
const CORNER_RADIUS: f32 = graph_editor::component::node::CORNER_RADIUS;
const CODE_BLOCK_CLASS: &str = "doc-code-container";
const COPY_BUTTON_CLASS: &str = "doc-copy-btn";



// =============
// === Model ===
// =============

type CodeCopyClosure = Closure<dyn FnMut(MouseEvent)>;

/// Model of Native visualization that generates documentation for given Enso code and embeds
/// it in a HTML container.
#[derive(Clone, CloneRef, Debug)]
#[allow(missing_docs)]
pub struct Model {
    logger:             Logger,
    outer_dom:          DomSymbol,
    inner_dom:          DomSymbol,
    size:               Rc<Cell<Vector2>>,
    /// The purpose of this overlay is stop propagating mouse events under the documentation panel
    /// to EnsoGL shapes, and pass them to the DOM instead.
    overlay:            overlay::View,
    display_object:     display::object::Instance,
    code_copy_closures: Rc<CloneCell<Vec<CodeCopyClosure>>>,
}

impl Model {
    /// Constructor.
    fn new(scene: &Scene) -> Self {
        let logger = Logger::new("DocumentationView");
        let display_object = display::object::Instance::new();
        let outer_div = web::document.create_div_or_panic();
        let outer_dom = DomSymbol::new(&outer_div);
        let inner_div = web::document.create_div_or_panic();
        let inner_dom = DomSymbol::new(&inner_div);
        let size = Rc::new(Cell::new(Vector2(VIEW_WIDTH, VIEW_HEIGHT)));
        let overlay = overlay::View::new();

        // FIXME : StyleWatch is unsuitable here, as it was designed as an internal tool for shape
        // system (#795)
        let styles = StyleWatch::new(&scene.style_sheet);
        let style_path = ensogl_hardcoded_theme::application::documentation::background;
        let bg_color = styles.get_color(style_path);
        let bg_color = bg_color.to_javascript_string();

        outer_dom.dom().set_style_or_warn("white-space", "normal");
        outer_dom.dom().set_style_or_warn("overflow-y", "auto");
        outer_dom.dom().set_style_or_warn("overflow-x", "auto");
        outer_dom.dom().set_style_or_warn("background-color", bg_color);
        outer_dom.dom().set_style_or_warn("pointer-events", "auto");
        outer_dom.dom().set_style_or_warn("border-radius", format!("{}px", CORNER_RADIUS));
        shadow::add_to_dom_element(&outer_dom, &styles);

        inner_dom.dom().set_attribute_or_warn("class", "scrollable");
        inner_dom.dom().set_style_or_warn("white-space", "normal");
        inner_dom.dom().set_style_or_warn("overflow-y", "auto");
        inner_dom.dom().set_style_or_warn("overflow-x", "auto");
        inner_dom.dom().set_style_or_warn("pointer-events", "auto");
        inner_dom.dom().set_style_or_warn("border-radius", format!("{}px", CORNER_RADIUS));

        overlay.roundness.set(1.0);
        overlay.radius.set(CORNER_RADIUS);
        display_object.add_child(&outer_dom);
        outer_dom.add_child(&inner_dom);
        display_object.add_child(&overlay);
        scene.dom.layers.node_searcher.manage(&outer_dom);
        scene.dom.layers.node_searcher.manage(&inner_dom);

        let code_copy_closures = default();
        Model { logger, outer_dom, inner_dom, size, overlay, display_object, code_copy_closures }
            .init()
    }

    fn init(self) -> Self {
        self.reload_style();
        self.load_css_stylesheet();
        self
    }

    /// Add `<style>` tag with the stylesheet to the `outer_dom`.
    fn load_css_stylesheet(&self) {
        let stylesheet = include_str!(concat!(env!("OUT_DIR"), "/stylesheet.css"));
        let element = web::document.create_element_or_panic("style");
        element.set_inner_html(stylesheet);
        self.outer_dom.append_or_warn(&element);
    }

    /// Set size of the documentation view.
    fn set_size(&self, size: Vector2) {
        self.size.set(size);
        self.overlay.set_size(size);
        self.reload_style();
    }

    /// Create a container for generated content and embed it with stylesheet.
    fn push_to_dom(&self, content: String) {
        let no_doc_txt = "<p style=\"color: #a3a6a9;\">No documentation available</p>";
        // FIXME [MM] : Temporary solution until engine update with changed class name in docs
        // parser.
        let content = content
            .replace("<p>No documentation available</p>", no_doc_txt)
            .replace("class=\"doc\"", "class=\"enso docs\"")
            .replace("\"font-size: 13px;\"", "\"font-size: 15px;\"")
            .replace("ALIAS", "alias");
        self.inner_dom.dom().set_inner_html(&content);
    }

    /// Append listeners to copy buttons in doc to enable copying examples.
    /// It is possible to do it with implemented method, because get_elements_by_class_name
    /// returns top-to-bottom sorted list of elements, as found in:
    /// https://stackoverflow.com/questions/35525811/order-of-elements-in-document-getelementsbyclassname-array
    fn attach_listeners_to_copy_buttons(&self) {
        let code_blocks = self.inner_dom.dom().get_elements_by_class_name(CODE_BLOCK_CLASS);
        let copy_buttons = self.inner_dom.dom().get_elements_by_class_name(COPY_BUTTON_CLASS);
        let closures = (0..copy_buttons.length()).map(|i| -> Result<CodeCopyClosure, u32> {
            let create_closures = || -> Option<CodeCopyClosure> {
                let copy_button = copy_buttons.get_with_index(i)?.dyn_into::<HtmlElement>().ok()?;
                let code_block = code_blocks.get_with_index(i)?.dyn_into::<HtmlElement>().ok()?;
                let closure: Closure<dyn FnMut(MouseEvent)> = Closure::new(move |_: MouseEvent| {
                    let inner_code = code_block.inner_text();
                    clipboard::write_text(inner_code);
                });
                let callback = closure.as_js_function();
                match copy_button.add_event_listener_with_callback("click", callback) {
                    Ok(_) => Some(closure),
                    Err(e) => {
                        error!("Unable to add event listener to copy button: {e:?}");
                        None
                    }
                }
            };
            create_closures().ok_or(i)
        });
        let (closures, errors): (Vec<_>, Vec<_>) = closures.partition(Result::is_ok);
        let ok_closures = closures.into_iter().filter_map(|t| t.ok()).collect_vec();
        let err_indices = errors.into_iter().filter_map(|t| t.err()).collect_vec();
        if !err_indices.is_empty() {
            error!("Failed to attach listeners to copy buttons with indices: {err_indices:?}.")
        }
        self.code_copy_closures.set(ok_closures)
    }

    /// Receive data, process and present it in the documentation view.
    fn receive_data(&self, data: &visualization::Data) -> Result<(), visualization::DataError> {
        let string = match data {
            visualization::Data::Json { content } => match serde_json::to_string_pretty(&**content)
            {
                Ok(string) => string,
                Err(err) => {
                    error!(
                        "Error during documentation vis-data serialization: \
                        {err:?}"
                    );
                    return Err(visualization::DataError::InternalComputationError);
                }
            },
            visualization::Data::Binary =>
                return Err(visualization::DataError::BinaryNotSupported),
        };
        self.display_doc(&string);
        Ok(())
    }

    /// Displays the received data in the panel.
    fn display_doc(&self, content: &str) {
        self.push_to_dom(String::from(content));
        self.attach_listeners_to_copy_buttons();
    }

    /// Load an HTML file into the documentation view when user is waiting for data to be received.
    /// TODO [MM] : This should be replaced with a EnsoGL spinner in the next PR.
    fn load_waiting_screen(&self) {
        let spinner = include_str!("../assets/spinner.html");
        self.push_to_dom(String::from(spinner))
    }

    fn reload_style(&self) {
        let size = self.size.get();
        self.outer_dom.set_dom_size(Vector2(size.x, size.y));
        self.inner_dom.set_dom_size(Vector2(size.x, size.y));
    }
}



// ===========
// === FRP ===
// ===========

ensogl::define_endpoints! {
    Input {
        /// Display documentation of the entity represented by given code.
        display_documentation (String)
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
#[derive(Clone, CloneRef, Debug, Shrinkwrap)]
#[allow(missing_docs)]
pub struct View {
    #[shrinkwrap(main_field)]
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
        frp::extend! { network

            // === Displaying documentation ===

            eval frp.display_documentation ((cont) model.display_doc(cont));
            eval visualization.send_data([visualization,model](data) {
                if let Err(error) = model.receive_data(data) {
                    visualization.data_receive_error.emit(error)
                }
            });


            // === Size and position ===

            eval visualization.set_size ((size) model.set_size(*size));


            // === Activation ===

            mouse_down_target <- scene.mouse.frp.down.map(f_!(scene.mouse.target.get()));
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

            app.frp.show_system_cursor <+ overlay.events.mouse_over;
            app.frp.hide_system_cursor <+ overlay.events.mouse_out;


            // === Hover ===

            frp.source.is_hovered <+ model.overlay.events.mouse_over.constant(true);
            frp.source.is_hovered <+ model.overlay.events.mouse_out.constant(false);
            let mouse_up = scene.mouse.frp.up.clone_ref();
            let mouse_down = scene.mouse.frp.down.clone_ref();
            let mouse_wheel = scene.mouse.frp.wheel.clone_ref();
            let mouse_position = scene.mouse.frp.position.clone_ref();
            caught_mouse <- any_(mouse_up,mouse_down,mouse_wheel,mouse_position);
            pass_to_dom <- caught_mouse.gate(&frp.source.is_hovered);
            eval_ pass_to_dom(scene.current_js_event.pass_to_dom.emit(()));
        }
        visualization.pass_events_to_dom_if_active(scene, network);
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
