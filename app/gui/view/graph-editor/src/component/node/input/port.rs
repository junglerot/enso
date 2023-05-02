//! Definition of all hardcoded node widget variants and common widget FRP API.

use crate::prelude::*;

use crate::component::node::input::widget::ConfigContext;
use crate::component::node::input::widget::DynConfig;
use crate::component::node::input::widget::DynWidget;
use crate::component::node::input::widget::EdgeData;
use crate::component::node::input::widget::SpanWidget;
use crate::component::node::input::widget::WidgetsFrp;

use enso_frp as frp;
use ensogl::application::Application;
use ensogl::control::io::mouse;
use ensogl::data::color;
use ensogl::display;
use ensogl::display::scene::layer::LayerSymbolPartition;
use ensogl::display::shape;



// =================
// === Constants ===
// =================

/// The horizontal padding of ports. It affects how the port shape should extend the target text
/// boundary on both sides.
pub const PORT_PADDING_X: f32 = 4.0;

/// The horizontal padding of port hover areas. It affects how the port hover should extend the
/// target text boundary on both sides.
pub const HOVER_PADDING_X: f32 = 2.0;

/// The minimum size of the port visual area.
pub const BASE_PORT_HEIGHT: f32 = 18.0;

/// The vertical hover padding of ports at low depth. It affects how the port hover should extend
/// the target text boundary on both sides.
pub const PRIMARY_PORT_HOVER_PADDING_Y: f32 = (crate::node::HEIGHT - BASE_PORT_HEIGHT) / 2.0;



// ===========================
// === Shapes / PortLayers ===
// ===========================

type PortShape = shape::compound::rectangle::Rectangle;
type PortShapeView = shape::compound::rectangle::shape::Shape;

/// Shape used for handling mouse events in the port, such as hovering or dropping an edge.
pub type HoverShape = shape::compound::rectangle::Rectangle;
type HoverShapeView = shape::compound::rectangle::shape::Shape;

/// An scene extension that maintains layer partitions for port shapes. It is shared by all ports in
/// the scene. The port selection and hover shapes are partitioned by span tree depth, so that the
/// ports deeper in the tree will always be displayed on top. For hover layers, that gives them
/// priority to receive mouse events.
#[derive(Clone, CloneRef)]
struct PortLayers {
    port_layer:  display::scene::Layer,
    hover_layer: display::scene::Layer,
    partitions: Rc<
        RefCell<Vec<(LayerSymbolPartition<PortShapeView>, LayerSymbolPartition<HoverShapeView>)>>,
    >,
}

impl display::scene::Extension for PortLayers {
    fn init(scene: &display::Scene) -> Self {
        let port_layer = scene.layers.port.clone_ref();
        let hover_layer = scene.layers.port_hover.clone_ref();
        Self { port_layer, hover_layer, partitions: default() }
    }
}

impl PortLayers {
    /// Add a display object to the partition at given depth, effectively setting its display order.
    /// If the partition does not exist yet, it will be created.
    fn add_to_partition(
        &self,
        port: &display::object::Instance,
        hover: &display::object::Instance,
        depth: usize,
    ) {
        let mut partitions = self.partitions.borrow_mut();
        if partitions.len() <= depth {
            partitions.resize_with(depth + 1, || {
                (
                    self.port_layer.create_symbol_partition("input port"),
                    self.hover_layer.create_symbol_partition("input port hover"),
                )
            })
        }
        let (port_partition, hover_partition) = &partitions[depth];
        port_partition.add(port);
        hover_partition.add(hover);
    }
}



// ============
// === Port ===
// ============

/// Node of a widget tree that can be a source of an edge. Displays a visual representation of the
/// connection below the widget, and handles mouse hover and click events when an edge is dragged.
#[derive(Debug)]
pub struct Port {
    /// Drop source must be kept at the top of the struct, so it will be dropped first.
    _on_cleanup:     frp::DropSource,
    crumbs:          Rc<RefCell<span_tree::Crumbs>>,
    port_root:       display::object::Instance,
    widget_root:     display::object::Instance,
    widget:          DynWidget,
    port_shape:      PortShape,
    hover_shape:     HoverShape,
    /// Last set tree depth of the port. Allows skipping layout update when the depth has not
    /// changed during reconfiguration.
    current_depth:   usize,
    /// Whether or not the port was configured as primary. Allows skipping layout update when the
    /// hierarchy level has not changed significantly during reconfiguration.
    current_primary: bool,
}

impl Port {
    /// Create a new port for given widget. The widget will be placed as a child of the port's root
    /// display object, and its layout size will be used to determine the port's size.
    pub fn new(widget: DynWidget, app: &Application, frp: &WidgetsFrp) -> Self {
        let port_root = display::object::Instance::new_named("Port");
        let widget_root = widget.root_object().clone_ref();
        let port_shape = PortShape::new();
        let hover_shape = HoverShape::new();
        port_shape.set_corner_radius_max().set_pointer_events(false);
        hover_shape.set_pointer_events(true).set_color(shape::INVISIBLE_HOVER_COLOR);

        port_root.add_child(&widget_root);
        widget_root.set_margin_left(0.0);
        port_shape
            .set_size_y(BASE_PORT_HEIGHT)
            .allow_grow()
            .set_margin_left(-PORT_PADDING_X)
            .set_margin_right(-PORT_PADDING_X)
            .set_alignment_left_center();
        hover_shape
            .set_size_y(BASE_PORT_HEIGHT)
            .allow_grow()
            .set_margin_left(-HOVER_PADDING_X)
            .set_margin_right(-HOVER_PADDING_X)
            .set_alignment_left_center();

        let layers = app.display.default_scene.extension::<PortLayers>();
        layers.add_to_partition(port_shape.display_object(), hover_shape.display_object(), 0);

        let mouse_enter = hover_shape.on_event::<mouse::Enter>();
        let mouse_leave = hover_shape.on_event::<mouse::Leave>();
        let mouse_down = hover_shape.on_event::<mouse::Down>();

        let crumbs: Rc<RefCell<span_tree::Crumbs>> = default();

        if frp.set_ports_visible.value() {
            port_root.add_child(&hover_shape);
        }

        let port_root_weak = port_root.downgrade();
        let network = &port_root.network;

        frp::extend! { network
            on_cleanup <- on_drop();
            hovering <- bool(&mouse_leave, &mouse_enter);
            cleanup_hovering <- on_cleanup.constant(false);
            hovering <- any(&hovering, &cleanup_hovering);
            hovering <- hovering.on_change();

            frp.on_port_hover <+ hovering.map(
                f!([crumbs](t) Switch::new(crumbs.borrow().clone(), *t))
            );

            frp.on_port_press <+ mouse_down.map(f_!(crumbs.borrow().clone()));
            eval frp.set_ports_visible([port_root_weak, hover_shape] (active) {
                if let Some(port_root) = port_root_weak.upgrade() {
                    if *active {
                        port_root.add_child(&hover_shape);
                    } else {
                        port_root.remove_child(&hover_shape);
                    }
                }
            });

            // Port shape is only connected to the display hierarchy when the port is connected.
            // Thus the `on_transformed` event is automatically disabled when the port is not
            // connected.
            let shape_display_object = port_shape.display_object();
            frp.connected_port_updated <+ shape_display_object.on_transformed;
        };

        Self {
            _on_cleanup: on_cleanup,
            port_shape,
            hover_shape,
            widget,
            widget_root,
            port_root,
            crumbs,
            current_primary: false,
            current_depth: 0,
        }
    }

    /// Configure the port and its attached widget. If the widget has changed its root object after
    /// reconfiguration, the port display object hierarchy will be updated to use it.
    ///
    /// See [`crate::component::node::input::widget`] module for more information about widget
    /// lifecycle.
    pub fn configure(&mut self, config: &DynConfig, ctx: ConfigContext) {
        self.crumbs.replace(ctx.span_node.crumbs.clone());
        self.set_connected(ctx.info.connection);
        self.set_port_layout(&ctx);
        self.widget.configure(config, ctx);
        self.update_root();
    }

    /// Update connection status of this port. Changing the connection status will add or remove the
    /// port's visible shape from the display hierarchy.
    fn set_connected(&self, status: Option<EdgeData>) {
        match status {
            Some(data) => {
                self.port_root.add_child(&self.port_shape);
                self.port_shape.color.set(color::Rgba::from(data.color).into())
            }
            None => {
                self.port_root.remove_child(&self.port_shape);
            }
        };
    }

    fn update_root(&mut self) {
        let new_root = self.widget.root_object();
        if new_root != &self.widget_root {
            self.port_root.remove_child(&self.widget_root);
            self.port_root.add_child(new_root);
            self.widget_root = new_root.clone_ref();
            self.widget_root.set_margin_left(0.0);
        }
    }

    fn set_port_layout(&mut self, ctx: &ConfigContext) {
        let node_depth = ctx.span_node.crumbs.len();
        if self.current_depth != node_depth {
            self.current_depth = node_depth;
            let layers = ctx.app().display.default_scene.extension::<PortLayers>();
            let port_shape = self.port_shape.display_object();
            let hover_shape = self.hover_shape.display_object();
            layers.add_to_partition(port_shape, hover_shape, node_depth);
        }

        let is_primary = ctx.info.nesting_level.is_primary();
        if self.current_primary != is_primary {
            self.current_primary = is_primary;
            let margin = if is_primary { PRIMARY_PORT_HOVER_PADDING_Y } else { 0.0 };
            self.hover_shape.set_size_y(BASE_PORT_HEIGHT + 2.0 * margin);
            self.hover_shape.set_margin_top(-margin);
            self.hover_shape.set_margin_bottom(-margin);
        }
    }

    /// Extract the widget out of the port, dropping the port specific display objects. The widget
    /// can be reinserted into the display hierarchy of widget tree.
    pub(super) fn into_widget(self) -> DynWidget {
        self.widget
    }

    /// Get a reference to a widget currently wrapped by the port. The widget may change during
    /// the next tree rebuild.
    pub(super) fn widget(&self) -> &DynWidget {
        &self.widget
    }

    /// Get a mutable reference to a widget currently wrapped by the port. The widget may change
    /// during the next tree rebuild.
    pub(super) fn widget_mut(&mut self) -> &mut DynWidget {
        &mut self.widget
    }

    /// Get the port's hover shape. Used for testing to simulate mouse events.
    pub fn hover_shape(&self) -> &HoverShape {
        &self.hover_shape
    }
}

impl display::Object for Port {
    fn display_object(&self) -> &display::object::Instance {
        self.port_root.display_object()
    }
}
