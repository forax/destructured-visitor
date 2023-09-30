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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static java.lang.invoke.MethodHandles.exactInvoker;
import static java.lang.invoke.MethodHandles.foldArguments;
import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodType.methodType;
import static java.util.stream.Collectors.toList;

public class DestructuredVisitor {
  @Target(ElementType.PARAMETER)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface Signature {
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

  public static DestructuredVisitor of(Lookup lookup, List<Method> methods) {
    Objects.requireNonNull(lookup);
    Objects.requireNonNull(methods);
    if (methods.isEmpty()) {
      throw new IllegalArgumentException("methods is empty");
    }
    DestructuredVisitor destructuredVisitor = new DestructuredVisitor();
    for(Method method: methods) {
      if (!Modifier.isStatic(method.getModifiers())) {
        throw new IllegalArgumentException("method " + method + " is not static");
      }
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
      MethodData methodData = new MethodData(mh, toSignatures(method.getParameterAnnotations()));

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

  public MethodHandle createDispatch(Class<?>... signatureClasses) {
    Objects.requireNonNull(signatureClasses);
    return new InliningCache(toMethodType(signatureClasses), cache).dynamicInvoker();
  }
}
