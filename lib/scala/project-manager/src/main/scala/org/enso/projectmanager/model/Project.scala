package org.enso.projectmanager.model

import org.enso.editions.Editions

import java.nio.file.attribute.FileTime
import java.time.OffsetDateTime
import java.util.UUID

/** Project entity.
  *
  * @param id a project id
  * @param name a project name
  * @param kind a project kind
  * @param created a project creation time
  * @param edition the edition configuration associated with the project
  * @param lastOpened a project last open time
  * @param path a path to the project structure
  */
case class Project(
  id: UUID,
  name: String,
  kind: ProjectKind,
  created: OffsetDateTime,
  edition: Editions.RawEdition,
  lastOpened: Option[OffsetDateTime]      = None,
  path: Option[String]                    = None,
  directoryCreationTime: Option[FileTime] = None
)
