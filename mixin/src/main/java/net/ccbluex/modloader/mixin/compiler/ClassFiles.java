package net.ccbluex.modloader.mixin.compiler;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The game's classes as their class files declare them, read without loading a class. A class may have
 * been changed while it loaded, so this is a first guess; {@code Linker} looks at the loaded class.
 *
 * @agent claude-fable-5-2
 * @agent claude-opus-5-5
 */
final class ClassFiles {

    /** A field or method and the class that declares it. */
    record Member(ClassNode declaring, int access) {
    }

    private final ClassLoader loader;
    private final URLClassLoader excluded;
    private final Map<String, Optional<ClassNode>> classes = new ConcurrentHashMap<>();

    ClassFiles(ClassLoader loader) {
        this(loader, null);
    }

    /** @param excluded a class loader whose own jars do not count, though {@code loader} reaches them */
    ClassFiles(ClassLoader loader, URLClassLoader excluded) {
        this.loader = loader;
        this.excluded = excluded;
    }

    /** Null when the loader has no class file by that internal name. */
    ClassNode get(String name) {
        return classes.computeIfAbsent(name, _ -> {
            URL url = loader.getResource(name + ".class");
            if (url == null || excluded != null && url.toString().equals(String.valueOf(excluded.findResource(name + ".class")))) {
                return Optional.empty();
            }
            // The class loader reads from the jar it keeps open; opening the URL would open the jar for every class.
            try (InputStream in = loader.getResourceAsStream(name + ".class")) {
                if (in == null) {
                    return Optional.empty();
                }
                ClassNode node = new ClassNode();
                new ClassReader(in).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                return Optional.of(node);
            } catch (IOException e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    /** As the JVM looks a field up: the class, its interfaces, its superclass. */
    Member field(String owner, String name, String descriptor) {
        ClassNode node = owner == null ? null : get(owner);
        if (node == null) {
            return null;
        }
        for (FieldNode field : node.fields) {
            if (field.name.equals(name) && field.desc.equals(descriptor)) {
                return new Member(node, field.access);
            }
        }
        for (String implemented : node.interfaces) {
            Member inherited = field(implemented, name, descriptor);
            if (inherited != null) {
                return inherited;
            }
        }
        return field(node.superName, name, descriptor);
    }

    Member method(String owner, String name, String descriptor) {
        ClassNode node = owner == null ? null : get(owner);
        if (node == null) {
            return null;
        }
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) {
                return new Member(node, method.access);
            }
        }
        Member inherited = method(node.superName, name, descriptor);
        for (String implemented : node.interfaces) {
            if (inherited == null) {
                inherited = method(implemented, name, descriptor);
            }
        }
        return inherited;
    }

    /** Whether the class, by internal name, is {@code ancestor} or extends it. */
    boolean isSubclass(String name, String ancestor) {
        for (ClassNode node = get(name); node != null; node = node.superName == null ? null : get(node.superName)) {
            if (node.name.equals(ancestor)) {
                return true;
            }
        }
        return false;
    }
}
