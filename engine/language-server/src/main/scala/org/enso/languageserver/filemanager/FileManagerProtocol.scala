package org.enso.languageserver.filemanager

object FileManagerProtocol {

  /**
    * Requests the Language Server write textual content to an arbitrary file.
    *
    * @param path a path to a file
    * @param content a textual content
    */
  case class WriteFile(path: Path, content: String)

  /**
    * Signals file manipulation status.
    *
    * @param result either file system failure or unit representing success
    */
  case class WriteFileResult(result: Either[FileSystemFailure, Unit])

  /**
    * Requests the Language Server read a file.
    *
    * @param path a path to a file
    */
  case class ReadFile(path: Path)

  /**
    * Returns a result of reading a file.
    *
    * @param result either file system failure or content of a file
    */
  case class ReadFileResult(result: Either[FileSystemFailure, String])

  /**
    * Requests the Language Server create a file system object.
    *
    * @param `object` a file system object
    */
  case class CreateFile(`object`: FileSystemObject)

  /**
    * Returns a result of creating a file system object.
    *
    * @param result either file system failure or unit representing success
    */
  case class CreateFileResult(result: Either[FileSystemFailure, Unit])

  /**
    * Requests the Language Server delete a file system object.
    *
    * @param path a path to a file
    */
  case class DeleteFile(path: Path)

  /**
    * Returns a result of deleting a file system object.
    *
    * @param result either file system failure or unit representing success
    */
  case class DeleteFileResult(result: Either[FileSystemFailure, Unit])

  /**
    * Requests the Language Server copy a file system object.
    *
    * @param from a path to the source
    * @param to a path to the destination
    */
  case class CopyFile(from: Path, to: Path)

  /**
    * Returns a result of copying a file system object.
    *
    * @param result either file system failure or unit representing success
    */
  case class CopyFileResult(result: Either[FileSystemFailure, Unit])

  /**
    * Requests the Language Server to check the existence of file system object.
    *
    * @param path a path to a file
    */
  case class ExistsFile(path: Path)

  /**
    * Returns a result of checking the existence of file system object.
    *
    * @param result either file system failure or file existence flag
    */
  case class ExistsFileResult(result: Either[FileSystemFailure, Boolean])
}
