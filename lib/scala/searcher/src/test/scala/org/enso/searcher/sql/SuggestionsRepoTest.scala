package org.enso.searcher.sql

import java.nio.file.{Files, Path}
import java.util.UUID

import org.enso.polyglot.{ExportedSymbol, ModuleExports, Suggestion}
import org.enso.polyglot.runtime.Runtime.Api
import org.enso.searcher.data.QueryResult
import org.enso.testkit.RetrySpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class SuggestionsRepoTest extends AnyWordSpec with Matchers with RetrySpec {

  val Timeout: FiniteDuration = 20.seconds

  val tmpdir: Path = {
    val tmp = Files.createTempDirectory("suggestions-repo-test")
    sys.addShutdownHook {
      Files.list(tmp).forEach { path =>
        path.toFile.delete()
      }
      tmp.toFile.delete()
    }
    tmp
  }

  def withRepo(test: SqlSuggestionsRepo => Any): Any = {
    val tmpdb = Files.createTempFile(tmpdir, "suggestions-repo", ".db")
    val repo  = new SqlSuggestionsRepo(SqlDatabase(tmpdb.toFile))
    Await.ready(repo.init, Timeout)
    try test(repo)
    finally {
      Await.ready(repo.clean, Timeout)
      repo.close()
    }
  }

  "SuggestionsRepo" should {

    "init idempotent" taggedAs Retry in withRepo { repo =>
      Await.result(repo.init, Timeout)
    }

    "check the schema version when init" taggedAs Retry in withRepo { repo =>
      val wrongSchemaVersion = Long.MinValue
      val action =
        for {
          version <- repo.setSchemaVersion(wrongSchemaVersion)
          _       <- repo.init
        } yield version

      val thrown =
        the[InvalidSchemaVersion] thrownBy Await.result(action, Timeout)
      thrown.version shouldEqual wrongSchemaVersion
    }

    "get all suggestions" taggedAs Retry in withRepo { repo =>
      val action =
        for {
          _ <- repo.insertAll(
            Seq(
              suggestion.module,
              suggestion.tpe,
              suggestion.constructor,
              suggestion.method,
              suggestion.instanceMethod,
              suggestion.conversion,
              suggestion.function,
              suggestion.local
            )
          )
          all <- repo.getAll
        } yield all._2

      val suggestions = Await.result(action, Timeout).map(_.suggestion)
      suggestions should contain theSameElementsAs Seq(
        suggestion.module,
        suggestion.tpe,
        suggestion.constructor,
        suggestion.method,
        suggestion.instanceMethod,
        suggestion.conversion,
        suggestion.function,
        suggestion.local
      )
    }

    "get suggestions by method call info" taggedAs Retry in withRepo { repo =>
      val action = for {
        (_, ids) <- repo.insertAll(
          Seq(
            suggestion.module,
            suggestion.tpe,
            suggestion.constructor,
            suggestion.method,
            suggestion.instanceMethod,
            suggestion.conversion,
            suggestion.function,
            suggestion.local
          )
        )
        results <- repo.getAllMethods(
          Seq(
            ("local.Test.Main", "local.Test.Main", "main"),
            ("local.Test.Main", "local.Test.Main", "foo")
          )
        )
      } yield (ids, results)

      val (ids, results) = Await.result(action, Timeout)
      results should contain theSameElementsInOrderAs Seq(ids(3), None)
    }

    "get suggestions by empty method call info" taggedAs Retry in withRepo {
      repo =>
        val action = for {
          _ <- repo.insertAll(
            Seq(
              suggestion.module,
              suggestion.tpe,
              suggestion.constructor,
              suggestion.method,
              suggestion.conversion,
              suggestion.function,
              suggestion.local
            )
          )
          results <- repo.getAllMethods(Seq())
        } yield results

        val results = Await.result(action, Timeout)
        results.isEmpty shouldEqual true
    }

    "get all module names" taggedAs Retry in withRepo { repo =>
      val action = for {
        _ <- repo.insertAll(
          Seq(
            suggestion.module,
            suggestion.tpe,
            suggestion.constructor,
            suggestion.method,
            suggestion.conversion,
            suggestion.function,
            suggestion.local
          )
        )
        results <- repo.getAllModules
      } yield results

      val results = Await.result(action, Timeout)
      results shouldEqual Seq(suggestion.constructor.module)
    }

    "fail to insert duplicate suggestion" taggedAs Retry in withRepo { repo =>
      val action =
        for {
          (_, ids) <- repo.insertAll(
            Seq(
              suggestion.module,
              suggestion.module,
              suggestion.tpe,
              suggestion.tpe,
              suggestion.constructor,
              suggestion.constructor,
              suggestion.method,
              suggestion.method,
              suggestion.conversion,
              suggestion.conversion,
              suggestion.function,
              suggestion.function,
              suggestion.local,
              suggestion.local
            )
          )
          all <- repo.getAll
        } yield (ids, all._2)

      val (ids, all) = Await.result(action, Timeout)
      ids(0) shouldBe a[Some[_]]
      ids(1) shouldBe a[None.type]
      ids(2) shouldBe a[Some[_]]
      ids(3) shouldBe a[None.type]
      ids(4) shouldBe a[Some[_]]
      ids(5) shouldBe a[None.type]
      ids(6) shouldBe a[Some[_]]
      ids(7) shouldBe a[None.type]
      ids(8) shouldBe a[Some[_]]
      ids(9) shouldBe a[None.type]
      all.map(_.suggestion) should contain theSameElementsAs Seq(
        suggestion.module,
        suggestion.tpe,
        suggestion.constructor,
        suggestion.method,
        suggestion.conversion,
        suggestion.function,
        suggestion.local
      )
    }

    "fail to insertAll duplicate suggestion" taggedAs Retry in withRepo {
      repo =>
        val action =
          for {
            (v1, ids) <- repo.insertAll(Seq(suggestion.local, suggestion.local))
            (v2, all) <- repo.getAll
          } yield (v1, v2, ids, all)

        val (v1, v2, ids, all) = Await.result(action, Timeout)
        v1 shouldEqual v2
        ids.flatten.length shouldEqual 1
        all.map(_.suggestion) should contain theSameElementsAs Seq(
          suggestion.local
        )
    }

    "select suggestion by id" taggedAs Retry in withRepo { repo =>
      val action =
        for {
          Some(id) <- repo.insert(suggestion.constructor)
          res      <- repo.select(id)
        } yield res

      Await.result(action, Timeout) shouldEqual Some(suggestion.constructor)
    }

    "remove suggestion" taggedAs Retry in withRepo { repo =>
      val action =
        for {
          id1 <- repo.insert(suggestion.constructor)
          id2 <- repo.remove(suggestion.constructor)
        } yield (id1, id2)

      val (id1, id2) = Await.result(action, Timeout)
      id1 shouldEqual id2
    }

    "remove suggestions by module names" taggedAs Retry in withRepo { repo =>
      val action = for {
        (_, idsIns) <- repo.insertAll(
          Seq(
            suggestion.module,
            suggestion.tpe,
            suggestion.constructor,
            suggestion.method,
            suggestion.conversion,
            suggestion.function,
            suggestion.local
          )
        )
        (_, idsRem) <- repo.removeModules(Seq(suggestion.constructor.module))
      } yield (idsIns.flatten, idsRem)

      val (inserted, removed) = Await.result(action, Timeout)
      inserted should contain theSameElementsAs removed
    }

    "remove suggestions by empty module names" taggedAs Retry in withRepo {
      repo =>
        val action = for {
          (v1, _) <- repo.insertAll(
            Seq(
              suggestion.module,
              suggestion.tpe,
              suggestion.constructor,
              suggestion.method,
              suggestion.conversion,
              suggestion.function,
              suggestion.local
            )
          )
          (v2, removed) <- repo.removeModules(Seq())
        } yield (v1, v2, removed)

        val (v1, v2, removed) = Await.result(action, Timeout)
        v1 shouldEqual v2
        removed shouldEqual Seq()
    }

    "remove all suggestions" taggedAs Retry in withRepo { repo =>
      val action = for {
        (_, Seq(_, _, id1, _, _, _, id4)) <- repo.insertAll(
          Seq(
            suggestion.module,
            suggestion.tpe,
            suggestion.constructor,
            suggestion.method,
            suggestion.conversion,
            suggestion.function,
            suggestion.local
          )
        )
        (_, ids) <- repo.removeAll(
          Seq(suggestion.constructor, suggestion.local)
        )
      } yield (Seq(id1, id4), ids)

      val (inserted, removed) = Await.result(action, Timeout)
      inserted should contain theSameElementsAs removed
    }

    "get version" taggedAs Retry in withRepo { repo =>
      val action = repo.currentVersion

      Await.result(action, Timeout) shouldEqual 0L
    }

    "change version after insert" taggedAs Retry in withRepo { repo =>
      val action = for {
        v1 <- repo.currentVersion
        _  <- repo.insert(suggestion.constructor)
        v2 <- repo.currentVersion
      } yield (v1, v2)

      val (v1, v2) = Await.result(action, Timeout)
      v1 should not equal v2
    }

    "not change version after failed insert" taggedAs Retry in withRepo {
      repo =>
        val action = for {
          v1 <- repo.currentVersion
          _  <- repo.insert(suggestion.constructor)
          v2 <- repo.currentVersion
          _  <- repo.insert(suggestion.constructor)
          v3 <- repo.currentVersion
        } yield (v1, v2, v3)

        val (v1, v2, v3) = Await.result(action, Timeout)
        v1 should not equal v2
        v2 shouldEqual v3
    }

    "change version after remove" taggedAs Retry in withRepo { repo =>
      val action = for {
        v1 <- repo.currentVersion
        _  <- repo.insert(suggestion.local)
        v2 <- repo.currentVersion
        _  <- repo.remove(suggestion.local)
        v3 <- repo.currentVersion
      } yield (v1, v2, v3)

      val (v1, v2, v3) = Await.result(action, Timeout)
      v1 should not equal v2
      v2 should not equal v3
    }

    "not change version after failed remove" taggedAs Retry in withRepo {
      repo =>
        val action = for {
          v1 <- repo.currentVersion
          _  <- repo.insert(suggestion.local)
          v2 <- repo.currentVersion
          _  <- repo.remove(suggestion.local)
          v3 <- repo.currentVersion
          _  <- repo.remove(suggestion.local)
          v4 <- repo.currentVersion
        } yield (v1, v2, v3, v4)

        val (v1, v2, v3, v4) = Await.result(action, Timeout)
        v1 should not equal v2
        v2 should not equal v3
        v3 shouldEqual v4
    }

    "change version after remove by module name" taggedAs Retry in withRepo {
      repo =>
        val action = for {
          v1      <- repo.currentVersion
          _       <- repo.insert(suggestion.local)
          v2      <- repo.currentVersion
          (v3, _) <- repo.removeModules(Seq(suggestion.local.module))
        } yield (v1, v2, v3)

        val (v1, v2, v3) = Await.result(action, Timeout)
        v1 should not equal v2
        v2 should not equal v3
    }

    "not change version after failed remove by module name" taggedAs Retry in withRepo {
      repo =>
        val action = for {
          v1      <- repo.currentVersion
          _       <- repo.insert(suggestion.local)
          v2      <- repo.currentVersion
          _       <- repo.removeModules(Seq(suggestion.local.module))
          v3      <- repo.currentVersion
          (v4, _) <- repo.removeModules(Seq(suggestion.local.module))
        } yield (v1, v2, v3, v4)

        val (v1, v2, v3, v4) = Await.result(action, Timeout)
        v1 should not equal v2
        v2 should not equal v3
        v3 shouldEqual v4
    }

    "change version after remove all suggestions" taggedAs Retry in withRepo {
      repo =>
        val action = for {
          v1      <- repo.currentVersion
          _       <- repo.insert(suggestion.local)
          v2      <- repo.currentVersion
          (v3, _) <- repo.removeAll(Seq(suggestion.local))
        } yield (v1, v2, v3)

        val (v1, v2, v3) = Await.result(action, Timeout)
        v1 should not equal v2
        v2 should not equal v3
    }

    "not change version after failed remove all suggestions" taggedAs Retry in withRepo {
      repo =>
        val action = for {
          v1      <- repo.currentVersion
          _       <- repo.insert(suggestion.local)
          v2      <- repo.currentVersion
          (v3, _) <- repo.removeAll(Seq(suggestion.local))
          (v4, _) <- repo.removeAll(Seq(suggestion.local))
        } yield (v1, v2, v3, v4)

        val (v1, v2, v3, v4) = Await.result(action, Timeout)
        v1 should not equal v2
        v2 should not equal v3
        v3 shouldEqual v4
    }

    "update suggestion by external id" taggedAs Retry in withRepo { repo =>
      val newReturnType = "Quux"
      val action = for {
        _         <- repo.insert(suggestion.module)
        _         <- repo.insert(suggestion.tpe)
        _         <- repo.insert(suggestion.constructor)
        _         <- repo.insert(suggestion.method)
        _         <- repo.insert(suggestion.conversion)
        _         <- repo.insert(suggestion.function)
        Some(id4) <- repo.insert(suggestion.local)
        res <-
          repo.updateAll(Seq(suggestion.local.externalId.get -> newReturnType))
        Some(val4) <- repo.select(id4)
      } yield (id4, res._2, val4)

      val (suggestionId, updatedIds, result) = Await.result(action, Timeout)
      updatedIds.flatten shouldEqual Seq(suggestionId)
      result shouldEqual suggestion.local.copy(returnType = newReturnType)
    }

    "update suggestion external id" taggedAs Retry in withRepo { repo =>
      val newUuid = UUID.randomUUID()
      val action = for {
        (v1, Seq(_, _, _, id1, _, _, _)) <- repo.insertAll(
          Seq(
            suggestion.module,
            suggestion.tpe,
            suggestion.constructor,
            suggestion.method,
            suggestion.conversion,
            suggestion.function,
            suggestion.local
          )
        )
        (v2, id2) <- repo.update(
          suggestion.method,
          Some(Some(newUuid)),
          None,
          None,
          None,
          None,
          None
        )
        s <- repo.select(id1.get)
      } yield (v1, id1, v2, id2, s)
      val (v1, id1, v2, id2, s) = Await.result(action, Timeout)
      v1 should not equal v2
      id1 shouldEqual id2
      s shouldEqual Some(suggestion.method.copy(externalId = Some(newUuid)))
    }

    "update suggestion removing external id" taggedAs Retry in withRepo {
      repo =>
        val action = for {
          (v1, Seq(_, _, _, _, _, id1, _)) <- repo.insertAll(
            Seq(
              suggestion.module,
              suggestion.tpe,
              suggestion.constructor,
              suggestion.method,
              suggestion.conversion,
              suggestion.function,
              suggestion.local
            )
          )
          (v2, id2) <- repo.update(
            suggestion.function,
            Some(None),
            None,
            None,
            None,
            None,
            None
          )
          s <- repo.select(id1.get)
        } yield (v1, id1, v2, id2, s)
        val (v1, id1, v2, id2, s) = Await.result(action, Timeout)
        v1 should not equal v2
        id1 shouldEqual id2
        s shouldEqual Some(suggestion.function.copy(externalId = None))
    }

    "update suggestion return type" taggedAs Retry in withRepo { repo =>
      val newReturnType = "NewType"
      val action = for {
        (v1, Seq(_, _, _, _, _, id1, _)) <- repo.insertAll(
          Seq(
            suggestion.module,
            suggestion.tpe,
            suggestion.constructor,
            suggestion.method,
            suggestion.conversion,
            suggestion.function,
            suggestion.local
          )
        )
        (v2, id2) <- repo.update(
          suggestion.function,
          None,
          None,
          Some(newReturnType),
          None,
          None,
          None
        )
        s <- repo.select(id1.get)
      } yield (v1, id1, v2, id2, s)
      val (v1, id1, v2, id2, s) = Await.result(action, Timeout)
      v1 should not equal v2
      id1 shouldEqual id2
      s shouldEqual Some(suggestion.function.copy(returnType = newReturnType))
    }

    "update suggestion type documentation" taggedAs Retry in withRepo { repo =>
      val newDoc = "My Doc"
      val action = for {
        (v1, Seq(_, id1, _, _, _, _, _)) <- repo.insertAll(
          Seq(
            suggestion.module,
            suggestion.tpe,
            suggestion.constructor,
            suggestion.method,
            suggestion.conversion,
            suggestion.function,
            suggestion.local
          )
        )
        (v2, id2) <- repo.update(
          suggestion.tpe,
          None,
          None,
          None,
          Some(Some(newDoc)),
          None,
          None
        )
        s <- repo.select(id1.get)
      } yield (v1, id1, v2, id2, s)
      val (v1, id1, v2, id2, s) = Await.result(action, Timeout)
      v1 should not equal v2
      id1 shouldEqual id2
      s shouldEqual Some(
        suggestion.tpe.copy(documentation = Some(newDoc))
      )
    }

    "update suggestion constructor documentation" taggedAs Retry in withRepo {
      repo =>
        val newDoc = "My Doc"
        val action = for {
          (v1, Seq(_, _, id1, _, _, _, _)) <- repo.insertAll(
            Seq(
              suggestion.module,
              suggestion.tpe,
              suggestion.constructor,
              suggestion.method,
              suggestion.conversion,
              suggestion.function,
              suggestion.local
            )
          )
          (v2, id2) <- repo.update(
            suggestion.constructor,
            None,
            None,
            None,
            Some(Some(newDoc)),
            None,
            None
          )
          s <- repo.select(id1.get)
        } yield (v1, id1, v2, id2, s)
        val (v1, id1, v2, id2, s) = Await.result(action, Timeout)
        v1 should not equal v2
        id1 shouldEqual id2
        s shouldEqual Some(
          suggestion.constructor.copy(documentation = Some(newDoc))
        )
    }

    "update suggestion module documentation" taggedAs Retry in withRepo {
      repo =>
        val newDoc = "My Doc"
        val action = for {
          (v1, Seq(id1, _, _, _, _, _, _)) <- repo.insertAll(
            Seq(
              suggestion.module,
              suggestion.tpe,
              suggestion.constructor,
              suggestion.method,
              suggestion.conversion,
              suggestion.function,
              suggestion.local
            )
          )
          (v2, id2) <- repo.update(
            suggestion.module,
            None,
            None,
            None,
            Some(Some(newDoc)),
            None,
            None
          )
          s <- repo.select(id1.get)
        } yield (v1, id1, v2, id2, s)
        val (v1, id1, v2, id2, s) = Await.result(action, Timeout)
        v1 should not equal v2
        id1 shouldEqual id2
        s shouldEqual Some(suggestion.module.copy(documentation = Some(newDoc)))
    }

    "update suggestion conversion documentation" taggedAs Retry in withRepo {
      repo =>
        val newDoc = "My Doc"
        val action = for {
          (v1, Seq(_, _, _, _, id1, _, _)) <- repo.insertAll(
            Seq(
              suggestion.module,
              suggestion.tpe,
              suggestion.constructor,
              suggestion.method,
              suggestion.conversion,
              suggestion.function,
              suggestion.local
            )
          )
          (v2, id2) <- repo.update(
            suggestion.conversion,
            None,
            None,
            None,
            Some(Some(newDoc)),
            None,
            None
          )
          s <- repo.select(id1.get)
        } yield (v1, id1, v2, id2, s)
        val (v1, id1, v2, id2, s) = Await.result(action, Timeout)
        v1 should not equal v2
        id1 shouldEqual id2
        s shouldEqual Some(
          suggestion.conversion.copy(documentation = Some(newDoc))
        )
    }

    "update suggestion function documentation" taggedAs Retry in withRepo {
      repo =>
        val newDoc = "My awesome function!"
        val action = for {
          (v1, Seq(_, _, _, _, _, id1, _)) <- repo.insertAll(
            Seq(
              suggestion.module,
              suggestion.tpe,
              suggestion.constructor,
              suggestion.method,
              suggestion.conversion,
              suggestion.function,
              suggestion.local
            )
          )
          (v2, id2) <- repo.update(
            suggestion.function,
            None,
            None,
            None,
            Some(Some(newDoc)),
            None,
            None
          )
          s <- repo.select(id1.get)
        } yield (v1, id1, v2, id2, s)
        val (v1, id1, v2, id2, s) = Await.result(action, Timeout)
        v1 should not equal v2
        id1 shouldEqual id2
        s shouldEqual Some(
          suggestion.function.copy(documentation = Some(newDoc))
        )
    }

    "update suggestion local documentation" taggedAs Retry in withRepo { repo =>
      val newDoc = "Some stuff there"
      val action = for {
        (v1, Seq(_, _, _, _, _, _, id1)) <- repo.insertAll(
          Seq(
            suggestion.module,
            suggestion.tpe,
            suggestion.constructor,
            suggestion.method,
            suggestion.conversion,
            suggestion.function,
            suggestion.local
          )
        )
        (v2, id2) <- repo.update(
          suggestion.local,
          None,
          None,
          None,
          Some(Some(newDoc)),
          None,
          None
        )
        s <- repo.select(id1.get)
      } yield (v1, id1, v2, id2, s)
      val (v1, id1, v2, id2, s) = Await.result(action, Timeout)
      v1 should not equal v2
      id1 shouldEqual id2
      s shouldEqual Some(
        suggestion.local.copy(documentation = Some(newDoc))
      )
    }

    "update suggestion removing documentation" taggedAs Retry in withRepo {
      repo =>
        val action = for {
          (v1, Seq(_, _, id1, _, _, _, _)) <- repo.insertAll(
            Seq(
              suggestion.module,
              suggestion.tpe,
              suggestion.constructor,
              suggestion.method,
              suggestion.conversion,
              suggestion.function,
              suggestion.local
            )
          )
          (v2, id2) <- repo.update(
            suggestion.constructor,
            None,
            None,
            None,
            Some(None),
            None,
            None
          )
          s <- repo.select(id1.get)
        } yield (v1, id1, v2, id2, s)
        val (v1, id1, v2, id2, s) = Await.result(action, Timeout)
        v1 should not equal v2
        id1 shouldEqual id2
        s shouldEqual Some(suggestion.constructor.copy(documentation = None))
    }

    "update suggestion scope" taggedAs Retry in withRepo { repo =>
      val newScope = Suggestion.Scope(
        Suggestion.Position(14, 15),
        Suggestion.Position(42, 43)
      )
      val action = for {
        (v1, Seq(_, _, _, _, _, _, id1)) <- repo.insertAll(
          Seq(
            suggestion.module,
            suggestion.tpe,
            suggestion.constructor,
            suggestion.method,
            suggestion.conversion,
            suggestion.function,
            suggestion.local
          )
        )
        (v2, id2) <- repo.update(
          suggestion.local,
          None,
          None,
          None,
          None,
          Some(newScope),
          None
        )
        s <- repo.select(id1.get)
      } yield (v1, id1, v2, id2, s)
      val (v1, id1, v2, id2, s) = Await.result(action, Timeout)
      v1 should not equal v2
      id1 shouldEqual id2
      s shouldEqual Some(suggestion.local.copy(scope = newScope))
    }

    "remove suggestion arguments" taggedAs Retry in withRepo { repo =>
      val newArgs = Seq(
        Api.SuggestionArgumentAction.Remove(1)
      )
      val action = for {
        (v1, Seq(_, _, id1, _, _, _, _)) <- repo.insertAll(
          Seq(
            suggestion.module,
            suggestion.tpe,
            suggestion.constructor,
            suggestion.method,
            suggestion.conversion,
            suggestion.function,
            suggestion.local
          )
        )
        (v2, id2) <- repo.update(
          suggestion.constructor,
          None,
          Some(newArgs),
          None,
          None,
          None,
          None
        )
        s <- repo.select(id1.get)
      } yield (v1, id1, v2, id2, s)
      val (v1, id1, v2, id2, s) = Await.result(action, Timeout)
      v1 should not equal v2
      id1 shouldEqual id2
      s shouldEqual Some(
        suggestion.constructor.copy(arguments =
          suggestion.constructor.arguments.init
        )
      )
    }

    "add suggestion arguments" taggedAs Retry in withRepo { repo =>
      val newArgs = Seq(
        Api.SuggestionArgumentAction
          .Add(2, suggestion.constructor.arguments(0)),
        Api.SuggestionArgumentAction.Add(3, suggestion.constructor.arguments(1))
      )
      val action = for {
        (v1, Seq(_, _, id1, _, _, _, _)) <- repo.insertAll(
          Seq(
            suggestion.module,
            suggestion.tpe,
            suggestion.constructor,
            suggestion.method,
            suggestion.conversion,
            suggestion.function,
            suggestion.local
          )
        )
        (v2, id2) <- repo.update(
          suggestion.constructor,
          None,
          Some(newArgs),
          None,
          None,
          None,
          None
        )
        s <- repo.select(id1.get)
      } yield (v1, id1, v2, id2, s)
      val (v1, id1, v2, id2, s) = Await.result(action, Timeout)
      v1 should not equal v2
      id1 shouldEqual id2
      s shouldEqual Some(
        suggestion.constructor.copy(arguments =
          suggestion.constructor.arguments ++ suggestion.constructor.arguments
        )
      )
    }

    "update suggestion arguments" taggedAs Retry in withRepo { repo =>
      val newArgs = Seq(
        Api.SuggestionArgumentAction.Modify(
          1,
          Some("c"),
          Some("C"),
          Some(true),
          Some(true),
          Some(Some("C"))
        )
      )
      val action = for {
        (v1, Seq(_, _, id1, _, _, _, _)) <- repo.insertAll(
          Seq(
            suggestion.module,
            suggestion.tpe,
            suggestion.constructor,
            suggestion.method,
            suggestion.conversion,
            suggestion.function,
            suggestion.local
          )
        )
        (v2, id2) <- repo.update(
          suggestion.constructor,
          None,
          Some(newArgs),
          None,
          None,
          None,
          None
        )
        s <- repo.select(id1.get)
      } yield (v1, id1, v2, id2, s)
      val (v1, id1, v2, id2, s) = Await.result(action, Timeout)
      v1 should not equal v2
      id1 shouldEqual id2
      s shouldEqual Some(
        suggestion.constructor.copy(arguments =
          suggestion.constructor.arguments.init :+
          Suggestion.Argument("c", "C", true, true, Some("C"))
        )
      )
    }

    "update suggestion empty request" taggedAs Retry in withRepo { repo =>
      val action = for {
        (v1, _) <- repo.insertAll(
          Seq(
            suggestion.module,
            suggestion.tpe,
            suggestion.constructor,
            suggestion.method,
            suggestion.conversion,
            suggestion.function,
            suggestion.local
          )
        )
        (v2, id2) <- repo.update(
          suggestion.method,
          None,
          None,
          None,
          None,
          None,
          None
        )
      } yield (v1, v2, id2)
      val (v1, v2, id2) = Await.result(action, Timeout)
      v1 shouldEqual v2
      id2 shouldEqual None
    }

    "change version after updateAll" taggedAs Retry in withRepo { repo =>
      val newReturnType = "Quux"
      val action = for {
        _   <- repo.insert(suggestion.module)
        _   <- repo.insert(suggestion.tpe)
        _   <- repo.insert(suggestion.constructor)
        _   <- repo.insert(suggestion.method)
        _   <- repo.insert(suggestion.conversion)
        _   <- repo.insert(suggestion.function)
        id4 <- repo.insert(suggestion.local)
        v1  <- repo.currentVersion
        res <-
          repo.updateAll(Seq(suggestion.local.externalId.get -> newReturnType))
      } yield (id4, res._2, v1, res._1)

      val (suggestionId, updatedIds, v1, v2) = Await.result(action, Timeout)
      updatedIds shouldEqual Seq(suggestionId)
      v1 should not equal v2
    }

    "not change version after failed updateAll" taggedAs Retry in withRepo {
      repo =>
        val newReturnType = "Quux"
        val action = for {
          _   <- repo.insert(suggestion.module)
          _   <- repo.insert(suggestion.tpe)
          _   <- repo.insert(suggestion.constructor)
          _   <- repo.insert(suggestion.method)
          _   <- repo.insert(suggestion.conversion)
          _   <- repo.insert(suggestion.function)
          _   <- repo.insert(suggestion.local)
          v1  <- repo.currentVersion
          res <- repo.updateAll(Seq(UUID.randomUUID() -> newReturnType))
        } yield (res._2, v1, res._1)

        val (updatedIds, v1, v2) = Await.result(action, Timeout)
        updatedIds shouldEqual Seq(None)
        v1 shouldEqual v2
    }

    "rename the project name" taggedAs Retry in withRepo { repo =>
      val newModuleName = "local.Best.Main"
      val newSelfType   = "local.Best.Main"
      val newReturnType = "local.Best.Main.MyType"
      val action = for {
        (_, ids) <- repo.insertAll(
          Seq(
            suggestion.module,
            suggestion.tpe,
            suggestion.constructor,
            suggestion.method,
            suggestion.conversion,
            suggestion.function,
            suggestion.local
          )
        )
        (_, xs1, xs2, xs3, xs4) <- repo.renameProject("Test", "Best")
        (_, res)                <- repo.getAll
      } yield (ids, xs1, xs2, xs3, xs4, res)

      val (ids, xs1, xs2, xs3, xs4, res) = Await.result(action, Timeout)

      xs1 should contain theSameElementsAs ids.flatten.map((_, newModuleName))
      xs2 should contain theSameElementsAs Seq(ids(3)).flatten
        .map((_, newSelfType))
      xs3 should contain theSameElementsAs Seq(ids(4), ids(5), ids(6)).flatten
        .map {
          case id if ids(4).get == id => (id, "local.Best.Main.Bar")
          case id                     => (id, newReturnType)
        }
      xs4 should contain theSameElementsAs Seq(
        (ids(4).get, 0, "local.Best.Main.Foo")
      )
      res.map(_.suggestion) should contain theSameElementsAs Seq(
        suggestion.module.copy(module      = newModuleName),
        suggestion.tpe.copy(module         = newModuleName),
        suggestion.constructor.copy(module = newModuleName),
        suggestion.method
          .copy(module = newModuleName, selfType = newSelfType),
        suggestion.conversion.copy(
          module     = newModuleName,
          sourceType = "local.Best.Main.Foo",
          returnType = "local.Best.Main.Bar"
        ),
        suggestion.function
          .copy(module = newModuleName, returnType = newReturnType),
        suggestion.local
          .copy(module = newModuleName, returnType = newReturnType)
      )
    }

    "rename the module containing project name" taggedAs Retry in withRepo {
      repo =>
        val newModuleName = "local.Best.Main"
        val newSelfType   = "local.Best.Main"
        val newReturnType = "local.Best.Main.MyType"

        val constructor =
          suggestion.constructor.copy(module = "local.Test.Main.Test.Main")
        val all =
          Seq(
            suggestion.module,
            suggestion.tpe,
            constructor,
            suggestion.method,
            suggestion.conversion,
            suggestion.function,
            suggestion.local
          )
        val action = for {
          (_, ids)                <- repo.insertAll(all)
          (_, xs1, xs2, xs3, xs4) <- repo.renameProject("Test", "Best")
          (_, res)                <- repo.getAll
        } yield (ids, xs1, xs2, xs3, xs4, res)

        val (ids, xs1, xs2, xs3, xs4, res) = Await.result(action, Timeout)

        xs1 should contain theSameElementsAs ids.zip(all).flatMap {
          case (idOpt, _: Suggestion.Constructor) =>
            idOpt.map((_, "local.Best.Main.Test.Main"))
          case (idOpt, _) =>
            idOpt.map((_, newModuleName))
        }
        xs2 should contain theSameElementsAs Seq(ids(3)).flatten
          .map((_, newSelfType))
        xs3 should contain theSameElementsAs Seq(ids(4), ids(5), ids(6)).flatten
          .map {
            case id if ids(4).get == id => (id, "local.Best.Main.Bar")
            case id                     => (id, newReturnType)
          }
        xs4 should contain theSameElementsAs Seq(
          (ids(4).get, 0, "local.Best.Main.Foo")
        )
        res.map(_.suggestion) should contain theSameElementsAs Seq(
          suggestion.module.copy(module = newModuleName),
          suggestion.tpe.copy(module    = newModuleName),
          constructor.copy(module       = "local.Best.Main.Test.Main"),
          suggestion.method
            .copy(module = newModuleName, selfType = newSelfType),
          suggestion.conversion.copy(
            module     = newModuleName,
            sourceType = "local.Best.Main.Foo",
            returnType = "local.Best.Main.Bar"
          ),
          suggestion.function
            .copy(module = newModuleName, returnType = newReturnType),
          suggestion.local
            .copy(module = newModuleName, returnType = newReturnType)
        )
    }

    "rename multiple modules containing project name" taggedAs Retry in withRepo {
      repo =>
        val newMainModuleName = "local.Best.Main"
        val newFooModuleName  = "local.Best.Foo"
        val newReturnTypeName = "local.Best.Main.MyType"

        val module = suggestion.module.copy(module = "local.Test.Main")
        val tpe    = suggestion.tpe.copy(module = "local.Test.Main")
        val constructor =
          suggestion.constructor.copy(module = "local.Test.Main")
        val method     = suggestion.method.copy(module = "local.Test.Foo")
        val conversion = suggestion.conversion.copy(module = "local.Test.Foo")
        val function   = suggestion.function.copy(module = "local.Bar.Main")
        val local      = suggestion.local.copy(module = "local.Bar.Main")
        val all =
          Seq(module, tpe, constructor, method, conversion, function, local)
        val action = for {
          (_, ids)                <- repo.insertAll(all)
          (_, xs1, xs2, xs3, xs4) <- repo.renameProject("Test", "Best")
          (_, res)                <- repo.getAll
        } yield (ids, xs1, xs2, xs3, xs4, res)

        val (ids, xs1, xs2, xs3, xs4, res) = Await.result(action, Timeout)

        xs1 should contain theSameElementsAs ids
          .zip(Seq(module, tpe, constructor, method, conversion))
          .flatMap {
            case (idOpt, _: Suggestion.Module) =>
              idOpt.map((_, newMainModuleName))
            case (idOpt, _: Suggestion.Type) =>
              idOpt.map((_, newMainModuleName))
            case (idOpt, _: Suggestion.Constructor) =>
              idOpt.map((_, newMainModuleName))
            case (idOpt, _) =>
              idOpt.map((_, newFooModuleName))
          }
        xs2 should contain theSameElementsAs Seq(ids(3)).flatten
          .map((_, newMainModuleName))
        xs3 should contain theSameElementsAs Seq(ids(4), ids(5), ids(6)).flatten
          .map {
            case id if ids(4).get == id => (id, "local.Best.Main.Bar")
            case id                     => (id, newReturnTypeName)
          }
        xs4 should contain theSameElementsAs Seq(
          (ids(4).get, 0, "local.Best.Main.Foo")
        )
        res.map(_.suggestion) should contain theSameElementsAs Seq(
          module.copy(module      = newMainModuleName),
          tpe.copy(module         = newMainModuleName),
          constructor.copy(module = newMainModuleName),
          method.copy(module      = newFooModuleName, selfType = newMainModuleName),
          suggestion.conversion.copy(
            module     = newFooModuleName,
            sourceType = "local.Best.Main.Foo",
            returnType = "local.Best.Main.Bar"
          ),
          function.copy(returnType = newReturnTypeName),
          local.copy(returnType    = newReturnTypeName)
        )
    }

    "rename arguments containing project name" taggedAs Retry in withRepo {
      repo =>
        val newModuleName   = "local.Best.Main"
        val newSelfType     = "local.Best.Main"
        val newReturnType   = "local.Best.Main.MyType"
        val newArgumentType = "local.Best.Main.Test.MyType"

        val method = suggestion.method.copy(arguments =
          Seq(
            Suggestion.Argument("x", "Number", false, true, Some("0")),
            Suggestion.Argument(
              "y",
              "local.Test.Main.Test.MyType",
              false,
              false,
              None
            )
          )
        )
        val all =
          Seq(
            suggestion.module,
            suggestion.tpe,
            suggestion.constructor,
            method,
            suggestion.conversion,
            suggestion.function,
            suggestion.local
          )
        val action = for {
          (_, ids)                <- repo.insertAll(all)
          (_, xs1, xs2, xs3, xs4) <- repo.renameProject("Test", "Best")
          (_, res)                <- repo.getAll
        } yield (ids, xs1, xs2, xs3, xs4, res)

        val (ids, xs1, xs2, xs3, xs4, res) = Await.result(action, Timeout)

        xs1 should contain theSameElementsAs ids.flatten.map((_, newModuleName))
        xs2 should contain theSameElementsAs Seq(ids(3)).flatten
          .map((_, newSelfType))
        xs3 should contain theSameElementsAs Seq(ids(4), ids(5), ids(6)).flatten
          .map {
            case id if ids(4).get == id => (id, "local.Best.Main.Bar")
            case id                     => (id, newReturnType)
          }
        xs4 should contain theSameElementsAs Seq(ids(3), ids(4)).flatMap {
          _.map {
            case id if ids(4).get == id => (id, 0, "local.Best.Main.Foo")
            case id                     => (id, 1, newArgumentType)
          }
        }
        res.map(_.suggestion) should contain theSameElementsAs Seq(
          suggestion.module.copy(module      = newModuleName),
          suggestion.tpe.copy(module         = newModuleName),
          suggestion.constructor.copy(module = newModuleName),
          method
            .copy(
              module   = newModuleName,
              selfType = newSelfType,
              arguments = method.arguments.map { argument =>
                argument.copy(reprType =
                  if (argument.reprType.startsWith("local.Test."))
                    newArgumentType
                  else argument.reprType
                )
              }
            ),
          suggestion.conversion.copy(
            module     = newModuleName,
            sourceType = "local.Best.Main.Foo",
            returnType = "local.Best.Main.Bar"
          ),
          suggestion.function
            .copy(module = newModuleName, returnType = newReturnType),
          suggestion.local
            .copy(module = newModuleName, returnType = newReturnType)
        )
    }

    "change version after renaming the module" taggedAs Retry in withRepo {
      repo =>
        val action = for {
          v1               <- repo.insert(suggestion.constructor)
          (v2, _, _, _, _) <- repo.renameProject("Test", "Zest")
        } yield (v1, v2)

        val (v1, v2) = Await.result(action, Timeout)
        v1 should not equal Some(v2)
    }

    "not change version when not renamed the module" taggedAs Retry in withRepo {
      repo =>
        val action = for {
          v1               <- repo.insert(suggestion.constructor)
          (v2, _, _, _, _) <- repo.renameProject("Zest", "Best")
        } yield (v1, v2)

        val (v1, v2) = Await.result(action, Timeout)
        v1 shouldEqual Some(v2)
    }

    "apply export updates" taggedAs Retry in withRepo { repo =>
      val reexport = "Foo.Bar"
      val method   = suggestion.method.copy(reexport = Some(reexport))
      val updates = Seq(
        Api.ExportsUpdate(
          ModuleExports(
            reexport,
            Set(ExportedSymbol.Module(suggestion.module.module))
          ),
          Api.ExportsAction.Add()
        ),
        Api.ExportsUpdate(
          ModuleExports(
            reexport,
            Set(ExportedSymbol.Method(method.module, method.name))
          ),
          Api.ExportsAction.Remove()
        )
      )
      val action = for {
        (_, ids) <- repo.insertAll(
          Seq(
            suggestion.module,
            suggestion.tpe,
            suggestion.constructor,
            method,
            suggestion.conversion,
            suggestion.function,
            suggestion.local
          )
        )
        results <- repo.applyExports(updates)
      } yield (ids, results)

      val (ids, results) = Await.result(action, Timeout)
      results should contain theSameElementsAs Seq(
        QueryResult(ids(0).toSeq, updates(0)),
        QueryResult(ids(3).toSeq, updates(1))
      )
    }

    "not apply exports with bigger module name" taggedAs Retry in withRepo {
      repo =>
        val reexport = "Foo.Bar.Baz"
        val method   = suggestion.method.copy(reexport = Some("Foo.Bar"))
        val updates = Seq(
          Api.ExportsUpdate(
            ModuleExports(
              reexport,
              Set(ExportedSymbol.Module(suggestion.module.module))
            ),
            Api.ExportsAction.Add()
          ),
          Api.ExportsUpdate(
            ModuleExports(
              reexport,
              Set(ExportedSymbol.Method(method.module, method.name))
            ),
            Api.ExportsAction.Remove()
          )
        )
        val action = for {
          (_, ids) <- repo.insertAll(
            Seq(
              suggestion.module,
              suggestion.tpe,
              suggestion.constructor,
              method,
              suggestion.conversion,
              suggestion.function,
              suggestion.local
            )
          )
          results <- repo.applyExports(updates)
        } yield (ids, results)

        val (ids, results) = Await.result(action, Timeout)
        results should contain theSameElementsAs Seq(
          QueryResult(ids(0).toSeq, updates(0)),
          QueryResult(Seq(), updates(1))
        )
    }

    "change version after applying exports" taggedAs Retry in withRepo { repo =>
      val reexport = "Foo.Bar"
      val method   = suggestion.method.copy(reexport = Some(reexport))
      val updates = Seq(
        Api.ExportsUpdate(
          ModuleExports(
            reexport,
            Set(ExportedSymbol.Module(suggestion.module.module))
          ),
          Api.ExportsAction.Add()
        ),
        Api.ExportsUpdate(
          ModuleExports(
            reexport,
            Set(ExportedSymbol.Method(method.module, method.name))
          ),
          Api.ExportsAction.Remove()
        )
      )
      val action = for {
        _ <- repo.insertAll(
          Seq(
            suggestion.module,
            suggestion.tpe,
            suggestion.constructor,
            method,
            suggestion.conversion,
            suggestion.function,
            suggestion.local
          )
        )
        v1      <- repo.currentVersion
        results <- repo.applyExports(updates)
        v2      <- repo.currentVersion
      } yield (results, v1, v2)

      val (results, v1, v2) = Await.result(action, Timeout)
      results.flatMap(_.ids).isEmpty shouldBe false
      v1 should not equal v2
    }

    "not change version when exports not applied" taggedAs Retry in withRepo {
      repo =>
        val reexport = "Foo.Bar"
        val updates = Seq(
          Api.ExportsUpdate(
            ModuleExports(
              reexport,
              Set(
                ExportedSymbol
                  .Method(suggestion.method.module, suggestion.method.name)
              )
            ),
            Api.ExportsAction.Remove()
          )
        )
        val action = for {
          _ <- repo.insertAll(
            Seq(
              suggestion.module,
              suggestion.tpe,
              suggestion.constructor,
              suggestion.method,
              suggestion.conversion,
              suggestion.function,
              suggestion.local
            )
          )
          v1      <- repo.currentVersion
          results <- repo.applyExports(updates)
          v2      <- repo.currentVersion
        } yield (results, v1, v2)

        val (results, v1, v2) = Await.result(action, Timeout)
        results.flatMap(_.ids).isEmpty shouldBe true
        v1 shouldEqual v2
    }

    "search suggestion by empty query" taggedAs Retry in withRepo { repo =>
      val action = for {
        _   <- repo.insert(suggestion.module)
        _   <- repo.insert(suggestion.tpe)
        _   <- repo.insert(suggestion.constructor)
        _   <- repo.insert(suggestion.method)
        _   <- repo.insert(suggestion.conversion)
        _   <- repo.insert(suggestion.function)
        _   <- repo.insert(suggestion.local)
        res <- repo.search(None, Seq(), None, None, None)
      } yield res._2

      val res = Await.result(action, Timeout)
      res.isEmpty shouldEqual true
    }

    "search suggestion by module" taggedAs Retry in withRepo { repo =>
      val action = for {
        id0 <- repo.insert(suggestion.module)
        id1 <- repo.insert(suggestion.tpe)
        id2 <- repo.insert(suggestion.constructor)
        id3 <- repo.insert(suggestion.method)
        id4 <- repo.insert(suggestion.conversion)
        id5 <- repo.insert(suggestion.function)
        id6 <- repo.insert(suggestion.local)
        res <- repo.search(Some("local.Test.Main"), Seq(), None, None, None)
      } yield (id0, id1, id2, id3, id4, id5, id6, res._2)

      val (id0, id1, id2, id3, id4, id5, id6, res) =
        Await.result(action, Timeout)
      res should contain theSameElementsAs Seq(
        id0,
        id1,
        id2,
        id3,
        id4,
        id5,
        id6
      ).flatten
    }

    "search suggestion by empty module" taggedAs Retry in withRepo { repo =>
      val action = for {
        id0 <- repo.insert(suggestion.module)
        id1 <- repo.insert(suggestion.tpe)
        id2 <- repo.insert(suggestion.constructor)
        id3 <- repo.insert(suggestion.method)
        id4 <- repo.insert(suggestion.conversion)
        _   <- repo.insert(suggestion.function)
        _   <- repo.insert(suggestion.local)
        res <- repo.search(Some(""), Seq(), None, None, None)
      } yield (res._2, Seq(id0, id1, id2, id3, id4))

      val (res, globals) = Await.result(action, Timeout)
      res should contain theSameElementsAs globals.flatten
    }

    "search suggestion by self type" taggedAs Retry in withRepo { repo =>
      val action = for {
        _   <- repo.insert(suggestion.module)
        _   <- repo.insert(suggestion.tpe)
        _   <- repo.insert(suggestion.constructor)
        id2 <- repo.insert(suggestion.method)
        _   <- repo.insert(suggestion.conversion)
        _   <- repo.insert(suggestion.function)
        _   <- repo.insert(suggestion.local)
        res <- repo.search(None, Seq("local.Test.Main"), None, None, None)
      } yield (id2, res._2)

      val (id, res) = Await.result(action, Timeout)
      res should contain theSameElementsAs Seq(id).flatten
    }

    "search suggestion by return type" taggedAs Retry in withRepo { repo =>
      val action = for {
        _   <- repo.insert(suggestion.module)
        _   <- repo.insert(suggestion.tpe)
        _   <- repo.insert(suggestion.constructor)
        _   <- repo.insert(suggestion.method)
        _   <- repo.insert(suggestion.conversion)
        id3 <- repo.insert(suggestion.function)
        id4 <- repo.insert(suggestion.local)
        res <- repo.search(
          None,
          Seq(),
          Some("local.Test.Main.MyType"),
          None,
          None
        )
      } yield (id3, id4, res._2)

      val (id1, id2, res) = Await.result(action, Timeout)
      res should contain theSameElementsAs Seq(id1, id2).flatten
    }

    "search suggestion by kind" taggedAs Retry in withRepo { repo =>
      val kinds = Seq(Suggestion.Kind.Constructor, Suggestion.Kind.Local)
      val action = for {
        _   <- repo.insert(suggestion.module)
        _   <- repo.insert(suggestion.tpe)
        id1 <- repo.insert(suggestion.constructor)
        _   <- repo.insert(suggestion.method)
        _   <- repo.insert(suggestion.conversion)
        _   <- repo.insert(suggestion.function)
        id4 <- repo.insert(suggestion.local)
        res <- repo.search(None, Seq(), None, Some(kinds), None)
      } yield (id1, id4, res._2)

      val (id1, id2, res) = Await.result(action, Timeout)
      res should contain theSameElementsAs Seq(id1, id2).flatten
    }

    "search suggestion by empty kinds" taggedAs Retry in withRepo { repo =>
      val action = for {
        _   <- repo.insert(suggestion.module)
        _   <- repo.insert(suggestion.tpe)
        _   <- repo.insert(suggestion.constructor)
        _   <- repo.insert(suggestion.method)
        _   <- repo.insert(suggestion.conversion)
        _   <- repo.insert(suggestion.function)
        _   <- repo.insert(suggestion.local)
        res <- repo.search(None, Seq(), None, Some(Seq()), None)
      } yield res._2

      val res = Await.result(action, Timeout)
      res.isEmpty shouldEqual true
    }

    "search suggestion outside of scope" taggedAs Retry in withRepo { repo =>
      val action = for {
        id0 <- repo.insert(suggestion.module)
        id1 <- repo.insert(suggestion.tpe)
        id2 <- repo.insert(suggestion.constructor)
        id3 <- repo.insert(suggestion.method)
        id4 <- repo.insert(suggestion.conversion)
        _   <- repo.insert(suggestion.function)
        _   <- repo.insert(suggestion.local)
        res <-
          repo.search(
            None,
            Seq(),
            None,
            None,
            Some(Suggestion.Position(99, 42))
          )
      } yield (id0, id1, id2, id3, id4, res._2)

      val (id0, id1, id2, id3, id4, res) = Await.result(action, Timeout)
      res should contain theSameElementsAs Seq(id0, id1, id2, id3, id4).flatten
    }

    "search suggestion by scope begin" taggedAs Retry in withRepo { repo =>
      val action = for {
        id0 <- repo.insert(suggestion.module)
        id1 <- repo.insert(suggestion.tpe)
        id2 <- repo.insert(suggestion.constructor)
        id3 <- repo.insert(suggestion.method)
        id4 <- repo.insert(suggestion.conversion)
        id5 <- repo.insert(suggestion.function)
        _   <- repo.insert(suggestion.local)
        res <-
          repo.search(None, Seq(), None, None, Some(Suggestion.Position(1, 5)))
      } yield (id0, id1, id2, id3, id4, id5, res._2)

      val (id0, id1, id2, id3, id4, id5, res) = Await.result(action, Timeout)
      res should contain theSameElementsAs Seq(
        id0,
        id1,
        id2,
        id3,
        id4,
        id5
      ).flatten
    }

    "search suggestion by scope end" taggedAs Retry in withRepo { repo =>
      val action = for {
        id0 <- repo.insert(suggestion.module)
        id1 <- repo.insert(suggestion.tpe)
        id2 <- repo.insert(suggestion.constructor)
        id3 <- repo.insert(suggestion.method)
        id4 <- repo.insert(suggestion.conversion)
        id5 <- repo.insert(suggestion.function)
        id6 <- repo.insert(suggestion.local)
        res <-
          repo.search(None, Seq(), None, None, Some(Suggestion.Position(6, 0)))
      } yield (id0, id1, id2, id3, id4, id5, id6, res._2)

      val (id0, id1, id2, id3, id4, id5, id6, res) =
        Await.result(action, Timeout)
      res should contain theSameElementsAs Seq(
        id0,
        id1,
        id2,
        id3,
        id4,
        id5,
        id6
      ).flatten
    }

    "search suggestion inside the function scope" taggedAs Retry in withRepo {
      repo =>
        val action = for {
          id0 <- repo.insert(suggestion.module)
          id1 <- repo.insert(suggestion.tpe)
          id2 <- repo.insert(suggestion.constructor)
          id3 <- repo.insert(suggestion.method)
          id4 <- repo.insert(suggestion.conversion)
          id5 <- repo.insert(suggestion.function)
          id6 <- repo.insert(suggestion.local)
          res <-
            repo.search(
              None,
              Seq(),
              None,
              None,
              Some(Suggestion.Position(2, 0))
            )
        } yield (id0, id1, id2, id3, id4, id5, id6, res._2)

        val (id0, id1, id2, id3, id4, id5, _, res) =
          Await.result(action, Timeout)
        res should contain theSameElementsAs Seq(
          id0,
          id1,
          id2,
          id3,
          id4,
          id5
        ).flatten
    }

    "search suggestion inside the local scope" taggedAs Retry in withRepo {
      repo =>
        val action = for {
          id0 <- repo.insert(suggestion.module)
          id1 <- repo.insert(suggestion.tpe)
          id2 <- repo.insert(suggestion.constructor)
          id3 <- repo.insert(suggestion.method)
          id4 <- repo.insert(suggestion.conversion)
          id5 <- repo.insert(suggestion.function)
          id6 <- repo.insert(suggestion.local)
          res <-
            repo.search(
              None,
              Seq(),
              None,
              None,
              Some(Suggestion.Position(4, 0))
            )
        } yield (id0, id1, id2, id3, id4, id5, id6, res._2)

        val (id0, id1, id2, id3, id4, id5, id6, res) =
          Await.result(action, Timeout)
        res should contain theSameElementsAs Seq(
          id0,
          id1,
          id2,
          id3,
          id4,
          id5,
          id6
        ).flatten
    }

    "search suggestion by module and self type" taggedAs Retry in withRepo {
      repo =>
        val action = for {
          _   <- repo.insert(suggestion.module)
          _   <- repo.insert(suggestion.tpe)
          _   <- repo.insert(suggestion.constructor)
          id2 <- repo.insert(suggestion.method)
          _   <- repo.insert(suggestion.conversion)
          _   <- repo.insert(suggestion.function)
          _   <- repo.insert(suggestion.local)
          res <- repo.search(
            Some("local.Test.Main"),
            Seq("local.Test.Main"),
            None,
            None,
            None
          )
        } yield (id2, res._2)

        val (id, res) = Await.result(action, Timeout)
        res should contain theSameElementsAs Seq(id).flatten
    }

    "search suggestion by return type and kind" taggedAs Retry in withRepo {
      repo =>
        val kinds = Seq(Suggestion.Kind.Constructor, Suggestion.Kind.Local)
        val action = for {
          _   <- repo.insert(suggestion.module)
          _   <- repo.insert(suggestion.tpe)
          _   <- repo.insert(suggestion.constructor)
          _   <- repo.insert(suggestion.method)
          _   <- repo.insert(suggestion.conversion)
          _   <- repo.insert(suggestion.function)
          id4 <- repo.insert(suggestion.local)
          res <- repo.search(
            None,
            Seq(),
            Some("local.Test.Main.MyType"),
            Some(kinds),
            None
          )
        } yield (id4, res._2)

        val (id, res) = Await.result(action, Timeout)
        res should contain theSameElementsAs Seq(id).flatten
    }

    "search suggestion by return type and scope" taggedAs Retry in withRepo {
      repo =>
        val action = for {
          _   <- repo.insert(suggestion.module)
          _   <- repo.insert(suggestion.tpe)
          _   <- repo.insert(suggestion.constructor)
          _   <- repo.insert(suggestion.method)
          _   <- repo.insert(suggestion.conversion)
          id3 <- repo.insert(suggestion.function)
          id4 <- repo.insert(suggestion.local)
          res <- repo.search(
            None,
            Seq(),
            Some("local.Test.Main.MyType"),
            None,
            Some(Suggestion.Position(6, 0))
          )
        } yield (id3, id4, res._2)

        val (id1, id2, res) = Await.result(action, Timeout)
        res should contain theSameElementsAs Seq(id1, id2).flatten
    }

    "search suggestion by kind and scope" taggedAs Retry in withRepo { repo =>
      val kinds = Seq(Suggestion.Kind.Constructor, Suggestion.Kind.Local)
      val action = for {
        _   <- repo.insert(suggestion.module)
        _   <- repo.insert(suggestion.tpe)
        id1 <- repo.insert(suggestion.constructor)
        _   <- repo.insert(suggestion.method)
        _   <- repo.insert(suggestion.conversion)
        _   <- repo.insert(suggestion.function)
        _   <- repo.insert(suggestion.local)
        res <- repo.search(
          None,
          Seq(),
          None,
          Some(kinds),
          Some(Suggestion.Position(99, 1))
        )
      } yield (id1, res._2)

      val (id, res) = Await.result(action, Timeout)
      res should contain theSameElementsAs Seq(id).flatten
    }

    "search suggestion by self and return types" taggedAs Retry in withRepo {
      repo =>
        val action = for {
          _ <- repo.insert(suggestion.module)
          _ <- repo.insert(suggestion.tpe)
          _ <- repo.insert(suggestion.constructor)
          _ <- repo.insert(suggestion.method)
          _ <- repo.insert(suggestion.conversion)
          _ <- repo.insert(suggestion.function)
          _ <- repo.insert(suggestion.local)
          res <- repo.search(
            None,
            Seq("local.Test.Main"),
            Some("local.Test.Main.MyType"),
            None,
            None
          )
        } yield res._2

        val res = Await.result(action, Timeout)
        res.isEmpty shouldEqual true
    }

    "search suggestion by module, return type and kind" taggedAs Retry in withRepo {
      repo =>
        val kinds = Seq(Suggestion.Kind.Constructor, Suggestion.Kind.Local)
        val action = for {
          _   <- repo.insert(suggestion.module)
          _   <- repo.insert(suggestion.tpe)
          _   <- repo.insert(suggestion.constructor)
          _   <- repo.insert(suggestion.method)
          _   <- repo.insert(suggestion.conversion)
          _   <- repo.insert(suggestion.function)
          id4 <- repo.insert(suggestion.local)
          res <- repo.search(
            Some("local.Test.Main"),
            Seq(),
            Some("local.Test.Main.MyType"),
            Some(kinds),
            None
          )
        } yield (id4, res._2)

        val (id, res) = Await.result(action, Timeout)
        res should contain theSameElementsAs Seq(id).flatten
    }

    "search suggestion by return type, kind and scope" taggedAs Retry in withRepo {
      repo =>
        val kinds = Seq(Suggestion.Kind.Constructor, Suggestion.Kind.Local)
        val action = for {
          _   <- repo.insert(suggestion.module)
          _   <- repo.insert(suggestion.tpe)
          _   <- repo.insert(suggestion.constructor)
          _   <- repo.insert(suggestion.method)
          _   <- repo.insert(suggestion.conversion)
          _   <- repo.insert(suggestion.function)
          id4 <- repo.insert(suggestion.local)
          res <- repo.search(
            None,
            Seq(),
            Some("local.Test.Main.MyType"),
            Some(kinds),
            Some(Suggestion.Position(6, 0))
          )
        } yield (id4, res._2)

        val (id, res) = Await.result(action, Timeout)
        res should contain theSameElementsAs Seq(id).flatten
    }

    "search suggestion by all parameters" taggedAs Retry in withRepo { repo =>
      val kinds = Seq(
        Suggestion.Kind.Constructor,
        Suggestion.Kind.Method,
        Suggestion.Kind.Function
      )
      val action = for {
        _ <- repo.insert(suggestion.module)
        _ <- repo.insert(suggestion.tpe)
        _ <- repo.insert(suggestion.constructor)
        _ <- repo.insert(suggestion.method)
        _ <- repo.insert(suggestion.conversion)
        _ <- repo.insert(suggestion.function)
        _ <- repo.insert(suggestion.local)
        res <- repo.search(
          Some("local.Test.Main"),
          Seq("local.Test.Main"),
          Some("local.Test.Main.MyType"),
          Some(kinds),
          Some(Suggestion.Position(42, 0))
        )
      } yield res._2

      val res = Await.result(action, Timeout)
      res.isEmpty shouldEqual true
    }
  }

  object suggestion {

    val module: Suggestion.Module =
      Suggestion.Module(
        module        = "local.Test.Main",
        documentation = Some("This is a main module.")
      )

    val tpe: Suggestion.Type =
      Suggestion.Type(
        externalId = None,
        module     = "local.Test.Main",
        name       = "Maybe",
        params = Seq(
          Suggestion.Argument("a", "Any", false, false, None)
        ),
        returnType    = "Standard.Builtins.Maybe",
        parentType    = Some("Standard.Builtins.Any"),
        documentation = Some("To be or not to be")
      )

    val constructor: Suggestion.Constructor =
      Suggestion.Constructor(
        externalId = None,
        module     = "local.Test.Main",
        name       = "Standard.Builtins.Pair",
        arguments = Seq(
          Suggestion.Argument("a", "Any", false, false, None),
          Suggestion.Argument("b", "Any", false, false, None)
        ),
        returnType    = "Standard.Builtins.Pair",
        documentation = Some("Awesome")
      )

    val method: Suggestion.Method =
      Suggestion.Method(
        externalId    = Some(UUID.randomUUID()),
        module        = "local.Test.Main",
        name          = "main",
        arguments     = Seq(),
        selfType      = "local.Test.Main",
        returnType    = "Standard.Builtins.IO",
        isStatic      = true,
        documentation = None
      )

    val instanceMethod: Suggestion.Method =
      Suggestion.Method(
        externalId    = Some(UUID.randomUUID()),
        module        = "local.Test.Main",
        name          = "foo",
        arguments     = Seq(),
        selfType      = "local.Test.Main.A",
        returnType    = "Standard.Builtins.Nothing",
        isStatic      = false,
        documentation = None
      )

    val conversion: Suggestion.Conversion =
      Suggestion.Conversion(
        externalId    = Some(UUID.randomUUID()),
        module        = "local.Test.Main",
        arguments     = Seq(),
        sourceType    = "local.Test.Main.Foo",
        returnType    = "local.Test.Main.Bar",
        documentation = None
      )

    val function: Suggestion.Function =
      Suggestion.Function(
        externalId = Some(UUID.randomUUID()),
        module     = "local.Test.Main",
        name       = "bar",
        arguments = Seq(
          Suggestion.Argument("x", "Number", false, true, Some("0"))
        ),
        returnType = "local.Test.Main.MyType",
        scope = Suggestion
          .Scope(Suggestion.Position(1, 5), Suggestion.Position(6, 0)),
        documentation = Some("My function bar.")
      )

    val local: Suggestion.Local =
      Suggestion.Local(
        externalId = Some(UUID.randomUUID()),
        module     = "local.Test.Main",
        name       = "bazz",
        returnType = "local.Test.Main.MyType",
        scope = Suggestion.Scope(
          Suggestion.Position(3, 4),
          Suggestion.Position(6, 0)
        ),
        documentation = Some("Some bazz")
      )
  }
}
