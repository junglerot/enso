package org.enso.table.parsing;

import org.enso.table.data.column.storage.Storage;
import org.enso.table.parsing.problems.CommonParseProblemAggregator;
import org.enso.table.parsing.problems.ParseProblemAggregator;

/** A base type for a parser capable of parsing a column of text values into some other type. */
public abstract class DatatypeParser {
  /**
   * Parses a single cell.
   *
   * @param text the text contents to parse, it will never be null in the default implementation -
   *     null values are just passed as-is without any parsing attempts by default
   * @param problemAggregator an instance of the problem aggregator, used for reporting parsing
   *     problems
   * @return the parsed value or null if the value could not be parsed or could be parsed but should
   *     be treated as missing value
   */
  public abstract Object parseSingleValue(String text, ParseProblemAggregator problemAggregator);

  /**
   * Parses a column of texts (represented as a {@code StringStorage}) and returns a new storage,
   * containing the parsed elements.
   */
  public abstract Storage<?> parseColumn(
      Storage<String> sourceStorage, CommonParseProblemAggregator problemAggregator);
}
