//! Scene layers implementation. See docs of [`Layer`] to learn more.

use crate::data::dirty::traits::*;
use crate::prelude::*;

use crate::data::color;
use crate::data::dirty;
use crate::data::OptVec;
use crate::display;
use crate::display::camera::Camera2d;
use crate::display::shape::primitive::system::ShapeSystemFlavor;
use crate::display::shape::system::Shape;
use crate::display::shape::system::ShapeInstance;
use crate::display::shape::system::ShapeSystem;
use crate::display::shape::system::ShapeSystemId;
use crate::display::symbol;
use crate::display::symbol::RenderGroup;
use crate::display::symbol::SymbolId;
use crate::display::Context;

use enso_data_structures::dependency_graph::DependencyGraph;
use enso_shapely::shared;
use smallvec::alloc::collections::BTreeSet;



// ==================
// === LayerFlags ===
// ==================

bitflags::bitflags! {
    /// A set of flags associated with each [`Layer`].
    #[derive(Deref)]
    pub struct LayerFlags: u8 {
        /// When layer is `MAIN_PASS_VISIBLE`, it will be rendered during standard color buffer
        /// render pass. Layers without this flag are not rendered in main pass, but can still be
        /// rendered in other passes, e.g. when used as mask layers.
        const MAIN_PASS_VISIBLE = 1 << 0;
        /// This layer's camera will be updated every time its parent camera is updated, or the
        /// parent layer itself is changed. See [`LayerModel::set_camera`] for implementation of
        /// camera inheritance.
        const INHERIT_PARENT_CAMERA = 1 << 1;
    }
}



// =============
// === Layer ===
// =============

/// Display layers. A [`Layer`] consists of a [`Camera`] and a set of [`LayerItem`]s.
///
/// Layers are hierarchical and contain sublayers. Any items directly-contained by a layer are
/// displayed below any items of the layer's sublayers. Layers can also be divided into
/// [`LayerSymbolPartition`]s; symbol partitions manage the relative ordering of instances of a
/// particular symbol more efficiently than splitting it into multiple layers (any other symbols
/// placed in a partition will be drawn in the same order as if they were placed in the parent
/// layer).
///
/// Layers are allowed to share references to the same camera, and the same [`Symbol`]s.
///
/// # Symbol Management
/// [`Symbol`]s are the basic primitives managed by layers. Even if you add an user-defined shape
/// system, it will be internally represented as a group of symbols (details are provided in the
/// following section). Layers are allowed to share references to the same [`Symbol`]s. For example,
/// you can create a layer which displays the same symbols as another layer, but from a
/// different camera to create a "mini-map view" of a graph editor.
///
/// ```text
/// +------+.
/// |`.    | `.  Layer 1 (top)
/// |  `+--+---+ (Camera 1 and symbols [1,2,3])
/// +---+--+.  |
/// |`. |  | `.| Layer 2 (middle)
/// |  `+------+ (Camera 2 and symbols [3,4,5])
/// +---+--+.  |
///  `. |    `.| Layer 3 (bottom)
///    `+------+ (Camera 1 and symbols [3,6,7])
/// ```
///
/// # ShapeProxy and ShapeSystem Management
/// You are allowed to define custom [`ShapeProxy`]s, which are like [`Shape`]s, but may not be
/// bound to a [`Scene`] (and thus to WebGL context) yet. You can use the [`Layer::add_exclusive`]
/// to add any [`DisplayObject`] to that particular layer. During update, the display object
/// hierarchy will propagate the layer-assignment information, and all [`ShapeView`]s containing
/// user-defined dynamic shapes will be initialized during the display object update time. Each
/// layer contains a [`ShapeSystemRegistry`] which contains a mapping between all used user-defined
/// shape types (this is type-level mapping!) to its corresponding [`ShapeSystem`]s. This allows
/// multiple [`DynamicShapes`] to share the same [`ShapeSystem`]s. For example, adding different
/// components containing the same shape to the same layer, will make rendering of all the shapes in
/// a single draw-call. This provides a great control over performance and possible rendering
/// optimizations.
///
/// # Layer Ordering
/// Layers can be ordered by using the `set_sublayers` method on their parent. By default,
/// layers are ordered according to their creation order.
///
/// # Symbols Ordering
/// There are two ways to define symbol ordering in scene layers, a global, and local (per-layer)
/// one. In order to define a global depth-order dependency, you can use the
/// `add_elements_order_dependency`, and the `remove_elements_order_dependency` methods
/// respectively. In order to define local (per-layer) depth-order dependency, you can use methods
/// of the same names in every layer instance. After changing a dependency graph, the layer
/// management marks appropriate dirty flags and re-orders symbols on each new frame processed.
///
/// During symbol sorting, the global and local dependency graphs are merged together. The defined
/// rules are equivalently important, so local rules will not override global ones. In case of
/// lack of dependencies or circular dependencies, the symbol ids are considered (the ids are
/// increasing with every new symbol created).
///
/// Please note, that symbol ordering doesn't work cross-layer. Even if you define that symbol A has
/// to be above the symbol B, but you place symbol B on a layer above the layer of the symbol A, the
/// symbol A will be drawn first, below symbol B!
///
/// # Symbol Instance Ordering
/// Within a layer, instances of a symbol are ordered first by partition, and then partially by
/// creation-order.
///
/// Partitions created earlier are drawn below partitions created later. Within a partition, if two
/// instance are added to the partition with no intervening instance removals, the instance added
/// earlier is drawn below the later instance. If any instance may have been removed from the
/// partition in between the addition of two instances to the partition, the relative order of those
/// instances is unspecified.
///
/// Symbol partitions allow managing relative ordering of a particular symbol, much more efficiently
/// than using separate layers: All partitions are allocated in the same set of buffers, and
/// rendered in one draw call.
///
/// # Shapes Ordering
/// Ordering of shapes is more tricky than ordering of [`Symbol`]s. Each shape instance will be
/// assigned with a unique [`Symbol`] when placed on a stage, but the connection may change or can
/// be missing when the shape will be detached from the display object hierarchy or when the shape
/// will be moved between the layers. Read the "Shape Management" section below to learn why.
///
/// Shapes can be ordered by using the same methods as symbols (described above). In fact, the
/// depth-order dependencies can be seamlessly defined between both [`Symbol`]s and
/// [`ShapeProxy`]s thanks to the [`LayerItem`] abstraction. Moreover, there is a special
/// shapes ordering API allowing describing their dependencies without requiring references to their
/// instances (unlike the API described above). You can add or remove depth-order dependencies for
/// shapes based solely on their types by using the [`add_shapes_order_dependency`],and the
/// [`remove_shapes_order_dependency`] methods, respectively. Please note, that
///
/// Also, there is a macro [`shapes_order_dependencies!`] which allows convenient form for
/// defining the depth-order dependency graph for shapes based on their types.
///
/// # Compile Time Shapes Ordering Relations
/// There is also a third way to define depth-dependencies for shapes. However, unlike previous
/// methods, this one does not require you to own a reference to [`Scene`] or its [`Layer`]. Also,
/// it is impossible to remove during runtime dependencies created this way. This might sound
/// restrictive, but actually it is what you may often want to do. For example, when creating a
/// text area, you want to define that the cursor should always be above its background and there is
/// no situation when it should not be hold. In such a way, you should use this method to define
/// depth-dependencies. In order to define such compile tie shapes ordering relations, you have to
/// define them while defining the shape system. The easiest way to do it is by using the
/// [`shape!`] macro. Refer to its documentation to learn more.
///
/// # Layer Lifetime Management
/// Every [`Layer`] allows you to add symbols while removing them from other layers automatically.
/// The [`SublayersModel`] holds [`WeakLayer`], the weak form of a [`Layer`] that does not prevent
/// the layer from being dropped. That means a layer is not held alive just by being a part of the
/// scene hierarchy. When you drop last [`Layer`] reference, it will be automatically unregistered
/// from its parent and all its symbols will be removed from the scene.
///
/// # Masking Layers With ScissorBox
/// Layers rendering an be limited to a specific set of pixels by using the [`ScissorBox`] object.
/// Only the required pixels will be processed by the GPU which makes layer scissors a very
/// efficient clipping mechanism (definitely faster than masking with arbitrary shapes). All
/// [`ScissorBox`] elements are inherited by sublayers and can be refined (the common shape of
/// overlapping scissor boxes is automatically computed).
///
/// Please note that although this method is the fastest (almost zero-cost) masking way, it has
/// several downsides – it can be used only for rectangular areas, and also it works on whole pixels
/// only. The latter fact drastically limits its usability on elements with animations. Animating
/// rectangles requires displaying them sometimes with non-integer coordinates in order to get a
/// correct, smooth movement. Using [`ScissorBox`] on such elements would always cut them to whole
/// pixels which might result in a jaggy animation.
///
/// # Masking Layers With Arbitrary Shapes
/// Every layer can be applied with a "mask", another layer defining the visible area of the first
/// layer. The masked layer will be rendered first and it will be used to determine which pixels to
/// hide in the first layer. Unlike in many other solutions, masks are not black-white. Only the
/// alpha channel of the mask is used to determine which area should be hidden in the masked layer.
/// This design allows for a much easier definition of layers and also, it allows layers to be
/// assigned as both visible layers as masks, without the need to modify their shapes definitions.
/// As layers are hierarchical, you can also apply masks to group of layers.
///
/// Please note that the current implementation does not allow for hierarchical masks (masks applied
/// to already masked area or masks applied to masks). If you try using masks in hierarchical way,
/// the nested masks will be skipped and a warning will be emitted to the console.
///
/// # Example
/// ```
///    use ensogl_core::display;
///    use ensogl_core::display::world::*;
///    use ensogl_core::prelude::*;
///    use ensogl_core::data::color;
///    use ensogl_core::display::shape::compound::rectangle;
///    use ensogl_core::display::shape::compound::rectangle::Rectangle;
///    # use ensogl_core::display::navigation::navigator::Navigator;
///    # const RED: color::Rgba = color::Rgba::new(1.0, 0.5, 0.5, 0.9);
///    # const BLUE: color::Rgba = color::Rgba::new(0.5, 0.5, 1.0, 0.9);
///    # pub fn main() {
///        # let world = World::new().displayed_in("root");
///        # let scene = &world.default_scene;
///        # let camera = scene.camera().clone_ref();
///        # let navigator = Navigator::new(scene, &camera);
///
///        // We'll be using the `main` layer directly. If we needed a dedicated layer, we could use
///        // [`Layer::create_sublayer`], but layers are expensive to render; always use existing
///        // layers when possible.
///        let layer = &world.default_scene.layers.main;
///
///        let bottom = layer.create_symbol_partition::<rectangle::Shape>("bottom");
///        let top = layer.create_symbol_partition::<rectangle::Shape>("top");
///
///
///        // === Component 1 ===
///
///        // Create a component that always draws in the higher symbol-partition ([`top`]).
///        let root1 = display::object::Instance::new();
///        world.add_child(&root1);
///        let rectangle1 = Rectangle().build(|t| {
///            t.set_size(Vector2::new(64.0, 64.0)).set_color(RED);
///        });
///        root1.add_child(&rectangle1);
///        top.add(&rectangle1);
///
///
///        // === Component 2 ===
///
///        // This component draws in the lower symbol-partition ([`bottom`]). Without layer symbol
///        // partitions, it would be drawn above Component 1, because it was added more recently.
///        let root2 = display::object::Instance::new();
///        world.add_child(&root2);
///        let rectangle2 = Rectangle().build(|t| {
///            t.set_size(Vector2::new(128.0, 128.0)).set_color(BLUE);
///        });
///        root2.add_child(&rectangle2);
///        bottom.add(&rectangle2);
///    }
/// ```
#[derive(Clone, CloneRef, Deref)]
pub struct Layer {
    model: Rc<LayerModel>,
}

impl Layer {
    /// Create a new detached layer. It will inherit the camera of the parent layer once it is
    /// attached.
    pub fn new(name: impl Into<String>) -> Self {
        let flags = LayerFlags::MAIN_PASS_VISIBLE | LayerFlags::INHERIT_PARENT_CAMERA;
        Self::new_with_flags(name, flags)
    }

    /// Create a new layer with specified camera. If it will be later attached as a sublayer, it
    /// will not inherit the camera of the set parent layer.
    #[profile(Detail)]
    pub fn new_with_camera(name: impl Into<String>, camera: &Camera2d) -> Self {
        let flags = LayerFlags::MAIN_PASS_VISIBLE;
        let this = Self::new_with_flags(name, flags);
        this.set_camera(camera);
        this
    }

    /// Create a new layer with specified inheritance and render_only_as_mask flags.
    fn new_with_flags(name: impl Into<String>, flags: LayerFlags) -> Self {
        let model = LayerModel::new(name, flags);
        let model = Rc::new(model);
        Self { model }
    }

    /// Create a new weak pointer to this layer.
    pub fn downgrade(&self) -> WeakLayer {
        let model = Rc::downgrade(&self.model);
        WeakLayer { model }
    }

    /// Add the display object to this layer and remove it from a layer it was assigned to, if any.
    pub fn add(&self, object: impl display::Object) {
        object.display_object().add_to_display_layer(self);
    }

    /// Remove the display object from a layer it was assigned to, if any.
    pub fn remove(&self, object: impl display::Object) {
        object.display_object().remove_from_display_layer(self);
    }

    /// Instantiate the provided [`ShapeProxy`].
    pub fn instantiate<S>(
        &self,
        data: &S::ShapeData,
        symbol_partition: SymbolPartitionId,
    ) -> (ShapeInstance<S>, LayerShapeBinding)
    where
        S: Shape,
    {
        let (shape_system_info, symbol_id, shape_instance, global_instance_id) =
            self.shape_system_registry.instantiate(data, symbol_partition);
        self.add_shape(shape_system_info, symbol_id);
        (shape_instance, LayerShapeBinding::new(self, global_instance_id))
    }

    /// Iterate over all layers and sublayers of this layer hierarchically. Parent layers will be
    /// visited before their corresponding sublayers. Does not visit masks. If you want to visit
    /// masks, use [`iter_sublayers_and_masks_nested`] instead. The visited layer sublayers will be
    /// borrowed during the iteration.
    pub fn iter_sublayers_nested(&self, mut f: impl FnMut(&Layer)) {
        self.iter_sublayers_nested_internal(&mut f)
    }

    fn iter_sublayers_nested_internal(&self, f: &mut impl FnMut(&Layer)) {
        f(self);
        self.for_each_sublayer(|layer| layer.iter_sublayers_nested_internal(f));
    }

    /// Iterate over all layers, sublayers, masks, and their sublayers of this layer hierarchically.
    /// Parent layers will be visited before their corresponding sublayers. The visited layer
    /// sublayers and masks will be borrowed during the iteration.
    pub fn iter_sublayers_and_masks_nested(&self, mut f: impl FnMut(&Layer)) {
        self.iter_sublayers_and_masks_nested_internal(&mut f)
    }

    fn iter_sublayers_and_masks_nested_internal(&self, f: &mut impl FnMut(&Layer)) {
        f(self);
        if let Some(mask) = self.mask() {
            mask.layer.iter_sublayers_and_masks_nested_internal(f)
        }
        self.for_each_sublayer(|layer| layer.iter_sublayers_and_masks_nested_internal(f));
    }
}

impl AsRef<Layer> for Layer {
    fn as_ref(&self) -> &Layer {
        self
    }
}

impl Debug for Layer {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        Debug::fmt(&*self.model, f)
    }
}

impl From<&Layer> for LayerId {
    fn from(t: &Layer) -> Self {
        t.id()
    }
}

impl Eq for Layer {}
impl PartialEq for Layer {
    fn eq(&self, other: &Self) -> bool {
        Rc::ptr_eq(&self.model, &other.model)
    }
}



// =================
// === WeakLayer ===
// =================

/// A weak version of [`Layer`].
#[derive(Clone, CloneRef, Default)]
pub struct WeakLayer {
    model: Weak<LayerModel>,
}

impl WeakLayer {
    /// Upgrade to strong reference.
    pub fn upgrade(&self) -> Option<Layer> {
        self.model.upgrade().map(|model| Layer { model })
    }

    /// Attach a `layer` as a sublayer. Will do nothing if the layer does not exist.
    pub fn add_sublayer(&self, sublayer: &Layer) {
        if let Some(layer) = self.upgrade() {
            layer.add_sublayer(sublayer)
        } else {
            warn!("Attempt to add a sublayer to deallocated layer.");
        }
    }

    /// Remove previously attached sublayer. Will do nothing if the layer does not exist.
    pub fn remove_sublayer(&self, sublayer: &Layer) {
        if let Some(layer) = self.upgrade() {
            layer.remove_sublayer(sublayer)
        } else {
            warn!("Attempt to remove a sublayer from deallocated layer.");
        }
    }
}

impl Debug for WeakLayer {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "WeakLayer")
    }
}

impl Eq for WeakLayer {}
impl PartialEq for WeakLayer {
    fn eq(&self, other: &Self) -> bool {
        self.model.ptr_eq(&other.model)
    }
}

impl PartialEq<Layer> for WeakLayer {
    fn eq(&self, other: &Layer) -> bool {
        self.model.strong_ptr_eq(&other.model)
    }
}



// ==================
// === LayerModel ===
// ==================

/// Internal representation of [`Layer`].
///
/// Please note that the [`parent`] field contains reference to a very small part of parent layer,
/// namely to its [`Sublayers`] struct. Only this part is needed to properly update all the models.
#[allow(missing_docs)]
pub struct LayerModel {
    pub name: String,
    camera: RefCell<Camera2d>,
    pub shape_system_registry: ShapeSystemRegistry,
    shape_system_to_symbol_info_map:
        RefCell<HashMap<ShapeSystemIdWithFlavor, ShapeSystemSymbolInfo>>,
    symbol_to_shape_system_map: RefCell<HashMap<SymbolId, ShapeSystemIdWithFlavor>>,
    elements: RefCell<BTreeSet<LayerItem>>,
    symbols_renderable: RefCell<RenderGroup>,
    depth_order: RefCell<DependencyGraph<LayerOrderItem>>,
    depth_order_dirty: dirty::SharedBool<OnDepthOrderDirty>,
    parent: Rc<RefCell<Option<Sublayers>>>,
    global_element_depth_order: RefCell<DependencyGraph<LayerOrderItem>>,
    sublayers: Sublayers,
    symbol_buffer_partitions: RefCell<HashMap<ShapeSystemId, usize>>,
    mask: Cell<Option<Mask<WeakLayer>>>,
    scissor_box: RefCell<Option<ScissorBox>>,
    blend_mode: Cell<BlendMode>,
    mem_mark: Rc<()>,
    pub flags: LayerFlags,
    /// When [`display::object::ENABLE_DOM_DEBUG`] is enabled all display objects on this layer
    /// will be represented by a DOM in this DOM layer.
    pub debug_dom: Option<enso_web::HtmlDivElement>,
}

impl Debug for LayerModel {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Layer")
            .field("name", &self.name)
            .field("id", &self.id().raw)
            .field("registry", &self.shape_system_registry)
            .field("elements", &self.elements.borrow().iter().collect_vec())
            .field("symbols_renderable", &self.symbols_renderable)
            .finish()
    }
}

impl Drop for LayerModel {
    fn drop(&mut self) {
        self.remove_from_parent();
        if let Some(dom) = &mut self.debug_dom {
            dom.remove();
        }
    }
}

impl LayerModel {
    fn new(name: impl Into<String>, flags: LayerFlags) -> Self {
        let name = name.into();
        let parent = default();
        let on_mut = on_depth_order_dirty(&parent);
        let depth_order_dirty = dirty::SharedBool::new(on_mut);
        let sublayers = Sublayers::new(&parent);
        let mut debug_dom = default();

        if display::object::ENABLE_DOM_DEBUG {
            use enso_web::prelude::*;
            if let Some(document) = enso_web::window.document() {
                let root = document.get_html_element_by_id("debug-root").unwrap_or_else(|| {
                    let root = document.create_html_element_or_panic("div");
                    let checkbox = document.create_html_element_or_panic("input");
                    checkbox.set_attribute_or_warn("type", "checkbox");
                    let label = document.create_html_element_or_panic("label");
                    label.set_inner_text("DOM Debug");
                    label.set_id("debug-enable-checkbox");
                    label.append_child(&checkbox).unwrap();

                    root.set_id("debug-root");
                    // Dashboard uses z-index 9999 for toasts full-screen div already...
                    root.set_style_or_warn("z-index", "10000");
                    let body = document.body().unwrap();
                    body.append_child(&label).unwrap();
                    body.append_child(&root).unwrap();
                    root
                });

                let dom = document.create_div_or_panic();
                dom.set_class_name("debug-layer");
                dom.set_attribute_or_warn("data-layer-name", &name);
                root.append_child(&dom).unwrap();
                debug_dom = Some(dom);
            }
        }

        Self {
            name,
            depth_order_dirty,
            sublayers,
            flags,
            parent,
            camera: default(),
            shape_system_registry: default(),
            shape_system_to_symbol_info_map: default(),
            symbol_to_shape_system_map: default(),
            elements: default(),
            symbols_renderable: default(),
            depth_order: default(),
            global_element_depth_order: default(),
            symbol_buffer_partitions: default(),
            mask: default(),
            scissor_box: default(),
            blend_mode: default(),
            mem_mark: default(),
            debug_dom,
        }
    }

    /// Unique identifier of this layer. It is memory-based, it will be unique even for layers in
    /// different instances of [`Scene`].
    pub fn id(&self) -> LayerId {
        LayerId::new(Rc::as_ptr(&self.mem_mark) as usize)
    }

    /// Vector of all symbols registered in this layer, ordered according to the defined depth-order
    /// dependencies. Please note that this function does not update the depth-ordering of the
    /// elements. Updates are performed by calling the `update` method on [`Layer`], which happens
    /// at least once per animation frame.
    pub fn symbols(&self) -> impl Deref<Target = RenderGroup> + '_ {
        self.symbols_renderable.borrow()
    }

    /// Check if this layer has any symbols to render.
    pub fn has_symbols(&self) -> bool {
        !self.symbols_renderable.borrow().is_empty()
    }

    /// Check if this layer has any sublayers that are renderable in the main pass.
    pub fn has_main_pass_sublayers(&self) -> bool {
        self.sublayers
            .borrow()
            .iter_all()
            .any(|sublayer| sublayer.flags.contains(LayerFlags::MAIN_PASS_VISIBLE))
    }

    /// Add depth-order dependency between two [`LayerOrderItem`]s in this layer.
    pub fn add_elements_order_dependency(
        &self,
        below: impl Into<LayerOrderItem>,
        above: impl Into<LayerOrderItem>,
    ) {
        let below = below.into();
        let above = above.into();
        if self.depth_order.borrow_mut().insert_dependency(below, above) {
            self.depth_order_dirty.set();
        }
    }

    /// Remove a depth-order dependency between two [`LayerItem`]s in this layer. Returns `true`
    /// if the dependency was found, and `false` otherwise.
    pub fn remove_elements_order_dependency(
        &self,
        below: impl Into<LayerOrderItem>,
        above: impl Into<LayerOrderItem>,
    ) -> bool {
        let below = below.into();
        let above = above.into();
        let found = self.depth_order.borrow_mut().remove_dependency(below, above);
        if found {
            self.depth_order_dirty.set();
        }
        found
    }

    /// Add depth-order dependency between two shape-like definitions, where a "shape-like"
    /// definition means a [`Shape`], a [`ShapeProxy`], or user-defined shape system.
    pub fn add_shapes_order_dependency<S1, S2>(&self) -> (ZST<S1>, ZST<S2>)
    where
        S1: Shape,
        S2: Shape, {
        let s1_id = ShapeSystem::<S1>::id();
        let s2_id = ShapeSystem::<S2>::id();
        self.add_elements_order_dependency(s1_id, s2_id);
        self.depth_order_dirty.set();
        default()
    }

    /// Remove depth-order dependency between two shape-like definitions, where a "shape-like"
    /// definition means a [`Shape`], a [`ShapeProxy`], or user-defined shape system. Returns
    /// `true` if the dependency was found, and `false` otherwise.
    pub fn remove_shapes_order_dependency<S1, S2>(&self) -> (bool, ZST<S1>, ZST<S2>)
    where
        S1: Shape,
        S2: Shape, {
        let s1_id = ShapeSystem::<S1>::id();
        let s2_id = ShapeSystem::<S2>::id();
        let found = self.remove_elements_order_dependency(s1_id, s2_id);
        if found {
            self.depth_order_dirty.set();
        }
        (found, default(), default())
    }

    /// Camera getter of this layer.
    pub fn camera(&self) -> Camera2d {
        self.camera.borrow().clone_ref()
    }

    /// Camera setter of this layer. Will propagate the camera to all sublayers if they inherit
    /// the parent camera.
    fn set_camera(&self, camera: impl Into<Camera2d>) {
        let camera = camera.into();
        *self.camera.borrow_mut() = camera.clone_ref();
        self.for_each_sublayer(|layer| {
            if layer.flags.contains(LayerFlags::INHERIT_PARENT_CAMERA) {
                layer.set_camera(camera.clone_ref());
            }
        });
    }


    /// Set the blend mode used during rendering of this layer. See [`BlendMode`] for more
    /// information. By default, the blend mode is set to [`BlendMode::PREMULTIPLIED_ALPHA_OVER`].
    ///
    /// NOTE: Due to a limitation of WebGL, the set blend mode is also applied to object ID buffer
    /// for all objects on this layer. If you use a blend mode that is not preserving fully opaque
    /// pixels unchanged (e.g. additive blending), the object ID buffer will contain invalid values.
    /// In such cases, make sure to use that layer only for objects with pointer events disabled.
    /// This limitation may be lifted in the future, since other rendering APIs allow setting the
    /// blend mode for each render target separately.
    pub fn set_blend_mode(&self, blend_mode: BlendMode) {
        self.blend_mode.replace(blend_mode);
    }

    /// Get the blend mode used during rendering of this layer. See [`Self::set_blend_mode`] for
    /// more information.
    pub fn blend_mode(&self) -> BlendMode {
        self.blend_mode.get()
    }

    /// Add the symbol to this layer.
    pub fn add_symbol(&self, symbol_id: impl Into<SymbolId>) {
        self.add_element(symbol_id.into(), None)
    }

    /// Add the shape to this layer.
    pub(crate) fn add_shape(
        &self,
        shape_system_info: ShapeSystemInfo,
        symbol_id: impl Into<SymbolId>,
    ) {
        self.add_element(symbol_id.into(), Some(shape_system_info))
    }

    /// Internal helper for adding elements to this layer.
    fn add_element(&self, symbol_id: SymbolId, shape_system_info: Option<ShapeSystemInfo>) {
        self.depth_order_dirty.set();
        match shape_system_info {
            None => {
                self.elements.borrow_mut().insert(LayerItem::Symbol(symbol_id));
            }
            Some(info) => {
                let symbol_info = ShapeSystemSymbolInfo::new(symbol_id, info.ordering);
                self.shape_system_to_symbol_info_map.borrow_mut().insert(info.id, symbol_info);
                self.symbol_to_shape_system_map.borrow_mut().insert(symbol_id, info.id);
                self.elements.borrow_mut().insert(LayerItem::ShapeSystem(info.id));
            }
        }
    }

    /// Remove the symbol from the current layer.
    pub fn remove_symbol(&self, symbol_id: impl Into<SymbolId>) {
        self.depth_order_dirty.set();
        let symbol_id = symbol_id.into();

        self.elements.borrow_mut().remove(&LayerItem::Symbol(symbol_id));
        if let Some(shape_system_id) =
            self.symbol_to_shape_system_map.borrow_mut().remove(&symbol_id)
        {
            self.shape_system_to_symbol_info_map.borrow_mut().remove(&shape_system_id);
            self.elements.borrow_mut().remove(&LayerItem::ShapeSystem(shape_system_id));
        }
    }

    /// Remove the [`ShapeSystem`] registered in this layer together with all of its [`Symbol`]s.
    pub fn remove_shape_system(&self, shape_system_id: ShapeSystemId) {
        self.depth_order_dirty.set();
        for flavor in self.shape_system_registry.flavors(shape_system_id) {
            let id = ShapeSystemIdWithFlavor { id: shape_system_id, flavor };
            self.elements.borrow_mut().remove(&LayerItem::ShapeSystem(id));
            if let Some(symbol_id) = self.shape_system_to_symbol_info_map.borrow_mut().remove(&id) {
                self.symbol_to_shape_system_map.borrow_mut().remove(&symbol_id.id);
            }
        }
    }

    /// Consume all dirty flags and update the ordering of elements if needed. Returns [`true`] if
    /// the layer or its sub-layers were modified during this call.
    pub fn update(&self) -> bool {
        self.update_internal(default(), default())
    }

    /// Consume all dirty flags and update the ordering of elements if needed.
    #[profile(Debug)]
    pub(crate) fn update_internal(
        &self,
        global_element_depth_order: Option<&DependencyGraph<LayerOrderItem>>,
        parent_depth_order_changed: bool,
    ) -> bool {
        let mut was_dirty = false;

        if self.depth_order_dirty.check() || parent_depth_order_changed {
            was_dirty = true;
            self.depth_order_dirty.unset();
            self.depth_sort(global_element_depth_order);
        }

        if self.sublayers.element_depth_order_dirty.check() {
            was_dirty = true;
            self.sublayers.element_depth_order_dirty.unset();
            self.for_each_sublayer(|layer| {
                let global_order = self.global_element_depth_order.borrow();
                layer.update_internal(Some(&*global_order), true);
            });
            if let Some(mask) = self.mask() {
                let global_order = self.global_element_depth_order.borrow();
                mask.layer.update_internal(Some(&*global_order), true);
            }
        }

        was_dirty
    }

    /// Update this layer's DOM debug object with current camera transform.
    pub(crate) fn update_debug_view(&self) {
        if !display::object::ENABLE_DOM_DEBUG {
            return;
        }

        use display::camera::camera2d::Projection;
        use enso_web::prelude::*;

        let Some(dom) = &self.debug_dom else { return };
        let camera = self.camera.borrow();

        let trans_cam = camera.transformation_matrix().try_inverse();
        let mut trans_cam = trans_cam.expect("Camera's matrix is not invertible.");
        let half_dim_y = camera.screen().height / 2.0;
        let fovy_slope = camera.half_fovy_slope();
        let near = half_dim_y / fovy_slope;

        match camera.projection() {
            Projection::Perspective { .. } => {
                trans_cam.prepend_translation_mut(&Vector3(0.0, 0.0, near));
            }
            Projection::Orthographic => {}
        }
        trans_cam.append_nonuniform_scaling_mut(&Vector3(1.0, -1.0, 1.0));
        let matrix_fmt = display::object::transformation::CssTransformFormatter(&trans_cam);
        let style = format!("transform:perspective({near}px) {matrix_fmt};");
        dom.set_attribute_or_warn("style", style);
    }

    /// Compute a combined [`DependencyGraph`] for the layer taking into consideration the global
    /// dependency graph (from root [`Layer`]), the local one (per layer), and individual shape
    /// preferences (see the "Compile Time Shapes Ordering Relations" section in docs of [`Layer`]
    /// to learn more).
    fn combined_depth_order_graph(
        &self,
        global_element_depth_order: Option<&DependencyGraph<LayerOrderItem>>,
    ) -> DependencyGraph<LayerOrderItem> {
        let mut graph = if let Some(global_element_depth_order) = global_element_depth_order {
            let mut graph = global_element_depth_order.clone();
            graph.extend(self.depth_order.borrow().clone().into_iter());
            graph
        } else {
            self.depth_order.borrow().clone()
        };
        for element in &*self.elements.borrow() {
            if let LayerItem::ShapeSystem(id) = element {
                if let Some(info) = self.shape_system_to_symbol_info_map.borrow().get(id) {
                    for &id2 in &info.below {
                        graph.insert_dependency(element.into(), id2.into());
                    }
                    for &id2 in &info.above {
                        graph.insert_dependency(id2.into(), element.into());
                    }
                }
            }
        }
        graph
    }

    fn depth_sort(&self, global_element_depth_order: Option<&DependencyGraph<LayerOrderItem>>) {
        let graph = self.combined_depth_order_graph(global_element_depth_order);
        let elements = self.elements.borrow();
        let mut order_items = elements.iter().map(|&e| LayerOrderItem::from(e)).collect_vec();
        order_items.dedup();
        let dependency_sorted_elements = graph.into_unchecked_topo_sort(order_items);
        let mut sorted_symbols = Vec::with_capacity(self.elements.borrow().len());
        for element in dependency_sorted_elements {
            match element {
                LayerOrderItem::Symbol(id) => sorted_symbols.push(id),
                LayerOrderItem::ShapeSystem(id) => {
                    let lower_bound = LayerItem::ShapeSystem(ShapeSystemIdWithFlavor {
                        id,
                        flavor: ShapeSystemFlavor::MIN,
                    });
                    let flavors = elements
                        .range(lower_bound..)
                        .take_while(|e| matches!(e, LayerItem::ShapeSystem(info) if info.id == id));
                    sorted_symbols.extend(flavors.filter_map(|item| match *item {
                        LayerItem::Symbol(symbol_id) => Some(symbol_id),
                        LayerItem::ShapeSystem(id) => {
                            let out = self
                                .shape_system_to_symbol_info_map
                                .borrow()
                                .get(&id)
                                .map(|t| t.id);
                            if out.is_none() {
                                warn!(
                                    "Trying to perform depth-order of non-existing element '{:?}'.",
                                    id
                                )
                            }
                            out
                        }
                    }))
                }
            };
        }
        self.symbols_renderable.borrow_mut().set(sorted_symbols);
    }
}


// === Grouping Utilities ===

impl LayerModel {
    /// Query [`Layer`] by [`LayerId`].
    pub fn get_sublayer(&self, layer_id: LayerId) -> Option<Layer> {
        self.sublayers.borrow().get(layer_id)
    }

    /// Vector of all layers, ordered according to the defined depth-order dependencies. Please note
    /// that this function does not update the depth-ordering of the layers. Updates are performed
    /// by calling the `update` method on [`Layer`], which happens at least once per animation
    /// frame.
    pub fn sublayers(&self) -> Vec<Layer> {
        self.sublayers.borrow().all()
    }

    /// Iterate over all sublayers, ordered according to the defined depth-order dependencies. The
    /// layer sublayers list will be borrowed during the iteration.
    ///
    /// Please note that this function does not update the depth-ordering of the layers. Updates are
    /// performed by calling the `update` method on [`Layer`], which happens at least once per
    /// animation frame.
    pub fn for_each_sublayer(&self, mut f: impl FnMut(Layer)) {
        for layer in self.sublayers.borrow().iter_all() {
            f(layer);
        }
    }

    /// Attach a `layer` as a sublayer. If that layer is already attached as a sublayer of any
    /// layer, it will be detached first.
    pub fn add_sublayer(&self, layer: &Layer) {
        layer.remove_from_parent();
        self.sublayers.borrow_mut().add(layer);
        layer.set_parent(self);
    }

    /// Remove previously attached sublayer.
    ///
    /// The implementation is the opposite of [`LayerModel::add_sublayer`]: we modify both fields of
    /// [`SublayersModel`] and also unset parent. If the layer was not attached as a sublayer, this
    /// function does nothing.
    pub fn remove_sublayer(&self, layer: &Layer) {
        let removed = self.sublayers.borrow_mut().remove(layer.id());
        if removed {
            *layer.parent.borrow_mut() = None;
        }
    }

    fn remove_all_sublayers(&self) {
        for layer in self.sublayers.borrow().layers.iter() {
            if let Some(layer) = layer.upgrade() {
                layer.unset_parent()
            }
        }
        mem::take(&mut *self.sublayers.model.borrow_mut());
    }

    fn set_parent(&self, parent: &LayerModel) {
        *self.parent.borrow_mut() = Some(parent.sublayers.clone_ref());
        if self.flags.contains(LayerFlags::INHERIT_PARENT_CAMERA) {
            self.set_camera(parent.camera());
        }
        // Parent's `global_element_depth_order` is an input to depth order computation.
        self.depth_order_dirty.set();
    }

    /// Clear the parent field. Note that this doesn't remove the layer from its parent's sublayer
    /// list.
    fn unset_parent(&self) {
        *self.parent.borrow_mut() = None;
        // Recompute depth order, in case removing a parent resolved a cycle.
        self.depth_order_dirty.set();
    }

    /// Clear the parent field and remove the layer from its parent's sublayer list.
    fn remove_from_parent(&self) {
        // Borrow `self.parent` only within the scope of this line. Note that if this expression
        // were used directly as the matched expression of the `if let`, the `RefMut` would not be
        // dropped until the end of the `if let` block, even though it is a temporary value within
        // the subexpression with `take`.
        let parent = self.parent.borrow_mut().take();
        if let Some(parent) = parent {
            parent.borrow_mut().remove(self.id());
            // Recompute depth order, in case removing a parent resolved a cycle.
            self.depth_order_dirty.set();
        }
    }

    /// Set all sublayers layer of this layer. Old sublayers layers will be unregistered.
    pub fn set_sublayers(&self, layers: &[&Layer]) {
        self.remove_all_sublayers();
        for layer in layers {
            self.add_sublayer(layer)
        }
    }

    /// Create a new sublayer to this layer. It will inherit this layer's camera.
    pub fn create_sublayer(&self, name: impl Into<String>) -> Layer {
        let layer = Layer::new_with_flags(
            name,
            LayerFlags::MAIN_PASS_VISIBLE | LayerFlags::INHERIT_PARENT_CAMERA,
        );
        self.add_sublayer(&layer);
        layer
    }

    /// Create a new sublayer to this layer. Override the camera for this layer, breaking the camera
    /// inheritance chain. Updates to the parent camera will not affect this layer.
    pub fn create_sublayer_with_camera(&self, name: impl Into<String>, camera: &Camera2d) -> Layer {
        let layer = Layer::new_with_camera(name, camera);
        self.add_sublayer(&layer);
        layer
    }

    /// Create a new sublayer to this layer, optionally providing a camera override. See
    /// [`Self::create_sublayer_with_camera`] and [`Self::create_sublayer`] for more information.
    pub fn create_sublayer_with_optional_camera(
        &self,
        name: impl Into<String>,
        camera: Option<&Camera2d>,
    ) -> Layer {
        match camera {
            Some(camera) => self.create_sublayer_with_camera(name, camera),
            None => self.create_sublayer(name),
        }
    }

    /// Create a new sublayer to this layer. It will inherit this layer's camera, but will not be
    /// rendered as a part of standard layer stack. Instead, it will only be rendered as a mask for
    /// other layers. See [`Layer::set_mask`] for more information. Note that this will not set up
    /// the mask connection. You still have to separately call `set_mask` on any layer you want to
    /// mask with this layer.
    pub fn create_mask_sublayer(&self, name: impl Into<String>) -> Layer {
        let layer = Layer::new_with_flags(name, LayerFlags::INHERIT_PARENT_CAMERA);
        self.add_sublayer(&layer);
        layer
    }

    /// Create a symbol partition in the layer. The order of symbol partition creation determines
    /// depth ordering of their content objects of the type specified in the type parameter.
    pub fn create_symbol_partition<S: Shape>(
        self: &Rc<Self>,
        _name: impl Into<String>,
    ) -> LayerSymbolPartition<S> {
        let system_id = ShapeSystem::<S>::id();
        let mut partitions = self.symbol_buffer_partitions.borrow_mut();
        let index = partitions.entry(system_id).or_default();
        let id = Immutable(SymbolPartitionId { index: *index });
        *index += 1;
        LayerSymbolPartition { layer: WeakLayer { model: self.downgrade() }, id, shape: default() }
    }

    /// Return some symbol partition for a particular symbol in the layer. This can be used to refer
    /// to the only partition in an unpartitioned layer.
    pub fn default_partition<S: Shape>(self: &Rc<Self>) -> LayerSymbolPartition<S> {
        let layer = WeakLayer { model: self.downgrade() };
        LayerSymbolPartition { layer, id: default(), shape: default() }
    }

    /// The layer's mask, if any.
    pub fn mask(&self) -> Option<Mask> {
        let weak_mask = self.mask.take()?;
        let layer = weak_mask.layer.upgrade()?;
        let inverted = weak_mask.inverted;
        self.mask.set(Some(weak_mask));
        Some(Mask { layer, inverted })
    }

    /// Set a mask layer of this layer. Old mask layer will be unregistered.
    pub fn set_mask(&self, mask: &Layer) {
        self.mask.set(Some(Mask { layer: mask.downgrade(), inverted: false }))
    }

    /// Set an inverted mask layer of this layer. Old mask layer will be unregistered.
    ///
    /// NOTE: When using an inverted mask, the mask layer's own blend mode is ignored. Instead,
    /// the mask layer is always rendered with the blend mode [`BlendMode::ALPHA_CUTOUT`], carving
    /// out the shape of the mask from the layer it masks.
    pub fn set_inverted_mask(&self, mask: &Layer) {
        self.mask.set(Some(Mask { layer: mask.downgrade(), inverted: true }))
    }

    /// Remove a previously set mask from this layer.
    pub fn remove_mask(&self) {
        self.mask.take();
    }

    /// The layer's [`ScissorBox`], if any.
    pub fn scissor_box(&self) -> Option<ScissorBox> {
        *self.scissor_box.borrow()
    }

    /// Set the [`ScissorBox`] of this layer.
    pub fn set_scissor_box(&self, scissor_box: Option<&ScissorBox>) {
        *self.scissor_box.borrow_mut() = scissor_box.cloned();
    }

    /// Add depth-order dependency between two [`LayerItem`]s in this layer. Returns `true`
    /// if the dependency was inserted successfully (was not already present), and `false`
    /// otherwise. All sublayers will inherit these rules.
    pub fn add_global_elements_order_dependency(
        &self,
        below: impl Into<LayerOrderItem>,
        above: impl Into<LayerOrderItem>,
    ) -> bool {
        let below = below.into();
        let above = above.into();
        let fresh = self.global_element_depth_order.borrow_mut().insert_dependency(below, above);
        if fresh {
            self.sublayers.element_depth_order_dirty.set();
        }
        fresh
    }

    /// Remove a depth-order dependency between two [`LayerItem`]s in this layer. Returns `true`
    /// if the dependency was found, and `false` otherwise.
    pub fn remove_global_elements_order_dependency(
        &self,
        below: impl Into<LayerOrderItem>,
        above: impl Into<LayerOrderItem>,
    ) -> bool {
        let below = below.into();
        let above = above.into();
        let found = self.global_element_depth_order.borrow_mut().remove_dependency(below, above);
        if found {
            self.sublayers.element_depth_order_dirty.set();
        }
        found
    }

    /// # Future Improvements
    /// This implementation can be simplified to `S1:KnownShapeSystemId` (not using [`Content`] at
    /// all), after the compiler gets updated to newer version. Returns `true` if the dependency was
    /// inserted successfully (was not already present), and `false` otherwise.
    pub fn add_global_shapes_order_dependency<S1, S2>(&self) -> (bool, ZST<S1>, ZST<S2>)
    where
        S1: Shape,
        S2: Shape, {
        let s1_id = ShapeSystem::<S1>::id();
        let s2_id = ShapeSystem::<S2>::id();
        let fresh = self.add_global_elements_order_dependency(s1_id, s2_id);
        (fresh, default(), default())
    }

    /// # Future Improvements
    /// This implementation can be simplified to `S1:KnownShapeSystemId` (not using [`Content`] at
    /// all), after the compiler gets updated to newer version. Returns `true` if the dependency was
    /// found, and `false` otherwise.
    pub fn remove_global_shapes_order_dependency<S1, S2>(&self) -> (bool, ZST<S1>, ZST<S2>)
    where
        S1: Shape,
        S2: Shape, {
        let s1_id = ShapeSystem::<S1>::id();
        let s2_id = ShapeSystem::<S2>::id();
        let found = self.remove_global_elements_order_dependency(s1_id, s2_id);
        (found, default(), default())
    }
}

/// The callback setting `element_depth_order_dirty` in parents.
pub type OnDepthOrderDirty = impl Fn();
fn on_depth_order_dirty(parent: &Rc<RefCell<Option<Sublayers>>>) -> OnDepthOrderDirty {
    let parent = parent.clone();
    move || {
        if let Some(parent) = parent.borrow().as_ref() {
            // It's safe to do it having parent borrowed, because the only possible callback called
            // [`OnElementDepthOrderDirty`], which don't borrow_mut at any point.
            parent.element_depth_order_dirty.set()
        }
    }
}

impl AsRef<LayerModel> for Layer {
    fn as_ref(&self) -> &LayerModel {
        &self.model
    }
}

impl std::borrow::Borrow<LayerModel> for Layer {
    fn borrow(&self) -> &LayerModel {
        &self.model
    }
}


// === Layer symbol partitions ===

/// A symbol partition within a [`Layer`].
///
/// Symbol partitions determine the depth-order of instances of a particular symbol within a layer.
/// Any other symbol added to a symbol partition will be treated as if present in the parent layer.
#[derive(Debug, Derivative, CloneRef)]
#[derivative(Clone(bound = ""))]
pub struct LayerSymbolPartition<S> {
    layer: WeakLayer,
    id:    Immutable<SymbolPartitionId>,
    shape: ZST<S>,
}

/// Identifies a symbol partition, for some [`Symbol`], relative to some [`Layer`].
#[derive(Debug, Default, Copy, Clone, PartialEq, Eq)]
pub struct SymbolPartitionId {
    index: usize,
}

impl<S: Shape> LayerSymbolPartition<S> {
    /// Add the display object to this symbol partition and remove it from a layer it was assigned
    /// to, if any.
    pub fn add(&self, object: impl display::Object) {
        if let Some(layer) = self.layer.upgrade() {
            object.display_object().add_to_display_layer_symbol_partition(&layer, self.into());
        } else {
            error!("Shape added to symbol partition of non-existent layer.");
        }
    }

    /// Remove the display object from a layer it was assigned to, if any.
    pub fn remove(&self, object: impl display::Object) {
        if let Some(layer) = self.layer.upgrade() {
            object.display_object().remove_from_display_layer(&layer);
        }
    }
}

// === Type-erased symbol partitions ===

/// Identifies a [`Symbol`] and ID of its partition, relative to some [`Layer`].
#[derive(Debug, Copy, Clone, PartialEq, Eq)]
pub struct AnySymbolPartition {
    id:    SymbolPartitionId,
    shape: ShapeSystemId,
}

impl AnySymbolPartition {
    /// Get the partition id assigned to the specified shape system, if any.
    pub fn partition_id(&self, shape_system: ShapeSystemId) -> Option<SymbolPartitionId> {
        (self.shape == shape_system).then_some(self.id)
    }
}

impl<S: Shape> From<&'_ LayerSymbolPartition<S>> for AnySymbolPartition {
    fn from(value: &'_ LayerSymbolPartition<S>) -> Self {
        let shape = ShapeSystem::<S>::id();
        let id = *value.id;
        Self { shape, id }
    }
}



// =========================
// === LayerShapeBinding ===
// =========================

/// Information about an instance of a shape bound to a particular layer.
#[derive(Debug)]
#[allow(missing_docs)]
pub struct LayerShapeBinding {
    pub layer:              WeakLayer,
    pub global_instance_id: symbol::GlobalInstanceId,
}

impl LayerShapeBinding {
    /// Constructor.
    pub fn new(layer: &Layer, global_instance_id: symbol::GlobalInstanceId) -> Self {
        let layer = layer.downgrade();
        Self { layer, global_instance_id }
    }
}



// =================
// === Sublayers ===
// =================

/// The callback propagating `element_depth_order_dirty` flag to parent.
pub type OnElementDepthOrderDirty = impl Fn();
fn on_element_depth_order_dirty(
    parent: &Rc<RefCell<Option<Sublayers>>>,
) -> OnElementDepthOrderDirty {
    let parent = parent.clone_ref();
    move || {
        if let Some(parent) = parent.borrow().as_ref() {
            // It's safe to do it having parent borrowed, because the only possible callback called
            // [`OnElementDepthOrderDirty`], which don't borrow_mut at any point.
            parent.element_depth_order_dirty.set()
        }
    }
}

/// Abstraction for layer sublayers.
#[derive(Clone, CloneRef, Debug)]
pub struct Sublayers {
    model:                     Rc<RefCell<SublayersModel>>,
    element_depth_order_dirty: dirty::SharedBool<OnElementDepthOrderDirty>,
}

impl Deref for Sublayers {
    type Target = Rc<RefCell<SublayersModel>>;
    fn deref(&self) -> &Self::Target {
        &self.model
    }
}

impl Eq for Sublayers {}
impl PartialEq for Sublayers {
    fn eq(&self, other: &Self) -> bool {
        Rc::ptr_eq(&self.model, &other.model)
    }
}

impl Sublayers {
    /// Constructor.
    pub fn new(parent: &Rc<RefCell<Option<Sublayers>>>) -> Self {
        let model = default();
        let dirty_on_mut = on_element_depth_order_dirty(parent);
        let element_depth_order_dirty = dirty::SharedBool::new(dirty_on_mut);
        Self { model, element_depth_order_dirty }
    }
}



// ======================
// === SublayersModel ===
// ======================

/// Internal representation of [`Sublayers`].
#[derive(Debug, Default)]
pub struct SublayersModel {
    layers:          OptVec<WeakLayer>,
    layer_placement: HashMap<LayerId, usize>,
}

impl SublayersModel {
    /// Vector of all layers, ordered according to the defined depth-order dependencies. Please note
    /// that this function does not update the depth-ordering of the layers. Updates are performed
    /// by calling the `update` method on [`LayerModel`], which happens once per animation frame.
    pub fn all(&self) -> Vec<Layer> {
        self.iter_all().collect()
    }

    /// Iterator of all layers, ordered according to the defined depth-order dependencies. Please
    /// note that this function does not update the depth-ordering of the layers. Updates are
    /// performed by calling the `update` method on [`LayerModel`], which happens once per animation
    /// frame.
    pub fn iter_all(&self) -> impl Iterator<Item = Layer> + '_ {
        self.layers.iter().filter_map(|t| t.upgrade())
    }


    fn layer_ix(&self, layer_id: LayerId) -> Option<usize> {
        self.layer_placement.get(&layer_id).copied()
    }

    fn add(&mut self, layer: &Layer) {
        let ix = self.layers.insert(layer.downgrade());
        self.layer_placement.insert(layer.id(), ix);
    }

    fn remove(&mut self, layer_id: LayerId) -> bool {
        if let Some(ix) = self.layer_ix(layer_id) {
            self.layers.remove(ix);
            true
        } else {
            false
        }
    }

    /// Query a [`Layer`] based on its [`LayerId`].
    pub fn get(&self, layer_id: LayerId) -> Option<Layer> {
        self.layer_ix(layer_id).and_then(|ix| self.layers.safe_index(ix).and_then(|t| t.upgrade()))
    }
}



// ==============
// === Masked ===
// ==============

/// A layer with an attached mask. Each shape in the `mask_layer` defines the renderable area
/// of the `masked_layer`. See [`Layer`] docs for the info about masking.
///
/// One of the use cases might be an `ensogl_scroll_area::ScrollArea` component
/// implementation. To clip the area's content (so that it is displayed only inside its borders) we
/// place the area's content in the `masked_object` layer; and we place a rectangular mask in the
/// `mask` layer.
///
/// We need to store `mask_layer`, because [`LayerModel::set_mask`] uses [`WeakLayer`] internally,
/// so the [`Layer`] would be deallocated otherwise.
#[derive(Debug, Clone, CloneRef, Deref)]
#[allow(missing_docs)]
pub struct Masked {
    #[deref]
    pub masked_layer: Layer,
    pub mask_layer:   Layer,
}

impl AsRef<Layer> for Masked {
    fn as_ref(&self) -> &Layer {
        &self.masked_layer
    }
}

impl Masked {
    /// Constructor.
    pub fn new() -> Self {
        let masked_layer = Layer::new("MaskedLayer");
        let mask_layer = masked_layer.create_mask_sublayer("MaskLayer");
        masked_layer.set_mask(&mask_layer);
        Self { masked_layer, mask_layer }
    }

    /// Constructor.
    pub fn new_with_cam(camera: &Camera2d) -> Self {
        let masked_layer = Layer::new_with_camera("MaskedLayer", camera);
        let mask_layer = masked_layer.create_mask_sublayer("MaskLayer");
        masked_layer.set_mask(&mask_layer);
        Self { masked_layer, mask_layer }
    }
}

impl Default for Masked {
    fn default() -> Self {
        Self::new()
    }
}



// ===============
// === LayerId ===
// ===============

use enso_shapely::newtype_prim;
newtype_prim! {
    /// The ID of a layer. Under the hood, it is the index of the layer.
    LayerId(usize);
}



// =================
// === LayerItem ===
// =================

/// Abstraction over [`SymbolId`] and [`ShapeSystemId`]. Read docs of [`Layer`] to learn about its
/// usage scenarios.
#[derive(Clone, Copy, Debug, PartialEq, PartialOrd, Eq, Hash, Ord)]
#[allow(missing_docs)]
pub enum LayerItem {
    Symbol(SymbolId),
    ShapeSystem(ShapeSystemIdWithFlavor),
}

impl From<ShapeSystemIdWithFlavor> for LayerItem {
    fn from(t: ShapeSystemIdWithFlavor) -> Self {
        Self::ShapeSystem(t)
    }
}


// === LayerOrderItem ===

/// Identifies an item only in terms of the information necessary to describe ordering
/// relationships. This is equivalent to [`LayerItem`], except different flavors of the same layer
/// are not distinguished.
#[derive(Clone, Copy, Debug, PartialEq, PartialOrd, Eq, Hash, Ord)]
#[allow(missing_docs)]
pub enum LayerOrderItem {
    Symbol(SymbolId),
    ShapeSystem(ShapeSystemId),
}

impl From<ShapeSystemId> for LayerOrderItem {
    fn from(t: ShapeSystemId) -> Self {
        Self::ShapeSystem(t)
    }
}

impl From<LayerItem> for LayerOrderItem {
    fn from(t: LayerItem) -> Self {
        Self::from(&t)
    }
}

impl From<&LayerItem> for LayerOrderItem {
    fn from(t: &LayerItem) -> Self {
        match *t {
            LayerItem::Symbol(id) => Self::Symbol(id),
            LayerItem::ShapeSystem(ShapeSystemIdWithFlavor { id, .. }) => Self::ShapeSystem(id),
        }
    }
}



// =====================
// === ShapeRegistry ===
// =====================

/// An entry containing [`Any`]-encoded [`ShapeSystem`] and information about symbol instance count
/// of this [`ShapeSystem`].
pub struct ShapeSystemRegistryEntry {
    shape_system:   Box<dyn Any>,
    instance_count: usize,
}

impl Debug for ShapeSystemRegistryEntry {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        Debug::fmt(&self.instance_count, f)
    }
}

/// Mutable reference to decoded [`ShapeSystemRegistryEntry`].
#[derive(Debug)]
pub struct ShapeSystemRegistryEntryRefMut<'t, T> {
    shape_system:   &'t mut T,
    instance_count: &'t mut usize,
}

shared! { ShapeSystemRegistry
/// A per [`Scene`] [`Layer`] user defined shape system registry. It is used as a cache for existing
/// shape system instances. When creating a shape instance, we often want it to share the same shape
/// system than other instances in order for all of them to be drawn with just a single WebGL draw
/// call. After adding a [`ShapeProxy`] to a layer, it will get instantiated (its shape will be
/// created), and because of this structure, it will share the same shape system as other shapes of
/// the same type on the same layer. Read the docs of [`ShapeProxy`] to learn more.
#[derive(Default,Debug)]
pub struct ShapeSystemRegistryData {
    shape_system_map : HashMap<(ShapeSystemId, ShapeSystemFlavor),ShapeSystemRegistryEntry>,
    shape_system_flavors: HashMap<ShapeSystemId, Vec<ShapeSystemFlavor>>,
}

impl {
    /// Instantiate the provided [`ShapeProxy`].
    pub fn instantiate<S>
    (&mut self, data: &S::ShapeData, symbol_partition: SymbolPartitionId) -> (ShapeSystemInfo, SymbolId, ShapeInstance<S>, symbol::GlobalInstanceId)
    where S : Shape {
        self.with_get_or_register_mut::<S,_,_>(data, |entry| {
            let system = entry.shape_system;
            let system_id = ShapeSystem::<S>::id();
            let SymbolPartitionId { index } = symbol_partition;
            let partition_id = crate::system::gpu::data::BufferPartitionId { index };
            let (shape_instance, global_instance_id) = system.instantiate(partition_id);
            let symbol_id = system.sprite_system().symbol.id;
            let above = S::always_above().to_vec();
            let below = S::always_below().to_vec();
            let ordering = ShapeSystemStaticDepthOrdering {above,below};
            let system_id_with_flavor = ShapeSystemIdWithFlavor {
                id: system_id, flavor: S::flavor(data),
            };
            let shape_system_info = ShapeSystemInfo::new(system_id_with_flavor, ordering);
            *entry.instance_count += 1;
            (shape_system_info, symbol_id, shape_instance, global_instance_id)
        })
    }

    /// Decrement internal register of used [`Symbol`] instances previously instantiated with the
    /// [`instantiate`] method. In case there are no more instances associated with any system of
    /// type `S`, the caller of this function should perform necessary cleanup.
    pub(crate) fn drop_instance<S>(
        &mut self,
        flavor: ShapeSystemFlavor
    ) -> (bool, ShapeSystemId, ZST<S>)
    where
        S : Shape
    {
        let system_id = ShapeSystem::<S>::id();
        let entry_is_empty = self.get_mut::<S>(flavor).map_or(true, |entry| {
            *entry.instance_count = entry.instance_count.saturating_sub(1);
            *entry.instance_count == 0
        });

        // Intentional short-circuit - avoid computing `total_system_instances` when we know there
        // are still more instances in the currently processed entry.
        let no_more_instances = entry_is_empty && self.total_system_instances(system_id) == 0;

        (no_more_instances, system_id, ZST())
    }

    fn flavors(&self, shape_system_id: ShapeSystemId) -> impl Iterator<Item=ShapeSystemFlavor> {
        self.shape_system_flavors.get(&shape_system_id).cloned().unwrap_or_default().into_iter()
    }
}}

impl ShapeSystemRegistryData {
    fn get_mut<S>(
        &mut self,
        flavor: ShapeSystemFlavor,
    ) -> Option<ShapeSystemRegistryEntryRefMut<ShapeSystem<S>>>
    where
        S: Shape,
    {
        let id = ShapeSystemId::of::<S>();
        self.shape_system_map.get_mut(&(id, flavor)).and_then(|t| {
            let shape_system = t.shape_system.downcast_mut::<ShapeSystem<S>>();
            let instance_count = &mut t.instance_count;
            shape_system.map(move |shape_system| ShapeSystemRegistryEntryRefMut {
                shape_system,
                instance_count,
            })
        })
    }

    // T: ShapeSystemInstance
    fn register<S>(
        &mut self,
        data: &S::ShapeData,
    ) -> ShapeSystemRegistryEntryRefMut<ShapeSystem<S>>
    where
        S: Shape,
    {
        let id = ShapeSystemId::of::<S>();
        let flavor = S::flavor(data);
        let system = ShapeSystem::<S>::new(data);
        let any = Box::new(system);
        let entry = ShapeSystemRegistryEntry { shape_system: any, instance_count: 0 };
        self.shape_system_map.entry((id, flavor)).insert_entry(entry);
        self.shape_system_flavors.entry(id).or_default().push(flavor);
        // The following line is safe, as the object was just registered.
        self.get_mut(flavor).unwrap()
    }

    /// Get total number of shape instances from shape systems of given type and all flavors.
    fn total_system_instances(&self, system_id: ShapeSystemId) -> usize {
        let Some(flavors) = self.shape_system_flavors.get(&system_id) else { return 0 };
        flavors.iter().map(|f| self.shape_system_map[&(system_id, *f)].instance_count).sum()
    }

    fn with_get_or_register_mut<S, F, Out>(&mut self, data: &S::ShapeData, f: F) -> Out
    where
        F: FnOnce(ShapeSystemRegistryEntryRefMut<ShapeSystem<S>>) -> Out,
        S: Shape, {
        let flavor = S::flavor(data);
        match self.get_mut(flavor) {
            Some(entry) => f(entry),
            None => f(self.register(data)),
        }
    }
}

// ============
// === Mask ===
// ============

/// A definition of mask operation to perform on a layer. The mask is another layer, which is used
/// to filter out parts of the layer it is assigned to it. When mask is not inverted, the parts of
/// the layer that are covered by the mask are kept, and the rest is discarded. When mask is
/// inverted, the parts covered by mask are discarded. Only the alpha channel of the mask is used.
#[derive(Debug, Clone)]
pub struct Mask<L = Layer> {
    /// The alpha channel of this layer is used as a mask.
    pub layer:    L,
    /// Whether the mask should be inverted. For non-inverted mask, the mask's alpha will multiply
    /// the alpha of the layer it is assigned to. For inverted mask, the mask's alpha will be
    /// subtracted from 1.0, and then multiplied by the alpha of the layer it is assigned to.
    pub inverted: bool,
}



// =======================
// === ShapeSystemInfo ===
// =======================

/// [`ShapeSystemInfoTemplate`] specialized for [`ShapeSystemIdWithFlavor`].
pub type ShapeSystemInfo = ShapeSystemInfoTemplate<ShapeSystemIdWithFlavor>;

/// [`ShapeSystemInfoTemplate`] specialized for [`SymbolId`].
pub type ShapeSystemSymbolInfo = ShapeSystemInfoTemplate<SymbolId>;

/// When adding a [`ShapeProxy`] to a [`Layer`], it will get instantiated to [`Shape`] by reusing
/// the shape system (read docs of [`ShapeSystemRegistry`] to learn more). This struct contains
/// information about the compile time depth ordering relations. See the "Compile Time Shapes
/// Ordering Relations" section in docs of [`Layer`] to learn more.
#[derive(Clone, Debug)]
pub struct ShapeSystemStaticDepthOrdering {
    above: Vec<ShapeSystemId>,
    below: Vec<ShapeSystemId>,
}

/// [`ShapeSystemStaticDepthOrdering`] associated with an id.
#[derive(Clone, Debug)]
pub struct ShapeSystemInfoTemplate<T> {
    id:       T,
    ordering: ShapeSystemStaticDepthOrdering,
}

impl<T> Deref for ShapeSystemInfoTemplate<T> {
    type Target = ShapeSystemStaticDepthOrdering;
    fn deref(&self) -> &Self::Target {
        &self.ordering
    }
}

impl<T> ShapeSystemInfoTemplate<T> {
    fn new(id: T, ordering: ShapeSystemStaticDepthOrdering) -> Self {
        Self { id, ordering }
    }
}

/// Identifies a specific flavor of a shape system.
#[derive(Debug, Copy, Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct ShapeSystemIdWithFlavor {
    id:     ShapeSystemId,
    flavor: ShapeSystemFlavor,
}



// ======================
// === Shape Ordering ===
// ======================

/// Shape ordering utility. Currently, this macro supports ordering of shapes for a given stage.
/// For example, the following usage:
///
/// ```text
/// shapes_order_dependencies! {
///     scene => {
///         output::port::single_port -> shape;
///         output::port::multi_port  -> shape;
///         shape                     -> input::port::hover;
///         input::port::hover        -> input::port::viz;
///     }
/// }
/// ```
///
/// Will expand to:
///
/// ```text
/// scene.layers.add_shapes_order_dependency::<output::port::single_port::Shape, shape::Shape>();
/// scene.layers.add_shapes_order_dependency::<output::port::multi_port::Shape, shape::Shape>();
/// scene.layers.add_shapes_order_dependency::<shape::Shape, input::port::hover::Shape>();
/// scene
///     .layers
///     .add_shapes_order_dependency::<input::port::hover::Shape, input::port::viz::Shape>();
/// ```
///
/// A shape listed on the left side of an arrow (`->`) will be ordered below the shape listed on
/// the right side of the arrow.
#[macro_export]
macro_rules! shapes_order_dependencies {
    ($scene:expr => {
        $( $p1:ident $(:: $ps1:ident)* -> $p2:ident $(:: $ps2:ident)*; )*
    }) => {$(
        $scene.layers.add_global_shapes_order_dependency::<$p1$(::$ps1)*::Shape, $p2$(::$ps2)*::Shape>();
    )*};
}



// ==================
// === ScissorBox ===
// ==================

/// A rectangular area used to limit rendering of a [`Layer`]. The area contains information about
/// rendering limits from each side of the image (left, right, top, and bottom).
#[allow(missing_docs)]
#[derive(Debug, Clone, Copy)]
pub struct ScissorBox {
    pub min_x: i32,
    pub min_y: i32,
    pub max_x: i32,
    pub max_y: i32,
}

impl ScissorBox {
    /// Constructor.
    pub fn new() -> Self {
        let min_x = 0;
        let min_y = 0;
        let max_x = i32::MAX;
        let max_y = i32::MAX;
        Self { min_x, min_y, max_x, max_y }
    }

    /// Constructor.
    pub fn new_with_position_and_size(position: Vector2<i32>, size: Vector2<i32>) -> Self {
        let min_x = position.x;
        let min_y = position.y;
        let max_x = min_x + size.x;
        let max_y = min_y + size.y;
        Self { min_x, min_y, max_x, max_y }
    }
}

impl ScissorBox {
    /// The size of the scissor box.
    pub fn size(&self) -> Vector2<i32> {
        let width = (self.max_x - self.min_x).max(0);
        let height = (self.max_y - self.min_y).max(0);
        Vector2(width, height)
    }

    /// The position of the scissor box computed from the left bottom corner.
    pub fn position(&self) -> Vector2<i32> {
        Vector2(self.min_x.max(0), self.min_y.max(0))
    }
}

impl Default for ScissorBox {
    fn default() -> Self {
        Self::new()
    }
}

impl PartialSemigroup<ScissorBox> for ScissorBox {
    fn concat_mut(&mut self, other: Self) {
        self.min_x = Ord::max(self.min_x, other.min_x);
        self.min_y = Ord::max(self.min_y, other.min_y);
        self.max_x = Ord::min(self.max_x, other.max_x);
        self.max_y = Ord::min(self.max_y, other.max_y);
    }
}

impl PartialSemigroup<&ScissorBox> for ScissorBox {
    fn concat_mut(&mut self, other: &Self) {
        self.concat_mut(*other)
    }
}

// =================
// === BlendMode ===
// =================

/// Description of color blending that is used during rendering of this layer. Specifies how shape
/// colors rendered on this layer will be combined with ones drawn before them, including on all
/// layers below them. Blend modes can be used for many visual effects, especially involving
/// different interpretation of transparency, without any additional performance cost. Blending is
/// set up on webgl context using [`blendFuncSeparate`][func], [`blendEquationSeparate`][equation],
/// and [`blendColor`][color].
///
/// By default, each layer is set up in "alpha over" blending mode (assuming premultiplied alpha in
/// shader output), which corresponds to equation [`BlendEquation::Add`], source
/// [`BlendEquation::One`] and destination [`BlendEquation::OneMinusSrcAlpha`].
///
///
/// To learn more about blending in general, see a [Learn OpenGL tutorial about blending][tutorial].
///
/// [func]: https://developer.mozilla.org/en-US/docs/Web/API/WebGLRenderingContext/blendFuncSeparate
/// [equation]: https://developer.mozilla.org/en-US/docs/Web/API/WebGLRenderingContext/blendEquationSeparate
/// [color]: https://developer.mozilla.org/en-US/docs/Web/API/WebGLRenderingContext/blendColor
/// [tutorial]: https://learnopengl.com/Advanced-OpenGL/Blending
#[derive(Debug, Clone, Copy)]
pub struct BlendMode {
    /// Blend equation used for RGB components.
    pub equation_color: BlendEquation,
    /// Blend equation used for Alpha component.
    pub equation_alpha: BlendEquation,
    /// Blend factor used for source RGB components.
    pub src_color:      BlendFactor,
    /// Blend factor used for source Alpha component.
    pub src_alpha:      BlendFactor,
    /// Blend factor used for source RGB components.
    pub dst_color:      BlendFactor,
    /// Blend factor used for source RGB components.
    pub dst_alpha:      BlendFactor,
    /// Constant color value used in [`BlendFactor::ConstantColor`] and
    /// [`BlendFactor::OneMinusConstantColor`]. It is shared by all set blend factors with either
    /// of those variants.
    pub constant:       color::Rgba,
}

/// Blend equation used for either color or alpha blending, used as an argument to
/// [`gl.blendEquationSeparate`][mdn] before rendering this layer. See [`BlendMode`] for more
/// information.
///
/// Blending is computed using following formula: `f(s * S, d * D)`, where `s` and `d` are
/// respective src and dst blend factors (see [`BlendFactor`]), and `S` and `D` are the source and
/// destination pixel values. [`BlendEquation`] selects what function `f` is used.
///
/// [mdn]: https://developer.mozilla.org/en-US/docs/Web/API/WebGLRenderingContext/blendEquationSeparate
#[derive(Debug, Clone, Copy, Default)]
#[repr(u32)]
pub enum BlendEquation {
    #[default]
    /// `O = s * S + d * D`
    Add             = *Context::FUNC_ADD,
    /// `O = s * S - d * D`
    Subtract        = *Context::FUNC_SUBTRACT,
    /// `O = s * S - d * D`
    ReverseSubtract = *Context::FUNC_REVERSE_SUBTRACT,
    /// `O = min(s * S, d * D)`
    Min             = *Context::MIN,
    /// `O = max(s * S, d * D)`
    Max             = *Context::MAX,
}

/// Blend factor for particular color component, used as an argument to
/// [`gl.blendFuncSeparate`][mdn] before rendering this layer. See [`BlendMode`] for more
/// information.
///
/// [mdn]: https://developer.mozilla.org/en-US/docs/Web/API/WebGLRendering*Context/blendFuncSepare
#[derive(Debug, Clone, Copy)]
#[repr(u32)]
pub enum BlendFactor {
    /// Always `0.0`. Will effectively remove given color component from the blend equation.
    Zero             = *Context::ZERO,
    /// Always `1.0`.
    One              = *Context::ONE,
    /// value of corresponding component in source pixel (either rgb or alpha).
    SrcColor         = *Context::SRC_COLOR,
    /// inverse of value of corresponding component in source pixel (either rgb or alpha).
    OneMinusSrcColor = *Context::ONE_MINUS_SRC_COLOR,
    /// value of corresponding component in destination pixel (either rgb or alpha).
    DstColor         = *Context::DST_COLOR,
    /// inverse of value of corresponding component in destination pixel (either rgb or alpha).
    OneMinusDstColor = *Context::ONE_MINUS_DST_COLOR,
    /// value of alpha component in source pixel
    SrcAlpha         = *Context::SRC_ALPHA,
    /// inverse of value of alpha component in source pixel
    OneMinusSrcAlpha = *Context::ONE_MINUS_SRC_ALPHA,
    /// value of alpha component in destination pixel
    DstAlpha         = *Context::DST_ALPHA,
    /// inverse of value of alpha component in destination pixel
    OneMinusDstAlpha = *Context::ONE_MINUS_DST_ALPHA,
    /// value of corresponding component in constant value (['BlendMode.constant']).
    ConstantColor    = *Context::CONSTANT_COLOR,
    /// inverse of value of corresponding component in constant value (['BlendMode.constant']).
    OneMinusConstantColor = *Context::ONE_MINUS_CONSTANT_COLOR,
}

impl BlendMode {
    /// Alpha over blending, assuming premultiplied alpha in shader output.
    pub const PREMULTIPLIED_ALPHA_OVER: BlendMode =
        BlendMode::simple(BlendEquation::Add, BlendFactor::One, BlendFactor::OneMinusSrcAlpha);

    /// Pick maximum value for each color component.
    pub const MAX: BlendMode =
        BlendMode::simple(BlendEquation::Max, BlendFactor::One, BlendFactor::One);

    /// Wherever the source alpha is non-zero, it will cut out the destination color and alpha
    /// proportionally to its value.
    pub const ALPHA_CUTOUT: BlendMode =
        BlendMode::simple(BlendEquation::Add, BlendFactor::Zero, BlendFactor::OneMinusSrcAlpha);

    /// Create a blend mode with the same settings for color and alpha channels.
    const fn simple(equation: BlendEquation, src: BlendFactor, dst: BlendFactor) -> Self {
        BlendMode {
            equation_color: equation,
            equation_alpha: equation,
            src_color:      src,
            src_alpha:      src,
            dst_color:      dst,
            dst_alpha:      dst,
            constant:       color::Rgba::transparent(),
        }
    }

    /// Apply this blend mode settings to the rendering context.
    pub fn apply_to_context(&self, context: &Context) {
        context.blend_equation_separate(self.equation_color as _, self.equation_alpha as _);
        context.blend_func_separate(
            self.src_color as _,
            self.dst_color as _,
            self.src_alpha as _,
            self.dst_alpha as _,
        );
        context.blend_color(
            self.constant.red,
            self.constant.green,
            self.constant.blue,
            self.constant.alpha,
        );
    }
}

impl Default for BlendMode {
    fn default() -> Self {
        Self::PREMULTIPLIED_ALPHA_OVER
    }
}
