package net.ccbluex.modloader.mixin.compiler;

import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import com.llamalad7.mixinextras.utils.ClassGenUtils;
import net.ccbluex.modloader.Framework;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.FabricUtil;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.MixinEnvironment.Option;
import org.spongepowered.asm.mixin.MixinEnvironment.Phase;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.injection.invoke.arg.ArgsClassGenerator;
import org.spongepowered.asm.mixin.transformer.Config;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.ext.Extensions;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;
import org.spongepowered.asm.service.MixinService;
import org.spongepowered.asm.mixin.throwables.MixinError;
import org.spongepowered.asm.util.Annotations;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One run of Mixin over the mixin configs of the loaded mods. Mixin keeps its state in statics, so a class loader
 * holds one session.
 *
 * @agent claude-fable-5-2
 * @agent claude-opus-5-5
 */
final class MixinSession {
    private final IMixinTransformer transformer;
    private final String sessionId;
    private final MixinEnvironment environment;
    private final Map<String, String> mods = new HashMap<>();
    private final List<IMixinConfig> configs = new ArrayList<>();
    private final List<IMixinConfig> relaxed = new ArrayList<>();
    private boolean selected;
    private boolean transformed;

    /**
     * @param classes bytes of a class by binary name ("net.minecraft.server.MinecraftServer"), null when there is none.
     *                Asked for target classes, their superclasses and interfaces, and anything else Mixin inspects.
     * @param client  whether the game is a client; decides Mixin's side (CLIENT or SERVER)
     * @param replacements what the game's frameworks replaced, which relaxed configs name
     */
    MixinSession(Function<String, byte[]> sources, Function<String, byte[]> classes, boolean client, List<Framework.Replacement> replacements) {
        if (MixinHost.classes != null) {
            throw new IllegalStateException("This class loader already ran a MixinSession");
        }
        MixinHost.sources = sources;
        MixinHost.classes = classes;
        MixinHost.client = client;
        MixinHost.replacements = replacements;

        try {
            // A Fabric game leaves the names of its own services in these properties, and Mixin reads them first.
            Properties properties = System.getProperties();
            synchronized (properties) {
                Object service = properties.setProperty("mixin.service", MixinHost.class.getName());
                Object bootstrap = properties.setProperty("mixin.bootstrapService", MixinHostBootstrap.class.getName());
                try {
                    MixinService.getService();
                } finally {
                    restore(properties, "mixin.service", service);
                    restore(properties, "mixin.bootstrapService", bootstrap);
                }
            }

            // Mixin takes the first property service its jar or ours lists, and its ModLauncher one works on Forge.
            Field instance = MixinService.class.getDeclaredField("instance");
            instance.setAccessible(true);
            Field propertyService = MixinService.class.getDeclaredField("propertyService");
            propertyService.setAccessible(true);
            propertyService.set(instance.get(null), new MixinProperties());

            MixinBootstrap.init();
            Method gotoPhase = MixinEnvironment.class.getDeclaredMethod("gotoPhase", Phase.class);
            gotoPhase.setAccessible(true);
            gotoPhase.invoke(null, Phase.INIT);
            gotoPhase.invoke(null, Phase.DEFAULT);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("This Mixin version cannot be started", e);
        }

        environment = MixinEnvironment.getCurrentEnvironment();
        // The game's own Mixin reads the same mixin.* system properties; ours must write no files whatever they say.
        for (Option option : List.of(Option.DEBUG_EXPORT, Option.DEBUG_EXPORT_DECOMPILE, Option.CHECK_IMPLEMENTS,
                Option.DUMP_TARGET_ON_FAILURE, Option.HOT_SWAP)) {
            environment.setOption(option, false);
        }
        transformer = MixinHost.transformers.createTransformer();
        try {
            Field nextIndex = ArgsClassGenerator.class.getDeclaredField("nextIndex");
            nextIndex.setAccessible(true);
            nextIndex.setInt(((Extensions) transformer.getExtensions()).getGenerator(ArgsClassGenerator.class),
                    1_000_001);
            Field processor = transformer.getClass().getDeclaredField("processor");
            processor.setAccessible(true);
            Object mixinProcessor = processor.get(transformer);
            Field session = mixinProcessor.getClass().getDeclaredField("sessionId");
            session.setAccessible(true);
            sessionId = (String) session.get(mixinProcessor);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("This Mixin version cannot be started", e);
        }
        Mixins.registerErrorHandlerClass(StrictErrorHandler.class.getName());
        MixinExtrasBootstrap.init();
        ClassLoader own = MixinSession.class.getClassLoader();
        if (own.getResource("com/bawnorton/mixinsquared/MixinSquaredBootstrap.class") != null) {
            try {
                Class.forName("com.bawnorton.mixinsquared.MixinSquaredBootstrap", true, own).getMethod("init")
                        .invoke(null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("MixinSquared cannot be started", e);
            }
        }
    }

    private static void restore(Properties properties, String key, Object value) {
        if (value == null) {
            properties.remove(key);
        } else {
            properties.put(key, value);
        }
    }

    /**
     * Registers a mixin config (a JSON resource name such as "example.mixins.json") of the mod with this id. Only
     * before transform() or generate(): Mixin selects no configs once it changed a class.
     *
     * @param isRelaxed whether an injector of the config may find no target, see {@link #leftOut}
     */
    void add(String mod, String config, boolean isRelaxed, int compatibility) {
        String id = mod == null ? config : mod;
        if (transformed) {
            throw new IllegalStateException("Mixin config " + config + " of " + id + " comes after Mixin changed a class");
        }
        selected = false;
        String owner = mods.putIfAbsent(config, id);
        if (owner != null) {
            throw new IllegalArgumentException("Mixin config " + config + " is used by the mods " + owner + " and " + id);
        }
        run(() -> {
            Mixins.addConfiguration(config);
            return null;
        });
        for (Config added : Mixins.getConfigs()) {
            if (added.getName().equals(config)) {
                if (mod != null) {
                    added.getConfig().decorate(FabricUtil.KEY_MOD_ID, mod);
                }
                added.getConfig().decorate(FabricUtil.KEY_COMPATIBILITY, compatibility);
                configs.add(added.getConfig());
                if (isRelaxed) {
                    MixinHost.relaxedPackages.add(added.getConfig().getMixinPackage());
                    relaxed.add(added.getConfig());
                }
            }
        }
    }

    /** Binary names of every class the registered configs target. Prepares the configs added since the last call. */
    Set<String> targets() {
        select();
        Set<String> targets = new TreeSet<>();
        for (IMixinConfig config : configs) {
            targets.addAll(config.getTargets());
        }
        return targets;
    }

    /** Whether a relaxed config targets the class: what fails there comes from a mod outside its framework. */
    boolean isRelaxed(String target) {
        return relaxed.stream().anyMatch(config -> config.getTargets().contains(target));
    }

    /** The class with every mixin that targets it applied; the given array itself when none does. */
    byte[] transform(String name, byte[] bytes) {
        select();
        transformed = true;
        byte[] input = adopt(bytes);
        int dropped;
        byte[] mixed;
        do {
            // A dropped handler failed Mixin's run over the class, which then left it as it was.
            dropped = MixinHost.dropped.getOrDefault(name, List.of()).size();
            mixed = run(() -> transformer.transformClass(environment, name, input));
        } while (MixinHost.dropped.getOrDefault(name, List.of()).size() > dropped);
        return mixed == input ? bytes : mixed;
    }

    private byte[] adopt(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        boolean isAdopted = false;
        for (MethodNode method : node.methods) {
            AnnotationNode merged = Annotations.getVisible(method, MixinMerged.class);
            if (merged != null && !sessionId.equals(Annotations.getValue(merged, "sessionId"))) {
                Annotations.setValue(merged, "sessionId", sessionId);
                isAdopted = true;
            }
        }
        if (!isAdopted) {
            return bytes;
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * The injectors of relaxed configs that inject nothing into the mixed class or were dropped from it, in words
     * for the person loading the mod.
     */
    static List<String> leftOut(ClassNode mixed) {
        Set<String> called = new HashSet<>();
        for (MethodNode method : mixed.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(mixed.name)) {
                    called.add(call.name);
                } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                    for (Object argument : dynamic.bsmArgs) {
                        if (argument instanceof Handle handle && handle.getOwner().equals(mixed.name)) {
                            called.add(handle.getName());
                        }
                    }
                }
            }
        }
        List<String> notes = new ArrayList<>(MixinHost.dropped.getOrDefault(mixed.name.replace('/', '.'), List.of()));
        for (MethodNode method : mixed.methods) {
            if (method.invisibleAnnotations == null || called.contains(method.name)) {
                continue;
            }
            for (AnnotationNode mark : method.invisibleAnnotations) {
                if (mark.desc.equals(MixinHost.RELAXED)) {
                    AnnotationNode merged = Annotations.getVisible(method, MixinMerged.class);
                    String mixin = merged == null ? "a mixin" : Annotations.getValue(merged, "mixin");
                    notes.add(mixin + "." + Annotations.getValue(mark, "value") + ", which finds no target in "
                            + mixed.name.replace('/', '.'));
                }
            }
        }
        return notes;
    }

    /**
     * A class Mixin generates itself (the Args subclasses of @ModifyArgs, copies of a mixin's inner classes,
     * MixinExtras' generated classes), null when it has none by that name.
     */
    byte[] generate(String name) {
        select();
        transformed = true;
        byte[] bytes = run(() -> transformer.generateClass(environment, name));
        return bytes != null ? bytes : ClassGenUtils.getDefinitions().get(name);
    }

    private void select() {
        if (selected) {
            return;
        }
        selected = true;
        // Mixin selects and prepares the configs added since, when it is asked about a class.
        run(() -> transformer.couldTransformClass(environment, "java.lang.Object"));
        if (!Mixins.getConfigs().isEmpty()) {
            throw new IllegalStateException("Mixin configs that are not for the default phase or failed to load: " + Mixins.getConfigs());
        }
    }

    /** Mixin logs many of its failures and carries on, and throws the others as Errors; both leave here as exceptions. */
    private static <T> T run(Supplier<T> mixin) {
        T result;
        try {
            result = mixin.get();
        } catch (MixinError e) {
            Throwable cause = e;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            // The line Mixin logs before it throws names the mixin and the mod; the innermost cause does not.
            String message = StderrLogger.failure != null ? StderrLogger.failure.getMessage() : cause.getMessage();
            StderrLogger.failure = null;
            throw new IllegalStateException(message, e);
        } catch (Throwable t) {
            StderrLogger.failure = null;
            throw t;
        }
        RuntimeException failure = StderrLogger.failure;
        if (failure != null) {
            StderrLogger.failure = null;
            throw failure;
        }
        return result;
    }
}
