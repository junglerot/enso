// === Standard Linter Configuration ===
#![deny(non_ascii_idents)]
#![warn(unsafe_code)]
#![allow(clippy::bool_to_int_with_if)]
#![allow(clippy::let_and_return)]

use wasm_bindgen::prelude::*;

use enso_parser::Parser;



thread_local! {
    pub static PARSER: Parser = Parser::new();
}

#[wasm_bindgen]
pub fn parse_to_json(code: &str) -> String {
    let ast = PARSER.with(|parser| parser.run(code));
    serde_json::to_string(&ast).expect("Failed to serialize AST to JSON")
}

#[wasm_bindgen]
pub fn parse_doc_to_json(docs: &str) -> String {
    let docs = enso_doc_parser::parse(docs);
    serde_json::to_string(&docs).expect("Failed to serialize Doc Sections to JSON")
}

#[wasm_bindgen(start)]
fn main() {
    console_error_panic_hook::set_once();
}
