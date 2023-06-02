package org.enso.polyglot

import com.fasterxml.jackson.annotation.{JsonSubTypes, JsonTypeInfo}
import org.enso.logger.masking.ToLogString

import java.util.UUID

/** A search suggestion. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
  Array(
    new JsonSubTypes.Type(
      value = classOf[Suggestion.Module],
      name  = "suggestionModule"
    ),
    new JsonSubTypes.Type(
      value = classOf[Suggestion.Type],
      name  = "suggestionType"
    ),
    new JsonSubTypes.Type(
      value = classOf[Suggestion.Constructor],
      name  = "suggestionConstructor"
    ),
    new JsonSubTypes.Type(
      value = classOf[Suggestion.Method],
      name  = "suggestionMethod"
    ),
    new JsonSubTypes.Type(
      value = classOf[Suggestion.Conversion],
      name  = "suggestionConversion"
    ),
    new JsonSubTypes.Type(
      value = classOf[Suggestion.Function],
      name  = "suggestionFunction"
    ),
    new JsonSubTypes.Type(
      value = classOf[Suggestion.Local],
      name  = "suggestionLocal"
    )
  )
)
sealed trait Suggestion extends ToLogString {

  def externalId:    Option[Suggestion.ExternalId]
  def module:        String
  def name:          String
  def returnType:    String
  def documentation: Option[String]

  /** Set the reexport field of the suggestion. */
  def withReexport(reexport: Option[String]): Suggestion
}

object Suggestion {

  type ExternalId = UUID

  /** @return `true` if the suggestion id defined in the global (module) scope.
    */
  def isGlobal(suggestion: Suggestion): Boolean =
    suggestion match {
      case _: Suggestion.Module      => true
      case _: Suggestion.Type        => true
      case _: Suggestion.Constructor => true
      case _: Suggestion.Method      => true
      case _: Suggestion.Conversion  => true
      case _: Suggestion.Function    => false
      case _: Suggestion.Local       => false
    }

  /** The type of a suggestion. */
  sealed trait Kind
  object Kind {

    def apply(suggestion: Suggestion): Kind =
      suggestion match {
        case _: Module      => Module
        case _: Type        => Type
        case _: Constructor => Constructor
        case _: Method      => Method
        case _: Conversion  => Conversion
        case _: Function    => Function
        case _: Local       => Local
      }

    /** The module suggestion. */
    case object Module extends Kind

    /** The type suggestion. */
    case object Type extends Kind

    /** The constructor suggestion. */
    case object Constructor extends Kind

    /** The method suggestion. */
    case object Method extends Kind

    /** The conversion suggestion. */
    case object Conversion extends Kind {
      val From = "from"
    }

    /** The function suggestion. */
    case object Function extends Kind

    /** The suggestion of a local value. */
    case object Local extends Kind
  }

  /** Arguments extractor. */
  object Arguments {

    def apply(suggestion: Suggestion): Seq[Argument] =
      suggestion match {
        case _: Module                => Seq()
        case tpe: Type                => tpe.params
        case constructor: Constructor => constructor.arguments
        case method: Method           => method.arguments
        case conversion: Conversion   => conversion.arguments
        case function: Function       => function.arguments
        case _: Local                 => Seq()
      }
  }

  /** Self type extractor. */
  object SelfType {

    def apply(suggestion: Suggestion): Option[String] =
      suggestion match {
        case _: Module                => None
        case _: Type                  => None
        case constructor: Constructor => Some(constructor.returnType)
        case method: Method           => Some(method.selfType)
        case conversion: Conversion   => Some(conversion.sourceType)
        case _: Function              => None
        case _: Local                 => None
      }
  }

  /** Annotations extractor. */
  object Annotations {

    def apply(suggestion: Suggestion): Seq[String] =
      suggestion match {
        case _: Module                => Seq()
        case _: Type                  => Seq()
        case constructor: Constructor => constructor.annotations
        case method: Method           => method.annotations
        case _: Conversion            => Seq()
        case _: Function              => Seq()
        case _: Local                 => Seq()
      }
  }

  /** An argument of an atom or a function.
    *
    * @param name the argument name
    * @param reprType the type of the argument
    * @param isSuspended is the argument lazy
    * @param hasDefault does the argument have a default
    * @param defaultValue optional default value
    * @param tagValues optional list of possible values
    */
  case class Argument(
    name: String,
    reprType: String,
    isSuspended: Boolean,
    hasDefault: Boolean,
    defaultValue: Option[String],
    tagValues: Option[Seq[String]] = None
  ) extends ToLogString {

    /** @inheritdoc */
    override def toLogString(shouldMask: Boolean): String =
      "Argument(" +
      s"name=$name," +
      s"reprType=$reprType," +
      s"isSuspended=$isSuspended," +
      s"hasDefault=$hasDefault,defaultValue=" +
      (if (shouldMask) defaultValue.map(_ => STUB) else defaultValue) +
      s",tagValues=" +
      (if (shouldMask) tagValues.map(_ => STUB) else tagValues) +
      ")"
  }

  /** Position in the text.
    *
    * @param line a line position in a document (zero-based).
    * @param character a character offset
    */
  case class Position(line: Int, character: Int)

  /** The definition scope.
    *
    * @param start the start of the definition scope
    * @param end the end of the definition scope
    */
  case class Scope(start: Position, end: Position)

  object Scope {

    /** Creates a scope from the provided suggestion.
      *
      * @param suggestion the provided suggestion
      * @return the scope of the suggestion
      */
    def apply(suggestion: Suggestion): Option[Scope] =
      suggestion match {
        case _: Module          => None
        case _: Type            => None
        case _: Constructor     => None
        case _: Method          => None
        case _: Conversion      => None
        case function: Function => Some(function.scope)
        case local: Local       => Some(local.scope)
      }
  }

  /** A module.
    *
    * @param module the fully qualified module name
    * @param documentation the documentation string
    * @param reexport the module re-exporting this module
    */
  case class Module(
    module: String,
    documentation: Option[String],
    reexport: Option[String] = None
  ) extends Suggestion
      with ToLogString {

    override def name: String =
      module

    override def externalId: Option[ExternalId] =
      None

    override def returnType: String =
      module

    /** @inheritdoc */
    override def withReexport(reexport: Option[String]): Module =
      copy(reexport = reexport)

    /** @inheritdoc */
    override def toLogString(shouldMask: Boolean): String =
      s"Module(module=$module,name=$name,documentation=" +
      (if (shouldMask) documentation.map(_ => STUB) else documentation) +
      s",reexport=$reexport)"
  }

  /** A type definition.
    *
    * @param externalId the external id
    * @param module the module name
    * @param name the atom name
    * @param params the list of parameters
    * @param returnType the type of an atom
    * @param parentType qualified name of the parent type
    * @param documentation the documentation string
    * @param reexport the module re-exporting this atom
    */
  case class Type(
    externalId: Option[ExternalId],
    module: String,
    name: String,
    params: Seq[Argument],
    returnType: String,
    parentType: Option[String],
    documentation: Option[String],
    reexport: Option[String] = None
  ) extends Suggestion
      with ToLogString {

    /** @inheritdoc */
    override def withReexport(reexport: Option[String]): Type =
      copy(reexport = reexport)

    /** @inheritdoc */
    override def toLogString(shouldMask: Boolean): String =
      "Type(" +
      s"externalId=$externalId," +
      s"module=$module," +
      s"name=$name," +
      s"params=${params.map(_.toLogString(shouldMask))}," +
      s"returnType=$returnType" +
      s"parentType=$parentType" +
      s",documentation=" + (if (shouldMask) documentation.map(_ => STUB)
                            else documentation) +
      s",reexport=$reexport)"
  }

  /** A value constructor.
    *
    * @param externalId the external id
    * @param module the module name
    * @param name the constructor name
    * @param arguments the list of arguments
    * @param returnType the type of an atom
    * @param documentation the documentation string
    * @param annotations the list of annotations
    * @param reexport the module re-exporting this atom
    */
  case class Constructor(
    externalId: Option[ExternalId],
    module: String,
    name: String,
    arguments: Seq[Argument],
    returnType: String,
    documentation: Option[String],
    annotations: Seq[String],
    reexport: Option[String] = None
  ) extends Suggestion
      with ToLogString {

    /** @inheritdoc */
    override def withReexport(reexport: Option[String]): Constructor =
      copy(reexport = reexport)

    /** @inheritdoc */
    override def toLogString(shouldMask: Boolean): String =
      "Constructor(" +
      s"externalId=$externalId," +
      s"module=$module," +
      s"name=$name," +
      s"arguments=${arguments.map(_.toLogString(shouldMask))}," +
      s"returnType=$returnType" +
      s",documentation=" + (if (shouldMask) documentation.map(_ => STUB)
                            else documentation) +
      s",reexport=$reexport)"
  }

  /** A function defined on a type or a module.
    *
    * @param externalId the external id
    * @param module the module name
    * @param name the method name
    * @param arguments the list of arguments
    * @param selfType the self type of a method
    * @param returnType the return type of a method
    * @param isStatic the flag indicating whether a method is static or instance
    * @param documentation the documentation string
    * @param annotations the list of annotations
    * @param reexport the module re-exporting this method
    */
  case class Method(
    externalId: Option[ExternalId],
    module: String,
    name: String,
    arguments: Seq[Argument],
    selfType: String,
    returnType: String,
    isStatic: Boolean,
    documentation: Option[String],
    annotations: Seq[String],
    reexport: Option[String] = None
  ) extends Suggestion
      with ToLogString {

    /** @inheritdoc */
    override def withReexport(reexport: Option[String]): Method =
      copy(reexport = reexport)

    /** @inheritdoc */
    override def toLogString(shouldMask: Boolean): String =
      "Method(" +
      s"module=$module," +
      s"name=$name," +
      s"arguments=${arguments.map(_.toLogString(shouldMask))}," +
      s"selfType=$selfType," +
      s"returnType=$returnType," +
      s"isStatic=$isStatic," +
      s"documentation=" + (if (shouldMask) documentation.map(_ => STUB)
                           else documentation) +
      s",reexport=$reexport)"
  }

  /** A conversion function.
    *
    * @param externalId the external id
    * @param module the module name
    * @param arguments the list of arguments
    * @param sourceType the source type of a conversion
    * @param returnType the return type of a conversion
    * @param documentation the documentation string
    * @param reexport the module re-exporting this conversion
    */
  case class Conversion(
    externalId: Option[ExternalId],
    module: String,
    arguments: Seq[Argument],
    sourceType: String,
    returnType: String,
    documentation: Option[String],
    reexport: Option[String] = None
  ) extends Suggestion {

    /** @inheritdoc */
    override def withReexport(reexport: Option[String]): Conversion =
      copy(reexport = reexport)

    /** @inheritdoc */
    override def name: String =
      Kind.Conversion.From

    /** @inheritdoc */
    override def toLogString(shouldMask: Boolean): String =
      "Conversion(" +
      s"module=$module," +
      s"arguments=${arguments.map(_.toLogString(shouldMask))}," +
      s"sourceType=$sourceType," +
      s"returnType=$returnType," +
      s"documentation=" + (if (shouldMask) documentation.map(_ => STUB)
                           else documentation) +
      s",reexport=$reexport)"
  }

  /** A local function definition.
    *
    * @param externalId the external id
    * @param module the module name
    * @param name the function name
    * @param arguments the function arguments
    * @param returnType the return type of a function
    * @param scope the scope where the function is defined
    * @param documentation the documentation string
    */
  case class Function(
    externalId: Option[ExternalId],
    module: String,
    name: String,
    arguments: Seq[Argument],
    returnType: String,
    scope: Scope,
    documentation: Option[String]
  ) extends Suggestion
      with ToLogString {

    /** @inheritdoc */
    override def withReexport(reexport: Option[String]): Function =
      this

    /** @inheritdoc */
    override def toLogString(shouldMask: Boolean): String =
      "Function(" +
      s"externalId=$externalId," +
      s"module=$module," +
      s"name=$name," +
      s"arguments=${arguments.map(_.toLogString(shouldMask))}," +
      s"returnType=$returnType," +
      s"scope=$scope," +
      s"documentation=$documentation" +
      ")"
  }

  /** A local value.
    *
    * @param externalId the external id
    * @param module the module name
    * @param name the name of a value
    * @param returnType the type of a local value
    * @param scope the scope where the value is defined
    * @param documentation the documentation string
    */
  case class Local(
    externalId: Option[ExternalId],
    module: String,
    name: String,
    returnType: String,
    scope: Scope,
    documentation: Option[String]
  ) extends Suggestion {

    /** @inheritdoc */
    override def withReexport(reexport: Option[String]): Local =
      this

    /** @inheritdoc */
    override def toLogString(shouldMask: Boolean): String =
      s"Local(" +
      s"externalId=$externalId," +
      s"module=$module," +
      s"name=$name," +
      s"returnType=$returnType," +
      s"scope=$scope," +
      s"documentation=$documentation" +
      s")"
  }
}
