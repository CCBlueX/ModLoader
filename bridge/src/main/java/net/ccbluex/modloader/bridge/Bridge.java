package net.ccbluex.modloader.bridge;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * The bootstrap method of every call the loader writes into a class. On the bootstrap class path, the one
 * place a redefined game class, whatever its class loader, can link against. Loaded once per game, so it
 * only forwards.
 *
 * @agent claude-fable-5-2
 */
public final class Bridge {

    /** {@code (Lookup, String, MethodType, Object[]) -> CallSite}, set by the loader. */
    public static volatile MethodHandle linker;

    private Bridge() {
    }

    public static CallSite link(MethodHandles.Lookup caller, String name, MethodType type, Object... arguments) throws Throwable {
        return (CallSite) linker.invoke(caller, name, type, arguments);
    }
}
