//! A Project Controller.

use crate::model::traits::*;
use crate::prelude::*;

use crate::controller::ide::StatusNotificationPublisher;

use double_representation::import;
use double_representation::name::project;
use double_representation::name::QualifiedName;
use engine_protocol::language_server::MethodPointer;
use engine_protocol::language_server::Path;
use enso_frp::web::platform;
use enso_frp::web::platform::Platform;
use parser::Parser;



// =================
// === Constants ===
// =================

/// The label of compiling stdlib message process.
pub const COMPILING_STDLIB_LABEL: &str = "Compiling standard library. It can take up to 1 minute.";

/// Name of the main definition.
///
/// This is the definition whose graph will be opened on IDE start.
pub const MAIN_DEFINITION_NAME: &str = "main";

/// The code with definition of the default `main` method.
pub fn default_main_method_code() -> String {
    format!(r#"{MAIN_DEFINITION_NAME} = "Hello, World!""#)
}

/// The default content of the newly created initial main module file.
pub fn default_main_module_code() -> String {
    default_main_method_code()
}

/// Method pointer that described the main method, i.e. the method that project view wants to open
/// and which presence is currently required.
pub fn main_method_ptr(
    project_name: project::QualifiedName,
    module_path: &model::module::Path,
) -> MethodPointer {
    module_path.method_pointer(project_name, MAIN_DEFINITION_NAME)
}



// =================
// === Utilities ===
// =================

/// Returns the path to package.yaml file for given project.
pub fn package_yaml_path(project_name: &str) -> String {
    match platform::current() {
        Some(Platform::Linux) | Some(Platform::MacOS) =>
            format!("~/enso/projects/{project_name}/package.yaml"),
        Some(Platform::Windows) =>
            format!("%userprofile%\\enso\\projects\\{project_name}\\package.yaml"),
        _ => format!("<path-to-enso-projects>/{project_name}/package.yaml"),
    }
}


// ==============
// === Handle ===
// ==============

// === SetupResult ===

/// The result of initial project setup, containing handy controllers to be used in the initial
/// view.
#[derive(Clone, CloneRef, Debug)]
pub struct InitializationResult {
    /// The Text Controller for Main module code to be displayed in Code Editor.
    pub main_module_text:  controller::Text,
    /// The model of the project's Main module.
    pub main_module_model: model::Module,
    /// The Graph Controller for main method's definition graph, to be displayed in Graph Editor.
    pub main_graph:        controller::ExecutedGraph,
}

/// Project Controller Handle.
///
/// This controller supports IDE-related operations on a specific project.
#[allow(missing_docs)]
#[derive(Clone, CloneRef, Debug)]
pub struct Project {
    pub model:                model::Project,
    pub status_notifications: StatusNotificationPublisher,
}

impl Project {
    /// Create a controller of given project.
    pub fn new(model: model::Project, status_notifications: StatusNotificationPublisher) -> Self {
        Self { model, status_notifications }
    }

    /// Do the initial setup of opened project.
    ///
    /// This function should be called always after opening a new project in IDE. It checks if main
    /// module and main method are present in the project, and recreates them if missing.
    /// It also sends status notifications and warnings about the opened project (like
    /// warning about unsupported engine version).
    ///
    /// Returns the controllers of module and graph which should be displayed in the view.
    #[profile(Task)]
    pub async fn initialize(&self) -> FallibleResult<InitializationResult> {
        let project = self.model.clone_ref();
        let parser = self.model.parser();
        let module_path = self.initial_module_path();
        let file_path = module_path.file_path().clone();

        // TODO [mwu] This solution to recreate missing main file should be considered provisional
        //   until proper decision is made. See: https://github.com/enso-org/enso/issues/1050
        self.recreate_if_missing(&file_path, default_main_method_code()).await?;
        let method = main_method_ptr(project.qualified_name(), &module_path);
        let main_module_model = self.model.module(module_path.clone()).await?;
        Self::add_main_if_missing(project.qualified_name(), &main_module_model, &method, &parser)?;

        let mut info = main_module_model.info();
        let visualization_module = QualifiedName::from_text("Standard.Visualization").unwrap();
        let visualization_import = import::Info::new_qualified(visualization_module);
        info.add_import_if_missing(&project.parser(), visualization_import);
        main_module_model.update_ast(info.ast)?;

        // Here, we should be relatively certain (except race conditions in case of multiple
        // clients that we currently do not support) that main module exists and contains main
        // method. Thus, we should be able to successfully create a graph controller for it.
        let main_module_text = controller::Text::new(&project, file_path).await?;
        let main_graph = controller::ExecutedGraph::new(project, method).await?;

        self.init_call_stack_from_metadata(&main_module_model, &main_graph).await;
        self.notify_about_compiling_process(&main_graph);
        self.display_warning_on_unsupported_engine_version();

        Ok(InitializationResult { main_module_text, main_module_model, main_graph })
    }
}


// === Project Initialization Utilities ===

impl Project {
    /// Returns the path to the initially opened module in the given project.
    fn initial_module_path(&self) -> model::module::Path {
        crate::ide::initial_module_path(&self.model)
    }

    /// Create a file with default content if it does not already exist.
    pub async fn recreate_if_missing(
        &self,
        path: &Path,
        default_content: String,
    ) -> FallibleResult {
        let rpc = self.model.json_rpc();
        if !rpc.file_exists(path).await?.exists {
            rpc.write_file(path, &default_content).await?;
        }
        Ok(())
    }

    /// Add main method definition to the given module, if the method is not already defined.
    ///
    /// The lookup will be done using the given `main_ptr` value.
    pub fn add_main_if_missing(
        project_name: project::QualifiedName,
        module: &model::Module,
        main_ptr: &MethodPointer,
        parser: &Parser,
    ) -> FallibleResult {
        if module.lookup_method(project_name, main_ptr).is_err() {
            let mut info = module.info();
            let main_code = default_main_method_code();
            let main_ast = parser.parse_line_ast(main_code)?;
            info.add_ast(main_ast, double_representation::module::Placement::End)?;
            module.update_ast(info.ast)?;
        }
        Ok(())
    }

    async fn init_call_stack_from_metadata(
        &self,
        main_module: &model::Module,
        main_graph: &controller::ExecutedGraph,
    ) {
        // Restore the call stack from the metadata.
        let initial_call_stack = main_module.with_project_metadata(|m| m.call_stack.clone());
        // We should not be too concerned about failure here. It is to be assumed that metadata can
        // get broken.
        if let Err(error) = main_graph.enter_stack(initial_call_stack).await {
            warn!("Failed to push initial call stack. {error}");
        }
    }

    #[profile(Detail)]
    fn notify_about_compiling_process(&self, graph: &controller::ExecutedGraph) {
        let status_notifier = self.status_notifications.clone_ref();
        let compiling_process = status_notifier.publish_background_task(COMPILING_STDLIB_LABEL);
        let execution_ready = graph.when_ready();
        executor::global::spawn(async move {
            if execution_ready.await.is_some() {
                status_notifier.published_background_task_finished(compiling_process);
            } else {
                warn!("Executed graph dropped before first successful execution!")
            }
        });
    }

    fn display_warning_on_unsupported_engine_version(&self) {
        if let Err(e) = enso_config::check_engine_version(&self.model.engine_version()) {
            self.status_notifications.publish_event(e.to_string());
        }
    }
}


// === Project Snapshotting ===

impl Project {
    /// Saves a snapshot of the current state of the project to the VCS.
    #[profile(Detail)]
    pub fn save_project_snapshot(&self) -> impl Future<Output = FallibleResult> {
        let project_root_id = self.model.project_content_root_id();
        let path_segments: [&str; 0] = [];
        let root_path = Path::new(project_root_id, &path_segments);
        let language_server = self.model.json_rpc();
        async move {
            language_server.save_vcs(&root_path, &None).await?;
            Ok(())
        }
    }

    /// Restores the state of the project to the last snapshot saved to the VCS.
    #[profile(Detail)]
    pub fn restore_project_snapshot(&self) -> impl Future<Output = FallibleResult> {
        let project_root_id = self.model.project_content_root_id();
        let path_segments: [&str; 0] = [];
        let root_path = Path::new(project_root_id, &path_segments);
        let language_server = self.model.json_rpc();
        async move {
            language_server.restore_vcs(&root_path, &None).await?;
            Ok(())
        }
    }
}



// =============
// === Tests ===
// =============

#[cfg(test)]
mod tests {
    use super::*;

    use crate::executor::test_utils::TestWithLocalPoolExecutor;
    use engine_protocol::language_server;
    use std::assert_matches::assert_matches;

    #[wasm_bindgen_test]
    fn adding_missing_main() {
        let _ctx = TestWithLocalPoolExecutor::set_up();
        let parser = parser::Parser::new();
        let mut data = crate::test::mock::Unified::new();
        let module_name = data.module_path.module_name().to_owned();
        let main_ptr = main_method_ptr(data.project_name.clone(), &data.module_path);

        // Check that module without main gets it after the call.
        let empty_module_code = "";
        data.set_code(empty_module_code);
        let urm = data.undo_redo_manager();
        let module = data.module(urm.clone_ref());
        assert!(module.lookup_method(data.project_name.clone(), &main_ptr).is_err());
        Project::add_main_if_missing(data.project_name.clone(), &module, &main_ptr, &parser)
            .unwrap();
        assert!(module.lookup_method(data.project_name.clone(), &main_ptr).is_ok());

        // Now check that modules that have main already defined won't get modified.
        let mut expect_intact = move |code: &str| {
            data.set_code(code);
            let module = data.module(urm.clone_ref());
            Project::add_main_if_missing(data.project_name.clone(), &module, &main_ptr, &parser)
                .unwrap();
            assert_eq!(code, module.ast().repr());
        };
        expect_intact("main = 5");
        expect_intact(&format!("{module_name}.main = 5"));
    }


    // === Project Snapshotting ===

    /// Structure that keeps track of how many commits are made.
    struct VcsMockState {
        commit_count: Cell<usize>,
    }

    /// Mock project that updates a VcsMockState on relevant language server API calls.
    fn setup_mock_project(vcs: Rc<VcsMockState>) -> model::Project {
        let json_client = language_server::MockClient::default();
        let path_segments: [&str; 0] = [];
        let root_path = Path::new(Uuid::default(), &path_segments);

        let vcs_entry = language_server::response::SaveVcs::default();
        json_client.expect.save_vcs(move |path, name| {
            assert_eq!(path, &root_path);
            assert_eq!(name, &None);
            let count = vcs.commit_count.get();
            vcs.commit_count.set(count + 1);
            Ok(vcs_entry)
        });

        let ls = language_server::Connection::new_mock_rc(json_client);
        let mut project = model::project::MockAPI::new();
        model::project::test::expect_root_id(&mut project, Uuid::default());
        project.expect_json_rpc().returning_st(move || ls.clone_ref());
        Rc::new(project)
    }

    #[wasm_bindgen_test]
    fn save_project_snapshot() {
        TestWithLocalPoolExecutor::set_up().run_task(async move {
            let vcs = Rc::new(VcsMockState { commit_count: Cell::new(2) });
            let project = setup_mock_project(vcs.clone());
            let project_controller = controller::Project::new(project, default());
            let result = project_controller.save_project_snapshot().await;
            assert_matches!(result, Ok(()));
            assert_eq!(vcs.commit_count.get(), 3);
        });
    }
}
