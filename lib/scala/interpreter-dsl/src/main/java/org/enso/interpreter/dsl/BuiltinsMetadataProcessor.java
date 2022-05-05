package org.enso.interpreter.dsl;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The base class of Builtins processors. Apart from any associated with a given annotation, such as
 * generating code, {@code BuiltinsMetadataProcessor} detects when the processing of the last
 * annotation in the round is being processed and allows for dumping any collected metadata once.
 */
public abstract class BuiltinsMetadataProcessor extends AbstractProcessor {

  /**
   * Processes annotated elements, generating code for each of them, if necessary.
   *
   * <p>Compared to regular annotations processor, it will detect the last round of processing, read
   * any existing metadata (for diff to handle separate compilation) and call @{code storeMetadata}
   * to dump any metadata, if needed.
   *
   * @param annotations annotation being processed this round.
   * @param roundEnv additional round information.
   * @return {@code true}
   */
  @Override
  public final boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    if (roundEnv.errorRaised()) {
      return false;
    }
    if (roundEnv.processingOver()) {
      // A hack to improve support for separate compilation.
      //
      // Since one cannot open the existing resource file in Append mode,
      // we read the exisitng metadata.
      // Deletes/renaming are still not going to work nicely but that would be the same case
      // if we were writing metadata information per source file anyway.
      Map<String, String> pastEntries;
      try {
        FileObject existingFile =
            processingEnv.getFiler().getResource(StandardLocation.CLASS_OUTPUT, "", metadataPath());
        try (InputStream resource = existingFile.openInputStream()) {
          pastEntries =
              new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))
                  .lines()
                  .collect(Collectors.toMap(l -> l.split(":")[0], Function.identity()));
        }
      } catch (NoSuchFileException notFoundException) {
        // This is the first time we are generating the metadata file, ignore the exception.
        pastEntries = new HashMap<>();
      } catch (Exception e) {
        e.printStackTrace();
        pastEntries = new HashMap<>();
      }
      try {
        FileObject res =
            processingEnv
                .getFiler()
                .createResource(StandardLocation.CLASS_OUTPUT, "", metadataPath());
        Writer writer = res.openWriter();
        try {
          storeMetadata(writer, pastEntries);
          // Dump past entries, to workaround separate compilation + annotation processing issues
          for (String value : pastEntries.values()) {
            writer.append(value + "\n");
          }
        } finally {
          writer.close();
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
      cleanup();
      return true;
    } else {
      return handleProcess(annotations, roundEnv);
    }
  }

  /**
   * A name of the resource where metadata collected during the processing should be written to.
   *
   * @return a relative path to the resource file
   */
  protected abstract String metadataPath();

  /**
   * The method called at the end of the round of annotation processing. It allows to write any
   * collected metadata to a related resoource (see {@link
   * BuiltinsMetadataProcessor#metadataPath()}).
   *
   * @param writer a writer to the metadata resource
   * @param pastEntries entries from the previously created metadata file, if any. Entries that
   *     should not be appended to {@code writer} should be removed
   * @throws IOException
   */
  protected abstract void storeMetadata(Writer writer, Map<String, String> pastEntries)
      throws IOException;

  /**
   * The main body of {@link #process} that needs to be implemented by the processors. The method is
   * called during regular rounds if there are no outstanding errors.
   *
   * @param annotations as in {@link #process}
   * @param roundEnv as in {@link #process}
   * @return as in {@link #process}
   */
  protected abstract boolean handleProcess(
      Set<? extends TypeElement> annotations, RoundEnvironment roundEnv);

  /**
   * Cleanup any metadata information collected during annotation processing. Called when all
   * processing is done.
   */
  protected abstract void cleanup();
}
