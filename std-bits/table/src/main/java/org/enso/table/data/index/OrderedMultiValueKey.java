package org.enso.table.data.index;

import org.enso.base.ObjectComparator;
import org.enso.table.data.column.storage.Storage;

import java.util.Comparator;

/**
 * A multi-value key for ordered operations like sorting.
 *
 * <p>It is meant to be used by sorted collections relying on {@code compareTo}, like {@code
 * TreeMap}. It uses an {@code objectComparator} that should expose the Enso comparison logic to the
 * Java-verse.
 *
 * <p>It currently does not support hashing, as we do not have a hashing implementation consistent
 * with Enso's comparison semantics.
 */
public class OrderedMultiValueKey extends MultiValueKeyBase
    implements Comparable<OrderedMultiValueKey> {
  private final Comparator<Object> objectComparator;

  private final int[] directions;

  public OrderedMultiValueKey(
          Storage<?>[] storages, int rowIndex, int[] directions) {
    this(storages, rowIndex, directions, ObjectComparator.DEFAULT);
  }

  public OrderedMultiValueKey(
      Storage<?>[] storages, int rowIndex, int[] directions, Comparator<Object> objectComparator) {
    super(storages, rowIndex);
    this.objectComparator = objectComparator;
    this.directions = directions;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof MultiValueKeyBase that)) return false;
    if (storages.length != that.storages.length) return false;
    for (int i = 0; i < storages.length; i++) {
      if (objectComparator.compare(get(i), that.get(i)) != 0) {
        return false;
      }
    }

    return true;
  }

  @Override
  public int compareTo(OrderedMultiValueKey that) {
    if (objectComparator == null || that == null) {
      throw new NullPointerException();
    }

    if (that.storages.length != storages.length) {
      throw new ClassCastException("Incomparable keys.");
    }

    for (int i = 0; i < storages.length; i++) {
      int comparison = objectComparator.compare(get(i), that.get(i));
      if (comparison != 0) {
        return comparison * directions[i];
      }
    }

    return 0;
  }

  @Override
  public int hashCode() {
    throw new IllegalStateException(
        "Currently no hash_code implementation consistent with the ObjectComparator is exposed, so"
            + " OrderedMultiValueKey is not hashable.");
  }

  @Override
  public String toString() {
    return "OrderedMultiValueKey{row="+rowIndex+"}";
  }

  /**
   * A comparator that uses only one dimension of the key.
   */
  public static class ProjectionComparator implements Comparator<OrderedMultiValueKey> {
    private final int ix;

    public ProjectionComparator(int ix) {
      this.ix = ix;
    }

    @Override
    public int compare(OrderedMultiValueKey o1, OrderedMultiValueKey o2) {
      if (o1.storages.length != o2.storages.length) {
        throw new ClassCastException("Incomparable keys.");
      }

      return o1.objectComparator.compare(o1.get(ix), o2.get(ix));
    }
  }
}
