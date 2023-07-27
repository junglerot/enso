//! This module defines utilities for working with the [`std::option::Option`] type.



/// Adds mapping methods to the `Option` type.
pub trait OptionOps {
    type Item;
    fn map_none<F>(self, f: F) -> Self
    where F: FnOnce();
    fn map_ref<'a, U, F>(&'a self, f: F) -> Option<U>
    where F: FnOnce(&'a Self::Item) -> U;
    fn map_or_default<U, F>(self, f: F) -> U
    where
        U: Default,
        F: FnOnce(Self::Item) -> U;
    fn if_some_or_default<U, F>(self, f: F) -> U
    where
        U: Default,
        F: FnOnce() -> U;
    fn map_ref_or_default<'a, U, F>(&'a self, f: F) -> U
    where
        U: Default,
        F: FnOnce(&'a Self::Item) -> U;
    fn for_each<U, F>(self, f: F)
    where F: FnOnce(Self::Item) -> U;
    fn for_each_ref<'a, U, F>(&'a self, f: F)
    where F: FnOnce(&'a Self::Item) -> U;
    /// Returns true if option contains Some with value matching given predicate.
    fn contains_if<'a, F>(&'a self, f: F) -> bool
    where F: FnOnce(&'a Self::Item) -> bool;
}

impl<T> OptionOps for Option<T> {
    type Item = T;

    fn map_none<F>(self, f: F) -> Self
    where
        F: FnOnce(),
        T: Sized, {
        if self.is_none() {
            f()
        }
        self
    }

    fn map_ref<'a, U, F>(&'a self, f: F) -> Option<U>
    where F: FnOnce(&'a Self::Item) -> U {
        self.as_ref().map(f)
    }

    fn map_or_default<U, F>(self, f: F) -> U
    where
        U: Default,
        F: FnOnce(Self::Item) -> U, {
        self.map_or_else(U::default, f)
    }

    fn if_some_or_default<U, F>(self, f: F) -> U
    where
        U: Default,
        F: FnOnce() -> U, {
        self.map_or_else(U::default, |_| f())
    }

    fn map_ref_or_default<'a, U, F>(&'a self, f: F) -> U
    where
        U: Default,
        F: FnOnce(&'a Self::Item) -> U, {
        self.as_ref().map_or_default(f)
    }

    fn for_each<U, F>(self, f: F)
    where F: FnOnce(Self::Item) -> U {
        if let Some(x) = self {
            f(x);
        }
    }

    fn for_each_ref<'a, U, F>(&'a self, f: F)
    where F: FnOnce(&'a Self::Item) -> U {
        if let Some(x) = self {
            f(x);
        }
    }

    fn contains_if<'a, F>(&'a self, f: F) -> bool
    where F: FnOnce(&'a Self::Item) -> bool {
        self.as_ref().map_or(false, f)
    }
}
