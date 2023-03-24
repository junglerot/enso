package org.enso.table.data.table.join;

import java.util.List;
import java.util.stream.Collectors;
import org.enso.base.text.TextFoldingStrategy;
import org.enso.table.data.column.storage.Storage;
import org.enso.table.data.index.MultiValueIndex;
import org.enso.table.data.table.Column;
import org.enso.table.data.table.Table;
import org.enso.table.data.table.join.scan.Matcher;
import org.enso.table.data.table.join.scan.MatcherFactory;
import org.enso.table.problems.AggregatedProblems;

public class IndexJoin implements JoinStrategy {
  private record HashEqualityCondition(
      Column left, Column right, TextFoldingStrategy textFoldingStrategy) {}

  @Override
  public JoinResult join(Table left, Table right, List<JoinCondition> conditions) {
    List<HashEqualityCondition> equalConditions =
        conditions.stream()
            .filter(IndexJoin::isSupported)
            .map(IndexJoin::makeHashEqualityCondition)
            .collect(Collectors.toList());

    var remainingConditions =
        conditions.stream().filter(c -> !isSupported(c)).collect(Collectors.toList());

    var leftEquals =
        equalConditions.stream().map(HashEqualityCondition::left).toArray(Column[]::new);
    var rightEquals =
        equalConditions.stream().map(HashEqualityCondition::right).toArray(Column[]::new);
    var textFoldingStrategies =
        equalConditions.stream()
            .map(HashEqualityCondition::textFoldingStrategy)
            .collect(Collectors.toList());

    var leftIndex =
        MultiValueIndex.makeUnorderedIndex(leftEquals, left.rowCount(), textFoldingStrategies);
    var rightIndex =
        MultiValueIndex.makeUnorderedIndex(rightEquals, right.rowCount(), textFoldingStrategies);

    MatcherFactory factory = new MatcherFactory();
    Matcher remainingMatcher = factory.create(remainingConditions);

    JoinResult.Builder resultBuilder = new JoinResult.Builder();
    for (var leftKey : leftIndex.keys()) {
      if (rightIndex.contains(leftKey)) {
        for (var leftRow : leftIndex.get(leftKey)) {
          for (var rightRow : rightIndex.get(leftKey)) {
            if (remainingMatcher.matches(leftRow, rightRow)) {
              resultBuilder.addRow(leftRow, rightRow);
            }
          }
        }
      }
    }

    AggregatedProblems problems =
        AggregatedProblems.merge(leftIndex.getProblems(), rightIndex.getProblems(), remainingMatcher.getProblems());
    return resultBuilder.build(problems);
  }

  private static boolean isSupported(JoinCondition condition) {
    switch (condition) {
      case Equals eq -> {
        return isBuiltinType(eq.left().getStorage()) && isBuiltinType(eq.right().getStorage());
      }
      case EqualsIgnoreCase ignored -> {
        return true;
      }
      default -> {
        return false;
      }
    }
  }

  private static HashEqualityCondition makeHashEqualityCondition(JoinCondition eq) {
    switch (eq) {
      case Equals e -> {
        return new HashEqualityCondition(
            e.left(), e.right(), TextFoldingStrategy.unicodeNormalizedFold);
      }
      case EqualsIgnoreCase e -> {
        return new HashEqualityCondition(
            e.left(), e.right(), TextFoldingStrategy.caseInsensitiveFold(e.locale()));
      }
      default -> throw new IllegalStateException(
          "Impossible: trying to convert condition "
              + eq
              + " to a HashEqualityCondition, but it should not be marked as supported. This is a"
              + " bug in the Table library.");
    }
  }

  private static boolean isBuiltinType(Storage<?> storage) {
    return storage.getType() != Storage.Type.OBJECT;
  }
}
