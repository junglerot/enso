//! Utilities for creating custom strongly typed units. For example, unit `Duration` is a wrapper
//! for [`f32`] and defines time-related utilities.
//!
//! Units automatically implement a lot of traits in a generic fashion, so you can for example add
//! [`Duration`] together, or divide one [`Duration`] by a number or another [`Duration`] and get
//! [`Duration`] or a number, respectfully. You are allowed to define any combination of operators
//! and rules of how the result inference should be performed.

use std::marker::PhantomData;



/// Common traits for built-in units.
pub mod traits {
    pub use super::DurationNumberOps;
    pub use super::DurationOps;
}

mod ops {
    pub use crate::algebra::*;
    pub use std::ops::*;
}



// =====================
// === UncheckedInto ===
// =====================

/// Unchecked unit conversion. You should use it only for unit conversion definition, never in
/// unit-usage code.
#[allow(missing_docs)]
pub trait UncheckedInto<T> {
    fn unchecked_into(self) -> T;
}

impl<T> const UncheckedInto<T> for T {
    fn unchecked_into(self) -> T {
        self
    }
}

impl<V, R> const UncheckedInto<UnitData<V, R>> for R {
    fn unchecked_into(self) -> UnitData<V, R> {
        let repr = self;
        let variant = PhantomData;
        UnitData { repr, variant }
    }
}



// ================
// === UnitData ===
// ================

/// Abstract unit type for the given variant. Variants are marker structs used to distinguish units.
/// For example, the [`Duration`] unit is defined as (after macro expansion):
/// ```text
/// pub type Duration = Unit<DURATION>
/// pub struct DURATION;
/// impl Variant for DURATION {
///     type Repr = f64
/// }
/// ```
pub type Unit<V> = UnitData<V, <V as Variant>::Repr>;

/// Relation between the unit variant and its internal representation. Read the docs of [`UnitData`]
/// to learn more about variants.
#[allow(missing_docs)]
pub trait Variant {
    type Repr;
}

/// Internal representation of every unit.
pub struct UnitData<V, R> {
    repr:    R,
    variant: PhantomData<V>,
}

impl<V, R: Copy> UnitData<V, R> {
    /// Get the underlying value. Please note that this might result in a different value than you
    /// might expect. For example, if the internal representation of a duration type is a number of
    /// milliseconds, then `1.second().unchecked_raw()` will return `1000`.
    pub const fn unchecked_raw(self) -> R {
        self.repr
    }
}

impl<V, R: Copy> Copy for UnitData<V, R> {}
impl<V, R: Copy> Clone for UnitData<V, R> {
    fn clone(&self) -> Self {
        *self
    }
}



// =================
// === IsNotUnit ===
// =================

/// Trait used to resolve conflicts when implementing traits fot [`Unit`].
pub auto trait IsNotUnit {}
impl<V, R> !IsNotUnit for UnitData<V, R> {}



// ===============
// === Default ===
// ===============

impl<V, R: Default> Default for UnitData<V, R> {
    fn default() -> Self {
        R::default().unchecked_into()
    }
}



// =======================
// === Debug / Display ===
// =======================

impl<V, R: std::fmt::Debug> std::fmt::Debug for UnitData<V, R> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Unit({:?})", self.repr)
    }
}

impl<V, R: std::fmt::Display> std::fmt::Display for UnitData<V, R> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        self.repr.fmt(f)
    }
}



// =====================
// === Deref / AsRef ===
// =====================

impl<V, R> AsRef<UnitData<V, R>> for UnitData<V, R> {
    fn as_ref(&self) -> &UnitData<V, R> {
        self
    }
}



// ==========
// === Eq ===
// ==========

impl<V, R: PartialEq> Eq for UnitData<V, R> {}
impl<V, R: PartialEq> PartialEq for UnitData<V, R> {
    fn eq(&self, other: &Self) -> bool {
        self.repr.eq(&other.repr)
    }
}



// ===========
// === Ord ===
// ===========

impl<V, R: Ord> Ord for UnitData<V, R> {
    fn cmp(&self, other: &Self) -> std::cmp::Ordering {
        self.repr.cmp(&other.repr)
    }
}

impl<V, R: PartialOrd> PartialOrd for UnitData<V, R> {
    fn partial_cmp(&self, other: &Self) -> Option<std::cmp::Ordering> {
        self.repr.partial_cmp(&other.repr)
    }
}



// ============
// === From ===
// ============

impl<V, R> From<&UnitData<V, R>> for UnitData<V, R>
where R: Copy
{
    fn from(t: &UnitData<V, R>) -> Self {
        *t
    }
}



// ===============
// === gen_ops ===
// ===============

/// Internal macro for defining operators for the generic [`Unit`] structure. Because Rust disallows
/// defining trait impls for structs defined in external modules and in order to provide a
/// configurable default impl, this macro generates local traits controlling the behavior of the
/// generated impls. For example, the following code:
///
/// ```text
/// gen_ops!(RevAdd, Add, add);
/// ```
///
/// will result in:
///
/// ```text
/// pub trait Add<T> {
///     type Output;
/// }
///
/// pub trait RevAdd<T> {
///     type Output;
/// }
///
/// impl<V, R> const ops::Add<UnitData<V, R>> for f32
/// where V: RevAdd<f32>, ... {
///     type Output = UnitData<<V as RevAdd<f32>>::Output, <f32 as ops::Add<R>>::Output>;
///     fn add(self, rhs: UnitData<V, R>) -> Self::Output { ... }
/// }
///
/// impl<V, R, T> const ops::Add<T> for UnitData<V, R>
/// where UnitData<V, R>: Add<T>, ... {
///     type Output = <UnitData<V, R> as Add<T>>::Output;
///     fn add(self, rhs: T) -> Self::Output { ... }
/// }
///
/// impl<V1, V2, R1, R2> const ops::Add<UnitData<V2, R2>> for UnitData<V1, R1>
/// where UnitData<V1, R1>: Add<UnitData<V2, R2>>, ... {
///     type Output = <UnitData<V1, R1> as Add<UnitData<V2, R2>>>::Output;
///     fn add(self, rhs: UnitData<V2, R2>) -> Self::Output { ... }
/// }
/// ```
///
/// Please note, that all traits in the [`ops`] module are standard Rust traits, while the ones
/// with no prefix, are custom ones. Having such an implementation and an example unit type
/// definition:
///
/// ```text
/// pub type Duration = Unit<DURATION>;
/// pub struct DURATION;
/// impl Variant for DURATION {
///     type Repr = f32;
/// }
/// ```
///
/// We can allow for adding and dividing two units simply by implementing the following traits:
///
/// ```text
/// impl Add<Duration> for Duration {
///     type Output = Duration;
/// }
///
/// impl Div<Duration> for Duration {
///     type Output = f32;
/// }
/// ```
///
/// This crate provides a nice utility for such trait impl generation. See [`define_ops`] to learn
/// more.
macro_rules! gen_ops {
    ($rev_trait:ident, $trait:ident, $op:ident) => {
        #[allow(missing_docs)]
        pub trait $trait<T> {
            type Output;
        }

        #[allow(missing_docs)]
        pub trait $rev_trait<T> {
            type Output;
        }

        // Please note that this impl is not as generic as the following ones because Rust compiler
        // is unable to compile the more generic version.
        impl<V, R> const ops::$trait<UnitData<V, R>> for f32
        where
            R: Copy,
            V: $rev_trait<f32>,
            f32: ~const ops::$trait<R>,
        {
            type Output = UnitData<<V as $rev_trait<f32>>::Output, <f32 as ops::$trait<R>>::Output>;
            fn $op(self, rhs: UnitData<V, R>) -> Self::Output {
                self.$op(rhs.repr).unchecked_into()
            }
        }

        impl<V, R, T> const ops::$trait<T> for UnitData<V, R>
        where
            UnitData<V, R>: $trait<T>,
            R: ~const ops::$trait<T> + Copy,
            T: IsNotUnit,
            <R as ops::$trait<T>>::Output:
                ~const UncheckedInto<<UnitData<V, R> as $trait<T>>::Output>,
        {
            type Output = <UnitData<V, R> as $trait<T>>::Output;
            fn $op(self, rhs: T) -> Self::Output {
                self.repr.$op(rhs).unchecked_into()
            }
        }

        impl<V1, V2, R1, R2> const ops::$trait<UnitData<V2, R2>> for UnitData<V1, R1>
        where
            UnitData<V1, R1>: $trait<UnitData<V2, R2>>,
            R1: ~const ops::$trait<R2> + Copy,
            R2: Copy,
            <R1 as ops::$trait<R2>>::Output:
                ~const UncheckedInto<<UnitData<V1, R1> as $trait<UnitData<V2, R2>>>::Output>,
        {
            type Output = <UnitData<V1, R1> as $trait<UnitData<V2, R2>>>::Output;
            fn $op(self, rhs: UnitData<V2, R2>) -> Self::Output {
                self.repr.$op(rhs.repr).unchecked_into()
            }
        }
    };
}

/// Internal helper for the [`gen_ops`] macro.
macro_rules! gen_ops_mut {
    ($rev_trait:ident, $trait:ident, $trait_mut:ident, $op:ident) => {
        impl<V, R> const ops::$trait_mut<UnitData<V, R>> for f32
        where
            f32: ~const ops::$trait_mut<R>,
            R: Copy,
            UnitData<V, R>: $rev_trait<f32>,
        {
            fn $op(&mut self, rhs: UnitData<V, R>) {
                self.$op(rhs.repr)
            }
        }
        impl<V, R, T> const ops::$trait_mut<T> for UnitData<V, R>
        where
            T: IsNotUnit,
            R: ~const ops::$trait_mut<T>,
            UnitData<V, R>: $trait<T>,
        {
            fn $op(&mut self, rhs: T) {
                self.repr.$op(rhs)
            }
        }
        impl<V1, V2, R1, R2> const ops::$trait_mut<UnitData<V2, R2>> for UnitData<V1, R1>
        where
            R1: ~const ops::$trait_mut<R2>,
            R2: Copy,
            UnitData<V1, R1>: $trait<UnitData<V2, R2>>,
        {
            fn $op(&mut self, rhs: UnitData<V2, R2>) {
                self.repr.$op(rhs.repr)
            }
        }
    };
}



gen_ops!(RevAdd, Add, add);
gen_ops!(RevSub, Sub, sub);
gen_ops!(RevMul, Mul, mul);
gen_ops!(RevDiv, Div, div);
gen_ops!(SaturatingRevAdd, SaturatingAdd, saturating_add);
gen_ops!(SaturatingRevSub, SaturatingSub, saturating_sub);
gen_ops!(SaturatingRevMul, SaturatingMul, saturating_mul);
gen_ops_mut!(RevAdd, Add, AddAssign, add_assign);
gen_ops_mut!(RevSub, Sub, SubAssign, sub_assign);
gen_ops_mut!(RevMul, Mul, MulAssign, mul_assign);
gen_ops_mut!(RevDiv, Div, DivAssign, div_assign);



// ==============================
// === Unit Definition Macros ===
// ==============================


// === define ===

/// Utilities for new unit definition. For example, the following definition:
///
/// ```text
/// define!(Duration = DURATION(f32));
/// ```
///
/// will generate the following code:
///
/// ````text
/// pub type Duration = Unit<DURATION>;
/// pub struct DURATION;
/// impl Variant for DURATION {
///     type Repr = f32;
/// }
/// ```
#[macro_export]
macro_rules! define {
    ($(#$meta:tt)* $name:ident = $variant:ident ($tp:ident)) => {
        $(#$meta)*
        pub type $name = Unit<$variant>;

        $(#$meta)*
        #[derive(Debug, Clone, Copy)]
        pub struct $variant;

        impl Variant for $variant {
            type Repr = $tp;
        }
    };
}


// === define_ops ===

/// Utilities for new unit-operations relations definition. For example, the following definition:
///
/// ```text
/// define_ops![
///     Duration [+,-] Duration = Duration,
///     Duration [*,/] f32 = Duration,
///     Duration / Duration = f32,
///     f32 * Duration = Duration,
/// ];
/// ```
///
/// will generate the following code:
///
/// ```text
/// impl Add<Duration> for Duration {
///     type Output = Duration;
/// }
/// impl Sub<Duration> for Duration {
///     type Output = Duration;
/// }
/// impl Mul<f32> for Duration {
///     type Output = Duration;
/// }
/// impl Div<f32> for Duration {
///     type Output = Duration;
/// }
/// impl Div<Duration> for Duration {
///     type Output = f32;
/// }
/// impl RevMul<Duration> for f32 {
///     type Output = Duration;
/// }
/// ```
///
/// See the documentation of the [`gen_ops`] macro to learn about such traits as [`Add`], [`Mul`],
/// or [`RevMul`] – these are NOT standard Rust traits.
#[macro_export]
macro_rules! define_ops {
    ($($lhs:ident $op:tt $rhs:ident = $out:ident),* $(,)?) => {
        $(
            $crate::define_single_op_switch!{ $lhs $op $rhs = $out }
        )*
    };
}

/// Internal helper for the [`define_ops`] macro.
#[macro_export]
macro_rules! define_single_op_switch {
    (f32 $op:tt $rhs:ident = $out:ident) => {
        $crate::define_single_rev_op! {f32 $op $rhs = $out}
    };
    (f64 $op:tt $rhs:ident = $out:ident) => {
        $crate::define_single_rev_op! {f64 $op $rhs = $out}
    };
    ($lhs:ident $op:tt $rhs:ident = $out:ident) => {
        $crate::define_single_op! {$lhs $op $rhs = $out}
    };
}

/// Internal helper for the [`define_ops`] macro.
#[macro_export]
macro_rules! define_single_op {
    ($lhs:ident + $rhs:ident = $out:ident) => {
        impl Add<$rhs> for $lhs {
            type Output = $out;
        }
    };

    ($lhs:ident - $rhs:ident = $out:ident) => {
        impl Sub<$rhs> for $lhs {
            type Output = $out;
        }
    };

    ($lhs:ident * $rhs:ident = $out:ident) => {
        impl Mul<$rhs> for $lhs {
            type Output = $out;
        }
    };

    ($lhs:ident / $rhs:ident = $out:ident) => {
        impl Div<$rhs> for $lhs {
            type Output = $out;
        }
    };

    ($lhs:ident [$($op:tt),* $(,)?] $rhs:ident = $out:ident) => {
        $(
            $crate::define_single_op!{$lhs $op $rhs = $out}
        )*
    };
}

/// Internal helper for the [`define_ops`] macro.
#[macro_export]
macro_rules! define_single_rev_op {
    ($lhs:ident + $rhs:ident = $out:ident) => {
        impl RevAdd<$rhs> for $lhs {
            type Output = $out;
        }
    };

    ($lhs:ident - $rhs:ident = $out:ident) => {
        impl RevSub<$rhs> for $lhs {
            type Output = $out;
        }
    };

    ($lhs:ident * $rhs:ident = $out:ident) => {
        impl RevMul<$rhs> for $lhs {
            type Output = $out;
        }
    };

    ($lhs:ident / $rhs:ident = $out:ident) => {
        impl RevDiv<$rhs> for $lhs {
            type Output = $out;
        }
    };

    ($lhs:ident [$($op:tt),* $(,)?] $rhs:ident = $out:ident) => {
        $(
            $crate::define_single_rev_op!{$lhs $op $rhs = $out}
        )*
    };
}



// ================
// === Duration ===
// ================

define! {
    /// A span of time. Duration stores milliseconds under the hood, which is a representation
    /// optimized for real-time rendering and graphics processing.
    ///
    /// Conversions between this type and the [`std::time::Duration`] are provided, however, please
    /// note that [`std::time::Duration`] internal representation is optimized for different cases,
    /// so losing precision is expected during the conversion.
    Duration = DURATION(f32)
}
define_ops![
    Duration [+,-] Duration = Duration,
    Duration [*,/] f32 = Duration,
    Duration / Duration = f32,
    f32 * Duration = Duration,
];

/// Methods for the [`Duration`] unit.
#[allow(missing_docs)]
pub trait DurationOps {
    fn ms(t: f32) -> Duration;
    fn s(t: f32) -> Duration;
    fn min(t: f32) -> Duration;
    fn h(t: f32) -> Duration;
    fn as_ms(&self) -> f32;
    fn as_s(&self) -> f32;
    fn as_min(&self) -> f32;
    fn as_h(&self) -> f32;
}

impl const DurationOps for Duration {
    fn ms(t: f32) -> Duration {
        t.unchecked_into()
    }
    fn s(t: f32) -> Duration {
        Self::ms(t * 1000.0)
    }
    fn min(t: f32) -> Duration {
        Self::s(t * 60.0)
    }
    fn h(t: f32) -> Duration {
        Self::min(t * 60.0)
    }

    fn as_ms(&self) -> f32 {
        self.unchecked_raw()
    }
    fn as_s(&self) -> f32 {
        self.as_ms() / 1000.0
    }
    fn as_min(&self) -> f32 {
        self.as_s() / 60.0
    }
    fn as_h(&self) -> f32 {
        self.as_min() / 60.0
    }
}

/// Methods of the [`Duration`] unit as extensions for numeric types.
#[allow(missing_docs)]
pub trait DurationNumberOps {
    fn ms(self) -> Duration;
    fn s(self) -> Duration;
}

impl const DurationNumberOps for f32 {
    fn ms(self) -> Duration {
        Duration::ms(self)
    }

    fn s(self) -> Duration {
        Duration::s(self)
    }
}

impl From<std::time::Duration> for Duration {
    fn from(duration: std::time::Duration) -> Self {
        (duration.as_millis() as <DURATION as Variant>::Repr).ms()
    }
}

impl From<Duration> for std::time::Duration {
    fn from(duration: Duration) -> Self {
        std::time::Duration::from_millis(duration.as_ms() as u64)
    }
}
