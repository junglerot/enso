package org.enso.searcher.sql;

import org.enso.polyglot.Suggestion;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import scala.collection.immutable.Seq;
import scala.concurrent.Await;
import scala.concurrent.ExecutionContext;
import scala.concurrent.duration.Duration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

@BenchmarkMode(Mode.AverageTime)
@Fork(1)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class SuggestionsRepoBenchmark {

  static final int DATABASE_SIZE = 1000000;
  static final Duration TIMEOUT = Duration.apply(3, TimeUnit.SECONDS);

  final Path dbfile = Path.of(System.getProperty("java.io.tmpdir"), "bench-suggestions.db");
  final Seq<Suggestion.Kind> kinds = SuggestionRandom.nextKinds();
  final Seq<scala.Tuple2<UUID, String>> updateInput = SuggestionRandom.nextUpdateAllInput();

  SqlSuggestionsRepo repo;

  @Setup
  public void setup() throws TimeoutException, InterruptedException {
    repo = SqlSuggestionsRepo.apply(dbfile.toFile(), ExecutionContext.global());
    if (Files.notExists(dbfile)) {
      System.out.println("initializing " + dbfile.toString() + " ...");
      Await.ready(repo.init(), TIMEOUT);
      System.out.println("inserting records...");
      int size = 0;
      while (size < DATABASE_SIZE) {
        size = insertBatch(20000);
      }
      System.out.println("created " + size + " records");
    }
  }

  @TearDown
  public void tearDown() {
    repo.close();
  }

  int insertBatch(int size) throws TimeoutException, InterruptedException {
    Suggestion[] stubs =
        Stream.generate(SuggestionRandom::nextSuggestion).limit(size).toArray(Suggestion[]::new);
    return (int) Await.result(repo.insertBatch(stubs), TIMEOUT);
  }

  static <T> scala.Option<T> none() {
    return (scala.Option<T>) scala.None$.MODULE$;
  }

  @Benchmark
  public Object searchBaseline() throws TimeoutException, InterruptedException {
    return Await.result(repo.search(none(), none(), none(), none(), none()), TIMEOUT);
  }

  @Benchmark
  public Object searchByReturnType() throws TimeoutException, InterruptedException {
    return Await.result(
        repo.search(none(), none(), scala.Some.apply("MyType"), none(), none()), TIMEOUT);
  }

  @Benchmark
  public Object searchBySelfType() throws TimeoutException, InterruptedException {
    return Await.result(
        repo.search(none(), scala.Some.apply("MyType"), none(), none(), none()), TIMEOUT);
  }

  @Benchmark
  public Object searchBySelfReturnTypes() throws TimeoutException, InterruptedException {
    return Await.result(
        repo.search(
            none(), scala.Some.apply("SelfType"), scala.Some.apply("ReturnType"), none(), none()),
        TIMEOUT);
  }

  @Benchmark
  public Object searchByAll() throws TimeoutException, InterruptedException {
    return Await.result(
        repo.search(
            none(),
            scala.Some.apply("SelfType"),
            scala.Some.apply("ReturnType"),
            scala.Some.apply(kinds),
            none()),
        TIMEOUT);
  }

  @Benchmark
  public Object updateByExternalId() throws TimeoutException, InterruptedException {
    return Await.result(repo.updateAll(updateInput), TIMEOUT);
  }


  public static void main(String[] args) throws RunnerException {
    Options opt =
        new OptionsBuilder().include(SuggestionsRepoBenchmark.class.getSimpleName()).build();

    new Runner(opt).run();
  }
}
