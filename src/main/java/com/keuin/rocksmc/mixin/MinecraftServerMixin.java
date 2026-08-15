package com.keuin.rocksmc.mixin;

import com.keuin.rocksmc.FailureReporter;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the running server, so storage failures can reach operators in game.
 *
 * <h2>Why a mixin for this</h2>
 *
 * <p>Vanilla exposes no static accessor for the {@code MinecraftServer} instance, and
 * this mod deliberately depends on only one Fabric API module -- the command API --
 * so {@code ServerLifecycleEvents} is not available. A command source would provide
 * the server, but only once somebody has run a command, which is far too late for the
 * alerts this exists to deliver.
 *
 * <p>{@code runServer} is chosen over the constructor because it is a no-argument
 * method that runs exactly once on the server thread. Injecting into {@code <init>}
 * would mean a handler mirroring the constructor's long parameter list, which breaks
 * on any signature change for no benefit.
 *
 * <p>Purely additive: it stores a reference and returns. Nothing is cancelled and no
 * behaviour changes, so a failure here cannot affect the server -- the worst case is
 * that alerts go only to the log, which is where they went before this existed.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Inject(method = "runServer", at = @At("HEAD"))
    private void rocksmc$captureServer(CallbackInfo ci) {
        FailureReporter.setServer((MinecraftServer)(Object)this);
    }
}
