//! Definition of commands, labeled FPR endpoints useful when implementing actions which can be
//! altered at runtime, like a keyboard shortcut management.

use crate::prelude::*;

use crate::application::shortcut;
use crate::application::shortcut::Shortcut;
use crate::application::Application;
use crate::frp;



// ============
// === View ===
// ============

/// All view components should deref to their FRP definitions, which should implement
/// the `CommandApi`. Please note that it is automatically derived if you use the
/// `define_endpoints!` macro.
pub trait DerefToCommandApi = Deref where <Self as Deref>::Target: CommandApi;

/// A visual component of an application.
pub trait View: FrpNetworkProvider + DerefToCommandApi {
    /// Identifier of the command provider class.
    fn label() -> &'static str;

    /// Constructor.
    fn new(app: &Application) -> Self;

    /// Set of default shortcuts.
    fn default_shortcuts() -> Vec<Shortcut> {
        default()
    }

    /// Add a new shortcut targeting the self object.
    fn self_shortcut(
        action_type: shortcut::ActionType,
        pattern: impl Into<String>,
        command: impl Into<shortcut::Command>,
    ) -> Shortcut {
        Shortcut::new(shortcut::Rule::new(action_type, pattern), Self::label(), command)
    }

    /// Add a new shortcut targeting the self object.
    fn self_shortcut_when(
        action_type: shortcut::ActionType,
        pattern: impl Into<String>,
        command: impl Into<shortcut::Command>,
        condition: impl Into<shortcut::Condition>,
    ) -> Shortcut {
        Shortcut::new_when(
            shortcut::Rule::new(action_type, pattern),
            Self::label(),
            command,
            condition,
        )
    }
}

/// FRP Network provider. Used to check whether FRP bindings are still alive.
pub trait FrpNetworkProvider {
    /// The underlying frp network accessor.
    fn network(&self) -> &frp::Network;
}



// ===============
// === Command ===
// ===============

/// Abstraction for a command. Includes an frp endpoint which should be called to evaluate the
/// action and information whether this command was disabled in a particular component view.
#[derive(Debug)]
#[allow(missing_docs)]
pub struct Command {
    pub frp:     frp::Any,
    pub enabled: bool,
}

impl Deref for Command {
    type Target = frp::Any;
    fn deref(&self) -> &Self::Target {
        &self.frp
    }
}

impl Command {
    /// Constructor.
    pub fn new(frp: frp::Any<()>) -> Self {
        let enabled = true;
        Self { frp, enabled }
    }
}



// ==================
// === CommandApi ===
// ==================

/// The API for a command provider. This trait should not be provided manually.
/// Use the `define_endpoints!` macro to auto-derive it.
#[allow(missing_docs)]
pub trait CommandApi: Sized {
    fn command_api(&self) -> Rc<RefCell<HashMap<String, Command>>> {
        default()
    }
    fn status_api(&self) -> Rc<RefCell<HashMap<String, frp::Sampler<bool>>>> {
        default()
    }
}



// ========================
// === ProviderInstance ===
// ========================

/// Generic interface to an instance of a component. It contains bindings to all FRP endpoints
/// defined by the component (often by using the `define_endpoints!` macro).
#[derive(Clone, CloneRef, Debug)]
#[allow(missing_docs)]
pub struct ProviderInstance {
    pub network:     frp::WeakNetwork,
    pub command_map: Rc<RefCell<HashMap<String, Command>>>,
    pub status_map:  Rc<RefCell<HashMap<String, frp::Sampler<bool>>>>,
}

impl ProviderInstance {
    /// Check whether the underlying object is still alive.
    pub fn check_alive(&self) -> bool {
        self.network.upgrade().is_some()
    }

    /// The ID of this instance.
    pub fn id(&self) -> frp::NetworkId {
        self.network.id()
    }
}



// ================
// === Registry ===
// ================

/// A command registry. Allows registering command providers (gui components) and corresponding
/// `ProviderInstance`s.
#[derive(Debug, Clone, CloneRef)]
#[allow(missing_docs)]
pub struct Registry {
    pub name_map: Rc<RefCell<HashMap<String, Vec<ProviderInstance>>>>,
    pub id_map:   Rc<RefCell<HashMap<frp::NetworkId, ProviderInstance>>>,
}

impl Registry {
    /// Constructor.
    pub fn create() -> Self {
        let name_map = default();
        let id_map = default();
        Self { name_map, id_map }
    }

    /// Registers a gui component as a command provider.
    pub fn register<V: View>(&self) {
        let label = V::label();
        let exists = self.name_map.borrow().get(label).is_some();
        if exists {
            warn!("The view '{label}' was already registered.")
        } else {
            self.name_map.borrow_mut().insert(label.into(), default());
        }
    }

    /// Registers the command `ProviderInstance`.
    pub fn register_instance<T: View>(&self, target: &T) {
        let label = T::label();
        let network = T::network(target).downgrade();
        let command_map = target.deref().command_api();
        let status_map = target.deref().status_api();
        let instance = ProviderInstance { network, command_map, status_map };
        let was_registered = self.name_map.borrow().get(label).is_some();
        if !was_registered {
            self.register::<T>();
            // FIXME[WD]: The registration should be performed automatically by using before-main
            //     entry points.
        };
        let id = instance.id();
        self.name_map.borrow_mut().get_mut(label).unwrap().push(instance.clone_ref());
        self.id_map.borrow_mut().insert(id, instance);
    }

    /// Queries the command map by command name and applies the provided function to the result.
    /// Emits warnings in case the command could not be found.
    fn with_command_mut<T: View>(
        &self,
        target: &T,
        name: impl AsRef<str>,
        f: impl Fn(&mut Command),
    ) {
        let name = name.as_ref();
        let id = T::network(target).id();
        match self.id_map.borrow_mut().get(&id) {
            None => warn!("The provided component ID is invalid {id}."),
            Some(instance) => match instance.command_map.borrow_mut().get_mut(name) {
                None => warn!("The command name {name} is invalid."),
                Some(command) => f(command),
            },
        }
    }

    /// Sets the command enable status for the provided component instance.
    pub fn set_command_enabled<T: View>(&self, instance: &T, name: impl AsRef<str>, enabled: bool) {
        self.with_command_mut(instance, name, |command| command.enabled = enabled)
    }
}
