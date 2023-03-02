// === Non-Standard Linter Configuration ===
#![allow(missing_docs)]

use crate::data::dirty::traits::*;
use crate::prelude::*;
use web::traits::*;

use crate::animation;
use crate::control::callback;
use crate::control::io::mouse;
use crate::control::io::mouse::MouseManager;
use crate::data::dirty;
use crate::debug::stats::Stats;
use crate::display;
use crate::display::camera::Camera2d;
use crate::display::render;
use crate::display::scene::dom::DomScene;
use crate::display::shape::primitive::glsl;
use crate::display::style;
use crate::display::style::data::DataMatch;
use crate::display::symbol::Symbol;
use crate::display::world;
use crate::system;
use crate::system::gpu::data::uniform::Uniform;
use crate::system::gpu::data::uniform::UniformScope;
use crate::system::gpu::shader;
use crate::system::gpu::Context;
use crate::system::gpu::ContextLostHandler;
use crate::system::web;
use crate::system::web::EventListenerHandle;

use enso_frp as frp;
use enso_frp::io::js::CurrentJsEvent;
use enso_shapely::shared;
use std::any::TypeId;
use web::HtmlElement;


// ==============
// === Export ===
// ==============

#[warn(missing_docs)]
pub mod dom;
#[warn(missing_docs)]
pub mod layer;
#[warn(missing_docs)]
pub mod pointer_target;

pub use crate::system::web::dom::Shape;
pub use layer::Layer;
pub use pointer_target::PointerTarget;
pub use pointer_target::PointerTargetId;



// =====================
// === ShapeRegistry ===
// =====================

shared! { PointerTargetRegistry
#[derive(Debug)]
pub struct ShapeRegistryData {
    mouse_target_map : HashMap<PointerTargetId, PointerTarget>,
}

impl {
    fn new(background: &PointerTarget) -> Self {
        let mouse_target_map = default();
        Self {mouse_target_map} . init(background)
    }

    pub fn insert
    (&mut self, id:impl Into<PointerTargetId>, target:impl Into<PointerTarget>) {
        self.mouse_target_map.insert(id.into(),target.into());
    }

    pub fn remove
    (&mut self, id:impl Into<PointerTargetId>) {
        self.mouse_target_map.remove(&id.into());
    }

    pub fn get(&self, target:PointerTargetId) -> Option<PointerTarget> {
        self.mouse_target_map.get(&target).cloned()
    }
}}

impl ShapeRegistryData {
    fn init(mut self, background: &PointerTarget) -> Self {
        self.mouse_target_map.insert(PointerTargetId::Background, background.clone_ref());
        self
    }
}

impl PointerTargetRegistry {
    /// Runs the provided function on the [`PointerTarget`] associated with the provided
    /// [`PointerTargetId`]. Please note that the [`PointerTarget`] will be cloned because during
    /// evaluation of the provided function this registry might be changed, which would result in
    /// double borrow mut otherwise.
    pub fn with_mouse_target<T>(
        &self,
        target: PointerTargetId,
        f: impl FnOnce(&PointerTarget) -> T,
    ) -> Option<T> {
        match self.get(target) {
            Some(target) => Some(f(&target)),
            None => {
                warn!("Internal error. Symbol ID {target:?} is not registered.");
                None
            }
        }
    }
}



// =============
// === Mouse ===
// =============

#[derive(Clone, CloneRef, Debug)]
pub struct Mouse {
    pub mouse_manager: MouseManager,
    pub last_position: Rc<Cell<Vector2<i32>>>,
    pub position:      Uniform<Vector2<i32>>,
    pub click_count:   Uniform<i32>,
    pub hover_rgba:    Uniform<Vector4<u32>>,
    pub target:        Rc<Cell<PointerTargetId>>,
    pub handles:       Rc<[callback::Handle; 4]>,
    pub frp:           enso_frp::io::Mouse,
    pub scene_frp:     Frp,
}

impl Mouse {
    pub fn new(
        scene_frp: &Frp,
        root: &web::dom::WithKnownShape<web::HtmlDivElement>,
        variables: &UniformScope,
        current_js_event: &CurrentJsEvent,
        display_mode: &Rc<Cell<glsl::codes::DisplayModes>>,
    ) -> Self {
        let scene_frp = scene_frp.clone_ref();
        let target = PointerTargetId::default();
        let last_position = Rc::new(Cell::new(Vector2::new(0, 0)));
        let position = variables.add_or_panic("mouse_position", Vector2(0, 0));
        let click_count = variables.add_or_panic("mouse_click_count", 0);
        let hover_rgba = variables.add_or_panic("mouse_hover_ids", Vector4(0, 0, 0, 0));
        let target = Rc::new(Cell::new(target));
        let mouse_manager = MouseManager::new_separated(&root.clone_ref().into(), &web::window);
        let frp = frp::io::Mouse::new();
        let on_move = mouse_manager.on_move.add(current_js_event.make_event_handler(
            f!([frp, scene_frp, position, last_position] (event: &mouse::OnMove) {
                let shape = scene_frp.shape.value();
                let pixel_ratio = shape.pixel_ratio;
                let screen_x = event.client_x();
                let screen_y = event.client_y();

                let new_pos = Vector2::new(screen_x,screen_y);
                let pos_changed = new_pos != last_position.get();
                if pos_changed {
                    last_position.set(new_pos);
                    let new_canvas_position = new_pos.map(|v| (v as f32 *  pixel_ratio) as i32);
                    position.set(new_canvas_position);
                    let position = Vector2(new_pos.x as f32,new_pos.y as f32) - shape.center();
                    frp.position.emit(position);
                }
            }),
        ));
        let on_down = mouse_manager.on_down.add(current_js_event.make_event_handler(
            f!([frp, click_count, display_mode] (event:&mouse::OnDown) {
                click_count.modify(|v| *v += 1);
                if display_mode.get().allow_mouse_events() {
                    frp.down.emit(event.button());
                }
            }),
        ));
        let on_up = mouse_manager.on_up.add(current_js_event.make_event_handler(
            f!([frp, display_mode] (event:&mouse::OnUp) {
                if display_mode.get().allow_mouse_events() {
                    frp.up.emit(event.button())
                }
            }),
        ));
        let on_wheel = mouse_manager.on_wheel.add(current_js_event.make_event_handler(
            f_!([frp, display_mode] {
                if display_mode.get().allow_mouse_events() {
                    frp.wheel.emit(())
                }
            }),
        ));
        let handles = Rc::new([on_move, on_down, on_up, on_wheel]);
        Self {
            mouse_manager,
            last_position,
            position,
            click_count,
            hover_rgba,
            target,
            handles,
            frp,
            scene_frp,
        }
    }

    /// Re-emits FRP mouse changed position event with the last mouse position value.
    ///
    /// The immediate question that appears is why it is even needed. The reason is tightly coupled
    /// with how the rendering engine works, and it is important to understand it properly. When
    /// moving a mouse the following events happen:
    /// - `MouseManager` gets notification and fires callbacks.
    /// - Callback above is run. The value of `screen_position` uniform changes and FRP events are
    ///   emitted.
    /// - FRP events propagate through the whole system.
    /// - The rendering engine renders a frame and waits for the pixel read pass to report symbol ID
    ///   under the cursor. This is normally done the next frame but sometimes could take even few
    ///   frames.
    /// - When the new ID are received, we emit `over` and `out` FRP events for appropriate
    ///   elements.
    /// - After emitting `over` and `out `events, the `position` event is re-emitted.
    ///
    /// The idea is that if your FRP network listens on both `position` and `over` or `out` events,
    /// then you do not need to think about the whole asynchronous mechanisms going under the hood,
    /// and you can assume that it is synchronous. Whenever mouse moves, it is discovered what
    /// element it hovers, and its position change event is emitted as well.
    pub fn re_emit_position_event(&self) {
        let shape = self.scene_frp.shape.value();
        let new_pos = self.last_position.get();
        let position = Vector2(new_pos.x as f32, new_pos.y as f32) - shape.center();
        self.frp.position.emit(position);
    }
}



// ================
// === Keyboard ===
// ================

#[derive(Clone, CloneRef, Debug)]
pub struct Keyboard {
    pub frp:  enso_frp::io::keyboard::Keyboard,
    bindings: Rc<enso_frp::io::keyboard::DomBindings>,
}

impl Keyboard {
    pub fn new(current_event: &CurrentJsEvent) -> Self {
        let frp = enso_frp::io::keyboard::Keyboard::default();
        let bindings = Rc::new(enso_frp::io::keyboard::DomBindings::new(&frp, current_event));
        Self { frp, bindings }
    }
}



// ===========
// === Dom ===
// ===========

/// DOM element manager
#[derive(Clone, CloneRef, Debug)]
pub struct Dom {
    /// Root DOM element of the scene.
    pub root:   web::dom::WithKnownShape<web::HtmlDivElement>,
    /// DomLayers of the scene.
    pub layers: DomLayers,
}

impl Dom {
    /// Constructor.
    pub fn new() -> Self {
        let root = web::document.create_div_or_panic();
        let layers = DomLayers::new(&root);
        root.set_class_name("scene");
        root.set_style_or_warn("height", "100vh");
        root.set_style_or_warn("width", "100vw");
        root.set_style_or_warn("display", "block");
        let root = web::dom::WithKnownShape::new(&root);
        Self { root, layers }
    }

    pub fn shape(&self) -> Shape {
        self.root.shape()
    }

    pub fn recompute_shape_with_reflow(&self) {
        self.root.recompute_shape_with_reflow();
    }
}

impl Default for Dom {
    fn default() -> Self {
        Self::new()
    }
}



// =================
// === DomLayers ===
// =================

/// DOM DomLayers of the scene. It contains several CSS 3D layers and a canvas layer in the middle.
/// The CSS layers are used to manage DOM elements and to simulate depth-sorting of DOM and canvas
/// elements.
///
/// Each DomLayer is created with `pointer-events: none` CSS property to avoid "stealing" mouse
/// events from other layers.
/// See https://github.com/enso-org/enso/blob/develop/lib/rust/ensogl/doc/mouse-handling.md for more info.
#[derive(Clone, CloneRef, Debug)]
pub struct DomLayers {
    /// Bottom-most DOM scene layer with disabled panning. This layer is placed at the bottom
    /// because mouse cursor is drawn on `canvas` layer, and would be covered by Welcome Screen
    /// elements otherwise.
    pub welcome_screen: DomScene,
    /// Back DOM scene layer.
    pub back:           DomScene,
    /// Back DOM scene layer with fullscreen visualization. Kept separately from `back`, because
    /// the fullscreen visualizations should not share camera with main view. This layer is placed
    /// behind `canvas` because mouse cursor is drawn on `canvas` layer, and would be covered by
    /// `fullscreen_vis` elements otherwise.
    pub fullscreen_vis: DomScene,
    /// Front DOM scene layer.
    pub front:          DomScene,
    /// DOM scene layer for Node Searcher DOM elements. This layer should probably be removed once
    /// all parts of Node Searcher will use ensogl primitives instead of DOM objects for rendering.
    pub node_searcher:  DomScene,
    /// The WebGL scene layer.
    pub canvas:         web::HtmlCanvasElement,
}

impl DomLayers {
    /// Constructor.
    pub fn new(dom: &web::HtmlDivElement) -> Self {
        let welcome_screen = DomScene::new();
        welcome_screen.dom.set_class_name("welcome_screen");
        welcome_screen.dom.set_style_or_warn("z-index", "0");
        dom.append_or_warn(&welcome_screen.dom);

        let back = DomScene::new();
        back.dom.set_class_name("back");
        back.dom.set_style_or_warn("z-index", "1");
        dom.append_or_warn(&back.dom);

        let fullscreen_vis = DomScene::new();
        fullscreen_vis.dom.set_class_name("fullscreen_vis");
        fullscreen_vis.dom.set_style_or_warn("z-index", "2");
        dom.append_or_warn(&fullscreen_vis.dom);

        let canvas = web::document.create_canvas_or_panic();
        canvas.set_style_or_warn("display", "block");
        canvas.set_style_or_warn("z-index", "3");
        // These properties are set by `DomScene::new` constructor for other layers.
        // See its documentation for more info.
        canvas.set_style_or_warn("position", "absolute");
        canvas.set_style_or_warn("height", "100vh");
        canvas.set_style_or_warn("width", "100vw");
        canvas.set_style_or_warn("pointer-events", "none");
        dom.append_or_warn(&canvas);

        let node_searcher = DomScene::new();
        node_searcher.dom.set_class_name("node-searcher");
        node_searcher.dom.set_style_or_warn("z-index", "4");
        dom.append_or_warn(&node_searcher.dom);

        let front = DomScene::new();
        front.dom.set_class_name("front");
        front.dom.set_style_or_warn("z-index", "5");
        dom.append_or_warn(&front.dom);

        Self { back, welcome_screen, fullscreen_vis, front, node_searcher, canvas }
    }
}



// ================
// === Uniforms ===
// ================

/// Uniforms owned by the scene.
#[derive(Clone, CloneRef, Debug)]
pub struct Uniforms {
    /// Pixel ratio of the screen used to display the scene.
    pub pixel_ratio: Uniform<f32>,
}

impl Uniforms {
    /// Constructor.
    pub fn new(scope: &UniformScope) -> Self {
        let pixel_ratio = scope.add_or_panic("pixel_ratio", 1.0);
        Self { pixel_ratio }
    }
}



// =============
// === Dirty ===
// =============

pub type ShapeDirty = dirty::SharedBool<Box<dyn Fn()>>;
pub type SymbolRegistryDirty = dirty::SharedBool<Box<dyn Fn()>>;

#[derive(Clone, CloneRef, Debug)]
pub struct Dirty {
    shape: ShapeDirty,
}

impl Dirty {
    pub fn new<OnMut: Fn() + Clone + 'static>(on_mut: OnMut) -> Self {
        Self { shape: ShapeDirty::new(Box::new(on_mut)) }
    }
}



// ================
// === Renderer ===
// ================

/// Scene renderer. Manages the initialization and lifetime of both [`render::Pipeline`] and the
/// [`render::Composer`].
///
/// Please note that the composer can be empty if the context was either not provided yet or it was
/// lost.
#[derive(Clone, CloneRef, Debug)]
#[allow(missing_docs)]
pub struct Renderer {
    dom:          Dom,
    variables:    UniformScope,
    pub pipeline: Rc<CloneCell<render::Pipeline>>,
    pub composer: Rc<RefCell<Option<render::Composer>>>,
}

impl Renderer {
    fn new(dom: &Dom, variables: &UniformScope) -> Self {
        let dom = dom.clone_ref();
        let variables = variables.clone_ref();
        let pipeline = default();
        let composer = default();
        Self { dom, variables, pipeline, composer }
    }

    /// Set the GPU context. In most cases, this happens during app initialization or during context
    /// restoration, after the context was lost. See the docs of [`Context`] to learn more.
    fn set_context(&self, context: Option<&Context>) {
        let composer = context.map(|context| {
            // To learn more about the blending equations used here, please see the following
            // articles:
            // - http://www.realtimerendering.com/blog/gpus-prefer-premultiplication
            // - https://www.khronos.org/opengl/wiki/Blending#Colors
            context.enable(*Context::BLEND);
            context.blend_equation_separate(*Context::FUNC_ADD, *Context::FUNC_ADD);
            context.blend_func_separate(
                *Context::ONE,
                *Context::ONE_MINUS_SRC_ALPHA,
                *Context::ONE,
                *Context::ONE_MINUS_SRC_ALPHA,
            );

            let (width, height, pixel_ratio) = self.view_size();
            let pipeline = self.pipeline.get();
            render::Composer::new(&pipeline, context, &self.variables, width, height, pixel_ratio)
        });
        *self.composer.borrow_mut() = composer;
    }

    /// Set the pipeline of this renderer.
    pub fn set_pipeline(&self, pipeline: render::Pipeline) {
        self.pipeline.set(pipeline);
        self.update_composer_pipeline()
    }

    /// Reload the composer pipeline.
    fn update_composer_pipeline(&self) {
        if let Some(composer) = &mut *self.composer.borrow_mut() {
            composer.set_pipeline(&self.pipeline.get());
        }
    }

    /// Reload the composer after scene shape change.
    fn resize_composer(&self) {
        if let Some(composer) = &mut *self.composer.borrow_mut() {
            let (width, height, pixel_ratio) = self.view_size();
            composer.resize(width, height, pixel_ratio);
        }
    }

    // The width and height in device pixels should be integers. If they are not then this is due to
    // rounding errors. We round to the nearest integer to compensate for those errors.
    fn view_size(&self) -> (i32, i32, i32) {
        let shape = self.dom.shape().device_pixels();
        let width = shape.width.round() as i32;
        let height = shape.height.round() as i32;
        let pixel_ratio = shape.pixel_ratio.round() as i32;
        (width, height, pixel_ratio)
    }

    /// Run the renderer.
    pub fn run(&self, update_status: UpdateStatus) {
        if let Some(composer) = &mut *self.composer.borrow_mut() {
            debug_span!("Running.").in_scope(|| {
                composer.run(update_status);
            })
        }
    }
}



// ==============
// === Layers ===
// ==============

/// Please note that currently the `Layers` structure is implemented in a hacky way. It assumes the
/// existence of several layers, which are needed for the GUI to display shapes properly. This
/// should be abstracted away in the future.
#[derive(Clone, CloneRef, Debug)]
#[allow(non_snake_case)]
pub struct HardcodedLayers {
    /// A special layer used to store shapes not attached to any layer. This layer will not be
    /// rendered. You should not need to use it directly.
    pub DETACHED:           Layer,
    pub root:               Layer,
    pub viz:                Layer,
    pub below_main:         Layer,
    pub main:               Layer,
    pub port_selection:     Layer,
    pub label:              Layer,
    pub above_nodes:        Layer,
    pub above_nodes_text:   Layer,
    /// Layer containing all panels with fixed position (not moving with the panned scene)
    /// like status bar, breadcrumbs or similar.
    pub panel:              Layer,
    pub panel_text:         Layer,
    pub node_searcher:      Layer,
    pub node_searcher_text: Layer,
    pub edited_node:        Layer,
    pub edited_node_text:   Layer,
    pub tooltip:            Layer,
    pub tooltip_text:       Layer,
    pub cursor:             Layer,
}

impl Deref for HardcodedLayers {
    type Target = Layer;
    fn deref(&self) -> &Self::Target {
        &self.root
    }
}

impl HardcodedLayers {
    pub fn new() -> Self {
        let main_cam = Camera2d::new();
        let node_searcher_cam = Camera2d::new();
        let panel_cam = Camera2d::new();
        let edited_node_cam = Camera2d::new();
        let port_selection_cam = Camera2d::new();
        let cursor_cam = Camera2d::new();

        #[allow(non_snake_case)]
        let DETACHED = Layer::new("DETACHED");
        let root = Layer::new_with_camera("root", &main_cam);

        let viz = root.create_sublayer("viz");
        let below_main = root.create_sublayer("below_main");
        let main = root.create_sublayer("main");
        let port_selection =
            root.create_sublayer_with_camera("port_selection", &port_selection_cam);
        let label = root.create_sublayer("label");
        let above_nodes = root.create_sublayer("above_nodes");
        let above_nodes_text = root.create_sublayer("above_nodes_text");
        let panel = root.create_sublayer_with_camera("panel", &panel_cam);
        let panel_text = root.create_sublayer_with_camera("panel_text", &panel_cam);
        let node_searcher = root.create_sublayer_with_camera("node_searcher", &node_searcher_cam);
        let node_searcher_text =
            root.create_sublayer_with_camera("node_searcher_text", &node_searcher_cam);
        let edited_node = root.create_sublayer_with_camera("edited_node", &edited_node_cam);
        let edited_node_text =
            root.create_sublayer_with_camera("edited_node_text", &edited_node_cam);
        let tooltip = root.create_sublayer("tooltip");
        let tooltip_text = root.create_sublayer("tooltip_text");
        let cursor = root.create_sublayer_with_camera("cursor", &cursor_cam);

        Self {
            DETACHED,
            root,
            viz,
            below_main,
            main,
            port_selection,
            label,
            above_nodes,
            above_nodes_text,
            panel,
            panel_text,
            node_searcher,
            node_searcher_text,
            edited_node,
            edited_node_text,
            tooltip,
            tooltip_text,
            cursor,
        }
    }
}

impl Default for HardcodedLayers {
    fn default() -> Self {
        Self::new()
    }
}



// ===========
// === FRP ===
// ===========

/// FRP Scene interface.
#[derive(Clone, CloneRef, Debug)]
pub struct Frp {
    pub network:           frp::Network,
    pub shape:             frp::Sampler<Shape>,
    pub camera_changed:    frp::Stream,
    pub frame_time:        frp::Stream<f32>,
    camera_changed_source: frp::Source,
    frame_time_source:     frp::Source<f32>,
}

impl Frp {
    /// Constructor
    pub fn new(shape: &frp::Sampler<Shape>) -> Self {
        frp::new_network! { network
            camera_changed_source <- source();
            frame_time_source     <- source();
        }
        let shape = shape.clone_ref();
        let camera_changed = camera_changed_source.clone_ref().into();
        let frame_time = frame_time_source.clone_ref().into();
        Self {
            network,
            shape,
            camera_changed,
            frame_time,
            camera_changed_source,
            frame_time_source,
        }
    }
}



// =================
// === Extension ===
// =================

pub trait Extension: 'static + CloneRef {
    fn init(scene: &Scene) -> Self;
}

#[derive(Clone, CloneRef, Debug, Default)]
pub struct Extensions {
    map: Rc<RefCell<HashMap<TypeId, Box<dyn Any>>>>,
}

impl Extensions {
    pub fn get<T: Extension>(&self, scene: &Scene) -> T {
        let type_id = TypeId::of::<T>();
        let map_mut = &mut self.map.borrow_mut();
        let entry = map_mut.entry(type_id).or_insert_with(|| Box::new(T::init(scene)));
        entry.downcast_ref::<T>().unwrap().clone_ref()
    }
}



// ====================
// === UpdateStatus ===
// ====================

/// Scene update status. Used to tell the renderer what is the minimal amount of per-frame
/// processing. For example, if scene was not dirty (no animations), but pointer position changed,
/// the scene should not be re-rendered, but the id-texture pixel under the mouse should be read.
#[derive(Clone, Copy, Debug, Default)]
pub struct UpdateStatus {
    pub scene_was_dirty:          bool,
    pub pointer_position_changed: bool,
}



// =================
// === SceneData ===
// =================

#[derive(Clone, CloneRef, Debug)]
pub struct SceneData {
    pub display_object: display::object::Root,
    pub dom: Dom,
    pub context: Rc<RefCell<Option<Context>>>,
    pub context_lost_handler: Rc<RefCell<Option<ContextLostHandler>>>,
    pub variables: UniformScope,
    pub current_js_event: CurrentJsEvent,
    pub mouse: Mouse,
    pub keyboard: Keyboard,
    pub uniforms: Uniforms,
    pub background: PointerTarget,
    pub pointer_target_registry: PointerTargetRegistry,
    pub stats: Stats,
    pub dirty: Dirty,
    pub renderer: Renderer,
    pub layers: HardcodedLayers,
    pub style_sheet: style::Sheet,
    pub bg_color_var: style::Var,
    pub bg_color_change: callback::Handle,
    pub frp: Frp,
    pub pointer_position_changed: Rc<Cell<bool>>,
    pub shader_compiler: shader::compiler::Controller,
    initial_shader_compilation: Rc<Cell<TaskState>>,
    display_mode: Rc<Cell<glsl::codes::DisplayModes>>,
    extensions: Extensions,
    disable_context_menu: Rc<EventListenerHandle>,
}

impl SceneData {
    /// Create new instance with the provided on-dirty callback.
    pub fn new<OnMut: Fn() + Clone + 'static>(
        stats: &Stats,
        on_mut: OnMut,
        display_mode: &Rc<Cell<glsl::codes::DisplayModes>>,
    ) -> Self {
        debug!("Initializing.");
        let display_mode = display_mode.clone_ref();
        let dom = Dom::new();
        let display_object = display::object::Root::new_named("Scene");
        let variables = world::with_context(|t| t.variables.clone_ref());
        let dirty = Dirty::new(on_mut);
        let layers = world::with_context(|t| t.layers.clone_ref());
        let stats = stats.clone();
        let background = PointerTarget::new();
        let pointer_target_registry = PointerTargetRegistry::new(&background);
        let uniforms = Uniforms::new(&variables);
        let renderer = Renderer::new(&dom, &variables);
        let style_sheet = world::with_context(|t| t.style_sheet.clone_ref());
        let current_js_event = CurrentJsEvent::new();
        let frp = Frp::new(&dom.root.shape);
        let mouse = Mouse::new(&frp, &dom.root, &variables, &current_js_event, &display_mode);
        let disable_context_menu = Rc::new(web::ignore_context_menu(&dom.root));
        let keyboard = Keyboard::new(&current_js_event);
        let network = &frp.network;
        let extensions = Extensions::default();
        let bg_color_var = style_sheet.var("application.background");
        let bg_color_change = bg_color_var.on_change(f!([dom](change){
            change.color().for_each(|color| {
                let color = color.to_javascript_string();
                dom.root.set_style_or_warn("background-color",color);
            })
        }));

        layers.main.add(&display_object);
        frp::extend! { network
            eval_ frp.shape (dirty.shape.set());
        }

        uniforms.pixel_ratio.set(dom.shape().pixel_ratio);
        let context = default();
        let context_lost_handler = default();
        let pointer_position_changed = default();
        let shader_compiler = default();
        let initial_shader_compilation = default();
        Self {
            display_object,
            display_mode,
            dom,
            context,
            context_lost_handler,
            variables,
            current_js_event,
            mouse,
            keyboard,
            uniforms,
            pointer_target_registry,
            background,
            stats,
            dirty,
            renderer,
            layers,
            style_sheet,
            bg_color_var,
            bg_color_change,
            frp,
            pointer_position_changed,
            shader_compiler,
            initial_shader_compilation,
            extensions,
            disable_context_menu,
        }
        .init()
    }

    fn init(self) -> Self {
        self.init_mouse_down_and_up_events();
        self
    }

    /// Set the GPU context. In most cases, this happens during app initialization or during context
    /// restoration, after the context was lost. See the docs of [`Context`] to learn more.
    pub fn set_context(&self, context: Option<&Context>) {
        let _profiler = profiler::start_objective!(profiler::APP_LIFETIME, "@set_context");
        world::with_context(|t| t.set_context(context));
        *self.context.borrow_mut() = context.cloned();
        self.dirty.shape.set();
        self.renderer.set_context(context);
    }

    pub fn shape(&self) -> &frp::Sampler<Shape> {
        &self.dom.root.shape
    }

    pub fn camera(&self) -> Camera2d {
        self.layers.main.camera()
    }

    pub fn new_symbol(&self) -> Symbol {
        world::with_context(|t| t.new())
    }

    fn update_shape(&self) -> bool {
        if self.dirty.shape.check_all() {
            let screen = self.dom.shape();
            self.resize_canvas(screen);
            self.layers.iter_sublayers_and_masks_nested(|layer| {
                layer.camera().set_screen(screen.width, screen.height)
            });
            self.renderer.resize_composer();
            self.dirty.shape.unset_all();
            true
        } else {
            false
        }
    }

    fn update_symbols(&self) -> bool {
        world::with_context(|context| context.update())
    }

    fn update_camera(&self, scene: &Scene) -> bool {
        let mut was_dirty = false;
        // Updating camera for DOM layers. Please note that DOM layers cannot use multi-camera
        // setups now, so we are using here the main camera only.
        let camera = self.camera();
        let fullscreen_vis_camera = self.layers.panel.camera();
        // We are using unnavigable camera to disable panning behavior.
        let welcome_screen_camera = self.layers.panel.camera();
        let changed = camera.update(scene);
        if changed {
            was_dirty = true;
            self.frp.camera_changed_source.emit(());
            world::with_context(|t| t.set_camera(&camera));
            self.dom.layers.front.update_view_projection(&camera);
            self.dom.layers.back.update_view_projection(&camera);
        }
        let fs_vis_camera_changed = fullscreen_vis_camera.update(scene);
        if fs_vis_camera_changed {
            was_dirty = true;
            self.dom.layers.fullscreen_vis.update_view_projection(&fullscreen_vis_camera);
            self.dom.layers.welcome_screen.update_view_projection(&welcome_screen_camera);
        }
        let node_searcher_camera = self.layers.node_searcher.camera();
        let node_searcher_camera_changed = node_searcher_camera.update(scene);
        if node_searcher_camera_changed {
            was_dirty = true;
            self.dom.layers.node_searcher.update_view_projection(&node_searcher_camera);
        }

        // Updating all other cameras (the main camera was already updated, so it will be skipped).
        self.layers.iter_sublayers_and_masks_nested(|layer| {
            let dirty = layer.camera().update(scene);
            was_dirty = was_dirty || dirty;
        });

        was_dirty
    }

    /// Resize the underlying canvas. This function should rather not be called
    /// directly. If you want to change the canvas size, modify the `shape` and
    /// set the dirty flag.
    fn resize_canvas(&self, screen: Shape) {
        let canvas = screen.device_pixels();
        // The width and height in device pixels should be integers. If they are not then this is
        // due to rounding errors. We round to the nearest integer to compensate for those errors.
        let width = canvas.width.round() as i32;
        let height = canvas.height.round() as i32;
        debug_span!("Resized to {}px x {}px.", screen.width, screen.height).in_scope(|| {
            self.dom.layers.canvas.set_attribute_or_warn("width", width.to_string());
            self.dom.layers.canvas.set_attribute_or_warn("height", height.to_string());
            if let Some(context) = &*self.context.borrow() {
                context.viewport(0, 0, width, height);
            }
        });
    }

    pub fn render(&self, update_status: UpdateStatus) {
        self.renderer.run(update_status);
        // WebGL `flush` should be called when expecting results such as queries, or at completion
        // of a rendering frame. Flush tells the implementation to push all pending commands out
        // for execution, flushing them out of the queue, instead of waiting for more commands to
        // enqueue before sending for execution.
        //
        // Not flushing commands can sometimes cause context loss. To learn more, see:
        // [https://developer.mozilla.org/en-US/docs/Web/API/WebGL_API/WebGL_best_practices#flush_when_expecting_results].
        if let Some(context) = &*self.context.borrow() {
            context.flush()
        }
    }

    pub fn screen_to_scene_coordinates(&self, position: Vector3<f32>) -> Vector3<f32> {
        let position = position / self.camera().zoom();
        let position = Vector4::new(position.x, position.y, position.z, 1.0);
        (self.camera().inversed_view_matrix() * position).xyz()
    }

    /// Transforms screen position to the object (display object) coordinate system.
    pub fn screen_to_object_space(
        &self,
        object: &impl display::Object,
        screen_pos: Vector2,
    ) -> Vector2 {
        let origin_world_space = Vector4(0.0, 0.0, 0.0, 1.0);
        let layer = object.display_layer();
        let camera = layer.map_or(self.camera(), |l| l.camera());
        let origin_clip_space = camera.view_projection_matrix() * origin_world_space;
        let inv_object_matrix = object.transformation_matrix().try_inverse().unwrap();

        let shape = camera.screen();
        let clip_space_z = origin_clip_space.z;
        let clip_space_x = origin_clip_space.w * 2.0 * screen_pos.x / shape.width;
        let clip_space_y = origin_clip_space.w * 2.0 * screen_pos.y / shape.height;
        let clip_space = Vector4(clip_space_x, clip_space_y, clip_space_z, origin_clip_space.w);
        let world_space = camera.inversed_view_projection_matrix() * clip_space;
        (inv_object_matrix * world_space).xy()
    }
}


// === Mouse ===

impl SceneData {
    /// Init handling of mouse up and down events. It is also responsible for discovering of the
    /// mouse release events. To learn more see the documentation of [`PointerTarget`].
    fn init_mouse_down_and_up_events(&self) {
        let network = &self.frp.network;
        let pointer_target_registry = &self.pointer_target_registry;
        let target = &self.mouse.target;
        let pointer_position_changed = &self.pointer_position_changed;
        let pressed: Rc<RefCell<HashMap<mouse::Button, PointerTargetId>>> = default();

        frp::extend! { network
            eval self.mouse.frp.down ([pointer_target_registry, target, pressed](button) {
                let current_target = target.get();
                pressed.borrow_mut().insert(*button,current_target);
                pointer_target_registry.with_mouse_target(current_target, |t|
                    t.emit_mouse_down(*button)
                );
            });

            eval self.mouse.frp.up ([pointer_target_registry, target, pressed](button) {
                let current_target = target.get();
                if let Some(last_target) = pressed.borrow_mut().remove(button) {
                    pointer_target_registry.with_mouse_target(last_target, |t|
                        t.emit_mouse_release(*button)
                    );
                }
                pointer_target_registry.with_mouse_target(current_target, |t|
                    t.emit_mouse_up(*button)
                );
            });

            eval_ self.mouse.frp.position (pointer_position_changed.set(true));
        }
    }

    /// Discover what object the mouse pointer is on.
    fn handle_mouse_over_and_out_events(&self) {
        let opt_new_target = PointerTargetId::decode_from_rgba(self.mouse.hover_rgba.get());
        let new_target = opt_new_target.unwrap_or_else(|err| {
            error!("{err}");
            default()
        });
        let current_target = self.mouse.target.get();
        if new_target != current_target {
            self.mouse.target.set(new_target);
            self.pointer_target_registry
                .with_mouse_target(current_target, |t| t.mouse_out.emit(()));
            self.pointer_target_registry.with_mouse_target(new_target, |t| t.mouse_over.emit(()));
            self.mouse.re_emit_position_event(); // See docs to learn why.
        }
    }
}


impl display::Object for SceneData {
    fn display_object(&self) -> &display::object::Instance {
        &self.display_object
    }
}



// =============
// === Scene ===
// =============

#[derive(Clone, CloneRef, Debug)]
pub struct Scene {
    no_mut_access: SceneData,
}

impl Scene {
    pub fn new<OnMut: Fn() + Clone + 'static>(
        stats: &Stats,
        on_mut: OnMut,
        display_mode: &Rc<Cell<glsl::codes::DisplayModes>>,
    ) -> Self {
        let no_mut_access = SceneData::new(stats, on_mut, display_mode);
        let this = Self { no_mut_access };
        this
    }

    pub fn display_in(&self, parent_dom: impl DomPath) {
        match parent_dom.try_into_dom_element() {
            None => error!("The scene host element could not be found."),
            Some(parent_dom) => {
                parent_dom.append_or_warn(&self.dom.root);
                self.dom.recompute_shape_with_reflow();
                self.uniforms.pixel_ratio.set(self.dom.shape().pixel_ratio);
                self.init();
            }
        }
    }

    fn init(&self) {
        let context_loss_handler = crate::system::gpu::context::init_webgl_2_context(self);
        match context_loss_handler {
            Err(err) => error!("{err}"),
            Ok(handler) => *self.context_lost_handler.borrow_mut() = Some(handler),
        }
    }

    pub fn extension<T: Extension>(&self) -> T {
        self.extensions.get(self)
    }

    /// Begin any preparation necessary to render, e.g. compilation of shaders. Returns when the
    /// scene is ready to start being displayed.
    #[profile(Task)]
    pub async fn prepare_to_render(&self) {
        match self.initial_shader_compilation.get() {
            TaskState::Unstarted => {
                self.begin_shader_initialization();
                self.next_shader_compiler_idle().await;
                self.initial_shader_compilation.set(TaskState::Completed);
            }
            TaskState::Running => self.next_shader_compiler_idle().await,
            TaskState::Completed => (),
        }
    }

    /// Begin compiling shaders.
    #[profile(Task)]
    pub fn begin_shader_initialization(&self) {
        if self.initial_shader_compilation.get() != TaskState::Unstarted {
            return;
        }
        world::SHAPES_DEFINITIONS.with_borrow(|shapes| {
            for shape in shapes.iter().filter(|shape| shape.is_main_application_shape()) {
                // Instantiate shape so that its shader program will be submitted to the
                // shader compiler. The runtime compiles the shaders in background threads,
                // and starting early ensures they will be ready when we want to render
                // them.
                let _shape = (shape.cons)();
            }
        });
        self.initial_shader_compilation.set(TaskState::Running);
    }

    /// Wait until the next time the compiler goes from busy to idle. If the compiler is already
    /// idle, this will complete during the next shader-compiler run.
    pub async fn next_shader_compiler_idle(&self) {
        if let Some(context) = &*self.context.borrow() {
            // Ensure the callback will be run if the queue is already idle.
            context.shader_compiler.submit_probe_job();
        } else {
            return;
        };
        // Register a callback that triggers a future, and await it.
        let (sender, receiver) = futures::channel::oneshot::channel();
        let sender = Box::new(RefCell::new(Some(sender)));
        let _handle = self.shader_compiler.on_idle(move || {
            if let Some(sender) = sender.take() {
                sender.send(()).unwrap();
            }
        });
        receiver.await.unwrap();
    }
}

impl system::gpu::context::Display for Scene {
    fn device_context_handler(&self) -> &system::gpu::context::DeviceContextHandler {
        &self.dom.layers.canvas
    }

    fn set_context(&self, context: Option<&Context>) {
        self.no_mut_access.set_context(context)
    }
}

impl AsRef<SceneData> for Scene {
    fn as_ref(&self) -> &SceneData {
        &self.no_mut_access
    }
}

impl std::borrow::Borrow<SceneData> for Scene {
    fn borrow(&self) -> &SceneData {
        &self.no_mut_access
    }
}

impl Deref for Scene {
    type Target = SceneData;
    fn deref(&self) -> &Self::Target {
        &self.no_mut_access
    }
}

impl Scene {
    #[profile(Debug)]
    // FIXME:
    #[allow(unused_assignments)]
    pub fn update(&self, time: animation::TimeInfo) -> UpdateStatus {
        if let Some(context) = &*self.context.borrow() {
            debug_span!("Updating.").in_scope(|| {
                let mut scene_was_dirty = false;
                self.frp.frame_time_source.emit(time.since_animation_loop_started.unchecked_raw());
                // Please note that `update_camera` is called first as it may trigger FRP events
                // which may change display objects layout.
                scene_was_dirty = self.update_camera(self) || scene_was_dirty;
                self.display_object.update(self);
                scene_was_dirty = self.layers.update() || scene_was_dirty;
                scene_was_dirty = self.update_shape() || scene_was_dirty;
                scene_was_dirty = self.update_symbols() || scene_was_dirty;
                self.handle_mouse_over_and_out_events();
                scene_was_dirty = self.shader_compiler.run(context, time) || scene_was_dirty;

                let pointer_position_changed = self.pointer_position_changed.get();
                self.pointer_position_changed.set(false);

                // FIXME: setting it to true for now in order to make cursor blinking work.
                //   Text cursor animation is in GLSL. To be handled properly in this PR:
                //   #183406745
                let scene_was_dirty = true;
                UpdateStatus { scene_was_dirty, pointer_position_changed }
            })
        } else {
            default()
        }
    }
}

impl AsRef<Scene> for Scene {
    fn as_ref(&self) -> &Scene {
        self
    }
}

impl display::Object for Scene {
    fn display_object(&self) -> &display::object::Instance {
        &self.display_object
    }
}



// ===============
// === DomPath ===
// ===============

/// Abstraction for DOM path. It can be either a specific HTML element or a DOM id, a string used
/// to query the DOM structure to find the corresponding HTML node.
#[allow(missing_docs)]
pub trait DomPath {
    fn try_into_dom_element(self) -> Option<HtmlElement>;
}

impl DomPath for HtmlElement {
    fn try_into_dom_element(self) -> Option<HtmlElement> {
        Some(self)
    }
}

impl<'t> DomPath for &'t HtmlElement {
    fn try_into_dom_element(self) -> Option<HtmlElement> {
        Some(self.clone())
    }
}

impl DomPath for String {
    fn try_into_dom_element(self) -> Option<HtmlElement> {
        web::document.get_html_element_by_id(&self)
    }
}

impl<'t> DomPath for &'t String {
    fn try_into_dom_element(self) -> Option<HtmlElement> {
        web::document.get_html_element_by_id(self)
    }
}

impl<'t> DomPath for &'t str {
    fn try_into_dom_element(self) -> Option<HtmlElement> {
        web::document.get_html_element_by_id(self)
    }
}


// === Initial shader compilation state ===

#[derive(Copy, Clone, Default, Debug, PartialEq, Eq)]
enum TaskState {
    #[default]
    Unstarted,
    Running,
    Completed,
}



// ==================
// === Test Utils ===
// ==================

/// Extended API for tests.
pub mod test_utils {
    use super::*;

    pub trait MouseExt {
        /// Emulate click on background for testing purposes.
        fn click_on_background(&self);
    }

    impl MouseExt for Mouse {
        fn click_on_background(&self) {
            self.target.set(PointerTargetId::Background);
            let left_mouse_button = frp::io::mouse::Button::Button0;
            self.frp.down.emit(left_mouse_button);
            self.frp.up.emit(left_mouse_button);
        }
    }
}
