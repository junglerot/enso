//! Application top-level structure definition. Handles views, keyboard shortcuts and more.

use crate::prelude::*;
use enso_web::traits::*;

use crate::application::command::FrpNetworkProvider;
use crate::display;
use crate::display::scene::DomPath;
use crate::display::world::World;
use crate::gui::cursor::Cursor;
use crate::system::web;


// ==============
// === Export ===
// ==============

pub mod command;
pub mod frp;
pub mod shortcut;
pub mod tooltip;
pub mod view;

pub use view::View;



/// A module with commonly used traits to mass import.
pub mod traits {
    pub use crate::application::view::View as TRAIT_View;
}


// ===========
// === Frp ===
// ===========

crate::define_endpoints_2! {
    Input {
        set_tooltip(tooltip::Style),
        /// Show the system mouse cursor.
        show_system_cursor(),
        /// Hide the system mouse cursor.
        hide_system_cursor(),
        /// Show a notification.
        show_notification(String),
    }
    Output {
        tooltip(tooltip::Style),
        notification(String),
    }
}



// ===================
// === Application ===
// ===================

/// A top level structure for an application. It combines a view, keyboard shortcut manager, and is
/// intended to also manage layout of visible panes.
#[derive(Debug, Clone, CloneRef, Deref)]
#[allow(missing_docs)]
pub struct Application {
    inner: Rc<ApplicationData>,
}

#[derive(Debug, display::Object)]
#[allow(missing_docs)]
pub struct ApplicationData {
    pub cursor:    Cursor,
    #[display_object]
    pub display:   World,
    pub commands:  command::Registry,
    pub shortcuts: shortcut::Registry,
    pub views:     view::Registry,
    pub frp:       Frp,
}

impl Application {
    /// Constructor.
    pub fn new(dom: impl DomPath) -> Self {
        let display = World::new();
        let scene = &display.default_scene;
        scene.display_in(dom);
        let commands = command::Registry::create();
        let shortcuts =
            shortcut::Registry::new(&scene.mouse.frp_deprecated, &scene, &scene, &commands);
        let views = view::Registry::create(&commands, &shortcuts);
        let cursor = Cursor::new(&display.default_scene);
        display.add_child(&cursor);
        let frp = Frp::new();

        let data = ApplicationData { cursor, display, commands, shortcuts, views, frp };

        Self { inner: Rc::new(data) }.init()
    }

    fn init(self) -> Self {
        let frp = &self.frp;
        let network = self.frp.network();
        enso_frp::extend! { network
            app_focused <- self.display.default_scene.frp.focused.on_change();
            eval app_focused([](t) Self::show_system_cursor(!t));
            eval_ frp.private.input.show_system_cursor([] Self::show_system_cursor(true));
            eval_ frp.private.input.hide_system_cursor([] Self::show_system_cursor(false));

            frp.private.output.tooltip <+ frp.private.input.set_tooltip;
        }
        // We hide the system cursor to replace it with the EnsoGL-provided one.
        self.frp.hide_system_cursor();
        self
    }

    /// Show or hide the system mouse cursor by setting the `cursor` CSS property of the `body`
    /// element.
    fn show_system_cursor(show: bool) {
        let style = if show { "auto" } else { "none" };
        web::document.body_or_panic().set_style_or_warn("cursor", style);
    }

    /// Create a new instance of a view.
    pub fn new_view<T: View>(&self) -> T {
        self.views.new_view(self)
    }
}



// ==================
// === Test Utils ===
// ==================

/// Test-specific API.
pub mod test_utils {
    use super::*;

    use crate::system::web::dom::Shape;

    /// Screen shape for unit and integration tests.
    pub const TEST_SCREEN_SHAPE: Shape =
        Shape { width: 1920.0, height: 1080.0, pixel_ratio: 1.5 };

    /// Extended API for tests.
    pub trait ApplicationExt {
        /// Set "fake" screen dimensions for unit and integration tests. This is important for a lot
        /// of position and screen size related computations in the IDE.
        fn set_screen_size_for_tests(&self);
    }

    impl ApplicationExt for Application {
        fn set_screen_size_for_tests(&self) {
            let scene = &self.display.default_scene;
            scene.dom.root.override_shape(TEST_SCREEN_SHAPE);
        }
    }

    /// Create a new application and a view for testing.
    pub fn init_component_for_test<T>() -> (Application, T)
    where T: View {
        let app = Application::new("root");
        app.set_screen_size_for_tests();
        let view = View::new(&app);
        app.display.add_child(&view);
        crate::animation::test_utils::next_frame();
        (app, view)
    }
}

// =============
// === Tests ===
// =============

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn native_compilation_in_test_mode() {
        let _app = Application::new("root");
    }
}
