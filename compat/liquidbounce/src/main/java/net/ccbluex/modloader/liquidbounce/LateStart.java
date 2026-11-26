package net.ccbluex.modloader.liquidbounce;

import static net.ccbluex.modloader.liquidbounce.Members.CLIENT;
import static net.ccbluex.modloader.liquidbounce.Members.call;
import static net.ccbluex.modloader.liquidbounce.Members.constructor;
import static net.ccbluex.modloader.liquidbounce.Members.create;
import static net.ccbluex.modloader.liquidbounce.Members.field;
import static net.ccbluex.modloader.liquidbounce.Members.get;
import static net.ccbluex.modloader.liquidbounce.Members.invoke;
import static net.ccbluex.modloader.liquidbounce.Members.method;
import static net.ccbluex.modloader.liquidbounce.Members.set;
import static net.ccbluex.modloader.liquidbounce.Members.type;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.ccbluex.modloader.api.LoadedMod;
import net.ccbluex.modloader.api.RuntimeMod;
import org.jspecify.annotations.Nullable;

/**
 * Starts a LiquidBounce build that has no {@link RuntimeMod} of its own: the game's constructor already ran past
 * the mixins that start the client. Such a client cannot be unloaded, so there is nothing to undo here.
 *
 * @agent claude-fable-5-2
 */
public final class LateStart implements RuntimeMod {

    @Override
    public void load(LoadedMod mod) {
        if (!FabricEntrypoint.hasFolder) {
            throw new IllegalStateException(
                    "LiquidBounce was not given the folder " + System.getProperty("liquidbounce.folder"));
        }
        try {
            Class.forName(CLIENT + "LateStart", false, getClass().getClassLoader());
            return;
        } catch (ClassNotFoundException _) {
            // A build from before the client knew ModLoader.
        }

        Class<?> minecraftClass = type("net.minecraft.client.Minecraft");
        Method getInstance = method(minecraftClass, "getInstance");
        Method getUser = method(minecraftClass, "getUser");
        Method reloadResourcePacks = method(minecraftClass, "reloadResourcePacks");
        Field options = field(minecraftClass, "options");
        Method guiScale = method(type("net.minecraft.client.Options"), "guiScale");

        Class<?> dispatcher = type("kotlinx.coroutines.CoroutineDispatcher");
        Class<?> dispatchers = type("kotlinx.coroutines.Dispatchers");
        Field dispatchersInstance = field(dispatchers, "INSTANCE");
        Method getDefault = method(dispatchers, "getDefault");
        Method getMinecraft =
                method(type(CLIENT + "utils.kotlin.MinecraftMainDispatcherKt"), "getMinecraft", dispatchers);
        Method getImmediate = method(type("kotlinx.coroutines.MainCoroutineDispatcher"), "getImmediate");

        Class<?> client = type(CLIENT + "LiquidBounce");
        Field clientInstance = field(client, "INSTANCE");
        Method initializeClient = method(client, "initializeClient", dispatcher, dispatcher);

        Class<?> eventManager = type(CLIENT + "event.EventManager");
        Field eventManagerInstance = field(eventManager, "INSTANCE");
        Method callEvent = method(eventManager, "callEvent", type(CLIENT + "event.Event"));
        Field clientStartEvent = field(type(CLIENT + "event.events.ClientStartEvent"), "INSTANCE");
        Constructor<?> sessionEvent =
                constructor(type(CLIENT + "event.events.SessionEvent"), type("net.minecraft.client.User"));

        Object minecraft = call(getInstance, null);
        Executor gameThread = (Executor) minecraft;
        Class<?> browsers = type(CLIENT + "integration.backend.BrowserBackendManager");
        Class<?> moduleHud = type(CLIENT + "features.module.modules.render.ModuleHud");
        Hud hud = new Hud(
                field(browsers, "INSTANCE"),
                method(browsers, "isInitialized"),
                method(type(CLIENT + "integration.backend.BrowserBackendManagerKt"), "isBrowserDisabled"),
                field(moduleHud, "INSTANCE"),
                method(moduleHud, "reopen"),
                gameThread);
        Loading loading = Loading.show(minecraftClass, minecraft);

        // Registers the start handlers; at startup the window title mixin gets there first.
        Object instance = get(clientInstance, null);
        // The client's mixin turns an automatic GUI scale into 2 on the first read, at startup while the window
        // opens. Left to the browser's first request, the change comes from a worker thread after the HUD read
        // the old scale.
        Object gameOptions = get(options, minecraft);
        Object scale = get(field(type("net.minecraft.client.Options"), "guiScale"), gameOptions);
        Field scaleValue = field(type("net.minecraft.client.OptionInstance"), "value");
        if (Integer.valueOf(0).equals(get(scaleValue, scale))) {
            set(scaleValue, scale, 2);
            invoke(method(minecraftClass, "resizeGui"), minecraft);
        }
        call(guiScale, gameOptions);
        Object events = get(eventManagerInstance, null);
        call(callEvent, events, create(sessionEvent, call(getUser, minecraft)));
        call(callEvent, events, get(clientStartEvent, null));
        // The game already runs the mixins, so the managers and modules have to exist when this returns: the
        // immediate dispatcher runs the initializer here up to its first wait, the rest follows on the game thread.
        Object gameDispatcher = call(getImmediate, call(getMinecraft, null, get(dispatchersInstance, null)));
        CompletableFuture<?> initialization =
                (CompletableFuture<?>) call(initializeClient, instance, call(getDefault, null), gameDispatcher);
        // The reload is for the listeners the start registered. The client's own would initialize it once more
        // while the first initialization is not through, so the reload waits for that.
        initialization.thenRunAsync(
                () -> loading.follow(() -> (CompletableFuture<?>) call(reloadResourcePacks, minecraft))
                        .thenRunAsync(hud, gameThread),
                gameThread);
    }

    /** @agent claude-fable-5-2 */
    private record Loading(Object gui, Method current, Method setOverlay, @Nullable Object overlay,
            AtomicReference<@Nullable Object> following) {

        @SuppressWarnings("unchecked")
        static Loading show(Class<?> minecraftClass, Object minecraft) {
            Class<?> reload = type("net.minecraft.server.packs.resources.ReloadInstance");
            Class<?> loadingOverlay = type("net.minecraft.client.gui.screens.LoadingOverlay");
            Class<?> guiClass = type("net.minecraft.client.gui.Gui");
            Field followedReload = field(loadingOverlay, "reload");
            Field followedOnFinish = field(loadingOverlay, "onFinish");
            Object gui = get(field(minecraftClass, "gui"), minecraft);
            Method current = method(guiClass, "overlay");
            Method setOverlay = method(guiClass, "setOverlay", type("net.minecraft.client.gui.screens.Overlay"));
            AtomicReference<@Nullable Object> following = new AtomicReference<>();
            Object overlay = null;
            if (invoke(current, gui) == null && invoke(method(minecraftClass, "getConnection"), minecraft) == null) {
                Object pending = Proxy.newProxyInstance(reload.getClassLoader(), new Class<?>[] {reload},
                        (_, method, arguments) -> {
                            Object followed = following.get();
                            if (followed != null) {
                                return invoke(method, get(followedReload, followed), arguments);
                            }
                            return switch (method.getName()) {
                                case "done" -> new CompletableFuture<>();
                                case "isDone" -> false;
                                case "getActualProgress" -> 0f;
                                default -> null;
                            };
                        });
                Consumer<Object> onFinish = error -> {
                    Object followed = following.get();
                    if (followed != null) {
                        ((Consumer<Object>) get(followedOnFinish, followed)).accept(error);
                    }
                };
                overlay = create(constructor(loadingOverlay, minecraftClass, reload, Consumer.class, boolean.class),
                        minecraft, pending, onFinish, false);
                invoke(setOverlay, gui, overlay);
            }
            return new Loading(gui, current, setOverlay, overlay, following);
        }

        CompletableFuture<?> follow(Supplier<CompletableFuture<?>> reload) {
            if (overlay == null || invoke(current, gui) != overlay) {
                return reload.get();
            }
            invoke(setOverlay, gui, (Object) null);
            CompletableFuture<?> reloaded = reload.get();
            Object started = invoke(current, gui);
            if (overlay.getClass().isInstance(started)) {
                following.set(started);
                invoke(setOverlay, gui, overlay);
            }
            return reloaded;
        }
    }

    /**
     * Shows the client's HUD in a world that is already open, where no screen change follows that would. The
     * browser it needs may still be downloading.
     *
     * @agent claude-fable-5-2
     */
    private record Hud(Field browsers, Method isBrowserInitialized, Method isBrowserDisabled, Field moduleHud,
            Method reopen, Executor gameThread) implements Runnable {

        @Override
        public void run() {
            if ((boolean) call(isBrowserInitialized, get(browsers, null))) {
                invoke(reopen, get(moduleHud, null));
            } else if (!(boolean) call(isBrowserDisabled, null)) {
                Executor later = CompletableFuture.delayedExecutor(500, TimeUnit.MILLISECONDS, gameThread);
                CompletableFuture.runAsync(this, later);
            }
        }
    }
}
