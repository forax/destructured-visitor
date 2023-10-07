package com.github.forax.destructuredvisitor;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static java.lang.invoke.MethodHandles.exactInvoker;
import static java.lang.invoke.MethodHandles.foldArguments;
import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodType.methodType;
import static java.util.stream.Collectors.toList;

/**
 * A destructured visitor is a fast implementation of a visitor pattern when the visit methods
 * <ol>
 *   <li>are not necessarily declared in the same class</li>
 *   <li>can ask for a specific inlining cache avoiding polymorphic calls at runtime</li>
 * </ol>
 *
 * Here is an example of usage
 * <pre>
 *   interface Vehicle { }
 *   record Car(int passenger) implements Vehicle { }
 *   record CarrierTruck(Vehicle vehicle) implements Vehicle { }
 *
 *   // visit method for a CarrierTruck, also ask for a dispatch method handle that takes a Vehicle and return an int
 *   static int passengers(CarrierTruck truck,
 *                         @Signature({int.class, Vehicle.class}) MethodHandle dispatch) throws Throwable {
 *     return (int) dispatch.invokeExact(truck.vehicle);
 *   }
 *
 *   // visit method for a Car
 *   static int passengers(Car car) {
 *     return car.passenger;
 *   }
 *
 *   private static final MethodHandle DISPATCH = DestructuredVisitor.of(lookup(),
 *         Arrays.stream(Demo.class.getDeclaredMethods())
 *               .filter(m -> m.getName().equals("passengers"))
 *               .toList())
 *       .createDispatch(int.class, Vehicle.class);
 *   }
 *
 *   public static void main(String[] args) throws Throwable {
 *     CarrierTruck truck = new CarrierTruck(new Car(4));
 *     int passengers = (int) DISPATCH.invokeExact((Vehicle) truck);
 *   }
 * </pre>
 */
public class DestructuredVisitor {
  /**
   * Declare the return type and parameter types of the dispatch method handle.
   */
  @Target(ElementType.PARAMETER)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface Signature {
    /**
     * Returns the return type and parameter types of the dispatch method handle
     * @return the return type and parameter types of the dispatch method handle
     */
    Class<?>[] value();
  }

  // should be a record but code must be Java 8 compatible
  private static final class MethodData {
    final MethodHandle target;
    final List<Signature> signatures;

    public MethodData(MethodHandle target, List<Signature> signatures) {
      this.target = target;
      this.signatures = signatures;
    }
  }

  // should use a ScopeValue in Java 21+
  private static final ThreadLocal<MethodData> CURRENT_METHOD_DATA = new ThreadLocal<>();

  private final ClassValue<MethodData> cache = new ClassValue<MethodData>() {
    @Override
    protected MethodData computeValue(Class<?> type) {
      MethodData methodData =  CURRENT_METHOD_DATA.get();
      if (methodData == null) {
        throw new IllegalStateException("no method found for class " + type.getName());
      }
      return methodData;
    }
  };

  private DestructuredVisitor() { }

  private static List<Signature> toSignatures(Annotation[][] parameterAnnotations) {
    return Arrays.stream(parameterAnnotations)
        .map(annotations ->
            Arrays.stream(annotations)
                .flatMap(annotation -> annotation instanceof Signature ? Stream.of((Signature) annotation) : null)
                .findFirst().orElse(null))
        .collect(toList());
  }

  /**
   * Creates a destructured visitor from a lookup and a list of methods to visit.
   *
   * @param lookup a lookup able to see the methods
   * @param methods a list of methods to visit
   * @return a destructured visitor
   * @throws NullPointerException if the {@code lookup} or {@code methods} is null
   * @throws IllegalArgumentException if either there is no methods, a method
   *  is neither an instance method nor a static method that takes at least one parameter
   */
  public static DestructuredVisitor of(Lookup lookup, List<Method> methods) {
    Objects.requireNonNull(lookup);
    Objects.requireNonNull(methods);
    if (methods.isEmpty()) {
      throw new IllegalArgumentException("methods is empty");
    }
    DestructuredVisitor destructuredVisitor = new DestructuredVisitor();
    for(Method method: methods) {
      MethodHandle mh;
      try {
        mh = lookup.unreflect(method);
      } catch (IllegalAccessException e) {
        throw (IllegalAccessError) new IllegalAccessError().initCause(e);
      }
      MethodType methodType = mh.type();
      if (methodType.parameterCount() == 0) {
        throw new IllegalArgumentException("method " + method + " should have at least one parameter");
      }
      AnnotatedType annotatedReceiver = method.getAnnotatedReceiverType();
      Annotation[][] parameterAnnotations = (annotatedReceiver == null) ?
          method.getParameterAnnotations() :
          Stream.concat(
              Stream.<Annotation[]>of(annotatedReceiver.getAnnotations()),
              Arrays.stream(method.getParameterAnnotations())
          ).toArray(Annotation[][]::new);
      MethodData methodData = new MethodData(mh, toSignatures(parameterAnnotations));

      // inject the method into the cache
      CURRENT_METHOD_DATA.set(methodData);
      try {
        destructuredVisitor.cache.get(methodType.parameterType(0));
      } finally {
        CURRENT_METHOD_DATA.remove();
      }
    }
    return destructuredVisitor;
  }

  private static MethodType toMethodType(Class<?>[] signatureClasses) {
    return methodType(
        signatureClasses[0],
        Arrays.stream(signatureClasses).skip(1).toArray(Class<?>[]::new));
  }

  private static final class InliningCache extends MutableCallSite {
    private static final MethodHandle FALLBACK, POINTER_CHECK;
    static {
      Lookup lookup = lookup();
      try {
        FALLBACK = lookup.findVirtual(InliningCache.class, "fallback",
            methodType(MethodHandle.class, Object.class));
        POINTER_CHECK = lookup.findStatic(InliningCache.class, "pointerCheck",
            methodType(boolean.class, Class.class, Object.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }

    private final ClassValue<DestructuredVisitor.MethodData> cache;

    private MethodHandle appendInliningCaches(MethodHandle target, List<Signature> signatures) {
      MethodType type = target.type();
      for(int i = type.parameterCount(); --i >= 0;) {
        Signature signature = signatures.get(i);
        if (signature != null) {
          MethodHandle inliningCacheMH = new InliningCache(toMethodType(signature.value()), cache).dynamicInvoker();
          target = MethodHandles.insertArguments(target, i, inliningCacheMH);
        }
      }
      return target;
    }

    public InliningCache(MethodType type, ClassValue<DestructuredVisitor.MethodData> cache) {
      super(type);
      this.cache = cache;
      setTarget(foldArguments(exactInvoker(type), FALLBACK.bindTo(this).asType(methodType(MethodHandle.class, type.parameterType(0)))));
    }

    private static boolean pointerCheck(Class<?> type, Object receiver) {
      return type == receiver.getClass();
    }

    private MethodHandle fallback(Object receiver) {
      Class<?> receiverClass = receiver.getClass();
      DestructuredVisitor.MethodData methodData = cache.get(receiverClass);
      MethodHandle target = appendInliningCaches(methodData.target, methodData.signatures).asType(type());

      MethodHandle test = POINTER_CHECK.bindTo(receiverClass);
      MethodHandle guard = MethodHandles.guardWithTest(
          test.asType(methodType(boolean.class, type().parameterType(0))),
          target,
          new InliningCache(type(), cache).dynamicInvoker()
      );
      setTarget(guard);
      return target;
    }
  }

  /**
   * Creates a dispatch method handle able to call one of the visit methods registered
   * by {@link #of(Lookup, List)}.
   * To be fully expanded by the JIT, the returned method handle has to be considered as a constant by the JIT,
   * for example by storing the method handle in a static final field.
   *
   * @param signatureClasses the return type and the parameter types of the dispatch method
   * @return a dispatch method handle
   * @throws NullPointerException if {code signatureClasses} is null
   */
  public MethodHandle createDispatch(Class<?>... signatureClasses) {
    Objects.requireNonNull(signatureClasses);
    return new InliningCache(toMethodType(signatureClasses), cache).dynamicInvoker();
  }
}
