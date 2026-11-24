package net.ccbluex.modloader.mixin.compiler;

import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinErrorHandler;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Makes the failure of a mixin an error also when its config is not "required"; Mixin would only log it.
 *
 * @agent claude-fable-5-2
 */
public final class StrictErrorHandler implements IMixinErrorHandler {
    @Override
    public ErrorAction onPrepareError(IMixinConfig config, Throwable th, IMixinInfo mixin, ErrorAction action) {
        return ErrorAction.ERROR;
    }

    @Override
    public ErrorAction onApplyError(String targetClassName, Throwable th, IMixinInfo mixin, ErrorAction action) {
        return ErrorAction.ERROR;
    }
}
