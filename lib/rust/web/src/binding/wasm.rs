//! Native bindings to the web-api.

use crate::prelude::*;


// ==============
// === Export ===
// ==============

pub use js_sys::Array;
pub use js_sys::Error;
pub use js_sys::Function;
pub use js_sys::JsString;
pub use js_sys::Map;
pub use js_sys::Object;
pub use js_sys::Promise;
pub use std::time::Duration;
pub use std::time::Instant;
pub use wasm_bindgen::prelude::Closure;
pub use wasm_bindgen::prelude::*;
pub use wasm_bindgen::JsCast;
pub use wasm_bindgen::JsValue;
pub use web_sys::console;
pub use web_sys::AddEventListenerOptions;
pub use web_sys::CanvasRenderingContext2d;
pub use web_sys::Document;
pub use web_sys::Element;
pub use web_sys::Event;
pub use web_sys::EventListenerOptions;
pub use web_sys::EventTarget;
pub use web_sys::HtmlCanvasElement;
pub use web_sys::HtmlCollection;
pub use web_sys::HtmlDivElement;
pub use web_sys::HtmlElement;
pub use web_sys::KeyboardEvent;
pub use web_sys::MouseEvent;
pub use web_sys::Node;
pub use web_sys::Performance;
pub use web_sys::WebGl2RenderingContext;
pub use web_sys::WebGlQuery;
pub use web_sys::WheelEvent;
pub use web_sys::Window;



// ================
// === Function ===
// ================

#[wasm_bindgen(inline_js = "
    export function new_function_with_args(args, body) {
        return new Function(args, body)
    }
")]
extern "C" {
    // See the docs of [`crate::FunctionOps`].
    #[allow(unsafe_code)]
    #[wasm_bindgen(catch)]
    pub fn new_function_with_args(args: &str, body: &str) -> Result<Function, JsValue>;
}



// ===============
// === Reflect ===
// ===============

/// [`wasm-bindgen`] defines [`Reflect`] as a module. This library needs to extend it with new
/// functions and thus it is mocked as this phantom structure.

#[derive(Copy, Clone, Debug)]
pub struct Reflect {}

#[allow(missing_docs)]
impl Reflect {
    pub fn get(target: &JsValue, key: &JsValue) -> Result<JsValue, JsValue> {
        js_sys::Reflect::get(target, key)
    }

    pub fn set(target: &JsValue, key: &JsValue, value: &JsValue) -> Result<bool, JsValue> {
        js_sys::Reflect::set(target, key, value)
    }
}



// ===========================
// === Window and Document ===
// ===========================

#[cfg(target_arch = "wasm32")]
/// Similar to [`lazy_static`], but does not require the type to be synced between threads (WASM32
/// target is single-threaded.
macro_rules! wasm_lazy_global {
    ($name:ident : $tp:ty = $expr:expr) => {
        #[allow(missing_docs)]
        pub mod $name {
            use super::*;
            pub static mut STORE: Option<$tp> = None;

            // [`Copy`] and [`Clone`] are not implemented on purpose, so when the value is cloned,
            // the operation will deref to it's target type.
            #[allow(missing_copy_implementations)]
            #[derive(Debug)]
            pub struct Ref {}
        }

        impl Deref for $name::Ref {
            type Target = $tp;
            #[allow(unsafe_code)]
            fn deref(&self) -> &Self::Target {
                unsafe {
                    $name::STORE.as_ref().unwrap_or_else(|| {
                        let val = $expr;
                        $name::STORE = Some(val);
                        $name::STORE.as_ref().unwrap()
                    })
                }
            }
        }

        #[allow(non_upper_case_globals)]
        #[allow(missing_docs)]
        pub const $name: $name::Ref = $name::Ref {};
    };
}

/// Get the global window object.
///
/// # Safety
/// We are using an unsafe cast here in order to make it working in node. Please note that node does
/// NOT expose a `window` global object. We are creating it there manually. This created object
/// exposes some `window` functions, such as `.performance.now()`. It is enough for us to run the
/// generated WASM there.
pub fn get_window() -> Window {
    js_sys::global().unchecked_into::<Window>()
}

#[cfg(target_arch = "wasm32")]
wasm_lazy_global! { window : Window = get_window() }

#[cfg(target_arch = "wasm32")]
wasm_lazy_global! { document : Document = window.document().unwrap() }
