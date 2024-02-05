package org.enso.compiler.context

import org.enso.compiler.PackageRepository
import org.enso.compiler.data.CompilerConfig
import org.enso.compiler.pass.PassConfiguration
import org.enso.pkg.Package;
import org.enso.pkg.QualifiedName;
import com.oracle.truffle.api.source.Source
import org.enso.compiler.data.BindingsMap.ModuleReference

/** A type containing the information about the execution context for a module.
  *
  * @param module the current module scope
  * @param freshNameSupply the compiler's supply of fresh names
  * @param passConfiguration the pass configuration
  * @param compilerConfig the compiler configuration
  * @param isGeneratingDocs if true, should generate docs for IR
  * @param pkgRepo the compiler's package repository
  */
case class ModuleContext(
  private val module: CompilerContext.Module,
  compilerConfig: CompilerConfig,
  freshNameSupply: Option[FreshNameSupply]     = None,
  passConfiguration: Option[PassConfiguration] = None,
  isGeneratingDocs: Boolean                    = false,
  pkgRepo: Option[PackageRepository]           = None
) {
  def isSynthetic()            = module.isSynthetic()
  def bindingsAnalysis()       = module.getBindingsMap()
  def getName(): QualifiedName = module.getName()
  def getPackage(): Package[_] = module.getPackage()
  def getSource(): Source      = module.getSource()
  def moduleReference(): ModuleReference.Concrete =
    ModuleReference.Concrete(module)
}
