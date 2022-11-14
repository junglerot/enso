//! Producing S-expression representations of data based on reflection information.
//!
//! The chosen output format is compact--more so than the pretty-printing supported by `lexpr`
//! (which is what is used by `serde_lexpr` to derive an S-expression "format" for data).
//!
//! - A struct is represented as a list of its fields.
//! - No type names are emitted. For variant types, the discriminant is included before the fields.
//! - Named fields are represented with the structure used for Lisp's `alist`s: `(name . value)`.
//! - Field names are prefixed with ':'.
//! - Sequence types like Rust's `Vec<_>` are represent with `lexpr` `Vector`s: `#(element element)`
//! - An option prints the same way as its contained value in the `Some` case, or as an empty list
//!   `()` in the `None` case.
//!
//! # Example
//!
//! ```
//! # use enso_reflect::prelude::*;
//! # use serde::Serialize;
//! use enso_reflect::Reflect;
//! use lexpr::Value;
//!
//! // A type, and a value. We'd like to see the S-expr representation of the value.
//! #[derive(Serialize, Reflect)]
//! struct A {
//!     value: u32,
//! }
//! let value = A { value: 36 };
//! // Get `meta` type info for the type.
//! let (graph, rust_to_meta) = enso_metamodel::rust::to_meta(value.reflect_type());
//! let value_ty = rust_to_meta[&value.reflect_type().id];
//! // Use the type info to get a representation of an instance's data.
//! let s_expr = enso_metamodel_lexpr::ToSExpr::new(&graph).value(value_ty, &value);
//! let field_expr = Value::cons(Value::symbol(":value"), Value::Number(36.into()));
//! assert_eq!(s_expr, Value::cons(field_expr, Value::Null));
//! ```

// === Features ===
#![feature(let_chains)]
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

use enso_metamodel::meta::*;

use derivative::Derivative;
use lexpr::Value;
use std::collections::BTreeMap;
use std::collections::BTreeSet;



// =============================
// === Meta to S-expressions ===
// =============================

/// Render data to an S-expression representation based on its `meta` model.
#[derive(Derivative)]
#[derivative(Debug)]
pub struct ToSExpr<'g> {
    graph:   &'g TypeGraph,
    #[derivative(Debug = "ignore")]
    mappers: BTreeMap<TypeId, Box<dyn Fn(Value) -> Value>>,
    skip:    BTreeSet<TypeId>,
}

impl<'g> ToSExpr<'g> {
    #[allow(missing_docs)]
    pub fn new(graph: &'g TypeGraph) -> Self {
        let mappers = Default::default();
        let skip = Default::default();
        Self { graph, mappers, skip }
    }

    /// Set a transformation to be applied to a type after translating to an S-expression.
    pub fn mapper(&mut self, id: TypeId, f: impl Fn(Value) -> Value + 'static) {
        self.mappers.insert(id, Box::new(f));
    }

    /// Omit a type, specified by ID, from the output, wherever it occurs. If it occurs as a field
    /// in another struct, that field will be omitted. If the type occurs as a variant of an enum,
    /// or as the top-level type passed to [`Self::value`], it will be represented as if it had no
    /// fields.
    pub fn skip(&mut self, id: TypeId) {
        self.skip.insert(id);
    }

    /// Given a bincode-serialized input, use its `meta` type info to transcribe it to an
    /// S-expression.
    pub fn value<T: serde::Serialize>(&self, id: TypeId, input: &T) -> Value {
        use bincode::Options;
        let bincoder = bincode::DefaultOptions::new().with_fixint_encoding();
        let data = bincoder.serialize(input).unwrap();
        let mut data = &data[..];
        let value = self.value_(id, &mut data);
        assert_eq!(data, &[0; 0], "{}", value);
        value
    }
}


// === Implementation ===

impl<'g> ToSExpr<'g> {
    fn value_(&self, id: TypeId, data: &mut &[u8]) -> Value {
        match &self.graph[id].data {
            Data::Struct(_) => self.struct_(id, data),
            Data::Primitive(primitive) => self.primitive(*primitive, data),
        }
    }

    fn struct_(&self, id: TypeId, data: &mut &[u8]) -> Value {
        let mut out = vec![];
        let hierarchy = self.graph.hierarchy(id);
        let mut child = None;
        for id in hierarchy.iter().rev() {
            let ty = &self.graph[id];
            let mut fields = ty.data.as_struct().unwrap();
            if let Some(i) = ty.child_field {
                fields = &fields[..i];
            }
            out.extend(fields.iter().filter_map(|field| self.field(field, data)));
        }
        let discriminants = &self.graph[id].discriminants;
        if !discriminants.is_empty() {
            let discriminant_index = read_u32(data);
            let id = discriminants[&(discriminant_index as usize)];
            let fields = self.graph[id].data.as_struct().unwrap();
            out.extend(fields.iter().filter_map(|field| self.field(field, data)));
            child = Some(id);
        }
        for id in hierarchy {
            let ty = &self.graph[id];
            if let Some(i) = ty.child_field {
                let mut fields = ty.data.as_struct().unwrap();
                fields = &fields[i..];
                out.extend(fields.iter().filter_map(|field| self.field(field, data)));
            }
        }
        let mut value = match self.skip.contains(&id) {
            true => Value::Null,
            false => Value::list(out),
        };
        if let Some(id) = child {
            if let Some(mapper) = self.mappers.get(&id) {
                value = (mapper)(value);
                if !value.is_cons() {
                    value = Value::cons(value, Value::Null);
                }
            };
            let discriminant = self.graph[id].name.to_pascal_case().into_boxed_str();
            let discriminant = Value::Symbol(discriminant);
            value = Value::cons(discriminant, value);
        }
        if let Some(mapper) = self.mappers.get(&id) {
            value = (mapper)(value);
        }
        value
    }

    fn field(&self, field: &Field, data: &mut &[u8]) -> Option<Value> {
        let value = self.value_(field.type_, data);
        if self.skip.contains(&field.type_) {
            return None;
        }
        if let Data::Primitive(Primitive::Option(t0)) = &self.graph[field.type_].data
                && self.skip.contains(t0) {
            return None;
        }
        Some(if field.name.is_empty() {
            value
        } else {
            let car = Value::Symbol(format!(":{}", field.name).into_boxed_str());
            Value::cons(car, value)
        })
    }

    fn primitive(&self, primitive: Primitive, data: &mut &[u8]) -> Value {
        match primitive {
            Primitive::U32 => Value::Number(read_u32(data).into()),
            Primitive::I32 => Value::Number((read_u32(data) as i32).into()),
            Primitive::Char => {
                let n = read_u32(data);
                match char::try_from(n) {
                    Ok(c) => Value::Char(c),
                    Err(_) => Value::Null,
                }
            }
            Primitive::U64 => Value::Number(read_u64(data).into()),
            Primitive::I64 => Value::Number((read_u64(data) as i64).into()),
            Primitive::Bool => {
                let value = read_u8(data);
                let value = match value {
                    0 => false,
                    1 => true,
                    _ => panic!(),
                };
                Value::Bool(value)
            }
            Primitive::String => Value::String(read_string(data).into()),
            Primitive::Sequence(t0) => {
                let len = read_u64(data);
                Value::vector((0..len).map(|_| self.value_(t0, data)))
            }
            Primitive::Option(t0) => match read_u8(data) {
                0 => Value::Null,
                1 => self.value_(t0, data),
                _ => panic!(),
            },
            Primitive::Result(t0, t1) => {
                let mut values = vec![];
                match read_u32(data) {
                    0 => {
                        values.push(Value::Symbol("Ok".to_owned().into_boxed_str()));
                        values.push(self.value_(t0, data));
                    }
                    1 => {
                        values.push(Value::Symbol("Err".to_owned().into_boxed_str()));
                        values.push(self.value_(t1, data));
                    }
                    _ => panic!(),
                }
                Value::list(values)
            }
        }
    }
}


// === Primitive Deserializers ===

fn read_u8(buffer: &mut &[u8]) -> u8 {
    let (bytes, rest) = buffer.split_at(1);
    *buffer = rest;
    bytes[0]
}

fn read_u32(buffer: &mut &[u8]) -> u32 {
    let (bytes, rest) = buffer.split_at(4);
    *buffer = rest;
    let mut data = [0; 4];
    data.copy_from_slice(bytes);
    u32::from_le_bytes(data)
}

fn read_u64(buffer: &mut &[u8]) -> u64 {
    let (bytes, rest) = buffer.split_at(8);
    *buffer = rest;
    let mut data = [0; 8];
    data.copy_from_slice(bytes);
    u64::from_le_bytes(data)
}

fn read_string(buffer: &mut &[u8]) -> String {
    let len = read_u64(buffer);
    let (bytes, rest) = buffer.split_at(len as usize);
    *buffer = rest;
    String::from_utf8(bytes.to_owned()).unwrap()
}



// =============
// === Tests ===
// =============

#[cfg(test)]
mod test {
    use super::*;

    #[test]
    fn unit_test() {
        #[derive(serde::Serialize)]
        struct A {
            value: u32,
        }
        let mut graph = TypeGraph::new();
        let int_name = TypeName::from_pascal_case("U32");
        let int = Type::new(int_name, Data::Primitive(Primitive::U32));
        let int = graph.types.insert(int);
        let a_name = TypeName::from_pascal_case("A");
        let a_field_name = FieldName::from_snake_case("value");
        let a_field = Field::named(a_field_name, int);
        let a = Type::new(a_name, Data::Struct(vec![a_field]));
        let a = graph.types.insert(a);
        let a_value = A { value: 36 };
        let s_expr = ToSExpr::new(&graph).value(a, &a_value);
        let field_expr = Value::cons(Value::symbol(":value"), Value::Number(36.into()));
        assert_eq!(s_expr, Value::cons(field_expr, Value::Null));
    }
}
