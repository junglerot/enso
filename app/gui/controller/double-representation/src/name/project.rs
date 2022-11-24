//! The structures related to the project name in the code.

use crate::prelude::*;

use crate::name::InvalidQualifiedName;
use crate::name::NamePath;
use crate::name::NamePathRef;

use ast::opr::predefined::ACCESS;
use const_format::concatcp;
use enso_prelude::serde_reexports::Deserialize;
use enso_prelude::serde_reexports::Serialize;



// =================
// === Constants ===
// =================

/// The namespace of the standard library.
pub const STANDARD_NAMESPACE: &str = "Standard";

/// The name of the project in the [`STANDARD_NAMESPACE`] containing the base standard library.
pub const BASE_LIBRARY_NAME: &str = "Base";

/// The full path of the [`BASE_LIBRARY_NAME`] project in the [`STANDARD_NAMESPACE`].
pub const STANDARD_BASE_LIBRARY_PATH: &str = concatcp!(STANDARD_NAMESPACE, ".", BASE_LIBRARY_NAME);



// =====================
// === QualifiedName ===
// =====================

/// The project qualified name has a form of `<namespace_name>.<project_name>`. It serves as
/// a prefix for qualified names of other entities (modules, types, etc.).
#[allow(missing_docs)]
#[derive(
    Clone,
    CloneRef,
    Debug,
    Default,
    Deserialize,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
    Serialize
)]
#[serde(into = "String")]
#[serde(try_from = "String")]
pub struct QualifiedName {
    pub namespace: ImString,
    pub project:   ImString,
}

impl QualifiedName {
    /// Create qualified name from components.
    pub fn new(namespace: impl Into<ImString>, project: impl Into<ImString>) -> Self {
        Self { namespace: namespace.into(), project: project.into() }
    }

    /// Create from a text representation. May fail if the text is not valid project Qualified Name.
    pub fn from_text(text: impl AsRef<str>) -> FallibleResult<Self> {
        let source = text.as_ref();
        let all_segments = source.split(ACCESS).collect_vec();
        match all_segments.as_slice() {
            [namespace, project] => Ok(Self::new(namespace, project)),
            [] => Err(InvalidQualifiedName::EmptyName.into()),
            [_] => Err(InvalidQualifiedName::NoNamespace.into()),
            _ => Err(InvalidQualifiedName::TooManySegments.into()),
        }
    }

    /// The iterator over name's segments: the namespace and project name.
    pub fn segments(&self) -> impl Iterator<Item = &ImString> {
        iter::once(&self.namespace).chain(iter::once(&self.project))
    }

    /// Return the fully qualified name of the [`BASE_LIBRARY_NAME`] project in the
    /// [`STANDARD_NAMESPACE`].
    pub fn standard_base_library() -> Self {
        Self::new(STANDARD_NAMESPACE, BASE_LIBRARY_NAME)
    }
}


// === Conversions From and Into String ===

impl TryFrom<&str> for QualifiedName {
    type Error = failure::Error;

    fn try_from(text: &str) -> Result<Self, Self::Error> {
        Self::from_text(text)
    }
}

impl TryFrom<String> for QualifiedName {
    type Error = failure::Error;

    fn try_from(text: String) -> Result<Self, Self::Error> {
        Self::from_text(text)
    }
}

impl From<QualifiedName> for String {
    fn from(name: QualifiedName) -> Self {
        String::from(&name)
    }
}

impl From<&QualifiedName> for String {
    fn from(name: &QualifiedName) -> Self {
        name.to_string()
    }
}

impl Display for QualifiedName {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}.{}", self.namespace, self.project)
    }
}


// === Comparing with NamePath ===

impl<'a> PartialEq<NamePathRef<'a>> for QualifiedName {
    fn eq(&self, other: &NamePathRef<'a>) -> bool {
        match other {
            [first, second] => &self.namespace == first && &self.project == second,
            _ => false,
        }
    }
}

impl PartialEq<NamePath> for QualifiedName {
    fn eq(&self, other: &NamePath) -> bool {
        *self == other.as_slice()
    }
}
