package org.enso.projectmanager.boot

import org.apache.commons.cli
import org.enso.polyglot.LanguageInfo

import scala.util.Try

object Cli {

  val JSON_OPTION    = "json"
  val HELP_OPTION    = "help"
  val NO_LOG_MASKING = "no-log-masking"
  val VERBOSE_OPTION = "verbose"
  val VERSION_OPTION = "version"

  object option {

    val help: cli.Option = cli.Option
      .builder("h")
      .longOpt(HELP_OPTION)
      .desc("Displays this message.")
      .build()

    val verbose: cli.Option = cli.Option
      .builder("v")
      .longOpt(VERBOSE_OPTION)
      .desc("Increase logs verbosity. Can be added multiple times (-vv).")
      .build()

    val version: cli.Option = cli.Option.builder
      .longOpt(VERSION_OPTION)
      .desc("Checks the version of the Enso executable.")
      .build()

    val json: cli.Option = cli.Option.builder
      .longOpt(JSON_OPTION)
      .desc("Switches the --version option to JSON output.")
      .build()

    val noLogMasking: cli.Option = cli.Option.builder
      .longOpt(NO_LOG_MASKING)
      .desc(
        "Disable masking of personally identifiable information in logs. " +
        "Masking can be also disabled with the `NO_LOG_MASKING` environment " +
        "variable."
      )
      .build()
  }

  val options: cli.Options =
    new cli.Options()
      .addOption(option.help)
      .addOption(option.verbose)
      .addOption(option.version)
      .addOption(option.json)
      .addOption(option.noLogMasking)

  /** Parse the command line options. */
  def parse(args: Array[String]): Either[String, cli.CommandLine] = {
    Try(new cli.DefaultParser().parse(options, args)).toEither.left
      .map(_.getMessage)
  }

  /** Print the help message to the standard output. */
  def printHelp(): Unit =
    new cli.HelpFormatter().printHelp(LanguageInfo.ID, options)

}
