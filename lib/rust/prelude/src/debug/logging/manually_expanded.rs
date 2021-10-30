//! This module contains manually expanded macros that should be defined by `define_debug_macros`.
//! See [Clippy ICE workaround] in [crate::debug::logging].

/// Special logging macro that prints to the Web Console on wasm targets and stdout
/// otherwise. It is supposed to be used only for development purposes and shouldn't be
/// present in a production-ready code.
/// Macro follows `iformat` formatting convention.
#[macro_export] macro_rules! TRACE {
    ($($arg:tt)*) => {
        $crate::debug::logging::trace($crate::iformat!($($arg)*))
    }
}

/// Special logging macro that prints to the Web Console on wasm targets and stdout
/// otherwise. It is supposed to be used only for development purposes and shouldn't be
/// present in a production-ready code.
/// Macro follows `iformat` formatting convention.
#[macro_export] macro_rules! DEBUG {
    ($($arg:tt)*) => {
        $crate::debug::logging::debug($crate::iformat!($($arg)*))
    }
}

/// Special logging macro that prints to the Web Console on wasm targets and stdout
/// otherwise. It is supposed to be used only for development purposes and shouldn't be
/// present in a production-ready code.
/// Macro follows `iformat` formatting convention.
#[macro_export] macro_rules! INFO {
    ($($arg:tt)*) => {
        $crate::debug::logging::info($crate::iformat!($($arg)*))
    }
}

/// Special logging macro that prints to the Web Console on wasm targets and stdout
/// otherwise. It is supposed to be used only for development purposes and shouldn't be
/// present in a production-ready code.
/// Macro follows `iformat` formatting convention.
#[macro_export] macro_rules! WARNING {
    ($($arg:tt)*) => {
        $crate::debug::logging::warn($crate::iformat!($($arg)*))
    }
}

/// Special logging macro that prints to the Web Console on wasm targets and stdout
/// otherwise. It is supposed to be used only for development purposes and shouldn't be
/// present in a production-ready code.
/// Macro follows `iformat` formatting convention.
#[macro_export] macro_rules! ERROR {
    ($($arg:tt)*) => {
        $crate::debug::logging::error($crate::iformat!($($arg)*))
    }
}
