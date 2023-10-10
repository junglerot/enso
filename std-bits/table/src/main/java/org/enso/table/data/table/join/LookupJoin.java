package org.enso.table.data.table.join;

import org.enso.base.text.TextFoldingStrategy;
import org.enso.table.data.column.builder.Builder;
import org.enso.table.data.column.storage.Storage;
import org.enso.table.data.index.Index;
import org.enso.table.data.index.MultiValueIndex;
import org.enso.table.data.index.UnorderedMultiValueKey;
import org.enso.table.data.mask.OrderMask;
import org.enso.table.data.table.Column;
import org.enso.table.data.table.Table;
import org.enso.table.error.NonUniqueLookupKey;
import org.enso.table.error.NullValuesInKeyColumns;
import org.enso.table.error.UnmatchedRow;
import org.enso.table.util.ConstantList;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

public class LookupJoin {
  private static final TextFoldingStrategy TEXT_FOLDING = TextFoldingStrategy.unicodeNormalizedFold;

  public static Table lookupAndReplace(List<Equals> keys, List<LookupColumnDescription> columnDescriptions,
                                       boolean allowUnmatchedRows) {
    if (keys.isEmpty()) {
      throw new IllegalArgumentException("No join keys specified.");
    }

    LookupJoin joiner = new LookupJoin(keys, columnDescriptions, allowUnmatchedRows);
    joiner.checkNullsInKey();
    joiner.verifyLookupUniqueness();
    return joiner.join();
  }

  private final List<LookupColumnDescription> columnDescriptions;
  private final List<String> keyColumnNames;

  private final MultiValueIndex<UnorderedMultiValueKey> lookupIndex;

  private final Storage<?>[] baseKeyStorages;
  private final List<TextFoldingStrategy> textFoldingStrategies;
  private final int baseTableRowCount;
  private final boolean allowUnmatchedRows;

  private UnorderedMultiValueKey makeTableRowKey(int ix) {
    return new UnorderedMultiValueKey(baseKeyStorages, ix, textFoldingStrategies);
  }

  private LookupJoin(List<Equals> keys, List<LookupColumnDescription> columnDescriptions, boolean allowUnmatchedRows) {
    baseKeyStorages = keys.stream().map(Equals::left).map(Column::getStorage).toArray(Storage[]::new);
    this.columnDescriptions = columnDescriptions;
    this.allowUnmatchedRows = allowUnmatchedRows;
    textFoldingStrategies = ConstantList.make(TEXT_FOLDING, baseKeyStorages.length);

    Column[] lookupKeyColumns = keys.stream().map(Equals::right).toArray(Column[]::new);
    keyColumnNames = Arrays.stream(lookupKeyColumns).map(Column::getName).toList();

    assert lookupKeyColumns.length > 0;
    // tableSize parameter is only needed if there are no key columns, but that is not possible
    lookupIndex = MultiValueIndex.makeUnorderedIndex(lookupKeyColumns, 0, textFoldingStrategies);
    baseTableRowCount = baseKeyStorages[0].size();
  }

  private void checkNullsInKey() {
    UnorderedMultiValueKey nullKey = lookupIndex.findAnyNullKey();
    if (nullKey != null) {
      throw new NullValuesInKeyColumns(nullKey.getValues());
    }
  }

  private void verifyLookupUniqueness() {
    if (!lookupIndex.isUnique()) {
      // Find the duplicated key
      for (Map.Entry<UnorderedMultiValueKey, List<Integer>> group : lookupIndex.mapping().entrySet()) {
        int groupSize = group.getValue().size();
        if (groupSize > 1) {
          UnorderedMultiValueKey key = group.getKey();
          List<Object> exampleValues = IntStream.range(0, keyColumnNames.size()).mapToObj(key::get).toList();
          throw new NonUniqueLookupKey(keyColumnNames, exampleValues, groupSize);
        }
      }

      assert false : "isUnique returned false, but no duplicated key was found.";
    }
  }

  private Table join() {
    List<LookupOutputColumn> outputColumns = columnDescriptions.stream().map(this::prepareOutputColumn).toList();
    List<LookupOutputColumn.MergeColumns> columnsToMerge =
        outputColumns.stream().filter(LookupOutputColumn.MergeColumns.class::isInstance).map(LookupOutputColumn.MergeColumns.class::cast).toList();

    // We have columns to merge only if unmatched rows are expected. If unmatched rows are not allowed, all lookup
    // columns will completely replace old values, so we can rely on the OrderMask optimization which is more efficient.
    assert allowUnmatchedRows || columnsToMerge.isEmpty();

    boolean needsOrderMask = outputColumns.stream().anyMatch(LookupOutputColumn.AddFromLookup.class::isInstance);
    int[] orderMask = needsOrderMask ? new int[baseTableRowCount] : null;

    for (int i = 0; i < baseTableRowCount; i++) {
      // Find corresponding row in the lookup table
      int lookupRow = findLookupRow(i);

      assert allowUnmatchedRows || lookupRow != Index.NOT_FOUND;

      // Merge columns replacing old values
      for (LookupOutputColumn.MergeColumns mergeColumns : columnsToMerge) {
        Object itemToAdd;
        if (lookupRow != Index.NOT_FOUND) {
          itemToAdd = mergeColumns.lookupReplacement.getItemBoxed(lookupRow);
        } else {
          itemToAdd = mergeColumns.original.getItemBoxed(i);
        }
        mergeColumns.builder.appendNoGrow(itemToAdd);
      }

      // Prepare order mask for new columns / fully-replaced columns
      if (needsOrderMask) {
        orderMask[i] = lookupRow;
      }
    }

    Column[] columns = outputColumns.stream().map(c -> c.build(orderMask)).toArray(Column[]::new);
    return new Table(columns);
  }

  private int findLookupRow(int baseRowIx) {
    UnorderedMultiValueKey key = makeTableRowKey(baseRowIx);
    List<Integer> lookupRowIndices = lookupIndex.get(key);
    if (lookupRowIndices == null) {
      if (allowUnmatchedRows) {
        return Index.NOT_FOUND;
      } else {
        List<Object> exampleKeyValues = IntStream.range(0, keyColumnNames.size()).mapToObj(key::get).toList();
        throw new UnmatchedRow(exampleKeyValues);
      }
    }

    assert !lookupRowIndices.isEmpty() : "No Index group should be empty.";
    assert lookupRowIndices.size() == 1 : "This should have been checked in verifyLookupUniqueness()";
    return lookupRowIndices.get(0);
  }

  private LookupOutputColumn prepareOutputColumn(LookupColumnDescription description) {
    return switch (description) {
      case LookupColumnDescription.KeepOriginal keepOriginal ->
          new LookupOutputColumn.KeepOriginal(keepOriginal.column());
      case LookupColumnDescription.MergeColumns mergeColumns -> {
        String name = mergeColumns.original().getName();
        if (allowUnmatchedRows) {
          Storage<?> original = mergeColumns.original().getStorage();
          Storage<?> lookupReplacement = mergeColumns.lookupReplacement().getStorage();
          Builder builder = Builder.getForType(mergeColumns.commonType(), baseTableRowCount);
          yield new LookupOutputColumn.MergeColumns(name, original, lookupReplacement, builder);
        } else {
          // If we do not allow unmatched rows, we can rely on the OrderMask optimization also for 'merged' columns -
          // because there is no real merging - all values are guaranteed to only come from the lookup table.
          Column renamedLookup = mergeColumns.lookupReplacement().rename(name);
          yield new LookupOutputColumn.AddFromLookup(renamedLookup);
        }
      }
      case LookupColumnDescription.AddNew addNew -> new LookupOutputColumn.AddFromLookup(addNew.lookupColumn());
    };
  }

  interface LookupOutputColumn {
    Column build(int[] orderMask);

    record KeepOriginal(Column column) implements LookupOutputColumn {

      @Override
      public Column build(int[] orderMask) {
        return column;
      }
    }

    record MergeColumns(String name, Storage<?> original, Storage<?> lookupReplacement,
                        Builder builder) implements LookupOutputColumn {
      @Override
      public Column build(int[] orderMask) {
        return new Column(name, builder.seal());
      }
    }

    record AddFromLookup(Column lookupColumn) implements LookupOutputColumn {
      @Override
      public Column build(int[] orderMask) {
        assert orderMask != null;
        return lookupColumn.applyMask(new OrderMask(orderMask));
      }
    }
  }

}
