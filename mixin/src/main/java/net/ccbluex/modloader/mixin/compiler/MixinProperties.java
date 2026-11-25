package net.ccbluex.modloader.mixin.compiler;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IPropertyKey;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** @agent claude-fable-5-2 */
public final class MixinProperties implements IGlobalPropertyService {
    /** @agent claude-fable-5-2 */
    private record Key(String name) implements IPropertyKey {
    }

    private final Map<IPropertyKey, Object> properties = new ConcurrentHashMap<>();

    @Override
    public IPropertyKey resolveKey(String name) {
        return new Key(name);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> @Nullable T getProperty(IPropertyKey key) {
        return (T) properties.get(key);
    }

    @Override
    public void setProperty(IPropertyKey key, Object value) {
        properties.put(key, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> @Nullable T getProperty(IPropertyKey key, @Nullable T defaultValue) {
        return (T) properties.getOrDefault(key, defaultValue);
    }

    @Override
    public @Nullable String getPropertyString(IPropertyKey key, @Nullable String defaultValue) {
        Object value = properties.get(key);
        return value != null ? value.toString() : defaultValue;
    }
}
