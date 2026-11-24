package net.ccbluex.modloader.mixin.compiler;

import net.ccbluex.modloader.Framework;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.launch.platform.container.ContainerHandleVirtual;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.MixinEnvironment.CompatibilityLevel;
import org.spongepowered.asm.mixin.MixinEnvironment.Phase;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.struct.InjectionInfo;
import org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;
import org.spongepowered.asm.service.IAdviceProvider;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.IMixinAuditTrail;
import org.spongepowered.asm.service.IMixinInternal;
import org.spongepowered.asm.service.IMixinService;
import org.spongepowered.asm.service.ITransformerProvider;
import org.spongepowered.asm.util.Annotations;
import org.spongepowered.asm.util.Constants;
import org.spongepowered.asm.util.asm.MethodNodeEx;
import org.spongepowered.asm.service.IFeatureValidator;
import org.spongepowered.asm.util.ReEntranceLock;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What Mixin asks for classes, resources and loggers. Mixin creates it by name, so the session hands itself in
 * through the static fields.
 *
 * @agent claude-fable-5-2
 * @agent claude-opus-5-5
 */
public final class MixinHost implements IMixinService, IClassProvider, IClassBytecodeProvider {
    static Function<String, byte[]> sources;
    static Function<String, byte[]> classes;
    static boolean client;
    static IMixinTransformerFactory transformers;
    /** Mixin packages of the mods running outside their own framework, see {@link #relax}. */
    static final Set<String> relaxedPackages = new HashSet<>();
    /** Marks a handler {@link #relax} made optional; its value is the handler's name in the mixin. */
    static final String RELAXED = "Lnet/ccbluex/modloader/mixin/compiler/Relaxed;";
    /** The classes of {@link #relaxedPackages} as Mixin got them, by binary name; see {@link #drop}. */
    static final Map<String, List<ClassNode>> relaxedClasses = new HashMap<>();
    /** By target, what {@link #drop} took out, in words for the person loading the mod. */
    static final Map<String, List<String>> dropped = new HashMap<>();
    /** What the game's frameworks replaced, which the injectors of relaxed mixins name; see {@link #replace}. */
    static List<Framework.Replacement> replacements = List.of();

    private final URLClassLoader loader = (URLClassLoader) MixinHost.class.getClassLoader();
    private final ReEntranceLock lock = new ReEntranceLock(1);
    private final Map<String, ILogger> loggers = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return "ModLoader";
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public void prepare() {
    }

    @Override
    public Phase getInitialPhase() {
        return Phase.PREINIT;
    }

    @Override
    public void offer(IMixinInternal internal) {
        if (internal instanceof IMixinTransformerFactory factory) {
            transformers = factory;
        }
    }

    @Override
    public void init() {
    }

    @Override
    public void beginPhase() {
    }

    @Override
    public void checkEnv(Object bootSource) {
    }

    @Override
    public ReEntranceLock getReEntranceLock() {
        return lock;
    }

    @Override
    public IClassProvider getClassProvider() {
        return this;
    }

    @Override
    public IClassBytecodeProvider getBytecodeProvider() {
        return this;
    }

    @Override
    public ITransformerProvider getTransformerProvider() {
        return null;
    }

    /** Without a tracker Mixin cannot find that a target "was loaded too early"; here every target already is. */
    @Override
    public IClassTracker getClassTracker() {
        return null;
    }

    @Override
    public IMixinAuditTrail getAuditTrail() {
        return null;
    }

    @Override
    public IFeatureValidator getFeatureValidator() {
        return IFeatureValidator.ALLOW_ALL;
    }

    @Override
    public IAdviceProvider getAdviceProvider() {
        return IAdviceProvider.GENERIC;
    }

    @Override
    public Collection<String> getPlatformAgents() {
        return List.of("org.spongepowered.asm.launch.platform.MixinPlatformAgentDefault");
    }

    @Override
    public IContainerHandle getPrimaryContainer() {
        return new ContainerHandleVirtual(getName());
    }

    @Override
    public Collection<IContainerHandle> getMixinContainers() {
        return List.of();
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        // Quilt names the configs of its mods "#<mod id>:<resource>".
        return loader.getResourceAsStream(name.startsWith("#") ? name.substring(name.indexOf(':') + 1) : name);
    }

    @Override
    public String getSideName() {
        return client ? Constants.SIDE_CLIENT : Constants.SIDE_SERVER;
    }

    /** The game's classes are Java 25, so no config may ask for less than Mixin would otherwise start at. */
    @Override
    public CompatibilityLevel getMinCompatibilityLevel() {
        return CompatibilityLevel.JAVA_25;
    }

    @Override
    public CompatibilityLevel getMaxCompatibilityLevel() {
        return CompatibilityLevel.JAVA_25;
    }

    @Override
    public ILogger getLogger(String name) {
        return loggers.computeIfAbsent(name, StderrLogger::new);
    }

    @Override
    public URL[] getClassPath() {
        return new URL[0];
    }

    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        return loader.loadClass(name);
    }

    @Override
    public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, loader);
    }

    @Override
    public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, loader);
    }

    @Override
    public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException {
        return getClassNode(name, true, 0);
    }

    @Override
    public ClassNode getClassNode(String name, boolean runTransformers) throws ClassNotFoundException, IOException {
        return getClassNode(name, runTransformers, 0);
    }

    @Override
    public ClassNode getClassNode(String name, boolean runTransformers, int readerFlags) throws ClassNotFoundException, IOException {
        byte[] bytes = bytes(name);
        if (bytes == null) {
            throw new ClassNotFoundException(name);
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, readerFlags);
        String binaryName = node.name.replace('/', '.');
        if (relaxedPackages.stream().anyMatch(binaryName::startsWith)) {
            relax(node);
            relaxedClasses.computeIfAbsent(binaryName, _ -> new ArrayList<>()).add(node);
        }
        return node;
    }

    private byte[] bytes(String name) throws IOException {
        String binaryName = name.replace('/', '.');
        String path = binaryName.replace('.', '/') + ".class";
        byte[] framed = loader.findResource(path) == null ? sources.apply(binaryName) : null;
        if (framed != null) {
            return framed;
        }
        try (InputStream in = loader.getResourceAsStream(path)) {
            return in != null ? in.readAllBytes() : classes.apply(binaryName);
        }
    }

    /**
     * Takes the handler that failed out of a relaxed mixin for good, as it does not fit a target that the game's
     * framework changed; the mixin's next application goes without it. False when the mixin is not relaxed or
     * the failure names no handler of it.
     */
    static boolean drop(String target, IMixinInfo mixin, Throwable failure) {
        if (!(failure instanceof InvalidInjectionException injection) || injection.getContext() == null
                || !(injection.getContext().getMethod() instanceof MethodNode handler)) {
            return false;
        }
        // Mixin renamed the handler of its copy of the mixin already.
        String name = MethodNodeEx.getName(handler);
        boolean isDropped = false;
        for (ClassNode node : relaxedClasses.getOrDefault(mixin.getClassName(), List.of())) {
            isDropped |= node.methods.removeIf(method -> method.name.equals(name) && method.desc.equals(handler.desc));
        }
        if (isDropped) {
            dropped.computeIfAbsent(target, _ -> new ArrayList<>())
                    .add(mixin.getClassName() + "." + name + ", which does not fit " + target);
        }
        return isDropped;
    }

    /**
     * Lets every injector of the mixin find no target: the classes of a game its framework did not start differ
     * from what it was written against. Mixin merges a handler with its annotations, so the mark finds it in the
     * mixed class. An injector the mod already made optional gets no mark.
     * <p>
     * An accessor or invoker whose method the target has already, as the game's framework added it, is dropped:
     * Mixin would replace that method, and the mod's calls reach it as it is. An injector that names what the
     * framework replaced gets what took its place.
     */
    private void relax(ClassNode mixin) throws IOException {
        List<ClassNode> targets = new ArrayList<>();
        AnnotationNode mixinAnnotation = Annotations.getInvisible(mixin, Mixin.class);
        if (mixinAnnotation != null) {
            List<String> names = new ArrayList<>(Annotations.getValue(mixinAnnotation, "targets", List.<String>of()));
            Annotations.<List<Type>>getValue(mixinAnnotation, "value", List.of()).forEach(type -> names.add(type.getClassName()));
            for (String name : names) {
                byte[] bytes = bytes(name);
                if (bytes != null) {
                    ClassNode node = new ClassNode();
                    new ClassReader(bytes).accept(node, 0);
                    targets.add(node);
                }
            }
        }
        Set<String> existing = new HashSet<>();
        targets.forEach(target -> target.methods.forEach(method -> existing.add(method.name + method.desc)));
        mixin.methods.removeIf(method -> existing.contains(method.name + method.desc)
                && (Annotations.getVisible(method, Accessor.class) != null || Annotations.getVisible(method, Invoker.class) != null));
        Set<String> injectors = new HashSet<>();
        InjectionInfo.getRegisteredAnnotations().forEach(type -> injectors.add(Type.getDescriptor(type)));
        for (MethodNode method : mixin.methods) {
            if (method.visibleAnnotations == null) {
                continue;
            }
            for (AnnotationNode annotation : method.visibleAnnotations) {
                if (!injectors.contains(annotation.desc)) {
                    continue;
                }
                replace(annotation, targets);
                pin(annotation, method, targets);
                Integer require = Annotations.getValue(annotation, "require");
                if (require == null || require != 0) {
                    Annotations.setValue(annotation, "require", 0);
                    AnnotationNode mark = new AnnotationNode(RELAXED);
                    mark.visit("value", method.name);
                    if (method.invisibleAnnotations == null) {
                        method.invisibleAnnotations = new ArrayList<>();
                    }
                    method.invisibleAnnotations.add(mark);
                }
            }
        }
    }

    /** Points the injector at what the game's framework put in place of a method or call it names, see {@link #replacements}. */
    private static void replace(AnnotationNode injector, List<ClassNode> targets) {
        List<String> selectors = new ArrayList<>(Annotations.getValue(injector, "method", List.<String>of()));
        for (Framework.Replacement replacement : replacements) {
            for (ClassNode target : targets) {
                if (!target.name.equals(replacement.owner())) {
                    continue;
                }
                if (replacement.in() == null && target.methods.stream().anyMatch(method -> method.name.equals(replacement.by()))) {
                    selectors.replaceAll(selector -> name(selector).equals(replacement.replaced()) ? rename(selector, replacement.by()) : selector);
                } else if (replacement.in() != null && selectors.stream().anyMatch(selector -> name(selector).equals(replacement.in()))
                        && !calls(target, replacement.in(), replacement.replaced())) {
                    for (AnnotationNode point : points(injector)) {
                        if (replacement.replaced().equals(Annotations.getValue(point, "target"))) {
                            Annotations.setValue(point, "target", replacement.by());
                        }
                    }
                }
            }
        }
        if (!selectors.isEmpty()) {
            Annotations.setValue(injector, "method", selectors);
        }
    }

    private static void pin(AnnotationNode injector, MethodNode handler, List<ClassNode> targets) {
        String arguments = injector.desc.equals(Type.getDescriptor(Inject.class)) ? arguments(handler) : null;
        List<String> points = points(injector).stream().map(point -> Annotations.<String>getValue(point, "target")).filter(Objects::nonNull).toList();
        List<String> selectors = Annotations.getValue(injector, "method", List.<String>of());
        List<String> pinned = new ArrayList<>();
        for (String selector : selectors) {
            List<MethodNode> named = selector.contains("(") || selector.contains("*") || selector.contains(":") ? List.of() : targets.stream().flatMap(target -> target.methods.stream()).filter(method -> method.name.equals(name(selector))).toList();
            List<MethodNode> fitting = named.stream().filter(method -> arguments == null || method.desc.startsWith(arguments)).filter(method -> points.isEmpty() || points.stream().anyMatch(point -> has(method, point))).toList();
            if (named.size() > 1 && !fitting.isEmpty() && fitting.size() < named.size()) {
                fitting.forEach(method -> pinned.add(selector + method.desc));
            } else {
                pinned.add(selector);
            }
        }
        if (!pinned.equals(selectors)) {
            Annotations.setValue(injector, "method", pinned);
        }
    }

    private static String arguments(MethodNode handler) {
        Type[] parameters = Type.getArgumentTypes(handler.desc);
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].getDescriptor().startsWith("Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo")) {
                return i == 0 ? null : Arrays.stream(parameters, 0, i).map(Type::getDescriptor).collect(Collectors.joining("", "(", ")"));
            }
        }
        return null;
    }

    private static List<AnnotationNode> points(AnnotationNode injector) {
        Object at = Annotations.getValue(injector, "at");
        List<?> points = at instanceof List<?> list ? list : at == null ? List.of() : List.of(at);
        return points.stream().map(AnnotationNode.class::cast).toList();
    }

    /** The method name in a selector such as {@code name}, {@code name(I)V} or {@code Lowner;name(I)V}. */
    private static String name(String selector) {
        int end = selector.indexOf('(') < 0 ? selector.length() : selector.indexOf('(');
        return selector.substring(selector.lastIndexOf(';', end) + 1, end);
    }

    private static String rename(String selector, String name) {
        int end = selector.indexOf('(') < 0 ? selector.length() : selector.indexOf('(');
        return selector.substring(0, selector.lastIndexOf(';', end) + 1) + name + selector.substring(end);
    }

    /** Whether a method of the class by this name has a call of the INVOKE target. */
    private static boolean calls(ClassNode owner, String method, String target) {
        return owner.methods.stream().anyMatch(each -> each.name.equals(method) && has(each, target));
    }

    private static boolean has(MethodNode method, String target) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && target.equals("L" + call.owner + ";" + call.name + call.desc)
                    || instruction instanceof FieldInsnNode field && target.equals("L" + field.owner + ";" + field.name + ":" + field.desc)) {
                return true;
            }
        }
        return false;
    }
}
