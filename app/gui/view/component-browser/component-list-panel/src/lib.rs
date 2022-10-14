//! This module defines the [Component Browser Panel](View), sub-content of the Component Browser,
//! that shows the available components grouped by categories, with navigator and breadcrumbs.
//!
//! To learn more about the Component Browser and its components, see the [Component Browser Design
//! Document](https://github.com/enso-org/design/blob/e6cffec2dd6d16688164f04a4ef0d9dff998c3e7/epics/component-browser/design.md).

#![recursion_limit = "4096"]
// === Features ===
#![allow(incomplete_features)]
#![feature(negative_impls)]
#![feature(associated_type_defaults)]
#![feature(cell_update)]
#![feature(const_type_id)]
#![feature(drain_filter)]
#![feature(entry_insert)]
#![feature(fn_traits)]
#![feature(marker_trait_attr)]
#![feature(specialization)]
#![feature(trait_alias)]
#![feature(type_alias_impl_trait)]
#![feature(unboxed_closures)]
#![feature(trace_macros)]
#![feature(const_trait_impl)]
#![feature(slice_as_chunks)]
#![feature(option_result_contains)]
#![feature(int_roundings)]
#![feature(array_methods)]
// === Standard Linter Configuration ===
#![deny(non_ascii_idents)]
#![warn(unsafe_code)]
#![allow(clippy::bool_to_int_with_if)]
#![allow(clippy::let_and_return)]
// === Non-Standard Linter Configuration ===
#![allow(clippy::option_map_unit_fn)]
#![allow(clippy::precedence)]
#![allow(dead_code)]
#![deny(unconditional_recursion)]
#![warn(missing_copy_implementations)]
#![warn(missing_debug_implementations)]
#![warn(missing_docs)]
#![warn(trivial_casts)]
#![warn(trivial_numeric_casts)]
#![warn(unused_import_braces)]
#![warn(unused_qualifications)]

use crate::prelude::*;
use ensogl_core::display::shape::*;

use crate::navigator::navigator_shadow;
use crate::navigator::Navigator as SectionNavigator;

use enso_frp as frp;
use ensogl_core::application::frp::API;
use ensogl_core::application::Application;
use ensogl_core::data::bounding_box::BoundingBox;
use ensogl_core::data::color;
use ensogl_core::define_endpoints_2;
use ensogl_core::display;
use ensogl_core::display::navigation::navigator::Navigator;
use ensogl_core::display::object::ObjectOps;
use ensogl_core::display::shape::StyleWatchFrp;
use ensogl_derive_theme::FromTheme;
use ensogl_gui_component::component;
use ensogl_hardcoded_theme::application::component_browser::component_list_panel as theme;
use ensogl_list_view as list_view;
use ensogl_shadow as shadow;



// ==============
// === Export ===
// ==============

mod navigator;

pub use breadcrumbs::BreadcrumbId;
pub use ensogl_core::prelude;
pub use ide_view_component_list_panel_breadcrumbs as breadcrumbs;
pub use ide_view_component_list_panel_grid as grid;
pub use ide_view_component_list_panel_grid::entry::icon;



// =================
// === Constants ===
// =================

/// The selection animation is faster than the default one because of the increased spring force.
const SELECTION_ANIMATION_SPRING_FORCE_MULTIPLIER: f32 = 1.5;



// ==============
// === Shapes ===
// ==============

// === Layout Constants ===

/// Extra space around shape to allow for shadows.
const SHADOW_PADDING: f32 = 25.0;
const INFINITE: f32 = 999999.0;


// === Style ===

/// The style values for the Component List Panel.
#[allow(missing_docs)]
#[derive(Copy, Clone, Debug, Default, FromTheme)]
#[base_path = "theme"]
pub struct Style {
    pub background_color:       color::Rgba,
    pub corners_radius:         f32,
    #[theme_path = "theme::menu::breadcrumbs::crop_left"]
    pub breadcrumbs_crop_left:  f32,
    #[theme_path = "theme::menu::breadcrumbs::crop_right"]
    pub breadcrumbs_crop_right: f32,
    pub menu_height:            f32,
    pub menu_divider_color:     color::Rgba,
    pub menu_divider_height:    f32,
}

/// The combined style values for Component List Panel and its content.
#[allow(missing_docs)]
#[derive(Clone, Copy, Debug, Default)]
pub struct AllStyles {
    pub panel:     Style,
    pub grid:      grid::Style,
    pub navigator: navigator::Style,
}

impl AllStyles {
    fn size(&self) -> Vector2 {
        let width = self.grid.width + self.navigator.width;
        let height = self.grid.height + self.panel.menu_height;
        Vector2::new(width, height)
    }

    fn background_sprite_size(&self) -> Vector2 {
        self.size().map(|value| value + 2.0 * SHADOW_PADDING)
    }

    fn menu_divider_y_pos(&self) -> f32 {
        self.size().y / 2.0 - self.panel.menu_height
    }

    fn breadcrumbs_pos(&self) -> Vector2 {
        let crop_left = self.panel.breadcrumbs_crop_left;
        let x = -self.grid.width / 2.0 + self.navigator.width / 2.0 + crop_left;
        let y = self.grid.height / 2.0 + self.panel.menu_height / 2.0 - self.grid.padding;
        Vector2(x, y)
    }

    fn breadcrumbs_size(&self) -> Vector2 {
        let crop_left = self.panel.breadcrumbs_crop_left;
        let crop_right = self.panel.breadcrumbs_crop_right;
        let width = self.grid.width - crop_left - crop_right;
        Vector2(width, self.panel.menu_height)
    }

    fn grid_pos(&self) -> Vector2 {
        let grid_x = -self.grid.content_size().x / 2.0 + self.navigator.width / 2.0;
        let grid_y = self.grid.content_size().y / 2.0 - self.panel.menu_height / 2.0;
        Vector2(grid_x, grid_y)
    }
}



// ========================
// === Shape Definition ===
// ========================


// === Background ===

mod background {
    use super::*;

    ensogl_core::define_shape_system! {
        below = [grid::entry::background, list_view::overlay];
        (style:Style,bg_color:Vector4) {
            let alpha = Var::<f32>::from(format!("({0}.w)",bg_color));
            let bg_color = &Var::<color::Rgba>::from(bg_color.clone());

            let grid_width = style.get_number(theme::grid::width);
            let grid_height = style.get_number(theme::grid::height);
            let corners_radius = style.get_number(theme::corners_radius);
            let menu_divider_color = style.get_color(theme::menu_divider_color);
            let menu_divider_height = style.get_number(theme::menu_divider_height);
            let menu_height = style.get_number(theme::menu_height);
            let navigator_width = style.get_number(theme::navigator::width);

            let width = grid_width + navigator_width;
            let height = grid_height + menu_height;

            let divider_x_pos = navigator_width / 2.0;
            let divider_y_pos = height / 2.0 - menu_height + menu_divider_height ;

            let divider = Rect((grid_width.px(),menu_divider_height.px()));
            let divider = divider.fill(menu_divider_color);
            let divider = divider.translate_x(divider_x_pos.px());
            let divider = divider.translate_y(divider_y_pos.px());

            let base_shape = Rect((width.px(), height.px()));
            let base_shape = base_shape.corners_radius(corners_radius.px());
            let background = base_shape.fill(bg_color);
            let shadow     = shadow::from_shape_with_alpha(base_shape.into(),&alpha,style);

            (shadow + background + divider).into()
        }
    }
}



// =============
// === Model ===
// =============

/// The Model of Select Component.
#[allow(missing_docs)]
#[derive(Clone, CloneRef, Debug)]
pub struct Model {
    display_object:        display::object::Instance,
    background:            background::View,
    // FIXME[#182593513]: This separate shape for navigator shadow can be removed and replaced
    //   with a shadow embedded into the [`background`] shape when the
    //   [issue](https://www.pivotaltracker.com/story/show/182593513) is fixed.
    //   To display the shadow correctly it needs to be clipped to the [`background`] shape, but
    //   we can't do that because of a bug in the renderer. So instead we add the shadow as a
    //   separate shape and clip it using `size.set(...)`.
    navigator_shadow:      navigator_shadow::View,
    pub grid:              grid::View,
    pub section_navigator: SectionNavigator,
    pub breadcrumbs:       breadcrumbs::Breadcrumbs,
    scene_navigator:       Rc<RefCell<Option<Navigator>>>,
}

impl Model {
    fn new(app: &Application) -> Self {
        let app = app.clone_ref();
        let display_object = display::object::Instance::new();
        let scene_navigator = default();

        let background = background::View::new();
        display_object.add_child(&background);
        let navigator_shadow = navigator_shadow::View::new();
        display_object.add_child(&navigator_shadow);

        let grid = app.new_view::<grid::View>();
        display_object.add_child(&grid);

        let section_navigator = SectionNavigator::new(&app);
        display_object.add_child(&section_navigator);

        let breadcrumbs = app.new_view::<breadcrumbs::Breadcrumbs>();
        breadcrumbs.set_base_layer(&app.display.default_scene.layers.node_searcher);
        display_object.add_child(&breadcrumbs);

        Self {
            display_object,
            background,
            navigator_shadow,
            grid,
            section_navigator,
            scene_navigator,
            breadcrumbs,
        }
    }

    fn set_initial_breadcrumbs(&self) {
        self.breadcrumbs.set_entries_from((vec![breadcrumbs::Breadcrumb::new("All")], 0));
        self.breadcrumbs.show_ellipsis(true);
    }

    fn update_style(&self, style: &AllStyles) {
        self.background.bg_color.set(style.panel.background_color.into());
        self.background.size.set(style.background_sprite_size());
        self.section_navigator.update_layout(style);

        let navigator_shadow_x = -style.grid.width / 2.0;
        self.navigator_shadow.set_position_x(navigator_shadow_x);
        let section_navigator_shadow_size = Vector2(style.navigator.width, style.size().y);
        self.navigator_shadow.size.set(section_navigator_shadow_size);

        self.breadcrumbs.set_position_xy(style.breadcrumbs_pos());
        self.breadcrumbs.set_size(style.breadcrumbs_size());
        self.grid.set_position_xy(style.grid_pos());
    }

    /// Set the navigator so it can be disabled on hover.
    pub fn set_navigator(&self, navigator: Option<Navigator>) {
        *self.scene_navigator.borrow_mut() = navigator
    }

    // Note that this is a workaround for lack of hierarchical mouse over events.
    // We need to know if the mouse is over the panel, but cannot do it via a shape, as
    // sub-components still need to receive all of the mouse events, too.
    //
    // The `pos` is mouse position in Component List Panel space (the origin is in the middle of
    // the panel).
    fn is_hovered(&self, pos: Vector2) -> bool {
        let size = self.background.size().get();
        let viewport = BoundingBox::from_center_and_size(default(), size);
        viewport.contains(pos)
    }

    fn on_hover(&self) {
        if let Some(navigator) = self.scene_navigator.borrow().as_ref() {
            navigator.disable()
        } else {
            warn!(
                "Navigator was not initialised on ComponentBrowserPanel. \
            Scroll events will not be handled correctly."
            )
        }
    }

    fn on_hover_end(&self) {
        if let Some(navigator) = self.scene_navigator.borrow().as_ref() {
            navigator.enable()
        }
    }
}

impl display::Object for Model {
    fn display_object(&self) -> &display::object::Instance {
        &self.display_object
    }
}

impl component::Model for Model {
    fn label() -> &'static str {
        "ComponentBrowserPanel"
    }

    fn new(app: &Application) -> Self {
        Self::new(app)
    }
}



// ===========
// === FRP ===
// ===========

define_endpoints_2! {
    Input{
        /// The component browser is displayed on screen.
        show(),
        /// The component browser is hidden from screen.
        hide(),
    }
    Output{
        size(Vector2),
    }
}

impl component::Frp<Model> for Frp {
    fn init(
        network: &frp::Network,
        frp_api: &<Self as API>::Private,
        app: &Application,
        model: &Model,
        style: &StyleWatchFrp,
    ) {
        let scene = &app.display.default_scene;
        let input = &frp_api.input;
        let output = &frp_api.output;

        frp::extend! { network

            is_visible <- bool(&input.hide, &input.show);
            is_hovered <- app.cursor.frp.screen_position.map(f!([model,scene](pos) {
                let pos = scene.screen_to_object_space(&model, pos.xy());
                model.is_hovered(pos)
            })).gate(&is_visible).on_change();
            // TODO[ib] Temporary solution for focus, we grab keyboard events if the
            //   component browser is visible. The proper implementation is tracked in
            //   https://www.pivotaltracker.com/story/show/180872763
            model.grid.set_focus <+ is_visible;

            on_hover <- is_hovered.on_true();
            on_hover_end <- is_hovered.on_false();
            eval_ on_hover ( model.on_hover() );
            eval_ on_hover_end ( model.on_hover_end() );
            model.grid.unhover_element <+ on_hover_end;


            // === Section navigator ===

            model.grid.switch_section <+ model.section_navigator.chosen_section.filter_map(|s| *s);
            model.section_navigator.select_section <+ model.grid.active_section.on_change();


            // === Navigator icons colors ===

            let strong_color = style.get_color(theme::navigator::icon_strong_color);
            let weak_color = style.get_color(theme::navigator::icon_weak_color);
            let params = icon::Params { strong_color, weak_color };
            model.section_navigator.set_bottom_buttons_entry_params(params);


            // === Breadcrumbs ===

            eval_ input.show(model.set_initial_breadcrumbs());


            // === Style ===

            let panel_style = Style::from_theme(network, style);
            let grid_style = grid::Style::from_theme(network, style);
            let navigator_style = navigator::Style::from_theme(network, style);
            style <- all_with3(&panel_style.update, &grid_style.update, &navigator_style.update, |&panel, &grid, &navigator| AllStyles {panel, grid, navigator});
            eval style ((style) model.update_style(style));
            output.size <+ style.map(|style| style.size());
        }
        panel_style.init.emit(());
        grid_style.init.emit(());
        navigator_style.init.emit(());
    }
}

/// A sub-content of the Component Browser, that shows the available Component List Sections.
/// Each Component List Section contains named tiles called Component List Groups. To learn more
/// see the [Component Browser Design Document](https://github.com/enso-org/design/blob/e6cffec2dd6d16688164f04a4ef0d9dff998c3e7/epics/component-browser/design.md).
pub type View = component::ComponentView<Model, Frp>;
