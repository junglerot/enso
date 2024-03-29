package org.enso.interpreter.service.error;

import java.io.File;
import org.enso.logger.masking.MaskedPath;

/** Thrown when a module for given file was requested but could not be found. */
public class ModuleNotFoundForFileException extends ModuleNotFoundException {

  /**
   * Create new instance of this error.
   *
   * @param path the path of the module file.
   */
  public ModuleNotFoundForFileException(File path) {
    super("Module not found for file " + new MaskedPath(path.toPath()).applyMasking() + ".");
  }
}
