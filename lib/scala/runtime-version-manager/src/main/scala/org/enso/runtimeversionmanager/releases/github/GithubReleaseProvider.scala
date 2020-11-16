package org.enso.runtimeversionmanager.releases.github

import org.enso.cli.task.TaskProgress
import org.enso.runtimeversionmanager.releases.{Release, SimpleReleaseProvider}

import scala.util.Try

/** Implements [[SimpleReleaseProvider]] providing releases from a specified GitHub
  * repository using the GitHub Release API.
  *
  * @param owner owner of the repository
  * @param repositoryName name of the repository
  */
class GithubReleaseProvider(
  owner: String,
  repositoryName: String
) extends SimpleReleaseProvider {
  private val repo = GithubAPI.Repository(owner, repositoryName)

  /** @inheritdoc
    */
  override def releaseForTag(tag: String): Try[Release] =
    TaskProgress.waitForTask(GithubAPI.getRelease(repo, tag)).map(GithubRelease)

  /** @inheritdoc
    */
  override def listReleases(): Try[Seq[Release]] =
    GithubAPI.listReleases(repo).map(_.map(GithubRelease))
}
