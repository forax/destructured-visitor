package com.github.forax.destructuredvisitor;

import com.github.forax.destructuredvisitor.DestructuredVisitor.Signature;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.List;

import static java.lang.invoke.MethodHandles.lookup;
import static org.junit.jupiter.api.Assertions.*;

public class DestructuredVisitorTest {
  private static class Example {
    interface Vehicle { }
    record Car(int passenger) implements Vehicle { }
    record CarrierTruck(Vehicle vehicle) implements Vehicle { }

    static int passengers(CarrierTruck truck,
                          @Signature({int.class, Vehicle.class}) MethodHandle dispatch) throws Throwable {
      return (int) dispatch.invokeExact(truck.vehicle);
    }

    static int passengers(Car car) {
      return car.passenger;
    }

    private static final MethodHandle DISPATCH = DestructuredVisitor.of(lookup(),
            Arrays.stream(Example.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("passengers"))
                .toList())
        .createDispatch(int.class, Vehicle.class);
  }

  @Test
  public void example() throws Throwable {
    var truck1 = new Example.CarrierTruck(new Example.Car(4));
    var passengers1 = (int) Example.DISPATCH.invokeExact((Example.Vehicle) truck1);
    assertEquals(4, passengers1);

    var truck2 = new Example.CarrierTruck(new Example.CarrierTruck(new Example.Car(7)));
    var passengers2 = (int) Example.DISPATCH.invokeExact((Example.Vehicle) truck2);
    assertEquals(7, passengers2);
  }

  @Test
  public void preconditions() throws NoSuchMethodException {
    record Container() {
      static void noParameters() {}
      static void visit(Container container) {}
    }

    var lookup = lookup();
    var noParameters = Container.class.getDeclaredMethod("noParameters");
    var visit = Container.class.getDeclaredMethod("visit", Container.class);
    assertAll(
        () -> assertThrows(NullPointerException.class, () -> DestructuredVisitor.of(null, List.of())),
        () -> assertThrows(NullPointerException.class, () -> DestructuredVisitor.of(lookup, null)),
        () -> assertThrows(IllegalArgumentException.class, () -> DestructuredVisitor.of(lookup, List.of())),
        () -> assertThrows(IllegalArgumentException.class, () -> DestructuredVisitor.of(lookup, List.of(noParameters))),
        () -> assertThrows(IllegalArgumentException.class, () -> DestructuredVisitor.of(lookup, List.of())),
        () -> assertThrows(NullPointerException.class, () -> DestructuredVisitor.of(lookup, List.of(visit)).createDispatch((Class<?>) null))
    );
  }

  @Test
  public void bimorphic() throws NoSuchMethodException {
    record A() {}
    record B() {}
    class Container {
      static String visit(A a) { return "A"; }
      static String visit(B b) { return "B"; }
    }

    var dispatch = DestructuredVisitor.of(lookup(), Arrays.asList(Container.class.getDeclaredMethods()))
        .createDispatch(String.class, Object.class);
    assertAll(
        () -> assertEquals("A", (String) dispatch.invokeExact((Object) new A())),
        () -> assertEquals("B", (String) dispatch.invokeExact((Object) new B())),
        () -> assertThrows(NullPointerException.class, () -> { var __ = (String) dispatch.invokeExact((Object) null); })
    );
  }

  @Test
  public void instanceMethods() throws NoSuchMethodException {
    record A() {
      String visit() { return "A"; }
    }
    record B() {
      String visit() { return "B"; }
    }

    var methods = List.of(
      A.class.getDeclaredMethod("visit"), B.class.getDeclaredMethod("visit")
    );
    var dispatch = DestructuredVisitor.of(lookup(), methods)
        .createDispatch(String.class, Object.class);
    assertAll(
        () -> assertEquals("A", (String) dispatch.invokeExact((Object) new A())),
        () -> assertEquals("B", (String) dispatch.invokeExact((Object) new B())),
        () -> assertThrows(NullPointerException.class, () -> { var __ = (String) dispatch.invokeExact((Object) null); })
    );
  }

  @Test
  public void explicitDispatch() throws Throwable {
    interface Unit {}
    record Marine() implements Unit {}
    record Sailor() implements Unit {}
    record Soldier() implements Unit {}

    interface Carrier {}
    record Boat(List<Unit> units) implements Carrier { }
    record Tank(List<Unit> units) implements Carrier { }

    record Visitor() {
      static final DestructuredVisitor VISITOR = DestructuredVisitor.of(MethodHandles.lookup(),
            Arrays.stream(Visitor.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("visit"))
                .toList());

      static int visit(Marine marine) { return 10; }
      static int visit(Sailor sailor) { return 12; }
      static int visit(Soldier soldier) { return 14; }

      private static final MethodHandle DISPATCH_UNIT = VISITOR.createDispatch(int.class, Unit.class);

      static int visit(Boat boat) throws Throwable {
        return visitUnits(boat.units());
      }

      static int visit(Tank tank) throws Throwable {
        return visitUnits(tank.units());
      }

      static int visitUnits(List<Unit> units) throws Throwable {
        var sum = 0;
        for(var unit: units) {
          sum += (int) DISPATCH_UNIT.invokeExact(unit);
        }
        return sum;
      }

      private static final MethodHandle DISPATCH_CARRIER = VISITOR.createDispatch(int.class, Carrier.class);

      static int accept(Carrier carrier) throws Throwable {
        return (int) DISPATCH_CARRIER.invokeExact(carrier);
      }
    }

    var boat = new Boat(List.of(new Marine(), new Sailor(), new Soldier()));
    var tank = new Boat(List.of(new Marine(), new Soldier()));
    assertAll(
        () -> assertEquals(36, Visitor.accept(boat)),
        () -> assertEquals(36, Visitor.accept(boat)),
        () -> assertEquals(24, Visitor.accept(tank)),
        () -> assertEquals(24, Visitor.accept(tank))
    );
  }


  // ---

  sealed interface Unit {}
  record Marine() implements Unit {}
  record Sailor() implements Unit {}
  record Soldier() implements Unit {}

  sealed interface Carrier {}
  record Boat(List<Unit> units) implements Carrier { }
  record Tank(List<Unit> units) implements Carrier { }

  @Test
  public void patternMatchingDispatch() {
    record PatternMatching() {
      static int visitCarrier(Carrier carrier) {
        return switch (carrier) {
          case Boat(var units) -> visitUnits(units);
          case Tank(var units) -> visitUnits(units);
        };
      }

      static int visitUnits(List<Unit> units) {
        return units.stream().mapToInt(PatternMatching::visitUnit).sum();
      }

      static int visitUnit(Unit unit) {
        return switch (unit) {
          case Marine marine -> 10;
          case Sailor sailor -> 12;
          case Soldier soldier -> 14;
        };
      }
    }

    var boat = new Boat(List.of(new Marine(), new Sailor(), new Soldier()));
    var tank = new Boat(List.of(new Marine(), new Soldier()));
    assertAll(
        () -> assertEquals(36, PatternMatching.visitCarrier(boat)),
        () -> assertEquals(36, PatternMatching.visitCarrier(boat)),
        () -> assertEquals(24, PatternMatching.visitCarrier(tank)),
        () -> assertEquals(24, PatternMatching.visitCarrier(tank))
    );
  }

  @Test
  public void annotationDispatch() throws Throwable {
    record Visitor() {
      static int visit(Marine marine) { return 10; }
      static int visit(Sailor sailor) { return 12; }
      static int visit(Soldier soldier) { return 14; }

      static int visit(Boat boat, @Signature({int.class, Unit.class}) MethodHandle dispatchUnit) throws Throwable {
        return visitUnits(boat.units(), dispatchUnit);
      }

      static int visit(Tank tank, @Signature({int.class, Unit.class}) MethodHandle dispatchUnit) throws Throwable {
        return visitUnits(tank.units(), dispatchUnit);
      }

      static int visitUnits(List<Unit> units, MethodHandle dispatchUnit) throws Throwable {
        var sum = 0;
        for(var unit: units) {
          sum += (int) dispatchUnit.invokeExact(unit);
        }
        return sum;
      }

      static final DestructuredVisitor VISITOR = DestructuredVisitor.of(MethodHandles.lookup(),
              Arrays.stream(Visitor.class.getDeclaredMethods())
                  .filter(m -> m.getName().equals("visit"))
                  .toList());
      static final MethodHandle DISPATCH_CARRIER = VISITOR.createDispatch(int.class, Carrier.class);

      static int accept(Carrier carrier) throws Throwable {
        return (int) DISPATCH_CARRIER.invokeExact(carrier);
      }
    }

    var boat = new Boat(List.of(new Marine(), new Sailor(), new Soldier()));
    var tank = new Boat(List.of(new Marine(), new Soldier()));
    assertAll(
        () -> assertEquals(36, Visitor.accept(boat)),
        () -> assertEquals(36, Visitor.accept(boat)),
        () -> assertEquals(24, Visitor.accept(tank)),
        () -> assertEquals(24, Visitor.accept(tank))
        );
  }
}