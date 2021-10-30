package org.enso.languageserver.libraries

import org.enso.editions.LibraryName
import org.enso.pkg.Contact

import java.nio.file.Path

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
  case class FindLibraryResponse(libraryRoot: Option[Path])

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
}
