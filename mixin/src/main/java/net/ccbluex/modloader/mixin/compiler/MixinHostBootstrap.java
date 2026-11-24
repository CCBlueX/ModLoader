package net.ccbluex.modloader.mixin.compiler;

import org.spongepowered.asm.service.IMixinServiceBootstrap;

/** @agent claude-fable-5-2 */
public final class MixinHostBootstrap implements IMixinServiceBootstrap {
    @Override
    public String getName() {
        return "ModLoader";
    }

    @Override
    public String getServiceClassName() {
        return MixinHost.class.getName();
    }

    @Override
    public void bootstrap() {
    }
}
