package net.ccbluex.modloader.mixin;

import java.lang.instrument.Instrumentation;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Links the calls the compiler writes into classes in place of what a loaded game class refuses: a method a
 * mixin moved to a companion, a member the mod may not access, a field, method or interface a mixin added.
 * The first argument of a call names its kind, after the instruction it stands for.
 *
 * @agent claude-fable-5-2
 * @agent claude-opus-5-5
 */
public final class Linker {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final MethodHandle IS_INSTANCE;
    private static final MethodHandle CAST;
    private static final MethodHandle FIND;
    private static final MethodHandle FIND_STATIC;
    private static final MethodHandle CLASS_IS_INSTANCE;
    private static final MethodHandle LAMBDA;

    static {
        try {
            IS_INSTANCE = LOOKUP.findVirtual(Linker.class, "isInstance", MethodType.methodType(boolean.class, Class.class, Object.class));
            CAST = LOOKUP.findVirtual(Linker.class, "cast", MethodType.methodType(Object.class, Class.class, Object.class));
            FIND = LOOKUP.findVirtual(Dispatch.class, "find", MethodType.methodType(MethodHandle.class, Object.class));
            FIND_STATIC = LOOKUP.findVirtual(Dispatch.class, "find", MethodType.methodType(MethodHandle.class, Class.class));
            CLASS_IS_INSTANCE = LOOKUP.findVirtual(Class.class, "isInstance", MethodType.methodType(boolean.class, Object.class));
            LAMBDA = LOOKUP.findStatic(Linker.class, "lambda", MethodType.methodType(Object.class, Class.class, String.class, MethodHandle.class, Object[].class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * What the mixins of the loaded mods added to the game.
     *
     * @param companions companion classes by binary name
     * @param methods by class, the methods mixins added to it: name and descriptor to the companion method
     * @param interfaces by interface, the classes mixins added it to
     * @param fields by class and field name, what computes the value of a field a mixin added for an object
     */
    record Generation(
            Map<String, Class<?>> companions,
            Map<Class<?>, Map<String, MethodHandle>> methods,
            Map<Class<?>, List<Class<?>>> interfaces,
            Map<String, MethodHandle> fields) {
    }

    private final Instrumentation instrumentation;
    private final Object unsafe;
    private final Map<String, VirtualField> fields = new ConcurrentHashMap<>();
    volatile Generation generation = new Generation(Map.of(), Map.of(), Map.of(), Map.of());

    Linker(Instrumentation instrumentation) throws ReflectiveOperationException {
        this.instrumentation = instrumentation;
        Class<?> type = Class.forName("jdk.internal.misc.Unsafe");
        instrumentation.redefineModule(type.getModule(), Set.of(),
                Map.of(type.getPackageName(), Set.of(Linker.class.getModule())), Map.of(), Set.of(), Map.of());
        this.unsafe = type.getMethod("getUnsafe").invoke(null);
    }

    boolean isInitialized(Class<?> type) throws ReflectiveOperationException {
        return Boolean.FALSE.equals(unsafe("shouldBeInitialized", type));
    }

    private Object unsafe(String method, Object argument) throws ReflectiveOperationException {
        return unsafe.getClass().getMethod(method, argument.getClass()).invoke(unsafe, argument);
    }

    /**
     * The bootstrap method behind Bridge.link. A call of a method whose descriptor names a class the caller
     * cannot gets the descriptor it was written with as its last argument; the caller's own has other types there.
     */
    public CallSite link(MethodHandles.Lookup caller, String name, MethodType type, Object... arguments)
            throws ReflectiveOperationException {
        String kind = (String) arguments[0];
        String subject = (String) arguments[1];
        ClassLoader loader = caller.lookupClass().getClassLoader();
        MethodHandle handle;
        if (kind.equals("companion")) {
            Class<?> companion = generation.companions().get(subject);
            if (companion == null) {
                throw new IllegalStateException(subject + " belongs to mixins that are no longer loaded");
            }
            // The name is unique in the companion, whose descriptors may differ from the caller's in the same way.
            Method method = Stream.of(companion.getMethods()).filter(each -> each.getName().equals(name)).findFirst()
                    .orElseThrow(() -> new NoSuchMethodException(subject + "." + name));
            handle = LOOKUP.unreflect(method);
        } else if (kind.equals("lambda")) {
            handle = MethodHandles.insertArguments(LAMBDA, 0, Class.forName(subject, false, loader), name,
                    (MethodHandle) arguments[2]).asCollector(Object[].class, type.parameterCount());
        } else {
            Class<?> owner = Class.forName(subject, false, loader);
            handle = switch (kind) {
                case "getfield", "getstatic", "putfield", "putstatic" -> field(kind, owner, name, type);
                case "invokevirtual", "invokeinterface", "invokestatic" -> method(kind, owner, name, type, null,
                        declared(arguments, 2, loader));
                // A super call a mixin wrote, made from outside the class it was written for: the third argument.
                case "invokespecial" -> method(kind, owner, name, type, Class.forName((String) arguments[2], false, loader),
                        declared(arguments, 3, loader));
                case "instanceof" -> MethodHandles.insertArguments(IS_INSTANCE, 0, this, owner);
                case "checkcast" -> MethodHandles.insertArguments(CAST, 0, this, owner);
                // The constructor's descriptor is the third argument.
                case "new" -> constructor(owner, declared(arguments, 2, loader));
                default -> throw new IllegalArgumentException(kind);
            };
        }
        return new ConstantCallSite(handle.asType(type));
    }

    private static Object lambda(Class<?> functional, String name, MethodHandle implementation, Object[] captured) {
        MethodHandle bound = MethodHandles.insertArguments(implementation, 0, captured);
        return Proxy.newProxyInstance(functional.getClassLoader(), new Class<?>[] {functional}, (proxy, method, arguments) -> {
            Object[] given = arguments == null ? new Object[0] : arguments;
            if (method.getName().equals(name) && Modifier.isAbstract(method.getModifiers())) {
                return bound.invokeWithArguments(given);
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, given);
            }
            return switch (method.getName()) {
                case "equals" -> proxy == given[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> functional.getName() + "@" + Integer.toHexString(System.identityHashCode(proxy));
            };
        });
    }

    private static MethodType declared(Object[] arguments, int index, ClassLoader loader) {
        return arguments.length > index ? MethodType.fromMethodDescriptorString((String) arguments[index], loader) : null;
    }

    private MethodHandle constructor(Class<?> owner, MethodType declared) throws ReflectiveOperationException {
        Constructor<?> constructor = owner.getDeclaredConstructor(declared.parameterArray());
        open(owner);
        constructor.setAccessible(true);
        return LOOKUP.unreflectConstructor(constructor);
    }

    private MethodHandle field(String kind, Class<?> owner, String name, MethodType type) throws ReflectiveOperationException {
        boolean isStatic = kind.endsWith("static");
        boolean isRead = kind.startsWith("get");
        Class<?> fieldType = isRead ? type.returnType() : type.lastParameterType();
        Field field = findField(owner, name, fieldType);
        if (field == null) {
            String key = owner.getName() + '.' + name;
            VirtualField virtual = fields.computeIfAbsent(key, _ -> new VirtualField(fieldType, generation.fields().get(key)));
            return isRead ? virtual.getter(isStatic) : virtual.setter(isStatic);
        }
        Class<?> declaring = field.getDeclaringClass();
        open(declaring);
        field.setAccessible(true);
        if (isRead) {
            return LOOKUP.unreflectGetter(field);
        }
        boolean isTrusted = Modifier.isFinal(field.getModifiers())
                && (isStatic || declaring.isRecord() || declaring.isHidden());
        return isTrusted ? trustedSetter(field) : LOOKUP.unreflectSetter(field);
    }

    private MethodHandle trustedSetter(Field field) throws ReflectiveOperationException {
        Class<?> type = field.getType().isPrimitive() ? field.getType() : Object.class;
        String kind = type.isPrimitive() ? type.getName() : "reference";
        Method put = unsafe.getClass().getMethod(
                "put" + kind.substring(0, 1).toUpperCase(Locale.ROOT) + kind.substring(1),
                Object.class, long.class, type);
        MethodHandle setter = LOOKUP.unreflect(put).bindTo(unsafe);
        if (Modifier.isStatic(field.getModifiers())) {
            return MethodHandles.insertArguments(setter, 0,
                    unsafe("staticFieldBase", field), unsafe("staticFieldOffset", field));
        }
        return MethodHandles.insertArguments(setter, 1, unsafe("objectFieldOffset", field));
    }

    /** @param declared the method's descriptor, when the call site's has other types */
    private MethodHandle method(String kind, Class<?> owner, String name, MethodType type, Class<?> superCaller, MethodType declared)
            throws IllegalAccessException {
        boolean isStatic = kind.equals("invokestatic");
        MethodType signature = declared != null ? declared : isStatic ? type : type.dropParameterTypes(0, 1);
        Method method = findMethod(owner, name, signature);
        MethodHandle direct = null;
        if (method != null) {
            open(method.getDeclaringClass());
            method.setAccessible(true);
            if (superCaller == null) {
                direct = LOOKUP.unreflect(method);
            } else {
                open(superCaller);
                direct = MethodHandles.privateLookupIn(superCaller, LOOKUP).unreflectSpecial(method, superCaller);
            }
        }
        Dispatch dispatch = new Dispatch(name, signature, type);
        if (isStatic && direct == null) {
            // A static method a mixin added, such as an accessor; its owner is the class it was called on.
            return MethodHandles.foldArguments(MethodHandles.exactInvoker(type), MethodHandles.insertArguments(FIND_STATIC, 0, dispatch, owner));
        }
        if (isStatic || superCaller != null) {
            if (direct == null) {
                return dispatch.find(owner);
            }
            return direct;
        }
        MethodHandle added = MethodHandles.foldArguments(MethodHandles.exactInvoker(type),
                FIND.bindTo(dispatch).asType(MethodType.methodType(MethodHandle.class, type.parameterType(0))));
        if (direct == null) {
            return added;
        }
        if (!kind.equals("invokeinterface")) {
            return direct;
        }
        // The receiver either implements the interface or got it from a mixin.
        MethodHandle implementsIt = CLASS_IS_INSTANCE.bindTo(method.getDeclaringClass())
                .asType(MethodType.methodType(boolean.class, type.parameterType(0)));
        return MethodHandles.guardWithTest(implementsIt, direct.asType(type), added);
    }

    /** Finds the companion method standing for a method a mixin added to the class of the receiver. */
    private final class Dispatch {

        private record Cache(Generation generation, Map<Class<?>, MethodHandle> handles) {
        }

        private final String name;
        private final MethodType signature;
        private final String method;
        private final MethodType type;
        private volatile Cache cache = new Cache(null, Map.of());

        Dispatch(String name, MethodType signature, MethodType type) {
            this.name = name;
            this.signature = signature;
            this.method = name + signature.toMethodDescriptorString();
            this.type = type;
        }

        @SuppressWarnings("unused")
        MethodHandle find(Object receiver) {
            return find(receiver.getClass());
        }

        MethodHandle find(Class<?> declaring) {
            Cache current = cache;
            if (current.generation() != generation) {
                current = new Cache(generation, new ConcurrentHashMap<>());
                cache = current;
            }
            Generation from = current.generation();
            return current.handles().computeIfAbsent(declaring, receiverClass -> {
                MethodHandle handle = added(from, receiverClass);
                if (handle == null) {
                    handle = own(receiverClass);
                }
                if (handle == null) {
                    throw new AbstractMethodError(receiverClass.getName() + "." + method);
                }
                return handle.asType(type);
            });
        }

        /** The method as a mixin added it to the class or to what it extends or implements. */
        private MethodHandle added(Generation from, Class<?> declaring) {
            if (declaring == null) {
                return null;
            }
            MethodHandle handle = from.methods().getOrDefault(declaring, Map.of()).get(method);
            if (handle == null) {
                handle = added(from, declaring.getSuperclass());
            }
            for (Class<?> implemented : declaring.getInterfaces()) {
                if (handle == null) {
                    handle = added(from, implemented);
                }
            }
            return handle;
        }

        /**
         * The method the class has itself, as the game's framework wrote it, which the mod's call reaches as the
         * JVM's would when the class implemented the interface. A mixin left out its accessor for that method.
         */
        private MethodHandle own(Class<?> declaring) {
            Method own = findMethod(declaring, name, signature);
            if (own == null || Modifier.isAbstract(own.getModifiers())) {
                return null;
            }
            open(own.getDeclaringClass());
            own.setAccessible(true);
            try {
                return LOOKUP.unreflect(own);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @SuppressWarnings("unused")
    private boolean isInstance(Class<?> type, Object object) {
        if (object == null) {
            return false;
        }
        if (type.isInstance(object)) {
            return true;
        }
        for (Class<?> mixed : generation.interfaces().getOrDefault(type, List.of())) {
            if (mixed.isInstance(object)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unused")
    private Object cast(Class<?> type, Object object) {
        if (object != null && !isInstance(type, object)) {
            throw new ClassCastException(object.getClass().getName() + " cannot be cast to " + type.getName());
        }
        return object;
    }

    /**
     * As the JVM looks a field up: by name and type, in the class, its interfaces, its superclass. A mixin may add a
     * field of a name the class has with another type. The type may be a superclass the compiler put in place of
     * the field's.
     */
    private static Field findField(Class<?> owner, String name, Class<?> type) {
        if (owner == null) {
            return null;
        }
        for (Field field : owner.getDeclaredFields()) {
            if (field.getName().equals(name) && type.isAssignableFrom(field.getType())) {
                return field;
            }
        }
        for (Class<?> implemented : owner.getInterfaces()) {
            Field field = findField(implemented, name, type);
            if (field != null) {
                return field;
            }
        }
        return findField(owner.getSuperclass(), name, type);
    }

    private static Method findMethod(Class<?> owner, String name, MethodType signature) {
        if (owner == null) {
            return null;
        }
        for (Method method : owner.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getReturnType() == signature.returnType()
                    && List.of(method.getParameterTypes()).equals(signature.parameterList())) {
                return method;
            }
        }
        Method inherited = findMethod(owner.getSuperclass(), name, signature);
        for (Class<?> implemented : owner.getInterfaces()) {
            if (inherited == null) {
                inherited = findMethod(implemented, name, signature);
            }
        }
        return inherited;
    }

    /**
     * Drops the values of the fields mixins added to classes other than these, and has the others computed by the
     * code of the current generation.
     */
    void forget(Set<Class<?>> mixed) {
        Set<String> owners = new HashSet<>();
        mixed.forEach(type -> owners.add(type.getName()));
        fields.keySet().removeIf(field -> !owners.contains(field.substring(0, field.lastIndexOf('.'))));
        fields.forEach((key, field) -> field.initializer = generation.fields().get(key));
    }

    /** Reflection into a named module needs its package opened to this one. */
    private void open(Class<?> type) {
        Module module = type.getModule();
        Module self = Linker.class.getModule();
        if (!module.isOpen(type.getPackageName(), self)) {
            instrumentation.redefineModule(module, Set.of(), Map.of(), Map.of(type.getPackageName(), Set.of(self)), Set.of(), Map.of());
        }
    }
}
