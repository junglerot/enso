package org.enso.table.read;

import com.univocity.parsers.csv.CsvFormat;
import com.univocity.parsers.csv.CsvParser;
import com.univocity.parsers.csv.CsvParserSettings;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.enso.table.data.column.builder.string.StringStorageBuilder;
import org.enso.table.data.column.storage.Storage;
import org.enso.table.data.column.storage.StringStorage;
import org.enso.table.data.index.DefaultIndex;
import org.enso.table.data.table.Column;
import org.enso.table.data.table.Table;
import org.enso.table.parsing.DatatypeParser;
import org.enso.table.parsing.problems.AdditionalInvalidRows;
import org.enso.table.parsing.problems.InvalidRow;
import org.enso.table.parsing.problems.MismatchedQuote;
import org.enso.table.parsing.problems.ParsingProblem;
import org.enso.table.util.NameDeduplicator;

/** A helper for reading delimited (CSV-like) files. */
public class DelimitedReader {

  /** Specifies how to set the headers for the returned table. */
  public enum HeaderBehavior {
    /** Tries to infer if the headers are present in the file. */
    INFER,

    /** Uses the first row in the file as headers. Duplicate names will be appended suffixes. */
    USE_FIRST_ROW_AS_HEADERS,

    /**
     * Treats the first row as data and generates header names starting with {@code COLUMN_NAME}.
     */
    GENERATE_HEADERS
  }

  private static final String COLUMN_NAME = "Column";

  private final char delimiter;
  private final char quoteCharacter;
  private final char quoteEscapeCharacter;
  private final HeaderBehavior headerBehavior;
  private final long skipRows;
  private final long rowLimit;
  private final int maxColumns;
  private final List<ParsingProblem> warnings = new ArrayList<>();
  private final CsvParser parser;
  private final DatatypeParser valueParser;
  private final boolean keepInvalidRows;
  private final boolean warningsAsErrors;

  private static final char noQuoteCharacter = '\0';

  /**
   * Creates a new reader.
   *
   * @param input a reader providing decoded input characters
   * @param delimiter the delimiter, should be a single character, but is a String for proper
   *     interoperability with Enso; if a string that does not fit in a single character is
   *     provided, an exception is raised
   * @param quote the quote character to use, should be a single character or {@code null}, but is a
   *     String for proper interoperability with Enso; if a string that does not fit in a single
   *     character is provided, an exception is raised
   * @param quoteEscape the quote escape character to use, should be a single character or {@code
   *     null}, but is a * String for proper interoperability with Enso; if a string that does not
   *     fit in a single * character is provided, an exception is raised
   * @param headerBehavior specifies how to set the header for the resulting table
   * @param skipRows specifies how many rows from the input to skip
   * @param rowLimit specifies how many rows to read (does not include the header row)
   * @param maxColumns specifies how many columns can be expected at most
   * @param valueParser an optional parser that is applied to each column to convert it to more
   *     specific datatype
   * @param keepInvalidRows specifies whether to keep rows that had an unexpected number of columns
   * @param warningsAsErrors specifies if the first warning should be immediately raised as an error
   *     (used as a fast-path for the error-reporting mode to avoid computing a value that is going
   *     to be discarded anyway)
   */
  public DelimitedReader(
      Reader input,
      String delimiter,
      String quote,
      String quoteEscape,
      HeaderBehavior headerBehavior,
      long skipRows,
      long rowLimit,
      int maxColumns,
      DatatypeParser valueParser,
      boolean keepInvalidRows,
      boolean warningsAsErrors) {
    if (delimiter.isEmpty()) {
      throw new IllegalArgumentException("Empty delimiters are not supported.");
    }
    if (delimiter.length() > 1) {
      throw new IllegalArgumentException(
          "Delimiters consisting of multiple characters or code units are not supported.");
    }

    this.delimiter = delimiter.charAt(0);

    if (quote != null) {
      if (quote.isEmpty()) {
        throw new IllegalArgumentException(
            "Empty quotes are not supported. Set the quote to `Nothing` to disable quotes.");
      }
      if (quote.length() > 1) {
        throw new IllegalArgumentException(
            "Quotes consisting of multiple characters or code units are not supported.");
      }

      quoteCharacter = quote.charAt(0);
      if (quoteCharacter == noQuoteCharacter) {
        throw new IllegalArgumentException("Illegal quote character.");
      }
    } else {
      quoteCharacter = noQuoteCharacter;
    }

    if (quoteEscape != null) {
      if (quoteEscape.isEmpty()) {
        throw new IllegalArgumentException(
            "Empty quote escapes are not supported. Set the escape to `Nothing` to disable escaping quotes.");
      }
      if (quoteEscape.length() > 1) {
        throw new IllegalArgumentException(
            "Quote escapes consisting of multiple characters or code units are not supported.");
      }

      quoteEscapeCharacter = quoteEscape.charAt(0);
    } else {
      quoteEscapeCharacter = noQuoteCharacter;
    }

    this.headerBehavior = headerBehavior;
    this.skipRows = skipRows;
    this.rowLimit = rowLimit;
    this.maxColumns = maxColumns;
    this.keepInvalidRows = keepInvalidRows;
    this.warningsAsErrors = warningsAsErrors;

    this.valueParser = valueParser;
    parser = setupCsvParser(input);
  }

  /** Creates a {@code CsvParser} according to the settings specified at construction. */
  private CsvParser setupCsvParser(Reader input) {
    CsvParserSettings settings = new CsvParserSettings();
    settings.setHeaderExtractionEnabled(false);
    CsvFormat format = new CsvFormat();
    format.setDelimiter(delimiter);
    format.setQuote(quoteCharacter);
    format.setQuoteEscape(quoteEscapeCharacter);
    settings.setFormat(format);
    settings.setMaxCharsPerColumn(-1);
    settings.setMaxColumns(maxColumns);
    settings.setSkipEmptyLines(false);
    settings.setKeepQuotes(true);
    settings.setLineSeparatorDetectionEnabled(true);
    CsvParser parser = new CsvParser(settings);
    parser.beginParsing(input);
    return parser;
  }

  /** Parses a header cell, removing surrounding quotes (if applicable). */
  private String parseHeader(String cell) {
    if (cell == null) return COLUMN_NAME;
    return QuoteHelper.stripQuotes(quoteCharacter, this::reportMismatchedQuote, cell);
  }

  private void reportMismatchedQuote() {
    reportProblem(new MismatchedQuote());
  }

  private long invalidRowsCount = 0;
  private static final long invalidRowsLimit = 10;

  private void reportInvalidRow(long source_row, Long table_index, String[] row) {
    if (invalidRowsCount < invalidRowsLimit) {
      reportProblem(new InvalidRow(source_row, table_index, row));
    }

    invalidRowsCount++;
  }

  /** Returns a list of currently reported problems encountered when parsing the input. */
  public List<ParsingProblem> getReportedProblems() {
    List<ParsingProblem> result = new ArrayList<>(warnings);
    if (invalidRowsCount > invalidRowsLimit) {
      long additionalInvalidRows = invalidRowsCount - invalidRowsLimit;
      result.add(new AdditionalInvalidRows(additionalInvalidRows));
    }
    return result;
  }

  private void reportProblem(ParsingProblem problem) {
    if (warningsAsErrors) {
      throw new ParsingFailedException(problem);
    } else {
      warnings.add(problem);
    }
  }

  private long target_table_index = 0;

  /** The line number of the start of the current row in the input file. */
  private long current_line = 0;

  /**
   * Reads the next row and updates the current line accordingly.
   *
   * <p>Will return {@code null} if no more rows are available.
   */
  private String[] nextRow() {
    current_line = parser.getContext().currentLine() + 1;
    return parser.parseNext();
  }

  /** Reads the input stream and returns a Table. */
  public Table read() {
    List<String> headerNames;
    String[] currentRow = nextRow();

    // Skip the first N rows.
    for (long i = 0; currentRow != null && i < skipRows; ++i) {
      currentRow = nextRow();
    }

    // If there are no rows to even infer the headers, we return an empty table.
    if (currentRow == null) {
      return new Table(new Column[0]);
    }

    switch (headerBehavior) {
      case INFER:
        throw new IllegalStateException("Inferring headers is not yet implemented");
      case USE_FIRST_ROW_AS_HEADERS:
        List<String> preprocessedHeaders =
            Arrays.stream(currentRow).map(this::parseHeader).collect(Collectors.toList());
        headerNames = NameDeduplicator.deduplicate(preprocessedHeaders, "_");
        // We have 'used up' the first row, so we load a next one.
        currentRow = nextRow();
        break;
      case GENERATE_HEADERS:
        headerNames = new ArrayList<>(currentRow.length);
        for (int i = 0; i < currentRow.length; ++i) {
          headerNames.add(COLUMN_NAME + "_" + (i + 1));
        }
        break;
      default:
        throw new IllegalStateException("Impossible branch.");
    }

    StringStorageBuilder[] builders = initBuilders(headerNames.size());

    while (currentRow != null && (rowLimit < 0 || target_table_index < rowLimit)) {
      if (currentRow.length != builders.length) {
        reportInvalidRow(current_line, keepInvalidRows ? target_table_index : null, currentRow);

        if (keepInvalidRows) {
          for (int i = 0; i < builders.length && i < currentRow.length; i++) {
            builders[i] = builders[i].parseAndAppend(currentRow[i]);
          }

          // If the current row had less columns than expected, nulls are inserted for the missing
          // values.
          // If it had more columns, the excess columns are discarded.
          for (int i = currentRow.length; i < builders.length; i++) {
            builders[i] = builders[i].parseAndAppend(null);
          }

          target_table_index++;
        }
      } else {
        for (int i = 0; i < builders.length; i++) {
          builders[i] = builders[i].parseAndAppend(currentRow[i]);
        }

        target_table_index++;
      }

      currentRow = nextRow();
    }

    parser.stopParsing();

    Column[] columns = new Column[builders.length];
    for (int i = 0; i < builders.length; i++) {
      String columnName = headerNames.get(i);
      StringStorage col = builders[i].seal();

      WithProblems<Storage> parseResult = valueParser.parseColumn(columnName, col);
      for (var problem : parseResult.problems()) {
        reportProblem(problem);
      }
      Storage storage = parseResult.value();

      columns[i] = new Column(columnName, new DefaultIndex(storage.size()), storage);
    }
    return new Table(columns);
  }

  private StringStorageBuilder[] initBuilders(int count) {
    StringStorageBuilder[] res = new StringStorageBuilder[count];
    for (int i = 0; i < count; i++) {
      res[i] = new StringStorageBuilder();
    }
    return res;
  }
}
