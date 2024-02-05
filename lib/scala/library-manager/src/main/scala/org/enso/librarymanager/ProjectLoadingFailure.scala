package org.enso.librarymanager

class ProjectLoadingFailure(name: String, cause: Throwable)
    extends RuntimeException(
      s"The runtime was run in context of a project [$name], " +
      s"but the project's package could not be loaded (caused by: " +
      s"${cause.getMessage}).",
      cause
    )
