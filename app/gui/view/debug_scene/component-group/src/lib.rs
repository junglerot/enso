//! A debug scene which shows the Component Group visual component.

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

use ensogl_core::display::shape::*;
use ensogl_core::prelude::*;
use wasm_bindgen::prelude::*;

use ensogl_core::application::Application;
use ensogl_core::data::color;
use ensogl_core::display::object::ObjectOps;
use ensogl_core::frp;
use ensogl_hardcoded_theme as theme;
use ensogl_list_view as list_view;
use ensogl_scroll_area::ScrollArea;
use ensogl_selector as selector;
use ensogl_text_msdf_sys::run_once_initialized;
use ide_view_component_group as component_group;
use list_view::entry::AnyModelProvider;



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



// ====================
// === Mock Entries ===
// ====================

const PREPARED_ITEMS: &[&str; 8] = &[
    "long sample entry with text overflowing the width",
    "convert",
    "table input",
    "text input",
    "number input",
    "table output",
    "data output",
    "data input",
];

#[derive(Debug)]
struct MockEntries {
    entries: Vec<component_group::entry::Model>,
    count:   Cell<usize>,
}

impl MockEntries {
    fn new(count: usize) -> Rc<Self> {
        Rc::new(Self {
            entries: PREPARED_ITEMS.iter().cycle().take(count).map(|&label| label.into()).collect(),
            count:   Cell::new(count),
        })
    }

    fn get_entry(&self, id: list_view::entry::Id) -> Option<component_group::entry::Model> {
        self.entries.get(id).cloned()
    }
}

impl list_view::entry::ModelProvider<component_group::Entry> for MockEntries {
    fn entry_count(&self) -> usize {
        self.count.get()
    }

    fn get(&self, id: list_view::entry::Id) -> Option<component_group::entry::Model> {
        self.get_entry(id)
    }
}



// ==================================
// === Component Group Controller ===
// ==================================

/// An example abstraction to arrange component groups vertically inside the scroll area.
///
/// It arranges `component_groups` on top of each other, with the first one being the top most.
/// It also calculates the header positions for every component group to simulate the scrolling.
/// While the [`ScrollArea`] moves every component group up we move headers of the partially
/// visible component groups down. That makes the headers of the partially scrolled component
/// groups visible at all times.
#[derive(Debug, Clone, CloneRef)]
struct ComponentGroupController {
    component_groups: Rc<Vec<component_group::View>>,
}

impl ComponentGroupController {
    fn init(
        component_groups: &[component_group::View],
        network: &frp::Network,
        scroll_area: &ScrollArea,
    ) {
        Self { component_groups: Rc::new(component_groups.to_vec()) }
            .init_inner(network, scroll_area)
    }

    fn init_inner(&self, network: &frp::Network, scroll_area: &ScrollArea) {
        for (i, group) in self.component_groups.iter().enumerate() {
            let this = self.clone_ref();
            frp::extend! { network
                eval group.size([group, this](size)
                    group.set_position_y(-size.y / 2.0 - this.heights_sum(i))
                );
                is_scrolled <- scroll_area.scroll_position_y.map(f!([this] (s) *s > this.heights_sum(i)));
                change_hdr_pos <- scroll_area.scroll_position_y.gate(&is_scrolled);
                reset_hdr_pos <- is_scrolled.on_false();
                eval change_hdr_pos([group, this](y) group.set_header_pos(*y - this.heights_sum(i)));
                eval_ reset_hdr_pos(group.set_header_pos(0.0));
            }
        }
    }

    /// Return a sum of heights of the first `n` component groups, counting from the top.
    fn heights_sum(&self, n: usize) -> f32 {
        self.component_groups.iter().take(n).map(|g| g.size.value().y).sum()
    }
}



// ========================
// === Init Application ===
// ========================


// === Helpers ====

fn create_component_group(
    app: &Application,
    header: &str,
    layers: &component_group::Layers,
) -> component_group::View {
    let component_group = app.new_view::<component_group::View>();
    component_group.model().set_layers(layers);
    component_group.set_header(header.to_string());
    component_group.set_width(150.0);
    component_group.set_position_x(75.0);
    component_group
}

fn color_component_slider(app: &Application, caption: &str) -> selector::NumberPicker {
    let slider = app.new_view::<selector::NumberPicker>();
    app.display.add_child(&slider);
    slider.frp.allow_click_selection(true);
    slider.frp.resize(Vector2(400.0, 30.0));
    slider.frp.set_bounds.emit(selector::Bounds::new(0.0, 1.0));
    slider.frp.set_caption(Some(caption.to_string()));
    slider
}


// === init ===

/// This is a workaround for the bug [#182193824](https://www.pivotaltracker.com/story/show/182193824).
///
/// We add a tranparent shape to the [`ScrollArea`] content to make component groups visible.
mod transparent_circle {
    use super::*;
    ensogl_core::define_shape_system! {
        (style:Style) {
            // As you can see even a zero-radius circle works as a workaround.
            let radius = 0.px();
            Circle(radius).fill(color::Rgba::transparent()).into()
        }
    }
}


fn init(app: &Application) {
    theme::builtin::dark::register(&app);
    theme::builtin::light::register(&app);
    theme::builtin::light::enable(&app);

    let network = frp::Network::new("Component Group Debug Scene");

    let scroll_area = ScrollArea::new(app);
    scroll_area.set_position_xy(Vector2(0.0, 100.0));
    scroll_area.resize(Vector2(170.0, 400.0));
    scroll_area.set_content_width(150.0);
    scroll_area.set_content_height(2000.0);
    app.display.add_child(&scroll_area);

    let camera = &scroll_area.content_layer().camera();
    let parent_layer = scroll_area.content_layer();
    let layers = component_group::Layers::new(&app.logger, camera, parent_layer);

    let group_name = "Long group name with text overflowing the width";
    let first_component_group = create_component_group(app, group_name, &layers);
    let group_name = "Second component group";
    let second_component_group = create_component_group(app, group_name, &layers);
    second_component_group.set_dimmed(true);

    scroll_area.content().add_child(&first_component_group);
    scroll_area.content().add_child(&second_component_group);

    // FIXME(#182193824): This is a workaround for a bug. See the docs of the
    // [`transparent_circle`].
    {
        let transparent_circle = transparent_circle::View::new(&app.logger);
        transparent_circle.size.set(Vector2(150.0, 150.0));
        transparent_circle.set_position_xy(Vector2(200.0, -150.0));
        scroll_area.content().add_child(&transparent_circle);
        std::mem::forget(transparent_circle);
    }

    // === Regular Component Group ===

    frp::extend! { network
        eval first_component_group.suggestion_accepted ([](id) DEBUG!("Accepted Suggestion {id}"));
        eval first_component_group.expression_accepted ([](id) DEBUG!("Accepted Expression {id}"));
        eval_ first_component_group.header_accepted ([] DEBUG!("Accepted Header"));
    }

    ComponentGroupController::init(
        &[first_component_group.clone_ref(), second_component_group.clone_ref()],
        &network,
        &scroll_area,
    );

    let mock_entries = MockEntries::new(15);
    let model_provider = AnyModelProvider::from(mock_entries.clone_ref());
    first_component_group.set_entries(model_provider.clone_ref());
    second_component_group.set_entries(model_provider);

    // === Color sliders ===

    let red_slider = color_component_slider(app, "Red");
    red_slider.set_position_y(350.0);
    red_slider.set_track_color(color::Rgba::new(1.0, 0.60, 0.60, 1.0));
    let red_slider_frp = &red_slider.frp;

    let green_slider = color_component_slider(app, "Green");
    green_slider.set_position_y(300.0);
    green_slider.set_track_color(color::Rgba::new(0.6, 1.0, 0.6, 1.0));
    let green_slider_frp = &green_slider.frp;

    let blue_slider = color_component_slider(app, "Blue");
    blue_slider.set_position_y(250.0);
    blue_slider.set_track_color(color::Rgba::new(0.6, 0.6, 1.0, 1.0));
    let blue_slider_frp = &blue_slider.frp;

    let default_color = color::Rgba(0.527, 0.554, 0.18, 1.0);
    frp::extend! { network
        init <- source_();
        red_slider_frp.set_value <+ init.constant(default_color.red);
        green_slider_frp.set_value <+ init.constant(default_color.green);
        blue_slider_frp.set_value <+ init.constant(default_color.blue);
        let red_slider_value = &red_slider_frp.value;
        let green_slider_value = &green_slider_frp.value;
        let blue_slider_value = &blue_slider_frp.value;
        color <- all_with3(red_slider_value, green_slider_value, blue_slider_value,
            |r,g,b| color::Rgba(*r, *g, *b, 1.0));
        first_component_group.set_color <+ color;
        second_component_group.set_color <+ color;
    }
    init.emit(());


    // === Forget ===

    std::mem::forget(red_slider);
    std::mem::forget(green_slider);
    std::mem::forget(blue_slider);
    std::mem::forget(scroll_area);
    std::mem::forget(network);
    std::mem::forget(first_component_group);
    std::mem::forget(second_component_group);
    std::mem::forget(layers);
}
