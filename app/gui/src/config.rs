//! This module provides IDE configuration structures.

use crate::prelude::*;

use crate::constants;

use engine_protocol::project_manager::ProjectName;
use enso_config::Args;
use enso_config::ARGS;



// ==============
// === Errors ===
// ==============

#[allow(missing_docs)]
#[derive(Clone, Debug, Fail)]
#[fail(display = "Missing program option: {}.", 0)]
pub struct MissingOption(String);

#[allow(missing_docs)]
#[derive(Clone, Copy, Debug, Fail)]
#[fail(display = "Provided options for both project manager and language server connection.")]
pub struct MutuallyExclusiveOptions;



// ======================
// === BackendService ===
// ======================

/// A Configuration defining to what backend service should IDE connect.
#[allow(missing_docs)]
#[derive(Clone, Debug)]
pub enum BackendService {
    /// Connect to the project manager. Using the project manager IDE will open or create a
    /// specific project and connect to its Language Server.
    ProjectManager { endpoint: String },
    /// Connect to the language server of some project. The project managing operations will be
    /// unavailable.
    LanguageServer {
        json_endpoint:   String,
        binary_endpoint: String,
        namespace:       String,
        project_name:    String,
    },
}

impl Default for BackendService {
    fn default() -> Self {
        Self::ProjectManager { endpoint: constants::PROJECT_MANAGER_ENDPOINT.into() }
    }
}

impl BackendService {
    /// Read backend configuration from the web arguments. See also [`web::Arguments`]
    /// documentation.
    pub fn from_web_arguments(args: &Args) -> FallibleResult<Self> {
        let endpoint = args.groups.engine.options.project_manager_url.value.as_str();
        let rpc_url_option = &args.groups.engine.options.rpc_url;
        let data_url_option = &args.groups.engine.options.data_url;
        let rpc_url = rpc_url_option.value.as_str();
        let data_url = data_url_option.value.as_str();
        if !endpoint.is_empty() {
            if !rpc_url.is_empty() || !data_url.is_empty() {
                Err(MutuallyExclusiveOptions.into())
            } else {
                let endpoint = endpoint.to_owned();
                Ok(Self::ProjectManager { endpoint })
            }
        } else {
            match (rpc_url, data_url) {
                ("", "") => Ok(default()),
                ("", _) => Err(MissingOption(rpc_url_option.__name__.to_owned()).into()),
                (_, "") => Err(MissingOption(data_url_option.__name__.to_owned()).into()),
                (json_endpoint, binary_endpoint) => {
                    let json_endpoint = json_endpoint.to_owned();
                    let binary_endpoint = binary_endpoint.to_owned();
                    let def_namespace = || constants::DEFAULT_PROJECT_NAMESPACE.to_owned();
                    let namespace = args.groups.engine.options.namespace.value.clone();
                    let namespace = if namespace.is_empty() { def_namespace() } else { namespace };
                    let project_name_option = &args.groups.startup.options.project;
                    let project_name = project_name_option.value.as_str();
                    let no_project_name = || MissingOption(project_name_option.__name__.to_owned());
                    let project_name = if project_name.is_empty() {
                        Err(no_project_name())
                    } else {
                        Ok(project_name.to_owned())
                    }?;
                    Ok(Self::LanguageServer {
                        json_endpoint,
                        binary_endpoint,
                        namespace,
                        project_name,
                    })
                }
            }
        }
    }
}



// ===============
// === Startup ===
// ===============

/// Configuration data necessary to initialize IDE.
#[derive(Clone, Debug, Default)]
pub struct Startup {
    /// The configuration of connection to the backend service.
    pub backend:       BackendService,
    /// The project name we want to open on startup.
    pub project_name:  Option<ProjectName>,
    /// Whether to open directly to the project view, skipping the welcome screen.
    pub initial_view:  InitialView,
    /// Identifies the element to create the IDE's DOM nodes as children of.
    pub dom_parent_id: Option<String>,
}

impl Startup {
    /// Read configuration from the web arguments. See also [`web::Arguments`] documentation.
    pub fn from_web_arguments() -> FallibleResult<Startup> {
        let backend = BackendService::from_web_arguments(&ARGS)?;
        let project_name = ARGS.groups.startup.options.project.value.as_str();
        let no_project_name = project_name.is_empty();
        let initial_view =
            if no_project_name { InitialView::WelcomeScreen } else { InitialView::Project };
        let project_name = (!no_project_name).as_some_from(|| project_name.to_owned().into());
        let dom_parent_id = None;
        Ok(Startup { backend, project_name, initial_view, dom_parent_id })
    }

    /// Identifies the element to create the IDE's DOM nodes as children of.
    pub fn dom_parent_id(&self) -> &str {
        // The main entry point requires that this matches the ID defined in `index.html`.
        match &self.dom_parent_id {
            Some(id) => id.as_ref(),
            None => "root",
        }
    }
}


// === InitialView ===

/// Identifies the view initially active on startup.
#[derive(Clone, Copy, Debug, Derivative)]
#[derivative(Default)]
pub enum InitialView {
    /// Start to the Welcome Screen.
    #[derivative(Default)]
    WelcomeScreen,
    /// Start to the Project View.
    Project,
}
