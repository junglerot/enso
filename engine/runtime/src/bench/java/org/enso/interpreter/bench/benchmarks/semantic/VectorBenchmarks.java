package org.enso.interpreter.bench.benchmarks.semantic;

import java.io.ByteArrayOutputStream;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Value;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.Blackhole;


@BenchmarkMode(Mode.AverageTime)
@Fork(1)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
public class VectorBenchmarks {
  private Value arrayOfFibNumbers;
  private Value avg;
  private Value self;

  @Setup
  public void initializeBenchmark(BenchmarkParams params) {
    Engine eng = Engine.newBuilder()
      .allowExperimentalOptions(true)
      .logHandler(new ByteArrayOutputStream())
      .option(
        "enso.languageHomeOverride",
        Paths.get("../../distribution/component").toFile().getAbsolutePath()
      ).build();
    var ctx = Context.newBuilder()
      .engine(eng)
      .allowIO(true)
      .allowAllAccess(true)
      .build();
    var module = ctx.eval("enso", "\n" +
      "import Standard.Base.Data.Vector\n" +
      "\n" +
      "avg arr =\n" +
      "    sum acc i = if i == arr.length then acc else\n" +
      "        sum (acc + arr.at i) i+1\n" +
      "    (sum 0 0) / arr.length\n" +
      "\n" +
      "fibarr size modulo = \n" +
      "    b = Vector.new_builder size\n" +
      "    b.append 1\n" +
      "    b.append 1\n" +
      "    \n" +
      "    add_more n = if n == size then b else \n" +
      "        b.append <| (b.at n-1 + b.at n-2) % modulo\n" +
      "        @Tail_Call add_more n+1\n" +
      "    \n" +
      "    add_more 2 . to_vector\n" +
      "\n" +
      "to_vector arr = Vector.from_polyglot_array arr\n" +
      "to_array vec = vec.to_array\n" +
      "\n");

    this.self = module.invokeMember("get_associated_type");
    Function<String,Value> getMethod = (name) -> module.invokeMember("get_method", self, name);

    Value arr = getMethod.apply("fibarr").execute(self, 1000, Integer.MAX_VALUE);

    switch (params.getBenchmark().replaceFirst(".*\\.", "")) {
      case "averageOverVector": {
        this.arrayOfFibNumbers = arr;
        break;
      }
      case "averageOverArray": {
        this.arrayOfFibNumbers = getMethod.apply("to_array").execute(self, arr);
        break;
      }
      case "averageOverPolyglotVector": {
        long[] copy = copyToPolyglotArray(arr);
        this.arrayOfFibNumbers = getMethod.apply("to_vector").execute(self, copy);
        break;
      }
      case "averageOverPolyglotArray": {
        long[] copy = copyToPolyglotArray(arr);
        this.arrayOfFibNumbers = Value.asValue(copy);
        break;
      }
      default:
        throw new IllegalStateException("Unexpected benchmark: " + params.getBenchmark());
    }
    this.avg = getMethod.apply("avg");
  }

  private long[] copyToPolyglotArray(Value arr) {
    long[] copy = new long[Math.toIntExact(arr.getArraySize())];
    for (int i = 0; i < copy.length; i++) {
      copy[i] = arr.getArrayElement(i).asLong();
    }
    return copy;
  }

  @Benchmark
  public void averageOverVector(Blackhole matter) {
    performBenchmark(matter);
  }

  @Benchmark
  public void averageOverPolyglotVector(Blackhole matter) {
    performBenchmark(matter);
  }

  @Benchmark
  public void averageOverArray(Blackhole matter) {
    performBenchmark(matter);
  }

  @Benchmark
  public void averageOverPolyglotArray(Blackhole matter) {
    performBenchmark(matter);
  }

  private void performBenchmark(Blackhole matter) throws AssertionError {
    var average = avg.execute(self, arrayOfFibNumbers);
    if (!average.fitsInDouble()) {
      throw new AssertionError("Shall be a double: " + average);
    }
    var result = (long) average.asDouble();
    if (result < 1019950590 || result > 1019950600) {
      throw new AssertionError("Expecting reasonable average but was " + result + "\n" + arrayOfFibNumbers);
    }
    matter.consume(result);
  }
}

