# Destructured Visitor
Destructured Visitor is a fast by type-unsafe implementation of a recursive Visitor in Java.

Unlike a classical recursive visitor or the pattern-matching (switch on types), this implementation
uses a specific inlining cache that is not shared in between different part of the tree of instances.

Let suppose we have the following hierarchies
```java
interface Unit {}
record Marine() implements Unit {}
record Sailor() implements Unit {}
record Soldier() implements Unit {}

interface Carrier {}
record Boat(List<Unit> units) implements Carrier { }
record Tank(List<Unit> units) implements Carrier { }
```

A way to traverse those hierarchies to compute something is to use the pattern matching
```java
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

public static void main(String[] args) {
  var boat = new Boat(List.of(new Marine(), new Sailor(), new Soldier()));
  System.out.println(PatternMatching.visitCarrier(boat)));  // 36
  ...    
```

This is how to do it using the destructured visitor
```java
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

public static void main(String[] args) throws Throwable {
  var boat = new Boat(List.of(new Marine(), new Sailor(), new Soldier()));
  System.out.println(Visitor.accept(boat)));  // 36
  ...  
```


### How to build the code

You need Java 21. The code of the DestructuredVisitor is compatible with Java 8 but the tests required Java 21.

To build with maven
```
  mvn package
```
