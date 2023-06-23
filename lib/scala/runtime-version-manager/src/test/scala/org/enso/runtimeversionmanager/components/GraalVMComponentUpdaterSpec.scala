package org.enso.runtimeversionmanager.components

import org.enso.cli.OS

import java.nio.file.Path
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.util.{Failure, Success}

class GraalVMComponentUpdaterSpec extends AnyWordSpec with Matchers {

  val javaHomeOpt: Option[Path] = sys.env.get("JAVA_HOME").map(Path.of(_))
  val os: OS                    = OS.operatingSystem

  val vendorVersionOpt: Option[String] =
    sys.props.get("java.vendor.version").map(_.dropWhile(!_.isDigit))

  val javaVersionOpt: Option[String] =
    sys.props.get("java.specification.version")

  val graalVMVersionOpt: Option[GraalVMVersion] =
    for {
      graalVersion <- vendorVersionOpt
      javaVersion  <- javaVersionOpt
    } yield GraalVMVersion(graalVersion, javaVersion)

  val graalRuntime: Option[GraalRuntime] =
    for {
      javaHome       <- javaHomeOpt
      graalVMVersion <- graalVMVersionOpt
    } yield {
      val path = os match {
        case OS.Linux   => javaHome
        case OS.MacOS   => javaHome.getParent.getParent
        case OS.Windows => javaHome
      }
      GraalRuntime(graalVMVersion, path)
    }

  def isCI: Boolean = sys.env.contains("CI")

  def getOrElseFail[A](opt: Option[A]): A = {
    if (opt.isEmpty) {
      if (isCI) {
        throw new Exception(
          s"Error in test environment. " +
          s"OS=$os; " +
          s"JAVA_HOME=$javaHomeOpt; " +
          s"java.vendor.version=$vendorVersionOpt; " +
          s"java.specification.version=$javaVersionOpt"
        )
      } else {
        pending
      }
    }
    opt.get
  }

  "RuntimeComponentUpdater" should {

    "list components" in {
      val graal = getOrElseFail(graalRuntime)
      val ru    = new GraalVMComponentUpdater(graal)

      ru.list() match {
        case Success(components) =>
          val componentIds = components.map(_.id)
          componentIds should (contain("graalvm") and contain("js"))
        case Failure(cause) =>
          fail(cause)
      }

      var maxFailures = 3
      val ruSometimesFailing = new GraalVMComponentUpdater(graal) {
        override def updaterExec: Path = if (maxFailures == 0) super.updaterExec
        else {
          maxFailures = maxFailures - 1
          OS.operatingSystem match {
            case OS.Linux   => Path.of("/bin/false")
            case OS.MacOS   => Path.of("/bin/false")
            case OS.Windows => Path.of("foobar")
          }
        }
      }

      ruSometimesFailing.list() match {
        case Success(components) =>
          val componentIds = components.map(_.id)
          componentIds should (contain("graalvm") and contain("js"))
        case Failure(_) =>
      }

      var attempted = 0
      val ruAlwaysFailing = new GraalVMComponentUpdater(graal) {
        override def updaterExec: Path = {
          attempted = attempted + 1
          OS.operatingSystem match {
            case OS.Linux   => Path.of("/bin/false")
            case OS.MacOS   => Path.of("/bin/false")
            case OS.Windows => Path.of("foobar")
          }
        }
      }

      val expectedRetries = 5
      ruAlwaysFailing.list() match {
        case Success(_) =>
          fail("expected `gu list` to always fail")
        case Failure(_) =>
          if (attempted != (expectedRetries + 1))
            fail(
              s"should have retried ${expectedRetries + 1} times, got $attempted"
            )
      }
    }
  }

}
