//! A debug scene which shows the scroll area.

#![recursion_limit = "1024"]
// === Features ===
#![feature(associated_type_defaults)]
#![feature(drain_filter)]
#![feature(fn_traits)]
#![feature(trait_alias)]
#![feature(type_alias_impl_trait)]
#![feature(unboxed_closures)]
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

use ensogl_core::display::shape::*;
use ensogl_core::prelude::*;
use wasm_bindgen::prelude::*;

use ensogl_core::application::Application;
use ensogl_core::data::color;
use ensogl_core::display::navigation::navigator::Navigator;
use ensogl_core::display::object::ObjectOps;
use ensogl_hardcoded_theme as theme;
use ensogl_scroll_area::ScrollArea;
use ensogl_text_msdf::run_once_initialized;



// ===================
// === Entry Point ===
// ===================

/// An entry point.
#[entry_point]
pub fn main() {
    run_once_initialized(|| {
        let app = Application::new("root");
        init(&app);
        mem::forget(app);
    });
}



// ==========================
// === Shapes definitions ===
// ==========================

mod content {
    use super::*;
    ensogl_core::define_shape_system! {
        (style:Style) {
            Circle(50.px()).fill(color::Rgb::new(1.0,0.0,0.0)).into()
        }
    }
}

mod background {
    use super::*;
    ensogl_core::define_shape_system! {
        (style:Style) {
            let size = (200.px(), 200.px());
            let color = color::Rgb::new(0.9, 0.9, 0.9);
            Rect(size).corners_radius(5.5.px()).fill(color).into()
        }
    }
}



// ========================
// === Init Application ===
// ========================

fn init(app: &Application) {
    theme::builtin::dark::register(app);
    theme::builtin::light::register(app);
    theme::builtin::light::enable(app);

    let scene = &app.display.default_scene;
    scene.camera().set_position_xy(Vector2(100.0, -100.0));

    let navigator = Navigator::new(scene, &scene.camera());
    navigator.disable_wheel_panning();
    std::mem::forget(navigator);


    // === Scroll Area ===

    let scroll_area = ScrollArea::new(app);
    app.display.add_child(&scroll_area);
    scroll_area.resize(Vector2(200.0, 200.0));
    scroll_area.set_content_width(300.0);
    scroll_area.set_content_height(1000.0);
    scroll_area.set_corner_radius(5.5);


    // === Background ===

    let background = background::View::new();
    scroll_area.add_child(&background);
    scene.layers.below_main.add_exclusive(&background);
    background.size.set(Vector2::new(200.0, 200.0));
    background.set_position_x(100.0);
    background.set_position_y(-100.0);
    std::mem::forget(background);


    // === Content ===

    let content = content::View::new();
    scroll_area.content().add_child(&content);
    content.size.set(Vector2::new(100.0, 100.0));
    content.set_position_x(100.0);
    content.set_position_y(-100.0);
    std::mem::forget(content);


    std::mem::forget(scroll_area);
}
