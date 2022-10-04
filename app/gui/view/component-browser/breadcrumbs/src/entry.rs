//! A module defining the special [`Entry`] type for the grid view of the breadcrumbs.

use ensogl_core::display::shape::*;
use ensogl_core::prelude::*;

use component_browser_theme::searcher::list_panel::breadcrumbs as theme;
use ensogl_core::application::command::FrpNetworkProvider;
use ensogl_core::application::frp::API;
use ensogl_core::application::Application;
use ensogl_core::data::color;
use ensogl_core::display;
use ensogl_core::display::scene::Layer;
use ensogl_core::Animation;
use ensogl_grid_view::entry::Contour;
use ensogl_grid_view::entry::EntryFrp;
use ensogl_grid_view::Col;
use ensogl_hardcoded_theme::application::component_browser as component_browser_theme;
use ensogl_text as text;



// ==============
// === Shapes ===
// ==============

/// A filled triangle pointing to the right. Used to separate breadcrumbs.
pub mod separator {
    use super::*;
    use std::f32::consts::PI;

    pub const ICON_WIDTH: f32 = 16.0;

    ensogl_core::define_shape_system! {
        above = [ensogl_grid_view::entry::shape];
        pointer_events = false;
        (style: Style, color: Vector4) {
            let width = style.get_number(theme::separator::width);
            let height = style.get_number(theme::separator::height);
            let triangle = Triangle(width, height).rotate((PI/2.0).radians());
            let shape = triangle.fill(color);
            shape.into()
        }
    }
}

/// A three dots icon. Used to indicate that the last module in breadcrumbs list contains more
/// ancestors.
pub mod ellipsis {
    use super::*;

    pub const ICON_WIDTH: f32 = 32.0;

    ensogl_core::define_shape_system! {
        above = [ensogl_grid_view::entry::shape];
        pointer_events = false;
        (style: Style, alpha: f32) {
            let radius = style.get_number(theme::ellipsis::circles_radius).px();
            let gap = style.get_number(theme::ellipsis::circles_gap).px();
            let background_width = style.get_number(theme::ellipsis::background_width);
            let background_height = style.get_number(theme::ellipsis::background_height);
            let background_corners_radius = style.get_number(theme::ellipsis::background_corners_radius);
            let col = style.get_color(theme::ellipsis::circles_color);
            let circles_color = Var::<color::Rgba>::rgba(col.red,col.green,col.blue,alpha.clone());
            let bg_col = style.get_color(theme::ellipsis::background_color);
            let background_color = Var::<color::Rgba>::rgba(bg_col.red,bg_col.green,bg_col.blue,alpha);

            let tile_size = radius.clone() * 2.0 + gap;
            let circles = Circle(radius).repeat((tile_size.clone(), tile_size.clone()));
            let mask = Rect((tile_size.clone() * 3.0, tile_size));
            let circles = circles.intersection(mask).fill(circles_color);
            let background = Rect((background_width.px(), background_height.px()));
            let background = background.corners_radius(background_corners_radius.px());
            let background = background.fill(background_color);
            let shape = background + circles;
            shape.into()
        }
    }
}



// =============
// === Model ===
// =============

/// A model for the entry in the breadcrumbs list.
#[allow(missing_docs)]
#[derive(Clone, CloneRef, Debug, Default)]
pub enum Model {
    #[default]
    Ellipsis,
    Text(ImString),
    Separator,
}

#[allow(missing_docs)]
#[derive(Clone, Copy, CloneRef, Debug, Default, PartialEq)]
enum State {
    #[default]
    Ellipsis,
    Text,
    Separator,
}



// =============
// === Entry ===
// =============

// === EntryData ===

/// An internal structure of [`Entry`]. It has three visual representations: text,
/// a separator between entries, and an ellipsis. The breadcrumbs implementation selects the needed
/// representation for each entry in the grid view. For efficiency, text label and icons are
/// allocated once the entry is created.
#[allow(missing_docs)]
#[derive(Clone, Debug)]
pub struct EntryData {
    display_object: display::object::Instance,
    text:           text::Text,
    separator:      separator::View,
    ellipsis:       ellipsis::View,
    state:          Rc<Cell<State>>,
}

impl EntryData {
    fn new(app: &Application, text_layer: Option<&Layer>) -> Self {
        let display_object = display::object::Instance::new();
        let text = app.new_view::<ensogl_text::Text>();
        if let Some(layer) = text_layer {
            text.add_to_scene_layer(layer);
        }
        let ellipsis = ellipsis::View::new();
        let separator = separator::View::new();
        let state = default();
        display_object.add_child(&ellipsis);
        Self { display_object, state, text, ellipsis, separator }
    }

    fn hide_current_visual_representation(&self) {
        match self.state.get() {
            State::Text => self.text.unset_parent(),
            State::Separator => self.separator.unset_parent(),
            State::Ellipsis => self.ellipsis.unset_parent(),
        }
    }

    fn set_model(&self, model: &Model) {
        match model {
            Model::Text(content) => self.switch_to_text(content),
            Model::Separator => self.switch_to_separator(),
            Model::Ellipsis => self.switch_to_ellipsis(),
        }
    }

    fn switch_to_text(&self, content: &str) {
        self.text.set_content(content);
        if self.state.get() != State::Text {
            self.hide_current_visual_representation();
            self.display_object.add_child(&self.text);
            self.state.set(State::Text);
        }
    }

    fn switch_to_separator(&self) {
        if self.state.get() != State::Separator {
            self.hide_current_visual_representation();
            self.display_object.add_child(&self.separator);
            self.state.set(State::Separator);
        }
    }

    fn switch_to_ellipsis(&self) {
        if self.state.get() != State::Ellipsis {
            self.hide_current_visual_representation();
            self.display_object.add_child(&self.ellipsis);
            self.state.set(State::Ellipsis);
        }
    }

    fn update_layout(&self, contour: Contour, text_size: text::Size, text_offset: f32) {
        let size = contour.size;
        self.text.set_position_xy(Vector2(text_offset - size.x / 2.0, text_size.value / 2.0));
        self.separator.size.set(size);
        self.ellipsis.size.set(size);
    }

    fn set_default_color(&self, color: color::Rgba) {
        self.text.set_property_default(color);
        self.ellipsis.alpha.set(color.alpha);
        self.separator.color.set(color.into());
    }

    fn set_font(&self, font: String) {
        self.text.set_font(font);
    }

    fn set_default_text_size(&self, size: text::Size) {
        self.text.set_property_default(size);
    }

    fn is_state_change(&self, model: &Model) -> bool {
        match model {
            Model::Text(new_text) => {
                let previous_state_was_not_text = self.state.get() != State::Text;
                let previous_text = String::from(self.text.content.value());
                let text_was_different = previous_text.as_str() != new_text.as_str();
                previous_state_was_not_text || text_was_different
            }
            Model::Separator => self.state.get() != State::Separator,
            Model::Ellipsis => self.state.get() != State::Ellipsis,
        }
    }

    fn width(&self, text_offset: f32) -> f32 {
        match self.state.get() {
            State::Text => self.text.width.value() + text_offset * 2.0,
            State::Separator => separator::ICON_WIDTH,
            State::Ellipsis => ellipsis::ICON_WIDTH,
        }
    }
}

// === Params ===

/// The style parameters of Breadcrumbs' entries. See [`ensogl_grid_view::Frp::set_entries_params`].
#[allow(missing_docs)]
#[derive(Clone, Debug, Default)]
pub struct Params {
    /// The margin of the entry's [`Contour`]. The [`Contour`] specifies the size of the
    /// clickable area of the entry. If the margin is zero, the contour covers the entire entry.
    pub margin:                   f32,
    pub hover_color:              color::Rgba,
    pub font_name:                ImString,
    pub text_padding_left:        f32,
    pub text_size:                text::Size,
    pub selected_color:           color::Rgba,
    pub highlight_corners_radius: f32,
    pub greyed_out_color:         color::Rgba,
    /// The first greyed out column. All columns to the right will also be greyed out.
    pub greyed_out_start:         Option<Col>,
}

// === Entry ===

/// A Breadcrumbs entry.
#[derive(Clone, CloneRef, Debug)]
pub struct Entry {
    frp:  EntryFrp<Self>,
    data: Rc<EntryData>,
}

impl ensogl_grid_view::Entry for Entry {
    type Model = Model;
    type Params = Params;

    fn new(app: &Application, text_layer: Option<&Layer>) -> Self {
        let data = Rc::new(EntryData::new(app, text_layer));
        let frp = EntryFrp::<Self>::new();
        let input = &frp.private().input;
        let out = &frp.private().output;
        let network = frp.network();
        let color_anim = Animation::new(network);
        let appear_anim = Animation::new(network);
        fn mix(c1: &color::Rgba, c2: &color::Rgba, coefficient: &f32) -> color::Rgba {
            color::mix(*c1, *c2, *coefficient)
        }

        enso_frp::extend! { network
            init <- source_();
            size <- input.set_size.on_change();
            margin <- input.set_params.map(|p| p.margin).on_change();
            hover_color <- input.set_params.map(|p| p.hover_color).on_change();
            font <- input.set_params.map(|p| p.font_name.clone_ref()).on_change();
            text_offset <- input.set_params.map(|p| p.text_padding_left).on_change();
            text_color <- input.set_params.map(|p| p.selected_color).on_change();
            text_size <- input.set_params.map(|p| p.text_size).on_change();
            greyed_out_color <- input.set_params.map(|p| p.greyed_out_color).on_change();
            greyed_out_from <- input.set_params.map(|p| p.greyed_out_start).on_change();
            highlight_corners_radius <- input.set_params.map(|p| p.highlight_corners_radius).on_change();
            transparent_color <- init.constant(color::Rgba::transparent());

            col <- input.set_location._1();
            should_grey_out <- all_with(&col, &greyed_out_from,
                |col, from| from.map_or(false, |from| *col >= from)
            );
            color_anim.target <+ should_grey_out.map(|should| if *should { 1.0 } else { 0.0 });
            target_color <- all_with3(&text_color, &greyed_out_color, &color_anim.value, mix);
            appear_anim.target <+ init.constant(1.0);
            model_was_set <- input.set_model.map(f!((model) data.is_state_change(model))).on_true();
            should_appear <- any(&init, &model_was_set);
            eval_ should_appear({
                appear_anim.target.emit(0.0);
                appear_anim.skip.emit(());
                appear_anim.target.emit(1.0);
            });
            color <- all_with3(&transparent_color, &target_color, &appear_anim.value, mix);

            contour <- all_with(&size, &margin, |size, margin| Contour {
                size: *size - Vector2(*margin, *margin) * 2.0,
                corners_radius: 0.0,
            });
            layout <- all(contour, text_size, text_offset);
            eval layout ((&(c, ts, to)) data.update_layout(c, ts, to));
            eval color((c) data.set_default_color(*c));
            eval font((f) data.set_font(f.to_string()));
            eval text_size((s) data.set_default_text_size(*s));
            is_disabled <- input.set_model.map(|m| matches!(m, Model::Separator | Model::Ellipsis));
            width <- map2(&input.set_model, &text_offset,
                f!((model, text_offset) {
                    data.set_model(model);
                    data.width(*text_offset)
                })
            );
            out.override_column_width <+ width;

            out.disabled <+ is_disabled;
            out.contour <+ contour;
            out.highlight_contour <+ contour.map2(&highlight_corners_radius,
                |c, r| Contour { corners_radius: *r, ..*c }
            );
            out.hover_highlight_color <+ hover_color;
            out.selection_highlight_color <+ init.constant(color::Rgba::transparent());
        }
        init.emit(());
        Self { frp, data }
    }

    fn frp(&self) -> &EntryFrp<Self> {
        &self.frp
    }
}

impl display::Object for Entry {
    fn display_object(&self) -> &display::object::Instance {
        &self.data.display_object
    }
}
