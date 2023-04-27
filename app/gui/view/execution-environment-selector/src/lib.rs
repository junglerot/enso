//! UI component that allows selecting a execution mode.

#![recursion_limit = "512"]
// === Features ===
#![feature(option_result_contains)]
#![feature(trait_alias)]
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



mod play_button;

use enso_prelude::*;
use ensogl::prelude::*;

use enso_frp as frp;
use ensogl::application::Application;
use ensogl::data::color::Rgba;
use ensogl::display;
use ensogl::display::camera::Camera2d;
use ensogl::display::shape::StyleWatchFrp;
use ensogl_derive_theme::FromTheme;
use ensogl_gui_component::component;
use ensogl_hardcoded_theme::graph_editor::execution_environment_selector as theme;



// =============
// === Style ===
// ==============

/// Theme specification for the execution environment selector.
#[derive(Debug, Clone, Copy, Default, FromTheme)]
#[base_path = "theme"]
pub struct Style {
    divider_offset:            f32,
    divider_padding:           f32,
    dropdown_width:            f32,
    height:                    f32,
    background:                Rgba,
    divider:                   Rgba,
    menu_offset:               f32,
    #[theme_path = "theme::play_button::triangle_size"]
    play_button_triangle_size: f32,
    #[theme_path = "theme::play_button::padding_x"]
    play_button_padding_x:     f32,
}

impl Style {
    fn overall_width(&self) -> f32 {
        self.dropdown_width
            + 2.0 * self.divider_padding
            + self.play_button_triangle_size
            + 2.0 * self.play_button_padding_x
    }
}



// ===========
// === FRP ===
// ===========

/// An identifier of a execution environment.
pub type ExecutionEnvironment = ImString;
/// A list of execution environments.
pub type ExecutionEnvironments = Rc<Vec<ExecutionEnvironment>>;

/// Provide a dummy list of execution environments. Used for testing and demo scenes.
pub fn make_dummy_execution_environments() -> ExecutionEnvironments {
    Rc::new(vec!["Design".to_string().into(), "Live".to_string().into()])
}

ensogl::define_endpoints_2! {
    Input {
        set_available_execution_environments      (ExecutionEnvironments),
        set_execution_environment                 (ExecutionEnvironment),
        reset_play_button_state (),
    }
    Output {
        selected_execution_environment (ExecutionEnvironment),
        play_press(),
        size(Vector2),
    }
}



// =============
// === Model ===
// =============

/// The model of the execution environment selector.
#[derive(Debug, Clone, CloneRef)]
pub struct Model {
    /// Main root object for the execution environment selector exposed for external positioning.
    display_object: display::object::Instance,
    /// Inner root that will be used for positioning the execution environment selector relative to
    /// the window
    inner_root:     display::object::Instance,
    background:     display::shape::compound::rectangle::Rectangle,
    divider:        display::shape::compound::rectangle::Rectangle,
    play_button:    play_button::PlayButton,
    dropdown:       ensogl_drop_down_menu::DropDownMenu,
}

impl Model {
    fn update_dropdown_style(&self, style: &Style) {
        self.dropdown.set_menu_offset_y(style.menu_offset);
        self.dropdown.set_x(style.overall_width() / 2.0 - style.divider_offset);
        self.dropdown.set_width(style.dropdown_width);
        self.dropdown.set_label_color(Rgba::white());
        self.dropdown.set_icon_size(Vector2::new(1.0, 1.0));
        self.dropdown.set_menu_alignment(ensogl_drop_down_menu::Alignment::Right);
        self.dropdown.set_label_alignment(ensogl_drop_down_menu::Alignment::Left);
    }

    fn update_background_style(&self, style: &Style) {
        let width = style.overall_width();
        let Style { height, background, .. } = *style;
        let size = Vector2::new(width, height);
        self.background.set_size(size);
        self.background.set_xy(-size / 2.0);
        self.background.set_corner_radius(height / 2.0);
        self.background.set_color(background);

        self.divider.set_size(Vector2::new(1.0, height));
        self.divider.set_xy(Vector2::new(width / 2.0 - style.divider_offset, -height / 2.0));
        self.divider.set_color(style.divider);
    }

    fn update_play_button_style(&self, style: &Style) {
        let width = style.overall_width();
        let Style { height, .. } = *style;
        self.play_button.set_x(width / 2.0);
        self.play_button.set_y(-height / 2.0);
    }

    fn update_position(&self, style: &Style, camera: &Camera2d) {
        let screen = camera.screen();
        let x = -screen.width / 2.0 + style.overall_width() / 2.0;
        let y = screen.height / 2.0 - style.height / 2.0;
        self.inner_root.set_x(x.round());
        self.inner_root.set_y(y.round());
    }

    fn set_entries(&self, entries: Rc<Vec<ExecutionEnvironment>>) {
        let provider = ensogl_list_view::entry::AnyModelProvider::from(entries.clone_ref());
        self.dropdown.set_entries(provider);
        self.dropdown.set_selected(0);
    }

    fn set_play_button_visibility(&self, visible: bool) {
        if visible {
            self.inner_root.add_child(&self.play_button);
            self.inner_root.add_child(&self.divider);
        } else {
            self.inner_root.remove_child(&self.play_button);
            self.inner_root.remove_child(&self.divider);
        }
    }
}

impl display::Object for Model {
    fn display_object(&self) -> &display::object::Instance {
        &self.display_object
    }
}



// ====================================
// === ExecutionEnvironmentDropdown ===
// ====================================

impl component::Model for Model {
    fn label() -> &'static str {
        "ExecutionEnvironmentDropdown"
    }

    fn new(app: &Application) -> Self {
        let scene = &app.display.default_scene;

        let display_object = display::object::Instance::new();
        let inner_root = display::object::Instance::new();
        let background = default();
        let divider = default();
        let play_button = play_button::PlayButton::new(app);
        let dropdown = ensogl_drop_down_menu::DropDownMenu::new(app);

        display_object.add_child(&inner_root);
        inner_root.add_child(&dropdown);
        inner_root.add_child(&play_button);
        inner_root.add_child(&background);
        inner_root.add_child(&divider);

        scene.layers.panel.add(&inner_root);
        scene.layers.panel.add(&dropdown);
        scene.layers.panel.add(&divider);

        dropdown.set_label_layer(&scene.layers.panel_text);

        Self { display_object, background, play_button, dropdown, inner_root, divider }
    }
}

impl component::Frp<Model> for Frp {
    fn init(
        network: &enso_frp::Network,
        frp: &<Self as ensogl::application::frp::API>::Private,
        app: &Application,
        model: &Model,
        style_watch: &StyleWatchFrp,
    ) {
        let scene = &app.display.default_scene;
        let camera = scene.camera();
        let dropdown = &model.dropdown;
        let play_button = &model.play_button;
        let input = &frp.input;
        let output = &frp.output;

        let style = Style::from_theme(network, style_watch);
        let style_update = style.update;

        frp::extend! { network

            // == Layout ==

            let camera_changed = scene.frp.camera_changed.clone_ref();
            update_position <- all(camera_changed, style_update)._1();
            eval update_position ([model, camera] (style){
                model.update_position(style, &camera);
            });

            eval style_update((style) {
               model.update_dropdown_style(style);
               model.update_background_style(style);
               model.update_play_button_style(style);
            });

            // == Inputs ==

            eval input.set_available_execution_environments ((entries) model.set_entries(entries.clone()));

            update_selected_entry <- input.set_execution_environment.map2(&input.set_available_execution_environments, |entry, entries| {
                    entries.iter().position(|mode| mode == entry)
            });
            dropdown.frp.set_selected <+ update_selected_entry;

            selected_id <- dropdown.frp.chosen_entry.unwrap();
            selection <- all(input.set_available_execution_environments, selected_id);
            selected_entry <- selection.map(|(entries, entry_id)| entries[*entry_id].clone());
            output.selected_execution_environment <+ selected_entry;

            eval selected_entry ([model] (execution_mode) {
                let play_button_visibility = matches!(execution_mode.to_lowercase().as_str(), "design");
                model.set_play_button_visibility(play_button_visibility);
            });
            play_button.reset <+ selected_entry.constant(());
            play_button.reset <+ input.reset_play_button_state;

            // == Outputs ==

            output.play_press <+ play_button.pressed;
            output.size <+ style_update.map(|style| {
                Vector2::new(style.overall_width(),style.height)
            }).on_change();
        }
        style.init.emit(());
    }
}

/// ExecutionEnvironmentSelector is a component that allows the user to select the execution
/// environment of the graph.
pub type ExecutionEnvironmentSelector = component::ComponentView<Model, Frp>;
