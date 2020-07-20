package org.enso.searcher.sql

import org.enso.polyglot.Suggestion
import slick.jdbc.SQLiteProfile.api._

import scala.annotation.nowarn

/** A row in the arguments table.
  *
  * @param id the id of an argument
  * @param suggestionId the id of the suggestion
  * @param index the argument position in the arguments list
  * @param name the argument name
  * @param tpe the argument type
  * @param isSuspended is the argument lazy
  * @param hasDefault does the argument have the default value
  * @param defaultValue optional default value
  */
case class ArgumentRow(
  id: Option[Long],
  suggestionId: Long,
  index: Int,
  name: String,
  tpe: String,
  isSuspended: Boolean,
  hasDefault: Boolean,
  defaultValue: Option[String]
)

/** A row in the suggestions table.
  *
  * @param id the id of a suggestion
  * @param externalIdLeast the least significant bits of external id
  * @param externalIdMost the most significant bits of external id
  * @param kind the type of a suggestion
  * @param module the module name
  * @param name the suggestion name
  * @param selfType the self type of a suggestion
  * @param returnType the return type of a suggestion
  * @param documentation the documentation string
  * @param scopeStartLine the line of the start position of the scope
  * @param scopeStartOffset the offset of the start position of the scope
  * @param scopeEndLine the line of the end position of the scope
  * @param scopeEndOffset the offset of the end position of the scope
  */
case class SuggestionRow(
  id: Option[Long],
  externalIdLeast: Option[Long],
  externalIdMost: Option[Long],
  kind: Byte,
  module: String,
  name: String,
  selfType: String,
  returnType: String,
  documentation: Option[String],
  scopeStartLine: Int,
  scopeStartOffset: Int,
  scopeEndLine: Int,
  scopeEndOffset: Int
)

/** A row in the suggestions_version table.
  *
  * @param id the row id
  */
case class SuggestionsVersionRow(id: Option[Long])

/** A row in the file_versions table
  *
  * @param path the file path
  * @param digest the file version
  */
case class FileVersionRow(path: String, digest: Array[Byte])

/** The type of a suggestion. */
object SuggestionKind {

  val ATOM: Byte     = 0
  val METHOD: Byte   = 1
  val FUNCTION: Byte = 2
  val LOCAL: Byte    = 3

  /** Create a database suggestion kind.
    *
    * @param kind the suggestion kind
    * @return the representation of the suggestion kind in the database
    */
  def apply(kind: Suggestion.Kind): Byte =
    kind match {
      case Suggestion.Kind.Atom     => ATOM
      case Suggestion.Kind.Method   => METHOD
      case Suggestion.Kind.Function => FUNCTION
      case Suggestion.Kind.Local    => LOCAL
    }
}

object ScopeColumn {

  /** A constant representing an empty value in the scope column. */
  val EMPTY: Int = -1
}

object SelfTypeColumn {

  /** A constant representing en empty value in the self type column. */
  val EMPTY: String = "\u0500"
}

/** The schema of the arguments table. */
@nowarn("msg=multiarg infix syntax")
final class ArgumentsTable(tag: Tag)
    extends Table[ArgumentRow](tag, "arguments") {

  def id           = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def suggestionId = column[Long]("suggestion_id")
  def index        = column[Int]("index")
  def name         = column[String]("name")
  def tpe          = column[String]("type")
  def isSuspended  = column[Boolean]("is_suspended", O.Default(false))
  def hasDefault   = column[Boolean]("has_default", O.Default(false))
  def defaultValue = column[Option[String]]("default_value")
  def * =
    (
      id.?,
      suggestionId,
      index,
      name,
      tpe,
      isSuspended,
      hasDefault,
      defaultValue
    ) <>
    (ArgumentRow.tupled, ArgumentRow.unapply)

  def suggestion =
    foreignKey("suggestion_fk", suggestionId, Suggestions)(
      _.id,
      onUpdate = ForeignKeyAction.Restrict,
      onDelete = ForeignKeyAction.Cascade
    )
}

/** The schema of the suggestions table. */
@nowarn("msg=multiarg infix syntax")
final class SuggestionsTable(tag: Tag)
    extends Table[SuggestionRow](tag, "suggestions") {

  def id              = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def externalIdLeast = column[Option[Long]]("external_id_least")
  def externalIdMost  = column[Option[Long]]("external_id_most")
  def kind            = column[Byte]("kind")
  def module          = column[String]("module")
  def name            = column[String]("name")
  def selfType        = column[String]("self_type")
  def returnType      = column[String]("return_type")
  def documentation   = column[Option[String]]("documentation")
  def scopeStartLine =
    column[Int]("scope_start_line", O.Default(ScopeColumn.EMPTY))
  def scopeStartOffset =
    column[Int]("scope_start_offset", O.Default(ScopeColumn.EMPTY))
  def scopeEndLine =
    column[Int]("scope_end_line", O.Default(ScopeColumn.EMPTY))
  def scopeEndOffset =
    column[Int]("scope_end_offset", O.Default(ScopeColumn.EMPTY))
  def * =
    (
      id.?,
      externalIdLeast,
      externalIdMost,
      kind,
      module,
      name,
      selfType,
      returnType,
      documentation,
      scopeStartLine,
      scopeStartOffset,
      scopeEndLine,
      scopeEndOffset
    ) <>
    (SuggestionRow.tupled, SuggestionRow.unapply)

  def moduleIdx     = index("suggestions_module_idx", module)
  def name_idx      = index("suggestions_name_idx", name)
  def selfTypeIdx   = index("suggestions_self_type_idx", selfType)
  def returnTypeIdx = index("suggestions_return_type_idx", returnType)
  def externalIdIdx =
    index("suggestions_external_id_idx", (externalIdLeast, externalIdMost))
  // NOTE: unique index should not contain nullable columns because SQLite
  // teats NULLs as distinct values.
  def uniqueIdx =
    index(
      "suggestions_unique_idx",
      (
        kind,
        module,
        name,
        selfType,
        scopeStartLine,
        scopeStartOffset,
        scopeEndLine,
        scopeEndOffset
      ),
      unique = true
    )
}

/** The schema of the suggestions_version table. */
@nowarn("msg=multiarg infix syntax")
final class SuggestionsVersionTable(tag: Tag)
    extends Table[SuggestionsVersionRow](tag, "suggestions_version") {

  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)

  def * = id.? <> (SuggestionsVersionRow.apply, SuggestionsVersionRow.unapply)
}

/** The schema of the file_versions table. */
@nowarn("msg=multiarg infix syntax")
final class FileVersionsTable(tag: Tag)
    extends Table[FileVersionRow](tag, "file_versions") {

  def path   = column[String]("path", O.PrimaryKey)
  def digest = column[Array[Byte]]("digest")

  def * = (path, digest) <> (FileVersionRow.tupled, FileVersionRow.unapply)
}

object Arguments extends TableQuery(new ArgumentsTable(_))

object Suggestions extends TableQuery(new SuggestionsTable(_))

object SuggestionsVersions extends TableQuery(new SuggestionsVersionTable(_))

object FileVersions extends TableQuery(new FileVersionsTable(_))
