package org.enso.languageserver.filemanager

import java.io.File

/**
  * File manipulation API.
  *
  * @tparam F represents target monad
  */
trait FileSystemApi[F[_]] {

  /**
    * Writes textual content to a file.
    *
    * @param file path to the file
    * @param content a textual content of the file
    * @return either [[FileSystemFailure]] or Unit
    */
  def write(
    file: File,
    content: String
  ): F[Either[FileSystemFailure, Unit]]

  /**
    * Reads the contents of a textual file.
    *
    * @param file path to the file
    * @return either [[FileSystemFailure]] or the content of a file as a String
    */
  def read(file: File): F[Either[FileSystemFailure, String]]

  /**
    * Creates an empty file with parent directory.
    *
    * @param file path to the file
    * @return
    */
  def createFile(file: File): F[Either[FileSystemFailure, Unit]]

  /**
    * Creates a directory, including any necessary but nonexistent parent
    * directories.
    *
    * @param file path to the file
    * @return
    */
  def createDirectory(file: File): F[Either[FileSystemFailure, Unit]]

}
