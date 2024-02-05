//! Build script for [`enso_logging_macros`]. This is needed to make cargo aware that
//! the crate depends on the values of environment variables at compile time, and changes to those
//! variables should result in recompiling this crate and its dependents.

// === Non-Standard Linter Configuration ===
#![warn(missing_copy_implementations)]
#![warn(missing_debug_implementations)]
#![warn(missing_docs)]
#![warn(trivial_casts)]
#![warn(trivial_numeric_casts)]
#![warn(unsafe_code)]
#![warn(unused_import_braces)]
#![warn(unused_qualifications)]



fn main() {
    declare_env_dependence("ENSO_MAX_LOG_LEVEL");
    declare_env_dependence("ENSO_UNCOLLAPSED_LOG_LEVEL");
}

/// Make cargo aware that the result of compiling this crate depends on an environment variable.
fn declare_env_dependence(env: &str) {
    println!("cargo:rerun-if-env-changed={env}");
    // This is a no-op assignment, except it makes cargo aware that the output depends on the env.
    let value = std::env::var(env).unwrap_or_default();
    println!("cargo:rustc-env={env}={value}");
}
