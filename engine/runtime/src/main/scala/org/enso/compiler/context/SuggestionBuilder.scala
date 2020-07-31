package org.enso.compiler.context

import org.enso.compiler.core.IR
import org.enso.compiler.pass.resolve.{DocumentationComments, TypeSignatures}
import org.enso.polyglot.Suggestion
import org.enso.syntax.text.Location
import org.enso.text.editing.IndexedSource

import scala.collection.immutable.VectorBuilder
import scala.collection.mutable

/** Module that extracts [[Suggestion]] entries from the [[IR]].
  *
  * @param source the text source
  * @tparam A the type of the text source
  */
final class SuggestionBuilder[A: IndexedSource](val source: A) {

  import SuggestionBuilder._

  /** Build suggestions from the given `ir`.
    *
    * @param module the module name
    * @param ir the input `IR`
    * @return the list of suggestion entries extracted from the given `IR`
    */
  def build(module: String, ir: IR): Vector[Suggestion] = {
    @scala.annotation.tailrec
    def go(
      scope: Scope,
      scopes: mutable.Queue[Scope],
      acc: mutable.Builder[Suggestion, Vector[Suggestion]]
    ): Vector[Suggestion] =
      if (scope.queue.isEmpty) {
        if (scopes.isEmpty) {
          acc.result()
        } else {
          val scope = scopes.dequeue()
          go(scope, scopes, acc)
        }
      } else {
        val ir  = scope.queue.dequeue()
        val doc = ir.getMetadata(DocumentationComments).map(_.documentation)
        ir match {
          case IR.Module.Scope.Definition.Method
                .Explicit(
                  IR.Name.MethodReference(typePtr, methodName, _, _, _),
                  IR.Function.Lambda(args, body, _, _, _, _),
                  _,
                  _,
                  _
                ) =>
            val typeSignature = ir.getMetadata(TypeSignatures)
            acc += buildMethod(
                body.getExternalId,
                module,
                methodName,
                typePtr,
                args,
                doc,
                typeSignature
              )
            scopes += Scope(body.children, body.location.map(_.location))
            go(scope, scopes, acc)
          case IR.Expression.Binding(
                name,
                IR.Function.Lambda(args, body, _, _, _, _),
                _,
                _,
                _
              ) if name.location.isDefined =>
            val typeSignature = ir.getMetadata(TypeSignatures)
            acc += buildFunction(
                body.getExternalId,
                module,
                name,
                args,
                scope.location.get,
                typeSignature
              )
            scopes += Scope(body.children, body.location.map(_.location))
            go(scope, scopes, acc)
          case IR.Expression.Binding(name, expr, _, _, _)
              if name.location.isDefined =>
            val typeSignature = ir.getMetadata(TypeSignatures)
            acc += buildLocal(
                expr.getExternalId,
                module,
                name.name,
                scope.location.get,
                typeSignature
              )
            scopes += Scope(expr.children, expr.location.map(_.location))
            go(scope, scopes, acc)
          case IR.Module.Scope.Definition.Atom(name, arguments, _, _, _) =>
            acc += buildAtom(
                module,
                name.name,
                arguments,
                doc
              )
            go(scope, scopes, acc)
          case _ =>
            go(scope, scopes, acc)
        }
      }

    go(
      Scope(ir.children, ir.location.map(_.location)),
      mutable.Queue(),
      new VectorBuilder()
    )
  }

  private def buildMethod(
    externalId: Option[IR.ExternalId],
    module: String,
    name: IR.Name,
    typeRef: IR.Name,
    args: Seq[IR.DefinitionArgument],
    doc: Option[String],
    typeSignature: Option[TypeSignatures.Metadata]
  ): Suggestion.Method = {
    typeSignature match {
      case Some(TypeSignatures.Signature(typeExpr)) =>
        val selfType = typeRef.name
        val typeSig  = buildTypeSignature(typeExpr)
        val (methodArgs, returnTypeDef) =
          buildMethodArguments(args, typeSig, selfType)
        Suggestion.Method(
          externalId    = externalId,
          module        = module,
          name          = name.name,
          arguments     = methodArgs,
          selfType      = selfType,
          returnType    = buildReturnType(returnTypeDef),
          documentation = doc
        )
      case _ =>
        Suggestion.Method(
          externalId    = externalId,
          module        = module,
          name          = name.name,
          arguments     = args.map(buildArgument),
          selfType      = typeRef.name,
          returnType    = Any,
          documentation = doc
        )
    }
  }

  private def buildFunction(
    externalId: Option[IR.ExternalId],
    module: String,
    name: IR.Name,
    args: Seq[IR.DefinitionArgument],
    location: Location,
    typeSignature: Option[TypeSignatures.Metadata]
  ): Suggestion.Function = {
    typeSignature match {
      case Some(TypeSignatures.Signature(typeExpr)) =>
        val typeSig = buildTypeSignature(typeExpr)
        val (methodArgs, returnTypeDef) =
          buildFunctionArguments(args, typeSig)
        Suggestion.Function(
          externalId = externalId,
          module     = module,
          name       = name.name,
          arguments  = methodArgs,
          returnType = buildReturnType(returnTypeDef),
          scope      = buildScope(location)
        )
      case _ =>
        Suggestion.Function(
          externalId = externalId,
          module     = module,
          name       = name.name,
          arguments  = args.map(buildArgument),
          returnType = Any,
          scope      = buildScope(location)
        )
    }
  }

  private def buildLocal(
    externalId: Option[IR.ExternalId],
    module: String,
    name: String,
    location: Location,
    typeSignature: Option[TypeSignatures.Metadata]
  ): Suggestion.Local =
    typeSignature match {
      case Some(TypeSignatures.Signature(tname: IR.Name)) =>
        Suggestion.Local(
          externalId,
          module,
          name,
          tname.name,
          buildScope(location)
        )
      case _ =>
        Suggestion.Local(externalId, module, name, Any, buildScope(location))
    }

  private def buildAtom(
    module: String,
    name: String,
    arguments: Seq[IR.DefinitionArgument],
    doc: Option[String]
  ): Suggestion.Atom =
    Suggestion.Atom(
      externalId    = None,
      module        = module,
      name          = name,
      arguments     = arguments.map(buildArgument),
      returnType    = name,
      documentation = doc
    )

  private def buildTypeSignature(typeExpr: IR.Expression): Vector[TypeArg] = {
    @scala.annotation.tailrec
    def go(typeExpr: IR.Expression, args: Vector[TypeArg]): Vector[TypeArg] =
      typeExpr match {
        case IR.Function.Lambda(List(targ), body, _, _, _, _) =>
          val tdef = TypeArg(targ.name.name, targ.suspended)
          go(body, args :+ tdef)
        case tname: IR.Name =>
          args :+ TypeArg(tname.name, isSuspended = false)
        case _ =>
          args
      }

    go(typeExpr, Vector())
  }

  private def buildMethodArguments(
    vargs: Seq[IR.DefinitionArgument],
    targs: Seq[TypeArg],
    selfType: String
  ): (Seq[Suggestion.Argument], Option[TypeArg]) = {
    @scala.annotation.tailrec
    def go(
      vargs: Seq[IR.DefinitionArgument],
      targs: Seq[TypeArg],
      acc: Vector[Suggestion.Argument]
    ): (Vector[Suggestion.Argument], Option[TypeArg]) =
      if (vargs.isEmpty) {
        (acc, targs.lastOption)
      } else {
        vargs match {
          case IR.DefinitionArgument.Specified(
                name: IR.Name.This,
                defaultValue,
                suspended,
                _,
                _,
                _
              ) +: vtail =>
            val thisArg = Suggestion.Argument(
              name         = name.name,
              reprType     = selfType,
              isSuspended  = suspended,
              hasDefault   = defaultValue.isDefined,
              defaultValue = defaultValue.flatMap(buildDefaultValue)
            )
            go(vtail, targs, acc :+ thisArg)
          case varg +: vtail =>
            targs match {
              case targ +: ttail =>
                go(vtail, ttail, acc :+ buildTypedArgument(varg, targ))
              case _ =>
                go(vtail, targs, acc :+ buildArgument(varg))
            }
        }
      }

    go(vargs, targs, Vector())
  }

  private def buildFunctionArguments(
    vargs: Seq[IR.DefinitionArgument],
    targs: Seq[TypeArg]
  ): (Seq[Suggestion.Argument], Option[TypeArg]) = {
    @scala.annotation.tailrec
    def go(
      vargs: Seq[IR.DefinitionArgument],
      targs: Seq[TypeArg],
      acc: Vector[Suggestion.Argument]
    ): (Seq[Suggestion.Argument], Option[TypeArg]) =
      if (vargs.isEmpty) {
        (acc, targs.lastOption)
      } else {
        vargs match {
          case varg +: vtail =>
            targs match {
              case targ +: ttail =>
                go(vtail, ttail, acc :+ buildTypedArgument(varg, targ))
              case _ =>
                go(vtail, targs, acc :+ buildArgument(varg))
            }
        }
      }

    go(vargs, targs, Vector())
  }

  private def buildTypedArgument(
    varg: IR.DefinitionArgument,
    targ: TypeArg
  ): Suggestion.Argument =
    Suggestion.Argument(
      name         = varg.name.name,
      reprType     = targ.name,
      isSuspended  = targ.isSuspended,
      hasDefault   = varg.defaultValue.isDefined,
      defaultValue = varg.defaultValue.flatMap(buildDefaultValue)
    )

  private def buildArgument(arg: IR.DefinitionArgument): Suggestion.Argument =
    Suggestion.Argument(
      name         = arg.name.name,
      reprType     = Any,
      isSuspended  = arg.suspended,
      hasDefault   = arg.defaultValue.isDefined,
      defaultValue = arg.defaultValue.flatMap(buildDefaultValue)
    )

  def buildArgument(
    varg: IR.DefinitionArgument,
    targ: Option[TypeArg]
  ): Suggestion.Argument =
    Suggestion.Argument(
      name         = varg.name.name,
      reprType     = targ.fold(Any)(_.name),
      isSuspended  = targ.fold(varg.suspended)(_.isSuspended),
      hasDefault   = varg.defaultValue.isDefined,
      defaultValue = varg.defaultValue.flatMap(buildDefaultValue)
    )

  private def buildReturnType(typeDef: Option[TypeArg]): String =
    typeDef match {
      case Some(TypeArg(name, _)) => name
      case None                   => Any
    }

  private def buildDefaultValue(expr: IR): Option[String] =
    expr match {
      case IR.Literal.Number(value, _, _, _) => Some(value)
      case IR.Literal.Text(text, _, _, _)    => Some(text)
      case _                                 => None
    }

  private def buildScope(location: Location): Suggestion.Scope =
    Suggestion.Scope(toPosition(location.start), toPosition(location.end))

  private def toPosition(index: Int): Suggestion.Position = {
    val pos = IndexedSource[A].toPosition(index, source)
    Suggestion.Position(pos.line, pos.character)
  }
}

object SuggestionBuilder {

  /** Create the suggestion builder.
    *
    * @param source the text source
    * @tparam A the type of the text source
    */
  def apply[A: IndexedSource](source: A): SuggestionBuilder[A] =
    new SuggestionBuilder[A](source)

  /** A single level of an `IR`.
    *
    * @param queue the nodes in the scope
    * @param location the scope location
    */
  private case class Scope(queue: mutable.Queue[IR], location: Option[Location])

  private object Scope {

    /** Create new scope from the list of items. */
    def apply(items: Seq[IR], location: Option[Location]): Scope =
      new Scope(mutable.Queue(items: _*), location)
  }

  /** Type of the argument.
    *
    * @param name the name of the type
    * @param isSuspended is the argument lazy
    */
  private case class TypeArg(name: String, isSuspended: Boolean)

  private val Any: String = "Any"

}
