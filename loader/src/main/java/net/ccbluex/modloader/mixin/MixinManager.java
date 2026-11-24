package net.ccbluex.modloader.mixin;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;
import net.ccbluex.modloader.Framework;
import net.ccbluex.modloader.bridge.Bridge;

/**
 * Keeps the game's classes in line with the mixins of the loaded mods. Every change of the loaded mods
 * compiles all their mixins again from the classes as the game first defined them, so taking a mod out is
 * the same step as putting one in.
 *
 * @agent claude-fable-5-2
 * @agent claude-opus-5-5
 */
public final class MixinManager {

    private final Instrumentation instrumentation;
    private final ClassLoader game = MixinManager.class.getClassLoader().getParent();
    private final URL[] libraries;
    private final Function<String, Class<?>> modClasses;
    private final Function<String, byte[]> preMixinClasses;
    private final Linker linker;
    private final ClassLoader support;

    private final Map<Class<?>, byte[]> baselines = new ConcurrentHashMap<>();
    private final Map<Class<?>, String> initialized = new HashMap<>();
    private Map<String, List<MixinCompiler.GameConfig>> gameConfigs;
    private List<URL> modJars = List.of();
    private Set<String> skipped = Set.of();
    private Set<Class<?>> redefined = Set.of();
    private volatile MixinCompiler compiler;

    /**
     * @param lib the directory of the compiler's jars
     * @param modClasses a class of a loaded mod by name, null when no mod has it
     * @param preMixinClasses a class as the game's framework hands it to the game's Mixin, null when it has none
     */
    public MixinManager(Instrumentation instrumentation, Path lib, Function<String, Class<?>> modClasses,
            Function<String, byte[]> preMixinClasses) throws IOException, ReflectiveOperationException {
        this.instrumentation = instrumentation;
        this.modClasses = modClasses;
        this.preMixinClasses = preMixinClasses;
        try (Stream<Path> jars = Files.list(lib)) {
            List<URL> urls = new ArrayList<>();
            for (Path jar : jars.toList()) {
                urls.add(jar.toUri().toURL());
            }
            this.libraries = urls.toArray(URL[]::new);
        }
        this.support = new URLClassLoader("modloader-mixin-support", libraries, game);
        this.linker = new Linker(instrumentation);
        Bridge.linker = MethodHandles.lookup().bind(linker, "link",
                MethodType.methodType(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class, Object[].class));
    }

    /** What Mixin makes of the game's classes for a set of configs, not yet in the game: see {@link #apply(Compilation)}. */
    public record Compilation(MixinCompiler compiler, Map<String, Redefinition> redefinitions) {
    }

    /**
     * Runs Mixin over the game's classes for these configs. Slow; changes nothing in the game.
     *
     * @param configs mixin config names by the id of their mod
     * @param relaxed ids of the mods whose injectors may find no target, see {@link MixinCompiler#compile}
     * @param replacements what the game's frameworks replaced, see {@link MixinCompiler#compile}
     * @param modJars the jars of every loaded mod
     */
    public synchronized Compilation compile(Map<String, List<String>> configs, Set<String> relaxed,
            List<Framework.Replacement> replacements, List<URL> modJars) {
        this.modJars = modJars;
        MixinCompiler next = compiler(modJars);
        if (configs.isEmpty()) {
            return new Compilation(next, Map.of());
        }
        Thread thread = Thread.currentThread();
        ClassLoader context = thread.getContextClassLoader();
        // A mixin plugin may look services of its mod up through the thread's class loader.
        thread.setContextClassLoader(next.getClass().getClassLoader());
        try {
            return new Compilation(next, next.compile(configs, relaxed, replacements, this::gameConfigs, preMixinClasses, this::baseline,
                    this::isInitialized));
        } finally {
            thread.setContextClassLoader(context);
        }
    }

    /**
     * Redefines the game's classes to what the compilation made of them, and back to what they were where no config
     * targets them any more. Changes nothing when it fails.
     *
     * @return what the mixins ask for that had to be left out and was not left out before
     */
    public synchronized List<String> apply(Compilation compilation) throws ReflectiveOperationException, UnmodifiableClassException {
        if (compilation.redefinitions().isEmpty() && redefined.isEmpty()) {
            // The compiler tells the classes of the mods from the game's by their jars.
            compiler = compilation.compiler();
            return List.of();
        }
        // From here on classes of the mods load, and they have to be rewritten by the compiler that knows these mixins.
        MixinCompiler old = compiler;
        compiler = compilation.compiler();
        try {
            return apply(compilation.compiler(), compilation.redefinitions());
        } catch (ReflectiveOperationException | UnmodifiableClassException | RuntimeException | LinkageError e) {
            compiler = old;
            throw e;
        }
    }

    private List<String> apply(MixinCompiler next, Map<String, Redefinition> redefinitions)
            throws ReflectiveOperationException, UnmodifiableClassException {
        GeneratedClassLoader generated = new GeneratedClassLoader(next, modClasses, support);
        Linker.Generation previous = linker.generation;
        // The companions in use stay linkable until their classes are redefined.
        Map<String, Class<?>> companions = new HashMap<>(previous.companions());
        Map<String, Class<?>> added = new HashMap<>();
        Map<Class<?>, Map<String, MethodHandle>> methods = new HashMap<>();
        Map<Class<?>, List<Class<?>>> interfaces = new HashMap<>();
        List<ClassDefinition> definitions = new ArrayList<>();
        Map<Class<?>, Method> initializers = new HashMap<>();
        Set<Class<?>> relaxed = new HashSet<>();
        Map<String, MethodHandle> fields = new HashMap<>();
        for (Map.Entry<String, Redefinition> entry : redefinitions.entrySet()) {
            Redefinition redefinition = entry.getValue();
            Class<?> target = Class.forName(entry.getKey(), false, game);
            Class<?> companion = generated.define(redefinition.companionName(), redefinition.companion());
            added.put(redefinition.companionName(), companion);

            Map<String, MethodHandle> addedMethods = new HashMap<>();
            for (Method method : companion.getMethods()) {
                for (Map.Entry<String, String> addedMethod : redefinition.methods().entrySet()) {
                    if (addedMethod.getValue().equals(method.getName())) {
                        addedMethods.put(addedMethod.getKey(), MethodHandles.lookup().unreflect(method));
                    }
                }
                if (method.getName().equals(redefinition.initializer())) {
                    initializers.put(target, method);
                }
                for (Map.Entry<String, String> field : redefinition.fields().entrySet()) {
                    if (field.getValue().equals(method.getName())) {
                        fields.put(target.getName() + '.' + field.getKey(), MethodHandles.lookup().unreflect(method));
                    }
                }
            }
            methods.put(target, addedMethods);
            if (redefinition.isRelaxed()) {
                relaxed.add(target);
            }
            for (String name : redefinition.interfaces()) {
                register(interfaces, Class.forName(name, false, generated), target);
            }
            definitions.add(new ClassDefinition(target, redefinition.target()));
        }
        for (Class<?> reverted : redefined) {
            if (!methods.containsKey(reverted)) {
                definitions.add(new ClassDefinition(reverted, baselines.get(reverted)));
            }
        }

        companions.putAll(added);
        linker.generation = new Linker.Generation(companions, methods, interfaces, fields);
        List<String> failed = new ArrayList<>();
        try {
            for (Map.Entry<Class<?>, Method> initializer : initializers.entrySet()) {
                // The name carries a hash of the code: static state set up before survives other mods coming and going.
                if (!initializer.getValue().getName().equals(initialized.put(initializer.getKey(), initializer.getValue().getName()))) {
                    try {
                        initializer.getValue().invoke(null);
                    } catch (InvocationTargetException e) {
                        if (!relaxed.contains(initializer.getKey())) {
                            throw e;
                        }
                        Throwable cause = e;
                        while (cause.getCause() != null) {
                            cause = cause.getCause();
                        }
                        failed.add("what the mixins append to the static initializer of " + initializer.getKey().getName()
                                + ", which fails: " + cause);
                    }
                }
            }
            instrumentation.redefineClasses(definitions.toArray(ClassDefinition[]::new));
        } catch (ReflectiveOperationException | UnmodifiableClassException | RuntimeException | LinkageError e) {
            linker.generation = previous;
            throw e;
        }
        linker.generation = new Linker.Generation(added, methods, interfaces, fields);
        // shortcut: fields a mod added stay when another mod still mixes into the class; attribute fields to mods if a reload ever reads stale ones
        initialized.keySet().retainAll(methods.keySet());
        linker.forget(methods.keySet());
        redefined = Set.copyOf(methods.keySet());
        Set<String> before = skipped;
        // In order, so that notes of one class stay together.
        skipped = new LinkedHashSet<>();
        redefinitions.values().forEach(redefinition -> skipped.addAll(redefinition.skipped()));
        skipped.addAll(failed);
        return skipped.stream().filter(note -> !before.contains(note)).toList();
    }

    /** A class of a mod as it has to be defined: see {@link MixinCompiler#rewrite}. */
    public byte[] rewrite(ClassLoader loader, byte[] modClass) {
        MixinCompiler current = compiler;
        if (current == null) {
            synchronized (this) {
                if (compiler == null) {
                    compiler = compiler(List.of());
                }
                current = compiler;
            }
        }
        return current.rewrite(loader, modClass);
    }

    private MixinCompiler compiler(List<URL> modJars) {
        List<URL> urls = new ArrayList<>(List.of(libraries));
        urls.addAll(modJars);
        return ServiceLoader.load(MixinCompiler.class, new CompilerClassLoader(urls.toArray(URL[]::new))).findFirst().orElseThrow();
    }

    /** An interface a mixin adds brings the interfaces it extends. */
    private static void register(Map<Class<?>, List<Class<?>>> interfaces, Class<?> added, Class<?> target) {
        interfaces.computeIfAbsent(added, _ -> new ArrayList<>()).add(target);
        for (Class<?> extended : added.getInterfaces()) {
            register(interfaces, extended, target);
        }
    }

    private boolean isInitialized(String name) {
        try {
            return linker.isInitialized(Class.forName(name, false, game));
        } catch (ReflectiveOperationException | LinkageError _) {
            return true;
        }
    }

    /** The class as the game defined it, before any mixin of ours; null when the game has no such class. */
    private byte[] baseline(String name) {
        String file = name.replace('.', '/') + ".class";
        URL url = game.getResource(file);
        // The game's class loader also reaches the classes of the mods, which load with the mixins applied.
        if (url != null && modJars.stream().anyMatch(jar -> url.toString().equals("jar:" + jar + "!/" + file))) {
            return null;
        }
        Class<?> type;
        try {
            type = Class.forName(name, false, game);
        } catch (ClassNotFoundException | LinkageError | RuntimeException e) {
            return null;
        }
        return baselines.computeIfAbsent(type, this::capture);
    }

    /**
     * The mixin configs the game's own Mixin applied to the class as it loaded. Only those whose input
     * {@link #preMixinClasses} has: the others would apply twice.
     */
    private List<MixinCompiler.GameConfig> gameConfigs(String target) {
        if (preMixinClasses.apply(target) == null) {
            return List.of();
        }
        if (gameConfigs == null) {
            gameConfigs = readGameConfigs();
        }
        return gameConfigs.getOrDefault(target, List.of());
    }

    /** The game's Mixin keeps its configs in its transformer, which has no public way to them. */
    private Map<String, List<MixinCompiler.GameConfig>> readGameConfigs() {
        Map<String, List<MixinCompiler.GameConfig>> configs = new HashMap<>();
        try {
            Class<?> environment = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment", false, game);
            Object transformer = environment.getMethod("getActiveTransformer").invoke(environment.getMethod("getCurrentEnvironment").invoke(null));
            if (transformer == null) {
                return configs;
            }
            Class<?> type = Class.forName("org.spongepowered.asm.mixin.transformer.IMixinTransformer", false, game);
            while (!transformer.getClass().getName().equals("org.spongepowered.asm.mixin.transformer.MixinTransformer")) {
                transformer = wrapped(transformer, type);
            }
            Field processor = transformer.getClass().getDeclaredField("processor");
            processor.setAccessible(true);
            Object mixinProcessor = processor.get(transformer);
            Field all = mixinProcessor.getClass().getDeclaredField("configs");
            all.setAccessible(true);
            Class<?> config = Class.forName("org.spongepowered.asm.mixin.extensibility.IMixinConfig", false, game);
            Method name = config.getMethod("getName");
            Method targets = config.getMethod("getTargets");
            Method decoration = config.getMethod("getDecoration", String.class);
            int order = 0;
            for (Object each : (List<?>) all.get(mixinProcessor)) {
                String configName = (String) name.invoke(each);
                // Fabric's name for the decoration.
                Object mod = decoration.invoke(each, "fabric-modId");
                MixinCompiler.GameConfig applied = new MixinCompiler.GameConfig(configName,
                        mod == null ? null : mod.toString(),
                        decoration.invoke(each, "fabric-compat") instanceof Integer level ? level : null, order++);
                for (Object target : (Set<?>) targets.invoke(each)) {
                    configs.computeIfAbsent((String) target, _ -> new ArrayList<>()).add(applied);
                }
            }
        } catch (ClassNotFoundException e) {
            // A game without Mixin.
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("cannot read the mixin configs of the game's Mixin", e);
        }
        return configs;
    }

    private static Object wrapped(Object transformer, Class<?> type) throws ReflectiveOperationException {
        for (Field field : transformer.getClass().getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && type.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                Object wrapped = field.get(transformer);
                if (wrapped != null) {
                    return wrapped;
                }
            }
        }
        throw new NoSuchFieldException("the transformer " + transformer.getClass().getName() + " wraps");
    }

    private byte[] capture(Class<?> type) {
        // A class of the JDK is what its class file says; retransforming those costs a safepoint each.
        if (type.getClassLoader() == null || !instrumentation.isModifiableClass(type)) {
            try (InputStream in = ClassLoader.getSystemResourceAsStream(type.getName().replace('.', '/') + ".class")) {
                return in == null ? null : in.readAllBytes();
            } catch (IOException e) {
                return null;
            }
        }
        // The JVM hands a retransforming agent the bytes the class was defined from, load-time mixins included.
        byte[][] captured = new byte[1][];
        ClassFileTransformer capture = new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> retransformed, ProtectionDomain domain, byte[] bytes) {
                if (retransformed == type) {
                    captured[0] = bytes;
                }
                return null;
            }
        };
        instrumentation.addTransformer(capture, true);
        try {
            instrumentation.retransformClasses(type);
        } catch (UnmodifiableClassException e) {
            return null;
        } finally {
            instrumentation.removeTransformer(capture);
        }
        return captured[0];
    }
}
