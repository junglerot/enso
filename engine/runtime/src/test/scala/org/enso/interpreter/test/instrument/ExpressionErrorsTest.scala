package org.enso.interpreter.test.instrument

import java.io.{ByteArrayOutputStream, File}
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import org.enso.interpreter.instrument.execution.Timer
import org.enso.interpreter.runtime.`type`.Constants
import org.enso.interpreter.test.Metadata
import org.enso.pkg.{Package, PackageManager}
import org.enso.polyglot._
import org.enso.polyglot.runtime.Runtime.Api
import org.enso.text.editing.model
import org.enso.text.{ContentVersion, Sha3_224VersionCalculator}
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.io.MessageEndpoint
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

@scala.annotation.nowarn("msg=multiarg infix syntax")
class ExpressionErrorsTest
    extends AnyFlatSpec
    with Matchers
    with BeforeAndAfterEach {

  // === Test Timer ===========================================================

  class TestTimer extends Timer {
    override def getTime(): Long = 0
  }

  // === Test Utilities =======================================================

  var context: TestContext = _

  class TestContext(packageName: String) {
    var endPoint: MessageEndpoint = _
    val messageQueue: LinkedBlockingQueue[Api.Response] =
      new LinkedBlockingQueue()

    val tmpDir: File = Files.createTempDirectory("enso-test-packages").toFile

    val pkg: Package[File] =
      PackageManager.Default.create(tmpDir, packageName, "0.0.1")
    val out: ByteArrayOutputStream = new ByteArrayOutputStream()
    val executionContext = new PolyglotContext(
      Context
        .newBuilder(LanguageInfo.ID)
        .allowExperimentalOptions(true)
        .allowAllAccess(true)
        .option(RuntimeOptions.PACKAGES_PATH, pkg.root.getAbsolutePath)
        .option(RuntimeOptions.LOG_LEVEL, "WARNING")
        .option(RuntimeOptions.INTERPRETER_SEQUENTIAL_COMMAND_EXECUTION, "true")
        .option(RuntimeServerInfo.ENABLE_OPTION, "true")
        .out(out)
        .serverTransport { (uri, peer) =>
          if (uri.toString == RuntimeServerInfo.URI) {
            endPoint = peer
            new MessageEndpoint {
              override def sendText(text: String): Unit = {}

              override def sendBinary(data: ByteBuffer): Unit =
                Api.deserializeResponse(data).foreach(messageQueue.add)

              override def sendPing(data: ByteBuffer): Unit = {}

              override def sendPong(data: ByteBuffer): Unit = {}

              override def sendClose(): Unit = {}
            }
          } else null
        }
        .build()
    )
    executionContext.context.initialize(LanguageInfo.ID)

    val languageContext = executionContext.context
      .getBindings(LanguageInfo.ID)
      .invokeMember(MethodNames.TopScope.LEAK_CONTEXT)
      .asHostObject[org.enso.interpreter.runtime.Context]
    languageContext.getLanguage.getIdExecutionInstrument
      .overrideTimer(new TestTimer)

    def writeMain(contents: String): File =
      Files.write(pkg.mainFile.toPath, contents.getBytes).toFile

    def writeFile(file: File, contents: String): File =
      Files.write(file.toPath, contents.getBytes).toFile

    def writeInSrcDir(moduleName: String, contents: String): File = {
      val file = new File(pkg.sourceDir, s"$moduleName.enso")
      Files.write(file.toPath, contents.getBytes).toFile
    }

    def send(msg: Api.Request): Unit = endPoint.sendBinary(Api.serialize(msg))

    def receiveNone: Option[Api.Response] = {
      Option(messageQueue.poll())
    }

    def receive: Option[Api.Response] = {
      Option(messageQueue.poll(5, TimeUnit.SECONDS))
    }

    def receive(n: Int): List[Api.Response] = {
      Iterator.continually(receive).take(n).flatten.toList
    }

    def consumeOut: List[String] = {
      val result = out.toString
      out.reset()
      result.linesIterator.toList
    }

    def executionComplete(contextId: UUID): Api.Response =
      Api.Response(Api.ExecutionComplete(contextId))
  }

  object Update {

    def panic(
      contextId: UUID,
      expressionId: UUID,
      payload: Api.ExpressionUpdate.Payload
    ): Api.Response =
      Api.Response(
        Api.ExpressionUpdates(
          contextId,
          Set(
            Api.ExpressionUpdate(
              expressionId,
              Some(Constants.PANIC),
              None,
              Vector(Api.ProfilingInfo.ExecutionTime(0)),
              false,
              payload
            )
          )
        )
      )

    def panic(
      contextId: UUID,
      expressionId: UUID,
      methodPointer: Api.MethodPointer,
      payload: Api.ExpressionUpdate.Payload
    ): Api.Response =
      Api.Response(
        Api.ExpressionUpdates(
          contextId,
          Set(
            Api.ExpressionUpdate(
              expressionId,
              Some(Constants.PANIC),
              Some(methodPointer),
              Vector(Api.ProfilingInfo.ExecutionTime(0)),
              false,
              payload
            )
          )
        )
      )

  }

  def contentsVersion(content: String): ContentVersion =
    Sha3_224VersionCalculator.evalVersion(content)

  override protected def beforeEach(): Unit = {
    context = new TestContext("Test")
    val Some(Api.Response(_, Api.InitializedNotification())) = context.receive
  }

  it should "return panic sentinels in method body" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Test.Main"
    val metadata   = new Metadata
    val fooBodyId  = metadata.addItem(21, 5)
    val xId        = metadata.addItem(35, 9)
    val yId        = metadata.addItem(53, 8)
    val mainResId  = metadata.addItem(66, 7)

    val code =
      """main =
        |    foo a b = a + b
        |    x = undefined
        |    y = foo x 42
        |    foo y 1
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Open the new file
    context.send(
      Api.Request(Api.OpenFileNotification(mainFile, contents, true))
    )
    context.receiveNone shouldEqual None

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, "Test.Main", "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receive(7) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionUpdate(
          contextId,
          Seq(
            Api.ExecutionResult.Diagnostic.error(
              "Variable `undefined` is not defined.",
              Some(mainFile),
              Some(model.Range(model.Position(2, 8), model.Position(2, 17))),
              Some(xId)
            )
          )
        )
      ),
      Update.panic(
        contextId,
        xId,
        Api.ExpressionUpdate.Payload.Panic(
          "Compile_Error Variable `undefined` is not defined.",
          Seq(xId)
        )
      ),
      Update.panic(
        contextId,
        fooBodyId,
        Api.ExpressionUpdate.Payload.Panic(
          "Compile_Error Variable `undefined` is not defined.",
          Seq(xId)
        )
      ),
      Update.panic(
        contextId,
        yId,
        Api.ExpressionUpdate.Payload.Panic(
          "Compile_Error Variable `undefined` is not defined.",
          Seq(xId)
        )
      ),
      Update.panic(
        contextId,
        mainResId,
        Api.ExpressionUpdate.Payload.Panic(
          "Compile_Error Variable `undefined` is not defined.",
          Seq(xId)
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "return panic sentinels in method calls" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Test.Main"
    val metadata   = new Metadata
    val mainBodyId = metadata.addItem(28, 12)

    val code =
      """foo a b = a + b + x
        |
        |main = here.foo 1 2
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Open the new file
    context.send(
      Api.Request(Api.OpenFileNotification(mainFile, contents, true))
    )
    context.receiveNone shouldEqual None

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, "Test.Main", "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receive(4) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionUpdate(
          contextId,
          Seq(
            Api.ExecutionResult.Diagnostic.error(
              "Variable `x` is not defined.",
              Some(mainFile),
              Some(model.Range(model.Position(0, 18), model.Position(0, 19))),
              None
            )
          )
        )
      ),
      Update.panic(
        contextId,
        mainBodyId,
        Api.MethodPointer("Test.Main", "Test.Main", "foo"),
        Api.ExpressionUpdate.Payload.Panic(
          "Compile_Error Variable `x` is not defined.",
          Seq(mainBodyId)
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "return dataflow errors in method body" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Test.Main"
    val metadata   = new Metadata
    val fooBodyId  = metadata.addItem(61, 5)
    val xId        = metadata.addItem(75, 19)
    val yId        = metadata.addItem(103, 8)
    val mainResId  = metadata.addItem(116, 7)

    val code =
      """from Builtins import all
        |
        |type MyError
        |
        |main =
        |    foo a b = a + b
        |    x = Error.throw MyError
        |    y = foo x 42
        |    foo y 1
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Open the new file
    context.send(
      Api.Request(Api.OpenFileNotification(mainFile, contents, true))
    )
    context.receiveNone shouldEqual None

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, "Test.Main", "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receive(6) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(contextId, xId, Constants.ERROR),
      TestMessages.update(contextId, fooBodyId, Constants.ERROR),
      TestMessages.update(contextId, yId, Constants.ERROR),
      TestMessages.update(contextId, mainResId, Constants.ERROR),
      context.executionComplete(contextId)
    )
  }

  it should "return panic sentinels continuing execution" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Test.Main"
    val metadata   = new Metadata
    val xId        = metadata.addItem(41, 9)
    val yId        = metadata.addItem(59, 2)
    val mainResId  = metadata.addItem(66, 12)

    val code =
      """from Builtins import all
        |
        |main =
        |    x = undefined
        |    y = 42
        |    IO.println y
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Open the new file
    context.send(
      Api.Request(Api.OpenFileNotification(mainFile, contents, true))
    )
    context.receiveNone shouldEqual None

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, "Test.Main", "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receive(6) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionUpdate(
          contextId,
          Seq(
            Api.ExecutionResult.Diagnostic.warning(
              "Unused variable x.",
              Some(mainFile),
              Some(model.Range(model.Position(3, 4), model.Position(3, 5)))
            ),
            Api.ExecutionResult.Diagnostic.error(
              "Variable `undefined` is not defined.",
              Some(mainFile),
              Some(model.Range(model.Position(3, 8), model.Position(3, 17))),
              Some(xId)
            )
          )
        )
      ),
      Update.panic(
        contextId,
        xId,
        Api.ExpressionUpdate.Payload.Panic(
          "Compile_Error Variable `undefined` is not defined.",
          Seq(xId)
        )
      ),
      TestMessages.update(contextId, yId, Constants.INTEGER),
      TestMessages.update(contextId, mainResId, Constants.NOTHING),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual Seq("42")
  }

  it should "return dataflow errors continuing execution" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Test.Main"
    val metadata   = new Metadata
    val xId        = metadata.addItem(55, 19)
    val yId        = metadata.addItem(83, 2)
    val mainResId  = metadata.addItem(90, 12)

    val code =
      """from Builtins import all
        |
        |type MyError
        |
        |main =
        |    x = Error.throw MyError
        |    y = 42
        |    IO.println y
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Open the new file
    context.send(
      Api.Request(Api.OpenFileNotification(mainFile, contents, true))
    )
    context.receiveNone shouldEqual None

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, "Test.Main", "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receive(6) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionUpdate(
          contextId,
          Seq(
            Api.ExecutionResult.Diagnostic.warning(
              "Unused variable x.",
              Some(mainFile),
              Some(model.Range(model.Position(5, 4), model.Position(5, 5)))
            )
          )
        )
      ),
      TestMessages.update(contextId, xId, Constants.ERROR),
      TestMessages.update(contextId, yId, Constants.INTEGER),
      TestMessages.update(contextId, mainResId, Constants.NOTHING),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual Seq("42")
  }

}
