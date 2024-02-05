//! UI entity that allows for the selection of a visualization. Uses the
//! `drop_down_menu::DropDownMenu` but provides convenience functionality to extract map the
//! indices of the `DropDownMenu` to `Path` values.
//!
//! TODO: If similar things are needed elsewhere, refactor this to a
//! Chooser<T:Eq+Display> (or similar) which would represent a `DropDownMenu` for specific owned
//! values. It should also be refactored to use `drop_down_menu` from `ensogl_components` instead
//! of the old list view.

use crate::prelude::*;

use crate::component::visualization;
use crate::data::enso;

use enso_frp as frp;
use enso_frp;
use ensogl::application::Application;
use ensogl::display;
use ensogl_component::drop_down_menu;
use ensogl_component::list_view;



// ===========
// === FRP ===
// ===========

ensogl::define_endpoints! {
    Input {
        set_icon_size       (Vector2),
        set_icon_padding    (Vector2),
        hide_selection_menu (),
        set_selected        (Option<visualization::Path>),
        set_menu_offset_y   (f32),
        set_vis_input_type  (Option<enso::Type>),
    }

    Output {
        menu_visible   (bool),
        menu_closed    (),
        chosen_entry   (Option<visualization::Path>),
        mouse_over     (),
        mouse_out      (),
        entries        (Rc<Vec<visualization::Path>>),
        vis_input_type (Option<enso::Type>)
    }
}



// =============
// === Model ===
// =============

#[derive(Clone, CloneRef, Debug, display::Object)]
struct Model {
    #[display_object]
    selection_menu: drop_down_menu::DropDownMenu,
    registry:       visualization::Registry,
}

impl Model {
    pub fn new(app: &Application, registry: visualization::Registry) -> Self {
        let selection_menu = drop_down_menu::DropDownMenu::new(app);
        selection_menu.set_label_alignment(drop_down_menu::Alignment::Right);
        app.display.default_scene.layers.above_nodes.add(&selection_menu);
        selection_menu.set_label_layer(&app.display.default_scene.layers.above_nodes_text);
        Self { selection_menu, registry }
    }

    pub fn entries(&self, input_type: &Option<enso::Type>) -> Vec<visualization::Path> {
        let input_type_or_any = input_type.clone().unwrap_or_else(enso::Type::any);
        let definitions_iter = self.registry.valid_sources(&input_type_or_any).into_iter();
        definitions_iter.map(|d| d.signature.path).collect_vec()
    }
}



// ============================
// === VisualizationChooser ===
// ============================

/// UI entity that shows a button that opens a list of visualizations that can be selected from.
#[allow(missing_docs)]
#[derive(Clone, CloneRef, Debug, display::Object)]
pub struct VisualizationChooser {
    pub frp: Frp,
    #[display_object]
    model:   Model,
}

impl VisualizationChooser {
    /// Constructor.
    pub fn new(app: &Application, registry: visualization::Registry) -> Self {
        let frp = Frp::new();
        let model = Model::new(app, registry);
        Self { frp, model }.init()
    }

    fn init(self) -> Self {
        let network = &self.frp.network;
        let frp = &self.frp;
        let model = &self.model;
        let menu = &self.model.selection_menu.frp;

        frp::extend! { network

            // === Input Processing ===

            eval  frp.set_icon_size ((size) menu.set_icon_size.emit(size) );
            eval  frp.set_icon_padding ((size) menu.set_icon_padding.emit(size) );
            eval_ frp.hide_selection_menu ( menu.hide_selection_menu.emit(()) );
            eval  frp.set_menu_offset_y ((offset) menu.set_menu_offset_y.emit(offset) );

            set_selected_ix <= all_with(&frp.input.set_selected, &frp.output.entries, |selected,entries|{
                let selected_ix = selected.as_ref().map(|s|
                    entries.iter().position(|item| item == s)
                );
                if selected.is_some() && selected_ix.is_none() {
                    warn!("Invalid visualization selected {selected:?} from available {entries:?}");
                };
                selected_ix
            });
            eval set_selected_ix ((ix) menu.set_selected.emit(ix));


            // === Output Processing ===

            frp.source.mouse_over   <+ menu.icon_mouse_over;
            frp.source.mouse_out    <+ menu.icon_mouse_out;
            frp.source.menu_closed  <+ menu.menu_closed;
            frp.source.menu_visible <+ menu.menu_visible;

            selected_path <- model.selection_menu.frp.chosen_entry.map2(&frp.output.entries,
                |entry_id,entries| entry_id.map(|entry_id| entries.get(entry_id).cloned()).flatten()
            );

            frp.source.chosen_entry <+ selected_path;

            eval frp.source.chosen_entry([](entry){
                if let Some(entry) = entry{
                    let event     = "graph_editor::visualization_chooser::vis_selected";
                    let name:&str = entry.name.as_ref();
                    let field     = "visualization_name";
                    let data      = analytics::AnonymousData(|| name.to_string());
                    analytics::remote_log_value(event,field,data);
                }
            });
            input_type_changed <- frp.set_vis_input_type.on_change();
            frp.source.vis_input_type <+ frp.set_vis_input_type;


            // === Showing Entries ===

            frp.source.entries <+ input_type_changed.map(f!([model] (input_type){
                let entries  = Rc::new(model.entries(input_type));
                let provider = list_view::entry::AnyModelProvider::from(entries.clone_ref());
                model.selection_menu.set_entries(provider);
                entries
            }));
        }
        self
    }
}
