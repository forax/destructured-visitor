# Destructured Visitor
Destructured Visitor is a fast by type-unsafe implementation of a recursive Visitor in Java.

Unlike a classical recursive visitor or the pattern-matching (switch on types), this implementation
instead of using the bimprohic inlining cache used by the Java VMs by default when doing a call
allow to use a specific inlining cache that is not shared in between different part of the tree.

Let suppose we have the following hierarchy
```java
sealed interface Vehicle permits Car, Carrier { }
record Car(int passenger) implements Vehicle { }
record CarrierTruck(Vehicle vehicle) implements Vehicle { }
```

A way to traverse the hierarchy to compute something using the pattern matching is
```java
static int dispatch(Vehicle vehicle) {
  return switch(vehicle) {
    case Car car -> passengers(car);
    case CarrierTruck truck -> passengers(truck);
  };
}

// visit method for a CarrierTruck,
static int passengers(CarrierTruck truck) {
  return (int) dispatch.invokeExact(truck.vehicle);
}

// visit method for a Car
static int passengers(Car car) {
  return car.passenger;
}

public static void main(String[] args) throws Throwable {
  CarrierTruck truck = new CarrierTruck(new Car(4));
  int passengers = dispatch(truck);
}
```

This is how to do it using the destructured visitor
```java
// visit method for a CarrierTruck, also ask for a dispatch method handle that takes a Vehicle and return an int
static int passengers(CarrierTruck truck,
                      @Signature({int.class, Vehicle.class}) MethodHandle dispatch) throws Throwable {
  return (int) dispatch.invokeExact(truck.vehicle);
}

// visit method for a Car
static int passengers(Car car) {
  return car.passenger;
}

private static final MethodHandle DISPATCH = DestructuredVisitor.of(lookup(),
      Arrays.stream(Demo.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("passengers"))
            .toList())
    .createDispatch(int.class, Vehicle.class);
}

public static void main(String[] args) throws Throwable {
  CarrierTruck truck = new CarrierTruck(new Car(4));
  int passengers = (int) Demo.DISPATCH.invokeExact((Vehicle) truck);
}
```


### How to build the code

You need Java 21. The code of the DestructuredVisitor is compatible with Java 8 but the tests required Java 21.

To build with maven
```
  mvn package
```
