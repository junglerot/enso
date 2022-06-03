package org.enso.compiler

import com.oracle.truffle.api.TruffleFile
import com.typesafe.scalalogging.Logger
import org.enso.distribution.locking.ResourceManager
import org.enso.distribution.{DistributionManager, LanguageHome}
import org.enso.editions.updater.EditionManager
import org.enso.editions.{DefaultEdition, LibraryName, LibraryVersion}
import org.enso.interpreter.instrument.NotificationHandler
import org.enso.interpreter.runtime.builtin.Builtins
import org.enso.interpreter.runtime.util.TruffleFileSystem
import org.enso.interpreter.runtime.{Context, Module}
import org.enso.librarymanager.resolved.LibraryRoot
import org.enso.librarymanager.{
  DefaultLibraryProvider,
  ResolvingLibraryProvider
}
import org.enso.logger.masking.MaskedPath
import org.enso.pkg.{
  Component,
  ComponentGroup,
  ComponentGroups,
  ExtendedComponentGroup,
  Package,
  PackageManager,
  QualifiedName
}

import java.nio.file.Path

import scala.collection.immutable.ListSet
import scala.util.Try

/** Manages loaded packages and modules. */
trait PackageRepository {

  /** Initialize the package repository.
    *
    * @return `Right` if the package repository initialized successfully,
    * and a `Left` containing an error otherwise.
    */
  def initialize(): Either[PackageRepository.Error, Unit]

  /** Informs the repository that it should populate the top scope with modules
    * belonging to a given package.
    *
    * @param libraryName the name of the library that should be loaded
    * @return `Right(())` if the package was already loaded or successfully
    *         downloaded. A `Left` containing an error otherwise.
    */
  def ensurePackageIsLoaded(
    libraryName: LibraryName
  ): Either[PackageRepository.Error, Unit]

  /** Get a sequence of currently loaded packages. */
  def getLoadedPackages: Seq[Package[TruffleFile]]

  /** Get a sequence of currently loaded modules. */
  def getLoadedModules: Seq[Module]

  /** Get the mapping from qualified module names (equivalent to
    * [[QualifiedName.toString]]) to modules.
    *
    * This map may be updated concurrently.
    */
  def getModuleMap: PackageRepository.ModuleMap

  /** Gets a frozen form of the module map that cannot be updated concurrently.
    */
  def freezeModuleMap: PackageRepository.FrozenModuleMap

  /** Get the loaded library components. */
  def getComponents: PackageRepository.ComponentsMap

  /** Modules required for compilation after loading the component groups. */
  def getPendingModules: ListSet[Module]

  /** Get a loaded module by its qualified name. */
  def getLoadedModule(qualifiedName: String): Option[Module]

  /** Register the main project package. */
  def registerMainProjectPackage(
    libraryName: LibraryName,
    pkg: Package[TruffleFile]
  ): Unit

  /** @return the main project package, if it exists
    */
  def getMainProjectPackage: Option[Package[TruffleFile]]

  /** Register a single module, outside of any packages or part of an already
    * loaded package, that has been created manually during runtime.
    */
  def registerModuleCreatedInRuntime(module: Module): Unit

  /** Removes a module with the given name from the list of loaded modules. */
  def deregisterModule(qualifiedName: String): Unit

  /** Modifies package and module names to reflect the project name change. */
  def renameProject(namespace: String, oldName: String, newName: String): Unit
}

object PackageRepository {

  type ModuleName      = String
  type ModuleMap       = collection.concurrent.Map[ModuleName, Module]
  type FrozenModuleMap = Map[ModuleName, Module]
  type ComponentsMap   = Map[LibraryName, ComponentGroups]

  /** A trait representing errors reported by this system */
  sealed trait Error

  object Error {

    /** Indicates that a resolution error has happened, for example the package
      * was not defined in the selected edition.
      */
    case class PackageCouldNotBeResolved(cause: Throwable) extends Error {
      override def toString: String =
        s"The package could not be resolved: ${cause.getMessage}"
    }

    /** Indicates that the package was missing and a download was attempted, but
      * it failed - for example due to connectivity problems or just because the
      * package did not exist in the repository.
      */
    case class PackageDownloadFailed(cause: Throwable) extends Error {
      override def toString: String =
        s"The package download has failed: ${cause.getMessage}"
    }

    /** Indicates that the package was already present in the cache (or within
      * local packages), but it could not be loaded, possibly to a filesystem
      * error or insufficient permissions.
      */
    case class PackageLoadingError(cause: String) extends Error {
      override def toString: String =
        s"The package could not be loaded: $cause"
    }
  }

  /** The default [[PackageRepository]] implementation.
    *
    * @param libraryProvider     the [[ResolvingLibraryProvider]] which resolves
    *                            which library version should be imported and
    *                            locates them (or downloads if they are missing)
    * @param context             the language context
    * @param builtins            the builtins module
    * @param notificationHandler a notification handler
    */
  class Default(
    libraryProvider: ResolvingLibraryProvider,
    context: Context,
    builtins: Builtins,
    notificationHandler: NotificationHandler
  ) extends PackageRepository {

    private val logger = Logger[Default]

    implicit private val fs: TruffleFileSystem               = new TruffleFileSystem
    private val packageManager                               = new PackageManager[TruffleFile]
    private var projectPackage: Option[Package[TruffleFile]] = None

    /** The mapping containing all loaded packages.
      *
      * It should be modified only from within synchronized sections, but it may
      * be always read. Thus elements should be added to this mapping only after
      * all library loading bookkeeping has been finished - so that if other,
      * unsynchronized threads read this map, every element it contains is
      * already fully processed.
      */
    private val loadedPackages
      : collection.mutable.Map[LibraryName, Option[Package[TruffleFile]]] = {
      val builtinsName = LibraryName(Builtins.NAMESPACE, Builtins.PACKAGE_NAME)
      collection.mutable.LinkedHashMap(builtinsName -> None)
    }

    /** The mapping containing loaded modules.
      *
      * We use [[String]] as the key as we often index into this map based on
      * qualified names that come from interop (via
      * [[org.enso.interpreter.runtime.scope.TopLevelScope]]). These arrive as
      * Strings, and constantly converting them into [[QualifiedName]]s would
      * add more overhead than is probably necessary.
      */
    private val loadedModules: collection.concurrent.Map[String, Module] =
      collection.concurrent.TrieMap(Builtins.MODULE_NAME -> builtins.getModule)

    /** The mapping containing loaded component groups.
      *
      * It should be modified and read only from within synchronized sections.
      * The component mapping is added to the collection after ensuring that the
      * corresponding library was loaded.
      */
    private val loadedComponents
      : collection.mutable.Map[LibraryName, ComponentGroups] = {
      val builtinsName = LibraryName(Builtins.NAMESPACE, Builtins.PACKAGE_NAME)
      collection.mutable.LinkedHashMap(builtinsName -> ComponentGroups.empty)
    }

    private def getComponentModules: ListSet[Module] = {
      val modules = for {
        componentGroups <- loadedComponents.values
        newComponents      = componentGroups.newGroups.flatMap(_.exports)
        extendedComponents = componentGroups.extendedGroups.flatMap(_.exports)
        component <- newComponents ++ extendedComponents
        module    <- findComponentModule(component)
      } yield module
      modules.to(ListSet)
    }

    private def findComponentModule(component: Component): Option[Module] = {
      def mkModuleName(path: Array[String]): String =
        path.mkString(LibraryName.separator.toString)
      @scala.annotation.tailrec
      def go(path: Array[String]): Option[Module] =
        if (path.isEmpty) None
        else {
          loadedModules.get(mkModuleName(path)) match {
            case Some(module) => Some(module)
            case None         => go(path.init)
          }
        }

      go(component.name.split(LibraryName.separator))
    }

    /** @inheritdoc */
    override def getModuleMap: ModuleMap = loadedModules

    /** @inheritdoc */
    override def freezeModuleMap: FrozenModuleMap = loadedModules.toMap

    /** @inheritdoc */
    override def getComponents: ComponentsMap =
      this.synchronized {
        loadedComponents.toMap
      }

    /** @inheritdoc */
    override def getPendingModules: ListSet[Module] =
      this.synchronized {
        for {
          module <- getComponentModules
          isCompiled =
            module.getCompilationStage.isAtLeast(
              Module.CompilationStage.AFTER_CODEGEN
            )
          if !isCompiled
        } yield module
      }

    /** @inheritdoc */
    override def registerMainProjectPackage(
      libraryName: LibraryName,
      pkg: Package[TruffleFile]
    ): Unit = {
      projectPackage = Some(pkg)
      registerPackageInternal(
        libraryName    = libraryName,
        pkg            = pkg,
        libraryVersion = LibraryVersion.Local,
        isLibrary      = false
      )
    }

    /** @inheritdoc */
    override def getMainProjectPackage: Option[Package[TruffleFile]] = {
      projectPackage
    }

    private def registerPackageInternal(
      libraryName: LibraryName,
      libraryVersion: LibraryVersion,
      pkg: Package[TruffleFile],
      isLibrary: Boolean
    ): Unit = {
      val extensions = pkg.listPolyglotExtensions("java")
      extensions.foreach(context.getEnvironment.addToHostClassPath)

      pkg.listSources
        .map { srcFile =>
          new Module(srcFile.qualifiedName, pkg, srcFile.file)
        }
        .foreach(registerModule)

      if (isLibrary) {
        val root = Path.of(pkg.root.toString)
        notificationHandler.addedLibrary(libraryName, libraryVersion, root)
      }

      loadedPackages.put(libraryName, Some(pkg))
    }

    /** This package modifies the [[loadedPackages]], so it should be only
      * called from within synchronized sections.
      */
    private def loadPackage(
      libraryName: LibraryName,
      libraryVersion: LibraryVersion,
      root: LibraryRoot
    ): Either[Error, Package[TruffleFile]] = Try {
      logger.debug(
        s"Loading library $libraryName from " +
        s"[${MaskedPath(root.location).applyMasking()}]."
      )
      val rootFile = context.getEnvironment.getInternalTruffleFile(
        root.location.toAbsolutePath.normalize.toString
      )
      val pkg = packageManager.loadPackage(rootFile).get
      registerPackageInternal(
        libraryName    = libraryName,
        libraryVersion = libraryVersion,
        pkg            = pkg,
        isLibrary      = true
      )
      pkg
    }.toEither.left.map { error => Error.PackageLoadingError(error.getMessage) }

    /** @inheritdoc */
    override def initialize(): Either[Error, Unit] =
      this.synchronized {
        val unprocessedPackages =
          loadedPackages.keySet
            .diff(loadedComponents.keySet)
            .flatMap(loadedPackages(_))
        unprocessedPackages.foldLeft[Either[Error, Unit]](Right(())) {
          (accumulator, pkg) =>
            for {
              _ <- accumulator
              _ <- resolveComponentGroups(pkg)
            } yield ()
        }
      }

    private def resolveComponentGroups(
      pkg: Package[TruffleFile]
    ): Either[Error, Unit] =
      if (loadedComponents.contains(pkg.libraryName)) Right(())
      else {
        pkg.config.componentGroups match {
          case Left(err) =>
            Left(Error.PackageLoadingError(err.getMessage()))
          case Right(componentGroups) =>
            logger.debug(
              s"Resolving component groups of package [${pkg.name}]."
            )

            registerComponentGroups(pkg.libraryName, componentGroups.newGroups)
            componentGroups.extendedGroups
              .foldLeft[Either[Error, Unit]](Right(())) {
                (accumulator, componentGroup) =>
                  for {
                    _ <- accumulator
                    extendedLibraryName = componentGroup.group.libraryName
                    _ <- ensurePackageIsLoaded(extendedLibraryName)
                    pkgOpt = loadedPackages(extendedLibraryName)
                    _ <- pkgOpt.fold[Either[Error, Unit]](Right(()))(
                      resolveComponentGroups
                    )
                    _ = registerExtendedComponentGroup(
                      pkg.libraryName,
                      componentGroup
                    )
                  } yield ()
              }
        }
      }

    /** Register the list of component groups defined by a library.
      *
      * @param library the library name
      * @param newGroups the list of component groups that the library defines
      */
    private def registerComponentGroups(
      library: LibraryName,
      newGroups: List[ComponentGroup]
    ): Unit =
      loadedComponents.updateWith(library) {
        case Some(groups) =>
          Some(groups.copy(newGroups = groups.newGroups ::: newGroups))
        case None =>
          Some(ComponentGroups(newGroups, List()))
      }

    /** Register a component group extended by a library.
      *
      * @param library the library name
      * @param group the extended component group
      */
    private def registerExtendedComponentGroup(
      library: LibraryName,
      group: ExtendedComponentGroup
    ): Unit =
      loadedComponents.updateWith(library) {
        case Some(groups) =>
          Some(groups.copy(extendedGroups = groups.extendedGroups :+ group))
        case None =>
          Some(ComponentGroups(List(), List(group)))
      }

    /** @inheritdoc */
    override def ensurePackageIsLoaded(
      libraryName: LibraryName
    ): Either[Error, Unit] =
      if (loadedPackages.contains(libraryName)) Right(())
      else {
        logger.trace(s"Resolving library $libraryName.")
        val resolvedLibrary = libraryProvider.findLibrary(libraryName)
        resolvedLibrary match {
          case Left(error) =>
            logger.warn(s"Resolution failed with [$error].", error)
          case Right(resolved) =>
            logger.info(
              s"Found library ${resolved.name} @ ${resolved.version} " +
              s"at [${MaskedPath(resolved.root.location).applyMasking()}]."
            )
        }

        this.synchronized {
          // We check again inside of the monitor, in case that some other
          // thread has just added this library.
          if (loadedPackages.contains(libraryName)) Right(())
          else
            resolvedLibrary
              .flatMap { library =>
                loadPackage(library.name, library.version, library.root)
              }
              .flatMap(resolveComponentGroups)
              .left
              .map {
                case ResolvingLibraryProvider.Error.NotResolved(details) =>
                  Error.PackageCouldNotBeResolved(details)
                case ResolvingLibraryProvider.Error.DownloadFailed(_, reason) =>
                  Error.PackageDownloadFailed(reason)
                case ResolvingLibraryProvider.Error.RequestedLocalLibraryDoesNotExist =>
                  Error.PackageLoadingError(
                    "The local library has not been found on the local " +
                    "libraries search paths."
                  )
              }
        }
      }

    /** @inheritdoc */
    override def getLoadedModules: Seq[Module] =
      loadedModules.values.toSeq

    /** @inheritdoc */
    override def getLoadedPackages: Seq[Package[TruffleFile]] =
      loadedPackages.values.toSeq.flatten

    /** @inheritdoc */
    override def getLoadedModule(qualifiedName: String): Option[Module] =
      loadedModules.get(qualifiedName)

    /** @inheritdoc */
    override def registerModuleCreatedInRuntime(module: Module): Unit =
      registerModule(module)

    private def registerModule(module: Module): Unit =
      loadedModules.put(module.getName.toString, module)

    override def deregisterModule(qualifiedName: String): Unit =
      loadedModules.remove(qualifiedName)

    /** @inheritdoc */
    override def renameProject(
      namespace: String,
      oldName: String,
      newName: String
    ): Unit = this.synchronized {
      renamePackages(namespace, oldName, newName)
      renameModules(namespace, oldName, newName)
    }

    private def renamePackages(
      namespace: String,
      oldName: String,
      newName: String
    ): Unit = {
      val toChange = loadedPackages.toSeq.filter { case (name, _) =>
        name.namespace == namespace && name.name == oldName
      }

      for ((key, _) <- toChange) {
        loadedPackages.remove(key)
      }

      for ((key, pkgOption) <- toChange) {
        val newPkg = pkgOption.map(_.setPackageName(newName))
        val newKey = key.copy(name = newName)
        loadedPackages.put(newKey, newPkg)
      }
    }

    private def renameModules(
      namespace: String,
      oldName: String,
      newName: String
    ): Unit = {
      val separator: String = QualifiedName.separator
      val keys = loadedModules.keySet.filter(name =>
        name.startsWith(namespace + separator + oldName + separator)
      )

      for {
        key    <- keys
        module <- loadedModules.remove(key)
      } {
        module.renameProject(newName)
        loadedModules.put(module.getName.toString, module)
      }
    }
  }

  /** Creates a [[PackageRepository]] for the run.
    *
    * It tries to load and resolve the edition used in the project (or the
    * default edition), so that any libraries to be loaded can be resolved using
    * that edition.
    *
    * Edition and library search paths are based on the distribution and
    * language home (if it is provided).
    *
    * @param projectPackage      the package of the current project (if ran inside of a project)
    * @param languageHome        the language home (if set)
    * @param distributionManager the distribution manager
    * @param resourceManager     the resource manager instance
    * @param context             the context reference, needed to add polyglot libraries to
    *                            the classpath
    * @param builtins            the builtins that are always preloaded
    * @param notificationHandler a handler for library addition and progress
    *                            notifications
    * @return an initialized [[PackageRepository]]
    */
  def initializeRepository(
    projectPackage: Option[Package[TruffleFile]],
    languageHome: Option[String],
    distributionManager: DistributionManager,
    resourceManager: ResourceManager,
    context: Context,
    builtins: Builtins,
    notificationHandler: NotificationHandler
  ): PackageRepository = {
    val rawEdition = projectPackage
      .flatMap(_.config.edition)
      .getOrElse(DefaultEdition.getDefaultEdition)

    val homeManager    = languageHome.map { home => LanguageHome(Path.of(home)) }
    val editionManager = EditionManager(distributionManager, homeManager)
    val edition        = editionManager.resolveEdition(rawEdition).get

    val resolvingLibraryProvider =
      DefaultLibraryProvider.make(
        distributionManager = distributionManager,
        resourceManager     = resourceManager,
        lockUserInterface   = notificationHandler,
        progressReporter    = notificationHandler,
        languageHome        = homeManager,
        edition             = edition,
        preferLocalLibraries =
          projectPackage.exists(_.config.preferLocalLibraries)
      )
    new Default(
      resolvingLibraryProvider,
      context,
      builtins,
      notificationHandler
    )
  }
}
