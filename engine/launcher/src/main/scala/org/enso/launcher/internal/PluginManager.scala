package org.enso.launcher.internal

import java.nio.file.{Files, Path}

import org.enso.cli.{CommandHelp, PluginBehaviour, PluginNotFound}
import org.enso.launcher.FileSystem

import scala.sys.process._
import scala.util.Try

/**
  * Implements an [[org.enso.cli.PluginManager]] using the given
  * [[Environment]].
  */
class PluginManager(env: Environment) extends org.enso.cli.PluginManager {

  /**
    * Checks if the provided name represents a valid plugin and tries to run it.
    *
    * @param name name of the plugin
    * @param args arguments that should be passed to it
    */
  override def tryRunningPlugin(
    name: String,
    args: Seq[String]
  ): PluginBehaviour =
    findPlugin(name) match {
      case Some(PluginDescription(commandName, _)) =>
        val exitCode = (Seq(commandName) ++ args).!
        sys.exit(exitCode)
      case None =>
        PluginNotFound
    }

  private val pluginPrefix           = "enso-"
  private val synopsisOption: String = "--synopsis"

  /**
    * Traverses all directories in the system PATH, looking for executable files
    * which names start with `enso-`. A valid plugin must support a `synopsis`
    * option, i.e. running `enso-foo --synopsis` should return a short
    * description of the plugin and return with exit code 0 for the plugin to be
    * considered valid.
    */
  override def pluginsHelp(): Seq[CommandHelp] = {
    def isIgnored(directory: Path): Boolean =
      env.getIgnoredPathDirectories.exists(directory.startsWith)

    for {
      directory <- env.getSystemPath if Files.isDirectory(directory)
      if !isIgnored(directory)
      file <- FileSystem.listDirectory(directory)
      if Files.isExecutable(file)
      pluginName  <- pluginNameForExecutable(file.getFileName.toString)
      description <- findPlugin(pluginName)
    } yield CommandHelp(pluginName, description.synopsis)
  }

  override def pluginsNames(): Seq[String] = pluginsHelp().map(_.name)

  case class PluginDescription(executableName: String, synopsis: String)

  /**
    * Checks if the plugin with the given name is installed and valid.
    *
    * It tries to execute it (checking various command extensions depending on
    * the OS) and check if it returns a synopsis.
    *
    * @param name name of the plugin
    * @return [[PluginDescription]] containing the command name that should be
    *        used to call the plugin and its synopsis
    */
  private def findPlugin(name: String): Option[PluginDescription] = {
    def canonicalizeDescription(description: String): String =
      description.replace("\n", " ").trim
    val noOpLogger = new ProcessLogger {
      override def out(s: => String): Unit = {}
      override def err(s: => String): Unit = {}
      override def buffer[T](f: => T): T = f
    }

    def getSynopsis(commandName: String): Option[String] = {
      val command = Seq(commandName, synopsisOption)
      Try(command.!!(noOpLogger)).toOption.map(canonicalizeDescription)
    }

    for (commandName <- pluginCommandsForName(name)) {
      val synopsis = getSynopsis(commandName)
      synopsis match {
        case Some(value) => return Some(PluginDescription(commandName, value))
        case None        =>
      }
    }

    None
  }

  /**
    * Returns a sequence of possible commands a plugin with the given name may
    * be called by.
    */
  private def pluginCommandsForName(name: String): Seq[String] =
    Seq(pluginPrefix + name) ++
    env.getPluginExtensions.map(ext => pluginPrefix + name + ext)

  private def pluginNameForExecutable(executableName: String): Option[String] =
    if (executableName.startsWith(pluginPrefix)) {
      Some(stripPlatformSuffix(executableName.stripPrefix(pluginPrefix)))
    } else None

  private def stripPlatformSuffix(executableName: String): String = {
    val extension =
      env.getPluginExtensions.find(executableName.endsWith)
    extension match {
      case Some(extension) => executableName.stripSuffix(extension)
      case None            => executableName
    }
  }
}
