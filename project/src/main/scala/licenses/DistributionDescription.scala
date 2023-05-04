package src.main.scala.licenses

import sbtlicensereport.license.LicenseReport
import sbt.File
import sbt.librarymanagement.UpdateReport

/** Describes a component included in the distribution managed by SBT.
  *
  * @param name name of the component
  * @param licenseReport license report generated by the `updateLicenses` task
  * @param classifiedArtifactsReport result of the `updateClassifiers` task
  */
case class SBTDistributionComponent(
  name: String,
  licenseReport: LicenseReport,
  classifiedArtifactsReport: UpdateReport
)

/** Describes an artifact consisting of multiple components that is distributed
  * independently.
  *
  * @param artifactName name of the artifact
  * @param packageDestination location of the generated notice package
  * @param sbtComponents sequence of SBT components that constitute this
  *                      artifact; only root components (components that are
  *                      directly packaged and distributed) are required (i.e.
  *                      if X is distributed and X depends on Y but Y is not
  *                      directly included, it does not have to be on this list
  *                      as it will be automatically discovered)
  */
case class DistributionDescription(
  artifactName: String,
  packageDestination: File,
  sbtComponents: Seq[SBTDistributionComponent]
) {

  /** Returns names of root components included in the distribution.
    */
  def rootComponentsNames: Seq[String] = sbtComponents.map(_.name)
}
