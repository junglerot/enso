package org.enso.compiler.core.ir

import org.enso.compiler.core.{ConstantsNames, IR}
import org.enso.compiler.core.IR.{randomId, Identifier, ToStringHelper}
import org.enso.syntax.text.Location

/** Enso names. */
trait Name extends Expression with IRKind.Primitive {
  val name: String

  /** @inheritdoc */
  override def mapExpressions(fn: Expression => Expression): Name

  /** @inheritdoc */
  override def setLocation(location: Option[IdentifiedLocation]): Name

  /** @inheritdoc */
  override def duplicate(
    keepLocations: Boolean   = true,
    keepMetadata: Boolean    = true,
    keepDiagnostics: Boolean = true,
    keepIdentifiers: Boolean = false
  ): Name

  /** Checks whether a name is a call-site method name.
    *
    * @return `true` if the name was created through a method call
    */
  def isMethod: Boolean = false

}

object Name {

  /** A representation of a method reference of the form `Type_Path.method`.
    *
    * @param typePointer the type name
    * @param methodName  the method on `typeName`
    * @param location    the source location that the node corresponds to
    * @param passData    the pass metadata associated with this node
    * @param diagnostics compiler diagnostics for this node
    */
  sealed case class MethodReference(
    typePointer: Option[Name],
    methodName: Name,
    override val location: Option[IdentifiedLocation],
    override val passData: MetadataStorage      = MetadataStorage(),
    override val diagnostics: DiagnosticStorage = DiagnosticStorage()
  ) extends Name
      with IRKind.Sugar {

    override val name: String             = showCode()
    override protected var id: Identifier = randomId

    /** Creates a copy of `this`.
      *
      * @param typePointer the type name
      * @param methodName  the method on `typeName`
      * @param location    the source location that the node corresponds to
      * @param passData    the pass metadata associated with this node
      * @param diagnostics compiler diagnostics for this node
      * @param id          the identifier for the new node
      * @return a copy of `this`, updated with the specified values
      */
    def copy(
      typePointer: Option[Name]            = typePointer,
      methodName: Name                     = methodName,
      location: Option[IdentifiedLocation] = location,
      passData: MetadataStorage            = passData,
      diagnostics: DiagnosticStorage       = diagnostics,
      id: Identifier                       = id
    ): MethodReference = {
      val res =
        MethodReference(
          typePointer,
          methodName,
          location,
          passData,
          diagnostics
        )
      res.id = id
      res
    }

    /** @inheritdoc */
    override def duplicate(
      keepLocations: Boolean   = true,
      keepMetadata: Boolean    = true,
      keepDiagnostics: Boolean = true,
      keepIdentifiers: Boolean = false
    ): MethodReference =
      copy(
        typePointer = typePointer.map(
          _.duplicate(
            keepLocations,
            keepMetadata,
            keepDiagnostics,
            keepIdentifiers
          )
        ),
        methodName = methodName.duplicate(
          keepLocations,
          keepMetadata,
          keepDiagnostics,
          keepIdentifiers
        ),
        location = if (keepLocations) location else None,
        passData = if (keepMetadata) passData.duplicate else MetadataStorage(),
        diagnostics =
          if (keepDiagnostics) diagnostics.copy else DiagnosticStorage(),
        id = if (keepIdentifiers) id else randomId
      )

    /** @inheritdoc */
    override def mapExpressions(
      fn: Expression => Expression
    ): MethodReference =
      copy(
        typePointer = typePointer.map(_.mapExpressions(fn)),
        methodName  = methodName.mapExpressions(fn)
      )

    /** @inheritdoc */
    override def setLocation(
      location: Option[IdentifiedLocation]
    ): MethodReference = {
      copy(location = location)
    }

    /** @inheritdoc */
    override def toString: String =
      s"""
         |Name.MethodReference(
         |typePointer = $typePointer,
         |methodName = $methodName,
         |location = $location,
         |passData = $passData,
         |diagnostics = $diagnostics,
         |id = $id
         |)
         |""".toSingleLine

    /** @inheritdoc */
    override def children: List[IR] =
      typePointer.map(_ :: methodName :: Nil).getOrElse(methodName :: Nil)

    /** @inheritdoc */
    override def showCode(indent: Int): String = {
      val tPointer = typePointer.map(_.showCode(indent) + ".").getOrElse("")
      s"$tPointer${methodName.showCode(indent)}"
    }

    /** Checks whether `this` and `that` reference the same method.
      *
      * @param that the other method reference to check against
      * @return `true`, if `this` and `that` represent the same method,
      *         otherwise `false`
      */
    def isSameReferenceAs(that: MethodReference): Boolean = {
      val sameTypePointer = typePointer
        .map(thisTp =>
          that.typePointer.map(_.name == thisTp.name).getOrElse(false)
        )
        .getOrElse(that.typePointer.isEmpty)
      sameTypePointer && (methodName.name == that.methodName.name)
    }
  }

  object MethodReference {

    /** Generates a location for the reference from the segments.
      *
      * @param segments the reference segments
      * @return a location for the method reference
      */
    def genLocation(segments: List[Name]): Option[IdentifiedLocation] = {
      segments.foldLeft(None: Option[IdentifiedLocation])(
        (identLoc, segment) => {
          identLoc.flatMap(loc => {
            Some(
              IdentifiedLocation(
                Location(
                  loc.location.start,
                  segment.location
                    .flatMap(l => Some(l.location.end))
                    .getOrElse(loc.location.end)
                )
              )
            )
          })
        }
      )
    }
  }

  /** A representation of a qualified (multi-part) name.
    *
    * @param parts       the segments of the name
    * @param location    the source location that the node corresponds to
    * @param passData    the pass metadata associated with this node
    * @param diagnostics compiler diagnostics for this node
    * @return a copy of `this`, updated with the specified values
    */
  sealed case class Qualified(
    parts: List[Name],
    override val location: Option[IdentifiedLocation],
    override val passData: MetadataStorage      = MetadataStorage(),
    override val diagnostics: DiagnosticStorage = DiagnosticStorage()
  ) extends Name
      with IRKind.Primitive {

    override val name: String = parts.map(_.name).mkString(".")

    override def mapExpressions(fn: Expression => Expression): Name = this

    override def setLocation(location: Option[IdentifiedLocation]): Name =
      copy(location = location)

    /** Creates a copy of `this`.
      *
      * @param parts       the segments of the name
      * @param location    the source location that the node corresponds to
      * @param passData    the pass metadata associated with this node
      * @param diagnostics compiler diagnostics for this node
      * @param id          the identifier for the new node
      * @return a copy of `this`, updated with the specified values
      */
    def copy(
      parts: List[Name]                    = parts,
      location: Option[IdentifiedLocation] = location,
      passData: MetadataStorage            = passData,
      diagnostics: DiagnosticStorage       = diagnostics,
      id: Identifier                       = id
    ): Qualified = {
      val res =
        Qualified(
          parts,
          location,
          passData,
          diagnostics
        )
      res.id = id
      res
    }

    /** @inheritdoc */
    override def duplicate(
      keepLocations: Boolean   = true,
      keepMetadata: Boolean    = true,
      keepDiagnostics: Boolean = true,
      keepIdentifiers: Boolean = false
    ): Qualified =
      copy(
        parts = parts.map(
          _.duplicate(
            keepLocations,
            keepMetadata,
            keepDiagnostics,
            keepIdentifiers
          )
        ),
        location = if (keepLocations) location else None,
        passData = if (keepMetadata) passData.duplicate else MetadataStorage(),
        diagnostics =
          if (keepDiagnostics) diagnostics.copy else DiagnosticStorage(),
        id = if (keepIdentifiers) id else randomId
      )

    /** @inheritdoc */
    override def children: List[IR] = parts

    /** @inheritdoc */
    override protected var id: Identifier = randomId

    /** @inheritdoc */
    override def showCode(indent: Int): String = name
  }

  /** Represents occurrences of blank (`_`) expressions.
    *
    * @param location    the source location that the node corresponds to.
    * @param passData    the pass metadata associated with this node
    * @param diagnostics compiler diagnostics for this node
    */
  sealed case class Blank(
    override val location: Option[IdentifiedLocation],
    override val passData: MetadataStorage      = MetadataStorage(),
    override val diagnostics: DiagnosticStorage = DiagnosticStorage()
  ) extends Name
      with IRKind.Sugar {
    override val name: String             = "_"
    override protected var id: Identifier = randomId

    /** Creates a copy of `this`.
      *
      * @param location    the source location that the node corresponds to.
      * @param passData    the pass metadata associated with this node
      * @param diagnostics compiler diagnostics for this node
      * @param id          the identifier for the node
      * @return a copy of `this`, updated with the specified values
      */
    def copy(
      location: Option[IdentifiedLocation] = location,
      passData: MetadataStorage            = passData,
      diagnostics: DiagnosticStorage       = diagnostics,
      id: Identifier                       = id
    ): Blank = {
      val res = Blank(location, passData, diagnostics)
      res.id = id
      res
    }

    /** @inheritdoc */
    override def duplicate(
      keepLocations: Boolean   = true,
      keepMetadata: Boolean    = true,
      keepDiagnostics: Boolean = true,
      keepIdentifiers: Boolean = false
    ): Blank =
      copy(
        location = if (keepLocations) location else None,
        passData = if (keepMetadata) passData.duplicate else MetadataStorage(),
        diagnostics =
          if (keepDiagnostics) diagnostics.copy else DiagnosticStorage(),
        id = if (keepIdentifiers) id else randomId
      )

    /** @inheritdoc */
    override def mapExpressions(fn: Expression => Expression): Blank =
      this

    /** @inheritdoc */
    override def setLocation(location: Option[IdentifiedLocation]): Blank =
      copy(location = location)

    /** @inheritdoc */
    override def toString: String =
      s"""
         |Name.Blank(
         |location = $location,
         |passData = ${this.showPassData},
         |diagnostics = $diagnostics,
         |id = $id
         |)
         |""".stripMargin

    /** @inheritdoc */
    override def children: List[IR] = List()

    /** @inheritdoc */
    override def showCode(indent: Int): String = "_"
  }

  sealed case class Special(
    specialName: Special.Ident,
    override val location: Option[IdentifiedLocation],
    override val passData: MetadataStorage      = MetadataStorage(),
    override val diagnostics: DiagnosticStorage = DiagnosticStorage()
  ) extends Name
      with IRKind.Sugar {
    override val name: String             = s"<special::${specialName}>"
    override protected var id: Identifier = randomId

    /** Creates a copy of `this`.
      *
      * @param location    the source location that the node corresponds to.
      * @param passData    the pass metadata associated with this node
      * @param diagnostics compiler diagnostics for this node
      * @param id          the identifier for the node
      * @return a copy of `this`, updated with the specified values
      */
    def copy(
      specialName: Special.Ident           = specialName,
      location: Option[IdentifiedLocation] = location,
      passData: MetadataStorage            = passData,
      diagnostics: DiagnosticStorage       = diagnostics,
      id: Identifier                       = id
    ): Special = {
      val res = Special(specialName, location, passData, diagnostics)
      res.id = id
      res
    }

    override def duplicate(
      keepLocations: Boolean   = true,
      keepMetadata: Boolean    = true,
      keepDiagnostics: Boolean = true,
      keepIdentifiers: Boolean = false
    ): Special =
      copy(
        location = if (keepLocations) location else None,
        passData = if (keepMetadata) passData.duplicate else MetadataStorage(),
        diagnostics =
          if (keepDiagnostics) diagnostics.copy else DiagnosticStorage(),
        id = if (keepIdentifiers) id else randomId
      )

    override def mapExpressions(fn: Expression => Expression): Special =
      this

    override def setLocation(location: Option[IdentifiedLocation]): Special =
      copy(location = location)

    override def toString: String =
      s"""
         |Name.Special(
         |specialName = $specialName,
         |location = $location,
         |passData = ${this.showPassData},
         |diagnostics = $diagnostics,
         |id = $id
         |)
         |""".stripMargin

    override def children: List[IR] = List()

    override def showCode(indent: Int): String = name
  }

  object Special {
    sealed trait Ident

    case object NewRef extends Ident

    case object ReadRef extends Ident

    case object WriteRef extends Ident

    case object RunThread extends Ident

    case object JoinThread extends Ident
  }

  /** The representation of a literal name.
    *
    * @param name        the literal text of the name
    * @param isMethod    is this a method call name
    * @param location    the source location that the node corresponds to
    * @param passData    the pass metadata associated with this node
    * @param diagnostics compiler diagnostics for this node
    */
  sealed case class Literal(
    override val name: String,
    override val isMethod: Boolean,
    override val location: Option[IdentifiedLocation],
    override val passData: MetadataStorage      = MetadataStorage(),
    override val diagnostics: DiagnosticStorage = DiagnosticStorage()
  ) extends Name {
    override protected var id: Identifier = randomId

    /** Creates a copy of `this`.
      *
      * @param name        the literal text of the name
      * @param isMethod    is this a method call name
      * @param location    the source location that the node corresponds to
      * @param passData    the pass metadata associated with this node
      * @param diagnostics compiler diagnostics for this node
      * @param id          the identifier for the new node
      * @return a copy of `this`, updated with the specified values
      */
    def copy(
      name: String                         = name,
      isMethod: Boolean                    = isMethod,
      location: Option[IdentifiedLocation] = location,
      passData: MetadataStorage            = passData,
      diagnostics: DiagnosticStorage       = diagnostics,
      id: Identifier                       = id
    ): Literal = {
      val res =
        Literal(name, isMethod, location, passData, diagnostics)
      res.id = id
      res
    }

    /** @inheritdoc */
    override def duplicate(
      keepLocations: Boolean   = true,
      keepMetadata: Boolean    = true,
      keepDiagnostics: Boolean = true,
      keepIdentifiers: Boolean = false
    ): Literal =
      copy(
        location = if (keepLocations) location else None,
        passData = if (keepMetadata) passData.duplicate else MetadataStorage(),
        diagnostics =
          if (keepDiagnostics) diagnostics.copy else DiagnosticStorage(),
        id = if (keepIdentifiers) id else randomId
      )

    /** @inheritdoc */
    override def setLocation(location: Option[IdentifiedLocation]): Literal =
      copy(location = location)

    /** @inheritdoc */
    override def mapExpressions(fn: Expression => Expression): Literal = this

    /** @inheritdoc */
    override def toString: String =
      s"""
         |Name.Literal(
         |name = $name,
         |isMethod = $isMethod,
         |location = $location,
         |passData = ${this.showPassData},
         |diagnostics = $diagnostics,
         |id = $id
         |)
         |""".toSingleLine

    /** @inheritdoc */
    override def children: List[IR] = List()

    /** @inheritdoc */
    override def showCode(indent: Int): String = name
  }

  /** Base trait for annotations. */
  sealed trait Annotation extends Name with module.scope.Definition {

    /** @inheritdoc */
    override def mapExpressions(fn: Expression => Expression): Annotation

    /** @inheritdoc */
    override def setLocation(location: Option[IdentifiedLocation]): Annotation

    /** @inheritdoc */
    override def duplicate(
      keepLocations: Boolean   = true,
      keepMetadata: Boolean    = true,
      keepDiagnostics: Boolean = true,
      keepIdentifiers: Boolean = false
    ): Annotation
  }

  /** The representation of builtin annotation.
    *
    * @param name        the annotation text of the name
    * @param location    the source location that the node corresponds to
    * @param passData    the pass metadata associated with this node
    * @param diagnostics compiler diagnostics for this node
    */
  sealed case class BuiltinAnnotation(
    override val name: String,
    override val location: Option[IdentifiedLocation],
    override val passData: MetadataStorage      = MetadataStorage(),
    override val diagnostics: DiagnosticStorage = DiagnosticStorage()
  ) extends Annotation
      with IRKind.Primitive {
    override protected var id: Identifier = randomId

    /** Creates a copy of `this`.
      *
      * @param name        the annotation text of the name
      * @param location    the source location that the node corresponds to
      * @param passData    the pass metadata associated with this node
      * @param diagnostics compiler diagnostics for this node
      * @param id          the identifier for the new node
      * @return a copy of `this`, updated with the specified values
      */
    def copy(
      name: String                         = name,
      location: Option[IdentifiedLocation] = location,
      passData: MetadataStorage            = passData,
      diagnostics: DiagnosticStorage       = diagnostics,
      id: Identifier                       = id
    ): BuiltinAnnotation = {
      val res = BuiltinAnnotation(name, location, passData, diagnostics)
      res.id = id
      res
    }

    /** @inheritdoc */
    override def duplicate(
      keepLocations: Boolean   = true,
      keepMetadata: Boolean    = true,
      keepDiagnostics: Boolean = true,
      keepIdentifiers: Boolean = false
    ): BuiltinAnnotation =
      copy(
        location = if (keepLocations) location else None,
        passData = if (keepMetadata) passData.duplicate else MetadataStorage(),
        diagnostics =
          if (keepDiagnostics) diagnostics.copy else DiagnosticStorage(),
        id = if (keepIdentifiers) id else randomId
      )

    /** @inheritdoc */
    override def setLocation(
      location: Option[IdentifiedLocation]
    ): BuiltinAnnotation =
      copy(location = location)

    /** @inheritdoc */
    override def mapExpressions(
      fn: Expression => Expression
    ): BuiltinAnnotation =
      this

    /** @inheritdoc */
    override def toString: String =
      s"""
         |Name.BuiltinAnnotation(
         |name = $name,
         |location = $location,
         |passData = ${this.showPassData},
         |diagnostics = $diagnostics,
         |id = $id
         |)
         |""".toSingleLine

    /** @inheritdoc */
    override def children: List[IR] = List()

    /** @inheritdoc */
    override def showCode(indent: Int): String = s"@$name"
  }

  /** Common annotations of form `@name expression`.
    *
    * @param name        the annotation text of the name
    * @param expression  the annotation expression
    * @param location    the source location that the node corresponds to
    * @param passData    the pass metadata associated with this node
    * @param diagnostics compiler diagnostics for this node
    */
  sealed case class GenericAnnotation(
    override val name: String,
    expression: Expression,
    override val location: Option[IdentifiedLocation],
    override val passData: MetadataStorage      = MetadataStorage(),
    override val diagnostics: DiagnosticStorage = DiagnosticStorage()
  ) extends Annotation {
    override protected var id: Identifier = randomId

    /** Creates a copy of `this`.
      *
      * @param name        the annotation text of the name
      * @param expression  the annotation expression
      * @param location    the source location that the node corresponds to
      * @param passData    the pass metadata associated with this node
      * @param diagnostics compiler diagnostics for this node
      * @param id          the identifier for the new node
      * @return a copy of `this`, updated with the specified values
      */
    def copy(
      name: String                         = name,
      expression: Expression               = expression,
      location: Option[IdentifiedLocation] = location,
      passData: MetadataStorage            = passData,
      diagnostics: DiagnosticStorage       = diagnostics,
      id: Identifier                       = id
    ): GenericAnnotation = {
      val res =
        GenericAnnotation(name, expression, location, passData, diagnostics)
      res.id = id
      res
    }

    /** @inheritdoc */
    override def duplicate(
      keepLocations: Boolean   = true,
      keepMetadata: Boolean    = true,
      keepDiagnostics: Boolean = true,
      keepIdentifiers: Boolean = false
    ): GenericAnnotation =
      copy(
        location = if (keepLocations) location else None,
        passData = if (keepMetadata) passData.duplicate else MetadataStorage(),
        diagnostics =
          if (keepDiagnostics) diagnostics.copy else DiagnosticStorage(),
        id = if (keepIdentifiers) id else randomId
      )

    /** @inheritdoc */
    override def setLocation(
      location: Option[IdentifiedLocation]
    ): GenericAnnotation =
      copy(location = location)

    /** @inheritdoc */
    override def mapExpressions(
      fn: Expression => Expression
    ): GenericAnnotation =
      copy(expression = fn(expression))

    /** @inheritdoc */
    override def toString: String =
      s"""
         |Name.GenericAnnotation(
         |name = $name,
         |expression = $expression,
         |location = $location,
         |passData = ${this.showPassData},
         |diagnostics = $diagnostics,
         |id = $id
         |)
         |""".toSingleLine

    /** @inheritdoc */
    override def children: List[IR] = List(expression)

    /** @inheritdoc */
    override def showCode(indent: Int): String =
      s"@$name ${expression.showCode(indent)}"
  }

  /** A representation of the name `self`, used to refer to the current type.
    *
    * @param location    the source location that the node corresponds to
    * @param passData    the pass metadata associated with this node
    * @param diagnostics compiler diagnostics for this node
    */
  sealed case class Self(
    override val location: Option[IdentifiedLocation],
    synthetic: Boolean                          = false,
    override val passData: MetadataStorage      = MetadataStorage(),
    override val diagnostics: DiagnosticStorage = DiagnosticStorage()
  ) extends Name {
    override protected var id: Identifier = randomId
    override val name: String             = ConstantsNames.SELF_ARGUMENT

    /** Creates a copy of `self`.
      *
      * @param location    the source location that the node corresponds to
      * @param passData    the pass metadata associated with this node
      * @param diagnostics compiler diagnostics for this node
      * @param id          the identifier for the new node
      * @return a copy of `this`, updated with the specified values
      */
    def copy(
      location: Option[IdentifiedLocation] = location,
      synthetic: Boolean                   = synthetic,
      passData: MetadataStorage            = passData,
      diagnostics: DiagnosticStorage       = diagnostics,
      id: Identifier                       = id
    ): Self = {
      val res = Self(location, synthetic, passData, diagnostics)
      res.id = id
      res
    }

    /** @inheritdoc */
    override def duplicate(
      keepLocations: Boolean   = true,
      keepMetadata: Boolean    = true,
      keepDiagnostics: Boolean = true,
      keepIdentifiers: Boolean = false
    ): Self =
      copy(
        location = if (keepLocations) location else None,
        passData = if (keepMetadata) passData.duplicate else MetadataStorage(),
        diagnostics =
          if (keepDiagnostics) diagnostics.copy else DiagnosticStorage(),
        id = if (keepIdentifiers) id else randomId
      )

    /** @inheritdoc */
    override def setLocation(location: Option[IdentifiedLocation]): Self =
      copy(location = location)

    /** @inheritdoc */
    override def mapExpressions(fn: Expression => Expression): Self = this

    /** @inheritdoc */
    override def toString: String =
      s"""
         |Name.Self(
         |location = $location,
         |synthetic = $synthetic,
         |passData = ${this.showPassData},
         |diagnostics = $diagnostics,
         |id = $id
         |)
         |""".toSingleLine

    /** @inheritdoc */
    override def children: List[IR] = List()

    /** @inheritdoc */
    override def showCode(indent: Int): String = name
  }

  /** A representation of the name `Self`, used to refer to the current type.
    *
    * @param location    the source location that the node corresponds to
    * @param passData    the pass metadata associated with this node
    * @param diagnostics compiler diagnostics for this node
    */
  sealed case class SelfType(
    override val location: Option[IdentifiedLocation],
    override val passData: MetadataStorage      = MetadataStorage(),
    override val diagnostics: DiagnosticStorage = DiagnosticStorage()
  ) extends Name {
    override protected var id: Identifier = randomId
    override val name: String             = ConstantsNames.SELF_TYPE_ARGUMENT

    /** Creates a copy of `Self`.
      *
      * @param location    the source location that the node corresponds to
      * @param passData    the pass metadata associated with this node
      * @param diagnostics compiler diagnostics for this node
      * @param id          the identifier for the new node
      * @return a copy of `this`, updated with the specified values
      */
    def copy(
      location: Option[IdentifiedLocation] = location,
      passData: MetadataStorage            = passData,
      diagnostics: DiagnosticStorage       = diagnostics,
      id: Identifier                       = id
    ): SelfType = {
      val res = SelfType(location, passData, diagnostics)
      res.id = id
      res
    }

    /** @inheritdoc */
    override def duplicate(
      keepLocations: Boolean   = true,
      keepMetadata: Boolean    = true,
      keepDiagnostics: Boolean = true,
      keepIdentifiers: Boolean = false
    ): SelfType =
      copy(
        location = if (keepLocations) location else None,
        passData = if (keepMetadata) passData.duplicate else MetadataStorage(),
        diagnostics =
          if (keepDiagnostics) diagnostics.copy else DiagnosticStorage(),
        id = if (keepIdentifiers) id else randomId
      )

    /** @inheritdoc */
    override def setLocation(location: Option[IdentifiedLocation]): SelfType =
      copy(location = location)

    /** @inheritdoc */
    override def mapExpressions(fn: Expression => Expression): SelfType = this

    /** @inheritdoc */
    override def toString: String =
      s"""
         |Name.SelfType(
         |location = $location,
         |passData = ${this.showPassData},
         |diagnostics = $diagnostics,
         |id = $id
         |)
         |""".toSingleLine

    /** @inheritdoc */
    override def children: List[IR] = List()

    /** @inheritdoc */
    override def showCode(indent: Int): String = name
  }
}
