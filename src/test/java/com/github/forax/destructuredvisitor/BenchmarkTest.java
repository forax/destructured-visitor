package com.github.forax.destructuredvisitor;

import com.github.forax.destructuredvisitor.DestructuredVisitor.Signature;

import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

// Benchmark                           (param)  Mode  Cnt   Score   Error  Units
//BenchmarkTest.destructured_visitor        1  avgt    5   2.359 ± 0.031  ns/op
//BenchmarkTest.destructured_visitor        2  avgt    5   7.262 ± 0.043  ns/op
//BenchmarkTest.destructured_visitor        3  avgt    5   8.793 ± 0.077  ns/op
//BenchmarkTest.destructured_visitor        4  avgt    5  14.497 ± 0.392  ns/op
//BenchmarkTest.late_binding                1  avgt    5   2.322 ± 0.021  ns/op
//BenchmarkTest.late_binding                2  avgt    5   6.973 ± 0.068  ns/op
//BenchmarkTest.late_binding                3  avgt    5  23.359 ± 0.238  ns/op
//BenchmarkTest.late_binding                4  avgt    5  31.366 ± 0.619  ns/op
//BenchmarkTest.pattern_matching            1  avgt    5   2.368 ± 0.017  ns/op
//BenchmarkTest.pattern_matching            2  avgt    5   5.873 ± 0.147  ns/op
//BenchmarkTest.pattern_matching            3  avgt    5  11.766 ± 0.167  ns/op
//BenchmarkTest.pattern_matching            4  avgt    5  16.747 ± 0.126  ns/op
//BenchmarkTest.value_visitor               1  avgt    5   2.325 ± 0.020  ns/op
//BenchmarkTest.value_visitor               2  avgt    5   6.288 ± 0.086  ns/op
//BenchmarkTest.value_visitor               3  avgt    5  23.269 ± 0.298  ns/op
//BenchmarkTest.value_visitor               4  avgt    5  31.315 ± 0.290  ns/op
@State(Scope.Benchmark)
public class BenchmarkTest {
  interface Visitor {
    int visit(A a);
    int visit(B b);
    int visit(K k);
    int visit(L l);
    int visit(M m);
    int visit(N n);
  }

  sealed interface I {
    int accept(Visitor visitor);
    int value();
  }
  record A(J j) implements I {
    public int accept(Visitor visitor) { return visitor.visit(this); }
    public int value() { return j.value(); }
  }
  record B(J j) implements I {
    public int accept(Visitor visitor) { return visitor.visit(this); }
    public int value() { return j.value(); }
  }

  sealed interface J {
    int accept(Visitor visitor);
    int value();
  }
  record K(int v) implements J {
    public int accept(Visitor visitor) { return visitor.visit(this); }
    public int value() { return v; }
  }
  record L(int v) implements J {
    public int accept(Visitor visitor) { return visitor.visit(this); }
    public int value() { return v; }
  }
  record M(int v) implements  J {
    public int accept(Visitor visitor) { return visitor.visit(this); }
    public int value() { return v; }
  }
  record N(int v) implements  J {
    public int accept(Visitor visitor) { return visitor.visit(this); }
    public int value() { return v; }
  }

  @Param({ "1", "2", "3", "4" })
  String param;

  I[] array;

  @Setup
  public void setup() {
    array = switch (param) {
      case "1" -> new I[] {
          new A(new K(1)),
          new B(new L(2)),
      };
      case "2" -> new I[] {
          new A(new K(1)),
          new A(new L(2)),
          new B(new L(2)),
          new B(new M(3)),
      };
      case "3" -> new I[] {
          new A(new K(1)),
          new A(new L(2)),
          new A(new M(3)),
          new B(new L(2)),
          new B(new M(3)),
          new B(new N(4))
      };
      case "4" -> new I[] {
          new A(new K(1)),
          new A(new L(2)),
          new A(new M(3)),
          new A(new N(4)),
          new B(new L(1)),
          new B(new L(2)),
          new B(new M(3)),
          new B(new N(4))
      };
      default -> throw new AssertionError();
    };
  }

  @Benchmark
  public int late_binding() {
    var sum = 0;
    for(var i : array) {
      sum += i.value();
    }
    return sum;
  }

  static final class ValueVisitor implements Visitor {
    public int visit(A a) { return a.j.accept(this); }
    public int visit(B b) { return b.j.accept(this); }

    public int visit(K k) { return k.v; }
    public int visit(L l) { return l.v; }
    public int visit(M m) { return m.v; }
    public int visit(N n) { return n.v; }

    private static final ValueVisitor VISITOR = new ValueVisitor();
  }

  @Benchmark
  public int value_visitor() {
    var sum = 0;
    for(var i : array) {
      sum += i.accept(ValueVisitor.VISITOR);
    }
    return sum;
  }

  static int patternMatching(I i) {
    return switch (i) {
      case A(K k) -> k.v;
      case A(L l) -> l.v;
      case A(M m) -> m.v;
      case A(N n) -> n.v;
      case B(K k) -> k.v;
      case B(L l) -> l.v;
      case B(M m) -> m.v;
      case B(N n) -> n.v;
    };
  }

  @Benchmark
  public int pattern_matching() {
    var sum = 0;
    for(var i : array) {
      sum += patternMatching(i);
    }
    return sum;
  }

  static final class ValueDestructuredVisitor {
    static int value(A a, @Signature({int.class, J.class}) MethodHandle mh) throws Throwable {
      return (int) mh.invokeExact(a.j);
    }
    static int value(B b, @Signature({int.class, J.class}) MethodHandle mh) throws Throwable {
      return (int) mh.invokeExact(b.j);
    }
    static int value(K k) { return k.v; }
    static int value(L l) { return l.v; }
    static int value(M m) { return m.v; }
    static int value(N n) { return n.v; }

    private static final MethodHandle DISPATCH = DestructuredVisitor.of(MethodHandles.lookup(),
        Arrays.stream(ValueDestructuredVisitor.class.getDeclaredMethods()).toList())
        .createDispatch(int.class, I.class);
  }

  @Benchmark
  public int destructured_visitor() throws Throwable {
    var sum = 0;
    for(var i : array) {
      sum += (int) ValueDestructuredVisitor.DISPATCH.invokeExact(i);
    }
    return sum;
  }


  private static Options options() {
    return new OptionsBuilder()
        .include(BenchmarkTest.class.getName() + ".*")
        .mode (Mode.AverageTime)
        .timeUnit(TimeUnit.NANOSECONDS)
        .warmupTime(TimeValue.seconds(1))
        .warmupIterations(5)
        .measurementTime(TimeValue.seconds(1))
        .measurementIterations(5)
        .forks(1)
        .shouldFailOnError(true)
        .shouldDoGC(true)
        .build();
  }

  //@Test
  public void runBenchmark() throws RunnerException {
    new Runner(options()).run();
  }

  public static void main(String[] args) throws RunnerException {
    new Runner(options()).run();
  }
}

