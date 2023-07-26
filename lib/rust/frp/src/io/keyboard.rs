//! Keyboard implementation and FRP bindings.

use crate::prelude::*;

use crate as frp;

use enso_web::KeyboardEvent;
use inflector::Inflector;
use unicode_segmentation::UnicodeSegmentation;



// ============
// === Side ===
// ============

/// The key placement enum.
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
#[allow(missing_docs)]
pub enum Side {
    Left,
    Right,
}

impl Side {
    /// Convert the side to a lowercase string representation.
    pub fn simple_name(self) -> &'static str {
        match self {
            Self::Left => "left",
            Self::Right => "right",
        }
    }
}



// ===========
// === Key ===
// ===========

macro_rules! define_keys {
    (
        Side { $($side:ident),* $(,)? }
        Arrow { $($arrow:ident),* $(,)? }
        Regular { $($regular:ident),* $(,)? }
    ) => {
        /// A key representation.
        ///
        /// For reference, see the following links:
        /// https://developer.mozilla.org/en-US/docs/Web/API/KeyboardEvent/key/Key_Values
        #[derive(Clone, Debug, Eq, Hash, PartialEq)]
        #[allow(missing_docs)]
        pub enum Key {
            $($side(Side),)*
            $($regular,)*
            Arrow (ArrowDirection),
            Character (String),
            Other (String),
        }


        // === ArrowDirection ===

        /// The directions of the arrow keys on a keyboard.
        #[derive(Copy, Clone, Debug, Eq, Hash, PartialEq, Default)]
        #[allow(missing_docs)]
        pub enum ArrowDirection {
            #[default]
            $($arrow,)*
        }

        // === KEY_NAME_MAP ===

        lazy_static! {
            /// A mapping from a name to key instance. Please note that all side-aware keys are
            /// instantiated to the left binding. The correct assignment (left/right) is done in a
            /// separate step.
            static ref KEY_NAME_MAP: HashMap<&'static str, Key> = {
                use Key::*;
                use Side::*;
                let mut m = HashMap::new();
                $(m.insert(stringify!($side), $side(Left));)*
                $(m.insert(stringify!($regular), $regular);)*
                $(
                    let key_name = concat!("Arrow", stringify!($arrow));
                    m.insert(key_name, Arrow(ArrowDirection::$arrow));
                )*
                m
            };
        }
    };
}

define_keys! {
    Side    {Alt, AltGr, AltGraph, Control, Meta, Shift}
    Arrow   {Down, Left, Right, Up}
    Regular {
        Backspace,
        Delete,
        End,
        Enter,
        Home,
        Insert,
        PageDown,
        PageUp,
        Space,
    }
}

impl Key {
    /// Constructor. The `key` is used to distinguish between keys, while the `code` is used to
    /// check whether it was left or right key in case of side-aware keys. It's important to use the
    /// `key` to distinguish between keys, as it it hardware independent. For example, `alt a` could
    /// result in key `ą` in some keyboard layouts and the code `KeyA`. When layout changes, the
    /// symbol `ą` could be mapped to a different hardware key. Check the following site for more
    /// info: https://keycode.info.
    pub fn new(key: String, code: &str) -> Self {
        let key_ref: &str = &key;
        // Space is very special case. It has key value being a character, but we don't want to
        // interpret is as a Key::Character.
        if key == " " {
            Self::Space
        } else if key.graphemes(true).count() == 1 {
            Self::Character(key)
        } else {
            let key = KEY_NAME_MAP.get(key_ref).cloned().unwrap_or(Self::Other(key));
            match (key, code) {
                (Self::Alt(_), "AltRight") => Self::Alt(Side::Right),
                (Self::AltGr(_), "AltRight") => Self::AltGr(Side::Right),
                (Self::AltGraph(_), "AltRight") => Self::AltGraph(Side::Right),
                (Self::Control(_), "ControlRight") => Self::Control(Side::Right),
                (Self::Meta(_), "MetaRight") => Self::Meta(Side::Right),
                (Self::Shift(_), "ShiftRight") => Self::Shift(Side::Right),
                (other, _) => other,
            }
        }
    }

    /// When the meta key is down on MacOS, the key up event is not fired for almost every key. This
    /// function checks whether the event will be emitted for a particular key. Please note that
    /// although this is MacOS specific issue, we are simulating this behavior on all platforms to
    /// keep it consistent.
    pub fn can_be_missing_when_meta_is_down(&self) -> bool {
        !matches!(
            self,
            Self::Alt(_)
                | Self::AltGr(_)
                | Self::AltGraph(_)
                | Self::Control(_)
                | Self::Meta(_)
                | Self::Shift(_)
        )
    }

    /// Simple, kebab-case name of a key.
    pub fn simple_name(&self) -> String {
        use ArrowDirection as Dir;
        let fmt = |side: &Side, repr| format!("{}-{}", repr, side.simple_name());
        match self {
            Self::Alt(side) => fmt(side, "alt"),
            Self::AltGr(side) => fmt(side, "alt-graph"),
            Self::AltGraph(side) => fmt(side, "alt-graph"),
            Self::Control(side) => fmt(side, "ctrl"),
            Self::Meta(side) => fmt(side, "meta"),
            Self::Shift(side) => fmt(side, "shift"),

            Self::Arrow(Dir::Down) => "arrow-down".into(),
            Self::Arrow(Dir::Left) => "arrow-left".into(),
            Self::Arrow(Dir::Right) => "arrow-right".into(),
            Self::Arrow(Dir::Up) => "arrow-up".into(),
            Self::Backspace => "backspace".into(),
            Self::Delete => "delete".into(),
            Self::End => "end".into(),
            Self::Enter => "enter".into(),
            Self::Home => "home".into(),
            Self::Insert => "insert".into(),
            Self::PageDown => "page-down".into(),
            Self::PageUp => "page-up".into(),
            Self::Space => "space".into(),

            Self::Character(repr) => repr.into(),
            Self::Other(repr) => repr.to_kebab_case(),
        }
    }
}

impl Default for Key {
    fn default() -> Self {
        Self::Other("".into())
    }
}



// ===================
// === KeyWithCode ===
// ===================

/// Structure representing key with its code.
///
/// For difference between keys and codes see
/// https://developer.mozilla.org/en-US/docs/Web/API/KeyboardEvent/code and
/// https://developer.mozilla.org/en-US/docs/Web/API/KeyboardEvent/key
#[allow(missing_docs)]
#[derive(Clone, Debug, Default)]
pub struct KeyWithCode {
    pub key:  Key,
    pub code: String,
}

impl KeyWithCode {
    /// Create a Key structure and return it with the passed code.
    pub fn new(key: String, code: String) -> Self {
        let key = Key::new(key, code.as_str());
        KeyWithCode { key, code }
    }
}

impl From<&KeyboardEvent> for KeyWithCode {
    fn from(event: &KeyboardEvent) -> Self {
        Self::new(event.key(), event.code())
    }
}



// =====================
// === KeyboardModel ===
// =====================

/// Model keeping track of currently pressed keys.
///
/// The keys are usually defined by their `key` value (see
/// https://developer.mozilla.org/en-US/docs/Web/API/KeyboardEvent/key), however we need to keep
/// also codes of pressed keys to properly handle releases:
///
/// Consider the following event sequence:
/// 1. press Shift
/// 2. press KeyA (emitted with key `A`)
/// 3. release Shift
/// 4. release KeyA (emitted with key `a`)
///
/// During release KeyA we must realize that the acrual key to release is `A`, otherwise the key `A`
/// will be stuck.
///
/// The current implementation will therefore emit repeat/release of pressed "key" values.
/// (so in above example releasing `a` will never be emitted, only releasing `A`).
#[derive(Clone, CloneRef, Debug, Default)]
pub struct KeyboardModel {
    pressed_keys:        Rc<RefCell<HashSet<Key>>>,
    pressed_code_to_key: Rc<RefCell<HashMap<String, Key>>>,
}

impl KeyboardModel {
    /// Constructor.
    pub fn new() -> Self {
        default()
    }

    /// Check whether the meta key is currently pressed.
    pub fn is_meta_down(&self) -> bool {
        self.is_down(&Key::Meta(Side::Left)) || self.is_down(&Key::Meta(Side::Right))
    }

    /// Check whether the control key is currently pressed.
    pub fn is_control_down(&self) -> bool {
        self.is_down(&Key::Control(Side::Left)) || self.is_down(&Key::Control(Side::Right))
    }

    /// Check whether the alt key is currently pressed.
    pub fn is_alt_down(&self) -> bool {
        self.is_down(&Key::Alt(Side::Left)) || self.is_down(&Key::Alt(Side::Right))
    }

    /// Checks whether the provided key is currently pressed.
    pub fn is_down(&self, key: &Key) -> bool {
        self.pressed_keys.borrow().contains(key)
    }

    /// Simulate press of the provided key.
    pub fn press(&self, KeyWithCode { key, code }: &KeyWithCode) -> Key {
        let pressed_key_opt = self.pressed_code_to_key.borrow_mut().get(code).cloned();
        let key = pressed_key_opt.unwrap_or_else(|| {
            self.pressed_code_to_key.borrow_mut().insert(code.clone(), key.clone());
            key.clone()
        });
        self.pressed_keys.borrow_mut().insert(key.clone());
        key
    }

    /// Simulate release of the provided key.
    pub fn release(&self, KeyWithCode { key, code }: &KeyWithCode) -> Key {
        let key = self.pressed_code_to_key.borrow_mut().remove(code).unwrap_or_else(|| key.clone());
        self.pressed_keys.borrow_mut().remove(&key);
        key
    }

    /// Release all keys which can become "sticky" when meta key is down. To learn more, refer to
    /// the docs of `can_be_missing_when_meta_is_down`.
    #[allow(clippy::unnecessary_filter_map)] // Allows not cloning the element.
    pub fn release_meta_dependent(&self) -> Vec<Key> {
        let mut to_release = Vec::new();
        let new_set = self
            .pressed_code_to_key
            .borrow_mut()
            .drain()
            .filter_map(|(code, key)| {
                if key.can_be_missing_when_meta_is_down() {
                    to_release.push(key);
                    None
                } else {
                    Some((code, key))
                }
            })
            .collect();
        *self.pressed_code_to_key.borrow_mut() = new_set;
        for released in &to_release {
            self.pressed_keys.borrow_mut().remove(released);
        }
        to_release
    }

    /// Release all keys and return list of keys released.
    pub fn release_all(&self) -> HashSet<Key> {
        self.pressed_code_to_key.borrow_mut().clear();
        std::mem::take(&mut *self.pressed_keys.borrow_mut())
    }
}



// ======================
// === KeyboardSource ===
// ======================

/// The source of FRP keyboard inputs (press / release).
#[derive(Clone, CloneRef, Debug)]
#[allow(missing_docs)]
pub struct KeyboardSource {
    pub up:               frp::Source<KeyWithCode>,
    pub down:             frp::Source<KeyWithCode>,
    pub window_defocused: frp::Source,
}

impl KeyboardSource {
    /// Constructor.
    pub fn new(network: &frp::Network) -> Self {
        frp::extend! { network
            down             <- source();
            up               <- source();
            window_defocused <- source();
        }
        Self { up, down, window_defocused }
    }
}



// ================
// === Keyboard ===
// ================

/// Keyboard FRP bindings.
#[derive(Clone, CloneRef, Debug)]
#[allow(missing_docs)]
pub struct Keyboard {
    model:               KeyboardModel,
    pub network:         frp::Network,
    pub source:          KeyboardSource,
    pub down:            frp::Stream<Key>,
    pub up:              frp::Stream<Key>,
    pub is_meta_down:    frp::Stream<bool>,
    pub is_control_down: frp::Stream<bool>,
    pub is_alt_down:     frp::Stream<bool>,
    pub any_event:       frp::Stream<()>,
}

impl Keyboard {
    /// Constructor.
    pub fn new() -> Self {
        let network = frp::Network::new("keyboard");
        let model = KeyboardModel::default();
        let source = KeyboardSource::new(&network);
        frp::extend! { network
            down <- source.down.map(f!((kc) model.press(kc)));
            up <- source.up.map(f!((kc) model.release(kc)));
            is_meta_down <- any(&down, &up).map(f_!(model.is_meta_down()));
            meta_release <= source.down.gate(&is_meta_down).map(
                f_!(model.release_meta_dependent())
            );
            defocus_release <= source.window_defocused.map(f_!(model.release_all()));
            up <- any3(&up, &meta_release, &defocus_release);
            any_event <- any_(&down, &up);
            is_control_down <- any_event.map(f_!(model.is_control_down()));
            is_alt_down <- any_event.map(f_!(model.is_alt_down()));
        }
        Keyboard {
            model,
            network,
            source,
            down,
            up,
            is_meta_down,
            is_control_down,
            is_alt_down,
            any_event,
        }
    }
}

impl Default for Keyboard {
    fn default() -> Self {
        Self::new()
    }
}


// =======================
// === BrowserShortcut ===
// =======================

/// A handy structure used for defining shortcuts used by the browser in some special way
/// (for example reload page or add bookmark). We want to prevent default actions on such shortcuts.
/// See also [`BROWSER_SHORTCUTS`].
struct BrowserShortcut {
    code:  &'static str,
    ctrl:  bool,
    shift: bool,
}

impl BrowserShortcut {
    const fn new(code: &'static str) -> Self {
        Self { code, ctrl: false, shift: false }
    }

    const fn ctrl(code: &'static str) -> Self {
        Self { code, ctrl: true, shift: false }
    }

    const fn ctrl_shift(code: &'static str) -> Self {
        Self { code, ctrl: true, shift: true }
    }

    fn matches(&self, event: &KeyboardEvent) -> bool {
        event.code() == self.code
            && self.ctrl == event.ctrl_key()
            && self.shift == event.shift_key()
    }
}

/// A list of shortcuts with special browser action, on which we want to call `prevent_default`.
const BROWSER_SHORTCUTS: &[BrowserShortcut] =
    &[BrowserShortcut::new("Tab"), BrowserShortcut::ctrl("R"), BrowserShortcut::ctrl_shift("D")];

/// Return `true` if the event is associated with a browser action that should be prevented.
pub fn is_browser_shortcut(event: &KeyboardEvent) -> bool {
    BROWSER_SHORTCUTS.iter().any(|shortcut| shortcut.matches(event))
}
