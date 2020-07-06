package org.enso.searcher.sql

import com.typesafe.config.{Config, ConfigFactory}
import org.enso.searcher.Database
import slick.dbio.DBIO
import slick.jdbc.SQLiteProfile
import slick.jdbc.SQLiteProfile.api._

import scala.concurrent.Future

/** Ths SQL database that runs Slick [[DBIO]] queries resulting in a [[Future]].
  *
  * @param config the configuration
  */
final private[sql] class SqlDatabase(config: Option[Config] = None)
    extends Database[DBIO, Future] {

  val db = SQLiteProfile.backend.Database
    .forConfig(SqlDatabase.configPath, config.orNull)

  /** @inheritdoc */
  override def run[A](query: DBIO[A]): Future[A] =
    db.run(query)

  /** @inheritdoc */
  override def transaction[A](query: DBIO[A]): Future[A] =
    db.run(query.transactionally)

  /** @inheritdoc */
  override def close(): Unit =
    db.close()
}

object SqlDatabase {

  private val configPath: String =
    "searcher.db"

  /** Create [[SqlDatabase]] instance.
    *
    * @param filename the database file path
    * @return new sql database instance
    */
  def apply(filename: String): SqlDatabase = {
    val config = ConfigFactory
      .parseString(s"""$configPath.url = "${jdbcUrl(filename)}"""")
      .withFallback(ConfigFactory.load())
    new SqlDatabase(Some(config))
  }

  /** Create JDBC URL from the file path. */
  private def jdbcUrl(filename: String): String =
    s"jdbc:sqlite:${escapePath(filename)}"

  /** Escape Windows path. */
  private def escapePath(path: String): String =
    path.replace("\\", "\\\\")
}
