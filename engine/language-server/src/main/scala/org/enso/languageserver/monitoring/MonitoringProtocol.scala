package org.enso.languageserver.monitoring

object MonitoringProtocol {

  /** A ping command.
    */
  case object Ping

  /** A pong reply.
    */
  case object Pong

  /** A request used to check if a server is ready.
    */
  case object IsReady

  /** Base trait for readiness responses.
    */
  sealed trait ReadinessResponse

  /** It signals that a server is not ready to accept users' requests.
    */
  case object KO extends ReadinessResponse

  /** It signals that a server is ready to accept users' requests.
    */
  case object OK extends ReadinessResponse

}
