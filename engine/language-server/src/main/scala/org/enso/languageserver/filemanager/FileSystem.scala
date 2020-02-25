package org.enso.languageserver.filemanager

import java.io.{File, IOException}
import java.nio.file._

import cats.effect.Sync
import cats.implicits._
import org.apache.commons.io.FileUtils

/**
  * File manipulation facility.
  *
  * @tparam F represents target monad
  */
class FileSystem[F[_]: Sync] extends FileSystemApi[F] {

  /**
    * Writes textual content to a file.
    *
    * @param file path to the file
    * @param content    a textual content of the file
    * @return either FileSystemFailure or Unit
    */
  override def write(
    file: File,
    content: String
  ): F[Either[FileSystemFailure, Unit]] =
    Sync[F].delay { writeStringToFile(file, content) }

  private def writeStringToFile(
    file: File,
    content: String
  ): Either[FileSystemFailure, Unit] =
    Either
      .catchOnly[IOException](
        FileUtils.write(file, content, "UTF-8")
      )
      .leftMap {
        case _: AccessDeniedException => AccessDenied
        case ex                       => GenericFileSystemFailure(ex.getMessage)
      }
      .map(_ => ())

}
