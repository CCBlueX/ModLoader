package net.ccbluex.modloader.mixin;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A field a mixin adds to a class that is already loaded and so cannot grow. Its values live here, by
 * instance, for as long as the instance does. An instance that existed before the mixin gets the mixin's
 * initializer on the first read when it has one, else the type's zero.
 *
 * @agent claude-fable-5-2
 * @agent claude-opus-5-5
 */
final class VirtualField {

    private static final MethodHandle GET;
    private static final MethodHandle SET;
    private static final MethodHandle GET_STATIC;
    private static final MethodHandle SET_STATIC;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            GET = lookup.findVirtual(VirtualField.class, "get", MethodType.methodType(Object.class, Object.class));
            SET = lookup.findVirtual(VirtualField.class, "set", MethodType.methodType(void.class, Object.class, Object.class));
            GET_STATIC = lookup.findVirtual(VirtualField.class, "get", MethodType.methodType(Object.class));
            SET_STATIC = lookup.findVirtual(VirtualField.class, "set", MethodType.methodType(void.class, Object.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Stands for null, which the map cannot hold. */
    private static final Object NULL = new Object();

    private final Object zero;
    private final Map<Instance, Object> values = new ConcurrentHashMap<>();
    private final ReferenceQueue<Object> collected = new ReferenceQueue<>();
    private volatile Object staticValue;
    /** {@code (Object) -> Object}, the value of the field for an instance that has none yet; null for the zero. */
    volatile MethodHandle initializer;

    VirtualField(Class<?> type, MethodHandle initializer) {
        // Unboxing a missing value would throw, so primitives start at their zero.
        this.zero = type.isPrimitive() ? Array.get(Array.newInstance(type, 1), 0) : null;
        this.staticValue = zero;
        this.initializer = initializer;
    }

    /** {@code (Object) -> Object}, or {@code () -> Object} for a static field. */
    MethodHandle getter(boolean isStatic) {
        return (isStatic ? GET_STATIC : GET).bindTo(this);
    }

    /** {@code (Object, Object) -> void}, or {@code (Object) -> void} for a static field. */
    MethodHandle setter(boolean isStatic) {
        return (isStatic ? SET_STATIC : SET).bindTo(this);
    }

    private Object get() {
        return staticValue;
    }

    private void set(Object value) {
        staticValue = value;
    }

    private Object get(Object instance) throws Throwable {
        Object value = values.get(new Instance(instance, null));
        if (value == null) {
            MethodHandle first = initializer;
            if (first == null) {
                return zero;
            }
            set(instance, first.invoke(instance));
            value = values.get(new Instance(instance, null));
        }
        return value == NULL ? null : value;
    }

    private void set(Object instance, Object value) {
        for (Reference<?> gone = collected.poll(); gone != null; gone = collected.poll()) {
            values.remove(gone);
        }
        values.put(new Instance(instance, collected), value == null ? NULL : value);
    }

    /** Map key that is the instance itself, not what it equals, and does not keep it alive. */
    private static final class Instance extends WeakReference<Object> {

        private final int hash;

        Instance(Object instance, ReferenceQueue<Object> queue) {
            super(instance, queue);
            this.hash = System.identityHashCode(instance);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            return other == this || other instanceof Instance key && key.get() == get() && get() != null;
        }
    }
}
