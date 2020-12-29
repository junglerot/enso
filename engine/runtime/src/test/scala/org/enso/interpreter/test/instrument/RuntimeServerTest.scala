package org.enso.interpreter.test.instrument

import java.io.{ByteArrayOutputStream, File}
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import org.enso.interpreter.test.Metadata
import org.enso.pkg.{Package, PackageManager}
import org.enso.polyglot._
import org.enso.polyglot.data.Tree
import org.enso.polyglot.runtime.Runtime.Api
import org.enso.text.{ContentVersion, Sha3_224VersionCalculator}
import org.enso.text.editing.model
import org.enso.text.editing.model.TextEdit
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.io.MessageEndpoint
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

@scala.annotation.nowarn("msg=multiarg infix syntax")
class RuntimeServerTest
    extends AnyFlatSpec
    with Matchers
    with BeforeAndAfterEach {

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
      Option(messageQueue.poll(3, TimeUnit.SECONDS))
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

    object Main {

      val metadata = new Metadata

      val idMainX = metadata.addItem(42, 1)
      val idMainY = metadata.addItem(52, 7)
      val idMainZ = metadata.addItem(68, 5)
      val idFooY  = metadata.addItem(107, 8)
      val idFooZ  = metadata.addItem(124, 5)

      def code =
        metadata.appendToCode(
          """
            |from Builtins import all
            |
            |main =
            |    x = 6
            |    y = x.foo 5
            |    z = y + 5
            |    z
            |
            |Number.foo = x ->
            |    y = this + 3
            |    z = y * x
            |    z
            |""".stripMargin.linesIterator.mkString("\n")
        )

      object Update {

        def mainX(contextId: UUID) =
          Api.Response(
            Api.ExpressionValuesComputed(
              contextId,
              Vector(
                Api.ExpressionValueUpdate(
                  Main.idMainX,
                  Some("Number"),
                  None
                )
              )
            )
          )

        def mainY(contextId: UUID) =
          Api.Response(
            Api.ExpressionValuesComputed(
              contextId,
              Vector(
                Api.ExpressionValueUpdate(
                  Main.idMainY,
                  Some("Number"),
                  Some(Api.MethodPointer("Test.Main", "Number", "foo"))
                )
              )
            )
          )

        def mainZ(contextId: UUID) =
          Api.Response(
            Api.ExpressionValuesComputed(
              contextId,
              Vector(
                Api.ExpressionValueUpdate(
                  Main.idMainZ,
                  Some("Number"),
                  None
                )
              )
            )
          )

        def fooY(contextId: UUID) =
          Api.Response(
            Api.ExpressionValuesComputed(
              contextId,
              Vector(
                Api.ExpressionValueUpdate(
                  Main.idFooY,
                  Some("Number"),
                  None
                )
              )
            )
          )

        def fooZ(contextId: UUID) =
          Api.Response(
            Api.ExpressionValuesComputed(
              contextId,
              Vector(
                Api.ExpressionValueUpdate(
                  Main.idFooZ,
                  Some("Number"),
                  None
                )
              )
            )
          )
      }
    }

    object Main2 {

      val metadata = new Metadata
      val idMainY  = metadata.addItem(174, 10)
      val idMainZ  = metadata.addItem(193, 10)

      val code = metadata.appendToCode(
        """
          |from Builtins import all
          |
          |foo = arg ->
          |    IO.println "I'm expensive!"
          |    arg + 5
          |
          |bar = arg ->
          |    IO.println "I'm more expensive!"
          |    arg * 5
          |
          |main =
          |    x = 10
          |    y = here.foo x
          |    z = here.bar y
          |    z
          |""".stripMargin
      )

      object Update {

        def mainY(contextId: UUID) =
          Api.Response(
            Api.ExpressionValuesComputed(
              contextId,
              Vector(
                Api.ExpressionValueUpdate(
                  idMainY,
                  Some("Number"),
                  Some(Api.MethodPointer("Test.Main", "Main", "foo"))
                )
              )
            )
          )

        def mainZ(contextId: UUID) =
          Api.Response(
            Api.ExpressionValuesComputed(
              contextId,
              Vector(
                Api.ExpressionValueUpdate(
                  idMainZ,
                  Some("Number"),
                  Some(Api.MethodPointer("Test.Main", "Main", "bar"))
                )
              )
            )
          )

      }
    }

    object Visualisation {

      val code =
        """
          |encode = x -> x.to_text
          |
          |incAndEncode = x -> here.encode x+1
          |
          |""".stripMargin

    }

  }

  def contentsVersion(content: String): ContentVersion =
    Sha3_224VersionCalculator.evalVersion(content)

  override protected def beforeEach(): Unit = {
    context = new TestContext("Test")
    val Some(Api.Response(_, Api.InitializedNotification())) = context.receive
  }

  "RuntimeServer" should "push and pop functions on the stack" in {
    val contents  = context.Main.code
    val mainFile  = context.writeMain(contents)
    val contextId = UUID.randomUUID()
    val requestId = UUID.randomUUID()

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(Api.OpenFileNotification(mainFile, contents, true))
    )
    context.receiveNone shouldEqual None

    // push local item on top of the empty stack
    val invalidLocalItem = Api.StackItem.LocalCall(context.Main.idMainY)
    context.send(
      Api
        .Request(requestId, Api.PushContextRequest(contextId, invalidLocalItem))
    )
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.InvalidStackItemError(contextId))
    )

    // push main
    val item1 = Api.StackItem.ExplicitCall(
      Api.MethodPointer("Test.Main", "Main", "main"),
      None,
      Vector()
    )
    context.send(
      Api.Request(requestId, Api.PushContextRequest(contextId, item1))
    )
    context.receive(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.Main.Update.mainX(contextId),
      context.Main.Update.mainY(contextId),
      context.Main.Update.mainZ(contextId),
      context.executionComplete(contextId)
    )

    // push foo call
    val item2 = Api.StackItem.LocalCall(context.Main.idMainY)
    context.send(
      Api.Request(requestId, Api.PushContextRequest(contextId, item2))
    )
    context.receive(4) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.Main.Update.fooY(contextId),
      context.Main.Update.fooZ(contextId),
      context.executionComplete(contextId)
    )

    // push method pointer on top of the non-empty stack
    val invalidExplicitCall = Api.StackItem.ExplicitCall(
      Api.MethodPointer("Test.Main", "Main", "main"),
      None,
      Vector()
    )
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(contextId, invalidExplicitCall)
      )
    )
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.InvalidStackItemError(contextId))
    )

    // pop foo call
    context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))
    context.receive(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PopContextResponse(contextId)),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(
            Api.ExpressionValueUpdate(
              context.Main.idMainY,
              Some("Number"),
              Some(Api.MethodPointer("Test.Main", "Number", "foo"))
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )

    // pop main
    context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.PopContextResponse(contextId))
    )

    // pop empty stack
    context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.EmptyStackError(contextId))
    )
  }

  it should "send updates from last line" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Test.Main"

    val metadata  = new Metadata
    val idMain    = metadata.addItem(49, 17)
    val idMainFoo = metadata.addItem(54, 12)

    val code =
      """from Builtins import all
        |
        |foo a b = a + b
        |
        |main =
        |    this.foo 1 2
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val version  = contentsVersion(contents)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(Api.OpenFileNotification(mainFile, contents, false))
    )
    context.receiveNone shouldEqual None

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, "Main", "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receive(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(
            Api.ExpressionValueUpdate(
              idMainFoo,
              Some("Number"),
              Some(Api.MethodPointer(moduleName, "Main", "foo"))
            )
          )
        )
      ),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(Api.ExpressionValueUpdate(idMain, Some("Number"), None))
        )
      ),
      Api.Response(
        Api.SuggestionsDatabaseModuleUpdateNotification(
          file    = mainFile,
          version = version,
          actions = Vector(Api.SuggestionsDatabaseAction.Clean(moduleName)),
          updates = Tree.Root(
            Vector(
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    None,
                    moduleName,
                    "foo",
                    Seq(
                      Suggestion.Argument("this", "Any", false, false, None),
                      Suggestion.Argument("a", "Any", false, false, None),
                      Suggestion.Argument("b", "Any", false, false, None)
                    ),
                    "Main",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector()
              ),
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    Some(idMain),
                    moduleName,
                    "main",
                    List(
                      Suggestion.Argument("this", "Any", false, false, None)
                    ),
                    "Main",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector()
              )
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "compute side effects correctly from last line" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Test.Main"

    val metadata  = new Metadata
    val idMain    = metadata.addItem(49, 30)
    val idMainFoo = metadata.addItem(66, 12)

    val code =
      """from Builtins import all
        |
        |foo a b = a + b
        |
        |main =
        |    IO.println (this.foo 1 2)
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val version  = contentsVersion(contents)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(Api.OpenFileNotification(mainFile, contents, false))
    )
    context.receiveNone shouldEqual None

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, "Main", "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receive(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(
            Api.ExpressionValueUpdate(
              idMainFoo,
              Some("Number"),
              Some(Api.MethodPointer(moduleName, "Main", "foo"))
            )
          )
        )
      ),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(Api.ExpressionValueUpdate(idMain, Some("Nothing"), None))
        )
      ),
      Api.Response(
        Api.SuggestionsDatabaseModuleUpdateNotification(
          file    = mainFile,
          version = version,
          actions = Vector(Api.SuggestionsDatabaseAction.Clean(moduleName)),
          updates = Tree.Root(
            Vector(
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    None,
                    moduleName,
                    "foo",
                    Seq(
                      Suggestion.Argument("this", "Any", false, false, None),
                      Suggestion.Argument("a", "Any", false, false, None),
                      Suggestion.Argument("b", "Any", false, false, None)
                    ),
                    "Main",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector()
              ),
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    Some(idMain),
                    moduleName,
                    "main",
                    List(
                      Suggestion.Argument("this", "Any", false, false, None)
                    ),
                    "Main",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector()
              )
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("3")
  }

  it should "run State getting the initial state" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Test.Main"

    val metadata  = new Metadata
    val idMain    = metadata.addItem(33, 41)
    val idMainBar = metadata.addItem(65, 8)

    val code =
      """from Builtins import all
        |
        |main = IO.println (State.run Number 42 this.bar)
        |
        |bar = State.get Number
        |""".stripMargin
    val contents = metadata.appendToCode(code)
    val version  = contentsVersion(contents)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(Api.OpenFileNotification(mainFile, contents, false))
    )
    context.receiveNone shouldEqual None

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, "Main", "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receive(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(
            Api.ExpressionValueUpdate(
              idMainBar,
              Some("Number"),
              Some(Api.MethodPointer(moduleName, "Main", "bar"))
            )
          )
        )
      ),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(Api.ExpressionValueUpdate(idMain, Some("Nothing"), None))
        )
      ),
      Api.Response(
        Api.SuggestionsDatabaseModuleUpdateNotification(
          file    = mainFile,
          version = version,
          actions = Vector(Api.SuggestionsDatabaseAction.Clean(moduleName)),
          updates = Tree.Root(
            Vector(
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    Some(idMain),
                    moduleName,
                    "main",
                    Seq(Suggestion.Argument("this", "Any", false, false, None)),
                    "Main",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector()
              ),
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    None,
                    moduleName,
                    "bar",
                    Seq(Suggestion.Argument("this", "Any", false, false, None)),
                    "Main",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector()
              )
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("42")
  }

  it should "run State setting the state" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Test.Main"

    val metadata  = new Metadata
    val idMain    = metadata.addItem(33, 40)
    val idMainBar = metadata.addItem(64, 8)

    val code =
      """from Builtins import all
        |
        |main = IO.println (State.run Number 0 this.bar)
        |
        |bar =
        |    State.put Number 10
        |    State.get Number
        |""".stripMargin
    val contents = metadata.appendToCode(code)
    val version  = contentsVersion(contents)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(Api.OpenFileNotification(mainFile, contents, false))
    )
    context.receiveNone shouldEqual None

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, "Main", "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receive(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(
            Api.ExpressionValueUpdate(
              idMainBar,
              Some("Number"),
              Some(Api.MethodPointer(moduleName, "Main", "bar"))
            )
          )
        )
      ),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(Api.ExpressionValueUpdate(idMain, Some("Nothing"), None))
        )
      ),
      Api.Response(
        Api.SuggestionsDatabaseModuleUpdateNotification(
          file    = mainFile,
          version = version,
          actions = Vector(Api.SuggestionsDatabaseAction.Clean(moduleName)),
          updates = Tree.Root(
            Vector(
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    Some(idMain),
                    moduleName,
                    "main",
                    Seq(Suggestion.Argument("this", "Any", false, false, None)),
                    "Main",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector()
              ),
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    None,
                    moduleName,
                    "bar",
                    Seq(Suggestion.Argument("this", "Any", false, false, None)),
                    "Main",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector()
              )
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("10")
  }

  it should "send updates of a function call" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Test.Main"

    val metadata  = new Metadata
    val idMain    = metadata.addItem(49, 23)
    val idMainFoo = metadata.addItem(54, 12)

    val code =
      """from Builtins import all
        |
        |foo a b = a + b
        |
        |main =
        |    this.foo 1 2
        |    1
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val version  = contentsVersion(contents)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(Api.OpenFileNotification(mainFile, contents, false))
    )
    context.receiveNone shouldEqual None

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, "Main", "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receive(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(
            Api.ExpressionValueUpdate(
              idMainFoo,
              Some("Number"),
              Some(Api.MethodPointer(moduleName, "Main", "foo"))
            )
          )
        )
      ),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(Api.ExpressionValueUpdate(idMain, Some("Number"), None))
        )
      ),
      Api.Response(
        Api.SuggestionsDatabaseModuleUpdateNotification(
          file    = mainFile,
          version = version,
          actions = Vector(Api.SuggestionsDatabaseAction.Clean(moduleName)),
          updates = Tree.Root(
            Vector(
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    None,
                    moduleName,
                    "foo",
                    Seq(
                      Suggestion.Argument("this", "Any", false, false, None),
                      Suggestion.Argument("a", "Any", false, false, None),
                      Suggestion.Argument("b", "Any", false, false, None)
                    ),
                    "Main",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector()
              ),
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    Some(idMain),
                    moduleName,
                    "main",
                    List(
                      Suggestion.Argument("this", "Any", false, false, None)
                    ),
                    "Main",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector()
              )
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "not send updates when the type is not changed" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Test.Main"
    val idMain     = context.Main.metadata.addItem(33, 47)
    val idMainUpdate =
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(Api.ExpressionValueUpdate(idMain, Some("Number"), None))
        )
      )
    val contents = context.Main.code
    val version  = contentsVersion(contents)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(Api.OpenFileNotification(mainFile, contents, false))
    )
    context.receiveNone shouldEqual None

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, "Main", "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receive(7) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.Main.Update.mainX(contextId),
      context.Main.Update.mainY(contextId),
      context.Main.Update.mainZ(contextId),
      idMainUpdate,
      Api.Response(
        Api.SuggestionsDatabaseModuleUpdateNotification(
          file    = mainFile,
          version = version,
          actions = Vector(Api.SuggestionsDatabaseAction.Clean(moduleName)),
          updates = Tree.Root(
            Vector(
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    Some(idMain),
                    moduleName,
                    "main",
                    Seq(Suggestion.Argument("this", "Any", false, false, None)),
                    "Main",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector(
                  Tree.Node(
                    Api.SuggestionUpdate(
                      Suggestion.Local(
                        Some(context.Main.idMainX),
                        moduleName,
                        "x",
                        "Any",
                        Suggestion
                          .Scope(
                            Suggestion.Position(3, 6),
                            Suggestion.Position(8, 0)
                          )
                      ),
                      Api.SuggestionAction.Add()
                    ),
                    Vector()
                  ),
                  Tree.Node(
                    Api.SuggestionUpdate(
                      Suggestion.Local(
                        Some(context.Main.idMainY),
                        moduleName,
                        "y",
                        "Any",
                        Suggestion
                          .Scope(
                            Suggestion.Position(3, 6),
                            Suggestion.Position(8, 0)
                          )
                      ),
                      Api.SuggestionAction.Add()
                    ),
                    Vector()
                  ),
                  Tree.Node(
                    Api.SuggestionUpdate(
                      Suggestion.Local(
                        Some(context.Main.idMainZ),
                        moduleName,
                        "z",
                        "Any",
                        Suggestion
                          .Scope(
                            Suggestion.Position(3, 6),
                            Suggestion.Position(8, 0)
                          )
                      ),
                      Api.SuggestionAction.Add()
                    ),
                    Vector()
                  )
                )
              ),
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    None,
                    moduleName,
                    "foo",
                    Seq(
                      Suggestion.Argument("this", "Any", false, false, None),
                      Suggestion.Argument("x", "Any", false, false, None)
                    ),
                    "Number",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector(
                  Tree.Node(
                    Api.SuggestionUpdate(
                      Suggestion.Local(
                        Some(context.Main.idFooY),
                        moduleName,
                        "y",
                        "Any",
                        Suggestion
                          .Scope(
                            Suggestion.Position(9, 17),
                            Suggestion.Position(12, 5)
                          )
                      ),
                      Api.SuggestionAction.Add()
                    ),
                    Vector()
                  ),
                  Tree.Node(
                    Api.SuggestionUpdate(
                      Suggestion.Local(
                        Some(context.Main.idFooZ),
                        moduleName,
                        "z",
                        "Any",
                        Suggestion.Scope(
                          Suggestion.Position(9, 17),
                          Suggestion.Position(12, 5)
                        )
                      ),
                      Api.SuggestionAction.Add()
                    ),
                    Vector()
                  )
                )
              )
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )

    // push foo call
    val item2 = Api.StackItem.LocalCall(context.Main.idMainY)
    context.send(
      Api.Request(requestId, Api.PushContextRequest(contextId, item2))
    )
    context.receive(4) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.Main.Update.fooY(contextId),
      context.Main.Update.fooZ(contextId),
      context.executionComplete(contextId)
    )

    // pop foo call
    context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))
    context.receive(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PopContextResponse(contextId)),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(
            Api.ExpressionValueUpdate(
              context.Main.idMainY,
              Some("Number"),
              Some(Api.MethodPointer("Test.Main", "Number", "foo"))
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )

    // pop main
    context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))
    context.receive(1) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PopContextResponse(contextId))
    )
  }

  it should "send updates when the type is changed" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Test.Main"

    val metadata  = new Metadata
    val idResult  = metadata.addItem(46, 4)
    val idPrintln = metadata.addItem(55, 17)
    val idMain    = metadata.addItem(32, 40)
    val code =
      """from Builtins import all
        |
        |main =
        |    result = 1337
        |    IO.println result
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val version  = contentsVersion(contents)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(Api.OpenFileNotification(mainFile, contents, false))
    )
    context.receiveNone shouldEqual None

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, "Main", "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receive(6) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(Api.ExpressionValueUpdate(idResult, Some("Number"), None))
        )
      ),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(Api.ExpressionValueUpdate(idPrintln, Some("Nothing"), None))
        )
      ),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(Api.ExpressionValueUpdate(idMain, Some("Nothing"), None))
        )
      ),
      Api.Response(
        Api.SuggestionsDatabaseModuleUpdateNotification(
          file    = mainFile,
          version = version,
          actions = Vector(Api.SuggestionsDatabaseAction.Clean(moduleName)),
          updates = Tree.Root(
            Vector(
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    Some(idMain),
                    moduleName,
                    "main",
                    Seq(Suggestion.Argument("this", "Any", false, false, None)),
                    "Main",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector(
                  Tree.Node(
                    Api.SuggestionUpdate(
                      Suggestion.Local(
                        Some(idResult),
                        moduleName,
                        "result",
                        "Any",
                        Suggestion.Scope(
                          Suggestion.Position(2, 6),
                          Suggestion.Position(4, 21)
                        )
                      ),
                      Api.SuggestionAction.Add()
                    ),
                    Vector()
                  )
                )
              )
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("1337")

    // Modify the file
    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            TextEdit(
              model.Range(model.Position(3, 13), model.Position(3, 17)),
              "\"Hi\""
            )
          )
        )
      )
    )
    context.receive(2) should contain theSameElementsAs Seq(
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(Api.ExpressionValueUpdate(idResult, Some("Text"), None))
        )
      ),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("Hi")
  }

  it should "send updates when the method pointer is changed" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Test.Main"

    val metadata = new Metadata
    val idMain   = metadata.addItem(32, 35)
    val idMainA  = metadata.addItem(41, 8)
    val idMainP  = metadata.addItem(54, 12)
    val idPie    = metadata.addItem(66 + 8, 1)
    val idUwu    = metadata.addItem(74 + 8, 1)
    val idHie    = metadata.addItem(82 + 8, 6)
    val idXxx    = metadata.addItem(102 + 8, 1)
    val code =
      """from Builtins import all
        |
        |main =
        |    a = 123 + 21
        |    IO.println a
        |
        |pie = 3
        |uwu = 7
        |hie = "hie!"
        |Number.x y = y
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val version  = contentsVersion(contents)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(Api.OpenFileNotification(mainFile, contents, false))
    )
    context.receiveNone shouldEqual None

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, "Main", "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receive(6) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(Api.ExpressionValueUpdate(idMainA, Some("Number"), None))
        )
      ),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(Api.ExpressionValueUpdate(idMainP, Some("Nothing"), None))
        )
      ),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(Api.ExpressionValueUpdate(idMain, Some("Nothing"), None))
        )
      ),
      Api.Response(
        Api.SuggestionsDatabaseModuleUpdateNotification(
          file    = mainFile,
          version = version,
          actions = Vector(Api.SuggestionsDatabaseAction.Clean(moduleName)),
          updates = Tree.Root(
            Vector(
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    Some(idMain),
                    moduleName,
                    "main",
                    Seq(Suggestion.Argument("this", "Any", false, false, None)),
                    "Main",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector(
                  Tree.Node(
                    Api.SuggestionUpdate(
                      Suggestion.Local(
                        Some(idMainA),
                        moduleName,
                        "a",
                        "Any",
                        Suggestion.Scope(
                          Suggestion.Position(2, 6),
                          Suggestion.Position(5, 0)
                        )
                      ),
                      Api.SuggestionAction.Add()
                    ),
                    Vector()
                  )
                )
              ),
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    Some(idPie),
                    moduleName,
                    "pie",
                    Seq(
                      Suggestion.Argument("this", "Any", false, false, None)
                    ),
                    "Main",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector()
              ),
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    Some(idUwu),
                    moduleName,
                    "uwu",
                    Seq(
                      Suggestion.Argument("this", "Any", false, false, None)
                    ),
                    "Main",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector()
              ),
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    Some(idHie),
                    moduleName,
                    "hie",
                    Seq(
                      Suggestion.Argument("this", "Any", false, false, None)
                    ),
                    "Main",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector()
              ),
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    Some(idXxx),
                    moduleName,
                    "x",
                    Seq(
                      Suggestion.Argument("this", "Any", false, false, None),
                      Suggestion.Argument("y", "Any", false, false, None)
                    ),
                    "Number",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector()
              )
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("144")

    // Edit s/123 + 21/1234.x 4/
    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            TextEdit(
              model.Range(model.Position(3, 8), model.Position(3, 16)),
              "1234.x 4"
            )
          )
        )
      )
    )
    context.receive(2) should contain theSameElementsAs Seq(
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(
            Api.ExpressionValueUpdate(
              idMainA,
              Some("Number"),
              Some(Api.MethodPointer(moduleName, "Number", "x"))
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("4")

    // Edit s/1234.x 4/1000.x 5/
    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            TextEdit(
              model.Range(model.Position(3, 8), model.Position(3, 16)),
              "1000.x 5"
            )
          )
        )
      )
    )
    context.receive shouldEqual Some(context.executionComplete(contextId))
    context.consumeOut shouldEqual List("5")

    // Edit s/1000.x 5/Main.pie/
    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            TextEdit(
              model.Range(model.Position(3, 8), model.Position(3, 16)),
              "here.pie"
            )
          )
        )
      )
    )
    context.receive(2) should contain theSameElementsAs Seq(
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(
            Api.ExpressionValueUpdate(
              idMainA,
              Some("Number"),
              Some(Api.MethodPointer(moduleName, "Main", "pie"))
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("3")

    // Edit s/Main.pie/Main.uwu/
    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            TextEdit(
              model.Range(model.Position(3, 8), model.Position(3, 16)),
              "here.uwu"
            )
          )
        )
      )
    )
    context.receive(2) should contain theSameElementsAs Seq(
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(
            Api.ExpressionValueUpdate(
              idMainA,
              Some("Number"),
              Some(Api.MethodPointer(moduleName, "Main", "uwu"))
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("7")

    // Edit s/Main.uwu/Main.hie/
    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            TextEdit(
              model.Range(model.Position(3, 8), model.Position(3, 16)),
              "here.hie"
            )
          )
        )
      )
    )
    context.receive(2) should contain theSameElementsAs Seq(
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(
            Api.ExpressionValueUpdate(
              idMainA,
              Some("Text"),
              Some(Api.MethodPointer(moduleName, "Main", "hie"))
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("hie!")

    // Edit s/Main.hie/"Hello!"/
    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            TextEdit(
              model.Range(model.Position(3, 8), model.Position(3, 16)),
              "\"Hello!\""
            )
          )
        )
      )
    )
    context.receive(2) should contain theSameElementsAs Seq(
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(Api.ExpressionValueUpdate(idMainA, Some("Text"), None))
        )
      ),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("Hello!")
  }

  it should "send updates for overloaded functions" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Test.Main"

    val metadata = new Metadata
    val idMain   = metadata.addItem(32, 80)
    val id1      = metadata.addItem(41, 15)
    val id2      = metadata.addItem(61, 18)
    val id3      = metadata.addItem(84, 15)
    val code =
      """from Builtins import all
        |
        |main =
        |    x = 15.overloaded 1
        |    "foo".overloaded 2
        |    overloaded 10 x
        |    Nothing
        |
        |Text.overloaded arg = arg + 1
        |Number.overloaded arg = arg + 2
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val version  = contentsVersion(contents)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(Api.OpenFileNotification(mainFile, contents, false))
    )
    context.receiveNone shouldEqual None

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, "Main", "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receive(7) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(Api.ExpressionValueUpdate(idMain, Some("Nothing"), None))
        )
      ),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(
            Api.ExpressionValueUpdate(
              id1,
              Some("Number"),
              Some(Api.MethodPointer(moduleName, "Number", "overloaded"))
            )
          )
        )
      ),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(
            Api.ExpressionValueUpdate(
              id2,
              Some("Number"),
              Some(Api.MethodPointer(moduleName, "Text", "overloaded"))
            )
          )
        )
      ),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(
            Api.ExpressionValueUpdate(
              id3,
              Some("Number"),
              Some(Api.MethodPointer(moduleName, "Number", "overloaded"))
            )
          )
        )
      ),
      Api.Response(
        Api.SuggestionsDatabaseModuleUpdateNotification(
          file    = mainFile,
          version = version,
          actions = Vector(Api.SuggestionsDatabaseAction.Clean(moduleName)),
          updates = Tree.Root(
            Vector(
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    Some(idMain),
                    moduleName,
                    "main",
                    Seq(Suggestion.Argument("this", "Any", false, false, None)),
                    "Main",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector(
                  Tree.Node(
                    Api.SuggestionUpdate(
                      Suggestion.Local(
                        Some(id1),
                        moduleName,
                        "x",
                        "Any",
                        Suggestion.Scope(
                          Suggestion.Position(2, 6),
                          Suggestion.Position(7, 0)
                        )
                      ),
                      Api.SuggestionAction.Add()
                    ),
                    Vector()
                  )
                )
              ),
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    None,
                    moduleName,
                    "overloaded",
                    Seq(
                      Suggestion.Argument("this", "Any", false, false, None),
                      Suggestion.Argument("arg", "Any", false, false, None)
                    ),
                    "Text",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector()
              ),
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    None,
                    moduleName,
                    "overloaded",
                    Seq(
                      Suggestion.Argument("this", "Any", false, false, None),
                      Suggestion.Argument("arg", "Any", false, false, None)
                    ),
                    "Number",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector()
              )
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )

    // push call1
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.LocalCall(id1)
        )
      )
    )
    context.receive(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.executionComplete(contextId)
    )

    // pop call1
    context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))
    context.receive(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PopContextResponse(contextId)),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(
            Api.ExpressionValueUpdate(
              id1,
              Some("Number"),
              Some(Api.MethodPointer(moduleName, "Number", "overloaded"))
            )
          )
        )
      ),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(
            Api.ExpressionValueUpdate(
              id2,
              Some("Number"),
              Some(Api.MethodPointer(moduleName, "Text", "overloaded"))
            )
          )
        )
      ),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(
            Api.ExpressionValueUpdate(
              id3,
              Some("Number"),
              Some(Api.MethodPointer(moduleName, "Number", "overloaded"))
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )

    // push call2
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.LocalCall(id2)
        )
      )
    )
    context.receive(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.executionComplete(contextId)
    )

    // pop call2
    context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))
    context.receive(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PopContextResponse(contextId)),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(
            Api.ExpressionValueUpdate(
              id1,
              Some("Number"),
              Some(Api.MethodPointer(moduleName, "Number", "overloaded"))
            )
          )
        )
      ),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(
            Api.ExpressionValueUpdate(
              id2,
              Some("Number"),
              Some(Api.MethodPointer(moduleName, "Text", "overloaded"))
            )
          )
        )
      ),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(
            Api.ExpressionValueUpdate(
              id3,
              Some("Number"),
              Some(Api.MethodPointer(moduleName, "Number", "overloaded"))
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )

    // push call3
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.LocalCall(id3)
        )
      )
    )
    context.receive(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.executionComplete(contextId)
    )

    // pop call3
    context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))
    context.receive(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PopContextResponse(contextId)),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(
            Api.ExpressionValueUpdate(
              id1,
              Some("Number"),
              Some(Api.MethodPointer(moduleName, "Number", "overloaded"))
            )
          )
        )
      ),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(
            Api.ExpressionValueUpdate(
              id2,
              Some("Number"),
              Some(Api.MethodPointer(moduleName, "Text", "overloaded"))
            )
          )
        )
      ),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(
            Api.ExpressionValueUpdate(
              id3,
              Some("Number"),
              Some(Api.MethodPointer(moduleName, "Number", "overloaded"))
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "support file modification operations without attached ids" in {
    val contextId = UUID.randomUUID()
    val requestId = UUID.randomUUID()

    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    val moduleName = "Test.Main"
    val code =
      """from Builtins import all
        |
        |main = IO.println "I'm a file!"
        |""".stripMargin
    val version = contentsVersion(code)

    // Create a new file
    val mainFile = context.writeMain(code)

    // Open the new file
    context.send(Api.Request(Api.OpenFileNotification(mainFile, code, false)))
    context.receiveNone shouldEqual None
    context.consumeOut shouldEqual List()

    // Push new item on the stack to trigger the re-execution
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem
            .ExplicitCall(
              Api.MethodPointer(moduleName, "Main", "main"),
              None,
              Vector()
            )
        )
      )
    )
    context.receive(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.SuggestionsDatabaseModuleUpdateNotification(
          file    = mainFile,
          version = version,
          actions = Vector(Api.SuggestionsDatabaseAction.Clean(moduleName)),
          updates = Tree.Root(
            Vector(
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    None,
                    "Test.Main",
                    "main",
                    Seq(Suggestion.Argument("this", "Any", false, false, None)),
                    "Main",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector()
              )
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("I'm a file!")

    // Modify the file
    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            TextEdit(
              model.Range(model.Position(2, 25), model.Position(2, 29)),
              "modified"
            )
          )
        )
      )
    )
    context.receive shouldEqual Some(context.executionComplete(contextId))
    context.consumeOut shouldEqual List("I'm a modified!")

    // Close the file
    context.send(Api.Request(Api.CloseFileNotification(mainFile)))
    context.consumeOut shouldEqual List()
  }

  it should "support file modification operations with attached ids" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Test.Main"
    val metadata   = new Metadata
    val idMain     = metadata.addItem(7, 2)
    val code       = metadata.appendToCode("main = 84")
    val version    = contentsVersion(code)

    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Create a new file
    val mainFile = context.writeMain(code)

    // Open the new file
    context.send(
      Api.Request(
        Api.OpenFileNotification(
          mainFile,
          code,
          false
        )
      )
    )
    context.receiveNone shouldEqual None

    // Push new item on the stack to trigger the re-execution
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem
            .ExplicitCall(
              Api.MethodPointer(moduleName, "Main", "main"),
              None,
              Vector()
            )
        )
      )
    )
    context.receive(4) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(
            Api.ExpressionValueUpdate(idMain, Some("Number"), None)
          )
        )
      ),
      Api.Response(
        Api.SuggestionsDatabaseModuleUpdateNotification(
          file    = mainFile,
          version = version,
          actions = Vector(Api.SuggestionsDatabaseAction.Clean(moduleName)),
          updates = Tree.Root(
            Vector(
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    Some(idMain),
                    "Test.Main",
                    "main",
                    Seq(Suggestion.Argument("this", "Any", false, false, None)),
                    "Main",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector()
              )
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )

    // Modify the file
    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            TextEdit(
              model.Range(model.Position(0, 0), model.Position(0, 9)),
              "main = 42"
            )
          )
        )
      )
    )
    context.receive shouldEqual Some(context.executionComplete(contextId))
  }

  it should "send suggestion notifications when file is executed" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Test.Main"
    val idMain     = context.Main.metadata.addItem(33, 47)
    val idMainUpdate =
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(Api.ExpressionValueUpdate(idMain, Some("Number"), None))
        )
      )
    val version = contentsVersion(context.Main.code)

    val mainFile = context.writeMain(context.Main.code)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Open the new file
    context.send(
      Api.Request(
        Api.OpenFileNotification(
          mainFile,
          context.Main.code,
          false
        )
      )
    )
    context.receiveNone shouldEqual None

    // push main
    val item1 = Api.StackItem.ExplicitCall(
      Api.MethodPointer(moduleName, "Main", "main"),
      None,
      Vector()
    )
    context.send(
      Api.Request(requestId, Api.PushContextRequest(contextId, item1))
    )
    context.receive(7) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.Main.Update.mainX(contextId),
      context.Main.Update.mainY(contextId),
      context.Main.Update.mainZ(contextId),
      idMainUpdate,
      Api.Response(
        Api.SuggestionsDatabaseModuleUpdateNotification(
          file    = mainFile,
          version = version,
          actions = Vector(Api.SuggestionsDatabaseAction.Clean(moduleName)),
          updates = Tree.Root(
            Vector(
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    Some(idMain),
                    moduleName,
                    "main",
                    Seq(Suggestion.Argument("this", "Any", false, false, None)),
                    "Main",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector(
                  Tree.Node(
                    Api.SuggestionUpdate(
                      Suggestion.Local(
                        Some(context.Main.idMainX),
                        moduleName,
                        "x",
                        "Any",
                        Suggestion
                          .Scope(
                            Suggestion.Position(3, 6),
                            Suggestion.Position(8, 0)
                          )
                      ),
                      Api.SuggestionAction.Add()
                    ),
                    Vector()
                  ),
                  Tree.Node(
                    Api.SuggestionUpdate(
                      Suggestion.Local(
                        Some(context.Main.idMainY),
                        moduleName,
                        "y",
                        "Any",
                        Suggestion
                          .Scope(
                            Suggestion.Position(3, 6),
                            Suggestion.Position(8, 0)
                          )
                      ),
                      Api.SuggestionAction.Add()
                    ),
                    Vector()
                  ),
                  Tree.Node(
                    Api.SuggestionUpdate(
                      Suggestion.Local(
                        Some(context.Main.idMainZ),
                        moduleName,
                        "z",
                        "Any",
                        Suggestion
                          .Scope(
                            Suggestion.Position(3, 6),
                            Suggestion.Position(8, 0)
                          )
                      ),
                      Api.SuggestionAction.Add()
                    ),
                    Vector()
                  )
                )
              ),
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    None,
                    moduleName,
                    "foo",
                    Seq(
                      Suggestion.Argument("this", "Any", false, false, None),
                      Suggestion.Argument("x", "Any", false, false, None)
                    ),
                    "Number",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector(
                  Tree.Node(
                    Api.SuggestionUpdate(
                      Suggestion.Local(
                        Some(context.Main.idFooY),
                        moduleName,
                        "y",
                        "Any",
                        Suggestion
                          .Scope(
                            Suggestion.Position(9, 17),
                            Suggestion.Position(12, 5)
                          )
                      ),
                      Api.SuggestionAction.Add()
                    ),
                    Vector()
                  ),
                  Tree.Node(
                    Api.SuggestionUpdate(
                      Suggestion.Local(
                        Some(context.Main.idFooZ),
                        moduleName,
                        "z",
                        "Any",
                        Suggestion.Scope(
                          Suggestion.Position(9, 17),
                          Suggestion.Position(12, 5)
                        )
                      ),
                      Api.SuggestionAction.Add()
                    ),
                    Vector()
                  )
                )
              )
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )

    // push foo call
    val item2 = Api.StackItem.LocalCall(context.Main.idMainY)
    context.send(
      Api.Request(requestId, Api.PushContextRequest(contextId, item2))
    )
    context.receive(4) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.Main.Update.fooY(contextId),
      context.Main.Update.fooZ(contextId),
      context.executionComplete(contextId)
    )

    // pop foo call
    context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))
    context.receive(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PopContextResponse(contextId)),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(
            Api.ExpressionValueUpdate(
              context.Main.idMainY,
              Some("Number"),
              Some(Api.MethodPointer("Test.Main", "Number", "foo"))
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )

    // pop main
    context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))
    context.receive(1) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PopContextResponse(contextId))
    )

    // pop empty stack
    context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.EmptyStackError(contextId))
    )
  }

  it should "send suggestion notifications when file is modified" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Test.Main"
    val newline    = System.lineSeparator()

    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    val code =
      """from Builtins import all
        |
        |main = IO.println "I'm a file!"
        |""".stripMargin
    val version = contentsVersion(code)

    // Create a new file
    val mainFile = context.writeMain(code)

    // Open the new file
    context.send(Api.Request(Api.OpenFileNotification(mainFile, code, false)))
    context.receiveNone shouldEqual None
    context.consumeOut shouldEqual List()

    // Push new item on the stack to trigger the re-execution
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem
            .ExplicitCall(
              Api.MethodPointer(moduleName, "Main", "main"),
              None,
              Vector()
            )
        )
      )
    )
    context.receive(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.SuggestionsDatabaseModuleUpdateNotification(
          file    = mainFile,
          version = version,
          actions = Vector(Api.SuggestionsDatabaseAction.Clean(moduleName)),
          updates = Tree.Root(
            Vector(
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    None,
                    moduleName,
                    "main",
                    Seq(Suggestion.Argument("this", "Any", false, false, None)),
                    "Main",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector()
              )
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("I'm a file!")

    // Modify the file
    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            TextEdit(
              model.Range(model.Position(2, 25), model.Position(2, 29)),
              "modified"
            ),
            TextEdit(
              model.Range(model.Position(2, 0), model.Position(2, 0)),
              s"Number.lucky = 42$newline$newline"
            )
          )
        )
      )
    )
    val codeModified =
      """from Builtins import all
        |
        |Number.lucky = 42
        |
        |main = IO.println "I'm a modified!"
        |""".stripMargin
    context.receive(2) should contain theSameElementsAs Seq(
      Api.Response(
        Api.SuggestionsDatabaseModuleUpdateNotification(
          file    = mainFile,
          version = contentsVersion(codeModified),
          actions = Vector(),
          updates = Tree.Root(
            Vector(
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    None,
                    moduleName,
                    "lucky",
                    Seq(Suggestion.Argument("this", "Any", false, false, None)),
                    "Number",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector()
              )
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("I'm a modified!")

    // Close the file
    context.send(Api.Request(Api.CloseFileNotification(mainFile)))
    context.receiveNone shouldEqual None
    context.consumeOut shouldEqual List()
  }

  it should "recompute expressions without invalidation" in {
    val contents   = context.Main.code
    val mainFile   = context.writeMain(contents)
    val moduleName = "Test.Main"
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()

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
    val item1 = Api.StackItem.ExplicitCall(
      Api.MethodPointer(moduleName, "Main", "main"),
      None,
      Vector()
    )
    context.send(
      Api.Request(requestId, Api.PushContextRequest(contextId, item1))
    )
    context.receive(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.Main.Update.mainX(contextId),
      context.Main.Update.mainY(contextId),
      context.Main.Update.mainZ(contextId),
      context.executionComplete(contextId)
    )

    // recompute
    context.send(
      Api.Request(requestId, Api.RecomputeContextRequest(contextId, None))
    )
    context.receive(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.RecomputeContextResponse(contextId)),
      context.executionComplete(contextId)
    )
  }

  it should "recompute expressions invalidating all" in {
    val contents   = context.Main.code
    val mainFile   = context.writeMain(contents)
    val moduleName = "Test.Main"
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()

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
    val item1 = Api.StackItem.ExplicitCall(
      Api.MethodPointer(moduleName, "Main", "main"),
      None,
      Vector()
    )
    context.send(
      Api.Request(requestId, Api.PushContextRequest(contextId, item1))
    )
    context.receive(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.Main.Update.mainX(contextId),
      context.Main.Update.mainY(contextId),
      context.Main.Update.mainZ(contextId),
      context.executionComplete(contextId)
    )

    // recompute
    context.send(
      Api.Request(
        requestId,
        Api.RecomputeContextRequest(
          contextId,
          Some(Api.InvalidatedExpressions.All())
        )
      )
    )
    context.receive(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.RecomputeContextResponse(contextId)),
      context.executionComplete(contextId)
    )
  }

  it should "recompute expressions invalidating some" in {
    val contents   = context.Main.code
    val mainFile   = context.writeMain(contents)
    val moduleName = "Test.Main"
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()

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
    val item1 = Api.StackItem.ExplicitCall(
      Api.MethodPointer(moduleName, "Main", "main"),
      None,
      Vector()
    )
    context.send(
      Api.Request(requestId, Api.PushContextRequest(contextId, item1))
    )
    context.receive(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.Main.Update.mainX(contextId),
      context.Main.Update.mainY(contextId),
      context.Main.Update.mainZ(contextId),
      context.executionComplete(contextId)
    )

    // recompute
    context.send(
      Api.Request(
        requestId,
        Api.RecomputeContextRequest(
          contextId,
          Some(
            Api.InvalidatedExpressions.Expressions(Vector(context.Main.idMainZ))
          )
        )
      )
    )
    context.receive(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.RecomputeContextResponse(contextId)),
      context.executionComplete(contextId)
    )
  }

  it should "return error when module not found" in {
    val contents  = context.Main.code
    val mainFile  = context.writeMain(context.Main.code)
    val contextId = UUID.randomUUID()
    val requestId = UUID.randomUUID()

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
            Api.MethodPointer("Unnamed.Main", "Main", "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receive(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionFailed(
          contextId,
          Api.ExecutionResult.Failure("Module Unnamed.Main not found.", None)
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "return error when constructor not found" in {
    val contents  = context.Main.code
    val mainFile  = context.writeMain(contents)
    val contextId = UUID.randomUUID()
    val requestId = UUID.randomUUID()

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
            Api.MethodPointer("Test.Main", "Unexpected", "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receive(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionFailed(
          contextId,
          Api.ExecutionResult.Failure(
            "Constructor Unexpected not found in module Test.Main.",
            Some(mainFile)
          )
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "return error when method not found" in {
    val contents  = context.Main.code
    val mainFile  = context.writeMain(contents)
    val contextId = UUID.randomUUID()
    val requestId = UUID.randomUUID()

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
            Api.MethodPointer("Test.Main", "Main", "ooops"),
            None,
            Vector()
          )
        )
      )
    )
    context.receive(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionFailed(
          contextId,
          Api.ExecutionResult.Failure(
            "Object Main does not define method ooops in module Test.Main.",
            Some(mainFile)
          )
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "return error not invocable" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Test.Main"
    val metadata   = new Metadata
    val code =
      """main = this.bar 40 2 123
        |
        |bar x y = x + y
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
            Api.MethodPointer(moduleName, "Main", "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receive(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionUpdate(
          contextId,
          Seq(
            Api.ExecutionResult.Diagnostic.error(
              "Object 42 is not invokable.",
              Some(mainFile),
              Some(model.Range(model.Position(0, 7), model.Position(0, 24))),
              Vector(
                Api.StackTraceElement(
                  "Main.main",
                  Some(mainFile),
                  Some(model.Range(model.Position(0, 7), model.Position(0, 24)))
                )
              )
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "return error unresolved symbol" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Test.Main"
    val metadata   = new Metadata
    val code =
      """main = this.bar x y
        |
        |bar one two = one + two
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
            Api.MethodPointer(moduleName, "Main", "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receive(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionUpdate(
          contextId,
          Seq(
            Api.ExecutionResult.Diagnostic.error(
              "No_Such_Method_Error UnresolvedSymbol<x> UnresolvedSymbol<+>",
              Some(mainFile),
              Some(model.Range(model.Position(2, 14), model.Position(2, 23))),
              Vector(
                Api.StackTraceElement(
                  "Main.bar",
                  Some(mainFile),
                  Some(
                    model.Range(model.Position(2, 14), model.Position(2, 23))
                  )
                ),
                Api.StackTraceElement(
                  "Main.main",
                  Some(mainFile),
                  Some(model.Range(model.Position(0, 7), model.Position(0, 19)))
                )
              )
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "return error unexpected type" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Test.Main"
    val metadata   = new Metadata
    val code =
      """main = this.bar "one" 2
        |
        |bar x y = x + y
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
            Api.MethodPointer(moduleName, "Main", "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receive(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionUpdate(
          contextId,
          Seq(
            Api.ExecutionResult.Diagnostic.error(
              "Unexpected type provided for argument `that` in Text.+",
              None,
              None,
              Vector(
                Api.StackTraceElement("Text.+", None, None),
                Api.StackTraceElement(
                  "Main.bar",
                  Some(mainFile),
                  Some(
                    model.Range(model.Position(2, 10), model.Position(2, 15))
                  )
                ),
                Api.StackTraceElement(
                  "Main.main",
                  Some(mainFile),
                  Some(model.Range(model.Position(0, 7), model.Position(0, 23)))
                )
              )
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "return error method does not exist" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Test.Main"
    val metadata   = new Metadata

    val code =
      """from Builtins import all
        |
        |main = Number.pi
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
            Api.MethodPointer(moduleName, "Main", "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receive(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionUpdate(
          contextId,
          Seq(
            Api.ExecutionResult.Diagnostic.error(
              "No_Such_Method_Error Number UnresolvedSymbol<pi>",
              Some(mainFile),
              Some(model.Range(model.Position(2, 7), model.Position(2, 16))),
              Vector(
                Api.StackTraceElement(
                  "Main.main",
                  Some(mainFile),
                  Some(model.Range(model.Position(2, 7), model.Position(2, 16)))
                )
              )
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "return error with a stack trace" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Test.Main"
    val metadata   = new Metadata

    val code =
      """from Builtins import all
        |
        |main = this.foo
        |
        |foo =
        |    x = this.bar
        |    x
        |bar =
        |    x = this.baz
        |    x
        |baz =
        |    x = 1 + quux
        |    x
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
            Api.MethodPointer(moduleName, "Main", "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receive(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionUpdate(
          contextId,
          Seq(
            Api.ExecutionResult.Diagnostic.error(
              "Unexpected type provided for argument `that` in Integer.+",
              None,
              None,
              Vector(
                Api.StackTraceElement("Small_Integer.+", None, None),
                Api.StackTraceElement(
                  "Main.baz",
                  Some(mainFile),
                  Some(
                    model.Range(model.Position(11, 8), model.Position(11, 16))
                  )
                ),
                Api.StackTraceElement(
                  "Main.bar",
                  Some(mainFile),
                  Some(model.Range(model.Position(8, 8), model.Position(8, 16)))
                ),
                Api.StackTraceElement(
                  "Main.foo",
                  Some(mainFile),
                  Some(model.Range(model.Position(5, 8), model.Position(5, 16)))
                ),
                Api.StackTraceElement(
                  "Main.main",
                  Some(mainFile),
                  Some(model.Range(model.Position(2, 7), model.Position(2, 15)))
                )
              )
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "return compiler warning unused variable" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Test.Main"
    val metadata   = new Metadata

    val code =
      """from Builtins import all
        |
        |main = x = 1
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
            Api.MethodPointer(moduleName, "Main", "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receive(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionUpdate(
          contextId,
          Seq(
            Api.ExecutionResult.Diagnostic.warning(
              "Unused variable x.",
              Some(mainFile),
              Some(model.Range(model.Position(2, 7), model.Position(2, 8)))
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "return compiler warning unused argument" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Test.Main"
    val metadata   = new Metadata

    val code =
      """from Builtins import all
        |
        |foo x = 1
        |
        |main = 42
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
            Api.MethodPointer(moduleName, "Main", "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receive(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionUpdate(
          contextId,
          Seq(
            Api.ExecutionResult.Diagnostic.warning(
              "Unused function argument x.",
              Some(mainFile),
              Some(model.Range(model.Position(2, 4), model.Position(2, 5)))
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "return compiler error variable redefined" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Test.Main"
    val metadata   = new Metadata

    val code =
      """from Builtins import all
        |
        |main =
        |    x = 1
        |    x = 2
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
            Api.MethodPointer(moduleName, "Main", "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receive(2) should contain theSameElementsAs Seq(
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
              "Variable x is being redefined.",
              Some(mainFile),
              Some(model.Range(model.Position(4, 4), model.Position(4, 9)))
            )
          )
        )
      )
    )
  }

  it should "return compiler error unrecognized token" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Test.Main"
    val metadata   = new Metadata

    val code =
      """from Builtins import all
        |
        |main =
        |    x = Panic.recover @
        |    IO.println (x.catch to_text)
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
            Api.MethodPointer(moduleName, "Main", "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receive(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionUpdate(
          contextId,
          Seq(
            Api.ExecutionResult.Diagnostic.error(
              "Unrecognized token.",
              Some(mainFile),
              Some(model.Range(model.Position(3, 22), model.Position(3, 23)))
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("(Syntax_Error 'Unrecognized token.')")
  }

  it should "return compiler error syntax error" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Test.Main"
    val metadata   = new Metadata

    val code =
      """from Builtins import all
        |
        |main =
        |    x = Panic.recover ()
        |    IO.println (x.catch to_text)
        |
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
            Api.MethodPointer(moduleName, "Main", "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receive(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionUpdate(
          contextId,
          Seq(
            Api.ExecutionResult.Diagnostic.error(
              "Parentheses can't be empty.",
              Some(mainFile),
              Some(model.Range(model.Position(3, 22), model.Position(3, 24)))
            )
          )
        )
      )
    )
  }

  it should "return compiler error method overloads are not supported" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Test.Main"
    val metadata   = new Metadata

    val code =
      """from Builtins import all
        |
        |foo = 1
        |foo = 2
        |
        |main = IO.println this.foo
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
            Api.MethodPointer(moduleName, "Main", "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receive(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionUpdate(
          contextId,
          Seq(
            Api.ExecutionResult.Diagnostic.error(
              "Method overloads are not supported: here.foo is defined multiple times in this module.",
              Some(mainFile),
              Some(model.Range(model.Position(3, 0), model.Position(3, 7)))
            )
          )
        )
      )
    )
  }

  it should "skip side effects when evaluating cached expression" in {
    val contents  = context.Main2.code
    val mainFile  = context.writeMain(contents)
    val contextId = UUID.randomUUID()
    val requestId = UUID.randomUUID()

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
    val item1 = Api.StackItem.ExplicitCall(
      Api.MethodPointer("Test.Main", "Main", "main"),
      None,
      Vector()
    )
    context.send(
      Api.Request(requestId, Api.PushContextRequest(contextId, item1))
    )
    context.receive(4) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.Main2.Update.mainY(contextId),
      context.Main2.Update.mainZ(contextId),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("I'm expensive!", "I'm more expensive!")

    // recompute
    context.send(
      Api.Request(requestId, Api.RecomputeContextRequest(contextId, None))
    )
    context.receive(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.RecomputeContextResponse(contextId)),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List()
  }

  it should "emit visualisation update when expression is computed" in {
    val idMain     = context.Main.metadata.addItem(78, 1)
    val contents   = context.Main.code
    val mainFile   = context.writeMain(context.Main.code)
    val moduleName = "Test.Main"
    val visualisationFile =
      context.writeInSrcDir("Visualisation", context.Visualisation.code)

    context.send(
      Api.Request(
        Api.OpenFileNotification(
          visualisationFile,
          context.Visualisation.code,
          false
        )
      )
    )

    val contextId       = UUID.randomUUID()
    val requestId       = UUID.randomUUID()
    val visualisationId = UUID.randomUUID()

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
    val item1 = Api.StackItem.ExplicitCall(
      Api.MethodPointer(moduleName, "Main", "main"),
      None,
      Vector()
    )
    context.send(
      Api.Request(requestId, Api.PushContextRequest(contextId, item1))
    )
    context.receive(7) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.Main.Update.mainX(contextId),
      context.Main.Update.mainY(contextId),
      context.Main.Update.mainZ(contextId),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(
            Api.ExpressionValueUpdate(
              idMain,
              Some("Number"),
              None
            )
          )
        )
      ),
      Api.Response(
        Api.SuggestionsDatabaseModuleUpdateNotification(
          file    = visualisationFile,
          version = contentsVersion(context.Visualisation.code),
          actions =
            Vector(Api.SuggestionsDatabaseAction.Clean("Test.Visualisation")),
          updates = Tree.Root(
            Vector(
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    None,
                    "Test.Visualisation",
                    "encode",
                    List(
                      Suggestion.Argument("this", "Any", false, false, None),
                      Suggestion.Argument("x", "Any", false, false, None)
                    ),
                    "Visualisation",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector()
              ),
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    None,
                    "Test.Visualisation",
                    "incAndEncode",
                    List(
                      Suggestion.Argument("this", "Any", false, false, None),
                      Suggestion.Argument("x", "Any", false, false, None)
                    ),
                    "Visualisation",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector()
              )
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )

    // attach visualisation
    context.send(
      Api.Request(
        requestId,
        Api.AttachVisualisation(
          visualisationId,
          idMain,
          Api.VisualisationConfiguration(
            contextId,
            "Test.Visualisation",
            "x -> here.encode x"
          )
        )
      )
    )
    val attachVisualisationResponses = context.receive(3)
    attachVisualisationResponses should contain allOf (
      Api.Response(requestId, Api.VisualisationAttached()),
      context.executionComplete(contextId)
    )
    val Some(data) = attachVisualisationResponses.collectFirst {
      case Api.Response(
            None,
            Api.VisualisationUpdate(
              Api.VisualisationContext(
                `visualisationId`,
                `contextId`,
                `idMain`
              ),
              data
            )
          ) =>
        data
    }
    data.sameElements("50".getBytes) shouldBe true

    // recompute
    context.send(
      Api.Request(requestId, Api.RecomputeContextRequest(contextId, None))
    )

    val recomputeResponses = context.receive(3)
    recomputeResponses should contain allOf (
      Api.Response(requestId, Api.RecomputeContextResponse(contextId)),
      context.executionComplete(contextId)
    )
    val Some(data2) = recomputeResponses.collectFirst {
      case Api.Response(
            None,
            Api.VisualisationUpdate(
              Api.VisualisationContext(
                `visualisationId`,
                `contextId`,
                `idMain`
              ),
              data
            )
          ) =>
        data
    }
    data2.sameElements("50".getBytes) shouldBe true
  }

  it should "emit visualisation update when expression is cached" in {
    val contents   = context.Main.code
    val mainFile   = context.writeMain(context.Main.code)
    val moduleName = "Test.Main"
    val visualisationFile =
      context.writeInSrcDir("Visualisation", context.Visualisation.code)

    context.send(
      Api.Request(
        Api.OpenFileNotification(
          visualisationFile,
          context.Visualisation.code,
          false
        )
      )
    )

    val contextId       = UUID.randomUUID()
    val requestId       = UUID.randomUUID()
    val visualisationId = UUID.randomUUID()

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
    val item1 = Api.StackItem.ExplicitCall(
      Api.MethodPointer(moduleName, "Main", "main"),
      None,
      Vector()
    )
    context.send(
      Api.Request(requestId, Api.PushContextRequest(contextId, item1))
    )
    context.receive(6) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.Main.Update.mainX(contextId),
      context.Main.Update.mainY(contextId),
      context.Main.Update.mainZ(contextId),
      Api.Response(
        Api.SuggestionsDatabaseModuleUpdateNotification(
          file    = visualisationFile,
          version = contentsVersion(context.Visualisation.code),
          actions =
            Vector(Api.SuggestionsDatabaseAction.Clean("Test.Visualisation")),
          updates = Tree.Root(
            Vector(
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    None,
                    "Test.Visualisation",
                    "encode",
                    List(
                      Suggestion.Argument("this", "Any", false, false, None),
                      Suggestion.Argument("x", "Any", false, false, None)
                    ),
                    "Visualisation",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector()
              ),
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    None,
                    "Test.Visualisation",
                    "incAndEncode",
                    List(
                      Suggestion.Argument("this", "Any", false, false, None),
                      Suggestion.Argument("x", "Any", false, false, None)
                    ),
                    "Visualisation",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector()
              )
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )

    // attach visualisation
    context.send(
      Api.Request(
        requestId,
        Api.AttachVisualisation(
          visualisationId,
          context.Main.idMainX,
          Api.VisualisationConfiguration(
            contextId,
            "Test.Visualisation",
            "x -> here.encode x"
          )
        )
      )
    )
    val attachVisualisationResponses = context.receive(2)
    attachVisualisationResponses should contain(
      Api.Response(requestId, Api.VisualisationAttached())
    )
    val expectedExpressionId = context.Main.idMainX
    val Some(data) = attachVisualisationResponses.collectFirst {
      case Api.Response(
            None,
            Api.VisualisationUpdate(
              Api.VisualisationContext(
                `visualisationId`,
                `contextId`,
                `expectedExpressionId`
              ),
              data
            )
          ) =>
        data
    }
    data.sameElements("6".getBytes) shouldBe true

    // recompute
    context.send(
      Api.Request(requestId, Api.RecomputeContextRequest(contextId, None))
    )
    context.receive(2) should contain allOf (
      Api.Response(requestId, Api.RecomputeContextResponse(contextId)),
      context.executionComplete(contextId)
    )

    // recompute invalidating x
    context.send(
      Api.Request(
        requestId,
        Api.RecomputeContextRequest(
          contextId,
          Some(
            Api.InvalidatedExpressions.Expressions(Vector(context.Main.idMainX))
          )
        )
      )
    )
    val recomputeResponses2 = context.receive(3)
    recomputeResponses2 should contain allOf (
      Api.Response(requestId, Api.RecomputeContextResponse(contextId)),
      context.executionComplete(contextId)
    )
    val Some(data2) = recomputeResponses2.collectFirst {
      case Api.Response(
            None,
            Api.VisualisationUpdate(
              Api.VisualisationContext(
                `visualisationId`,
                `contextId`,
                `expectedExpressionId`
              ),
              data
            )
          ) =>
        data
    }
    data2.sameElements("6".getBytes) shouldBe true
  }

  it should "emit visualisation update without value update" in {
    val contents   = context.Main.code
    val version    = contentsVersion(contents)
    val moduleName = "Test.Main"
    val mainFile   = context.writeMain(contents)
    val visualisationFile =
      context.writeInSrcDir("Visualisation", context.Visualisation.code)

    val contextId       = UUID.randomUUID()
    val requestId       = UUID.randomUUID()
    val visualisationId = UUID.randomUUID()

    // open files
    context.send(
      Api.Request(
        Api.OpenFileNotification(
          visualisationFile,
          context.Visualisation.code,
          false
        )
      )
    )
    context.receiveNone shouldEqual None
    context.send(
      Api.Request(Api.OpenFileNotification(mainFile, contents, false))
    )
    context.receiveNone shouldEqual None

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // push main
    val item1 = Api.StackItem.ExplicitCall(
      Api.MethodPointer(moduleName, "Main", "main"),
      None,
      Vector()
    )
    context.send(
      Api.Request(requestId, Api.PushContextRequest(contextId, item1))
    )

    context.receive(7) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.Main.Update.mainX(contextId),
      context.Main.Update.mainY(contextId),
      context.Main.Update.mainZ(contextId),
      Api.Response(
        Api.SuggestionsDatabaseModuleUpdateNotification(
          file    = visualisationFile,
          version = contentsVersion(context.Visualisation.code),
          actions =
            Vector(Api.SuggestionsDatabaseAction.Clean("Test.Visualisation")),
          updates = Tree.Root(
            Vector(
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    None,
                    "Test.Visualisation",
                    "encode",
                    List(
                      Suggestion.Argument("this", "Any", false, false, None),
                      Suggestion.Argument("x", "Any", false, false, None)
                    ),
                    "Visualisation",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector()
              ),
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    None,
                    "Test.Visualisation",
                    "incAndEncode",
                    List(
                      Suggestion.Argument("this", "Any", false, false, None),
                      Suggestion.Argument("x", "Any", false, false, None)
                    ),
                    "Visualisation",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector()
              )
            )
          )
        )
      ),
      Api.Response(
        Api.SuggestionsDatabaseModuleUpdateNotification(
          file    = mainFile,
          version = version,
          actions = Vector(Api.SuggestionsDatabaseAction.Clean(moduleName)),
          updates = Tree.Root(
            Vector(
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    None,
                    moduleName,
                    "main",
                    Seq(Suggestion.Argument("this", "Any", false, false, None)),
                    "Main",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector(
                  Tree.Node(
                    Api.SuggestionUpdate(
                      Suggestion.Local(
                        Some(context.Main.idMainX),
                        moduleName,
                        "x",
                        "Any",
                        Suggestion
                          .Scope(
                            Suggestion.Position(3, 6),
                            Suggestion.Position(8, 0)
                          )
                      ),
                      Api.SuggestionAction.Add()
                    ),
                    Vector()
                  ),
                  Tree.Node(
                    Api.SuggestionUpdate(
                      Suggestion.Local(
                        Some(context.Main.idMainY),
                        moduleName,
                        "y",
                        "Any",
                        Suggestion
                          .Scope(
                            Suggestion.Position(3, 6),
                            Suggestion.Position(8, 0)
                          )
                      ),
                      Api.SuggestionAction.Add()
                    ),
                    Vector()
                  ),
                  Tree.Node(
                    Api.SuggestionUpdate(
                      Suggestion.Local(
                        Some(context.Main.idMainZ),
                        moduleName,
                        "z",
                        "Any",
                        Suggestion
                          .Scope(
                            Suggestion.Position(3, 6),
                            Suggestion.Position(8, 0)
                          )
                      ),
                      Api.SuggestionAction.Add()
                    ),
                    Vector()
                  )
                )
              ),
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestion.Method(
                    None,
                    moduleName,
                    "foo",
                    Seq(
                      Suggestion.Argument("this", "Any", false, false, None),
                      Suggestion.Argument("x", "Any", false, false, None)
                    ),
                    "Number",
                    "Any",
                    None
                  ),
                  Api.SuggestionAction.Add()
                ),
                Vector(
                  Tree.Node(
                    Api.SuggestionUpdate(
                      Suggestion.Local(
                        Some(context.Main.idFooY),
                        moduleName,
                        "y",
                        "Any",
                        Suggestion
                          .Scope(
                            Suggestion.Position(9, 17),
                            Suggestion.Position(12, 5)
                          )
                      ),
                      Api.SuggestionAction.Add()
                    ),
                    Vector()
                  ),
                  Tree.Node(
                    Api.SuggestionUpdate(
                      Suggestion.Local(
                        Some(context.Main.idFooZ),
                        moduleName,
                        "z",
                        "Any",
                        Suggestion.Scope(
                          Suggestion.Position(9, 17),
                          Suggestion.Position(12, 5)
                        )
                      ),
                      Api.SuggestionAction.Add()
                    ),
                    Vector()
                  )
                )
              )
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )

    // attach visualization
    context.send(
      Api.Request(
        requestId,
        Api.AttachVisualisation(
          visualisationId,
          context.Main.idMainX,
          Api.VisualisationConfiguration(
            contextId,
            "Test.Visualisation",
            "x -> here.encode x"
          )
        )
      )
    )
    val attachVisualisationResponses = context.receive(2)
    attachVisualisationResponses should contain(
      Api.Response(requestId, Api.VisualisationAttached())
    )
    val expectedExpressionId = context.Main.idMainX
    val Some(data) = attachVisualisationResponses.collectFirst {
      case Api.Response(
            None,
            Api.VisualisationUpdate(
              Api.VisualisationContext(
                `visualisationId`,
                `contextId`,
                `expectedExpressionId`
              ),
              data
            )
          ) =>
        data
    }
    data.sameElements("6".getBytes) shouldBe true

    // Modify the file
    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            TextEdit(
              model.Range(model.Position(4, 8), model.Position(4, 9)),
              "5"
            )
          )
        )
      )
    )

    val editFileResponse = context.receive(1)
    val Some(data1) = editFileResponse.collectFirst {
      case Api.Response(
            None,
            Api.VisualisationUpdate(
              Api.VisualisationContext(
                `visualisationId`,
                `contextId`,
                `expectedExpressionId`
              ),
              data
            )
          ) =>
        data
    }
    data1.sameElements("5".getBytes) shouldBe true
  }

  it should "be able to modify visualisations" in {
    val contents = context.Main.code
    val mainFile = context.writeMain(contents)
    val visualisationFile =
      context.writeInSrcDir("Visualisation", context.Visualisation.code)

    // open files
    context.send(
      Api.Request(Api.OpenFileNotification(mainFile, contents, true))
    )
    context.receiveNone shouldEqual None
    context.send(
      Api.Request(
        Api.OpenFileNotification(
          visualisationFile,
          context.Visualisation.code,
          true
        )
      )
    )
    context.receiveNone shouldEqual None

    val contextId       = UUID.randomUUID()
    val requestId       = UUID.randomUUID()
    val visualisationId = UUID.randomUUID()

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // push main
    val item1 = Api.StackItem.ExplicitCall(
      Api.MethodPointer("Test.Main", "Main", "main"),
      None,
      Vector()
    )
    context.send(
      Api.Request(requestId, Api.PushContextRequest(contextId, item1))
    )
    context.receive(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.Main.Update.mainX(contextId),
      context.Main.Update.mainY(contextId),
      context.Main.Update.mainZ(contextId),
      context.executionComplete(contextId)
    )

    // attach visualisation
    context.send(
      Api.Request(
        requestId,
        Api.AttachVisualisation(
          visualisationId,
          context.Main.idMainX,
          Api.VisualisationConfiguration(
            contextId,
            "Test.Visualisation",
            "x -> here.encode x"
          )
        )
      )
    )

    val attachVisualisationResponses = context.receive(2)
    attachVisualisationResponses should contain(
      Api.Response(requestId, Api.VisualisationAttached())
    )
    val expectedExpressionId = context.Main.idMainX
    val Some(data) = attachVisualisationResponses.collectFirst {
      case Api.Response(
            None,
            Api.VisualisationUpdate(
              Api.VisualisationContext(
                `visualisationId`,
                `contextId`,
                `expectedExpressionId`
              ),
              data
            )
          ) =>
        data
    }
    data.sameElements("6".getBytes) shouldBe true

    // modify visualisation
    context.send(
      Api.Request(
        requestId,
        Api.ModifyVisualisation(
          visualisationId,
          Api.VisualisationConfiguration(
            contextId,
            "Test.Visualisation",
            "x -> here.incAndEncode x"
          )
        )
      )
    )
    val modifyVisualisationResponses = context.receive(2)
    modifyVisualisationResponses should contain(
      Api.Response(requestId, Api.VisualisationModified())
    )
    val Some(dataAfterModification) =
      modifyVisualisationResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualisationUpdate(
                Api.VisualisationContext(
                  `visualisationId`,
                  `contextId`,
                  `expectedExpressionId`
                ),
                data
              )
            ) =>
          data
      }
    dataAfterModification.sameElements("7".getBytes) shouldBe true
  }

  it should "not emit visualisation updates when visualisation is detached" in {
    val contents = context.Main.code
    val mainFile = context.writeMain(contents)
    val visualisationFile =
      context.writeInSrcDir("Visualisation", context.Visualisation.code)

    // open files
    context.send(
      Api.Request(Api.OpenFileNotification(mainFile, contents, true))
    )
    context.receiveNone shouldEqual None
    context.send(
      Api.Request(
        Api.OpenFileNotification(
          visualisationFile,
          context.Visualisation.code,
          true
        )
      )
    )

    val contextId       = UUID.randomUUID()
    val requestId       = UUID.randomUUID()
    val visualisationId = UUID.randomUUID()

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // attach visualisation
    context.send(
      Api.Request(
        requestId,
        Api.AttachVisualisation(
          visualisationId,
          context.Main.idMainX,
          Api.VisualisationConfiguration(
            contextId,
            "Test.Visualisation",
            "x -> here.encode x"
          )
        )
      )
    )
    context.receive(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.VisualisationAttached()),
      Api.Response(
        Api.ExecutionFailed(
          contextId,
          Api.ExecutionResult.Failure("Execution stack is empty.", None)
        )
      ),
      context.executionComplete(contextId)
    )

    // push main
    val item1 = Api.StackItem.ExplicitCall(
      Api.MethodPointer("Test.Main", "Main", "main"),
      None,
      Vector()
    )
    context.send(
      Api.Request(requestId, Api.PushContextRequest(contextId, item1))
    )
    val pushResponses = context.receive(6)
    pushResponses should contain allOf (
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.Main.Update.mainX(contextId),
      context.Main.Update.mainY(contextId),
      context.Main.Update.mainZ(contextId),
      context.executionComplete(contextId)
    )
    val expectedExpressionId = context.Main.idMainX
    val Some(data) =
      pushResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualisationUpdate(
                Api.VisualisationContext(
                  `visualisationId`,
                  `contextId`,
                  `expectedExpressionId`
                ),
                data
              )
            ) =>
          data
      }
    data.sameElements("6".getBytes) shouldBe true

    // detach visualisation
    context.send(
      Api.Request(
        requestId,
        Api.DetachVisualisation(
          contextId,
          visualisationId,
          context.Main.idMainX
        )
      )
    )
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.VisualisationDetached())
    )

    // recompute
    context.send(
      Api.Request(requestId, Api.RecomputeContextRequest(contextId, None))
    )
    context.receive(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.RecomputeContextResponse(contextId)),
      context.executionComplete(contextId)
    )

    // recompute invalidating x
    context.send(
      Api.Request(
        requestId,
        Api.RecomputeContextRequest(
          contextId,
          Some(
            Api.InvalidatedExpressions.Expressions(Vector(context.Main.idMainX))
          )
        )
      )
    )
    context.receive(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.RecomputeContextResponse(contextId)),
      context.executionComplete(contextId)
    )
  }

  it should "rename a project" in {
    val contents  = context.Main.code
    val mainFile  = context.writeMain(contents)
    val contextId = UUID.randomUUID()
    val requestId = UUID.randomUUID()

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
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
            Api.MethodPointer("Test.Main", "Main", "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receive(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.Main.Update.mainX(contextId),
      context.Main.Update.mainY(contextId),
      context.Main.Update.mainZ(contextId),
      context.executionComplete(contextId)
    )

    // rename Test -> Foo
    context.pkg.rename("Foo")
    context.send(Api.Request(requestId, Api.RenameProject("Test", "Foo")))
    context.receive(1) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.ProjectRenamed("Foo"))
    )

    // recompute existing stack
    context.send(
      Api.Request(requestId, Api.RecomputeContextRequest(contextId, None))
    )
    context.receive(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.RecomputeContextResponse(contextId)),
      context.executionComplete(contextId)
    )

    // recompute invalidating all
    context.send(
      Api.Request(
        requestId,
        Api.RecomputeContextRequest(
          contextId,
          Some(Api.InvalidatedExpressions.All())
        )
      )
    )
    context.receive(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.RecomputeContextResponse(contextId)),
      Api.Response(
        Api.ExpressionValuesComputed(
          contextId,
          Vector(
            Api.ExpressionValueUpdate(
              context.Main.idMainY,
              Some("Number"),
              Some(Api.MethodPointer("Foo.Main", "Number", "foo"))
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
  }

}
