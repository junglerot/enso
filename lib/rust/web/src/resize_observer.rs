//! Binding to the https://developer.mozilla.org/en-US/docs/Web/API/ResizeObserver.

use crate::prelude::*;

use crate::Closure;
use crate::JsValue;



// =============
// === Types ===
// =============

/// Listener closure for the [`ResizeObserver`].
pub type Listener = Closure<dyn FnMut(f32, f32)>;



// ===================
// === JS Bindings ===
// ===================

#[cfg(target_arch = "wasm32")]
use wasm_bindgen::prelude::wasm_bindgen;

#[cfg(target_arch = "wasm32")]
#[wasm_bindgen(module = "/js/resize_observer.js")]
extern "C" {
    #[allow(unsafe_code)]
    fn resize_observe(target: &JsValue, closure: &Listener) -> usize;

    #[allow(unsafe_code)]
    fn resize_unobserve(id: usize);
}

#[cfg(not(target_arch = "wasm32"))]
fn resize_observe(_target: &JsValue, _closure: &Listener) -> usize {
    0
}
#[cfg(not(target_arch = "wasm32"))]
fn resize_unobserve(_id: usize) {}


// ======================
// === ResizeObserver ===
// ======================

/// The ResizeObserver interface reports changes to the dimensions of an DOM Element's content or
/// border box. ResizeObserver avoids infinite callback loops and cyclic dependencies that are often
/// created when resizing via a callback function. It does this by only processing elements deeper
/// in the DOM in subsequent frames.
///
/// See also https://developer.mozilla.org/en-US/docs/Web/API/ResizeObserver.
#[derive(Debug)]
#[allow(missing_docs)]
pub struct ResizeObserver {
    pub target:      JsValue,
    pub listener:    Listener,
    pub observer_id: usize,
}

impl ResizeObserver {
    /// Constructor.
    pub fn new(target: &JsValue, listener: Listener) -> Self {
        let target = target.clone_ref();
        let observer_id = resize_observe(&target, &listener);
        Self { target, listener, observer_id }
    }
}

impl Drop for ResizeObserver {
    fn drop(&mut self) {
        resize_unobserve(self.observer_id);
    }
}
