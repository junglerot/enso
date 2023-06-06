//! Definitions, constructors, and management for the EnsoGL shapes that are used to draw an edge.
//!
//! The core function of this module is to translate edge layouts into the shape parameters that
//! will implement them.

use crate::prelude::*;

use super::layout::Corner;
use super::layout::EdgeSplit;
use super::layout::Oriented;
use super::layout::SplitArc;
use super::layout::TargetAttachment;
use ensogl::data::color;
use ensogl::display;
use ensogl::display::scene::Scene;
use ensogl::display::shape::*;

use std::f32::consts::FRAC_PI_2;
use std::f32::consts::PI;
use std::f32::consts::TAU;



// =================
// === Constants ===
// =================

const LINE_WIDTH: f32 = 4.0;
const HOVER_EXTENSION: f32 = 10.0;
pub(super) const HOVER_WIDTH: f32 = LINE_WIDTH + HOVER_EXTENSION;

mod arrow {
    use super::*;
    pub(super) const SIZE: Vector2 = Vector2(18.75, 18.75);
}

mod attachment {
    /// Extra length to add to the top of the target-attachment bit, to ensure that it
    /// appears to pass through the top of the node. Without this adjustment, inexact
    /// floating-point math and anti-aliasing would cause a 1-pixel gap artifact right where
    /// the attachment should meet the corner at the edge of the node.
    pub(super) const TOP_ADJUSTMENT: f32 = 0.5;
}



// ===================
// === Edge Shapes ===
// ===================

/// The shapes used to render an edge.
#[derive(Debug, Default)]
pub(super) struct Shapes {
    /// The individual [`Corner`]s making up the edge. Each is drawn in the focused or unfocused
    /// color.
    sections:          RefCell<Vec<Rectangle>>,
    /// A pair of [`arc`] shapes used when the mouse is over the rounded corner, and the edge must
    /// must be split into focused and unfocused sides at a certain angle along the arc.
    split_arc:         RefCell<Option<[arc::View; 2]>>,
    /// Wider versions of the [`sections`], for receiving mouse events.
    hover_sections:    RefCell<Vec<Rectangle>>,
    /// The end of the edge that is drawn on top of the node and connects to the target node's
    /// input port.
    target_attachment: RefCell<Option<Rectangle>>,
    /// Arrow drawn on long backward edges to indicate data flow direction.
    dataflow_arrow:    RefCell<Option<Rectangle>>,
}

impl Shapes {
    /// Redraw the arrow used to mark long backward edges.
    pub(super) fn redraw_dataflow_arrow(
        &self,
        parent: &impl ShapeParent,
        parameters: RedrawDataflowArrow,
    ) {
        let RedrawDataflowArrow { arrow, source_color, target_color, focus_split, is_attached } =
            parameters;
        let shape = self.dataflow_arrow.take();
        if let Some(arrow_center) = arrow {
            // The arrow will have the same color as the target-end of the first corner from the
            // source (this is the `arrow_center` point).
            let color = match focus_split.map(|split| split.corner_index) {
                Some(0) => target_color,
                _ => source_color,
            };
            let shape = shape.unwrap_or_else(|| parent.new_dataflow_arrow());
            shape.set_xy(arrow_center - arrow::SIZE / 2.0);
            shape.set_color(color);
            Self::set_layer(parent, is_attached, &shape);
            self.dataflow_arrow.set(shape);
        }
    }

    /// Redraw the invisible mouse-event-catching edges.
    pub(super) fn redraw_hover_sections(
        &self,
        parent: &impl ShapeParent,
        corners: &[Oriented<Corner>],
    ) {
        let hover_factory = self
            .hover_sections
            .take()
            .into_iter()
            .chain(iter::repeat_with(|| parent.new_hover_section()));
        *self.hover_sections.borrow_mut() = corners
            .iter()
            .zip(hover_factory)
            .map(|(corner, shape)| draw_corner(shape, **corner, HOVER_WIDTH))
            .collect();
    }

    /// Redraw the sections, each of which is a [`Rectangle`] implementing a [`Corner`], or multiple
    /// [`Rectangle`]s and multiple [`arc::View`]s, if it is a split [`Corner`].
    pub(super) fn redraw_sections(&self, parent: &impl ShapeParent, parameters: RedrawSections) {
        let RedrawSections { corners, source_color, target_color, focus_split, is_attached } =
            parameters;
        let corner_index =
            focus_split.map(|split| split.corner_index).unwrap_or_else(|| corners.len());
        let split_corner = focus_split.map(|split| split.split_corner);
        let mut section_factory =
            self.sections.take().into_iter().chain(iter::repeat_with(|| parent.new_section()));
        let mut new_sections = self.redraw_complete_sections(
            &mut section_factory,
            corners,
            corner_index,
            source_color,
            target_color,
        );
        for shape in &new_sections {
            Self::set_layer(parent, is_attached, shape);
        }
        let arc_shapes = self.split_arc.take();
        if let Some(split_corner) = split_corner {
            let source_side = rectangle_geometry(*split_corner.source_end, LINE_WIDTH);
            let target_side = rectangle_geometry(*split_corner.target_end, LINE_WIDTH);
            let split_arc = split_corner.split_arc;
            if let Some(split_arc) = split_arc {
                let arc_shapes = arc_shapes.unwrap_or_else(|| [parent.new_arc(), parent.new_arc()]);
                let arc_shapes = draw_split_arc(arc_shapes, split_arc);
                arc_shapes[0].color.set(source_color.into());
                arc_shapes[1].color.set(target_color.into());
                self.split_arc.set(arc_shapes);
            }
            let (source_shape, target_shape) =
                (section_factory.next().unwrap(), section_factory.next().unwrap());
            source_shape.set_border_color(source_color);
            target_shape.set_border_color(target_color);
            new_sections.push(draw_geometry(source_shape, source_side));
            new_sections.push(draw_geometry(target_shape, target_side));
        }
        *self.sections.borrow_mut() = new_sections;
    }

    /// Redraw the sections that aren't split by the focus position.
    pub(super) fn redraw_complete_sections(
        &self,
        section_factory: impl Iterator<Item = Rectangle>,
        corners: &[Oriented<Corner>],
        corner_index: usize,
        source_color: color::Rgba,
        target_color: color::Rgba,
    ) -> Vec<Rectangle> {
        corners
            .iter()
            .enumerate()
            .filter_map(|(i, corner)| {
                if i == corner_index {
                    None
                } else {
                    let color = match i < corner_index {
                        true => source_color,
                        false => target_color,
                    };
                    Some((color, corner))
                }
            })
            .zip(section_factory)
            .map(|((color, corner), shape)| {
                let shape = draw_corner(shape, **corner, LINE_WIDTH);
                shape.set_border_color(color);
                shape
            })
            .collect()
    }

    /// Redraw the little bit that goes on top of the target node.
    pub(super) fn redraw_target_attachment(
        &self,
        parent: &impl ShapeParent,
        target_attachment: Option<TargetAttachment>,
        color: color::Rgba,
    ) {
        let shape = self.target_attachment.take();
        if let Some(TargetAttachment { target, length }) = target_attachment
                && length > f32::EPSILON {
            let shape = shape.unwrap_or_else(|| parent.new_target_attachment());
            shape.set_size_y(length + attachment::TOP_ADJUSTMENT);
            shape.set_xy(target + Vector2(-LINE_WIDTH / 2.0, attachment::TOP_ADJUSTMENT));
            shape.set_color(color);
            self.target_attachment.set(shape);
        }
    }

    /// Add the given shape to the appropriate layer depending on whether it is attached.
    fn set_layer(parent: &impl ShapeParent, is_attached: bool, shape: &Rectangle) {
        (match is_attached {
            true => &parent.scene().layers.main_edges_level,
            false => &parent.scene().layers.main_above_inactive_nodes_level,
        })
        .add(shape)
    }
}


// === Redraw parameters ====

/// Arguments passed to [`Shapes::redraw_sections`].
pub(super) struct RedrawSections<'a> {
    /// The corners to be redrawn.
    pub(super) corners:      &'a [Oriented<Corner>],
    /// The color to use for the part of the edge closer to the source.
    pub(super) source_color: color::Rgba,
    /// The color to use for the part of the edge closer to the target.
    pub(super) target_color: color::Rgba,
    /// Where the edge should be split into differently-colored source and target parts.
    pub(super) focus_split:  Option<EdgeSplit>,
    /// Whether the edge is fully-attached.
    pub(super) is_attached:  bool,
}

/// Arguments passed to [`Shapes::redraw_dataflow_arrow`].
pub(super) struct RedrawDataflowArrow {
    /// The center of the arrow, if the arrow should be drawn.
    pub(super) arrow:        Option<Vector2>,
    /// The color to use for the part of the edge closer to the source.
    pub(super) source_color: color::Rgba,
    /// The color to use for the part of the edge closer to the target.
    pub(super) target_color: color::Rgba,
    /// Where the edge should be split into differently-colored source and target parts.
    pub(super) focus_split:  Option<EdgeSplit>,
    /// Whether the edge is fully-attached.
    pub(super) is_attached:  bool,
}



// =========================
// === Shape Definitions ===
// =========================

/// An arc around the origin. `outer_radius` determines the distance from the origin to the outer
/// edge of the arc, `stroke_width` the width of the arc. The arc starts at `start_angle`, relative
/// to the origin. Its radial size is `sector_angle`. The ends are flat, not rounded as in
/// [`RoundedArc`].
mod arc {
    use super::*;
    ensogl::shape! {
        pointer_events = false;
        (
            style: Style,
            color: Vector4,
            outer_radius: f32,
            stroke_width: f32,
            start_angle: f32,
            sector_angle: f32,
        ) {
            let circle = Circle(outer_radius.px()) - Circle((outer_radius - stroke_width).px());
            let angle_adjust = Var::<f32>::from(FRAC_PI_2);
            let rotate_angle = -start_angle + angle_adjust - &sector_angle / 2.0;
            let angle = PlaneAngleFast(sector_angle).rotate(rotate_angle);
            let angle = angle.grow(0.5.px());
            let shape = circle * angle;
            let shape = shape.fill(color);
            shape.into()
        }
    }
}



// ======================
// === Shape Creation ===
// ======================

pub(super) trait ShapeParent: display::Object {
    fn scene(&self) -> &Scene;

    /// Create a shape object to render one of the [`Corner`]s making up the edge.
    fn new_section(&self) -> Rectangle {
        let new = Rectangle::new();
        new.set_corner_radius_max();
        new.set_inset_border(LINE_WIDTH);
        new.set_color(color::Rgba::transparent());
        new.set_pointer_events(false);
        self.display_object().add_child(&new);
        new
    }

    /// Create a shape object to render the invisible hover area corresponding to one of the
    /// [`Corner`]s making up the edge.
    fn new_hover_section(&self) -> Rectangle {
        let new = Rectangle::new();
        new.set_corner_radius_max();
        new.set_inset_border(HOVER_WIDTH);
        new.set_color(color::Rgba::transparent());
        new.set_border_color(INVISIBLE_HOVER_COLOR);
        self.display_object().add_child(&new);
        new
    }

    /// Create a shape object to render an arbitrary-angle arc. This is used when the focus is split
    /// in the rounded part of a [`Corner`].
    fn new_arc(&self) -> arc::View {
        let arc = arc::View::new();
        arc.stroke_width.set(LINE_WIDTH);
        self.display_object().add_child(&arc);
        self.scene().layers.below_main.add(&arc);
        arc
    }

    /// Create a shape object to render the little bit at the target end of the edge, that draws on
    /// top of the node.
    fn new_target_attachment(&self) -> Rectangle {
        let new = Rectangle::new();
        new.set_size_x(LINE_WIDTH);
        new.set_border_color(color::Rgba::transparent());
        new.set_pointer_events(false);
        self.display_object().add_child(&new);
        self.scene().layers.main_above_all_nodes_level.add(&new);
        new
    }

    /// Create a shape object to render the arrow that is drawn on long backward edges to show the
    /// direction of data flow.
    fn new_dataflow_arrow(&self) -> Rectangle {
        let new = SimpleTriangle::from_size(arrow::SIZE);
        new.set_pointer_events(false);
        self.display_object().add_child(&new);
        new.into()
    }
}



// =========================
// === Rendering Corners ===
// =========================

/// Set the given [`Rectangle`]'s geometry to draw this corner shape.
///
/// Note that the shape's `inset` and `border` should be the same value as the provided
/// [`line_width`]. They are not set here as an optimization: When shapes are reused, the value does
/// not need to be set again, reducing needed GPU uploads.
pub(super) fn draw_corner(shape: Rectangle, corner: Corner, line_width: f32) -> Rectangle {
    draw_geometry(shape, rectangle_geometry(corner, line_width))
}

fn draw_geometry(shape: Rectangle, geometry: RectangleGeometry) -> Rectangle {
    shape.set_clip(geometry.clip);
    shape.set_size(geometry.size);
    shape.set_xy(geometry.xy);
    shape.set_corner_radius(geometry.radius);
    shape
}


// === Rectangle Geometry ===

#[derive(Debug, Copy, Clone, Default)]
struct RectangleGeometry {
    pub clip:   Vector2,
    pub size:   Vector2,
    pub xy:     Vector2,
    pub radius: f32,
}

/// Return [`Rectangle`] geometry parameters to draw this corner shape.
fn rectangle_geometry(corner: Corner, line_width: f32) -> RectangleGeometry {
    // Convert from a layout radius (in the center of the line) to a [`Rectangle`] radius (on the
    // inside edge of the border).
    let radius = max(corner.max_radius() - line_width / 2.0, 0.0);
    RectangleGeometry {
        clip: corner.clip(),
        size: corner.size(line_width),
        xy: corner.origin(line_width),
        radius,
    }
}



// ==============================
// === Rendering Partial Arcs ===
// ==============================

/// Apply the specified arc-splitting parameters to the given arc shapes.
pub(super) fn draw_split_arc(arc_shapes: [arc::View; 2], split_arc: SplitArc) -> [arc::View; 2] {
    let outer_radius = split_arc.radius + LINE_WIDTH / 2.0;
    let arc_box = Vector2(outer_radius * 2.0, outer_radius * 2.0);
    let arc_offset = Vector2(-outer_radius, -outer_radius);
    let geometry = ArcGeometry::bisection(
        split_arc.source_end_angle,
        split_arc.split_angle,
        split_arc.target_end_angle,
    );
    for (shape, geometry) in arc_shapes.iter().zip(&geometry) {
        shape.set_xy(split_arc.origin + arc_offset);
        shape.set_size(arc_box);
        shape.outer_radius.set(outer_radius);
        shape.start_angle.set(geometry.start);
        shape.sector_angle.set(geometry.sector);
    }
    arc_shapes
}


// === Arc geometry ===

#[derive(Debug, Copy, Clone, PartialEq)]
struct ArcGeometry {
    start:  f32,
    sector: f32,
}

impl ArcGeometry {
    fn bisection(a: f32, b: f32, c: f32) -> [Self; 2] {
        [Self::new_minor(a, b), Self::new_minor(b, c)]
    }

    fn new_minor(a: f32, b: f32) -> Self {
        let start = minor_arc_start(a, b);
        let sector = minor_arc_sector(a, b);
        Self { start, sector }
    }
}

fn minor_arc_start(a: f32, b: f32) -> f32 {
    let a = a.rem_euclid(TAU);
    let b = b.rem_euclid(TAU);
    let wrapped = (a - b).abs() >= PI;
    if wrapped {
        if a < f32::EPSILON {
            b
        } else {
            a
        }
    } else {
        min(a, b)
    }
}

fn minor_arc_sector(a: f32, b: f32) -> f32 {
    let a = a.abs();
    let b = b.abs();
    let ab = (a - b).abs();
    min(ab, TAU - ab)
}
