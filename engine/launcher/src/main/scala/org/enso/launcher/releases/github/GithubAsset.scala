package org.enso.launcher.releases.github

import java.nio.file.Path

import org.enso.cli.TaskProgress
import org.enso.launcher.releases.Asset

case class GithubAsset(asset: GithubAPI.Asset) extends Asset {

  /**
    * @inheritdoc
    */
  override def fileName: String = asset.name

  /**
    * @inheritdoc
    */
  override def downloadTo(path: Path): TaskProgress[Unit] =
    GithubAPI.downloadAsset(asset, path)

  /**
    * @inheritdoc
    */
  override def fetchAsText(): TaskProgress[String] =
    GithubAPI.fetchTextAsset(asset)
}
