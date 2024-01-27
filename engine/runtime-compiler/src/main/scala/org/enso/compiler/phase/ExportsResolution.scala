package org.enso.compiler.phase

import org.enso.compiler.data.BindingsMap
import org.enso.compiler.data.BindingsMap.ModuleReference.Concrete
import org.enso.compiler.data.BindingsMap.{
  ExportedModule,
  ImportTarget,
  ResolvedModule,
  SymbolRestriction
}
import org.enso.compiler.context.CompilerContext
import org.enso.compiler.context.CompilerContext.Module

import scala.collection.mutable

/** An exception signaling a loop in the export statements.
  * @param modules the modules forming the cycle.
  */
case class ExportCycleException(modules: List[Module])
    extends Exception(
      "Compilation aborted due to a cycle in export statements."
    )

class ExportsResolution(private val context: CompilerContext) {

  private case class Edge(
    exporter: Node,
    symbols: SymbolRestriction,
    exportsAs: Option[String],
    exportee: Node
  )

  private case class Node(
    module: ImportTarget
  ) {
    var exports: List[Edge]    = List()
    var exportedBy: List[Edge] = List()
  }

  private def getBindings(module: Module): BindingsMap = module.getBindingsMap()

  private def buildGraph(modules: List[Module]): List[Node] = {
    val moduleTargets = modules.map(m => ResolvedModule(Concrete(m)))
    val nodes = mutable.Map[ImportTarget, Node](
      moduleTargets.map(mod => (mod, Node(mod))): _*
    )
    moduleTargets.foreach { module =>
      val compilerModule: Module = module.module.unsafeAsModule()
      val bindings               = getBindings(compilerModule)
      val exports = if (bindings != null) {
        bindings.getDirectlyExportedModules
      } else {
        context.updateModule(
          compilerModule,
          u => {
            u.bindingsMap(null)
            u.invalidateCache()
            u.loadedFromCache(false)
          }
        )
        Nil
      }
      val node = nodes(module)
      node.exports = exports.map {
        case ExportedModule(mod, rename, restriction) =>
          Edge(node, restriction, rename, nodes.getOrElseUpdate(mod, Node(mod)))
      }
      node.exports.foreach { edge => edge.exportee.exportedBy ::= edge }
    }
    nodes.values.toList
  }

  private def findCycles(nodes: List[Node]): List[List[Node]] = {
    val visited: mutable.Set[Node]    = mutable.Set()
    val inProgress: mutable.Set[Node] = mutable.Set()
    var foundCycles: List[List[Node]] = List()
    def go(node: Node): Option[(Node, List[Node])] = {
      if (inProgress.contains(node)) {
        Some((node, List()))
      } else if (visited.contains(node)) {
        None
      } else {
        inProgress.add(node)
        val children        = node.exports.map(_.exportee)
        val childrenResults = children.flatMap(go)
        inProgress.remove(node)
        visited.add(node)
        childrenResults match {
          case List() => None
          case (mod, path) :: _ =>
            if (mod == node) {
              foundCycles = (mod :: path) :: foundCycles
              None
            } else {
              Some((mod, node :: path))
            }
        }

      }
    }
    nodes.foreach(go)
    foundCycles
  }

  private def topsort(nodes: List[Node]): List[Node] = {
    val degrees            = mutable.Map[Node, Int]()
    var result: List[Node] = List()
    nodes.foreach { node =>
      degrees(node) = node.exports.length
    }
    while (degrees.nonEmpty) {
      val q     = mutable.Queue[Node]()
      val entry = degrees.find { case (_, deg) => deg == 0 }.get._1
      q.enqueue(entry)
      while (q.nonEmpty) {
        val item = q.dequeue()
        degrees -= item
        item.exportedBy.foreach { edge =>
          degrees(edge.exporter) -= 1
          if (degrees(edge.exporter) == 0) {
            q.enqueue(edge.exporter)
          }
        }
        result ::= item
      }
    }
    result.reverse
  }

  private def resolveExports(nodes: List[Node]): Unit = {
    val exports = mutable.Map[ImportTarget, List[ExportedModule]]()
    nodes.foreach { node =>
      val explicitlyExported =
        node.exports.map(edge =>
          ExportedModule(
            edge.exportee.module,
            edge.exportsAs,
            edge.symbols
          )
        )
      val transitivelyExported: List[ExportedModule] =
        explicitlyExported.flatMap {
          case ExportedModule(module, _, restriction) =>
            exports(module).map {
              case ExportedModule(export, _, parentRestriction) =>
                ExportedModule(
                  export,
                  None,
                  SymbolRestriction.Intersect(
                    List(restriction, parentRestriction)
                  )
                )
            }
        }
      val allExported = explicitlyExported ++ transitivelyExported
      val unified = allExported
        .groupBy(_.target)
        .map { case (mod, items) =>
          val name = items.collectFirst { case ExportedModule(_, Some(n), _) =>
            n
          }
          val itemsUnion = SymbolRestriction.Union(items.map(_.symbols))
          ExportedModule(mod, name, itemsUnion)
        }
        .toList
      exports(node.module) = unified
    }
    exports.foreach { case (module, exports) =>
      module match {
        case _: BindingsMap.ResolvedType =>
        case ResolvedModule(module) =>
          getBindings(module.unsafeAsModule()).resolvedExports =
            exports.map(ex => ex.copy(symbols = ex.symbols.optimize))
      }
    }
  }

  private def resolveExportedSymbols(modules: List[Module]): Unit = {
    modules.foreach { module =>
      val bindings = getBindings(module)
      val ownEntities =
        bindings.definedEntities
          .filter(_.canExport)
          .map(e => (e.name, List(e.resolvedIn(module))))
      val exportedModules = bindings.resolvedExports.collect {
        case ExportedModule(mod, Some(name), _)
            if mod.module.unsafeAsModule() != module =>
          (name, List(mod))
      }
      val reExportedSymbols = bindings.resolvedExports.flatMap { export =>
        export.target.exportedSymbols
          .flatMap { case (sym, resolutions) =>
            val allowedResolutions =
              resolutions.filter(res => export.symbols.canAccess(sym, res))
            if (allowedResolutions.isEmpty) None
            else Some((sym, allowedResolutions))
          }
      }
      bindings.exportedSymbols = List(
        ownEntities,
        exportedModules,
        reExportedSymbols
      ).flatten.groupBy(_._1).map { case (m, names) =>
        (m, names.flatMap(_._2).distinct)
      }
    }
  }

  /** Performs exports resolution on a selected set of modules.
    *
    * The exports graph is validated and stored in the individual modules,
    * allowing further use.
    *
    * The method returns a list containing the original modules, in
    * a topological order, such that any module exported by a given module
    * comes before it in the list.
    *
    * @param modules the modules to process.
    * @return the original modules, sorted topologically.
    * @throws ExportCycleException when the export statements form a cycle.
    */
  @throws[ExportCycleException]
  def run(modules: List[Module]): List[Module] = {
    val graph  = buildGraph(modules)
    val cycles = findCycles(graph)
    if (cycles.nonEmpty) {
      throw ExportCycleException(
        cycles.head.map(_.module.module.unsafeAsModule())
      )
    }
    val tops = topsort(graph)
    resolveExports(tops)
    val topModules = tops.map(_.module)
    resolveExportedSymbols(tops.map(_.module).collect {
      case m: ResolvedModule => m.module.unsafeAsModule()
    })
    // Take _last_ occurrence of each module
    topModules.map(_.module.unsafeAsModule()).reverse.distinct.reverse
  }

  /** A fast version of [[run]] that does sorting of modules but
    * neither performs cycle checks nor resolves exports.
    */
  def runSort(modules: List[Module]): List[Module] = {
    val graph      = buildGraph(modules)
    val tops       = topsort(graph)
    val topModules = tops.map(_.module)
    topModules.map(_.module.unsafeAsModule()).reverse.distinct.reverse
  }
}
