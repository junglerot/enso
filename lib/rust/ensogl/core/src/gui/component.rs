//! Root module for GUI related components.

use crate::prelude::*;

use crate::application::command::Command;
use crate::application::command::CommandApi;
use crate::application::Application;
use crate::display;
use crate::display::scene;
use crate::display::scene::layer::AnySymbolPartition;
use crate::display::scene::layer::WeakLayer;
use crate::display::scene::Scene;
use crate::display::shape::primitive::system::Shape;
use crate::display::shape::primitive::system::ShapeInstance;
use crate::display::symbol;
use crate::display::world;
use crate::display::Sprite;
use crate::frp;



// ==============
// === Export ===
// ==============

pub use crate::display::scene::PointerTarget_DEPRECATED;



// ====================
// === AnyShapeView ===
// ====================

/// Generalization for any shape view. Allows storing different user-defined shapes in a single
/// collection.
pub trait AnyShapeView: display::Object {
    /// Get the shape's shader code in GLSL 330 format. The shader parameters will not be bound to
    /// any particular mesh and thus this code can be used for optimization purposes only.
    fn abstract_shader_code_in_glsl_310(&self) -> crate::system::gpu::shader::Code {
        self.sprite().symbol.shader().abstract_shader_code_in_glsl_310()
    }
    /// The shape definition path (file:line:column).
    fn definition_path(&self) -> &'static str;

    /// Get the sprite of given shape.
    fn sprite(&self) -> Sprite;
}

impl<S: Shape> AnyShapeView for ShapeView<S> {
    fn definition_path(&self) -> &'static str {
        S::definition_path()
    }

    fn sprite(&self) -> Sprite {
        self.sprite.borrow().clone_ref()
    }
}



// =================
// === ShapeView ===
// =================

/// A view for a shape definition. The view manages the lifetime and scene-registration of a shape
/// instance. In particular, it registers / unregisters callbacks for shape initialization and mouse
/// events handling.
#[derive(CloneRef, Debug, Deref, Derivative)]
#[derivative(Clone(bound = ""))]
#[allow(missing_docs)]
pub struct ShapeView<S: Shape> {
    model: Rc<ShapeViewModel<S>>,
}

// S: DynamicShapeInternals + 'static
impl<S: Shape> ShapeView<S> {
    /// Constructor with default shape data.
    pub fn new() -> Self
    where S::ShapeData: Default {
        Self::new_with_data(default())
    }

    /// Constructor.
    pub fn new_with_data(data: S::ShapeData) -> Self {
        let model = Rc::new(ShapeViewModel::new_with_data(data));
        Self { model }.init()
    }

    fn init(self) -> Self {
        self.init_on_scene_layer_changed();
        self
    }

    fn init_on_scene_layer_changed(&self) {
        let weak_model = Rc::downgrade(&self.model);
        let display_object = self.display_object();
        let network = &display_object.network;
        frp::extend! { network
            eval display_object.on_layer_change([] ((scene, old_layer, new_layer, symbol_partition)) {
                let scene = scene.as_ref().unwrap();
                if let Some(model) = weak_model.upgrade() {
                    let old_layer = old_layer.as_ref();
                    let new_layer = new_layer.as_ref();
                    model.on_scene_layer_changed(scene, old_layer, new_layer, *symbol_partition);
                }
            });
        }
    }
}

impl<S: Shape> HasContent for ShapeView<S> {
    type Content = S;
}

// S: DynamicShapeInternals + 'static
impl<S> Default for ShapeView<S>
where
    S: Shape,
    S::ShapeData: Default,
{
    fn default() -> Self {
        Self::new()
    }
}



// ======================
// === ShapeViewModel ===
// ======================

/// Model of [`ShapeView`].
#[derive(Deref, Debug)]
#[allow(missing_docs)]
pub struct ShapeViewModel<S: Shape> {
    #[deref]
    shape:                 ShapeInstance<S>,
    pub data:              RefCell<S::ShapeData>,
    /// # Deprecated
    /// This API is deprecated. Instead, use the display object's event API. For example, to get an
    /// FRP endpoint for mouse event, you can use the [`crate::display::Object::on_event`]
    /// function.
    pub events_deprecated: PointerTarget_DEPRECATED,
    pub pointer_targets:   RefCell<Vec<symbol::GlobalInstanceId>>,
}

impl<S: Shape> Drop for ShapeViewModel<S> {
    fn drop(&mut self) {
        self.unregister_existing_mouse_targets();
        self.events_deprecated.on_drop.emit(());
    }
}

impl<S: Shape> ShapeViewModel<S> {
    /// Constructor.
    pub fn new_with_data(data: S::ShapeData) -> Self {
        let (shape, _) = world::with_context(|t| t.layers.DETACHED.instantiate(&data, default()));
        let events_deprecated = PointerTarget_DEPRECATED::new();
        let pointer_targets = default();
        let data = RefCell::new(data);
        ShapeViewModel { shape, data, events_deprecated, pointer_targets }
    }

    #[profile(Debug)]
    fn on_scene_layer_changed(
        &self,
        scene: &Scene,
        old_layer: Option<&WeakLayer>,
        new_layer: Option<&WeakLayer>,
        new_symbol_partition: Option<AnySymbolPartition>,
    ) {
        if let Some(old_layer) = old_layer {
            self.remove_from_scene_layer(old_layer);
        }
        if let Some(new_layer) = new_layer.and_then(|layer| layer.upgrade()) {
            self.add_to_scene_layer(scene, &new_layer, new_symbol_partition)
        } else {
            let (shape, _) = scene.layers.DETACHED.instantiate(&*self.data.borrow(), default());
            self.shape.swap(&shape);
        }
    }

    fn remove_from_scene_layer(&self, old_layer: &WeakLayer) {
        let flavor = S::flavor(&self.data.borrow());
        if let Some(layer) = old_layer.upgrade() {
            let (no_more_instances, shape_system_id, _) =
                layer.shape_system_registry.drop_instance::<S>(flavor);
            if no_more_instances {
                layer.remove_shape_system(shape_system_id);
            }
        }
        self.unregister_existing_mouse_targets();
    }

    fn add_to_scene_layer(
        &self,
        scene: &Scene,
        layer: &scene::Layer,
        symbol_partition: Option<AnySymbolPartition>,
    ) {
        let shape_system = display::shape::system::ShapeSystem::<S>::id();
        let symbol_partition = symbol_partition
            .and_then(|assignment| assignment.partition_id(shape_system))
            .unwrap_or_default();
        let (shape, instance) = layer.instantiate(&*self.data.borrow(), symbol_partition);
        scene.pointer_target_registry.insert(
            instance.global_instance_id,
            self.events_deprecated.clone_ref(),
            self.display_object(),
        );
        self.pointer_targets.borrow_mut().push(instance.global_instance_id);
        self.shape.swap(&shape);
    }

    fn unregister_existing_mouse_targets(&self) {
        for global_instance_id in mem::take(&mut *self.pointer_targets.borrow_mut()) {
            scene().pointer_target_registry.remove(global_instance_id);
        }
    }
}

impl<S: Shape> display::Object for ShapeViewModel<S> {
    fn display_object(&self) -> &display::object::Instance {
        self.shape.display_object()
    }
}

impl<S: Shape> display::Object for ShapeView<S> {
    fn display_object(&self) -> &display::object::Instance {
        self.shape.display_object()
    }
}



// ==============
// === Widget ===
// ==============

// === WidgetData ===

// We use type bounds here, because Drop implementation requires them
#[derive(Debug)]
struct WidgetData<Model: 'static, Frp: 'static> {
    app:            Application,
    display_object: display::object::Instance,
    frp:            mem::ManuallyDrop<Frp>,
    model:          mem::ManuallyDrop<Rc<Model>>,
}

impl<Model: 'static, Frp: 'static> WidgetData<Model, Frp> {
    pub fn new(
        app: &Application,
        frp: Frp,
        model: Rc<Model>,
        display_object: display::object::Instance,
    ) -> Self {
        Self {
            app: app.clone_ref(),
            display_object,
            frp: mem::ManuallyDrop::new(frp),
            model: mem::ManuallyDrop::new(model),
        }
    }
}

impl<Model: 'static, Frp: 'static> Drop for WidgetData<Model, Frp> {
    fn drop(&mut self) {
        self.display_object.unset_parent();
        // Taking the value from `ManuallyDrop` requires us to not use it anymore.
        // This is clearly the case, because the structure will be soon dropped anyway.
        #[allow(unsafe_code)]
        unsafe {
            let frp = mem::ManuallyDrop::take(&mut self.frp);
            let model = mem::ManuallyDrop::take(&mut self.model);
            self.app.display.collect_garbage(frp);
            self.app.display.collect_garbage(model);
        }
    }
}


// === Widget ===

/// The EnsoGL widget abstraction.
///
/// The widget is a visible element with FRP logic. The structure contains a FRP network and a
/// model.
///
/// * The `Frp` structure should own `frp::Network` structure handling the widget's logic. It's
///   recommended to create one with [`crate::application::frp::define_endpoints`] macro.
/// * The `Model` is any structure containing shapes, properties, subwidgets etc.  manipulated by
///   the FRP network.
///
/// # Dropping Widget
///
/// Upon dropping Widget structure, the object will be hidden, but both FRP and Model will
/// not be dropped at once. Instead, they will be passed to the EnsoGL's garbage collector,
/// because handling all effects of hiding object and emitting appropriate events  (for example:
/// the "on_hide" event of [`core::gui::component::ShapeEvents`]) may take a several frames, and
/// we want to have FRP network alive to handle those effects. Thus, both FRP and model will
/// be dropped only after all process of object hiding will be handled.
#[derive(CloneRef, Debug, Derivative)]
#[derivative(Clone(bound = ""))]
pub struct Widget<Model: 'static, Frp: 'static> {
    data: Rc<WidgetData<Model, Frp>>,
}

impl<Model: 'static, Frp: 'static> Deref for Widget<Model, Frp> {
    type Target = Frp;

    fn deref(&self) -> &Self::Target {
        &self.data.frp
    }
}

impl<Model: 'static, Frp: 'static> Widget<Model, Frp> {
    /// Create a new widget.
    pub fn new(
        app: &Application,
        frp: Frp,
        model: Rc<Model>,
        display_object: display::object::Instance,
    ) -> Self {
        Self { data: Rc::new(WidgetData::new(app, frp, model, display_object)) }
    }

    /// Get the FRP structure. It is also a result of deref-ing the widget.
    pub fn frp(&self) -> &Frp {
        &self.data.frp
    }

    /// Get the Model structure.
    pub fn model(&self) -> &Model {
        &self.data.model
    }

    /// Reference to the application the Widget belongs to. It's required for handling model and
    /// FRP garbage collection, but also may be helpful when, for example, implementing
    /// `application::View`.
    pub fn app(&self) -> &Application {
        &self.data.app
    }
}

impl<Model: 'static, Frp: 'static> display::Object for Widget<Model, Frp> {
    fn display_object(&self) -> &display::object::Instance {
        &self.data.display_object
    }
}

impl<Model: 'static, Frp: 'static + crate::application::frp::API> CommandApi
    for Widget<Model, Frp>
{
    fn command_api(&self) -> Rc<RefCell<HashMap<String, Command>>> {
        self.data.frp.public().command_api()
    }
    fn status_api(&self) -> Rc<RefCell<HashMap<String, frp::Sampler<bool>>>> {
        self.data.frp.public().status_api()
    }
}
