use crate::prelude::*;

use crate::ide::web::IdeDesktop;
use crate::paths::generated::RepoRootTargetEnsoglPackLinkedDist;
use crate::project::perhaps_watch;
use crate::project::Context;
use crate::project::IsArtifact;
use crate::project::IsTarget;
use crate::project::IsWatchable;
use crate::project::IsWatcher;
use crate::project::PerhapsWatched;
use crate::project::Wasm;
use crate::source::BuildTargetJob;
use crate::source::GetTargetJob;
use crate::source::WatchTargetJob;
use crate::source::WithDestination;
use crate::BoxFuture;

use derivative::Derivative;
use futures_util::future::try_join;
use ide_ci::fs::tokio::create_dir_if_missing;
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

    pub fn symlink_ensogl_dist(&self, linked_dist: &RepoRootTargetEnsoglPackLinkedDist) -> Result {
        ide_ci::fs::remove_symlink_dir_if_exists(linked_dist)?;
        ide_ci::fs::symlink_auto(&self.ensogl_app, linked_dist)
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
        job: BuildTargetJob<Self>,
    ) -> BoxFuture<'static, Result<Self::Artifact>> {
        let WithDestination { inner, destination } = job;
        async move {
            let ide = ide_desktop_from_context(&context);
            let wasm = Wasm.get(context, inner.wasm);
            let content_env =
                ide.build_content(wasm, &inner.build_info.await?, &destination).await?;

            let ret = Artifact::new(destination.clone());
            let ensogl_app_dir = &ret.0.ensogl_app;
            create_dir_if_missing(ensogl_app_dir).await?;
            let ensogl_app_files = [
                &content_env.wasm.0.index_js.path,
                &content_env.wasm.0.index_d_ts.path,
                &content_env.wasm.0.index_js_map.path,
            ];
            for file in ensogl_app_files {
                ide_ci::fs::copy_to(file, ensogl_app_dir)?;
            }
            Ok(ret)
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
        let WatchTargetJob { watch_input, build: WithDestination { inner, destination } } = job;
        let BuildInput { build_info, wasm } = inner;
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
        let WatchTargetJob { watch_input, build: WithDestination { inner, destination } } = job;
        let BuildInput { build_info, wasm } = inner;
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
