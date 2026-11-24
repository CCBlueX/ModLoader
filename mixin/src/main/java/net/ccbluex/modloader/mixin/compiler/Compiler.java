package net.ccbluex.modloader.mixin.compiler;

import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import net.ccbluex.modloader.Framework;
import net.ccbluex.modloader.mixin.MixinCompiler;
import net.ccbluex.modloader.mixin.MixinCompiler.GameConfig;
import net.ccbluex.modloader.mixin.Redefinition;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.FabricUtil;

/**
 * @agent claude-fable-5-2
 * @agent claude-opus-5-5
 */
public final class Compiler implements MixinCompiler {

    /**
     * The loader's class loader sees the game, and the mods through the class loader the game shares with them.
     * The mods' jars are among this class loader's own, which tells them apart.
     */
    private final ClassFiles game = new ClassFiles(MixinCompiler.class.getClassLoader(),
            Compiler.class.getClassLoader() instanceof URLClassLoader own ? own : null);
    private final ClassFiles all = new ClassFiles(Compiler.class.getClassLoader());
    private volatile Rewriter rewriter = new Rewriter(game, Set.of());
    private volatile MixinSession session;

    @Override
    public Map<String, Redefinition> compile(Map<String, List<String>> configs, Function<String, List<GameConfig>> gameConfigs,
            Function<String, byte[]> sources, Function<String, byte[]> classes, Predicate<String> initialized) {
        MixinSession mixin = new MixinSession(sources, classes, Framework.hasClass("net.minecraft.client.Minecraft"));
        configs.forEach((mod, names) -> names.forEach(config -> mixin.add(mod, config, FabricUtil.COMPATIBILITY_LATEST)));
        Set<String> targets = mixin.targets();
        // Mixins of the game's own on the same classes, as each injector has to see the others' targets.
        Map<Integer, GameConfig> applied = new TreeMap<>();
        for (String target : targets) {
            gameConfigs.apply(target).forEach(config -> applied.putIfAbsent(config.order(), config));
        }
        applied.values().forEach(config -> mixin.add(config.mod(), config.name(),
                Objects.requireNonNullElse(config.compatibility(), FabricUtil.COMPATIBILITY_LATEST)));

        // One stamp per compilation: a class may still run code that calls the companion it had before.
        String stamp = Long.toHexString(System.nanoTime());
        Map<String, Outliner.Outline> outlines = new LinkedHashMap<>();
        Set<String> interfaces = new HashSet<>();
        for (String target : targets) {
            byte[] loaded = classes.apply(target);
            // A target the game does not have, such as a client class on a server, is left to Mixin's own rules.
            byte[] source = loaded == null ? null : Objects.requireNonNullElse(sources.apply(target), loaded);
            byte[] mixed = source == null ? null : mixin.transform(target, source);
            if (mixed == null || mixed == source) {
                continue;
            }
            // The class keeps the shape it was loaded with, which the game's own mixins may have changed.
            Outliner.Outline outline = Outliner.outline(all, read(loaded), read(mixed),
                    "net/ccbluex/modloader/generated/" + target.replace('.', '/') + "$" + stamp, initialized.test(target));
            outlines.put(target, outline);
            interfaces.addAll(outline.interfaces());
        }

        Set<String> seen = new HashSet<>();
        Set<String> overridden = new HashSet<>();
        outlines.values().forEach(outline -> outline.methods().keySet().stream()
                .filter(method -> !seen.add(method))
                .forEach(overridden::add));
        Rewriter linked = new Rewriter(game, interfaces);
        Map<String, Redefinition> redefinitions = new LinkedHashMap<>();
        outlines.forEach((target, outline) -> {
            linked.rewrite(outline.companion(), all, new Rewriter.Companion(
                    outline.target().name, outline.companion().name, outline.methods(), overridden));
            Map<String, String> methods = new LinkedHashMap<>();
            outline.methods().forEach((method, moved) -> methods.put(method, moved.name));
            List<String> added = new ArrayList<>();
            outline.interfaces().forEach(name -> added.add(name.replace('/', '.')));
            redefinitions.put(target, new Redefinition(write(outline.target()), outline.companion().name.replace('/', '.'),
                    write(outline.companion()), added, methods, outline.initializer(), outline.fields(), outline.skipped()));
        });
        rewriter = linked;
        session = mixin;
        return redefinitions;
    }

    @Override
    public byte[] rewrite(ClassLoader loader, byte[] modClass) {
        ClassReader reader = new ClassReader(modClass);
        byte[] bytes = modClass;
        MixinSession mixin = session;
        if (mixin != null) {
            // Mixin fills in the static methods of accessor interfaces when they load.
            bytes = mixin.transform(reader.getClassName().replace('/', '.'), modClass);
            reader = bytes == modClass ? reader : new ClassReader(bytes);
        }
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        if (!rewriter.rewrite(node, new ClassFiles(loader), null)) {
            return bytes;
        }
        return write(node);
    }

    /**
     * Public, with members that were package-private or protected: at the game's start these classes sit in the
     * package of the handlers that use them, which here moved to a companion in another package.
     */
    @Override
    public byte[] generate(String name) {
        MixinSession mixin = session;
        byte[] bytes = mixin == null ? null : mixin.generate(name);
        if (bytes == null) {
            return null;
        }
        ClassNode node = read(bytes);
        node.access = open(node.access);
        node.fields.forEach(field -> field.access = open(field.access));
        node.methods.forEach(method -> method.access = open(method.access));
        node.innerClasses.stream().filter(inner -> inner.name.equals(node.name)).forEach(inner -> inner.access = open(inner.access));
        return write(node);
    }

    private static int open(int access) {
        return (access & Opcodes.ACC_PRIVATE) != 0 ? access : access & ~Opcodes.ACC_PROTECTED | Opcodes.ACC_PUBLIC;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        // Whole frames survive methods being cut and moved; compressed ones lean on the frame before.
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }

    /** Written as it is: every change keeps the stack and the frames of the code around it. */
    private static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
