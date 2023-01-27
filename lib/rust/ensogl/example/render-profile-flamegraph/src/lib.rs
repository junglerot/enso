//! Renders profiling data, obtained from a file, as a flame graph.

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

use ensogl_core::prelude::*;
use wasm_bindgen::prelude::*;

use enso_profiler_data as profiler_data;
use enso_profiler_flame_graph as profiler_flame_graph;
use ensogl_core::application;
use ensogl_core::application::command::FrpNetworkProvider;
use ensogl_core::application::tooltip::Placement;
use ensogl_core::data::color;
use ensogl_core::display;
use ensogl_core::display::navigation::navigator;
use ensogl_core::display::style::theme;
use ensogl_core::frp;
use ensogl_flame_graph as flame_graph;



// ===================
// === Entry Point ===
// ===================

/// Render a flamegraph from a profile file.
#[wasm_bindgen]
#[allow(dead_code)]
pub async fn entry_point_render_profile_flamegraph() {
    let data = get_data().await;
    let profile: profiler_data::Profile<profiler_data::OpaqueMetadata> = data.parse().unwrap();
    ensogl_text_msdf::initialized().await;
    use ensogl_core::display::object::ObjectOps;
    let app = &application::Application::new("root");
    let world = &app.display;
    let scene = &world.default_scene;
    let network = app.frp.network();
    let navigator = navigator::Navigator::new(scene, &scene.camera());
    init_theme(scene);
    let mut builder = profiler_flame_graph::FlamegraphBuilder::default();
    builder.add_profile(&profile);
    let flame_graph = flame_graph::FlameGraph::from_data(builder.into(), app);
    scene.add_child(&flame_graph);
    scene.layers.main.add(&flame_graph);
    world.keep_alive_forever();
    let tooltip = ensogl_tooltip::Tooltip::new(app);
    scene.add_child(&tooltip);
    tooltip.frp.set_placement.emit(Placement::Right);
    frp::extend! { network
        tooltip.frp.set_style <+ app.frp.tooltip.map(|tt| tt.clone().with_placement(Placement::Right));
    }
    world
        .on
        .before_frame
        .add(move |_time| {
            let _keep_alive = &navigator;
            let _keep_alive = &flame_graph;
            let _keep_alive = &tooltip;
        })
        .forget();
}

// TODO[kw]: Since a flamegraph can be used to aggregate data from multiple runs, we should
//   implement some way of invoking this scene with paths to multiple profile files, and then
//   we'd replace this. For now, this is a copy of the file-loading code from the `render-profile`
//   scene.
/// Read profile data from a file. The file must be located at `/profile.json` in the root of the
/// directory that will be made available by the webserver, i.e. `enso/dist/content`.
async fn get_data() -> String {
    use wasm_bindgen::JsCast;

    let url = "assets/profile.json";
    let mut opts = web_sys::RequestInit::new();
    opts.method("GET");
    opts.mode(web_sys::RequestMode::Cors);
    let request = web_sys::Request::new_with_str_and_init(url, &opts).unwrap();
    request.headers().set("Accept", "application/json").unwrap();
    let window = web_sys::window().unwrap();
    let response = window.fetch_with_request(&request);
    let response = wasm_bindgen_futures::JsFuture::from(response).await.unwrap();
    assert!(response.is_instance_of::<web_sys::Response>());
    let response: web_sys::Response = response.dyn_into().unwrap();
    if !response.ok() {
        error!(
            "Error retrieving profile file from {url}: {}. Falling back to demo data.",
            response.status_text()
        );
        return enso_profiler_demo_data::create_data().await;
    }
    let data = response.text().unwrap();
    let data = wasm_bindgen_futures::JsFuture::from(data).await.unwrap();
    data.as_string().unwrap()
}

fn init_theme(scene: &display::Scene) {
    let theme_manager = theme::Manager::from(&scene.style_sheet);
    let theme = theme::Theme::new();
    const COLOR_PATH: &str = "flame_graph_color";
    theme.set(COLOR_PATH, color::Rgb::new(1.0, 45.0 / 255.0, 0.0));
    theme.set("component.label.text", color::Lcha::black());
    theme_manager.register("theme", theme);
    theme_manager.set_enabled(["theme".to_string()]);
    let style_watch = ensogl_core::display::shape::StyleWatch::new(&scene.style_sheet);
    style_watch.get(COLOR_PATH);
}
