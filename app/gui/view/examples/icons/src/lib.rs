// === Standard Linter Configuration ===
#![deny(non_ascii_idents)]
#![warn(unsafe_code)]
#![allow(clippy::bool_to_int_with_if)]
#![allow(clippy::let_and_return)]

use ensogl::system::web::traits::*;
use ide_view_component_list_panel_grid::prelude::*;
use wasm_bindgen::prelude::*;

use ensogl::application::Application;
use ensogl::data::color;
use ensogl::display::navigation::navigator::Navigator;
use ensogl::display::object::Object;
use ensogl::display::world::World;
use ensogl::display::DomSymbol;
use ensogl::system::web;
use ide_view_component_list_panel_grid::entry::icon;
use ide_view_component_list_panel_icons::SHRINK_AMOUNT;
use ide_view_component_list_panel_icons::SIZE;
use ide_view_graph_editor::component::node::action_bar;



// =============
// === Frame ===
// =============

/// A rectangular frame to mark the edges of icon. It can be displayed under them to see if they
/// are centered properly.
mod frame {
    use super::*;

    ensogl::shape! {
        (style:Style) {
            let inner = Rect((SIZE.px(), SIZE.px()));
            let outer = inner.grow(0.2.px());
            let shape = (outer - inner).fill(color::Rgba::black());
            shape.shrink(SHRINK_AMOUNT.px()).into()
        }
    }
}



// ===================
// === Entry Point ===
// ===================

/// An entry point that displays all icon on a grid.
#[wasm_bindgen]
#[allow(dead_code)]
pub fn entry_point_icons() {
    let app = Application::new("root");
    let world = app.display.clone();
    mem::forget(app);
    let scene = &world.default_scene;
    mem::forget(Navigator::new(scene, &scene.camera()));


    // === Searcher Icons ===

    create_grid(&world, 0.0, 0.0);
    let dark_green = color::Rgba(0.243, 0.541, 0.160, 1.0);
    let mut x = -300.0;
    icon::Id::for_each(|id| {
        let shape = ide_view_component_list_panel_icons::any::View::new();
        shape.icon.set(id.any_cached_shape_location());
        shape.r_component.set(dark_green.into());
        place_icon(&world, shape, x, 0.0);
        x += 20.0;
    });


    // === Action Bar Icons ===

    let y = 40.0;
    create_grid(&world, 0.0, y);

    let visibility_icon = action_bar::icon::visibility::View::new();
    visibility_icon.color_rgba.set(dark_green.into());
    place_icon(&world, visibility_icon, -40.0, y);

    let visibility2_icon = action_bar::icon::visibility2::View::new();
    visibility2_icon.color_rgba.set(dark_green.into());
    place_icon(&world, visibility2_icon, -20.0, y);

    let freeze_icon = action_bar::icon::freeze::View::new();
    freeze_icon.color_rgba.set(dark_green.into());
    place_icon(&world, freeze_icon, 0.0, y);

    let skip_icon = action_bar::icon::skip::View::new();
    skip_icon.color_rgba.set(dark_green.into());
    place_icon(&world, skip_icon, 20.0, y);

    let disable_reevaluation_icon = action_bar::icon::disable_reevaluation::View::new();
    disable_reevaluation_icon.color_rgba.set(dark_green.into());
    place_icon(&world, disable_reevaluation_icon, 40.0, y);

    let enable_reevaluation_icon = action_bar::icon::enable_reevaluation::View::new();
    enable_reevaluation_icon.color_rgba.set(dark_green.into());
    place_icon(&world, enable_reevaluation_icon, 60.0, y);
}

/// Create a grid with pixel squares to help development of icons.
fn create_grid(world: &World, x: f32, y: f32) {
    let grid_div = web::document.create_div_or_panic();
    grid_div.set_style_or_warn("width", "2000px");
    grid_div.set_style_or_warn("height", "16px");
    grid_div.set_style_or_warn("background-size", "1.0px 1.0px");
    grid_div.set_style_or_warn(
        "background-image",
        "linear-gradient(to right,  grey 0.05px, transparent 0.05px),
         linear-gradient(to bottom, grey 0.05px, transparent 0.05px)",
    );

    let grid = DomSymbol::new(&grid_div);
    grid.set_dom_size(Vector2(1000.0, SIZE));
    grid.set_xy((x, y));
    world.default_scene.dom.layers.back.manage(&grid);
    world.add_child(&grid);
    mem::forget(grid);
}

/// Place the given icon in the world at the right coordinates, in a dark green shade.
fn place_icon(world: &World, icon: impl Object, x: f32, y: f32) {
    icon.set_xy((x, y));
    icon.set_size((SIZE, SIZE));
    world.add_child(&icon);
    mem::forget(icon);
}
