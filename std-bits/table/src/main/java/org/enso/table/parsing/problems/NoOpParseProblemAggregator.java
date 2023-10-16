package org.enso.table.parsing.problems;

/** A problem aggregator which ignores problems. */
public final class NoOpParseProblemAggregator implements ParseProblemAggregator {

  @Override
  public void reportInvalidFormat(String cell) {}

  @Override
  public void reportMismatchedQuote(String cellText) {}

  @Override
  public boolean hasProblems() {
    throw new IllegalStateException("This implementation does not provide problem information.");
  }
}
