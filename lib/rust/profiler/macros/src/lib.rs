//! Proc macros supporting the implementation of the `enso_profiler` library.
//!
//! The profiler API uses procedural macros for two reasons:
//! - To define the hierarchy of profiler types ([`define_hierarchy!`]). Each profiler type (e.g.
//!   [`Objective`](../enso_profiler/struct.Objective.html)) needs an implementation of
//!   [`Parent`](../enso_profiler/trait.Parent.html) for each finer-grained profiler type;
//!   implementing this without proc macros would be complex and repetitious.
//! - To implement the [`#[profile]`](macro@profile) attribute macro.

#![cfg_attr(feature = "lineno", feature(proc_macro_span))]
#![deny(unconditional_recursion)]
#![warn(missing_copy_implementations)]
#![warn(missing_debug_implementations)]
#![warn(missing_docs)]
#![warn(trivial_casts)]
#![warn(trivial_numeric_casts)]
#![warn(unsafe_code)]
#![warn(unused_import_braces)]

use inflector::Inflector;
use quote::ToTokens;
use std::env;
use syn::parse::Parser;
use syn::punctuated;
use syn::visit_mut;
use syn::visit_mut::VisitMut;



// =========================
// === define_hierarchy! ===
// =========================

/// Define a profiler type.
fn define_profiler(
    level: &syn::Ident,
    obj_ident: &syn::Ident,
    enabled: bool,
) -> proc_macro::TokenStream {
    let start = quote::format_ident!("start_{}", level);
    let with_same_start = quote::format_ident!("{}_with_same_start", level);
    let level_link = format!("[{}-level](index.html#{})", level, level);
    let doc_obj = format!("Identifies a {} profiler.", level_link);
    let doc_start = format!("Start a new {} profiler.", level_link);
    let doc_with_same_start = format!(
        "Create a new {} profiler, with the same start as an existing profiler.",
        level_link,
    );
    let ts = if enabled {
        quote::quote! {
            // =================================
            // === Profiler (e.g. Objective) ===
            // =================================

            #[doc = #doc_obj]
            #[derive(Copy, Clone, Debug)]
            pub struct #obj_ident(pub EventId);


            // === Trait Implementations ===

            impl Profiler for #obj_ident {
                fn start(
                    parent: EventId,
                    label: StaticLabel,
                    time: Option<Timestamp>,
                    start: StartState,
                ) -> Self {
                    #obj_ident(EventLog.start(parent, label, time, start))
                }
                fn finish(self) {
                    EventLog.end(self.0, Timestamp::now())
                }
                fn pause(&self) {
                    EventLog.pause(self.0, Timestamp::now());
                }
                fn resume(&self) {
                    EventLog.resume(self.0, Timestamp::now());
                }
            }


            // === Constructor macros ===

            #[doc = #doc_start]
            #[macro_export]
            macro_rules! #start {
                ($parent: expr, $label: expr) => {{
                    use profiler::Parent;
                    let label = profiler::internal::Label(
                        concat!($label, " (", file!(), ":", line!(), ")"));
                    let profiler: profiler::internal::Started<profiler::#obj_ident> =
                        $parent.new_child(label);
                    profiler
                }}
            }

            #[doc = #doc_with_same_start]
            #[macro_export]
            macro_rules! #with_same_start {
                ($parent: expr, $label: expr) => {{
                    use profiler::Parent;
                    let label = profiler::internal::Label(
                        concat!($label, " (", file!(), ":", line!(), ")"));
                    let profiler: profiler::internal::Started<profiler::#obj_ident> =
                        $parent.new_child_same_start(label);
                    profiler
                }}
            }
        }
    } else {
        quote::quote! {
            // =================================
            // === Profiler (e.g. Objective) ===
            // =================================

            #[doc = #doc_obj]
            #[derive(Copy, Clone, Debug)]
            pub struct #obj_ident(pub ());


            // === Trait Implementations ===

            impl Profiler for #obj_ident {
                fn start(
                    _: EventId,
                    _: StaticLabel,
                    _: Option<Timestamp>,
                    _: StartState,
                ) -> Self {
                    Self(())
                }
                fn finish(self) {}
                fn pause(&self) {}
                fn resume(&self) { }
            }


            // === Constructor macros ===

            #[doc = #doc_start]
            #[macro_export]
            macro_rules! #start {
                ($parent: expr, $label: expr) => {
                    profiler::internal::Started(profiler::#obj_ident(()))
                }
            }
            #[macro_export]
            #[doc = #doc_with_same_start]
            macro_rules! #with_same_start {
                ($parent: expr, $label: expr) => {
                    profiler::internal::Started(profiler::#obj_ident(()))
                }
            }
        }
    };
    ts.into()
}

/// Generates an implementation of the [`Parent`] trait relating the given parent and child, when
/// both are enabled.
fn enabled_impl_parent(
    parent_ident: &syn::Ident,
    child_ident: &syn::Ident,
) -> proc_macro::TokenStream {
    let ts = quote::quote! {
        impl Parent<#child_ident> for #parent_ident {
            fn new_child(&self, label: StaticLabel) -> Started<#child_ident> {
                let start = Some(Timestamp::now());
                Started(#child_ident::start(self.0, label, start, StartState::Active))
            }
            fn new_child_same_start(&self, label: StaticLabel) -> Started<#child_ident> {
                Started(#child_ident::start(self.0, label, None, StartState::Active))
            }
        }
    };
    ts.into()
}

/// Generates an implementation of the [`Parent`] trait relating the given parent and child, when
/// the child is disabled.
fn disabled_impl_parent(
    parent_ident: &syn::Ident,
    child_ident: &syn::Ident,
) -> proc_macro::TokenStream {
    let ts = quote::quote! {
        impl Parent<#child_ident> for #parent_ident {
            fn new_child(&self, label: StaticLabel) -> Started<#child_ident> {
                self.new_child_same_start(label)
            }
            fn new_child_same_start(&self, _label: StaticLabel) -> Started<#child_ident> {
                Started(#child_ident(()))
            }
        }
    };
    ts.into()
}

/// Return the numeric Profiling Level (counting from 0 = top-level only).
fn get_enabled_level(levels: &[impl AsRef<str>]) -> usize {
    const PROFILING_LEVEL_ENV_VAR: &str = "ENSO_MAX_PROFILING_LEVEL";
    let enabled = match env::var(PROFILING_LEVEL_ENV_VAR) {
        Ok(level) => level,
        // If the variable isn't set, we default to the minimum.
        Err(_) => return 0,
    };
    for (i, name) in levels.iter().enumerate() {
        if &enabled[..] == name.as_ref() {
            return i;
        }
    }
    panic!("{} set to unknown profiling level: {}", PROFILING_LEVEL_ENV_VAR, enabled);
}

/// Defines a hierarchy of profiler levels.
///
/// # Usage
///
/// ```ignore
/// enso_profiler_macros::define_hierarchy![Objective, Task, Detail, Debug];
/// ```
///
/// Profiler-levels must be specified from coarsest to finest.
///
/// Profiler-level names should be given in CamelCase.
#[proc_macro]
pub fn define_hierarchy(ts: proc_macro::TokenStream) -> proc_macro::TokenStream {
    let parser = punctuated::Punctuated::<syn::Ident, syn::Token![,]>::parse_terminated;
    let idents: Vec<_> = parser.parse(ts).unwrap().into_iter().collect();
    let level_names: Vec<_> = idents.iter().map(|id| id.to_string().to_snake_case()).collect();
    let enabled_level = get_enabled_level(&level_names);
    let mut out = proc_macro::TokenStream::new();
    for ((i, obj_ident), level_name) in idents.iter().enumerate().zip(level_names.iter()) {
        let snake_ident = syn::Ident::new(level_name, proc_macro2::Span::call_site());
        out.extend(define_profiler(&snake_ident, obj_ident, enabled_level >= i));
        for (j, child_ident) in idents[i..].iter().enumerate() {
            let parent_impl = match enabled_level >= i + j {
                true => enabled_impl_parent(obj_ident, child_ident),
                false => disabled_impl_parent(obj_ident, child_ident),
            };
            out.extend(parent_impl);
        }
    }
    out
}



// ================
// === profile! ===
// ================

// [Documented in `profiler`](../enso_profiler/attr.profile.html).
#[allow(missing_docs)]
#[proc_macro_attribute]
pub fn profile(
    args: proc_macro::TokenStream,
    ts: proc_macro::TokenStream,
) -> proc_macro::TokenStream {
    let mut func = syn::parse_macro_input!(ts as syn::ItemFn);
    let obj_ident: syn::Ident = syn::parse(args)
        .expect("The `profile` macro requires a profiling-level argument, e.g. #[profile(Task)]");
    let label = make_label(&func.sig.ident);
    match func.sig.asyncness {
        Some(_) => profile_async(obj_ident, label, &mut func),
        None => profile_sync(obj_ident, label, &mut func),
    };
    func.into_token_stream().into()
}

fn profile_async(obj_ident: syn::Ident, label: String, func: &mut syn::ItemFn) {
    let start_profiler = start_profiler(obj_ident, label, func.sig.asyncness.is_some());
    for s in &mut func.block.stmts {
        WrapAwait.visit_stmt_mut(s);
    }
    let block = &func.block;
    let body = quote::quote! {{
        #start_profiler
        async move {
            profiler::internal::Profiler::resume(&__profiler_scope.0);
            let result = #block;
            std::mem::drop(__profiler_scope);
            result
        }
    }};
    let output_ty = match &func.sig.output {
        syn::ReturnType::Default => quote::quote! { () },
        syn::ReturnType::Type(_, ty) => ty.to_token_stream(),
    };
    let output = quote::quote! {
        -> impl std::future::Future<Output=#output_ty>
    };
    func.sig.asyncness = None;
    func.sig.output = syn::parse2(output).unwrap();
    func.block = syn::parse2(body).unwrap();
}

fn profile_sync(obj_ident: syn::Ident, label: String, func: &mut syn::ItemFn) {
    let start_profiler = start_profiler(obj_ident, label, func.sig.asyncness.is_some());
    let block = &func.block;
    let body = quote::quote! {{
        #start_profiler
        #block
    }};
    func.block = syn::parse2(body).unwrap();
}

fn start_profiler(
    obj_ident: syn::Ident,
    label: String,
    asyncness: bool,
) -> proc_macro2::TokenStream {
    let state = match asyncness {
        true => quote::quote! { profiler::internal::StartState::Paused },
        false => quote::quote! { profiler::internal::StartState::Active },
    };
    quote::quote! {
        let __profiler_scope = {
            use profiler::internal::Profiler;
            let parent = profiler::internal::EventId::implicit();
            let now = Some(profiler::internal::Timestamp::now());
            let label = profiler::internal::Label(#label);
            let profiler = profiler::#obj_ident::start(parent, label, now, #state);
            profiler::internal::Started(profiler)
        };
    }
}

#[cfg(not(feature = "lineno"))]
/// Decorate the input with file:line info determined by the proc_macro's call site.
fn make_label(ident: &syn::Ident) -> String {
    format!("{} (?:?)", ident)
}

#[cfg(feature = "lineno")]
/// Decorate the input with file:line info determined by the proc_macro's call site.
fn make_label(ident: &syn::Ident) -> String {
    let span = proc_macro::Span::call_site();
    let file = span.source_file().path();
    let path = file.as_path().to_string_lossy();
    let line = span.start().line;
    format!("{} ({}:{})", ident, path, line)
}

struct WrapAwait;

impl visit_mut::VisitMut for WrapAwait {
    fn visit_expr_mut(&mut self, expr: &mut syn::Expr) {
        if let syn::Expr::Await(await_) = expr {
            let new_expr = wrap_await(await_);
            *expr = new_expr;
        }
    }
}

fn wrap_await(await_: &syn::ExprAwait) -> syn::Expr {
    let expr = &await_.base;
    assert!(
        await_.attrs.is_empty(),
        "#[profile] cannot wrap a function that applies attributes to an .await expression"
    );
    let wrapped = quote::quote! {
        {
            let future = #expr;
            profiler::internal::Profiler::pause(&__profiler_scope.0);
            let result = future.await;
            profiler::internal::Profiler::resume(&__profiler_scope.0);
            result
        }
    };
    syn::parse2(wrapped).unwrap()
}
