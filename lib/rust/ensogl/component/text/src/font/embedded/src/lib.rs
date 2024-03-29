//! Collection of embedded fonts generated by the build.rs script.

// === Standard Linter Configuration ===
#![deny(non_ascii_idents)]
#![warn(unsafe_code)]
#![allow(clippy::bool_to_int_with_if)]
#![allow(clippy::let_and_return)]
// === Non-Standard Linter Configuration ===
#![allow(clippy::option_map_unit_fn)]
#![allow(clippy::precedence)]
#![allow(dead_code)]
#![deny(unconditional_recursion)]
#![warn(missing_copy_implementations)]
#![warn(missing_debug_implementations)]
#![warn(missing_docs)]
#![warn(trivial_casts)]
#![warn(trivial_numeric_casts)]
#![warn(unused_import_braces)]
#![warn(unused_qualifications)]

use enso_prelude::*;

use enso_font as family;



// ==============
// === Export ===
// ==============

include!(concat!(env!("OUT_DIR"), "/embedded_fonts_data.rs"));



// ================
// === Embedded ===
// ================

/// A base of built-in fonts in application.
///
/// The structure keeps a map from a font name to its binary ttf representation. The binary data can
/// be further interpreted by such libs as the MSDF-gen one.
///
/// For list of embedded fonts, see FONTS_TO_EXTRACT constant in `build.rs`.
#[allow(missing_docs)]
#[derive(Clone)]
pub struct Embedded {
    pub definitions: HashMap<family::Name, family::FontFamily>,
    pub data:        HashMap<&'static str, &'static [u8]>,
    pub features:    HashMap<family::Name, Vec<rustybuzz::Feature>>,
}

impl Default for Embedded {
    fn default() -> Self {
        let data = embedded_fonts_data();
        let definitions = embedded_family_definitions();
        let features = embedded_family_features()
            .into_iter()
            .map(|(family, feats)| {
                // Safe to `unwrap` because the input is compile-time constant.
                (family, feats.into_iter().map(|feat| feat.parse().unwrap()).collect())
            })
            .collect();
        Self { data, definitions, features }
    }
}

impl Debug for Embedded {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("<Embedded fonts>")
    }
}



// =============
// === Tests ===
// =============

#[cfg(test)]
mod test {
    use crate::*;

    #[test]
    fn loading_embedded_fonts() {
        let fonts = Embedded::default();
        let example_font = fonts.data.get("Enso-Regular.ttf").unwrap();
        assert_eq!(0x00, example_font[0]);
        assert_eq!(0x01, example_font[1]);
        assert_eq!(0x00, example_font[example_font.len() - 1]);
    }
}
