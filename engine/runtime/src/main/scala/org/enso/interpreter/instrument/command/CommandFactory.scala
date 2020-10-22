package org.enso.interpreter.instrument.command

import org.enso.polyglot.runtime.Runtime.Api

/** A factory that creates a command for an API request.
  */
object CommandFactory {

  /** Creates a command that encapsulates a function request as an object.
    *
    * @param request an API request
    * @return a command
    */
  def createCommand(request: Api.Request): Command =
    request.payload match {
      case payload: Api.CreateContextRequest =>
        new CreateContextCmd(request.requestId, payload)

      case payload: Api.PushContextRequest =>
        new PushContextCmd(request.requestId, payload)

      case payload: Api.PopContextRequest =>
        new PopContextCmd(request.requestId, payload)

      case payload: Api.DestroyContextRequest =>
        new DestroyContextCmd(request.requestId, payload)

      case payload: Api.RecomputeContextRequest =>
        new RecomputeContextCmd(request.requestId, payload)

      case payload: Api.AttachVisualisation =>
        new AttachVisualisationCmd(request.requestId, payload)

      case payload: Api.DetachVisualisation =>
        new DetachVisualisationCmd(request.requestId, payload)

      case payload: Api.ModifyVisualisation =>
        new ModifyVisualisationCmd(request.requestId, payload)

      case payload: Api.RenameProject =>
        new RenameProjectCmd(request.requestId, payload)

      case payload: Api.OpenFileNotification  => new OpenFileCmd(payload)
      case payload: Api.CloseFileNotification => new CloseFileCmd(payload)
      case payload: Api.EditFileNotification  => new EditFileCmd(payload)

      case payload: Api.InvalidateModulesIndexRequest =>
        new InvalidateModulesIndexCmd(request.requestId, payload)

      case Api.ShutDownRuntimeServer() =>
        throw new IllegalArgumentException(
          "ShutDownRuntimeServer request is not convertible to command object"
        )
    }

}
