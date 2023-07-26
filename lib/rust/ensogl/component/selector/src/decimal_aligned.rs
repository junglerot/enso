//! Utility wrapper for a text field representing a float. Centers the string representation of the
//! float on the decimal separator. This is a very bare bones implementation, thus not exposed as
//! a public utility.

use crate::prelude::*;

use enso_frp as frp;
use ensogl_core::application::Application;
use ensogl_core::display;
use ensogl_core::display::object::ObjectOps;
use ensogl_text as text;



// =================
// === Constants ===
// =================

const LABEL_OFFSET: f32 = 13.0;



// ============
// ===  FRP ===
// ============

ensogl_core::define_endpoints! {
    Input {
        set_content(f32),
    }
    Output {}
}



// ==============
// ===  Model ===
// ==============

#[derive(Clone, CloneRef, Debug, display::Object)]
pub struct Model {
    /// Root object. Required as the rendered text label will have an offset relative to the
    /// base position of the root, depending on the position of the decimal separator.
    display_object: display::object::Instance,
    /// Label containing the text to display. This is the label that will be shown.
    label_full:     text::Text,
    /// This label contains the text to the left of the decimal. This is here, so we can get
    /// information about the text width of this portion of the label. This label will
    /// not appear in the UI.
    label_left:     text::Text,
}

impl Model {
    fn new(app: &Application) -> Self {
        let display_object = display::object::Instance::new();
        let label_full = app.new_view::<text::Text>();
        let label_left = app.new_view::<text::Text>();

        app.display.default_scene.layers.main.remove(&label_full);
        label_full.add_to_scene_layer(&app.display.default_scene.layers.label);

        display_object.add_child(&label_full);
        display_object.add_child(&label_left);

        Self { display_object, label_full, label_left }
    }
}

impl Frp {
    fn init(&self, model: &Model) {
        let frp = &self;
        let network = &frp.network;

        frp::extend! { network
            formatted <- frp.set_content.map(|value| format!("{value:.2}").to_im_string());
            // FIXME: the next line is locale dependent as it is meant to split on the decimal
            //  separator, which might be different from "." in some locales. We need a way to get
            //  the current locale dependent decimal separator for this.
            //  See https://github.com/enso-org/ide/issues/1542 for progress on this.
            left <- formatted.map(|s| s.split('.').next().map(|s| s.to_im_string())).unwrap();

            model.label_left.set_content <+ left;
            model.label_full.set_content <+ formatted;

            eval model.label_left.width((offset)
                model.label_full.set_x(-offset-LABEL_OFFSET));
        }
    }
}

/// Decimal aligned text label that shows the text representation of a floating point number.
#[allow(missing_docs)]
#[derive(Clone, CloneRef, Debug, Deref, display::Object)]
pub struct FloatLabel {
    #[deref]
    pub frp: Rc<Frp>,
    #[display_object]
    model:   Rc<Model>,
}

impl FloatLabel {
    /// Constructor.
    pub fn new(app: &Application) -> Self {
        let model = Rc::new(Model::new(app));
        let frp = Frp::default();
        frp.init(&model);
        let frp = Rc::new(frp);
        Self { frp, model }
    }
}

impl FrpNetworkProvider for FloatLabel {
    fn network(&self) -> &frp::Network {
        self.frp.network()
    }
}
