package org.enso.languageserver.libraries

import io.circe.JsonObject
import org.enso.editions.LibraryName
import org.enso.librarymanager.resolved.LibraryRoot
import org.enso.pkg.{ComponentGroups, Contact}

object LocalLibraryManagerProtocol {

  /** A top class representing any request to the [[LocalLibraryManager]]. */
  sealed trait Request

  /** A request to get metadata of a library. */
  case class GetMetadata(libraryName: LibraryName) extends Request

  /** Response to [[GetMetadata]]. */
  case class GetMetadataResponse(
    description: Option[String],
    tagLine: Option[String]
  )

  /** A request to update metadata of a library. */
  case class SetMetadata(
    libraryName: LibraryName,
    description: Option[String],
    tagLine: Option[String]
  ) extends Request

  /** A request to get the library package. */
  case class GetPackage(libraryName: LibraryName) extends Request

  /** A response to the [[GetPackage]] request. */
  case class GetPackageResponse(
    license: String,
    componentGroups: Option[ComponentGroups],
    rawPackage: JsonObject
  )

  /** A request to list local libraries. */
  case object ListLocalLibraries extends Request

  /** A response to [[ListLocalLibraries]]. */
  case class ListLocalLibrariesResponse(libraries: Seq[LibraryEntry])

  /** A request to create a new library project. */
  case class Create(
    libraryName: LibraryName,
    authors: Seq[Contact],
    maintainers: Seq[Contact],
    license: String
  ) extends Request

  /** A request to find the path to a local library. */
  case class FindLibrary(libraryName: LibraryName) extends Request

  /** A response to [[FindLibrary]]. */
  case class FindLibraryResponse(libraryRoot: Option[LibraryRoot])

  /** Indicates that a library with the given name was not found among local
    * libraries.
    */
  case class LocalLibraryNotFoundError(libraryName: LibraryName)
      extends RuntimeException(
        s"Local library [$libraryName] has not been found."
      )

  /** Indicates that the request succeeded, but it does not have a response.
    *
    * Sent as a reply to [[Create]] and [[SetMetadata]].
    */
  case class EmptyResponse()

  /** A base trait for failures. */
  sealed trait Failure

  /** An error indicating that the provided version is not a valid semver
    * version.
    *
    * @version invalid version string
    */
  case class InvalidSemverVersionError(version: String) extends Failure
}
