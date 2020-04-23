package org.enso.languageserver.filemanager

import org.enso.languageserver.filemanager.FileManagerApi.{
  ContentRootNotFoundError,
  FileExistsError,
  FileNotFoundError,
  FileSystemError,
  NotDirectoryError,
  OperationTimeoutError
}
import org.enso.jsonrpc.Error
import org.enso.languageserver.protocol.rpc.ErrorApi

object FileSystemFailureMapper {

  /**
    * Maps [[FileSystemFailure]] into JSON RPC error.
    *
    * @param fileSystemFailure file system specific failure
    * @return JSON RPC error
    */
  def mapFailure(fileSystemFailure: FileSystemFailure): Error =
    fileSystemFailure match {
      case ContentRootNotFound              => ContentRootNotFoundError
      case AccessDenied                     => ErrorApi.AccessDeniedError
      case FileNotFound                     => FileNotFoundError
      case FileExists                       => FileExistsError
      case OperationTimeout                 => OperationTimeoutError
      case NotDirectory                     => NotDirectoryError
      case GenericFileSystemFailure(reason) => FileSystemError(reason)
    }

}
