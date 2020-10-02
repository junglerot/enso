package org.enso.cli.internal.opts

import org.enso.cli.arguments.Opts
import org.enso.cli.internal.ParserContinuation

class HiddenOpts[A](opts: Opts[A]) extends Opts[A] {
  override private[cli] def flags              = opts.flags
  override private[cli] def parameters         = opts.parameters
  override private[cli] def prefixedParameters = opts.prefixedParameters

  override private[cli] val usageOptions: Seq[String]            = Seq()
  override private[cli] def gatherOptions: Seq[(String, String)] = Seq()
  override private[cli] def gatherPrefixedParameters: Seq[(String, String)] =
    Seq()

  override private[cli] def wantsArgument() = opts.wantsArgument()
  override private[cli] def consumeArgument(
    arg: String,
    commandPrefix: Seq[String],
    suppressUnexpectedArgument: Boolean
  ): ParserContinuation =
    opts.consumeArgument(arg, commandPrefix, suppressUnexpectedArgument)

  override private[cli] val requiredArguments: Seq[String]    = Seq()
  override private[cli] val optionalArguments: Seq[String]    = Seq()
  override private[cli] val trailingArguments: Option[String] = None

  override private[cli] val additionalArguments: Option[Seq[String] => Unit] =
    None

  override def availableOptionsHelp(): Seq[String]            = Seq()
  override def availablePrefixedParametersHelp(): Seq[String] = Seq()
  override def additionalHelp(): Seq[String]                  = Seq()

  override private[cli] def reset(): Unit = opts.reset()

  override private[cli] def result(commandPrefix: Seq[String]) =
    opts.result(commandPrefix)
}
