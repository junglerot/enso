package org.enso.languageserver.boot

import akka.event.EventStream
import org.enso.languageserver.boot.resource.{
  DirectoriesInitialization,
  InitializationComponent,
  RepoInitialization,
  SequentialResourcesInitialization,
  TruffleContextInitialization,
  ZioRuntimeInitialization
}
import org.enso.languageserver.data.ProjectDirectoriesConfig
import org.enso.languageserver.effect
import org.enso.searcher.sql.{SqlDatabase, SqlSuggestionsRepo, SqlVersionsRepo}
import org.graalvm.polyglot.Context

import scala.concurrent.ExecutionContext

/** Helper object for the initialization of the Language Server resources.
  * Creates the directories, initializes the databases, and the Truffle context.
  */
object ResourcesInitialization {

  /** Create the initialization component of the Language Server.
    *
    * @param eventStream system event stream
    * @param directoriesConfig configuration of directories that should be created
    * @param suggestionsRepo the suggestions repo
    * @param sqlDatabase the sql database
    * @param versionsRepo the file versions repo
    * @param truffleContext the runtime context
    * @param runtime the runtime to run effects
    * @return the initialization component
    */
  def apply(
    eventStream: EventStream,
    directoriesConfig: ProjectDirectoriesConfig,
    sqlDatabase: SqlDatabase,
    suggestionsRepo: SqlSuggestionsRepo,
    versionsRepo: SqlVersionsRepo,
    truffleContext: Context,
    runtime: effect.Runtime
  )(implicit ec: ExecutionContext): InitializationComponent = {
    val resources = Seq(
      new DirectoriesInitialization(directoriesConfig),
      new ZioRuntimeInitialization(runtime),
      new RepoInitialization(
        directoriesConfig,
        eventStream,
        sqlDatabase,
        suggestionsRepo,
        versionsRepo
      ),
      new TruffleContextInitialization(eventStream, truffleContext)
    )
    new SequentialResourcesInitialization(resources)
  }
}
