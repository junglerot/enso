// === Features ===
#![feature(option_result_contains)]
#![feature(once_cell)]
#![feature(default_free_fn)]
#![feature(future_join)]
// === Standard Linter Configuration ===
#![deny(non_ascii_idents)]
#![warn(unsafe_code)]
#![allow(clippy::bool_to_int_with_if)]
#![allow(clippy::let_and_return)]
// === Non-Standard Linter Configuration ===
#![warn(missing_copy_implementations)]
#![warn(missing_debug_implementations)]
#![warn(trivial_numeric_casts)]
#![warn(unused_import_braces)]
#![warn(unused_qualifications)]


// ==============
// === Export ===
// ==============

pub mod arg;



pub mod prelude {
    pub use crate::arg::ArgExt as _;
    pub use enso_build::prelude::*;
}

use crate::prelude::*;
use std::future::join;

use crate::arg::java_gen;
use crate::arg::release::Action;
use crate::arg::BuildJob;
use crate::arg::Cli;
use crate::arg::IsTargetSource;
use crate::arg::IsWatchableSource;
use crate::arg::OutputPath;
use crate::arg::Target;
use crate::arg::WatchJob;
use anyhow::Context;
use arg::BuildDescription;
use clap::Parser;
use derivative::Derivative;
use enso_build::config::Config;
use enso_build::context::BuildContext;
use enso_build::engine::context::EnginePackageProvider;
use enso_build::engine::Benchmarks;
use enso_build::engine::Tests;
use enso_build::paths::TargetTriple;
use enso_build::project;
use enso_build::project::backend;
use enso_build::project::backend::Backend;
use enso_build::project::gui;
use enso_build::project::gui::Gui;
use enso_build::project::gui2;
use enso_build::project::gui2::Gui2;
use enso_build::project::ide;
use enso_build::project::ide::Ide;
use enso_build::project::ide2;
use enso_build::project::runtime;
use enso_build::project::runtime::Runtime;
use enso_build::project::wasm;
use enso_build::project::wasm::Wasm;
use enso_build::project::IsArtifact;
use enso_build::project::IsTarget;
use enso_build::project::IsWatchable;
use enso_build::project::IsWatcher;
use enso_build::source::BuildSource;
use enso_build::source::BuildTargetJob;
use enso_build::source::CiRunSource;
use enso_build::source::ExternalSource;
use enso_build::source::GetTargetJob;
use enso_build::source::OngoingCiRunSource;
use enso_build::source::ReleaseSource;
use enso_build::source::Source;
use enso_build::source::WatchTargetJob;
use enso_build::source::WithDestination;
use enso_build::version;
use futures_util::future::try_join;
use ide_ci::actions::workflow::is_in_env;
use ide_ci::cache::Cache;
use ide_ci::define_env_var;
use ide_ci::fs::remove_if_exists;
use ide_ci::github::release;
use ide_ci::github::setup_octocrab;
use ide_ci::global;
use ide_ci::ok_ready_boxed;
use ide_ci::programs::cargo;
use ide_ci::programs::git;
use ide_ci::programs::git::clean;
use ide_ci::programs::rustc;
use ide_ci::programs::Cargo;
use octocrab::models::ReleaseId;
use std::time::Duration;
use tempfile::tempdir;
use tokio::process::Child;

pub fn void<T>(_t: T) {}

fn resolve_artifact_name(input: Option<String>, project: &impl IsTarget) -> String {
    input.unwrap_or_else(|| project.artifact_name())
}

define_env_var! {
    ENSO_BUILD_KIND, enso_build::version::Kind;
}

/// The basic, common information available in this application.
#[derive(Clone, Derivative)]
#[derivative(Debug)]
pub struct Processor {
    pub context: BuildContext,
}

impl Deref for Processor {
    type Target = BuildContext;

    fn deref(&self) -> &Self::Target {
        &self.context
    }
}

impl Processor {
    /// Setup common build environment information based on command line input and local
    /// environment.
    pub async fn new(cli: &Cli) -> Result<Self> {
        let absolute_repo_path = cli.repo_path.absolutize()?;
        let octocrab = setup_octocrab().await?;
        let git = git::new(absolute_repo_path.as_ref()).await?;
        let release_provider = || version::promote::releases_on_remote(&git);
        let versions = version::deduce_or_generate(release_provider).await?;
        let mut triple = TargetTriple::new(versions);
        triple.os = cli.target_os;
        triple.versions.publish().await?;
        let context = BuildContext {
            inner: project::Context {
                cache: Cache::new(&cli.cache_path).await?,
                octocrab,
                repo_root: enso_build::paths::new_repo_root(absolute_repo_path, &triple),
            },
            triple,
            remote_repo: cli.repo_remote.clone(),
        };
        Ok(Self { context })
    }

    pub fn context(&self) -> project::Context {
        self.inner.clone()
    }

    pub fn resolve<T: IsTargetSource + IsTarget>(
        &self,
        target: T,
        source: arg::Source<T>,
    ) -> BoxFuture<'static, Result<GetTargetJob<T>>>
    where
        T: Resolvable,
    {
        let span = info_span!("Resolving.", ?target, ?source).entered();
        let destination = source.output_path.output_path;
        let should_upload_artifact = source.build_args.upload_artifact;
        let source = match source.source {
            arg::SourceKind::Build => T::resolve(self, source.build_args.input)
                .map_ok(move |input| {
                    Source::BuildLocally(BuildSource { input, should_upload_artifact })
                })
                .boxed(),
            arg::SourceKind::Local =>
                ok_ready_boxed(Source::External(ExternalSource::LocalFile(source.path))),
            arg::SourceKind::CiRun => {
                let run_id = source.run_id.context(format!(
                    "Missing run ID, please provide {} argument.",
                    T::RUN_ID_NAME
                ));
                let source = run_id.map(|run_id| {
                    Source::External(ExternalSource::CiRun(CiRunSource {
                        run_id,
                        repository: self.remote_repo.clone(),
                        artifact_name: resolve_artifact_name(source.artifact_name.clone(), &target),
                    }))
                });
                ready(source).boxed()
            }
            arg::SourceKind::CurrentCiRun =>
                ok_ready_boxed(Source::External(ExternalSource::OngoingCiRun(OngoingCiRunSource {
                    artifact_name: resolve_artifact_name(source.artifact_name, &target),
                }))),
            arg::SourceKind::Release => {
                let designator = source
                    .release
                    .context(format!("Missing {} argument.", T::RELEASE_DESIGNATOR_NAME));
                let resolved = designator
                    .and_then_async(|designator| self.resolve_release_source(target, designator));
                resolved.map_ok(|source| Source::External(ExternalSource::Release(source))).boxed()
            }
        };
        async move { Ok(GetTargetJob { inner: source.await?, destination }) }
            .instrument(span.clone())
            .boxed()
    }

    #[tracing::instrument]
    pub fn resolve_release_source<T: IsTarget>(
        &self,
        target: T,
        designator: String,
    ) -> BoxFuture<'static, Result<ReleaseSource>> {
        let repository = self.remote_repo.clone();
        let release = self.resolve_release_designator(designator);
        release
            .and_then_sync(move |release| {
                let asset = target.find_asset(&release)?;
                Ok(ReleaseSource { repository, asset_id: asset.id })
            })
            .boxed()
    }

    pub fn js_build_info(&self) -> BoxFuture<'static, Result<gui::BuildInfo>> {
        let triple = self.triple.clone();
        let commit = self.commit();
        async move {
            Ok(gui::BuildInfo {
                commit:         commit.await?,
                name:           "Enso IDE".into(),
                version:        triple.versions.version.clone(),
                engine_version: triple.versions.version.clone(),
            })
        }
        .boxed()
    }

    // pub fn pm_info(&self) -> enso_build::project::backend::BuildInput {
    //     enso_build::project::backend::BuildInput { versions: self.triple.versions.clone() }
    // }

    pub fn resolve_inputs<T: Resolvable>(
        &self,
        inputs: <T as IsTargetSource>::BuildInput,
    ) -> BoxFuture<'static, Result<<T as IsTarget>::BuildInput>> {
        T::resolve(self, inputs)
    }

    pub fn resolve_watch_inputs<T: WatchResolvable>(
        &self,
        inputs: <T as IsWatchableSource>::WatchInput,
    ) -> Result<<T as IsWatchable>::WatchInput> {
        T::resolve_watch(self, inputs)
    }

    pub fn resolve_build_job<T: Resolvable>(
        &self,
        job: BuildJob<T>,
    ) -> BoxFuture<'static, Result<BuildTargetJob<T>>> {
        let BuildJob { input: BuildDescription { input, upload_artifact }, output_path } = job;
        let input = self.resolve_inputs::<T>(input);
        async move {
            Ok(WithDestination::new(
                BuildSource {
                    input:                  input.await?,
                    should_upload_artifact: upload_artifact,
                },
                output_path.output_path,
            ))
        }
        .boxed()
    }

    pub fn resolve_watch_job<T: WatchResolvable>(
        &self,
        job: WatchJob<T>,
    ) -> BoxFuture<'static, Result<WatchTargetJob<T>>> {
        let WatchJob { build, watch_input } = job;
        let build = self.resolve_build_job(build);
        let watch_input = self.resolve_watch_inputs::<T>(watch_input);
        async move { Ok(WatchTargetJob { watch_input: watch_input?, build: build.await? }) }.boxed()
    }

    pub fn watch<Target: WatchResolvable>(
        &self,
        job: WatchJob<Target>,
    ) -> BoxFuture<'static, Result<Target::Watcher>> {
        let context = self.context();
        let job = self.resolve_watch_job(job);
        let target = self.target::<Target>();
        async move { target?.watch(context, job.await?).await }.boxed()
    }

    pub fn watch_and_wait<Target: WatchResolvable>(
        &self,
        job: WatchJob<Target>,
    ) -> BoxFuture<'static, Result> {
        let watcher = self.watch(job);
        async move { watcher.await?.wait_for_finish().await }.boxed()
    }

    pub fn get<Target>(
        &self,
        target_source: arg::Source<Target>,
    ) -> BoxFuture<'static, Result<Target::Artifact>>
    where
        Target: IsTarget + IsTargetSource + Send + Sync + 'static,
        Target: Resolvable,
    {
        let target = self.target::<Target>();
        let get_task = self.target().map(|target| self.resolve(target, target_source));
        let context = self.context();
        async move { target?.get(context, get_task?.await?).await }.boxed()
    }

    pub fn build<Target: Resolvable>(&self, job: BuildJob<Target>) -> BoxFuture<'static, Result> {
        let context = self.context();
        let target = self.target::<Target>();
        let job = self.resolve_build_job(job);
        async move {
            let job = job.await?;
            target?.build(context, job).await
        }
        .void_ok()
        .boxed()
    }

    pub fn handle_wasm(&self, wasm: arg::wasm::Target) -> BoxFuture<'static, Result> {
        match wasm.command {
            arg::wasm::Command::Watch(job) => self.watch_and_wait(job),
            arg::wasm::Command::Build(job) => self.build(job).void_ok().boxed(),
            arg::wasm::Command::Check => Wasm.check().boxed(),
            arg::wasm::Command::Test { no_wasm, no_native, browser } => {
                let wasm_browsers =
                    if no_wasm { default() } else { browser.into_iter().map_into().collect_vec() };
                let root = self.repo_root.to_path_buf();
                async move { Wasm.test(root, &wasm_browsers, !no_native).await }.boxed()
            }
            arg::wasm::Command::Get(source) => self.get(source).void_ok().boxed(),
        }
    }

    pub fn handle_gui(&self, gui: arg::gui::Target) -> BoxFuture<'static, Result> {
        match gui.command {
            arg::gui::Command::Build(job) => self.build(job),
            arg::gui::Command::Get(source) => self.get(source).void_ok().boxed(),
            arg::gui::Command::Watch(job) => self.watch_and_wait(job),
        }
    }

    pub fn handle_gui2(&self, gui: arg::gui2::Target) -> BoxFuture<'static, Result> {
        match gui.command {
            arg::gui2::Command::Build(job) => self.build(job),
            arg::gui2::Command::Get(source) => self.get(source).void_ok().boxed(),
            arg::gui2::Command::Test => gui2::tests(&self.repo_root),
            arg::gui2::Command::Watch => gui2::watch(&self.repo_root),
            arg::gui2::Command::Lint => gui2::lint(&self.repo_root),
        }
    }

    pub fn handle_runtime(&self, gui: arg::runtime::Target) -> BoxFuture<'static, Result> {
        // todo!()
        match gui.command {
            arg::runtime::Command::Build(job) => self.build(job),
            //     arg::gui::Command::Get(source) => self.get(source).void_ok().boxed(),
            //     arg::gui::Command::Watch(job) => self.watch_and_wait(job),
        }
    }

    pub fn handle_backend(&self, backend: arg::backend::Target) -> BoxFuture<'static, Result> {
        match backend.command {
            arg::backend::Command::Build { source } => self.get(source).void_ok().boxed(),
            arg::backend::Command::Upload { input } => {
                let input = enso_build::project::Backend::resolve(self, input);
                let repo = self.remote_repo.clone();
                let context = self.context();
                async move {
                    let input = input.await?;
                    let operation = enso_build::engine::Operation::Release(
                        enso_build::engine::ReleaseOperation {
                            repo,
                            command: enso_build::engine::ReleaseCommand::Upload,
                        },
                    );
                    let config = enso_build::engine::BuildConfigurationFlags {
                        build_engine_package: true,
                        build_launcher_bundle: true,
                        build_project_manager_bundle: true,
                        verify_packages: true,
                        ..default()
                    };
                    let context = input.prepare_context(context, config)?;
                    context.execute(operation).await?;
                    Ok(())
                }
                .boxed()
            }
            arg::backend::Command::Benchmark { which, minimal_run } => {
                let config = enso_build::engine::BuildConfigurationFlags {
                    execute_benchmarks: which.into_iter().collect(),
                    execute_benchmarks_once: minimal_run,
                    ..default()
                };
                let context = self.prepare_backend_context(config);
                async move {
                    let context = context.await?;
                    context.build().await?;
                    Ok(())
                }
                .boxed()
            }
            arg::backend::Command::Test { which } => {
                let mut config = enso_build::engine::BuildConfigurationFlags::default();
                for arg in which {
                    match arg {
                        Tests::Scala => config.test_scala = true,
                        Tests::StandardLibrary => config.test_standard_library = true,
                    }
                }
                config.test_java_generated_from_rust = true;
                let context = self.prepare_backend_context(config);
                async move { context.await?.build().void_ok().await }.boxed()
            }
            arg::backend::Command::Sbt { command } => {
                let context = self.prepare_backend_context(default());
                async move {
                    let mut command_pieces = vec![OsString::from("sbt")];
                    command_pieces.extend(command.into_iter().map(into));

                    let operation =
                        enso_build::engine::Operation::Run(enso_build::engine::RunOperation {
                            command_pieces,
                        });

                    let context = context.await?;
                    context.execute(operation).await
                }
                .boxed()
            }
            arg::backend::Command::CiCheck {} => {
                let config = enso_build::engine::BuildConfigurationFlags {
                    test_scala: true,
                    test_standard_library: true,
                    test_java_generated_from_rust: true,
                    build_benchmarks: true,
                    // Windows is not yet supported for the native runner.
                    build_native_runner: enso_build::ci::big_memory_machine()
                        && TARGET_OS != OS::Windows,
                    execute_benchmarks: {
                        // Run benchmarks only on Linux.
                        let mut ret = BTreeSet::new();
                        if TARGET_OS == OS::Linux {
                            ret.insert(Benchmarks::Runtime);
                        }
                        ret
                    },
                    execute_benchmarks_once: true,
                    // Benchmarks are only checked on Linux because:
                    // * they are then run only on Linux;
                    // * checking takes time;
                    // * this rather verifies the Enso code correctness which should not be platform
                    //   specific.
                    // Checking benchmarks on Windows has caused some CI issues, see
                    // https://github.com/enso-org/enso/issues/8777#issuecomment-1895749820 for the
                    // possible explanation.
                    check_enso_benchmarks: TARGET_OS == OS::Linux,
                    verify_packages: true,
                    ..default()
                };
                let context = self.prepare_backend_context(config);
                async move {
                    let context = context.await?;
                    context.build().await
                }
                .void_ok()
                .boxed()
            }
        }
    }

    #[instrument]
    pub fn prepare_backend_context(
        &self,
        config: enso_build::engine::BuildConfigurationFlags,
    ) -> BoxFuture<'static, Result<enso_build::engine::RunContext>> {
        let paths = enso_build::paths::Paths::new_triple(&self.repo_root, self.triple.clone());
        let config = config.into();
        let octocrab = self.octocrab.clone();
        async move {
            let paths = paths?;
            let inner = crate::project::Context {
                repo_root: paths.repo_root.clone(),
                // upload_artifacts: true,
                octocrab,
                cache: Cache::new_default().await?,
            };
            Ok(enso_build::engine::RunContext { inner, config, paths, external_runtime: None })
        }
        .boxed()
    }

    /// Get a handle to the release by its identifier.
    pub fn release(&self, id: ReleaseId) -> release::Handle {
        ide_ci::github::release::Handle::new(&self.octocrab, self.remote_repo.clone(), id)
    }

    /// Upload IDE assets from the build job to the given release.
    pub fn upload_ide_assets(
        &self,
        build_job: BoxFuture<'static, Result<ide::Artifact>>,
        release_id: ReleaseId,
        name_prefix: Option<String>,
    ) -> BoxFuture<'static, Result> {
        let release = self.release(release_id);
        let add_prefix = move |name: String| {
            if let Some(prefix) = name_prefix.clone() {
                format!("{prefix}-{name}")
            } else {
                name
            }
        };
        async move {
            let artifacts = build_job.await?;
            release
                .upload_asset_file_with_custom_name(&artifacts.image, add_prefix.clone())
                .await?;
            release
                .upload_asset_file_with_custom_name(&artifacts.image_checksum, add_prefix)
                .await?;
            Ok(())
        }
        .boxed()
    }

    pub fn handle_ide(&self, ide: arg::ide::Target) -> BoxFuture<'static, Result> {
        match ide.command {
            arg::ide::Command::Build { params } => self.build_old_ide(params).void_ok().boxed(),
            arg::ide::Command::Upload { params, release_id } => {
                let build_job = self.build_old_ide(params);
                self.upload_ide_assets(build_job, release_id, None)
            }
            arg::ide::Command::Start { params, ide_option } => {
                let build_job = self.build_old_ide(params);
                async move {
                    let ide = build_job.await?;
                    ide.start_unpacked(ide_option).run_ok().await?;
                    Ok(())
                }
                .boxed()
            }
            arg::ide::Command::Watch { gui, project_manager, ide_option: ide_watch } => {
                let context = self.context();
                let watch_gui_job = self.resolve_watch_job(gui);
                let project_manager = self.get(project_manager);
                async move {
                    crate::project::Ide::default()
                        .watch(&context, watch_gui_job.await?, project_manager, ide_watch)
                        .await
                }
                .boxed()
            }
            arg::ide::Command::IntegrationTest {
                external_backend,
                project_manager,
                wasm_pack_options,
                headless,
                wasm_timeout,
            } => {
                let custom_root = tempdir();
                let (custom_root, project_manager) = match custom_root {
                    Ok(tempdir) => {
                        let custom_root = Some(tempdir.path().into());
                        (
                            Some(tempdir),
                            Ok(self.spawn_project_manager(project_manager, custom_root)),
                        )
                    }
                    Err(e) => (None, Err(e)),
                };
                let source_root = self.repo_root.to_path_buf();
                async move {
                    let project_manager =
                        if !external_backend { Some(project_manager?.await?) } else { None };
                    Wasm.integration_test(
                        source_root,
                        project_manager,
                        headless,
                        wasm_pack_options,
                        Some(wasm_timeout.into()),
                    )
                    .await?;
                    // Custom root must live while the tests are being run.
                    drop(custom_root);
                    Ok(())
                }
                .boxed()
            }
        }
    }

    pub fn handle_ide2(&self, ide: arg::ide2::Target) -> BoxFuture<'static, Result> {
        match ide.command {
            arg::ide2::Command::Build { params } => self.build_new_ide(params).void_ok().boxed(),
            arg::ide2::Command::Upload { params, release_id } => {
                let build_job = self.build_new_ide(params);
                self.upload_ide_assets(build_job, release_id, Some("ide2".into()))
            }
        }
    }

    /// Spawns a Project Manager.
    pub fn spawn_project_manager(
        &self,
        source: arg::Source<Backend>,
        custom_root: Option<PathBuf>,
    ) -> BoxFuture<'static, Result<Child>> {
        let get_task = self.get(source);
        async move {
            let project_manager = get_task.await?;
            let mut command =
                enso_build::programs::project_manager::spawn_from(&project_manager.path);
            if let Some(custom_root) = custom_root {
                command
                    .set_env(enso_build::programs::project_manager::PROJECTS_ROOT, &custom_root)?;
            }
            command.spawn_intercepting()
        }
        .boxed()
    }
    pub fn build_ide(
        &self,
        input: ide::BuildInput<impl IsArtifact>,
        output_path: OutputPath<impl IsTargetSource + Send + Sync + 'static>,
    ) -> BoxFuture<'static, Result<ide::Artifact>> {
        let target = Ide { target_os: self.triple.os, target_arch: self.triple.arch };
        let artifact_name_prefix = input.artifact_name.clone();
        let build_job = target.build(&self.context, input, output_path);
        async move {
            let artifacts = build_job.await?;
            if is_in_env() {
                artifacts.upload_as_ci_artifact(artifact_name_prefix).await?;
            }
            Ok(artifacts)
        }
        .boxed()
    }

    pub fn build_old_ide(
        &self,
        params: arg::ide::BuildInput<Gui>,
    ) -> BoxFuture<'static, Result<ide::Artifact>> {
        let arg::ide::BuildInput { gui, project_manager, output_path, electron_target } = params;
        let input = ide::BuildInput {
            gui: self.get(gui),
            project_manager: self.get(project_manager),
            version: self.triple.versions.version.clone(),
            electron_target,
            artifact_name: "ide".into(),
        };
        self.build_ide(input, output_path)
    }

    pub fn build_new_ide(
        &self,
        params: arg::ide2::BuildInput,
    ) -> BoxFuture<'static, Result<ide2::Artifact>> {
        let arg::ide::BuildInput { gui, project_manager, output_path, electron_target } = params;

        let build_info_get = self.js_build_info();
        let build_info_path = self.context.inner.repo_root.join(&*enso_build::ide::web::BUILD_INFO);

        let build_info = async move {
            let build_info = build_info_get.await?;
            build_info_path.write_as_json(&build_info)
        };

        let gui = self.get(gui);

        let input = ide::BuildInput {
            gui: async move {
                build_info.await?;
                gui.await
            }
            .boxed(),
            project_manager: self.get(project_manager),
            version: self.triple.versions.version.clone(),
            electron_target,
            artifact_name: "ide2".into(),
        };
        self.build_ide(input, output_path)
    }

    pub fn target<Target: Resolvable>(&self) -> Result<Target> {
        Target::prepare_target(self)
    }
}

pub trait Resolvable: IsTarget + IsTargetSource + Clone {
    fn prepare_target(context: &Processor) -> Result<Self>;

    fn resolve(
        ctx: &Processor,
        from: <Self as IsTargetSource>::BuildInput,
    ) -> BoxFuture<'static, Result<<Self as IsTarget>::BuildInput>>;
}

impl Resolvable for Wasm {
    fn prepare_target(_context: &Processor) -> Result<Self> {
        Ok(Wasm {})
    }

    fn resolve(
        _ctx: &Processor,
        from: <Self as IsTargetSource>::BuildInput,
    ) -> BoxFuture<'static, Result<<Self as IsTarget>::BuildInput>> {
        let arg::wasm::BuildInput {
            crate_path,
            wasm_profile,
            wasm_opt_option: wasm_opt_options,
            cargo_options,
            profiling_level,
            wasm_log_level,
            wasm_uncollapsed_log_level,
            wasm_size_limit,
            skip_wasm_opt,
            system_shader_tools,
        } = from;
        ok_ready_boxed(wasm::BuildInput {
            crate_path,
            wasm_opt_options,
            skip_wasm_opt,
            extra_cargo_options: cargo_options,
            profile: wasm_profile,
            profiling_level,
            log_level: wasm_log_level,
            uncollapsed_log_level: wasm_uncollapsed_log_level,
            wasm_size_limit: wasm_size_limit.filter(|size_limit| size_limit.get_bytes() > 0),
            system_shader_tools,
        })
    }
}

impl Resolvable for Gui {
    fn prepare_target(_context: &Processor) -> Result<Self> {
        Ok(Gui {})
    }

    fn resolve(
        ctx: &Processor,
        from: <Self as IsTargetSource>::BuildInput,
    ) -> BoxFuture<'static, Result<<Self as IsTarget>::BuildInput>> {
        let wasm_source = ctx.resolve(Wasm, from.wasm);
        let build_info = ctx.js_build_info();
        async move { Ok(gui::BuildInput { wasm: wasm_source.await?, build_info }) }.boxed()
    }
}

impl Resolvable for Gui2 {
    fn prepare_target(_context: &Processor) -> Result<Self> {
        Ok(Gui2)
    }

    fn resolve(
        _ctx: &Processor,
        _from: <Self as IsTargetSource>::BuildInput,
    ) -> BoxFuture<'static, Result<<Self as IsTarget>::BuildInput>> {
        ok_ready_boxed(())
    }
}

impl Resolvable for Runtime {
    fn prepare_target(_context: &Processor) -> Result<Self> {
        Ok(Runtime {})
    }

    fn resolve(
        ctx: &Processor,
        from: <Self as IsTargetSource>::BuildInput,
    ) -> BoxFuture<'static, Result<<Self as IsTarget>::BuildInput>> {
        let arg::runtime::BuildInput {} = from;
        ok_ready_boxed(runtime::BuildInput { versions: ctx.triple.versions.clone() })
    }
}

impl Resolvable for Backend {
    fn prepare_target(context: &Processor) -> Result<Self> {
        Ok(Backend { target_os: context.triple.os })
    }

    fn resolve(
        ctx: &Processor,
        from: <Self as IsTargetSource>::BuildInput,
    ) -> BoxFuture<'static, Result<<Self as IsTarget>::BuildInput>> {
        let arg::backend::BuildInput { runtime } = from;
        let versions = ctx.triple.versions.clone();

        let context = ctx.context.inner.clone();

        ctx.resolve(Runtime, runtime)
            .and_then_sync(|runtime| {
                let external_runtime = runtime.to_external().map(move |external| {
                    Arc::new(move || {
                        Runtime
                            .get_external(context.clone(), external.clone())
                            .map_ok(|artifact| artifact.into_inner())
                            .boxed()
                    }) as Arc<EnginePackageProvider>
                });
                Ok(backend::BuildInput { external_runtime, versions })
            })
            .boxed()
    }
}

pub trait WatchResolvable: Resolvable + IsWatchableSource + IsWatchable {
    fn resolve_watch(
        ctx: &Processor,
        from: <Self as IsWatchableSource>::WatchInput,
    ) -> Result<<Self as IsWatchable>::WatchInput>;
}

impl WatchResolvable for Wasm {
    fn resolve_watch(
        _ctx: &Processor,
        from: <Self as IsWatchableSource>::WatchInput,
    ) -> Result<<Self as IsWatchable>::WatchInput> {
        Ok(wasm::WatchInput { cargo_watch_options: from.cargo_watch_option })
    }
}

impl WatchResolvable for Gui {
    fn resolve_watch(
        ctx: &Processor,
        from: <Self as IsWatchableSource>::WatchInput,
    ) -> Result<<Self as IsWatchable>::WatchInput> {
        Ok(gui::WatchInput { wasm: Wasm::resolve_watch(ctx, from.wasm)? })
    }
}

#[tracing::instrument(err, skip(config))]
pub async fn main_internal(config: Option<Config>) -> Result {
    trace!("Starting the build process.");
    let config = config.unwrap_or_else(|| {
        warn!("No config provided, using default config.");
        Config::default()
    });

    trace!("Creating the build context.");
    // Setup that affects Cli parser construction.
    if let Some(wasm_size_limit) = config.wasm_size_limit {
        crate::arg::wasm::initialize_default_wasm_size_limit(wasm_size_limit)?;
    }

    debug!("Initial configuration for the CLI driver: {config:#?}");

    let cli = Cli::parse();

    debug!("Parsed CLI arguments: {cli:#?}");

    if cli.skip_npm_install {
        enso_build::web::assume_installed();
    }

    if !cli.skip_version_check {
        // Let's be helpful!
        let error_message = "Program requirements were not fulfilled. Please do one of the \
        following:\n * Install the tools in the required versions.\n * Update the requirements in \
        `build-config.yaml`.\n * Run the build with `--skip-version-check` flag.";
        config.check_programs().await.context(error_message)?;
    }

    // TRANSITION: Previous Engine CI job used to clone these both repositories side-by-side.
    // This collides with GraalVM native image build location.
    if is_in_env() {
        remove_if_exists(cli.repo_path.join("enso"))?;
        remove_if_exists(cli.repo_path.join("ci-build"))?;
    }

    let ctx: Processor = Processor::new(&cli).instrument(info_span!("Building context.")).await?;
    match cli.target {
        Target::Wasm(wasm) => ctx.handle_wasm(wasm).await?,
        Target::Gui(gui) => ctx.handle_gui(gui).await?,
        Target::Gui2(gui2) => ctx.handle_gui2(gui2).await?,
        Target::Runtime(runtime) => ctx.handle_runtime(runtime).await?,
        // Target::ProjectManager(project_manager) =>
        //     ctx.handle_project_manager(project_manager).await?,
        // Target::Engine(engine) => ctx.handle_engine(engine).await?,
        Target::Backend(backend) => ctx.handle_backend(backend).await?,
        Target::Ide(ide) => ctx.handle_ide(ide).await?,
        Target::Ide2(ide2) => ctx.handle_ide2(ide2).await?,
        Target::GitClean(options) => {
            let crate::arg::git_clean::Options { dry_run, cache, build_script } = options;
            let mut exclusions = vec![".idea"];
            if !build_script {
                exclusions.push("target/rust/buildscript");
            }

            if !dry_run {
                // On Windows, `npm` uses junctions as symbolic links for in-workspace dependencies.
                // Unfortunately, Git for Windows treats those as hard links. That then leads to
                // `git clean` recursing into those linked directories, happily deleting sources of
                // whole linked packages. Manually deleting `node_modules` before running clean
                // prevents this from happening.
                //
                // Related npm issue: https://github.com/npm/npm/issues/19091
                ide_ci::fs::tokio::remove_dir_if_exists(ctx.repo_root.join("node_modules")).await?;
            }

            let git_clean = clean::clean_except_for(&ctx.repo_root, exclusions, dry_run);
            let clean_cache = async {
                if cache && !dry_run {
                    ide_ci::fs::tokio::remove_dir_if_exists(ctx.cache.path()).await?;
                }
                Result::Ok(())
            };
            try_join(git_clean, clean_cache).await?;
        }
        Target::Lint => {
            Cargo
                .cmd()?
                .current_dir(&ctx.repo_root)
                .arg(cargo::clippy::COMMAND)
                .apply(&cargo::Options::Workspace)
                .apply(&cargo::Options::Package("enso-integration-test".into()))
                .apply(&cargo::Options::AllTargets)
                .apply(&cargo::Color::Always)
                .arg("--")
                .apply(&rustc::Option::Deny(rustc::Lint::Warnings))
                .run_ok()
                .await?;

            Cargo
                .cmd()?
                .current_dir(&ctx.repo_root)
                .arg("fmt")
                .args(["--", "--check"])
                .run_ok()
                .await?;

            enso_build::web::install(&ctx.repo_root).await?;
            enso_build::web::run_script(&ctx.repo_root, enso_build::web::Script::Typecheck).await?;
            enso_build::web::run_script(&ctx.repo_root, enso_build::web::Script::Lint).await?;
            enso_build::web::run_script(&ctx.repo_root, enso_build::web::Script::Prettier).await?;
        }
        Target::Fmt => {
            enso_build::web::install(&ctx.repo_root).await?;
            let prettier =
                enso_build::web::run_script(&ctx.repo_root, enso_build::web::Script::Format);
            let our_formatter =
                enso_formatter::process_path(&ctx.repo_root, enso_formatter::Action::Format);
            let (r1, r2) = join!(prettier, our_formatter).await;
            r1?;
            r2?;
        }
        Target::Release(release) => match release.action {
            Action::CreateDraft => {
                let commit = ide_ci::actions::env::GITHUB_SHA.get()?;
                enso_build::release::draft_a_new_release(&ctx, &commit).await?;
            }
            Action::DeployRuntime(args) => {
                enso_build::release::deploy_to_ecr(&ctx, args.ecr_repository).await?;
                enso_build::repo::cloud::build_image_workflow_dispatch_input(
                    &ctx.octocrab,
                    &ctx.triple.versions.version,
                )
                .await?;
            }
            Action::DeployGui(args) => {
                let crate::arg::release::DeployGui {} = args;
                enso_build::release::upload_gui_to_cloud_good(&ctx).await?;
            }
            Action::Publish => {
                enso_build::release::publish_release(&ctx).await?;
            }
            Action::Promote(args) => {
                let crate::arg::release::Promote { designation } = args;
                enso_build::release::promote_release(&ctx, designation).await?;
            }
        },
        Target::JavaGen(command) => {
            let repo_root = ctx.repo_root.clone();
            async move {
                let generate_job = enso_build::rust::parser::generate_java(&repo_root);
                match command.action {
                    java_gen::Command::Build => generate_job.await,
                    java_gen::Command::Test => {
                        generate_job.await?;
                        let backend_context = ctx.prepare_backend_context(default()).await?;
                        backend_context.prepare_build_env().await?;
                        enso_build::rust::parser::run_self_tests(&repo_root).await
                    }
                }
            }
            .await?;
        }
        Target::ChangelogCheck => {
            let ci_context = ide_ci::actions::context::Context::from_env()?;
            enso_build::changelog::check::check(ctx.repo_root.clone(), ci_context).await?;
        }
    };
    info!("Completed main job.");
    global::complete_tasks().await?;
    Ok(())
}

pub fn lib_main(config: Option<Config>) -> Result {
    trace!("Starting the tokio runtime.");
    let rt = tokio::runtime::Runtime::new()?;
    trace!("Entering main.");
    rt.block_on(async { main_internal(config).await })?;
    rt.shutdown_timeout(Duration::from_secs(60 * 30));
    info!("Successfully ending.");
    Ok(())
}
