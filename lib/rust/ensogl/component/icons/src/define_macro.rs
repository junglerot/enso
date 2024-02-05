// ==========================
// === Define Icons Macro ===
// ==========================

// Lint gives an erroneous warning: the main is actually needed, because we generate modules here.
#[allow(clippy::needless_doctest_main)]
/// Macro for defining icon set.
///
/// The macro takes many modules with attached "variant name". Inside the modules, there should
/// be icon defined with `ensogl::cached_shape!` macro. The macro will also generate an
/// enum called `Id` gathering all icon' "variant names". The enum will allow for dynamically
/// creating given icon shape view (returned as [`crate::icon::AnyIcon`]).
///
/// # Example
///
/// ```
/// use ensogl_core::prelude::*;
/// use ensogl_core::display::shape::*;
/// use ensogl_core::data::color;
/// use ensogl_icons::define_icons;
///
/// define_icons! {
///     /// The example of icon.
///     pub mod icon1(Icon1) {
///         // This is a normal module and you may define whatever you want. It must however
///         // define a cached shape system with the macro below; otherwise the generated code wont
///         // compile.
///         //
///         // `use super::*` import is added silently.
///         ensogl_core::cached_shape! {
///             size = (16, 16);
///             (style:Style) {
///                 Plane().into()
///             }
///         }
///     }
///
///     pub mod icon2(Icon2) {
///         ensogl_core::cached_shape! {
///             size = (16, 16);
///             (style:Style) {
///                 Plane().fill(color::Rgba::red()).into()
///             }
///         }
///     }
/// }
///
/// fn main () {
///     let app = ensogl_core::application::Application::new("root");
///     let icon1 = Id::Icon1.cached_view();
///     let icon2_id: Id = "Icon2".parse().unwrap();
///     assert_eq!(icon2_id, Id::Icon2);
///     let icon2 = icon2_id.cached_view();
///     app.display.default_scene.add_child(&icon1);
///     app.display.default_scene.add_child(&icon2);
///
///     // Invalid icon
///     let icon3 = "Icon3".parse::<Id>();
///     assert!(icon3.is_err());
/// }
#[macro_export]
macro_rules! define_icons {
    ($(
        $(#$meta:tt)*
        pub mod $name:ident($variant:ident) {
            $($content:tt)*
        }
    )*) => {
        $(
            $(#$meta)*
            pub mod $name {
                use super::*;
                $($content)*
            }
        )*

        /// An identifier of one of the icons generated by the same `define_icons` macro invocation.
        #[allow(missing_docs)]
        #[derive(Copy, Clone, Debug, Eq, PartialEq)]
        pub enum Id {
            $($variant),*
        }

        impl Id {
            /// Get the cached texture location of the current icon.
            ///
            /// May be used to set [`AnyCachedShape`] parameter on shape.
            pub fn any_cached_shape_location(&self) -> Vector4 {
                use ensogl_core::display::shape::CachedShape;
                match self {$(
                    Self::$variant => $name::Shape::any_cached_shape_parameter(),
                )*}
            }

            /// Create a view reading the icon from the cached texture.
            pub fn cached_view(&self) -> $crate::any::View {
                let view = $crate::any::View::new();
                view.icon.set(self.any_cached_shape_location());
                view
            }

            /// Call `f` for each possible icon id.
            pub fn for_each<F: FnMut(Self)>(mut f: F) {
                $(f(Self::$variant);)*
            }

            /// Get a string identifier with the icon's name.
            pub fn as_str(&self) -> &'static str {
                match self {
                    $(Self::$variant => stringify!($variant),)*
                }
            }
        }

        impl FromStr for Id {
            type Err = $crate::UnknownIcon;
            fn from_str(s: &str) -> Result<Id, Self::Err> {
                match s {
                    $(stringify!($variant) => Ok(Self::$variant),)*
                    name => Err(Self::Err {name: name.to_owned() }),
                }
            }
        }
    }
}
