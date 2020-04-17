package org.enso.compiler.pass.analyse

import org.enso.compiler.InlineContext
import org.enso.compiler.core.IR
import org.enso.compiler.exception.CompilerError
import org.enso.compiler.pass.IRPass
import org.enso.compiler.pass.analyse.AliasAnalysis.Graph.{Occurrence, Scope}
import org.enso.syntax.text.Debug

import scala.reflect.ClassTag

/** This pass performs scope identification and analysis, as well as symbol
  * resolution where it is possible to do so statically.
  *
  * It attaches the following information to the IR:
  *
  * - Top-level constructs are annotated with an aliasing graph.
  * - Scopes within each top-level construct are annotated with the
  *   corresponding child scope.
  * - Occurrences of symbols are annotated with occurrence information that
  *   points into the graph.
  *
  * The analysis process explicitly compensates for some deficiencies in our
  * underlying IR representation by collapsing certain sets of scopes into each
  * other. The collapsing takes place under the following circumstances:
  *
  * - A lambda whose body is a block does not allocate an additional scope for
  *   the block.
  * - A method whose body is a block does not allocate an additional scope for
  *   the block.
  * - A method whose body is a lambda does not allocate an additional scope for
  *   the lambda.
  * - A method whose body is a lambda containing a block as its body allocates
  *   no additional scope for the lambda or the block.
  */
case object AliasAnalysis extends IRPass {

  /** Alias information for the IR. */
  override type Metadata = Info

  /** Performs alias analysis on a module.
    *
    * @param ir the Enso IR to process
    * @return `ir`, possibly having made transformations or annotations to that
    *         IR.
    */
  override def runModule(ir: IR.Module): IR.Module = {
    ir.copy(bindings = ir.bindings.map(analyseModuleDefinition))
  }

  /** Performs alias analysis on an inline expression, starting from the
    * provided scope.
    *
    * @param ir the Enso IR to process
    * @param inlineContext a context object that contains the information needed
    *                      for inline evaluation
    * @return `ir`, possibly having made transformations or annotations to that
    *         IR.
    */
  override def runExpression(
    ir: IR.Expression,
    inlineContext: InlineContext
  ): IR.Expression =
    inlineContext.localScope
      .map { localScope =>
        val scope = localScope.scope
        val graph = localScope.aliasingGraph
        analyseExpression(ir, graph, scope)
      }
      .getOrElse(
        throw new CompilerError(
          "Local scope must be provided for alias analysis."
        )
      )

  /** Performs alias analysis on the module-level definitions.
    *
    * Each module level definition is assigned its own aliasing graph, as under
    * the current dynamic semantics of the language we cannot statically resolve
    * aliasing between top-level constructs within or between modules.
    *
    * @param ir the module-level definition to perform alias analysis on
    * @return `ir`, with the results of alias analysis attached
    */
  def analyseModuleDefinition(
    ir: IR.Module.Scope.Definition
  ): IR.Module.Scope.Definition = {
    val topLevelGraph = new Graph

    ir match {
      case m @ IR.Module.Scope.Definition.Method(_, _, body, _, _) =>
        body match {
          case _: IR.Function =>
            m.copy(
                body = analyseExpression(
                  body,
                  topLevelGraph,
                  topLevelGraph.rootScope,
                  lambdaReuseScope = true,
                  blockReuseScope  = true
                )
              )
              .addMetadata(Info.Scope.Root(topLevelGraph))
          case _ =>
            throw new CompilerError(
              "The body of a method should always be a function."
            )
        }
      case a @ IR.Module.Scope.Definition.Atom(_, args, _, _) =>
        a.copy(
            arguments =
              analyseArgumentDefs(args, topLevelGraph, topLevelGraph.rootScope)
          )
          .addMetadata(Info.Scope.Root(topLevelGraph))
    }
  }

  /** Performs alias analysis on an expression.
    *
    * An expression is assumed to be a child of an aliasing `graph`, and the
    * analysis takes place in the context of said graph.
    *
    * It should be noted that not _all_ expressions are annotated with aliasing
    * information. Please see the pass header documentation for more details.
    *
    * @param expression the expression to perform alias analysis on
    * @param graph the aliasing graph in which the analysis is being performed
    * @param parentScope the parent scope for this expression
    * @param lambdaReuseScope whether to reuse the parent scope for a lambda
    *                         instead of creating a new scope
    * @param blockReuseScope whether to reuse the parent scope for a block
    *                        instead of creating a new scope
    * @return `expression`, potentially with aliasing information attached
    */
  def analyseExpression(
    expression: IR.Expression,
    graph: Graph,
    parentScope: Scope,
    lambdaReuseScope: Boolean = false,
    blockReuseScope: Boolean  = false
  ): IR.Expression = {
    expression match {
      case fn: IR.Function =>
        analyseFunction(fn, graph, parentScope, lambdaReuseScope)
      case name: IR.Name => analyseName(name, graph, parentScope)
      case cse: IR.Case =>
        analyseCase(cse, graph, parentScope)
      case block @ IR.Expression.Block(expressions, retVal, _, _, _) =>
        val currentScope =
          if (blockReuseScope) parentScope else parentScope.addChild()

        block
          .copy(
            expressions = expressions.map((expression: IR.Expression) =>
              analyseExpression(
                expression,
                graph,
                currentScope
              )
            ),
            returnValue = analyseExpression(
              retVal,
              graph,
              currentScope
            )
          )
          .addMetadata(Info.Scope.Child(graph, currentScope))
      case binding @ IR.Expression.Binding(name, expression, _, _) =>
        if (!parentScope.hasSymbolOccurrenceAs[Occurrence.Def](name.name)) {
          val isSuspended  = expression.isInstanceOf[IR.Expression.Block]
          val occurrenceId = graph.nextId()
          val occurrence =
            Occurrence.Def(occurrenceId, name.name, isSuspended)

          parentScope.add(occurrence)

          binding
            .copy(
              expression = analyseExpression(
                expression,
                graph,
                parentScope
              )
            )
            .addMetadata(Info.Occurrence(graph, occurrenceId))
        } else {
          IR.Error.Redefined.Binding(binding)
        }
      case app: IR.Application =>
        analyseApplication(app, graph, parentScope)
      case x =>
        x.mapExpressions((expression: IR.Expression) =>
          analyseExpression(
            expression,
            graph,
            parentScope
          )
        )
    }
  }

  // TODO [AA] Argument redefinition errors shouldn't work like this. Consider
  //  the fact that multi-argument lambdas don't actually exist, so something
  //  like `a b a -> a + b` is actually `a -> b -> a -> a + b`.
  /** Performs alias analysis on the argument definitions for a function.
    *
    * Care is taken during this analysis to ensure that spurious resolutions do
    * not happen regarding the ordering of arguments. Only the arguments
    * declared _earlier_ in the arguments list are considered to be in scope for
    *
    * This method _may_ replace an argument with a
    * [[IR.Error.Redefined.Argument]] error if `args` redefines an argument
    * name. Please note that this is _not representative_ of the intended
    * language semantics, and will need to be rectified at a later date.
    *
    * @param args the list of arguments to perform analysis on
    * @param graph the graph in which the analysis is taking place
    * @param scope the scope of the function for which `args` are being
    *                      defined
    * @return `args`, potentially
    */
  def analyseArgumentDefs(
    args: List[IR.DefinitionArgument],
    graph: Graph,
    scope: Scope
  ): List[IR.DefinitionArgument] = {
    args.map {
      case arg @ IR.DefinitionArgument.Specified(name, value, isSusp, _, _) =>
        val nameOccursInScope =
          scope.hasSymbolOccurrenceAs[Occurrence.Def](name.name)
        if (!nameOccursInScope) {
          val occurrenceId = graph.nextId()
          scope.add(Graph.Occurrence.Def(occurrenceId, name.name, isSusp))

          arg
            .copy(
              defaultValue = value.map((ir: IR.Expression) =>
                analyseExpression(
                  ir,
                  graph,
                  scope
                )
              )
            )
            .addMetadata(Info.Occurrence(graph, occurrenceId))
        } else {
          IR.Error.Redefined.Argument(arg)
        }
      case err: IR.Error.Redefined.Argument => err
    }
  }

  /** Performs alias analysis on a function application.
    *
    * @param application the function application to analyse
    * @param graph the graph in which the analysis is taking place
    * @param scope the scope in which the application is happening
    * @return `application`, possibly with aliasing information attached
    */
  def analyseApplication(
    application: IR.Application,
    graph: AliasAnalysis.Graph,
    scope: AliasAnalysis.Graph.Scope
  ): IR.Application = {
    application match {
      case app @ IR.Application.Prefix(fun, arguments, _, _, _) =>
        app.copy(
          function  = analyseExpression(fun, graph, scope),
          arguments = analyseCallArguments(arguments, graph, scope)
        )
      case app @ IR.Application.Force(expr, _, _) =>
        app.copy(target = analyseExpression(expr, graph, scope))
      case _: IR.Application.Operator.Binary =>
        throw new CompilerError(
          "Binary operator occurred during Alias Analysis."
        )

    }
  }

  /** Performs alias analysis on function call arguments.
    *
    * @param args the list of arguments to analyse
    * @param graph the graph in which the analysis is taking place
    * @param parentScope the scope in which the arguments are defined
    * @return `args`, with aliasing information attached to each argument
    */
  def analyseCallArguments(
    args: List[IR.CallArgument],
    graph: AliasAnalysis.Graph,
    parentScope: AliasAnalysis.Graph.Scope
  ): List[IR.CallArgument] = {
    args.map {
      case arg @ IR.CallArgument.Specified(_, expr, _, _, _) =>
        val currentScope = expr match {
          case _: IR.Literal => parentScope
          case _             => parentScope.addChild()
        }

        arg
          .copy(value = analyseExpression(expr, graph, currentScope))
          .addMetadata(Info.Scope.Child(graph, currentScope))
    }
  }

  /** Performs alias analysis on a function definition.
    *
    * @param function the function to analyse
    * @param graph the graph in which the analysis is taking place
    * @param parentScope the scope in which the function is declared
    * @param lambdaReuseScope whether or not the lambda should reuse the parent
    *                         scope or allocate a child of it
    * @return `function`, with alias analysis information attached
    */
  def analyseFunction(
    function: IR.Function,
    graph: Graph,
    parentScope: Scope,
    lambdaReuseScope: Boolean = false
  ): IR.Function = {
    val currentScope =
      if (lambdaReuseScope) parentScope else parentScope.addChild()

    function match {
      case lambda @ IR.Function.Lambda(arguments, body, _, _, _) =>
        lambda
          .copy(
            arguments = analyseArgumentDefs(arguments, graph, currentScope),
            body = analyseExpression(
              body,
              graph,
              currentScope,
              blockReuseScope = true
            )
          )
          .addMetadata(Info.Scope.Child(graph, currentScope))
    }
  }

  /** Performs alias analysis for a name.
    *
    * @param name the name to analyse
    * @param graph the graph in which the analysis is taking place
    * @param parentScope the scope in which `name` is delcared
    * @return `name`, with alias analysis information attached
    */
  def analyseName(
    name: IR.Name,
    graph: Graph,
    parentScope: Scope
  ): IR.Name = {
    val occurrenceId = graph.nextId()
    val occurrence   = Occurrence.Use(occurrenceId, name.name)

    parentScope.add(occurrence)
    graph.resolveUsage(occurrence)

    name.addMetadata(Info.Occurrence(graph, occurrenceId))
  }

  /** Performs alias analysis on a case expression.
    *
    * @param ir the case expression to analyse
    * @param graph the graph in which the analysis is taking place
    * @param parentScope the scope in which the case expression occurs
    * @return `ir`, possibly with alias analysis information attached
    */
  def analyseCase(
    ir: IR.Case,
    graph: Graph,
    parentScope: Scope
  ): IR.Case = {
    ir match {
      case caseExpr @ IR.Case.Expr(scrutinee, branches, fallback, _, _) =>
        caseExpr.copy(
          scrutinee = analyseExpression(scrutinee, graph, parentScope),
          branches = branches.map(branch =>
            branch.copy(
              pattern = analyseExpression(branch.pattern, graph, parentScope),
              expression =
                analyseExpression(branch.expression, graph, parentScope)
            )
          ),
          fallback = fallback.map(analyseExpression(_, graph, parentScope))
        ) //.addMetadata(Info.Scope.Child(graph, currentScope))
      case _ => throw new CompilerError("Case branch in `analyseCase`.")
    }
  }

  // === Data Definitions =====================================================

  /** Information about the aliasing state for a given IR node. */
  sealed trait Info extends IR.Metadata
  object Info {
    sealed trait Scope extends Info
    object Scope {

      /** Aliasing information for a root scope.
        *
        * A root scope has a 1:1 correspondence with a top-level binding.
        *
        * @param graph the graph containing the alias information for that node
        */
      sealed case class Root(graph: Graph) extends Scope

      /** Aliasing information about a child scope.
        *
        * @param graph the graph
        * @param scope the child scope in `graph`
        */
      sealed case class Child(graph: Graph, scope: Graph.Scope) extends Scope
    }

    /** Aliasing information for a piece of [[IR]] that is contained within a
      * [[Scope]].
      *
      * @param graph the graph in which this IR node can be found
      * @param id the identifier of this IR node in `graph`
      */
    sealed case class Occurrence(graph: Graph, id: Graph.Id) extends Info
  }

  /** A graph containing aliasing information for a given root scope in Enso. */
  sealed class Graph {
    var links: Set[Graph.Link] = Set()
    var rootScope: Graph.Scope = new Graph.Scope()

    private var nextIdCounter = 0

    /** Generates a new identifier for a node in the graph.
      *
      * @return a unique identifier for this graph
      */
    def nextId(): Graph.Id = {
      val nextId = nextIdCounter
      nextIdCounter += 1
      nextId
    }

    /** Resolves any links for the given usage of a symbol.
      *
      * @param occurrence the symbol usage
      * @return the link, if it exists
      */
    def resolveUsage(
      occurrence: Graph.Occurrence.Use
    ): Option[Graph.Link] = {
      val scope = scopeFor(occurrence.id)

      scope.flatMap {
        _.resolveUsage(occurrence)
          .flatMap(link => {
            links += link
            Some(link)
          })
      }
    }

    /** Returns a string representation of the graph.
      *
      * @return a string representation of `this`
      */
    override def toString: String =
      s"Graph(links = $links, rootScope = $rootScope)"

    /** Pretty prints the graph.
      *
      * @return a pretty-printed string representation of the graph
      */
    def pprint: String = {
      val original = toString
      Debug.pretty(original)
    }

    /** Gets all links in which the provided `id` is a participant.
      *
      * @param id the identifier for the symbol
      * @return a list of links in which `id` occurs
      */
    def linksFor(id: Graph.Id): Set[Graph.Link] = {
      links.filter(l => l.source == id || l.target == id)
    }

    /** Finds all links in the graph where `symbol` appears in the role
      * specified by `T`.
      *
      * @param symbol the symbol to find links for
      * @tparam T the role in which `symbol` should occur
      * @return a set of all links in which `symbol` occurs with role `T`
      */
    def linksFor[T <: Occurrence: ClassTag](
      symbol: Graph.Symbol
    ): Set[Graph.Link] = {
      val idsForSym = rootScope.symbolToIds[T](symbol)

      links.filter(l =>
        idsForSym.contains(l.source) || idsForSym.contains(l.target)
      )
    }

    /** Obtains the occurrence for a given ID, from whichever scope in which it
      * occurs.
      *
      * @param id the occurrence identifier
      * @return the occurrence for `id`, if it exists
      */
    def getOccurrence(id: Graph.Id): Option[Occurrence] =
      scopeFor(id).flatMap(_.getOccurrence(id))

    /** Gets the link from an id to the definition of the symbol it represents.
      *
      * @param id the identifier to find the definition link for
      * @return the definition link for `id` if it exists
      */
    def defLinkFor(id: Graph.Id): Option[Graph.Link] = {
      linksFor(id).find { edge =>
        val occ = getOccurrence(edge.target)
        occ match {
          case Some(Occurrence.Def(_, _, _)) => true
          case _                             => false
        }
      }
    }

    /** Gets the scope where a given ID is defined in the graph.
      *
      * @param id the id to find the scope for
      * @return the scope where `id` occurs
      */
    def scopeFor(id: Graph.Id): Option[Graph.Scope] = {
      rootScope.scopeFor(id)
    }

    /** Finds the scopes in which a name occurs with a given role.
      *
      * @param symbol the symbol
      * @tparam T the role in which `symbol` occurs
      * @return all the scopes where `symbol` occurs with role `T`
      */
    def scopesFor[T <: Graph.Occurrence: ClassTag](
      symbol: Graph.Symbol
    ): List[Graph.Scope] = {
      rootScope.scopesForSymbol[T](symbol)
    }

    /** Counts the number of scopes in this scope.
      *
      * @return the number of scopes that are either this scope or children of
      *         it
      */
    def numScopes: Int = {
      rootScope.scopeCount
    }

    /** Determines the maximum nesting depth of scopes through this scope.
      *
      * @return the maximum nesting depth of scopes through this scope.
      */
    def nesting: Int = {
      rootScope.maxNesting
    }

    /** Determines if the provided ID shadows any other bindings.
      *
      * @param id the occurrence identifier
      * @return `true` if `id` shadows other bindings, otherwise `false`
      */
    def shadows(id: Graph.Id): Boolean = {
      scopeFor(id)
        .flatMap(
          _.getOccurrence(id).flatMap {
            case d: Occurrence.Def => Some(d)
            case _                 => None
          }
        )
        .isDefined
    }

    /** Determines if the provided symbol shadows any other bindings.
      *
      * @param symbol the symbol
      * @return `true` if `symbol` shadows other bindings, otherwise `false`
      */
    def shadows(symbol: Graph.Symbol): Boolean = {
      scopesFor[Occurrence.Def](symbol).nonEmpty
    }

    /** Determines if the provided id is linked to a binding that shadows
      * another binding.
      *
      * @param id the identifier to check
      * @return `true` if the definition of the symbol for `id` shadows another
      *        binding for the same symbol, `false`, otherwise
      */
    def linkedToShadowingBinding(id: Graph.Id): Boolean = {
      defLinkFor(id).isDefined
    }

    /** Gets all symbols defined in the graph.
      *
      * @return the set of symbols defined in this graph
      */
    def symbols: Set[Graph.Symbol] = {
      rootScope.symbols
    }

    /** Goes from a symbol to all identifiers that relate to that symbol in
      * the role specified by `T`.
      *
      * @param symbol the symbol to find identifiers for
      * @tparam T the role in which `symbol` should occur
      * @return a list of identifiers for that symbol
      */
    def symbolToIds[T <: Occurrence: ClassTag](
      symbol: Graph.Symbol
    ): List[Graph.Id] = {
      rootScope.symbolToIds[T](symbol)
    }

    /** Goes from an identifier to the associated symbol.
      *
      * @param id the identifier of an occurrence
      * @return the symbol associated with `id`, if it exists
      */
    def idToSymbol(id: Graph.Id): Option[Graph.Symbol] = {
      rootScope.idToSymbol(id)
    }
  }
  object Graph {

    /** The type of symbols on the graph. */
    type Symbol = String

    /** The type of identifiers on the graph. */
    type Id = Int

    /** A representation of a local scope in Enso.
      *
      * @param childScopes all scopes that are _direct_ children of `this`
      * @param occurrences all symbol occurrences in `this` scope
      */
    sealed class Scope(
      var childScopes: List[Scope]     = List(),
      var occurrences: Set[Occurrence] = Set()
    ) {
      var parent: Option[Scope] = None

      /** Creates and returns a scope that is a child of this one.
        *
        * @return a scope that is a child of `this`
        */
      def addChild(): Scope = {
        val scope = new Scope()
        scope.parent = Some(this)
        childScopes ::= scope

        scope
      }

      /** Adds the specified symbol occurrence to this scope.
        *
        * @param occurrence the occurrence to add
        */
      def add(occurrence: Occurrence): Unit = {
        occurrences += occurrence
      }

      /** Finds an occurrence for the provided ID in the current scope, if it
        * exists.
        *
        * @param id the occurrence identifier
        * @return the occurrence for `id`, if it exists
        */
      def getOccurrence(id: Graph.Id): Option[Occurrence] = {
        occurrences.find(o => o.id == id)
      }

      /** Finds any occurrences for the provided symbol in the current scope, if
        * it exists.
        *
        * @param symbol the symbol of the occurrence
        * @tparam T the role for the symbol
        * @return the occurrences for `name`, if they exist
        */
      def getOccurrences[T <: Occurrence: ClassTag](
        symbol: Graph.Symbol
      ): Set[Occurrence] = {
        occurrences.collect {
          case o: T if o.symbol == symbol => o
        }
      }

      /** Unsafely gets the occurrence for the provided ID in the current scope.
        *
        * Please note that this will crash if the ID is not defined in this
        * scope.
        *
        * @param id the occurrence identifier
        * @return the occurrence for `id`
        */
      def unsafeGetOccurrence(id: Graph.Id): Occurrence = {
        getOccurrence(id).get
      }

      /** Checks whether a symbol occurs in a given role in the current scope.
        *
        * @param symbol the symbol to check for
        * @tparam T the role for it to occur in
        * @return `true` if `symbol` occurs in role `T` in this scope, `false`
        *         otherwise
        */
      def hasSymbolOccurrenceAs[T <: Occurrence: ClassTag](
        symbol: Graph.Symbol
      ): Boolean = {
        occurrences.collect { case x: T if x.symbol == symbol => x }.nonEmpty
      }

      /** Resolves usages of symbols into links where possible, creating an edge
        * from the usage site to the definition site.
        *
        * @param occurrence the symbol usage
        * @param parentCounter the number of scopes that the link has traversed
        * @return the link from `occurrence` to the definition of that symbol, if it
        *         exists
        */
      def resolveUsage(
        occurrence: Graph.Occurrence.Use,
        parentCounter: Int = 0
      ): Option[Graph.Link] = {
        val definition = occurrences.find {
          case Graph.Occurrence.Def(_, n, _) => n == occurrence.symbol
          case _                             => false
        }

        definition match {
          case None =>
            parent.flatMap(_.resolveUsage(occurrence, parentCounter + 1))
          case Some(target) =>
            Some(Graph.Link(occurrence.id, parentCounter, target.id))
        }
      }

      /** Creates a string representation of the scope.
        *
        * @return a string representation of `this`
        */
      override def toString: String =
        s"Scope(occurrences = $occurrences, childScopes = $childScopes)"

      /** Counts the number of scopes in this scope.
        *
        * @return the number of scopes that are either this scope or children of
        *         it
        */
      def scopeCount: Int = {
        childScopes.map(_.scopeCount).sum + 1
      }

      /** Determines the maximum nesting depth of scopes through this scope.
        *
        * @return the maximum nesting depth of scopes through this scope.
        */
      def maxNesting: Int = {
        childScopes.map(_.maxNesting).foldLeft(0)(Math.max) + 1
      }

      /** Gets the scope where a given ID is defined in the graph.
        *
        * @param id the id to find the scope for
        * @return the scope where `id` occurs
        */
      def scopeFor(id: Graph.Id): Option[Scope] = {
        val possibleCandidates = occurrences.filter(o => o.id == id)

        if (possibleCandidates.size == 1) {
          Some(this)
        } else if (possibleCandidates.isEmpty) {
          val childCandidates = childScopes.map(_.scopeFor(id)).collect {
            case Some(scope) => scope
          }

          if (childCandidates.length == 1) {
            Some(childCandidates.head)
          } else if (childCandidates.isEmpty) {
            None
          } else {
            throw new CompilerError(s"ID $id defined in multiple scopes.")
          }
        } else {
          throw new CompilerError(s"Multiple occurrences found for ID $id.")
        }
      }

      /** Gets the n-th parent of `this` scope.
        *
        * @param n the number of scopes to walk up
        * @return the n-th parent of `this` scope, if present
        */
      def nThParent(n: Int): Option[Scope] = {
        if (n == 0) Some(this) else this.parent.flatMap(_.nThParent(n - 1))
      }

      /** Finds the scopes in which a symbol occurs with a given role.
        *
        * Users of this function _must_ explicitly specify `T`, otherwise the
        * results will be an empty list.
        *
        * @param symbol the symbol
        * @tparam T the role in which `name` occurs
        * @return all the scopes where `name` occurs with role `T`
        */
      def scopesForSymbol[T <: Occurrence: ClassTag](
        symbol: Graph.Symbol
      ): List[Scope] = {
        val occursInThisScope = hasSymbolOccurrenceAs[T](symbol)

        val occurrencesInChildScopes =
          childScopes.flatMap(_.scopesForSymbol[T](symbol))

        if (occursInThisScope) {
          this +: occurrencesInChildScopes
        } else {
          occurrencesInChildScopes
        }
      }

      /** Gets the set of all symbols in this scope and its children.
        *
        * @return the set of symbols
        */
      def symbols: Set[Graph.Symbol] = {
        val symbolsInThis        = occurrences.map(_.symbol)
        val symbolsInChildScopes = childScopes.flatMap(_.symbols)

        symbolsInThis ++ symbolsInChildScopes
      }

      /** Goes from a symbol to all identifiers that relate to that symbol in
        * the role specified by `T`.
        *
        * @param symbol the symbol to find identifiers for
        * @tparam T the role in which `symbol` should occur
        * @return a list of identifiers for that symbol
        */
      def symbolToIds[T <: Occurrence: ClassTag](
        symbol: Graph.Symbol
      ): List[Graph.Id] = {
        val scopes =
          scopesForSymbol[T](symbol).flatMap(_.getOccurrences[T](symbol))
        scopes.map(_.id)
      }

      /** Goes from an identifier to the associated symbol.
        *
        * @param id the identifier of an occurrence
        * @return the symbol associated with `id`, if it exists
        */
      def idToSymbol(id: Graph.Id): Option[Graph.Symbol] = {
        scopeFor(id).flatMap(_.getOccurrence(id)).map(_.symbol)
      }

      /** Checks if `this` scope is a child of the provided `scope`.
        *
        * @param scope the potential parent scope
        * @return `true` if `this` is a child of `scope`, otherwise `false`
        */
      def isChildOf(scope: Scope): Boolean = {
        val isDirectChildOf = scope.childScopes.contains(this)

        val isChildOfChildren = scope.childScopes
          .map(scope => this.isChildOf(scope))
          .foldLeft(false)(_ || _)

        isDirectChildOf || isChildOfChildren
      }
    }

    /** A link in the [[Graph]].
      *
      * The source of the link should always be an [[Occurrence.Use]] while the
      * target of the link should always be an [[Occurrence.Def]].
      *
      * @param source the source ID of the link in the graph
      * @param scopeCount the number of scopes that the link traverses
      * @param target the target ID of the link in the graph
      */
    sealed case class Link(source: Id, scopeCount: Int, target: Id)

    /** An occurrence of a given symbol in the aliasing graph. */
    sealed trait Occurrence {
      val id: Id
      val symbol: Graph.Symbol
    }
    object Occurrence {

      /** The definition of a symbol in the aliasing graph.
        *
        * @param id the identifier of the name in the graph
        * @param symbol the text of the name
        */
      sealed case class Def(
        id: Id,
        symbol: Graph.Symbol,
        isLazy: Boolean = false
      ) extends Occurrence

      /** A usage of a symbol in the aliasing graph
        *
        * Name usages _need not_ correspond to name definitions, as dynamic
        * symbol resolution means that a name used at runtime _may not_ be
        * statically visible in the scope.
        *
        * @param id the identifier of the name in the graph
        * @param symbol the text of the name
        */
      sealed case class Use(id: Id, symbol: Graph.Symbol) extends Occurrence
    }
  }
}
