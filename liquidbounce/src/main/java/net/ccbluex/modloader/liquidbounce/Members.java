package net.ccbluex.modloader.liquidbounce;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The classes and members of LiquidBounce and the game, by name. A missing one means the client is of a version
 * this library does not know.
 *
 * @agent claude-fable-5-2
 */
final class Members {

    static final String CLIENT = "net.ccbluex.liquidbounce.";

    private Members() {
    }

    /** Without initializing the class: a lookup must not start anything. */
    static Class<?> type(String name) {
        try {
            return Class.forName(name, false, Members.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw missing(name, e);
        }
    }

    static Method method(Class<?> owner, String name, Class<?>... parameters) {
        try {
            Method method = owner.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            throw missing(owner.getName() + "." + name, e);
        }
    }

    static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            throw missing(owner.getName() + "." + name, e);
        }
    }

    static Constructor<?> constructor(Class<?> owner, Class<?>... parameters) {
        try {
            return owner.getDeclaredConstructor(parameters);
        } catch (NoSuchMethodException e) {
            throw missing(owner.getName() + ".<init>", e);
        }
    }

    private static IllegalStateException missing(String member, Exception cause) {
        return new IllegalStateException(
                member + " is missing: this LiquidBounce build needs a newer modloader-liquidbounce", cause);
    }

    static @Nullable Object invoke(Method method, @Nullable Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException e) {
            throw thrown(e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@link #invoke} for a method that returns a value. */
    static Object call(Method method, @Nullable Object target, Object... arguments) {
        return Objects.requireNonNull(invoke(method, target, arguments), method.getName());
    }

    static Object create(Constructor<?> constructor, Object... arguments) {
        try {
            return constructor.newInstance(arguments);
        } catch (InvocationTargetException e) {
            throw thrown(e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A static field with {@code null} as target; reading one initializes its class. */
    static Object get(Field field, @Nullable Object target) {
        try {
            return Objects.requireNonNull(field.get(target), field.getName());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static void set(Field field, Object target, Object value) {
        try {
            field.set(target, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RuntimeException thrown(InvocationTargetException e) {
        if (e.getCause() instanceof Error error) {
            throw error;
        }
        return e.getCause() instanceof RuntimeException exception ? exception : new IllegalStateException(e.getCause());
    }
}
