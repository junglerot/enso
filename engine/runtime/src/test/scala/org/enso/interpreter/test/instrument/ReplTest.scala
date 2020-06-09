package org.enso.interpreter.test.instrument

import org.enso.interpreter.test.{InterpreterRunner, ValueEquality}
import org.enso.polyglot.debugger.protocol.{
  ExceptionRepresentation,
  ObjectRepresentation
}
import org.enso.polyglot.debugger.{
  DebugServerInfo,
  DebuggerSessionManagerEndpoint,
  ReplExecutor,
  SessionManager
}
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.io.MessageEndpoint
import org.enso.polyglot.{debugger, LanguageInfo, PolyglotContext}
import org.scalatest.BeforeAndAfter
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

trait ReplRunner extends InterpreterRunner {
  var endPoint: MessageEndpoint = _
  var messageQueue
    : List[debugger.Response] = List() // TODO probably need a better message handler

  class ReplaceableSessionManager extends SessionManager {
    var currentSessionManager: SessionManager = _
    def setSessionManager(manager: SessionManager): Unit =
      currentSessionManager = manager

    override def startSession(executor: ReplExecutor): Nothing =
      currentSessionManager.startSession(executor)
  }

  private val sessionManager = new ReplaceableSessionManager

  override val ctx = Context
    .newBuilder(LanguageInfo.ID)
    .allowExperimentalOptions(true)
    .allowAllAccess(true)
    .option(DebugServerInfo.ENABLE_OPTION, "true")
    .out(output)
    .err(err)
    .in(in)
    .serverTransport { (uri, peer) =>
      if (uri.toString == DebugServerInfo.URI) {
        new DebuggerSessionManagerEndpoint(sessionManager, peer)
      } else null
    }
    .build()

  override lazy val executionContext = new PolyglotContext(ctx)

  def setSessionManager(manager: SessionManager): Unit =
    sessionManager.setSessionManager(manager)
}

class ReplTest
    extends AnyWordSpec
    with Matchers
    with BeforeAndAfter
    with ValueEquality
    with ReplRunner {

  after {
    messageQueue = List()
  }

  "Repl" should {
    "initialize properly" in {
      val code =
        """
          |main = Debug.breakpoint
          |""".stripMargin
      setSessionManager(executor => executor.exit())
      eval(code)
    }

    "be able to execute arbitrary code in the caller scope" in {
      val code =
        """
          |main =
          |    x = 1
          |    y = 2
          |    Debug.breakpoint
          |""".stripMargin
      var evalResult: Either[ExceptionRepresentation, ObjectRepresentation] =
        null
      setSessionManager { executor =>
        evalResult = executor.evaluate("x + y")
        executor.exit()
      }
      eval(code) shouldEqual 3
      evalResult.fold(_.toString, _.representation()) shouldEqual "3"
    }

    "return the last evaluated value back to normal execution flow" in {
      val code =
        """
          |main =
          |    a = 5
          |    b = 6
          |    c = Debug.breakpoint
          |    c * a
          |""".stripMargin
      setSessionManager { executor =>
        executor.evaluate("a + b")
        executor.exit()
      }
      eval(code) shouldEqual 55
    }

    "be able to define its local variables" in {
      val code =
        """
          |main =
          |    x = 10
          |    Debug.breakpoint
          |""".stripMargin
      setSessionManager { executor =>
        executor.evaluate("y = x + 1")
        executor.evaluate("z = y * x")
        executor.evaluate("z")
        executor.exit()
      }
      eval(code) shouldEqual 110
    }

    "not overwrite bindings" in {
      val code =
        """
          |main =
          |    x = 10
          |    Debug.breakpoint
          |    x
          |""".stripMargin
      setSessionManager { executor =>
        executor.evaluate("x = 20")
        executor.exit()
      }
      eval(code) shouldEqual 10
    }

    "access and modify monadic state" in {
      val code =
        """
          |main =
          |    State.put 10
          |    Debug.breakpoint
          |    State.get
          |""".stripMargin
      setSessionManager { executor =>
        executor.evaluate("x = State.get")
        executor.evaluate("State.put (x + 1)")
        executor.exit()
      }
      eval(code) shouldEqual 11
    }

    "be able to list local variables in its scope" in {
      val code =
        """
          |main =
          |    x = 10
          |    y = 20
          |    z = x + y
          |
          |    Debug.breakpoint
          |""".stripMargin
      var scopeResult: Map[String, ObjectRepresentation] = Map()
      setSessionManager { executor =>
        scopeResult = executor.listBindings()
        executor.exit()
      }
      eval(code)
      scopeResult.view.mapValues(_.representation()).toMap shouldEqual Map(
        "this" -> "Test",
        "x"    -> "10",
        "y"    -> "20",
        "z"    -> "30"
      )
    }

    "be able to list bindings it has created" in {
      val code =
        """
          |main =
          |    x = 10
          |    y = 20
          |    z = x + y
          |
          |    Debug.breakpoint
          |""".stripMargin
      var scopeResult: Map[String, ObjectRepresentation] = Map()
      setSessionManager { executor =>
        executor.evaluate("x = y + z")
        scopeResult = executor.listBindings()
        executor.exit()
      }
      eval(code)
      scopeResult.view.mapValues(_.representation()).toMap shouldEqual Map(
        "this" -> "Test",
        "x"    -> "50",
        "y"    -> "20",
        "z"    -> "30"
      )
    }

    "allow to be nested" in {
      val code =
        """
          |main =
          |    10 * Debug.breakpoint + 1
          |""".stripMargin
      setSessionManager { topExecutor =>
        setSessionManager { nestedExecutor =>
          setSessionManager { doubleNestedExecutor =>
            doubleNestedExecutor.evaluate("4")
            doubleNestedExecutor.exit()
          }
          nestedExecutor.evaluate("10 * Debug.breakpoint + 3")
          nestedExecutor.exit()
        }
        topExecutor.evaluate("10 * Debug.breakpoint + 2")
        topExecutor.exit()
      }
      eval(code) shouldEqual 4321
    }

    "behave well when nested" in {
      val code =
        """
          |main =
          |    x = 1
          |    10 * Debug.breakpoint + x
          |""".stripMargin
      setSessionManager { topExecutor =>
        topExecutor.evaluate("x = 2")
        setSessionManager { nestedExecutor =>
          nestedExecutor.evaluate("x = 3")
          setSessionManager { doubleNestedExecutor =>
            doubleNestedExecutor.evaluate("x = 4")
            doubleNestedExecutor.evaluate("x")
            doubleNestedExecutor.exit()
          }
          nestedExecutor.evaluate("10 * Debug.breakpoint + x")
          nestedExecutor.exit()
        }
        topExecutor.evaluate("10 * Debug.breakpoint + x")
        topExecutor.exit()
      }
      eval(code) shouldEqual 4321
    }
  }
}
