package org.enso.table.data.index;

import org.enso.table.aggregations.Aggregator;
import org.enso.table.data.column.builder.object.*;
import org.enso.table.data.column.builder.object.StringBuilder;
import org.enso.table.data.column.storage.Storage;
import org.enso.table.data.table.Column;
import org.enso.table.data.table.Table;
import org.enso.table.data.table.problems.AggregatedProblems;
import org.enso.table.data.table.problems.FloatingPointGrouping;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class MultiValueIndex {
  private final int keyColumnsLength;
  private final Map<MultiValueKey, List<Integer>> locs;
  private final AggregatedProblems problems;

  public MultiValueIndex(Column[] keyColumns, int tableSize) {
    this.keyColumnsLength = keyColumns.length;
    this.locs = new HashMap<>();
    this.problems = new AggregatedProblems();

    if (keyColumns.length != 0) {
      int size = keyColumns[0].getSize();
      for (int i = 0; i < size; i++) {
        int finalI = i;
        MultiValueKey key = new MultiValueKey(Arrays.stream(keyColumns).map(c -> c.getStorage().getItemBoxed(finalI)).toArray());

        if (key.hasFloatValues()) {
          problems.add(new FloatingPointGrouping("GroupBy", i));
        }

        List<Integer> ids = this.locs.computeIfAbsent(key, x -> new ArrayList<>());
        ids.add(i);
      }
    } else {
      this.locs.put(new MultiValueKey(new Object[0]), IntStream.range(0, tableSize).boxed().collect(Collectors.toList()));
    }
  }

  public Table makeTable(Aggregator[] columns) {
    final int length = columns.length;
    final int size = locs.size();

    boolean emptyScenario = size == 0 & keyColumnsLength == 0;
    Builder[] storage = Arrays.stream(columns)
        .map(c -> getBuilderForType(c.getType(), emptyScenario ? 1 : size))
        .toArray(Builder[]::new);

    if (emptyScenario) {
      // No grouping and no data
      List<Integer> empty = new ArrayList<>();
      for (int i = 0; i < length; i++) {
        storage[i].appendNoGrow(columns[i].aggregate(empty));
      }
    } else {
      for (List<Integer> group_locs : this.locs.values()) {
        for (int i = 0; i < length; i++) {
          Object value = columns[i].aggregate(group_locs);
          storage[i].appendNoGrow(value);
        }
      }
    }

    // Merge Problems
    AggregatedProblems[] problems = new AggregatedProblems[1 + length];
    problems[0] = this.problems;
    IntStream.range(0, length).forEach(i -> problems[i+1] = columns[i].getProblems());
    AggregatedProblems merged = AggregatedProblems.merge(problems);

    return new Table(
        IntStream.range(0, length)
            .mapToObj(i -> new Column(columns[i].getName(), storage[i].seal()))
            .toArray(Column[]::new),
        merged);
  }

  private static Builder getBuilderForType(int type, int size) {
    switch (type) {
      case Storage.Type.BOOL: return new BoolBuilder();
      case Storage.Type.DOUBLE: return NumericBuilder.createDoubleBuilder(size);
      case Storage.Type.LONG: return NumericBuilder.createLongBuilder(size);
      case Storage.Type.STRING: return new StringBuilder(size);
      case Storage.Type.OBJECT: return new ObjectBuilder(size);
    }
    return new InferredBuilder(size);
  }
}
