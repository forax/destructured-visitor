package com.github.forax.destructuredvisitor;

import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
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
                          @DestructuredVisitor.Signature({int.class, Vehicle.class}) MethodHandle dispatch) throws Throwable {
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
        () -> assertThrows(NullPointerException.class, () -> DestructuredVisitor.of(lookup, List.of(visit)).createDispatch(null))
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
}