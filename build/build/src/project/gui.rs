use crate::prelude::*;

use crate::ide::web::IdeDesktop;
use crate::project::perhaps_watch;
use crate::project::Context;
use crate::project::IsArtifact;
use crate::project::IsTarget;
use crate::project::IsWatchable;
use crate::project::IsWatcher;
use crate::project::PerhapsWatched;
use crate::project::Wasm;
use crate::source::BuildSource;
use crate::source::GetTargetJob;
use crate::source::WatchTargetJob;
use crate::source::WithDestination;
use crate::BoxFuture;

use derivative::Derivative;
use futures_util::future::try_join;
use ide_ci::ok_ready_boxed;



#[derive(Clone, Debug, PartialEq, Eq, Hash, Deref)]
pub struct Artifact(crate::paths::generated::RepoRootDistGui);

impl AsRef<Path> for Artifact {
    fn as_ref(&self) -> &Path {
        self.0.as_path()
    }
}

impl IsArtifact for Artifact {}

impl Artifact {
    pub fn new(gui_path: impl AsRef<Path>) -> Self {
        // TODO: sanity check
        Self(crate::paths::generated::RepoRootDistGui::new_root(gui_path.as_ref()))
    }
}

#[derive(Clone, Derivative, derive_more::Deref)]
#[derivative(Debug)]
pub struct WatchInput {
    #[deref]
    pub wasm: <Wasm as IsWatchable>::WatchInput,
}

#[derive(derivative::Derivative)]
#[derivative(Debug)]
pub struct BuildInput {
    #[derivative(Debug = "ignore")]
    pub wasm:       GetTargetJob<Wasm>,
    /// BoxFuture<'static, Result<wasm::Artifact>>,
    #[derivative(Debug = "ignore")]
    pub build_info: BoxFuture<'static, Result<BuildInfo>>,
}

/// Watcher of the GUI (including WASM watcher).
#[derive(Debug)]
pub struct Watcher {
    /// WASM watcher.
    pub wasm: PerhapsWatched<Wasm>,
    /// Watcher of the content project.
    pub web:  crate::project::Watcher<Gui, crate::ide::web::Watcher>,
}

impl AsRef<Artifact> for Watcher {
    fn as_ref(&self) -> &Artifact {
        &self.web.artifact
    }
}

impl IsWatcher<Gui> for Watcher {
    fn wait_for_finish(&mut self) -> BoxFuture<Result> {
        let Self { web, wasm } = self;
        try_join(wasm.wait_ok(), IsWatcher::wait_for_finish(web)).void_ok().boxed()
    }
}

/// Override the default value of `newDashboard` in `config.json` to `true`.
///
/// This is a temporary workaround. We want to enable the new dashboard by default in the CI-built
/// IDE, but we don't want to enable it by default in the IDE built locally by developers.
pub fn override_default_for_authentication(
    path: &crate::paths::generated::RepoRootAppIdeDesktopLibContentConfigSrcConfigJson,
) -> Result {
    let json_path = ["groups", "featurePreview", "options", "newDashboard", "value"];
    let mut json = ide_ci::fs::read_json::<serde_json::Value>(path)?;
    let mut current =
        json.as_object_mut().ok_or_else(|| anyhow!("Failed to find object in {:?}", path))?;
    for key in &json_path[..json_path.len() - 1] {
        current = current
            .get_mut(*key)
            .with_context(|| format!("Failed to find {key:?} in {path:?}"))?
            .as_object_mut()
            .with_context(|| format!("Failed to find object at {key:?} in {path:?}"))?;
    }
    current.insert(json_path.last().unwrap().to_string(), serde_json::Value::Bool(true));
    ide_ci::fs::write_json(path, &json)?;
    Ok(())
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Gui;

#[async_trait]
impl IsTarget for Gui {
    type BuildInput = BuildInput;
    type Artifact = Artifact;

    fn artifact_name(&self) -> String {
        "gui".into()
    }

    fn adapt_artifact(self, path: impl AsRef<Path>) -> BoxFuture<'static, Result<Self::Artifact>> {
        ok_ready_boxed(Artifact::new(path))
    }

    fn build_internal(
        &self,
        context: Context,
        job: WithDestination<Self::BuildInput>,
    ) -> BoxFuture<'static, Result<Self::Artifact>> {
        let WithDestination { inner, destination } = job;
        async move {
            // TODO: [mwu]
            //  This is a temporary workaround until https://github.com/enso-org/enso/issues/6662
            //  is resolved.
            if ide_ci::actions::workflow::is_in_env() {
                let path = &context.repo_root.app.ide_desktop.lib.content_config.src.config_json;
                warn!("Overriding default for authentication in {}", path.display());
                override_default_for_authentication(path)?;
            }

            let ide = ide_desktop_from_context(&context);
            crate::web::install(&ide.repo_root).await?;

            let wasm = Wasm.get(context, inner.wasm);
            ide.build_content(wasm, &inner.build_info.await?, &destination).await?;
            Ok(Artifact::new(destination.clone()))
        }
        .boxed()
    }
}

impl IsWatchable for Gui {
    type Watcher = Watcher;
    type WatchInput = WatchInput;

    // fn setup_watcher(
    //     &self,
    //     build_input: Self::BuildInput,
    //     watch_input: Self::WatchInput,
    //     output_path: impl AsRef<Path> + Send + Sync + 'static,
    // ) -> BoxFuture<'static, Result<Self::Watcher>> {
    //     async move {
    //         let BuildInput { build_info, repo_root, wasm } = build_input;
    //         let ide = IdeDesktop::new(&repo_root.app.ide_desktop);
    //         let watch_process = ide.watch_content(wasm, &build_info.await?).await?;
    //         let artifact = Self::Artifact::from_existing(output_path).await?;
    //         Ok(Self::Watcher { watch_process, artifact })
    //     }
    //     .boxed()
    // }

    fn watch(
        &self,
        context: Context,
        job: WatchTargetJob<Self>,
    ) -> BoxFuture<'static, Result<Self::Watcher>> {
        let WatchTargetJob {
            watch_input,
            build:
                WithDestination { inner: BuildSource { input, should_upload_artifact: _ }, destination },
        } = job;
        let BuildInput { build_info, wasm } = input;
        let perhaps_watched_wasm = perhaps_watch(Wasm, context.clone(), wasm, watch_input.wasm);
        let ide = ide_desktop_from_context(&context);
        async move {
            let perhaps_watched_wasm = perhaps_watched_wasm.await?;
            let wasm_artifacts = ok_ready_boxed(perhaps_watched_wasm.as_ref().clone());
            let watch_process = ide.watch_content(wasm_artifacts, &build_info.await?).await?;
            let artifact = Artifact::new(&destination);
            let web_watcher = crate::project::Watcher { watch_process, artifact };
            Ok(Self::Watcher { wasm: perhaps_watched_wasm, web: web_watcher })
        }
        .boxed()
    }
}

impl Gui {
    /// Setup watcher for WASM.
    ///
    /// If the WASM is static (e.g. comes from a release), it will be just referenced.
    pub fn perhaps_setup_wasm_watcher(
        &self,
        context: Context,
        job: WatchTargetJob<Self>,
    ) -> GuiBuildWithWatchedWasm {
        let WatchTargetJob {
            watch_input,
            build:
                WithDestination { inner: BuildSource { input, should_upload_artifact: _ }, destination },
        } = job;
        let BuildInput { build_info, wasm } = input;
        let WatchInput { wasm: wasm_watch_input } = watch_input;
        let perhaps_watched_wasm = perhaps_watch(Wasm, context, wasm, wasm_watch_input);
        GuiBuildWithWatchedWasm { perhaps_watched_wasm, build_info, destination }
    }
}

/// Watch job for the `Gui` target with already created watcher for the `Wasm` target.
// Futures cannot be sensibly printed and there is little else of interest here.
#[allow(missing_debug_implementations)]
pub struct GuiBuildWithWatchedWasm {
    /// WASM artifacts provider.
    pub perhaps_watched_wasm: BoxFuture<'static, Result<PerhapsWatched<Wasm>>>,
    /// Information for GUI build.
    pub build_info:           BoxFuture<'static, Result<BuildInfo>>,
    /// Path to the directory where the GUI should be built. Might be ignored in some watching
    /// scenarios.
    pub destination:          PathBuf,
}

#[derive(Clone, Derivative, Serialize, Deserialize)]
#[derivative(Debug)]
#[serde(rename_all = "camelCase")]
pub struct BuildInfo {
    pub commit:         String,
    #[derivative(Debug(format_with = "std::fmt::Display::fmt"))]
    pub version:        Version,
    #[derivative(Debug(format_with = "std::fmt::Display::fmt"))]
    pub engine_version: Version,
    pub name:           String,
}

pub fn ide_desktop_from_context(context: &Context) -> IdeDesktop {
    IdeDesktop::new(&context.repo_root, context.octocrab.clone(), context.cache.clone())
}
