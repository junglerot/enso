//! A module defining the drop-down specific grid-view [`Entry`].

use ensogl_core::display::shape::*;
use ensogl_grid_view::prelude::*;

use ensogl_core::application::command::FrpNetworkProvider;
use ensogl_core::application::frp::API;
use ensogl_core::application::Application;
use ensogl_core::data::color;
use ensogl_core::display;
use ensogl_core::display::scene::Layer;
use ensogl_grid_view::entry;
use ensogl_grid_view::entry::EntryFrp;
use ensogl_text as text;



// ===================
// === EntryParams ===
// ===================

/// The parameters of [`Dropdown`]` grid entries.
#[allow(missing_docs)]
#[derive(Clone, Debug)]
pub struct EntryParams {
    pub focus_color:         color::Lcha,
    pub font:                ImString,
    pub text_offset:         f32,
    pub text_size:           text::Size,
    pub text_color:          color::Lcha,
    pub selected_text_color: color::Lcha,
    pub corners_radius:      f32,
    pub min_width:           f32,
    pub max_width:           f32,
}

impl Default for EntryParams {
    fn default() -> Self {
        Self {
            focus_color:         color::Lcha::from(color::Rgba(1.0, 1.0, 1.0, 0.2)),
            font:                text::font::DEFAULT_FONT.into(),
            text_offset:         7.0,
            text_size:           text::Size(12.0),
            text_color:          color::Lcha::from(color::Rgba(1.0, 1.0, 1.0, 0.7)),
            selected_text_color: color::Lcha::from(color::Rgba(1.0, 1.0, 1.0, 1.0)),
            corners_radius:      0.0,
            min_width:           40.0,
            max_width:           160.0,
        }
    }
}



// ==================
// === EntryModel ===
// ==================

/// The model of [`SimpleGridView`]`s entries.
#[allow(missing_docs)]
#[derive(Clone, CloneRef, Debug, Default)]
pub struct EntryModel {
    pub text:     ImString,
    pub selected: Immutable<bool>,
}

impl EntryModel {
    /// Create a new entry model with given text contents.
    pub fn new(text: ImString, selected: bool) -> Self {
        Self { text, selected: Immutable(selected) }
    }
}



// =============
// === Entry ===
// =============

// === EntryData ===

/// An internal structure of [`Entry`], which may be passed to FRP network.
#[allow(missing_docs)]
#[derive(Clone, Debug, display::Object)]
pub struct EntryData {
    display_object: display::object::Instance,
    label_thin:     text::Text,
    label_bold:     text::Text,
    selected:       Cell<bool>,
    /// A text change to the currently-hidden label that has not yet been applied.
    deferred_label: RefCell<Option<ImString>>,
}

impl EntryData {
    fn new(app: &Application, text_layer: Option<&Layer>) -> Self {
        let display_object = display::object::Instance::new();
        let label_thin = app.new_view::<ensogl_text::Text>();
        let label_bold = app.new_view::<ensogl_text::Text>();
        label_thin.set_long_text_truncation_mode(true);
        label_bold.set_long_text_truncation_mode(true);
        label_bold.set_property_default(text::Weight::Bold);
        display_object.add_child(&label_thin);
        if let Some(layer) = text_layer {
            layer.add(&label_thin);
            layer.add(&label_bold);
        }
        let selected = default();
        let deferred_label = default();
        Self { display_object, label_thin, label_bold, selected, deferred_label }
    }

    fn update_selected(&self, selected: bool) {
        let old = self.selected_label();
        let was_selected = self.selected.replace(selected);
        if selected != was_selected {
            let new = self.selected_label();
            if let Some(label) = self.deferred_label.take() {
                new.set_content(label);
            }
            self.display_object.remove_child(old);
            self.display_object.add_child(new);
        }
    }

    /// Render the currently-enabled text control.
    fn selected_label(&self) -> &text::Text {
        match self.selected.get() {
            true => &self.label_bold,
            false => &self.label_thin,
        }
    }

    fn update_layout(&self, contour: entry::Contour, text_size: text::Size, text_offset: f32) {
        let label_pos = Vector2(text_offset - contour.size.x / 2.0, text_size.value / 2.0);
        self.label_thin.set_xy(label_pos);
        self.label_bold.set_xy(label_pos);
    }

    fn set_content(&self, text: &ImString) {
        self.selected_label().set_content(text.clone_ref());
        self.deferred_label.replace(Some(text.clone_ref()));
    }
}


// === Entry ===

/// A [`SimpleGridView`] entry - a label with background.
#[derive(Clone, CloneRef, Debug, display::Object)]
pub struct Entry {
    frp:  EntryFrp<Self>,
    #[display_object]
    data: Rc<EntryData>,
}

impl ensogl_grid_view::Entry for Entry {
    type Model = EntryModel;
    type Params = EntryParams;

    #[profile(Debug)]
    fn new(app: &Application, text_layer: Option<&Layer>) -> Self {
        let data = Rc::new(EntryData::new(app, text_layer));
        let frp = EntryFrp::<Self>::new();
        let input = &frp.private().input;
        let out = &frp.private().output;
        let network = frp.network();

        out.hover_highlight_color.emit(color::Lcha::transparent());

        enso_frp::extend! { network
            size <- input.set_size.on_change();
            font <- input.set_params.map(|p| p.font.clone_ref()).on_change();
            text_offset <- input.set_params.map(|p| p.text_offset).on_change();
            focus_color <- input.set_params.map(|p| p.focus_color).on_change();
            text_color <- input.set_params.map(|p| p.text_color).on_change();
            text_size <- input.set_params.map(|p| p.text_size).on_change();
            corners_radius <- input.set_params.map(|p| p.corners_radius).on_change();
            selected_text_color <- input.set_params.map(|p| p.selected_text_color).on_change();
            max_width <- input.set_params.map(|p| p.max_width).on_change();

            contour <- all_with(&size, &corners_radius, |&size, &corners_radius|
                entry::Contour { size, corners_radius }
            );
            layout <- all(contour, text_size, text_offset);
            eval layout ((&(c, ts, to)) data.update_layout(c, ts, to));

            text_size <- text_size.ref_into_some();
            data.label_thin.set_property_default <+ text_size;
            data.label_bold.set_property_default <+ text_size;
            data.label_thin.set_property_default <+ text_color.ref_into_some();
            data.label_bold.set_property_default <+ selected_text_color.ref_into_some();
            data.label_thin.set_font <+ font;
            data.label_bold.set_font <+ font;

            bold_width <- data.label_bold.width.map2(&text_offset, |w, offset| w + offset);
            thin_width <- data.label_thin.width.map2(&text_offset, |w, offset| w + offset);
            widths <- all(bold_width, thin_width);
            desired_entry_width <- widths.map(|&(b, t)| b.max(t)).on_change();
            limited_entry_width <- desired_entry_width.map2(&input.set_params, |width, params| {
                // Using min/max to avoid a panic in clamp when min_width > max_width. In those
                // cases, the max value is returned instead.
                #[allow(clippy::manual_clamp)]
                width.max(params.min_width).min(params.max_width)
            });
            out.minimum_column_width <+ limited_entry_width;

            view_width <- max_width.map2(&text_offset, |width, offset| Some(width - offset));
            data.label_thin.set_view_width <+ view_width;
            data.label_bold.set_view_width <+ view_width;

            eval input.set_model ((m) {
                data.update_selected(*m.selected);
                data.set_content(&m.text);
            });

            out.contour <+ contour;
            out.highlight_contour <+ contour;
            out.selection_highlight_color <+ focus_color;
        }
        Self { frp, data }
    }

    fn frp(&self) -> &EntryFrp<Self> {
        &self.frp
    }
}
