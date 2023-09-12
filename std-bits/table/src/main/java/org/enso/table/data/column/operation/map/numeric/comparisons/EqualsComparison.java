package org.enso.table.data.column.operation.map.numeric.comparisons;

import java.math.BigInteger;
import org.enso.table.data.column.operation.map.MapOperationProblemBuilder;
import org.enso.table.data.column.operation.map.numeric.helpers.DoubleArrayAdapter;
import org.enso.table.data.column.storage.BoolStorage;
import org.enso.table.data.column.storage.Storage;

public class EqualsComparison<T extends Number, I extends Storage<? super T>>
    extends NumericComparison<T, I> {
  public EqualsComparison() {
    super(Storage.Maps.EQ);
  }

  @Override
  protected boolean doDouble(double a, double b) {
    return a == b;
  }

  @Override
  protected BoolStorage runDoubleMap(
      DoubleArrayAdapter lhs, double rhs, MapOperationProblemBuilder problemBuilder) {
    problemBuilder.reportFloatingPointEquality(-1);
    return super.runDoubleMap(lhs, rhs, problemBuilder);
  }

  @Override
  protected BoolStorage runDoubleZip(
      DoubleArrayAdapter lhs, DoubleArrayAdapter rhs, MapOperationProblemBuilder problemBuilder) {
    problemBuilder.reportFloatingPointEquality(-1);
    return super.runDoubleZip(lhs, rhs, problemBuilder);
  }

  @Override
  protected boolean doLong(long a, long b) {
    return a == b;
  }

  @Override
  protected boolean doBigInteger(BigInteger a, BigInteger b) {
    return a.equals(b);
  }

  @Override
  protected boolean onOtherType(Object a, Object b) {
    return false;
  }
}
