package com.github.forax.destructuredvisitor;

import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.util.Arrays;

import static java.lang.invoke.MethodHandles.lookup;
import static org.junit.jupiter.api.Assertions.*;

public class DestructuredVisitorTest {
  private static class Demo {
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
            Arrays.stream(Demo.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("passengers"))
                .toList())
        .createDispatch(int.class, Vehicle.class);
  }

  @Test
  public void example() throws Throwable {
    var truck1 = new Demo.CarrierTruck(new Demo.Car(4));
    var passengers1 = (int) Demo.DISPATCH.invokeExact((Demo.Vehicle) truck1);
    assertEquals(4, passengers1);

    var truck2 = new Demo.CarrierTruck(new Demo.CarrierTruck(new Demo.Car(7)));
    var passengers2 = (int) Demo.DISPATCH.invokeExact((Demo.Vehicle) truck2);
    assertEquals(7, passengers2);
  }
}