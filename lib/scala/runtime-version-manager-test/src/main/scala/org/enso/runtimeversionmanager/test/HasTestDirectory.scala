package org.enso.runtimeversionmanager.test

import java.nio.file.Path

trait HasTestDirectory {

  /** Defines a (usually temporary) that is to be used for this test. */
  def getTestDirectory: Path
}
