package org.enso.interpreter.test.semantic

import java.io.File

import org.enso.interpreter.Constants
import org.enso.interpreter.runtime.RuntimeOptions
import org.enso.interpreter.test.InterpreterException
import org.enso.interpreter.test.ValueEquality
import org.enso.pkg.Package
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.scalatest.FlatSpec
import org.scalatest.Matchers

trait PackageTest extends FlatSpec with Matchers with ValueEquality {

  def evalTestProject(name: String): Value = {
    val pkgPath =
      new File(getClass.getClassLoader.getResource(name).getPath)
    val mainFile = Package.fromDirectory(pkgPath).get.mainFile
    val context = Context
      .newBuilder(Constants.LANGUAGE_ID)
      .allowExperimentalOptions(true)
      .allowAllAccess(true)
      .option(RuntimeOptions.getPackagesPathOption, pkgPath.getAbsolutePath)
      .out(System.out)
      .in(System.in)
      .build()
    InterpreterException.rethrowPolyglot(
      context.eval(Source.newBuilder(Constants.LANGUAGE_ID, mainFile).build)
    )
  }
}
