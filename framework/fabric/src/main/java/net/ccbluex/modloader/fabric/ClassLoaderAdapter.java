package net.ccbluex.modloader.fabric;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;
import org.jspecify.annotations.Nullable;

/**
 * Creates a mod's entrypoints from its own class loader the way Fabric's Java and Kotlin adapters do from Knot:
 * {@code Class}, a Kotlin {@code object}, or {@code Class::member} for a static field or a method. Knot keeps every
 * class it was asked for, so after a reload it would create the entrypoints of the jar loaded first.
 *
 * @agent claude-opus-5-5
 * @agent claude-fable-5-2
 */
public final class ClassLoaderAdapter implements LanguageAdapter {

    private final ClassLoader loader;
    private final boolean isKotlin;

    public ClassLoaderAdapter(ClassLoader loader, boolean isKotlin) {
        this.loader = loader;
        this.isKotlin = isKotlin;
    }

    @Override
    public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
        String[] parts = value.split("::");
        if (parts.length > 2) {
            throw new LanguageAdapterException("Invalid handle format: " + value);
        }
        try {
            Class<?> owner = Class.forName(parts[0], true, loader);
            if (parts.length == 1) {
                if (!type.isAssignableFrom(owner)) {
                    throw new LanguageAdapterException(
                            "Class " + owner.getName() + " cannot be cast to " + type.getName() + "!");
                }
                return type.cast(instance(owner));
            }
            Object companion = isKotlin ? staticField(owner, "Companion") : null;
            if (companion != null && !hasMember(owner, parts[1])) {
                return member(companion.getClass(), companion, parts[1], value, type);
            }
            return member(owner, null, parts[1], value, type);
        } catch (ReflectiveOperationException e) {
            throw new LanguageAdapterException(e);
        }
    }

    private <T> T member(Class<?> owner, @Nullable Object receiver, String name, String value, Class<T> type)
            throws ReflectiveOperationException, LanguageAdapterException {
        List<Method> methods = methods(owner, name);
        Field field = declaredField(owner, name);
        if (field != null && (receiver != null || Modifier.isStatic(field.getModifiers()))) {
            if (!methods.isEmpty()) {
                throw new LanguageAdapterException("Ambiguous " + value + " - refers to both field and method!");
            }
            if (!type.isAssignableFrom(field.getType())) {
                throw new LanguageAdapterException("Field " + value + " cannot be cast to " + type.getName() + "!");
            }
            field.setAccessible(true);
            return type.cast(field.get(Modifier.isStatic(field.getModifiers()) ? null : receiver));
        }
        if (!type.isInterface()) {
            throw new LanguageAdapterException(
                    "Cannot proxy method " + value + " to non-interface type " + type.getName() + "!");
        }
        if (methods.size() != 1) {
            throw new LanguageAdapterException(
                    (methods.isEmpty() ? "Could not find " : "Found multiple method entries of name ") + value + "!");
        }
        Method method = methods.getFirst();
        method.setAccessible(true);
        MethodHandle handle = MethodHandles.lookup().unreflect(method);
        if (!Modifier.isStatic(method.getModifiers())) {
            handle = handle.bindTo(receiver != null ? receiver : instance(owner));
        }
        return MethodHandleProxies.asInterfaceInstance(type, handle);
    }

    private Object instance(Class<?> owner) throws ReflectiveOperationException {
        Object object = isKotlin ? staticField(owner, "INSTANCE") : null;
        if (object != null && owner.isInstance(object)) {
            return object;
        }
        Constructor<?> constructor = owner.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static @Nullable Object staticField(Class<?> owner, String name) throws IllegalAccessException {
        Field field = declaredField(owner, name);
        if (field == null || !Modifier.isStatic(field.getModifiers())) {
            return null;
        }
        field.setAccessible(true);
        return field.get(null);
    }

    private static boolean hasMember(Class<?> owner, String name) {
        return declaredField(owner, name) != null || !methods(owner, name).isEmpty();
    }

    private static List<Method> methods(Class<?> owner, String name) {
        return Arrays.stream(owner.getDeclaredMethods()).filter(method -> method.getName().equals(name)).toList();
    }

    private static @Nullable Field declaredField(Class<?> owner, String name) {
        try {
            return owner.getDeclaredField(name);
        } catch (NoSuchFieldException _) {
            return null;
        }
    }
}
