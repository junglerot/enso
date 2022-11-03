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
use ensogl::display::DomSymbol;
use ensogl::system::web;
use ide_view_component_list_panel_grid::entry::icon;
use ide_view_component_list_panel_grid::entry::icon::SHRINK_AMOUNT;



// =============
// === Frame ===
// =============

/// A rectangular frame to mark the edges of icon. It can be displayed under them to see if they
/// are centered properly.
mod frame {
    use super::*;

    ensogl::shape! {
        (style:Style) {
            let inner = Rect((icon::SIZE.px(), icon::SIZE.px()));
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
pub fn entry_point_searcher_icons() {
    let app = Application::new("root");
    ensogl_hardcoded_theme::builtin::dark::register(&app);
    ensogl_hardcoded_theme::builtin::light::register(&app);
    ensogl_hardcoded_theme::builtin::light::enable(&app);
    let world = app.display.clone();
    mem::forget(app);
    let scene = &world.default_scene;
    mem::forget(Navigator::new(scene, &scene.camera()));


    // === Grid ===

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
    scene.dom.layers.back.manage(&grid);
    world.add_child(&grid);
    grid.set_size(Vector2(1000.0, icon::SIZE));
    mem::forget(grid);


    // === Icons ===

    let mut x = -300.0;
    icon::Id::for_each(|id| {
        let shape = id.create_shape(Vector2(icon::SIZE, icon::SIZE));
        shape.set_vivid_color(color::Rgba(0.243, 0.541, 0.160, 1.0).into());
        shape.set_dull_color(color::Rgba(0.655, 0.788, 0.624, 1.0).into());
        shape.set_position_x(x);
        x += 20.0;
        world.add_child(&shape);
        mem::forget(shape);
    });
}
