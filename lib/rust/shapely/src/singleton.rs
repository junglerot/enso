//! This module defines helpers for defining singletons and associated enum types. A singleton is
//! a type with one possible value. It is used mainly for a type level programming purposes.



/// Defines singleton types. For the following input:
/// ```text
/// define_singletons!{
///     /// A Foo!
///     Foo,
///     /// A Bar!
///     Bar,
/// }
/// ```
///
/// It expands to:
///
/// ```
/// #[allow(missing_docs)]
/// #[derive(Copy, Clone, Debug)]
/// #[doc = r###"A Foo!"###]
/// pub struct Foo;
/// impl Default for Foo {
///     fn default() -> Self {
///         Self
///     }
/// }
/// #[allow(missing_docs)]
/// #[derive(Copy, Clone, Debug)]
/// #[doc = r###"A Bar!"###]
/// pub struct Bar;
/// impl Default for Bar {
///     fn default() -> Self {
///         Self
///     }
/// }
/// ```
#[macro_export]
macro_rules! define_singletons {
    ( $( $(#$meta:tt)* $name:ident ),* $(,)? ) => {$(
        #[allow(missing_docs)]
        #[derive(Copy,Clone,Debug,PartialEq,Eq)]
        $(#$meta)*
        pub struct $name;

        impl Default for $name {
            fn default() -> Self {
                Self
            }
        }
    )*}
}

/// Defines an associated enum type for predefined singletons.
///
/// For the following input:
/// ```text
/// define_singleton_enum!{
///     MyEnum {
///         /// A Foo!
///         Foo,
///         /// A Bar!
///         Bar,
///     }
/// }
/// ```
///
/// It expands to:
///
/// ```text
/// #[allow(missing_docs)]
/// #[derive(Copy, Clone, Debug)]
/// pub enum MyEnum {
///     #[doc = r###"A Foo!"###]
///     Foo,
///     #[doc = r###"A Bar!"###]
///     Bar,
/// }
/// impl From<Foo> for MyEnum {
///     fn from(_: Foo) -> Self {
///         Self::Foo
///     }
/// }
/// impl From<ZST<Foo>> for MyEnum {
///     fn from(_: ZST<Foo>) -> Self {
///         Self::Foo
///     }
/// }
/// impl From<Bar> for MyEnum {
///     fn from(_: Bar) -> Self {
///         Self::Bar
///     }
/// }
/// impl From<ZST<Bar>> for MyEnum {
///     fn from(_: ZST<Bar>) -> Self {
///         Self::Bar
///     }
/// }
/// ```
#[macro_export]
macro_rules! define_singleton_enum_from {
    (
        $(#$meta:tt)*
        $name:ident {
            $( $(#$field_meta:tt)* $field:ident ),* $(,)?
        }
    ) => {
        #[allow(missing_docs)]
        #[derive(Copy,Clone,Debug,PartialEq,Eq)]
        $(#$meta)*
        pub enum $name {
            $( $(#$field_meta)* $field ),*
        }

        $(
            impl From<$field> for $name {
                fn from(_:$field) -> Self {
                    Self::$field
                }
            }

            impl From<ZST<$field>> for $name {
                fn from(_:ZST<$field>) -> Self {
                    Self::$field
                }
            }
        )*
    }
}

/// Defines singletons and an associated enum type.
/// It expands to the same as `define_singletons` and `define_singleton_enum_from`.
#[macro_export]
macro_rules! define_singleton_enum {
    (
        $(#$meta:tt)*
        $name:ident {
            $(
                $(#$variant_meta:tt)*
                $variant:ident $(($($variant_field:tt)*))?
            ),* $(,)?
        }
    ) => {
        $(
            $crate::define_singleton_enum_struct! {
                $(#$variant_meta)*
                $variant ($($($variant_field)*)?)
            }
        )*
        $crate::define_singleton_enum_from! { $(#$meta)* $name {$($(#$variant_meta)* $variant),*}}
    }
}

#[macro_export]
macro_rules! define_singleton_enum_struct {
    ( $(#$meta:tt)* $name:ident () ) => {
        #[allow(missing_docs)]
        #[derive(Copy,Clone,Debug,PartialEq,Eq)]
        $(#$meta)*
        pub struct $name;

        impl Default for $name {
            fn default() -> Self {
                Self
            }
        }
    };

    ( $(#$meta:tt)* $name:ident ($($args:tt)*) ) => {
        #[allow(missing_docs)]
        #[derive(Clone,Debug,PartialEq,Eq)]
        $(#$meta)*
        pub struct $name($($args)*);
    };
}
