package net.ccbluex.modloader;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/** @agent claude-fable-5-2 */
final class Loading implements InvocationHandler, AutoCloseable {

    private final long start = System.nanoTime();
    private final CompletableFuture<Void> done = new CompletableFuture<>();
    private @Nullable Runnable hide;

    static Loading show() {
        Loading loading = new Loading();
        if (Instance.isClient()) {
            try {
                loading.open();
            } catch (ReflectiveOperationException | LinkageError e) {
                e.printStackTrace();
            }
        }
        return loading;
    }

    private void open() throws ReflectiveOperationException {
        ClassLoader game = Loading.class.getClassLoader();
        Class<?> type = Class.forName("net.minecraft.client.Minecraft", false, game);
        Class<?> reload = Class.forName("net.minecraft.server.packs.resources.ReloadInstance", false, game);
        Class<?> overlayType = Class.forName("net.minecraft.client.gui.screens.Overlay", false, game);
        Object minecraft = type.getMethod("getInstance").invoke(null);
        Object gui = minecraft == null ? null : type.getField("gui").get(minecraft);
        if (gui == null) {
            throw new IllegalStateException("the game is still starting: load again once it shows its title screen");
        }
        Method current = gui.getClass().getMethod("overlay");
        Method set = gui.getClass().getMethod("setOverlay", overlayType);
        Method isSameThread = type.getMethod("isSameThread");
        Method getConnection = type.getMethod("getConnection");
        Object pending = Proxy.newProxyInstance(reload.getClassLoader(), new Class<?>[] {reload}, this);
        Object overlay = Class.forName("net.minecraft.client.gui.screens.LoadingOverlay", false, game)
                .getConstructor(type, reload, Consumer.class, boolean.class)
                .newInstance(minecraft, pending, (Consumer<?>) _ -> { }, true);

        Executor gameThread = task -> {
            if (Boolean.TRUE.equals(call(isSameThread, minecraft))) {
                task.run();
            } else {
                ((Executor) minecraft).execute(task);
            }
        };
        gameThread.execute(() -> {
            if (call(current, gui) == null && call(getConnection, minecraft) == null) {
                call(set, gui, overlay);
            }
        });
        hide = () -> gameThread.execute(() -> {
            if (call(current, gui) == overlay) {
                call(set, gui, (Object) null);
            }
        });
    }

    private static @Nullable Object call(Method method, Object target, @Nullable Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public @Nullable Object invoke(Object proxy, Method method, Object @Nullable [] arguments) {
        return switch (method.getName()) {
            case "done" -> done;
            case "isDone" -> false;
            case "getActualProgress" -> (float) (1 - Math.exp((start - System.nanoTime()) / 15e9));
            default -> null;
        };
    }

    @Override
    public void close() {
        Runnable hidden = hide;
        hide = null;
        if (hidden != null) {
            hidden.run();
        }
    }
}
