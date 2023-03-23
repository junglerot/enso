package org.enso.languageserver.search

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import org.apache.commons.io.FileUtils
import org.enso.languageserver.boot.{ProfilingConfig, StartupConfig}
import org.enso.languageserver.capability.CapabilityProtocol.{
  AcquireCapability,
  CapabilityAcquired
}
import org.enso.languageserver.data._
import org.enso.languageserver.event.InitializedEvent
import org.enso.languageserver.filemanager._
import org.enso.languageserver.refactoring.ProjectNameChangedEvent
import org.enso.languageserver.search.SearchProtocol.SuggestionDatabaseEntry
import org.enso.languageserver.session.JsonSession
import org.enso.languageserver.session.SessionRouter.DeliverToJsonController
import org.enso.polyglot.data.{Tree, TypeGraph}
import org.enso.polyglot.runtime.Runtime.Api
import org.enso.polyglot.{ExportedSymbol, ModuleExports, Suggestion}
import org.enso.searcher.sql.{SqlDatabase, SqlSuggestionsRepo, SqlVersionsRepo}
import org.enso.searcher.{SuggestionsRepo, VersionsRepo}
import org.enso.testkit.RetrySpec
import org.enso.text.editing.model.Position
import org.enso.text.{ContentVersion, Sha3_224VersionCalculator}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.file.Files
import java.util.UUID

import scala.collection.immutable.ListSet
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}

class SuggestionsHandlerSpec
    extends TestKit(ActorSystem("TestSystem"))
    with ImplicitSender
    with AnyWordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with RetrySpec {

  import system.dispatcher

  val Timeout: FiniteDuration = 10.seconds

  def contentsVersion(text: String): ContentVersion =
    Sha3_224VersionCalculator.evalVersion(text)

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  "SuggestionsHandler" should {

    "subscribe to notification updates" /*taggedAs Retry*/ in withDb {
      (_, _, _, _, handler) =>
        val clientId = UUID.randomUUID()

        handler ! AcquireCapability(
          newJsonSession(clientId),
          CapabilityRegistration(ReceivesSuggestionsDatabaseUpdates())
        )
        expectMsg(CapabilityAcquired)
    }

    "receive runtime updates" taggedAs Retry in withDb {
      (_, repo, router, _, handler) =>
        val clientId = UUID.randomUUID()

        // acquire capability
        handler ! AcquireCapability(
          newJsonSession(clientId),
          CapabilityRegistration(ReceivesSuggestionsDatabaseUpdates())
        )
        expectMsg(CapabilityAcquired)

        // receive updates
        handler ! Api.SuggestionsDatabaseModuleUpdateNotification(
          "Foo.Main",
          contentsVersion(""),
          Vector(),
          Vector(),
          Tree.Root(Suggestions.all.toVector.map { suggestion =>
            Tree.Node(
              Api.SuggestionUpdate(suggestion, Api.SuggestionAction.Add()),
              Vector()
            )
          })
        )

        val updates = Suggestions.all.zipWithIndex.map {
          case (suggestion, ix) =>
            SearchProtocol.SuggestionsDatabaseUpdate.Add(ix + 1L, suggestion)
        }
        router.expectMsg(
          DeliverToJsonController(
            clientId,
            SearchProtocol.SuggestionsDatabaseUpdateNotification(
              Suggestions.all.size.toLong,
              updates
            )
          )
        )

        // check database entries exist
        val (_, records) = Await.result(repo.getAll, Timeout)
        records.map(
          _.suggestion.name
        ) should contain theSameElementsAs Suggestions.all.map(_.name)
    }

    "apply runtime updates in correct order" taggedAs Retry in withDb {
      (_, repo, router, _, handler) =>
        val clientId = UUID.randomUUID()

        // acquire capability
        handler ! AcquireCapability(
          newJsonSession(clientId),
          CapabilityRegistration(ReceivesSuggestionsDatabaseUpdates())
        )
        expectMsg(CapabilityAcquired)

        // receive updates
        handler ! Api.SuggestionsDatabaseModuleUpdateNotification(
          "Foo.Main",
          contentsVersion(""),
          Vector(),
          Vector(),
          Tree.Root(
            Suggestions.all.toVector
              .map { suggestion =>
                Tree.Node(
                  Api
                    .SuggestionUpdate(suggestion, Api.SuggestionAction.Add()),
                  Vector()
                )
              } ++
            Suggestions.all.map { suggestion =>
              Tree.Node(
                Api.SuggestionUpdate(
                  suggestion,
                  Api.SuggestionAction.Remove()
                ),
                Vector()
              )
            }
          )
        )

        val updatesAdd = Suggestions.all.zipWithIndex.map {
          case (suggestion, ix) =>
            SearchProtocol.SuggestionsDatabaseUpdate.Add(ix + 1L, suggestion)
        }
        val updatesRemove = Suggestions.all.zipWithIndex.map { case (_, ix) =>
          SearchProtocol.SuggestionsDatabaseUpdate.Remove(ix + 1L)
        }
        router.expectMsg(
          DeliverToJsonController(
            clientId,
            SearchProtocol.SuggestionsDatabaseUpdateNotification(
              Suggestions.all.size * 2L,
              updatesAdd ++ updatesRemove
            )
          )
        )

        // check that all database entries are removed
        val (_, all) = Await.result(repo.getAll, Timeout)
        all shouldEqual Seq()
    }

    "apply runtime updates tree" taggedAs Retry in withDb {
      (_, _, router, _, handler) =>
        val clientId = UUID.randomUUID()

        // acquire capability
        handler ! AcquireCapability(
          newJsonSession(clientId),
          CapabilityRegistration(ReceivesSuggestionsDatabaseUpdates())
        )
        expectMsg(CapabilityAcquired)

        val tree1 = Tree.Root(
          Vector(
            Tree.Node(
              Api.SuggestionUpdate(
                Suggestions.module,
                Api.SuggestionAction.Add()
              ),
              Vector()
            ),
            Tree.Node(
              Api.SuggestionUpdate(
                Suggestions.constructor,
                Api.SuggestionAction.Add()
              ),
              Vector()
            ),
            Tree.Node(
              Api.SuggestionUpdate(
                Suggestions.method,
                Api.SuggestionAction.Add()
              ),
              Vector(
                Tree.Node(
                  Api.SuggestionUpdate(
                    Suggestions.function,
                    Api.SuggestionAction.Add()
                  ),
                  Vector(
                    Tree.Node(
                      Api.SuggestionUpdate(
                        Suggestions.local,
                        Api.SuggestionAction.Add()
                      ),
                      Vector()
                    )
                  )
                )
              )
            )
          )
        )

        // add tree
        handler ! Api.SuggestionsDatabaseModuleUpdateNotification(
          "Foo.Main",
          contentsVersion(""),
          Vector(),
          Vector(),
          tree1
        )

        val updates1 = tree1.toVector.zipWithIndex.map { case (update, ix) =>
          SearchProtocol.SuggestionsDatabaseUpdate.Add(
            ix + 1L,
            update.suggestion
          )
        }
        router.expectMsg(
          DeliverToJsonController(
            clientId,
            SearchProtocol.SuggestionsDatabaseUpdateNotification(
              updates1.size.toLong,
              updates1
            )
          )
        )

        val tree2 = Tree.Root(
          Vector(
            Tree.Node(
              Api.SuggestionUpdate(
                Suggestions.constructor,
                Api.SuggestionAction.Modify(
                  arguments = Some(
                    Seq(
                      Api.SuggestionArgumentAction
                        .Modify(0, reprType = Some("A"))
                    )
                  )
                )
              ),
              Vector()
            ),
            Tree.Node(
              Api.SuggestionUpdate(
                Suggestions.method,
                Api.SuggestionAction.Modify()
              ),
              Vector(
                Tree.Node(
                  Api.SuggestionUpdate(
                    Suggestions.function,
                    Api.SuggestionAction.Modify(
                      scope = Some(Suggestions.local.scope)
                    )
                  ),
                  Vector(
                    Tree.Node(
                      Api.SuggestionUpdate(
                        Suggestions.local,
                        Api.SuggestionAction.Remove()
                      ),
                      Vector()
                    ),
                    Tree.Node(
                      Api.SuggestionUpdate(
                        Suggestions.local,
                        Api.SuggestionAction.Add()
                      ),
                      Vector()
                    )
                  )
                )
              )
            )
          )
        )
        // update tree
        handler ! Api.SuggestionsDatabaseModuleUpdateNotification(
          "Foo.Main",
          contentsVersion("1"),
          Vector(),
          Vector(),
          tree2
        )

        val updates2 = Seq(
          SearchProtocol.SuggestionsDatabaseUpdate.Modify(
            2L,
            arguments = Some(
              Seq(
                SearchProtocol.SuggestionArgumentUpdate
                  .Modify(
                    0,
                    reprType = Some(
                      SearchProtocol
                        .FieldUpdate(SearchProtocol.FieldAction.Set, Some("A"))
                    )
                  )
              )
            )
          ),
          SearchProtocol.SuggestionsDatabaseUpdate.Modify(
            4L,
            scope = Some(
              SearchProtocol.FieldUpdate(
                SearchProtocol.FieldAction.Set,
                Some(Suggestions.local.scope)
              )
            )
          ),
          SearchProtocol.SuggestionsDatabaseUpdate.Remove(5L),
          SearchProtocol.SuggestionsDatabaseUpdate.Add(6L, Suggestions.local)
        )
        router.expectMsg(
          DeliverToJsonController(
            clientId,
            SearchProtocol.SuggestionsDatabaseUpdateNotification(
              (updates1.size + updates2.size).toLong,
              updates2
            )
          )
        )
    }

    "apply update with an atom named as module" taggedAs Retry in withDb {
      (_, _, router, _, handler) =>
        val clientId = UUID.randomUUID()

        // acquire capability
        handler ! AcquireCapability(
          newJsonSession(clientId),
          CapabilityRegistration(ReceivesSuggestionsDatabaseUpdates())
        )
        expectMsg(CapabilityAcquired)

        val moduleName = "Test.Foo"
        val fooAtom = Suggestion.Constructor(
          externalId    = None,
          module        = moduleName,
          name          = "Foo",
          arguments     = Vector(),
          returnType    = moduleName,
          documentation = None
        )
        val module = Suggestion.Module(
          module        = moduleName,
          documentation = None
        )

        val tree = Tree.Root(
          Vector(
            Tree.Node(
              Api.SuggestionUpdate(
                module,
                Api.SuggestionAction.Add()
              ),
              Vector()
            ),
            Tree.Node(
              Api.SuggestionUpdate(
                fooAtom,
                Api.SuggestionAction.Add()
              ),
              Vector()
            )
          )
        )

        // add tree
        handler ! Api.SuggestionsDatabaseModuleUpdateNotification(
          "Foo.Main",
          contentsVersion(""),
          Vector(),
          Vector(),
          tree
        )

        val updates = tree.toVector.zipWithIndex.map { case (update, ix) =>
          SearchProtocol.SuggestionsDatabaseUpdate.Add(
            ix + 1L,
            update.suggestion
          )
        }
        router.expectMsg(
          DeliverToJsonController(
            clientId,
            SearchProtocol.SuggestionsDatabaseUpdateNotification(
              updates.size.toLong,
              updates
            )
          )
        )
    }

    "apply runtime actions" taggedAs Retry in withDb {
      (_, repo, router, _, handler) =>
        val clientId = UUID.randomUUID()

        // acquire capability
        handler ! AcquireCapability(
          newJsonSession(clientId),
          CapabilityRegistration(ReceivesSuggestionsDatabaseUpdates())
        )
        expectMsg(CapabilityAcquired)

        val suggestions = Seq(
          Suggestions.constructor,
          Suggestions.method,
          Suggestions.function,
          Suggestions.local
        )

        val tree1 = Tree.Root(
          Vector(
            Tree.Node(
              Api.SuggestionUpdate(
                Suggestions.constructor,
                Api.SuggestionAction.Add()
              ),
              Vector()
            ),
            Tree.Node(
              Api.SuggestionUpdate(
                Suggestions.method,
                Api.SuggestionAction.Add()
              ),
              Vector(
                Tree.Node(
                  Api.SuggestionUpdate(
                    Suggestions.function,
                    Api.SuggestionAction.Add()
                  ),
                  Vector(
                    Tree.Node(
                      Api.SuggestionUpdate(
                        Suggestions.local,
                        Api.SuggestionAction.Add()
                      ),
                      Vector()
                    )
                  )
                )
              )
            )
          )
        )

        // add tree
        handler ! Api.SuggestionsDatabaseModuleUpdateNotification(
          "Foo.Main",
          contentsVersion(""),
          Vector(),
          Vector(),
          tree1
        )

        val updates1 = tree1.toVector.zipWithIndex.map { case (update, ix) =>
          SearchProtocol.SuggestionsDatabaseUpdate.Add(
            ix + 1L,
            update.suggestion
          )
        }
        router.expectMsg(
          DeliverToJsonController(
            clientId,
            SearchProtocol.SuggestionsDatabaseUpdateNotification(
              updates1.size.toLong,
              updates1
            )
          )
        )

        // clean module
        handler ! Api.SuggestionsDatabaseModuleUpdateNotification(
          "Foo.Main",
          contentsVersion("1"),
          Vector(
            Api.SuggestionsDatabaseAction.Clean(Suggestions.constructor.module)
          ),
          Vector(),
          Tree.Root(Vector())
        )

        val updates2 = suggestions.zipWithIndex.map { case (_, ix) =>
          SearchProtocol.SuggestionsDatabaseUpdate.Remove(ix + 1L)
        }
        router.expectMsg(
          DeliverToJsonController(
            clientId,
            SearchProtocol.SuggestionsDatabaseUpdateNotification(
              updates1.size.toLong,
              updates2
            )
          )
        )

        // check that all database entries are removed
        val (_, all) = Await.result(repo.getAll, Timeout)
        all shouldEqual Seq()
    }

    "apply export updates" taggedAs Retry in withDb {
      (_, _, router, _, handler) =>
        val clientId = UUID.randomUUID()

        // acquire capability
        handler ! AcquireCapability(
          newJsonSession(clientId),
          CapabilityRegistration(ReceivesSuggestionsDatabaseUpdates())
        )
        expectMsg(CapabilityAcquired)

        val tree1 = Tree.Root(
          Vector(
            Tree.Node(
              Api.SuggestionUpdate(
                Suggestions.module,
                Api.SuggestionAction.Add()
              ),
              Vector()
            ),
            Tree.Node(
              Api.SuggestionUpdate(
                Suggestions.constructor,
                Api.SuggestionAction.Add()
              ),
              Vector()
            ),
            Tree.Node(
              Api.SuggestionUpdate(
                Suggestions.method,
                Api.SuggestionAction.Add()
              ),
              Vector(
                Tree.Node(
                  Api.SuggestionUpdate(
                    Suggestions.function,
                    Api.SuggestionAction.Add()
                  ),
                  Vector(
                    Tree.Node(
                      Api.SuggestionUpdate(
                        Suggestions.local,
                        Api.SuggestionAction.Add()
                      ),
                      Vector()
                    )
                  )
                )
              )
            )
          )
        )

        // add tree
        handler ! Api.SuggestionsDatabaseModuleUpdateNotification(
          "Foo.Main",
          contentsVersion(""),
          Vector(),
          Vector(),
          tree1
        )

        val updates1 = tree1.toVector.zipWithIndex.map { case (update, ix) =>
          SearchProtocol.SuggestionsDatabaseUpdate.Add(
            ix + 1L,
            update.suggestion
          )
        }
        router.expectMsg(
          DeliverToJsonController(
            clientId,
            SearchProtocol.SuggestionsDatabaseUpdateNotification(
              updates1.size.toLong,
              updates1
            )
          )
        )

        val exportUpdateAdd =
          Api.ExportsUpdate(
            ModuleExports(
              "Foo.Bar",
              ListSet(
                ExportedSymbol.Module(
                  Suggestions.module.module
                ),
                ExportedSymbol.Atom(
                  Suggestions.constructor.module,
                  Suggestions.constructor.name
                ),
                ExportedSymbol.Method(
                  Suggestions.method.module,
                  Suggestions.method.name
                )
              )
            ),
            Api.ExportsAction.Add()
          )
        // apply updates1
        handler ! Api.SuggestionsDatabaseModuleUpdateNotification(
          "Foo.Main",
          contentsVersion("1"),
          Vector(),
          Vector(exportUpdateAdd),
          Tree.Root(Vector())
        )

        val updates2 = Seq(1L, 2L, 3L).map { id =>
          SearchProtocol.SuggestionsDatabaseUpdate.Modify(
            id,
            reexport = Some(fieldUpdate(exportUpdateAdd.exports.module))
          )
        }
        router.expectMsg(
          DeliverToJsonController(
            clientId,
            SearchProtocol.SuggestionsDatabaseUpdateNotification(
              updates1.size.toLong + 1,
              updates2
            )
          )
        )

        val exportUpdateRemove =
          Api.ExportsUpdate(
            ModuleExports(
              "Foo.Bar",
              ListSet(
                ExportedSymbol.Module(
                  Suggestions.module.module
                ),
                ExportedSymbol.Atom(
                  Suggestions.constructor.module,
                  Suggestions.constructor.name
                ),
                ExportedSymbol.Method(
                  Suggestions.method.module,
                  Suggestions.method.name
                )
              )
            ),
            Api.ExportsAction.Remove()
          )
        // apply updates2
        handler ! Api.SuggestionsDatabaseModuleUpdateNotification(
          "Foo.Main",
          contentsVersion("2"),
          Vector(),
          Vector(exportUpdateRemove),
          Tree.Root(Vector())
        )

        val updates3 = Seq(1L, 2L, 3L).map { id =>
          SearchProtocol.SuggestionsDatabaseUpdate.Modify(
            id,
            reexport = Some(fieldRemove)
          )
        }
        router.expectMsg(
          DeliverToJsonController(
            clientId,
            SearchProtocol.SuggestionsDatabaseUpdateNotification(
              updates1.size.toLong + 2,
              updates3
            )
          )
        )
    }

    "get initial suggestions database version" taggedAs Retry in withDb {
      (_, _, _, _, handler) =>
        handler ! SearchProtocol.GetSuggestionsDatabaseVersion

        expectMsg(SearchProtocol.GetSuggestionsDatabaseVersionResult(0))
    }

    "get suggestions database version" taggedAs Retry in withDb {
      (_, repo, _, _, handler) =>
        Await.ready(repo.insert(Suggestions.constructor), Timeout)
        handler ! SearchProtocol.GetSuggestionsDatabaseVersion

        expectMsg(SearchProtocol.GetSuggestionsDatabaseVersionResult(1))
    }

    "get initial suggestions database" taggedAs Retry in withDb {
      (_, _, _, _, handler) =>
        handler ! SearchProtocol.GetSuggestionsDatabase

        expectMsg(SearchProtocol.GetSuggestionsDatabaseResult(0, Seq()))
    }

    "get suggestions database" taggedAs Retry in withDb {
      (_, repo, _, _, handler) =>
        Await.ready(repo.insert(Suggestions.constructor), Timeout)
        handler ! SearchProtocol.GetSuggestionsDatabase

        expectMsg(
          SearchProtocol.GetSuggestionsDatabaseResult(
            1,
            Seq(SuggestionDatabaseEntry(1L, Suggestions.constructor))
          )
        )
    }

    "invalidate suggestions database" taggedAs Retry in withDb {
      (_, repo, _, connector, handler) =>
        Await.ready(repo.insert(Suggestions.constructor), Timeout)
        handler ! SearchProtocol.InvalidateSuggestionsDatabase

        connector.expectMsgClass(classOf[Api.Request]) match {
          case Api.Request(_, Api.InvalidateModulesIndexRequest()) =>
          case Api.Request(_, msg) =>
            fail(s"Runtime connector receive unexpected message: $msg")
        }
        connector.reply(Api.Response(Api.InvalidateModulesIndexResponse()))

        expectMsg(SearchProtocol.InvalidateSuggestionsDatabaseResult)
    }

    "rename module when renaming project" taggedAs Retry in withDb {
      (_, repo, router, _, handler) =>
        Await.ready(repo.insert(Suggestions.constructor), Timeout)
        val clientId      = UUID.randomUUID()
        val newModuleName = "Vest"

        // acquire capability
        handler ! AcquireCapability(
          newJsonSession(clientId),
          CapabilityRegistration(ReceivesSuggestionsDatabaseUpdates())
        )
        expectMsg(CapabilityAcquired)

        handler ! ProjectNameChangedEvent("Test", newModuleName)

        router.expectMsg(
          DeliverToJsonController(
            clientId,
            SearchProtocol.SuggestionsDatabaseUpdateNotification(
              2,
              Seq(
                SearchProtocol.SuggestionsDatabaseUpdate.Modify(
                  id     = 1,
                  module = Some(fieldUpdate("local.Vest.Main"))
                )
              )
            )
          )
        )
    }

    "rename types when renaming project" taggedAs Retry in withDb {
      (_, repo, router, _, handler) =>
        val method = Suggestions.method.copy(
          selfType = "local.Test.MyType",
          arguments = Suggestions.method.arguments.map(arg =>
            arg.copy(reprType = "local.Test.MyType")
          )
        )
        Await.ready(repo.insert(method), Timeout)
        val clientId      = UUID.randomUUID()
        val newModuleName = "Vest"

        // acquire capability
        handler ! AcquireCapability(
          newJsonSession(clientId),
          CapabilityRegistration(ReceivesSuggestionsDatabaseUpdates())
        )
        expectMsg(CapabilityAcquired)

        handler ! ProjectNameChangedEvent("Test", newModuleName)

        router.expectMsg(
          DeliverToJsonController(
            clientId,
            SearchProtocol.SuggestionsDatabaseUpdateNotification(
              2,
              Seq(
                SearchProtocol.SuggestionsDatabaseUpdate.Modify(
                  id     = 1,
                  module = Some(fieldUpdate("local.Vest.Main"))
                ),
                SearchProtocol.SuggestionsDatabaseUpdate.Modify(
                  id       = 1,
                  selfType = Some(fieldUpdate("local.Vest.MyType"))
                ),
                SearchProtocol.SuggestionsDatabaseUpdate.Modify(
                  id = 1,
                  arguments = Some(
                    method.arguments.zipWithIndex.map { case (_, index) =>
                      SearchProtocol.SuggestionArgumentUpdate.Modify(
                        index    = index,
                        reprType = Some(fieldUpdate("local.Vest.MyType"))
                      )
                    }
                  )
                )
              )
            )
          )
        )
    }

    "search entries by empty search query" taggedAs Retry in withDb {
      (config, repo, _, _, handler) =>
        val (_, inserted) =
          Await.result(repo.insertAll(Suggestions.all), Timeout)
        handler ! SearchProtocol.Completion(
          file       = mkModulePath(config, "Foo", "Main.enso"),
          position   = Position(0, 0),
          selfType   = None,
          returnType = None,
          tags       = None,
          isStatic   = None
        )

        expectMsg(
          SearchProtocol.CompletionResult(
            Suggestions.all.length.toLong,
            Seq(
              inserted(0).get,
              inserted(1).get,
              inserted(2).get,
              inserted(6).get,
              inserted(7).get,
              inserted(8).get,
              inserted(3).get
            )
          )
        )
    }

    "search entries by self type" taggedAs Retry in withDb {
      (config, repo, _, _, handler) =>
        val (_, Seq(_, _, _, methodId, _, _, methodOnAnyId, _, _)) =
          Await.result(repo.insertAll(Suggestions.all), Timeout)
        handler ! SearchProtocol.Completion(
          file       = mkModulePath(config, "Main.enso"),
          position   = Position(0, 0),
          selfType   = Some("MyType"),
          returnType = None,
          tags       = None,
          isStatic   = None
        )

        expectMsg(
          SearchProtocol.CompletionResult(
            Suggestions.all.length.toLong,
            Seq(methodId, methodOnAnyId).flatten
          )
        )
    }

    "search entries based on supertypes of self" taggedAs Retry in withDb {
      (config, repo, _, _, handler) =>
        val (
          _,
          Seq(_, _, _, _, _, _, anyMethodId, numberMethodId, integerMethodId)
        ) =
          Await.result(repo.insertAll(Suggestions.all), Timeout)

        handler ! SearchProtocol.Completion(
          file       = mkModulePath(config, "Main.enso"),
          position   = Position(0, 0),
          selfType   = Some("Integer"),
          returnType = None,
          tags       = None,
          isStatic   = None
        )

        expectMsg(
          SearchProtocol.CompletionResult(
            Suggestions.all.length.toLong,
            Seq(integerMethodId, numberMethodId, anyMethodId).flatten
          )
        )
    }

    "search entries for any" taggedAs Retry in withDb {
      (config, repo, _, _, handler) =>
        val (_, Seq(_, _, _, _, _, _, anyMethodId, _, _)) =
          Await.result(repo.insertAll(Suggestions.all), Timeout)

        handler ! SearchProtocol.Completion(
          file       = mkModulePath(config, "Main.enso"),
          position   = Position(0, 0),
          selfType   = Some("Any"),
          returnType = None,
          tags       = None,
          isStatic   = None
        )

        expectMsg(
          SearchProtocol.CompletionResult(
            Suggestions.all.length.toLong,
            Seq(anyMethodId).flatten
          )
        )
    }

    "search entries by return type" taggedAs Retry in withDb {
      (config, repo, _, _, handler) =>
        val (_, Seq(_, _, _, _, functionId, _, _, _, _)) =
          Await.result(repo.insertAll(Suggestions.all), Timeout)
        handler ! SearchProtocol.Completion(
          file       = mkModulePath(config, "Main.enso"),
          position   = Position(1, 10),
          selfType   = None,
          returnType = Some("IO"),
          tags       = None,
          isStatic   = None
        )

        expectMsg(
          SearchProtocol.CompletionResult(
            Suggestions.all.length.toLong,
            Seq(functionId).flatten
          )
        )
    }

    "search entries by tags" taggedAs Retry in withDb {
      (config, repo, _, _, handler) =>
        val (_, Seq(_, _, _, _, _, localId, _, _, _)) =
          Await.result(repo.insertAll(Suggestions.all), Timeout)
        handler ! SearchProtocol.Completion(
          file       = mkModulePath(config, "Main.enso"),
          position   = Position(42, 0),
          selfType   = None,
          returnType = None,
          tags       = Some(Seq(SearchProtocol.SuggestionKind.Local)),
          isStatic   = None
        )

        expectMsg(
          SearchProtocol.CompletionResult(
            Suggestions.all.length.toLong,
            Seq(localId).flatten
          )
        )
    }

    "search entries by static attribute" taggedAs Retry in withDb {
      (config, repo, _, _, handler) =>
        val all = Seq(
          Suggestions.module,
          Suggestions.tpe,
          Suggestions.constructor,
          Suggestions.method.copy(isStatic = true),
          Suggestions.function,
          Suggestions.local,
          Suggestions.methodOnAny,
          Suggestions.methodOnNumber,
          Suggestions.methodOnInteger
        )

        val (_, Seq(_, _, _, methodId, _, _, _, _, _)) =
          Await.result(repo.insertAll(all), Timeout)
        handler ! SearchProtocol.Completion(
          file       = mkModulePath(config, "Main.enso"),
          position   = Position(42, 0),
          selfType   = None,
          returnType = None,
          tags       = None,
          isStatic   = Some(true)
        )

        expectMsg(
          SearchProtocol.CompletionResult(
            all.length.toLong,
            Seq(methodId).flatten
          )
        )
    }
  }

  private def fieldUpdate(value: String): SearchProtocol.FieldUpdate[String] =
    SearchProtocol.FieldUpdate(SearchProtocol.FieldAction.Set, Some(value))

  private def fieldRemove[A]: SearchProtocol.FieldUpdate[A] =
    SearchProtocol.FieldUpdate(SearchProtocol.FieldAction.Remove, None)

  def newSuggestionsHandler(
    config: Config,
    sessionRouter: TestProbe,
    runtimeConnector: TestProbe,
    suggestionsRepo: SuggestionsRepo[Future],
    versionsRepo: VersionsRepo[Future]
  ): ActorRef = {
    val contentRootManagerActor =
      system.actorOf(ContentRootManagerActor.props(config))
    val contentRootManagerWrapper: ContentRootManager =
      new ContentRootManagerWrapper(config, contentRootManagerActor)
    system.actorOf(
      SuggestionsHandler.props(
        config,
        contentRootManagerWrapper,
        suggestionsRepo,
        versionsRepo,
        sessionRouter.ref,
        runtimeConnector.ref
      )
    )
  }

  def newInitializedSuggestionsHandler(
    config: Config,
    sessionRouter: TestProbe,
    runtimeConnector: TestProbe,
    suggestionsRepo: SuggestionsRepo[Future],
    versionsRepo: VersionsRepo[Future]
  ): ActorRef = {
    val handler =
      newSuggestionsHandler(
        config,
        sessionRouter,
        runtimeConnector,
        suggestionsRepo,
        versionsRepo
      )

    handler ! SuggestionsHandler.ProjectNameUpdated("Test")
    handler ! InitializedEvent.TruffleContextInitialized
    // GetTypeGraphRequest
    runtimeConnector.receiveN(1)
    handler ! Api.Response(
      UUID.randomUUID(),
      Api.GetTypeGraphResponse(buildTestTypeGraph)
    )

    val suggestionsInit = suggestionsRepo.init
    val versionsInit    = versionsRepo.init
    suggestionsInit.onComplete {
      case Success(()) =>
        system.eventStream.publish(InitializedEvent.SuggestionsRepoInitialized)
      case Failure(ex) =>
        system.log.error(ex, "Failed to initialize Suggestions repo")
    }
    versionsInit.onComplete {
      case Success(()) =>
        system.eventStream.publish(InitializedEvent.VersionsRepoInitialized)
      case Failure(ex) =>
        system.log.error(ex, "Failed to initialize FileVersions repo")
    }

    Await.ready(suggestionsInit, Timeout)
    Await.ready(versionsInit, Timeout)
    handler
  }

  def buildTestTypeGraph: TypeGraph = {
    val graph = TypeGraph("Any")
    graph.insert("Number", "Any")
    graph.insert("Integer", "Number")

    graph
  }

  def newConfig(root: ContentRootWithFile): Config = {
    Config(
      root,
      FileManagerConfig(timeout = 3.seconds),
      VcsManagerConfig(),
      PathWatcherConfig(),
      ExecutionContextConfig(requestTimeout = 3.seconds),
      ProjectDirectoriesConfig.initialize(root.file),
      ProfilingConfig(),
      StartupConfig()
    )
  }

  def mkModulePath(config: Config, segments: String*): Path = {
    val rootId = config.projectContentRoot.id
    Path(rootId, "src" +: segments.toVector)
  }

  def newJsonSession(clientId: UUID): JsonSession =
    JsonSession(clientId, TestProbe().ref)

  def withDbs(
    test: (Config, SuggestionsRepo[Future], VersionsRepo[Future]) => Any
  ): Unit = {
    val testContentRoot = Files.createTempDirectory(null).toRealPath()
    sys.addShutdownHook(FileUtils.deleteQuietly(testContentRoot.toFile))
    val config = newConfig(
      ContentRootWithFile(
        ContentRoot.Project(UUID.randomUUID()),
        testContentRoot.toFile
      )
    )
    val sqlDatabase     = SqlDatabase(config.directories.suggestionsDatabaseFile)
    val suggestionsRepo = new SqlSuggestionsRepo(sqlDatabase)
    val versionsRepo    = new SqlVersionsRepo(sqlDatabase)

    val suggestionsInit = suggestionsRepo.init
    val versionsInit    = versionsRepo.init
    suggestionsInit.onComplete {
      case Success(()) =>
        system.eventStream.publish(InitializedEvent.SuggestionsRepoInitialized)
      case Failure(ex) =>
        system.log.error(ex, "Failed to initialize Suggestions repo")
    }
    versionsInit.onComplete {
      case Success(()) =>
        system.eventStream.publish(InitializedEvent.VersionsRepoInitialized)
      case Failure(ex) =>
        system.log.error(ex, "Failed to initialize FileVersions repo")
    }

    Await.ready(suggestionsInit, Timeout)
    Await.ready(versionsInit, Timeout)

    try test(config, suggestionsRepo, versionsRepo)
    finally {
      versionsRepo.close()
      suggestionsRepo.close()
    }
  }

  def withDb(
    test: (
      Config,
      SuggestionsRepo[Future],
      TestProbe,
      TestProbe,
      ActorRef
    ) => Any
  ): Unit = {
    val testContentRoot = Files.createTempDirectory(null).toRealPath()
    sys.addShutdownHook(FileUtils.deleteQuietly(testContentRoot.toFile))
    val config = newConfig(
      ContentRootWithFile(
        ContentRoot.Project(UUID.randomUUID()),
        testContentRoot.toFile
      )
    )
    val router      = TestProbe("session-router")
    val connector   = TestProbe("runtime-connector")
    val sqlDatabase = SqlDatabase.inmem("testdb")
    sqlDatabase.open()
    val suggestionsRepo = new SqlSuggestionsRepo(sqlDatabase)
    val versionsRepo    = new SqlVersionsRepo(sqlDatabase)
    val handler = newInitializedSuggestionsHandler(
      config,
      router,
      connector,
      suggestionsRepo,
      versionsRepo
    )

    try test(config, suggestionsRepo, router, connector, handler)
    finally {
      system.stop(handler)
      sqlDatabase.close()
    }
  }

  object TestSuggestion {

    val atom: Suggestion.Constructor =
      Suggestion.Constructor(
        externalId = None,
        module     = "Test.Pair",
        name       = "Pair",
        arguments = Seq(
          Suggestion.Argument("a", "Any", false, false, None),
          Suggestion.Argument("b", "Any", false, false, None)
        ),
        returnType    = "Pair",
        documentation = Some("Awesome")
      )

    val method: Suggestion.Method =
      Suggestion.Method(
        externalId    = Some(UUID.randomUUID()),
        module        = "Test.Main",
        name          = "main",
        arguments     = Seq(),
        selfType      = "Test.Main",
        returnType    = "IO",
        isStatic      = true,
        documentation = None
      )
  }

}
