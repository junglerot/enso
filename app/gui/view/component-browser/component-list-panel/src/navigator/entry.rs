//! A module containing the grid view entry type which represents an arbitrary icon.

use ensogl_core::prelude::*;

use crate::grid::entry::icon;

use enso_frp as frp;
use ensogl_core::application::command::FrpNetworkProvider;
use ensogl_core::application::frp::API;
use ensogl_core::application::Application;
use ensogl_core::data::color;
use ensogl_core::display;
use ensogl_core::display::scene::Layer;
use ensogl_grid_view as grid;
use ide_view_component_list_panel_icons::Any as AnyIcon;
use ide_view_component_list_panel_icons::SIZE;



// =============
// === Model ===
// =============

/// Entry's model.
#[derive(Debug, Clone, Copy, Default)]
pub struct Model {
    icon_id:  icon::Id,
    /// Color of the icon when the entry is selected.
    active:   color::Lcha,
    /// Color of the icon when the entry is not selected.
    inactive: color::Lcha,
}

impl Model {
    /// Constructor.
    pub const fn new(icon_id: icon::Id, active: color::Lcha, inactive: color::Lcha) -> Self {
        Self { icon_id, active, inactive }
    }
}



// ==================
// === IconParams ===
// ==================

/// Entry parameters of the icon.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
#[allow(missing_docs)]
pub struct Params {
    pub hover_color:              color::Lcha,
    pub selection_color:          color::Lcha,
    pub selection_size:           f32,
    pub selection_corners_radius: f32,
}



// ============
// === Data ===
// ============

/// Grid view entry type which represents a single icon. We use grid view with icons instead of
/// multiple buttons to simplify the implementation.
#[derive(Debug, Clone, CloneRef)]
pub struct Data {
    display_object: display::object::Instance,
    icon:           Rc<RefCell<Option<AnyIcon>>>,
    color:          Rc<Cell<color::Lcha>>,
}

impl Data {
    pub fn new() -> Self {
        let display_object = display::object::Instance::new();
        let icon = default();
        let color = default();
        Self { display_object, icon, color }
    }

    fn set_icon(&self, icon_id: icon::Id) {
        let size = Vector2(SIZE, SIZE);
        let icon = icon_id.create_shape(size);
        icon.set_color(color::Rgba::from(self.color.get()).into());
        self.display_object.add_child(&icon);
        *self.icon.borrow_mut() = Some(icon);
    }

    fn set_color(&self, color: color::Lcha) {
        self.color.set(color);
        if let Some(icon) = self.icon.borrow().deref() {
            icon.set_color(color::Rgba::from(color).into());
        }
    }
}



// ============
// === View ===
// ============

/// The grid view entry.
#[derive(Clone, CloneRef, Debug)]
pub struct View {
    frp:  grid::entry::EntryFrp<Self>,
    data: Data,
}

impl grid::entry::Entry for View {
    type Model = Model;
    type Params = Params;

    fn new(_app: &Application, _text_layer: Option<&Layer>) -> Self {
        let frp = grid::entry::EntryFrp::<Self>::new();
        let data = Data::new();
        let network = frp.network();
        let input = &frp.private().input;
        let out = &frp.private().output;

        let color_anim = color::Animation::new(network);

        frp::extend! { network
            init <- source_();

            icon <- input.set_model.map(|m| m.icon_id);
            eval icon((icon) data.set_icon(*icon));

            active_color <- input.set_model.map(|m| m.active);
            inactive_color <- input.set_model.map(|m| m.inactive);

            color_anim.target <+ inactive_color;

            entry_selected <- input.set_selected.on_true();
            entry_deselected <- input.set_selected.on_false();
            color_anim.target <+ active_color.sample(&entry_selected);
            color_anim.target <+ inactive_color.sample(&entry_deselected);

            eval color_anim.value((color) data.set_color(*color));

            style <- input.set_params.on_change();
            selection_color <- style.map(|s| s.selection_color).on_change();
            hover_color <- style.map(|s| s.hover_color).on_change();

            out.contour <+ input.set_size.map(|s| grid::entry::Contour::rectangular(*s));
            out.highlight_contour <+ out.contour.all_with(&style,
                |c,s| grid::entry::Contour { corners_radius: s.selection_corners_radius, ..*c }
            );
            out.hover_highlight_color <+ hover_color;
            out.selection_highlight_color <+ selection_color;
        }
        init.emit(());

        Self { frp, data }
    }

    fn frp(&self) -> &grid::entry::EntryFrp<Self> {
        &self.frp
    }
}

impl display::Object for View {
    fn display_object(&self) -> &display::object::Instance {
        &self.data.display_object
    }
}
