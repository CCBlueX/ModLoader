package net.ccbluex.modloader.mixin.compiler;

import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinErrorHandler;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Makes the failure of a mixin an error also when its config is not "required"; Mixin would only log it. A
 * handler of a relaxed mixin that does not fit its target is dropped instead, see {@link MixinHost#drop}.
 *
 * @agent claude-fable-5-2
 * @agent claude-opus-5-5
 */
public final class StrictErrorHandler implements IMixinErrorHandler {
    @Override
    public ErrorAction onPrepareError(IMixinConfig config, Throwable th, IMixinInfo mixin, ErrorAction action) {
        return ErrorAction.ERROR;
    }

    @Override
    public ErrorAction onApplyError(String targetClassName, Throwable th, IMixinInfo mixin, ErrorAction action) {
        // Mixin then leaves the class as it was, and the session applies the mixins again.
        return MixinHost.drop(targetClassName, mixin, th) ? ErrorAction.WARN : ErrorAction.ERROR;
    }
}
