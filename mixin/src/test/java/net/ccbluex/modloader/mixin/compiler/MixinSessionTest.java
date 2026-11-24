package net.ccbluex.modloader.mixin.compiler;

import mod.Resettable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.FabricUtil;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** @agent claude-fable-5-2 */
class MixinSessionTest {
    /**
     * A MixinSession in a class loader of its own, as in the game; a second one in this loader would meet Mixin's statics.
     *
     * @agent claude-fable-5-2
     */
    private static final class Session {
        private final Object session;

        Session() throws Exception {
            List<URL> classPath = new ArrayList<>();
            for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
                classPath.add(new File(entry).toURI().toURL());
            }
            ClassLoader loader = new URLClassLoader(classPath.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
            Constructor<?> constructor = loader.loadClass(MixinSession.class.getName())
                    .getDeclaredConstructor(Function.class, Function.class, boolean.class);
            constructor.setAccessible(true);
            session = constructor.newInstance((Function<String, byte[]>) name -> null, (Function<String, byte[]>) name -> null, false);
        }

        @SuppressWarnings("unchecked")
        <T> T call(String name, Object... arguments) throws Throwable {
            for (Method method : session.getClass().getDeclaredMethods()) {
                if (method.getName().equals(name)) {
                    method.setAccessible(true);
                    try {
                        return (T) method.invoke(session, arguments);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }
            }
            throw new NoSuchMethodException(name);
        }
    }

    /**
     * Defines the mixed Counter and the classes Mixin generates for it.
     *
     * @agent claude-fable-5-2
     */
    private static final class MixedClassLoader extends ClassLoader {
        private final Session session;
        private final byte[] counter;

        MixedClassLoader(Session session, byte[] counter) {
            super(MixinSessionTest.class.getClassLoader());
            this.session = session;
            this.counter = counter;
        }

        @Override
        protected Class<?> loadClass(String name, boolean link) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded != null) {
                    return loaded;
                }
                byte[] bytes;
                try {
                    bytes = name.equals("game.Counter") ? counter : session.call("generate", name);
                } catch (Throwable t) {
                    throw new ClassNotFoundException(name, t);
                }
                return bytes != null ? defineClass(name, bytes, 0, bytes.length) : super.loadClass(name, link);
            }
        }
    }

    private static Session session;
    private static byte[] original;
    private static byte[] mixed;

    @BeforeAll
    static void mix() throws Throwable {
        try (InputStream in = MixinSessionTest.class.getResourceAsStream("/game/Counter.class")) {
            original = in.readAllBytes();
        }
        session = new Session();
        session.call("add", "fixture", "fixture.mixins.json", FabricUtil.COMPATIBILITY_LATEST);
        assertEquals(Set.of("game.Counter"), session.call("targets"));
        mixed = session.call("transform", "game.Counter", original);
    }

    private static Object call(Object counter, String method) throws Exception {
        Method found = counter.getClass().getMethod(method);
        return found.invoke(counter);
    }

    @Test
    void appliesEveryKindOfMixin() throws Exception {
        assertNotSame(original, mixed);
        Object counter = new MixedClassLoader(session, mixed).loadClass("game.Counter").getConstructor().newInstance();

        assertEquals(6, call(counter, "limit"), "@Inject with @Shadow field, @Shadow method and @Unique field");
        call(counter, "tick");
        call(counter, "tick");
        assertEquals(2, call(counter, "getCount"), "@Accessor");
        assertEquals(1, call(counter, "invokeStep"), "@Invoker");
        assertEquals(40, call(counter, "doubled"), "@Redirect");
        assertEquals("mixed=2", call(counter, "describe"), "@ModifyArgs");
        assertEquals(1006, call(counter, "tripled"), "@WrapOperation");
        ((Resettable) counter).reset();
        assertEquals(0, call(counter, "getCount"), "duck interface");
    }

    @Test
    void leavesOtherClassesAlone() throws Throwable {
        assertSame(original, session.call("transform", "game.Other", original));
        assertNull(session.call("generate", "game.Unknown"));
    }

    @Test
    void throwsWhenAMixinDoesNotApply() throws Throwable {
        Session broken = new Session();
        broken.call("add", "broken", "broken.mixins.json", FabricUtil.COMPATIBILITY_LATEST);
        Throwable failure = assertThrows(IllegalStateException.class, () -> broken.call("transform", "game.Counter", original));
        assertTrue(failure.getMessage().contains("MissingFieldMixin") && failure.getMessage().contains("@Shadow field missing"), failure.toString());
    }

    @Test
    void mergesOverAMethodAnotherSessionMerged() throws Throwable {
        Session game = new Session();
        game.call("add", "first", "first.mixins.json", FabricUtil.COMPATIBILITY_LATEST);
        byte[] started = game.call("transform", "game.Counter", original);
        Session later = new Session();
        later.call("add", "second", "second.mixins.json", FabricUtil.COMPATIBILITY_LATEST);
        byte[] mixed = later.call("transform", "game.Counter", started);
        Object counter = new MixedClassLoader(later, mixed).loadClass("game.Counter").getConstructor().newInstance();
        assertEquals(2, call(counter, "limit"));
    }

    @Test
    void throwsForAMissingConfig() throws Exception {
        Session empty = new Session();
        Throwable failure = assertThrows(IllegalStateException.class, () -> empty.call("add", "nobody", "nowhere.mixins.json", FabricUtil.COMPATIBILITY_LATEST));
        assertTrue(failure.getMessage().contains("nowhere.mixins.json"), failure.toString());
    }

    @Test
    void missesATargetAsTheCompatibilityLevelAllows() throws Throwable {
        Session older = new Session();
        older.call("add", "absent", "absent.mixins.json", FabricUtil.COMPATIBILITY_0_10_0);
        assertEquals(Set.of(), older.call("targets"));

        Session latest = new Session();
        latest.call("add", "absent", "absent.mixins.json", FabricUtil.COMPATIBILITY_LATEST);
        Throwable failure = assertThrows(IllegalStateException.class, () -> latest.call("targets"));
        assertTrue(failure.toString().contains("game.Missing"), failure.toString());
    }
}
