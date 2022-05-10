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
#[derive(Clone, Copy, Debug, Fail)]
#[fail(display = "Missing program option: {}.", 0)]
pub struct MissingOption(&'static str);

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
        if let Some(endpoint) = &args.project_manager {
            if args.language_server_rpc.is_some() || args.language_server_data.is_some() {
                Err(MutuallyExclusiveOptions.into())
            } else {
                let endpoint = endpoint.clone();
                Ok(Self::ProjectManager { endpoint })
            }
        } else {
            match (&args.language_server_rpc, &args.language_server_data) {
                (Some(json_endpoint), Some(binary_endpoint)) => {
                    let json_endpoint = json_endpoint.clone();
                    let binary_endpoint = binary_endpoint.clone();
                    let default_namespace = || constants::DEFAULT_PROJECT_NAMESPACE.to_owned();
                    let namespace = args.namespace.clone().unwrap_or_else(default_namespace);
                    let missing_project_name = || MissingOption(args.names().project());
                    let project_name = args.project.clone().ok_or_else(missing_project_name)?;
                    Ok(Self::LanguageServer {
                        json_endpoint,
                        binary_endpoint,
                        namespace,
                        project_name,
                    })
                }
                (None, None) => Ok(default()),
                (None, _) => Err(MissingOption(args.names().language_server_rpc()).into()),
                (_, None) => Err(MissingOption(args.names().language_server_data()).into()),
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
        let project_name = ARGS.project.clone().map(Into::into);
        let initial_view = match ARGS.project {
            Some(_) => InitialView::Project,
            None => InitialView::WelcomeScreen,
        };
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
