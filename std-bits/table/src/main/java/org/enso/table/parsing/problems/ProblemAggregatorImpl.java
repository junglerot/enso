package org.enso.table.parsing.problems;

import org.enso.table.problems.Problem;

import java.util.ArrayList;
import java.util.List;

public class ProblemAggregatorImpl implements ProblemAggregator {
  public final String relatedColumnName;
  private final List<String> invalidFormatCells = new ArrayList<>();
  private final List<String> leadingZerosCells = new ArrayList<>();

  public ProblemAggregatorImpl(String relatedColumnName) {
    this.relatedColumnName = relatedColumnName;
  }

  @Override
  public void reportInvalidFormat(String cell) {
    invalidFormatCells.add(cell);
  }

  @Override
  public void reportMismatchedQuote(String cellText) {
    throw new MismatchedQuote(cellText);
  }

  @Override
  public boolean hasProblems() {
    return !invalidFormatCells.isEmpty() || !leadingZerosCells.isEmpty();
  }

  @Override
  public List<Problem> getAggregatedProblems() {
    List<Problem> problems = new ArrayList<>();

    if (!invalidFormatCells.isEmpty()) {
      problems.add(new InvalidFormat(relatedColumnName, invalidFormatCells));
    }

    assert problems.isEmpty() == !hasProblems();

    return problems;
  }
}
