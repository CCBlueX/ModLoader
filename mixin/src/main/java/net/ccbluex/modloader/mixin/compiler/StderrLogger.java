package net.ccbluex.modloader.mixin.compiler;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.logging.Level;
import org.spongepowered.asm.logging.LoggerAdapterAbstract;

/**
 * Prints Mixin's warnings and errors to System.err and keeps the first error for the session to throw.
 *
 * @agent claude-fable-5-2
 */
final class StderrLogger extends LoggerAdapterAbstract {
    static @Nullable RuntimeException failure;

    StderrLogger(String name) {
        super(name);
    }

    @Override
    public String getType() {
        return "System.err";
    }

    @Override
    public void catching(Level level, Throwable t) {
        log(level, "Catching " + t, t);
    }

    @Override
    public void log(Level level, String message, Object... params) {
        FormattedMessage formatted = new FormattedMessage(message, params);
        log(level, formatted.getMessage(), formatted.getThrowable());
    }

    @Override
    public void log(Level level, String message, @Nullable Throwable t) {
        if (level.compareTo(Level.WARN) > 0) {
            return;
        }
        System.err.println("[" + getId() + "/" + level + "] " + message);
        if (level != Level.WARN && failure == null) {
            failure = new IllegalStateException(message, t);
        } else if (t != null) {
            t.printStackTrace();
        }
    }

    @Override
    public <T extends Throwable> T throwing(T t) {
        log(Level.ERROR, "Throwing " + t, t);
        return t;
    }
}
