//! Representation of datatype definitions in the Java typesystem.


// ==============
// === Export ===
// ==============

pub mod bincode;



mod from_meta;
#[cfg(feature = "graphviz")]
mod graphviz;
mod implementation;
pub mod syntax;
pub mod transform;

use crate::data_structures::VecMap;
use derive_more::Index;
use derive_more::IndexMut;
pub use from_meta::from_meta;
pub use implementation::implement as to_syntax;
use std::collections::BTreeMap;



// =====================
// === Java Builtins ===
// =====================

/// Fully-qualified name of Java's `Optional` type.
pub const OPTIONAL: &str = "java.util.Optional";
/// Fully-qualified name of Java's `List` type.
pub const LIST: &str = "java.util.List";
/// Fully-qualified name of Java's `String` type.
pub const STRING: &str = "String";



// ==============================
// === Type Parameterizations ===
// ==============================

/// Globally unique, stable identifier for a `Field`.
pub type FieldId = crate::data_structures::Id<Field>;
/// Identifies a Java class within a `TypeGraph`.
pub type ClassId = crate::data_structures::vecmap::Key<Class>;
/// Identifier for a class whose value hasn't been set yet.
pub type UnboundClassId = crate::data_structures::vecmap::UnboundKey<Class>;



// ======================
// === Datatype Types ===
// ======================

/// A Java class.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct Class {
    /// The name of the class, not including package.
    pub name:      String,
    /// Parameters of a generic class.
    pub params:    Vec<ClassId>,
    /// The parent class, if any.
    pub parent:    Option<ClassId>,
    /// Whether this class is `abstract`.
    pub abstract_: bool,
    /// Whether this class is `sealed`.
    pub sealed:    bool,
    /// The data fields.
    pub fields:    Vec<Field>,
    /// The class's methods.
    pub methods:   Vec<Method>,
    builtin:       bool,
    // Attributes
    discriminants: BTreeMap<usize, ClassId>,
    child_field:   Option<usize>,
}

impl Class {
    /// Create a new "builtin" class.
    pub fn builtin(name: &str, fields: impl IntoIterator<Item = ClassId>) -> Self {
        let params: Vec<_> = fields.into_iter().collect();
        let name = name.to_owned();
        let builtin = true;
        let fields = params.iter().map(|&type_| Field::object("data", type_, true)).collect();
        Class { name, params, builtin, fields, ..Default::default() }
    }

    /// Define a type for Java's `Optional` instantiated with a type.
    pub fn optional(param: ClassId) -> Self {
        Self::builtin(OPTIONAL, Some(param))
    }

    /// Define a type for Java's `List` instantiated with a type.
    pub fn list(param: ClassId) -> Self {
        Self::builtin(LIST, Some(param))
    }

    /// Define a type for Java's `String` instantiated with a type.
    pub fn string() -> Self {
        Self::builtin(STRING, [])
    }

    /// Get a field by name.
    pub fn find_field(&self, name: &str) -> Option<&Field> {
        self.fields.iter().find(|field| field.name == name)
    }
}

/// A method of a class.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Method {
    /// A `Dynamic` method.
    Dynamic(Dynamic),
    /// A literal method implementation.
    Raw(syntax::Method),
}

/// A method that is rendered to syntax on demand.
#[derive(Debug, Copy, Clone, PartialEq, Eq)]
pub enum Dynamic {
    /// A constructor.
    Constructor,
    /// `hashCode` method.
    HashCode,
    /// `equals` method.
    Equals,
    /// `toString` method.
    ToString,
    /// A read-accessor for a field.
    Getter(FieldId),
}

impl From<Dynamic> for Method {
    fn from(method: Dynamic) -> Self {
        Method::Dynamic(method)
    }
}

fn abstract_methods() -> Vec<Method> {
    vec![Dynamic::Constructor.into()]
}

fn standard_methods() -> Vec<Method> {
    vec![
        Dynamic::Constructor.into(),
        Dynamic::HashCode.into(),
        Dynamic::Equals.into(),
        Dynamic::ToString.into(),
    ]
}

/// A data field of a class.
#[derive(Debug, PartialEq, Eq)]
pub struct Field {
    #[allow(missing_docs)]
    pub name: String,
    #[allow(missing_docs)]
    pub data: FieldData,
    id:       FieldId,
}

impl Field {
    /// Create a field referencing a `Class` of a specified type.
    pub fn object(name: impl Into<String>, type_: ClassId, non_null: bool) -> Self {
        let name = name.into();
        let data = FieldData::Object { type_, non_null };
        let id = Default::default();
        Self { name, data, id }
    }

    /// Create a field holding primitive data.
    pub fn primitive(name: impl Into<String>, primitive: Primitive) -> Self {
        let name = name.into();
        let data = FieldData::Primitive(primitive);
        let id = Default::default();
        Self { name, data, id }
    }

    #[allow(missing_docs)]
    pub fn id(&self) -> FieldId {
        self.id
    }
}

/// A field's data contents.
#[derive(Debug, Clone, PartialEq, Eq, Copy, PartialOrd, Ord, Hash)]
pub enum FieldData {
    /// A reference to an object.
    Object {
        #[allow(missing_docs)]
        type_:    ClassId,
        /// If `true`, this field should be subject to null-checking in constructors, and can be
        /// assumed always to be present.
        non_null: bool,
    },
    /// An unboxed primitive.
    Primitive(Primitive),
}

impl FieldData {
    fn fmt_equals(&self, a: &str, b: &str) -> String {
        match self {
            FieldData::Object { .. } => format!("{}.equals({})", a, b),
            FieldData::Primitive(_) => format!("({} == {})", a, b),
        }
    }
}

/// An unboxed type; i.e. a type that is not a subtype of `java.lang.Object`.
#[derive(Debug, Clone, PartialEq, Eq, Copy, PartialOrd, Ord, Hash)]
pub enum Primitive {
    /// Java's `boolean`
    Bool,
    /// Java's `int`
    Int {
        /// If `true`, arithmetic on this value is to be performed with unsigned operations.
        unsigned: bool,
    },
    /// Java's `long`
    Long {
        /// If `true`, arithmetic on this value is to be performed with unsigned operations.
        unsigned: bool,
    },
}



// ============================
// === Systems of Datatypes ===
// ============================

/// A system of Java `Class`es.
#[derive(Debug, Default, Index, IndexMut)]
pub struct TypeGraph {
    #[allow(missing_docs)]
    pub classes: VecMap<Class>,
}


// === GraphViz support ===

#[cfg(feature = "graphviz")]
impl From<&'_ TypeGraph> for crate::graphviz::Graph {
    fn from(graph: &'_ TypeGraph) -> Self {
        graphviz::graph(graph)
    }
}
