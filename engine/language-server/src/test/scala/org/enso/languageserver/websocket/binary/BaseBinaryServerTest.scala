package org.enso.languageserver.websocket.binary
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.UUID
import akka.actor.{ActorRef, Props}
import akka.http.scaladsl.model.RemoteAddress
import com.google.flatbuffers.FlatBufferBuilder
import org.apache.commons.io.FileUtils
import org.enso.languageserver.data.{
  Config,
  ProjectDirectoriesConfig,
  ExecutionContextConfig,
  FileManagerConfig,
  PathWatcherConfig
}
import org.enso.languageserver.effect.ZioExec
import org.enso.languageserver.filemanager.{
  ContentRootType,
  ContentRootWithFile,
  FileManager,
  FileSystem
}
import org.enso.languageserver.http.server.ConnectionControllerFactory
import org.enso.languageserver.protocol.binary.BinaryConnectionController
import org.enso.languageserver.protocol.binary.InboundPayload
import org.enso.languageserver.websocket.binary.factory.{
  InboundMessageFactory,
  SessionInitFactory
}

import scala.concurrent.duration._

class BaseBinaryServerTest extends BinaryServerTestKit {

  val testContentRootId = UUID.randomUUID()
  val testContentRoot = ContentRootWithFile(
    testContentRootId,
    ContentRootType.Project,
    "Project",
    Files.createTempDirectory(null).toRealPath().toFile
  )
  val config = Config(
    Map(testContentRootId -> testContentRoot),
    FileManagerConfig(timeout = 3.seconds),
    PathWatcherConfig(),
    ExecutionContextConfig(requestTimeout = 3.seconds),
    ProjectDirectoriesConfig.initialize(testContentRoot.file)
  )

  sys.addShutdownHook(FileUtils.deleteQuietly(testContentRoot.file))

  @volatile
  protected var lastConnectionController: ActorRef = _

  override def connectionControllerFactory: ConnectionControllerFactory = {
    (clientIp: RemoteAddress.IP) =>
      {
        val zioExec = ZioExec(zio.Runtime.default)

        val fileManager =
          system.actorOf(FileManager.props(config, new FileSystem, zioExec))

        val controller =
          system.actorOf(
            Props(new BinaryConnectionController(clientIp, fileManager))
          )
        lastConnectionController = controller
        controller
      }
  }

  protected def createSessionInitCmd(
    clientId: UUID = UUID.randomUUID()
  ): ByteBuffer = {
    val requestId        = UUID.randomUUID()
    implicit val builder = new FlatBufferBuilder(1024)
    val cmd              = SessionInitFactory.create(clientId)
    val inMsg = InboundMessageFactory.create(
      requestId,
      None,
      InboundPayload.INIT_SESSION_CMD,
      cmd
    )
    builder.finish(inMsg)
    builder.dataBuffer()
  }

}
