//! Definition of font, font face, and font registry. Aggregates information and utilities for
//! working with fonts.

use crate::prelude::*;

use ensogl_core::display::scene;
use ensogl_core::display::world::Context;
use ensogl_core::system::gpu;
use ensogl_core::system::gpu::texture;
use ensogl_text_msdf as msdf;
use ordered_float::NotNan;
use owned_ttf_parser as ttf;
use ttf::AsFaceRef;


// ==============
// === Export ===
// ==============

pub mod glyph;
pub mod glyph_render_info;

pub use enso_font as family;
pub use family::Name;
pub use family::NonVariableFaceHeader;
pub use glyph_render_info::GlyphRenderInfo;
pub use ttf::GlyphId;
pub use ttf::Style;
pub use ttf::Tag;
pub use ttf::Weight;
pub use ttf::Width;



// =================
// === Constants ===
// =================

/// TTF files can contain multiple face definitions. We support only the first defined, just as
/// most web browsers (you cannot define `@font-face` in CSS for multiple faces of the same file).
const TTF_FONT_FACE_INDEX: u32 = 0;

/// The name of the default font family for general text.
pub const DEFAULT_FONT: &str = "mplus1p";

/// The name of the default font family for code.
pub const DEFAULT_CODE_FONT: &str = "enso";



// =====================
// === VariationAxis ===
// =====================

/// A variation axis of variable fonts. The axis name is [`Tag`], which is a 4-bytes identifier
/// constructed from the axis name, e.g. by `Tag::from_bytes(b"ital")`. See the following link to
/// learn more:
/// https://docs.microsoft.com/en-us/typography/opentype/spec/dvaraxisreg#registered-axis-tags
#[allow(missing_docs)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct VariationAxis {
    tag:   Tag,
    value: NotNan<f32>,
}

impl VariationAxis {
    /// Constructor
    pub fn new(tag: Tag, value: NotNan<f32>) -> Self {
        Self { tag, value }
    }

    /// Constructor.
    pub fn from_bytes(bytes: &[u8; 4], value: NotNan<f32>) -> Self {
        let tag = Tag::from_bytes(bytes);
        Self { tag, value }
    }
}



// =====================
// === VariationAxes ===
// =====================

/// Variation axes of variable fonts.
#[allow(missing_docs)]
#[derive(Debug, Default, Clone, PartialEq, Eq, Hash)]
pub struct VariationAxes {
    pub vec: Vec<VariationAxis>,
}

impl VariationAxes {
    /// Map a function over all standard axes. Not all fonts have to support them, but it is a good
    /// idea to set these values when loading a font. Otherwise, some fonts might not be visible
    /// on the screen, as for example their width might default to zero.
    pub fn with_default_axes_values(f: impl Fn(VariationAxis)) {
        let mut axes = Self::default();
        axes.set_weight(Weight::Normal);
        axes.set_width(Width::Normal);
        axes.set_style(Style::Normal);
        axes.with_axes(f);
    }

    /// Map a function over all changed axes.
    pub fn with_axes(&self, f: impl Fn(VariationAxis)) {
        for axis in &self.vec {
            f(*axis);
        }
    }

    /// Variation axis setter.
    pub fn set(&mut self, axis: VariationAxis) {
        if let Some(index) = self.vec.iter().position(|a| a.tag == axis.tag) {
            self.vec[index] = axis;
        } else {
            self.vec.push(axis);
        }
    }

    /// Variation axis setter. “Italic” (`ital` in CSS) is an axis found in some variable fonts. It
    /// controls the font file’s italic parameter, with italics either turned “off” or “on”, rather
    /// than gradually changing over a range. The Google Fonts CSS v2 API defines the axis as:
    /// Default: 0   Min: 0   Max: 1   Step: 0.1
    /// https://fonts.google.com/knowledge/glossary/italic_axis
    pub fn set_ital(&mut self, value: NotNan<f32>) {
        self.set(VariationAxis::from_bytes(b"ital", value));
    }

    /// Variation axis setter. “Optical Size” (controlled with `font-optical-sizing` or
    /// `font-variation-setting`: ‘opsz’ VALUE in CSS) is an axis found in some variable fonts. It
    /// controls the font file’s optical size optimizations. The Google Fonts CSS v2 API defines the
    /// axis as:
    /// Default: 14   Min: 6   Max: 144   Step: 0.1
    /// https://fonts.google.com/knowledge/glossary/optical_size_axis
    pub fn set_opsz(&mut self, value: NotNan<f32>) {
        self.set(VariationAxis::from_bytes(b"opsz", value));
    }

    /// Variation axis setter. Slant (`slnt` in CSS) is an axis found in some variable fonts. It
    /// controls the font file’s slant parameter for oblique styles. The Google Fonts CSS v2 API
    /// defines the axis as:
    /// Default: 0   Min: -90   Max: 90   Step: 1
    /// https://fonts.google.com/knowledge/glossary/slant_axis
    pub fn set_slnt(&mut self, value: NotNan<f32>) {
        self.set(VariationAxis::from_bytes(b"slnt", value));
    }

    /// Variation axis setter. “Weight” (`wght` in CSS) is an axis found in many variable fonts. It
    /// controls the font file’s weight parameter. The Google Fonts CSS v2 API defines the axis as:
    /// Default: 400   Min: 1   Max: 1000   Step: 1
    /// https://fonts.google.com/knowledge/glossary/weight_axis
    pub fn set_wght(&mut self, value: NotNan<f32>) {
        self.set(VariationAxis::from_bytes(b"wght", value));
    }

    /// Variation axis setter. “Width” (`wdth` in CSS) is an axis found in some variable fonts. It
    /// controls the font file’s width parameter. The Google Fonts CSS v2 API defines the axis as:
    /// Default: 100   Min: 25   Max: 200   Step: 0.1
    /// https://fonts.google.com/knowledge/glossary/width_axis
    pub fn set_wdth(&mut self, value: NotNan<f32>) {
        self.set(VariationAxis::from_bytes(b"wdth", value));
    }

    /// Weight setter.
    pub fn set_weight(&mut self, value: Weight) {
        self.set_wght(value.to_number().into());
    }

    /// Width setter.
    pub fn set_width(&mut self, value: Width) {
        let wdth = match value {
            Width::UltraCondensed => 25.0,
            Width::ExtraCondensed => 43.75,
            Width::Condensed => 62.5,
            Width::SemiCondensed => 81.25,
            Width::Normal => 100.0,
            Width::SemiExpanded => 118.75,
            Width::Expanded => 137.5,
            Width::ExtraExpanded => 156.25,
            Width::UltraExpanded => 175.0,
        };
        self.set_wdth(NotNan::new(wdth).unwrap());
    }

    /// Style setter.
    pub fn set_style(&mut self, value: Style) {
        match value {
            Style::Normal => {
                self.set_ital(0_u16.into());
                self.set_slnt(0_u16.into());
            }
            Style::Italic => {
                self.set_ital(1_u16.into());
                self.set_slnt(0_u16.into());
            }
            Style::Oblique => {
                self.set_ital(0_u16.into());
                self.set_slnt(90_u16.into());
            }
        }
    }
}



// ============
// === Face ===
// ============

/// A face of a font. In case of non-variable fonts, a face corresponds to a font variation defined
/// as a triple (width, weight, style), see [`NonVariableFaceHeader`]. In case of variable fonts,
/// the font variation ([`VariationAxes`]) is set up at runtime, so only one face is needed.
///
/// The face consists of a [ttf face](ttf::OwnedFace) and [MSDF one](msdf::OwnedFace). The former
/// contains all information needed to layout glyphs, like kerning. The latter is used to generate
/// MSDF textures for glyphs.
#[allow(missing_docs)]
#[derive(Debug)]
pub struct Face {
    pub msdf: msdf::OwnedFace,
    pub ttf:  ttf::OwnedFace,
}



// ==============
// === Family ===
// ==============

/// A generalization of a font family, a set of font faces. Allows borrowing a font face based on
/// variations.
pub trait Family {
    /// For non-variable fonts, variations is a triple (width, weight, style), see
    /// [`NonVariableFaceHeader`] to learn more. For variable faces, the variation is
    /// [`VariationAxes`], however, as variable fonts have one face only, this parameter is not
    /// used while borrowing the face.
    type Variations: Eq + Hash + Clone + Debug;

    /// Update MSDFgen settings for given variations. For non-variable fonts, this function is a
    /// no-op.
    fn update_msdfgen_variations(&self, variations: &Self::Variations);
    /// Run the function with borrowed font face for the given variations set. For non-variable
    /// fonts, the function will not be run if variations do not match a known definition. For
    /// variable fonts, the function always succeeds.
    fn with_borrowed_face<F, T>(&self, variations: &Self::Variations, f: F) -> Option<T>
    where F: for<'a> FnOnce(&'a Face) -> T;

    /// Get the closest font face header for the given font face header. Not all font face headers
    /// can be defined in a font family. For example, a font family may not contain a face with both
    /// bold and italic glyphs. In such a case, this function will return the bold glyphs face.
    fn closest_non_variable_variations(
        &self,
        variations: NonVariableFaceHeader,
    ) -> Option<NonVariableFaceHeaderMatch>;

    /// Just like [`closest_non_variable_variations`], but panics if the face does not contain any
    /// styles.
    fn closest_non_variable_variations_or_panic(
        &self,
        variations: NonVariableFaceHeader,
    ) -> NonVariableFaceHeaderMatch;
}

/// A non-variable font family.
#[allow(missing_docs)]
#[derive(Debug)]
pub struct NonVariableFamily {
    pub definition: family::NonVariableDefinition,
    pub faces:      RefCell<HashMap<NonVariableFaceHeader, Face>>,
}

/// A variable font family. Contains font family definition and the font face. The face is kept in
/// an `Option` because it is created after the family initialization. Currently, it could be
/// simplified, but it is already designed in this way to support on-demand face loading (served
/// from server when needed).
#[allow(missing_docs)]
#[derive(Debug)]
pub struct VariableFamily {
    pub definition: family::VariableDefinition,
    pub face:       RefCell<Option<Face>>,
    /// Most recent axes used to generate MSDF textures. If axes change, MSDFgen parameters need to
    /// be updated, which involves a non-zero cost (mostly due to Rust <> JS interop). Thus, we
    /// want to refresh them only when needed. This field is a cache allowing us to check if
    /// axes changed.
    pub last_axes:  RefCell<Option<VariationAxes>>,
}

impl NonVariableFamily {
    /// Load all font faces from the embedded font data. Corrupted faces will be reported and
    /// ignored.
    fn load_all_faces(&mut self, embedded: &EmbeddedData) {
        for variation in self.definition.variations() {
            if let Some(face) = embedded.load_face(variation.file) {
                self.faces.get_mut().insert(variation.header, face);
            }
        }
    }

    fn closest_variations(
        &self,
        variation: NonVariableFaceHeader,
    ) -> Option<NonVariableFaceHeaderMatch> {
        let faces = self.faces.borrow();
        if faces.contains_key(&variation) {
            Some(NonVariableFaceHeaderMatch::exact(variation))
        } else {
            let mut closest = None;
            let mut closest_distance = usize::MAX;
            for known_header in faces.keys() {
                let distance = similarity_distance(known_header, &variation);
                if distance < closest_distance {
                    closest_distance = distance;
                    closest = Some(NonVariableFaceHeaderMatch::closest(*known_header));
                }
            }
            closest
        }
    }

    fn closest_variations_or_panic(
        &self,
        variation: NonVariableFaceHeader,
    ) -> NonVariableFaceHeaderMatch {
        self.closest_variations(variation)
            .unwrap_or_else(|| panic!("Trying to use a font with no faces registered."))
    }
}

impl VariableFamily {
    /// Load all font faces from the embedded font data. Corrupted faces will be reported and
    /// ignored.
    fn load_all_faces(&mut self, embedded: &EmbeddedData) {
        if let Some(face) = embedded.load_face(&self.definition.file_name) {
            // Set default variation axes during face initialization. This is needed to make some
            // fonts appear on the screen. In case some axes are not found, warnings will be
            // silenced.
            VariationAxes::with_default_axes_values(|axis| {
                face.msdf.set_variation_axis(axis.tag, axis.value.into_inner() as f64).ok();
            });
            *self.face.get_mut() = Some(face);
        }
    }
}

impl Family for NonVariableFamily {
    type Variations = NonVariableFaceHeader;
    fn update_msdfgen_variations(&self, _variations: &Self::Variations) {}
    fn with_borrowed_face<F, T>(&self, variations: &Self::Variations, f: F) -> Option<T>
    where F: for<'a> FnOnce(&'a Face) -> T {
        self.faces.borrow().get(variations).map(f)
    }

    fn closest_non_variable_variations(
        &self,
        variations: NonVariableFaceHeader,
    ) -> Option<NonVariableFaceHeaderMatch> {
        self.closest_variations(variations)
    }

    fn closest_non_variable_variations_or_panic(
        &self,
        variations: NonVariableFaceHeader,
    ) -> NonVariableFaceHeaderMatch {
        self.closest_variations_or_panic(variations)
    }
}

impl Family for VariableFamily {
    type Variations = VariationAxes;
    #[profile(Debug)]
    fn update_msdfgen_variations(&self, variations: &Self::Variations) {
        if let Some(face) = self.face.borrow().as_ref() {
            if self.last_axes.borrow().as_ref() != Some(variations) {
                self.last_axes.borrow_mut().replace(variations.clone());
                variations.with_axes(|axis| {
                    let value = axis.value.into_inner() as f64;
                    face.msdf
                        .set_variation_axis(axis.tag, value)
                        .map_err(|err| {
                            warn!("Error setting font variation axis: {}", err);
                        })
                        .ok();
                });
            }
        }
    }

    fn with_borrowed_face<F, T>(&self, _variations: &Self::Variations, f: F) -> Option<T>
    where F: for<'a> FnOnce(&'a Face) -> T {
        self.face.borrow().as_ref().map(f)
    }

    fn closest_non_variable_variations(
        &self,
        variations: NonVariableFaceHeader,
    ) -> Option<NonVariableFaceHeaderMatch> {
        // Variable fonts do not depend on non-variable variations.
        Some(NonVariableFaceHeaderMatch::exact(variations))
    }

    fn closest_non_variable_variations_or_panic(
        &self,
        variations: NonVariableFaceHeader,
    ) -> NonVariableFaceHeaderMatch {
        // Variable fonts do not depend on non-variable variations.
        NonVariableFaceHeaderMatch::exact(variations)
    }
}

impl From<&family::VariableDefinition> for VariableFamily {
    fn from(definition: &family::VariableDefinition) -> Self {
        let definition = definition.clone();
        Self { definition, face: default(), last_axes: default() }
    }
}

impl From<&family::NonVariableDefinition> for NonVariableFamily {
    fn from(definition: &family::NonVariableDefinition) -> Self {
        let definition = definition.clone();
        Self { definition, faces: default() }
    }
}



// ========================================
// === NonVariableFaceHeader comparison ===
// ========================================

/// Distance between two font variations. It is used to find the closest variations if the
/// provided is not available.
fn similarity_distance(this: &NonVariableFaceHeader, other: &NonVariableFaceHeader) -> usize {
    let width_weight = 10;
    let weight_weight = 100;
    let style_weight = 1;

    let self_width = this.width.to_number() as usize;
    let self_weight = this.weight.to_number() as usize;
    let self_style: usize = match this.style {
        Style::Normal => 0,
        Style::Italic => 1,
        Style::Oblique => 2,
    };

    let other_width = other.width.to_number() as usize;
    let other_weight = other.weight.to_number() as usize;
    let other_style: usize = match other.style {
        Style::Normal => 0,
        Style::Italic => 1,
        Style::Oblique => 2,
    };

    let width = self_width.abs_diff(other_width) * width_weight;
    let weight = self_weight.abs_diff(other_weight) * weight_weight;
    let style = self_style.abs_diff(other_style) * style_weight;
    width + weight + style
}

/// Indicates whether the provided variation was an exact match or a closest match was found.
#[derive(Debug, Copy, Clone, PartialEq, Eq)]
#[allow(missing_docs)]
pub enum NonVariableFaceHeaderMatchType {
    Exact,
    Closest,
}

/// Result of finding a closest font variation for a non-variable font family.
#[derive(Debug, Copy, Clone)]
#[allow(missing_docs)]
pub struct NonVariableFaceHeaderMatch {
    pub variations: NonVariableFaceHeader,
    pub match_type: NonVariableFaceHeaderMatchType,
}

impl NonVariableFaceHeaderMatch {
    /// Constructor.
    pub fn exact(variations: NonVariableFaceHeader) -> Self {
        Self { variations, match_type: NonVariableFaceHeaderMatchType::Exact }
    }

    /// Constructor.
    pub fn closest(variations: NonVariableFaceHeader) -> Self {
        Self { variations, match_type: NonVariableFaceHeaderMatchType::Closest }
    }

    /// Checks whether the match was exact.
    pub fn was_exact(&self) -> bool {
        self.match_type == NonVariableFaceHeaderMatchType::Exact
    }

    /// Checks whether the match was closest.
    pub fn was_closest(&self) -> bool {
        self.match_type == NonVariableFaceHeaderMatchType::Closest
    }
}



// ============
// === Font ===
// ============

/// A typeface, commonly referred to as a font.
#[allow(missing_docs)]
#[derive(Debug, Clone, CloneRef, From)]
pub enum Font {
    NonVariable(NonVariableFont),
    Variable(VariableFont),
}

/// A non-variable version of [`Font`].
pub type NonVariableFont = FontTemplate<NonVariableFamily>;

/// A variable version of [`Font`].
pub type VariableFont = FontTemplate<VariableFamily>;

impl Font {
    /// Font family name getter.
    pub fn name(&self) -> &Name {
        match self {
            Font::NonVariable(font) => &font.name,
            Font::Variable(font) => &font.name,
        }
    }

    /// List all possible weights. In case of variable fonts, [`None`] will be returned.
    pub fn possible_weights(&self) -> Option<Vec<Weight>> {
        match self {
            Font::NonVariable(font) => Some(font.family.definition.possible_weights()),
            Font::Variable(_) => None,
        }
    }

    /// Get render info for the provided glyph, generating one if not found.
    pub fn glyph_info(
        &self,
        non_variable_font_variations: NonVariableFaceHeader,
        variable_font_variations: &VariationAxes,
        glyph_id: GlyphId,
    ) -> Option<GlyphRenderInfo> {
        match self {
            Font::NonVariable(font) => font.glyph_info(&non_variable_font_variations, glyph_id),
            Font::Variable(font) => font.glyph_info(variable_font_variations, glyph_id),
        }
    }

    /// Get render info for the provided glyph, generating one if not found.
    pub fn glyph_info_of_known_face(
        &self,
        non_variable_font_variations: NonVariableFaceHeader,
        variable_font_variations: &VariationAxes,
        glyph_id: GlyphId,
        face: &Face,
    ) -> GlyphRenderInfo {
        match self {
            Font::NonVariable(font) =>
                font.glyph_info_of_known_face(&non_variable_font_variations, glyph_id, face),
            Font::Variable(font) =>
                font.glyph_info_of_known_face(variable_font_variations, glyph_id, face),
        }
    }

    /// Get the closest font-face header to the provided one.
    pub fn closest_non_variable_variations(
        &self,
        variations: NonVariableFaceHeader,
    ) -> Option<NonVariableFaceHeaderMatch> {
        match self {
            Font::NonVariable(font) => font.family.closest_non_variable_variations(variations),
            Font::Variable(font) => font.family.closest_non_variable_variations(variations),
        }
    }

    /// Get the closest font-face header to the provided one. Panics if the face does not define
    /// any styles.
    pub fn closest_non_variable_variations_or_panic(
        &self,
        variations: NonVariableFaceHeader,
    ) -> NonVariableFaceHeaderMatch {
        match self {
            Font::NonVariable(font) =>
                font.family.closest_non_variable_variations_or_panic(variations),
            Font::Variable(font) =>
                font.family.closest_non_variable_variations_or_panic(variations),
        }
    }

    /// Get the font MSDF atlas texture.
    pub fn msdf_texture(&self) -> &msdf::Texture {
        match self {
            Font::NonVariable(font) => &font.atlas,
            Font::Variable(font) => &font.atlas,
        }
    }

    /// A whole MSDF texture bound for this font.
    pub fn with_borrowed_msdf_texture_data<R>(&self, operation: impl FnOnce(&[u8]) -> R) -> R {
        match self {
            Font::NonVariable(font) => font.with_borrowed_msdf_texture_data(operation),
            Font::Variable(font) => font.with_borrowed_msdf_texture_data(operation),
        }
    }

    // FIXME: remove?
    /// Get kerning between two characters.
    pub fn kerning(
        &self,
        non_variable_font_variations: NonVariableFaceHeader,
        variable_font_variations: &VariationAxes,
        left: GlyphId,
        right: GlyphId,
    ) -> f32 {
        match self {
            Font::NonVariable(font) => font.kerning(&non_variable_font_variations, left, right),
            Font::Variable(font) => font.kerning(variable_font_variations, left, right),
        }
    }

    /// Perform the provided closure with a borrowed font face.
    pub fn with_borrowed_face<F, T>(
        &self,
        non_variable_font_variations: NonVariableFaceHeader,
        f: F,
    ) -> Option<T>
    where
        F: for<'a> FnOnce(&'a Face) -> T,
    {
        match self {
            Font::NonVariable(font) =>
                font.family.with_borrowed_face(&non_variable_font_variations, f),
            Font::Variable(font) => font.family.with_borrowed_face(&default(), f),
        }
    }

    /// Return the base set of feature settings to be used when rendering this font in EnsoGL.
    pub fn feature_settings(&self) -> &[rustybuzz::Feature] {
        match self {
            Font::NonVariable(font) => &font.features,
            Font::Variable(font) => &font.features,
        }
    }
}



// ====================
// === FontTemplate ===
// ====================

/// Internal representation of [`Font`]. It contains references to the font family definition,
/// a texture with MSDF-encoded glyph shapes, and a cache for common glyph properties, used to
/// layout glyphs.
#[derive(Deref, Derivative, CloneRef, Debug)]
#[derivative(Clone(bound = ""))]
pub struct FontTemplate<F: Family> {
    rc: Rc<FontTemplateData<F>>,
}

/// Internal representation of [`FontTemplate`].
#[derive(Debug)]
#[allow(missing_docs)]
pub struct FontTemplateData<F: Family> {
    pub name:     Name,
    pub family:   F,
    pub features: Vec<rustybuzz::Feature>,
    pub atlas:    msdf::Texture,
    pub cache:    RefCell<HashMap<F::Variations, FontDataCache>>,
}

/// A cache for common glyph properties, used to layout glyphs.
#[derive(Debug, Default)]
pub struct FontDataCache {
    kerning: HashMap<(GlyphId, GlyphId), f32>,
    glyphs:  HashMap<GlyphId, GlyphRenderInfo>,
}

impl<F: Family> From<FontTemplateData<F>> for FontTemplate<F> {
    fn from(t: FontTemplateData<F>) -> Self {
        let rc = Rc::new(t);
        Self { rc }
    }
}

impl<F: Family> FontTemplate<F> {
    /// Constructor.
    pub fn new(name: Name, family: impl Into<F>, features: Vec<rustybuzz::Feature>) -> Self {
        let atlas = default();
        let cache = default();
        let family = family.into();
        let data = FontTemplateData { name, family, features, atlas, cache };
        Self { rc: Rc::new(data) }
    }

    /// Get render info for one character, generating one if not found.
    pub fn glyph_info(
        &self,
        variations: &F::Variations,
        glyph_id: GlyphId,
    ) -> Option<GlyphRenderInfo> {
        let opt_render_info =
            self.cache.borrow().get(variations).and_then(|t| t.glyphs.get(&glyph_id)).copied();
        if opt_render_info.is_some() {
            opt_render_info
        } else {
            self.family.with_borrowed_face(variations, |face| {
                self.non_cached_glyph_info_of_known_face(variations, glyph_id, face)
            })
        }
    }

    /// Get render info for one character, generating one if not found.
    pub fn glyph_info_of_known_face(
        &self,
        variations: &F::Variations,
        glyph_id: GlyphId,
        face: &Face,
    ) -> GlyphRenderInfo {
        let opt_render_info =
            self.cache.borrow().get(variations).and_then(|t| t.glyphs.get(&glyph_id)).copied();
        if let Some(render_info) = opt_render_info {
            render_info
        } else {
            self.non_cached_glyph_info_of_known_face(variations, glyph_id, face)
        }
    }

    fn non_cached_glyph_info_of_known_face(
        &self,
        variations: &F::Variations,
        glyph_id: GlyphId,
        face: &Face,
    ) -> GlyphRenderInfo {
        log_miss(GlyphCacheMiss {
            face:       self.name.normalized.clone(),
            variations: format!("{variations:?}"),
            glyph_id:   glyph_id.0,
        });
        self.family.update_msdfgen_variations(variations);
        let render_info = GlyphRenderInfo::load(&face.msdf, glyph_id, &self.atlas);
        if !self.cache.borrow().contains_key(variations) {
            self.cache.borrow_mut().insert(variations.clone(), default());
        }
        let mut borrowed_cache = self.cache.borrow_mut();
        let font_data_cache = borrowed_cache.get_mut(variations).unwrap();
        font_data_cache.glyphs.insert(glyph_id, render_info);
        render_info
    }

    /// Get kerning between two characters.
    pub fn kerning(&self, variations: &F::Variations, left: GlyphId, right: GlyphId) -> f32 {
        self.family
            .with_borrowed_face(variations, |face| {
                if !self.cache.borrow().contains_key(variations) {
                    self.cache.borrow_mut().insert(variations.clone(), default());
                }
                let mut borrowed_cache = self.cache.borrow_mut();
                let font_data_cache = borrowed_cache.get_mut(variations).unwrap();
                let calculate_kerning = || {
                    let _profiler = profiler::start_debug!("calculate_kerning");
                    let tables = face.ttf.as_face_ref().tables();
                    let units_per_em = tables.head.units_per_em;
                    let kern_table = tables.kern.and_then(|t| t.subtables.into_iter().next());
                    let kerning = kern_table.and_then(|t| t.glyphs_kerning(left, right));
                    kerning.unwrap_or_default() as f32 / units_per_em as f32
                };
                *font_data_cache.kerning.entry((left, right)).or_insert_with(calculate_kerning)
            })
            .unwrap_or_default()
    }

    /// A whole MSDF texture bound for this font.
    pub fn with_borrowed_msdf_texture_data<R>(&self, operation: impl FnOnce(&[u8]) -> R) -> R {
        self.atlas.with_borrowed_data(operation)
    }
}



// ===============
// === Caching ===
// ===============

thread_local! {
    /// Atlases loaded at application startup.
    pub static PREBUILT_ATLASES: RefCell<HashMap<Name, Rc<CacheSnapshot>>> = default();
}

/// Cached rendering information for a font.
#[derive(Debug)]
pub struct CacheSnapshot {
    /// The MSDF atlas pixel data.
    pub atlas:  enso_bitmap::Image,
    /// Index of glyphs found in [`atlas`].
    pub glyphs: String,
}

impl FontTemplate<NonVariableFamily> {
    /// Return the current glyph cache data.
    pub fn cache_snapshot(&self) -> CacheSnapshot {
        let atlas = self.atlas.to_image();
        let cache: HashMap<String, _> = self
            .cache
            .borrow()
            .iter()
            .map(|(variation, info)| {
                let glyphs: HashMap<String, GlyphRenderInfo> =
                    info.glyphs.iter().map(|(id, data)| (id.0.to_string(), *data)).collect();
                (serialize_variation(variation), glyphs)
            })
            .collect();
        let glyphs = serde_json::to_string(&cache);
        // Serialization can only fail if the types are not serializable to JSON, so this will
        // either succeed consistently or fail consistently. [`unwrap`] it so if it gets broken,
        // we'll catch it.
        let glyphs = glyphs.unwrap();
        CacheSnapshot { atlas, glyphs }
    }

    /// Populate the cache with the given data.
    #[profile(Debug)]
    pub fn load_cache(&self, snapshot: &CacheSnapshot) -> anyhow::Result<()> {
        self.atlas.set_data(snapshot.atlas.clone());
        let cache: HashMap<String, HashMap<String, GlyphRenderInfo>> =
            serde_json::from_str(&snapshot.glyphs)?;
        *self.cache.borrow_mut() = cache
            .into_iter()
            .map(|(variation, info)| {
                let kerning = default();
                let glyphs = info
                    .into_iter()
                    .map(|(id, data)| Ok((GlyphId(id.parse()?), data)))
                    .collect::<anyhow::Result<_>>()?;
                Ok((deserialize_variation(&variation)?, FontDataCache { kerning, glyphs }))
            })
            .collect::<anyhow::Result<_>>()?;
        Ok(())
    }

    /// Load the glyphs for the given text into the cache.
    pub fn prepare_glyphs_for_text(
        &self,
        variations: &NonVariableFaceHeader,
        glyphs: &str,
    ) -> anyhow::Result<()> {
        let faces = self.family.faces.borrow();
        let face = faces
            .get(variations)
            .ok_or_else(|| anyhow!("No face found for variations: {variations:?}."))?;
        let ttf_face = face.ttf.as_face_ref();
        // This is safe. Unwrap should be removed after rustybuzz is fixed:
        // https://github.com/RazrFalcon/rustybuzz/issues/52
        let buzz_face = rustybuzz::Face::from_face(ttf_face.clone()).unwrap();
        let mut buffer = rustybuzz::UnicodeBuffer::new();
        buffer.push_str(glyphs);
        let shaped = rustybuzz::shape(&buzz_face, &[], buffer);
        for info in shaped.glyph_infos() {
            let id = GlyphId(info.glyph_id as u16);
            // Load it into the cache.
            let _ = self.glyph_info(variations, id);
        }
        Ok(())
    }

    /// Load the glyph with the given ID into the cache.
    pub fn prepare_glyph_by_id(&self, variations: &NonVariableFaceHeader, id: GlyphId) {
        // Load it into the cache.
        let _ = self.glyph_info(variations, id);
    }
}


// === Serialization Helpers, Because `ttf_parser` Doesn't `derive` Them ===

fn serialize_variation(variation: &NonVariableFaceHeader) -> String {
    let width = match variation.width {
        Width::UltraCondensed => "UltraCondensed",
        Width::ExtraCondensed => "ExtraCondensed",
        Width::Condensed => "Condensed",
        Width::SemiCondensed => "SemiCondensed",
        Width::Normal => "Normal",
        Width::SemiExpanded => "SemiExpanded",
        Width::Expanded => "Expanded",
        Width::ExtraExpanded => "ExtraExpanded",
        Width::UltraExpanded => "UltraExpanded",
    };
    let weight = variation.weight.to_number().to_string();
    let style = match variation.style {
        Style::Normal => "Normal",
        Style::Italic => "Italic",
        Style::Oblique => "Oblique",
    };
    format!("{width}-{weight}-{style}")
}

fn deserialize_variation(variation: &str) -> anyhow::Result<NonVariableFaceHeader> {
    let mut parts = variation.splitn(3, '-');
    let bad_variation = || anyhow!("Malformed variation specifier: {variation}");
    let width = match parts.next().ok_or_else(bad_variation)? {
        "UltraCondensed" => Width::UltraCondensed,
        "ExtraCondensed" => Width::ExtraCondensed,
        "Condensed" => Width::Condensed,
        "SemiCondensed" => Width::SemiCondensed,
        "Normal" => Width::Normal,
        "SemiExpanded" => Width::SemiExpanded,
        "Expanded" => Width::Expanded,
        "ExtraExpanded" => Width::ExtraExpanded,
        "UltraExpanded" => Width::UltraExpanded,
        width => anyhow::bail!("Unexpected font width: `{width}`."),
    };
    let weight = Weight::from(parts.next().ok_or_else(bad_variation)?.parse::<u16>()?);
    let style = match parts.next().ok_or_else(bad_variation)? {
        "Normal" => Style::Normal,
        "Italic" => Style::Italic,
        "Oblique" => Style::Oblique,
        style => anyhow::bail!("Unexpected font style: `{style}`."),
    };
    Ok(NonVariableFaceHeader { width, weight, style })
}


// === Cache Logging ===

/// A glyph that was not found in the MSDF data cache.
#[derive(Debug, serde::Serialize)]
#[allow(dead_code)]
pub struct GlyphCacheMiss {
    face:       String,
    variations: String,
    glyph_id:   u16,
}

profiler::metadata_logger!("GlyphCacheMiss", log_miss(GlyphCacheMiss));



// =======================
// === FontWithGpuData ===
// =======================

/// A font with associated GPU-stored data.
#[allow(missing_docs)]
#[derive(Clone, CloneRef, Debug, Deref)]
pub struct FontWithGpuData {
    #[deref]
    pub font:             Font,
    pub atlas:            gpu::Uniform<Option<gpu::Texture>>,
    pub opacity_increase: gpu::Uniform<f32>,
    pub opacity_exponent: gpu::Uniform<f32>,
    context:              Rc<RefCell<Option<Context>>>,
}

impl FontWithGpuData {
    fn new(font: Font, hinting: Hinting) -> Self {
        let Hinting { opacity_increase, opacity_exponent } = hinting;
        let opacity_increase = gpu::Uniform::new(opacity_increase);
        let opacity_exponent = gpu::Uniform::new(opacity_exponent);
        let atlas = gpu::Uniform::new(default());
        let context = default();
        Self { font, atlas, opacity_exponent, opacity_increase, context }
    }

    fn set_context_and_update(&self, context: Option<&Context>) {
        *self.context.borrow_mut() = context.cloned();
        self.update_atlas();
    }

    /// Upload the current atlas to the GPU if it is dirty (contains more glyphs than the currently-
    /// uploaded version); drop the `gpu::Texture` if context has been lost.
    #[profile(Debug)]
    fn update_atlas(&self) {
        if let Some(context) = self.context.borrow().as_ref() {
            let num_glyphs = self.font.msdf_texture().glyphs();
            let gpu_tex_glyphs = self
                .atlas
                .with_item(|texture| texture.as_ref().map_or_default(|texture| texture.layers()));
            let texture_changed = gpu_tex_glyphs as u32 != num_glyphs;
            if texture_changed {
                let glyph_size = self.font.msdf_texture().size();
                let texture = gpu::Texture::new(
                    context,
                    texture::AnyInternalFormat::Rgb8,
                    texture::AnyItemType::u8,
                    glyph_size.x() as i32,
                    glyph_size.y() as i32,
                    num_glyphs as i32,
                    default(),
                );
                if let Ok(texture) = texture.as_ref() {
                    self.font
                        .with_borrowed_msdf_texture_data(|data| texture.reload_with_content(data));
                }
                self.atlas.set(texture.ok());
            }
        } else {
            self.atlas.set(None);
        }
    }
}



// ================
// === Registry ===
// ================

/// Stores all loaded fonts.
#[derive(Clone, CloneRef, Debug)]
pub struct Registry {
    network:            frp::Network,
    fonts:              Rc<HashMap<Name, FontWithGpuData>>,
    set_context_handle: ensogl_core::display::world::ContextHandler,
}

impl Registry {
    /// Load the default font. See the docs of [`load`] to learn more.
    pub fn load_default(&self) -> FontWithGpuData {
        self.try_load(DEFAULT_FONT).expect("Default font not found.")
    }

    /// Load a font by name. Returns the default font if a font is not found for the name.
    pub fn load(&self, name: impl Into<Name>) -> FontWithGpuData {
        let name = name.into();
        self.try_load(&name).unwrap_or_else(|| {
            warn!("Font '{name}' not found. Loading the default font.");
            self.load_default()
        })
    }

    /// Load a font by name. Returns [`None`] if a font is not found for the name.
    pub fn try_load(&self, name: impl Into<Name>) -> Option<FontWithGpuData> {
        let name = name.into();
        self.fonts.get(&name).cloned()
    }

    fn new(
        scene: &ensogl_core::display::Scene,
        fonts: impl IntoIterator<Item = (Name, Font)>,
    ) -> Self {
        let context = scene.context.borrow();
        let context = context.as_ref();
        let scene_shape = scene.shape().value();
        let fonts: HashMap<_, _> = fonts
            .into_iter()
            .map(|(name, font)| {
                let hinting = Hinting::for_font(&name, scene_shape);
                let font = FontWithGpuData::new(font, hinting);
                font.set_context_and_update(context);
                (name, font)
            })
            .collect();
        let fonts = Rc::new(fonts);
        let fonts_ = Rc::clone(&fonts);
        let set_context_handle = scene.on_set_context(move |context| {
            for font in fonts_.values() {
                font.set_context_and_update(context);
            }
        });
        let network = frp::Network::new("font::Registry");
        let on_before_rendering = ensogl_core::animation::on_before_rendering();
        frp::extend! { network
            eval_ on_before_rendering([fonts] Self::update(&fonts));
        }
        Self { network, fonts, set_context_handle }
    }

    fn update(fonts: impl AsRef<HashMap<Name, FontWithGpuData>>) {
        for font in fonts.as_ref().values() {
            font.update_atlas()
        }
    }
}

impl scene::Extension for Registry {
    fn init(scene: &scene::Scene) -> Self {
        let fonts = Embedded::default().into_fonts();
        Self::new(scene, fonts)
    }
}



// ===============
// === Hinting ===
// ===============

/// System- and font-specific hinting properties. They affect the way the font is rasterized. In
/// order to understand how these variables affect the font rendering, see the GLSL file (in
/// [`glyph::FUNCTIONS`]).
#[allow(missing_docs)]
#[derive(Clone, Copy, Debug)]
struct Hinting {
    opacity_increase: f32,
    opacity_exponent: f32,
}

impl Hinting {
    fn for_font(font_name: &str, shape: scene::Shape) -> Self {
        let pixel_ratio = shape.pixel_ratio;
        // The optimal hinting values must be found by testing. The [`text_area`] debug scene
        // supports trying different values at runtime.
        match font_name {
            "mplus1p" | "enso" if pixel_ratio >= 2.0 =>
                Self { opacity_increase: 0.4, opacity_exponent: 4.0 },
            "mplus1p" | "enso" => Self { opacity_increase: 0.3, opacity_exponent: 3.0 },
            _ => Self::default(),
        }
    }
}

impl Default for Hinting {
    fn default() -> Self {
        Self { opacity_increase: 0.0, opacity_exponent: 1.0 }
    }
}



// =========================
// === Embedded Registry ===
// =========================

/// A registry of font data built-in to the application.
#[derive(Debug)]
pub struct Embedded {
    definitions: HashMap<Name, family::FontFamily>,
    data:        EmbeddedData,
    features:    HashMap<Name, Vec<rustybuzz::Feature>>,
}

impl Embedded {
    /// Load a font from the registry.
    pub fn load_font(&self, name: Name) -> Option<Font> {
        let features = self.features.get(&name).cloned().unwrap_or_default();
        let definition = self.definitions.get(&name);
        definition.map(|def| self.data.load_font(name.clone(), def, features))
    }

    /// Load and return all fonts from the registry.
    pub fn into_fonts(self) -> impl Iterator<Item = (Name, Font)> {
        let Self { definitions, data, mut features } = self;
        definitions.into_iter().map(move |(name, definition)| {
            let features = features.remove(&name).unwrap_or_default();
            (name.clone(), data.load_font(name, &definition, features))
        })
    }
}

impl Default for Embedded {
    fn default() -> Self {
        let ensogl_text_embedded_fonts::Embedded { definitions, data, features } = default();
        Self { definitions, data: EmbeddedData { data }, features }
    }
}


// === Embedded data ===

/// Font files compiled into the application.
#[derive(Debug)]
pub struct EmbeddedData {
    data: HashMap<&'static str, &'static [u8]>,
}

impl EmbeddedData {
    fn load_font(
        &self,
        name: Name,
        definition: &family::FontFamily,
        features: Vec<rustybuzz::Feature>,
    ) -> Font {
        match definition {
            family::FontFamily::NonVariable(definition) => {
                let mut family = NonVariableFamily::from(definition);
                family.load_all_faces(self);
                let cache = PREBUILT_ATLASES.with_borrow_mut(|atlases| atlases.get(&name).cloned());
                let font = NonVariableFont::new(name, family, features);
                if let Some(cache) = cache {
                    font.load_cache(&cache)
                        .unwrap_or_else(|e| error!("Failed to load cached font data: {e}."));
                }
                font.into()
            }
            family::FontFamily::Variable(definition) => {
                let mut family = VariableFamily::from(definition);
                family.load_all_faces(self);
                VariableFont::new(name, family, features).into()
            }
        }
    }

    /// Load the font face from memory. Corrupted faces will be reported.
    fn load_face(&self, name: &str) -> Option<Face> {
        let result = self.try_load_face(name);
        result.map_err(|err| error!("Error parsing font: {}", err)).ok()
    }

    fn try_load_face(&self, name: &str) -> anyhow::Result<Face> {
        let data = self.data.get(name).ok_or_else(|| anyhow!("Font '{}' not found", name))?;
        let ttf = ttf::OwnedFace::from_vec((**data).into(), TTF_FONT_FACE_INDEX)?;
        let msdf = msdf::OwnedFace::load_from_memory(data)?;
        Ok(Face { msdf, ttf })
    }
}



// =============
// === Tests ===
// =============

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_enso_font_ligatures_disabled() {
        let registry: HashMap<_, _> = Embedded::default().into_fonts().collect();
        let font = registry.get(&"enso".into()).unwrap();
        font.with_borrowed_face(NonVariableFaceHeader::default(), |face| {
            let face = face.ttf.as_face_ref().clone();
            let face = rustybuzz::Face::from_face(face).unwrap();
            let features = font.feature_settings();
            // If the font's ligatures are used, these two characters will correspond to one
            // glyph. `ensogl_text` doesn't support ligatures, so its shaped length must be the
            // same as its character count.
            let test_str = "fi";
            let mut buffer = rustybuzz::UnicodeBuffer::new();
            buffer.push_str(test_str);
            let shaped = rustybuzz::shape(&face, features, buffer);
            assert_eq!(shaped.len(), test_str.chars().count());
        })
        .unwrap();
    }
}
