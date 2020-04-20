package org.enso.interpreter.test.instrument

import java.io.{ByteArrayOutputStream, File, OutputStream}
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.UUID

import org.enso.interpreter.test.Metadata
import org.enso.pkg.Package
import org.enso.polyglot.runtime.Runtime.{Api, ApiRequest}
import org.enso.polyglot.{
  LanguageInfo,
  PolyglotContext,
  RuntimeOptions,
  RuntimeServerInfo
}
import org.enso.text.editing.model
import org.enso.text.editing.model.TextEdit
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.io.MessageEndpoint
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RuntimeServerTest
    extends AnyFlatSpec
    with Matchers
    with BeforeAndAfterEach {

  var context: TestContext = _

  class TestContext(packageName: String) {
    var endPoint: MessageEndpoint        = _
    var messageQueue: List[Api.Response] = List()

    val tmpDir: File = Files.createTempDirectory("enso-test-packages").toFile

    val pkg: Package               = Package.create(tmpDir, packageName)
    val out: ByteArrayOutputStream = new ByteArrayOutputStream()
    val executionContext = new PolyglotContext(
      Context
        .newBuilder(LanguageInfo.ID)
        .allowExperimentalOptions(true)
        .allowAllAccess(true)
        .option(RuntimeOptions.getPackagesPathOption, pkg.root.getAbsolutePath)
        .option(RuntimeServerInfo.ENABLE_OPTION, "true")
        .out(out)
        .serverTransport { (uri, peer) =>
          if (uri.toString == RuntimeServerInfo.URI) {
            endPoint = peer
            new MessageEndpoint {
              override def sendText(text: String): Unit = {}

              override def sendBinary(data: ByteBuffer): Unit =
                messageQueue ++= Api.deserializeResponse(data)

              override def sendPing(data: ByteBuffer): Unit = {}

              override def sendPong(data: ByteBuffer): Unit = {}

              override def sendClose(): Unit = {}
            }
          } else null
        }
        .build()
    )
    executionContext.context.initialize(LanguageInfo.ID)

    def writeMain(contents: String): File =
      Files.write(pkg.mainFile.toPath, contents.getBytes).toFile

    def writeFile(file: File, contents: String): Unit = {
      Files.write(file.toPath, contents.getBytes): Unit
    }

    def send(msg: Api.Request): Unit = endPoint.sendBinary(Api.serialize(msg))

    def receive: Option[Api.Response] = {
      val msg = messageQueue.headOption
      messageQueue = messageQueue.drop(1)
      msg
    }

    def consumeOut: List[String] = {
      val result = out.toString
      out.reset()
      result.linesIterator.toList
    }
  }

  object Program {

    val metadata = new Metadata

    val idMainX = metadata.addItem(16, 5)
    val idMainY = metadata.addItem(30, 7)
    val idMainZ = metadata.addItem(46, 5)
    val idFooY  = metadata.addItem(85, 8)
    val idFooZ  = metadata.addItem(102, 5)

    val text =
      """
        |main =
        |    x = 1 + 5
        |    y = x.foo 5
        |    z = y + 5
        |    z
        |
        |Number.foo = x ->
        |    y = this + 3
        |    z = y * x
        |    z
        |""".stripMargin

    val code = metadata.appendToCode(text)

    object update {

      def idMainX(contextId: UUID) =
        Api.Response(
          Api.ExpressionValuesComputed(
            contextId,
            Vector(
              Api.ExpressionValueUpdate(
                Program.idMainX,
                Some("Number"),
                Some("6"),
                None
              )
            )
          )
        )

      def idMainY(contextId: UUID) =
        Api.Response(
          Api.ExpressionValuesComputed(
            contextId,
            Vector(
              Api.ExpressionValueUpdate(
                Program.idMainY,
                Some("Number"),
                Some("45"),
                None
              )
            )
          )
        )

      def idMainZ(contextId: UUID) =
        Api.Response(
          Api.ExpressionValuesComputed(
            contextId,
            Vector(
              Api.ExpressionValueUpdate(
                Program.idMainZ,
                Some("Number"),
                Some("50"),
                None
              )
            )
          )
        )

      def idFooY(contextId: UUID) =
        Api.Response(
          Api.ExpressionValuesComputed(
            contextId,
            Vector(
              Api.ExpressionValueUpdate(
                Program.idFooY,
                Some("Number"),
                Some("9"),
                None
              )
            )
          )
        )

      def idFooZ(contextId: UUID) =
        Api.Response(
          Api.ExpressionValuesComputed(
            contextId,
            Vector(
              Api.ExpressionValueUpdate(
                Program.idFooZ,
                Some("Number"),
                Some("45"),
                None
              )
            )
          )
        )
    }
  }

  override protected def beforeEach(): Unit = {
    context = new TestContext("Test")
    val Some(Api.Response(_, Api.InitializedNotification())) = context.receive
  }

  "RuntimeServer" should "push and pop functions on the stack" in {
    val mainFile  = context.writeMain(Program.code)
    val contextId = UUID.randomUUID()
    val requestId = UUID.randomUUID()

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // push local item on top of the empty stack
    val invalidLocalItem = Api.StackItem.LocalCall(Program.idMainY)
    context.send(
      Api
        .Request(requestId, Api.PushContextRequest(contextId, invalidLocalItem))
    )
    Set.fill(2)(context.receive) shouldEqual Set(
      Some(Api.Response(requestId, Api.InvalidStackItemError(contextId))),
      None
    )

    // push main
    val item1 = Api.StackItem.ExplicitCall(
      Api.MethodPointer(mainFile, "Main", "main"),
      None,
      Vector()
    )
    context.send(
      Api.Request(requestId, Api.PushContextRequest(contextId, item1))
    )
    Set.fill(5)(context.receive) shouldEqual Set(
      Some(Api.Response(requestId, Api.PushContextResponse(contextId))),
      Some(Program.update.idMainX(contextId)),
      Some(Program.update.idMainY(contextId)),
      Some(Program.update.idMainZ(contextId)),
      None
    )

    // push foo call
    val item2 = Api.StackItem.LocalCall(Program.idMainY)
    context.send(
      Api.Request(requestId, Api.PushContextRequest(contextId, item2))
    )
    Set.fill(4)(context.receive) shouldEqual Set(
      Some(Api.Response(requestId, Api.PushContextResponse(contextId))),
      Some(Program.update.idFooY(contextId)),
      Some(Program.update.idFooZ(contextId)),
      None
    )

    // push method pointer on top of the non-empty stack
    val invalidExplicitCall = Api.StackItem.ExplicitCall(
      Api.MethodPointer(mainFile, "Main", "main"),
      None,
      Vector()
    )
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(contextId, invalidExplicitCall)
      )
    )
    Set.fill(2)(context.receive) shouldEqual Set(
      Some(Api.Response(requestId, Api.InvalidStackItemError(contextId))),
      None
    )

    // pop foo call
    context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))
    Set.fill(5)(context.receive) shouldEqual Set(
      Some(Api.Response(requestId, Api.PopContextResponse(contextId))),
      Some(Program.update.idMainX(contextId)),
      Some(Program.update.idMainY(contextId)),
      Some(Program.update.idMainZ(contextId)),
      None
    )

    // pop main
    context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))
    Set.fill(2)(context.receive) shouldEqual Set(
      Some(Api.Response(requestId, Api.PopContextResponse(contextId))),
      None
    )

    // pop empty stack
    context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))
    Set.fill(2)(context.receive) shouldEqual Set(
      Some(Api.Response(requestId, Api.EmptyStackError(contextId))),
      None
    )
  }

  "Runtime server" should "support file modification operations" in {
    def send(msg: ApiRequest): Unit =
      context.send(Api.Request(UUID.randomUUID(), msg))

    val fooFile   = new File(context.pkg.sourceDir, "Foo.enso")
    val contextId = UUID.randomUUID()

    send(Api.CreateContextRequest(contextId))
    context.receive

    def push: Unit =
      send(
        Api.PushContextRequest(
          contextId,
          Api.StackItem
            .ExplicitCall(
              Api.MethodPointer(fooFile, "Foo", "main"),
              None,
              Vector()
            )
        )
      )
    def pop: Unit = send(Api.PopContextRequest(contextId))

    // Create a new file
    context.writeFile(fooFile, "main = IO.println \"I'm a file!\"")
    send(Api.CreateFileNotification(fooFile))
    push
    context.consumeOut shouldEqual List("I'm a file!")

    // Open the new file and set literal source
    send(
      Api.OpenFileNotification(
        fooFile,
        "main = IO.println \"I'm an open file!\""
      )
    )
    pop
    push
    context.consumeOut shouldEqual List("I'm an open file!")

    // Modify the file
    send(
      Api.EditFileNotification(
        fooFile,
        Seq(
          TextEdit(
            model.Range(model.Position(0, 24), model.Position(0, 30)),
            " modified"
          )
        )
      )
    )
    pop
    push
    context.consumeOut shouldEqual List("I'm a modified file!")

    // Close the file
    send(Api.CloseFileNotification(fooFile))
    pop
    push
    context.consumeOut shouldEqual List("I'm a file!")

  }
}
