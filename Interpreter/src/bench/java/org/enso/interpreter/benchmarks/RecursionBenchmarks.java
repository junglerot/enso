package org.enso.interpreter.benchmarks;

import org.enso.interpreter.RecursionFixtures;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@Fork(2)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class RecursionBenchmarks {
  private static RecursionFixtures recursionFixtures = new RecursionFixtures();

  @Benchmark
  public void benchSumTCO() {
    recursionFixtures.sumTCO().execute(recursionFixtures.hundredMillion());
  }
}
