// === Features ===
#![feature(const_trait_impl)]
#![feature(string_remove_matches)]
#![feature(default_free_fn)]
#![feature(once_cell)]
#![feature(option_result_contains)]
// === Standard Linter Configuration ===
#![deny(non_ascii_idents)]
#![warn(unsafe_code)]
#![allow(clippy::bool_to_int_with_if)]
#![allow(clippy::let_and_return)]



mod prelude {
    pub use enso_build_base::prelude::*;

    pub use convert_case::Case;
    pub use convert_case::Casing;
    pub use itertools::Itertools;
    pub use proc_macro2::Span;
    pub use proc_macro2::TokenStream;
    pub use quote::quote;
    pub use shrinkwraprs::Shrinkwrap;
    pub use syn::parse::Parse;
    pub use syn::Data;
    pub use syn::DeriveInput;
    pub use syn::Ident;
}

use prelude::*;

#[proc_macro_derive(Arg, attributes(arg))]
pub fn derive_answer_fn(item: proc_macro::TokenStream) -> proc_macro::TokenStream {
    let input = syn::parse_macro_input!(item as DeriveInput);
    program_args::arg(input)
        .unwrap_or_else(|err| panic!("Failed to derive program argument: {err:?}"))
        .into()
}

mod program_args;
