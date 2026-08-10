package com.keuin.rocksmc.mixin;

import com.keuin.rocksmc.RocksMc;
import com.keuin.rocksmc.RocksMcCommand;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Registers {@code /rocksmc} alongside vanilla's own commands.
 *
 * <h2>Why a mixin rather than the Fabric API</h2>
 *
 * <p>{@code CommandRegistrationCallback} would be the idiomatic route, but it lives
 * in fabric-api, which this mod does not depend on and does not otherwise need.
 * Adding a ~10 MB API dependency and its version-compatibility surface to register
 * five subcommands is a poor trade for a storage mod whose whole point is being a
 * thin layer.
 *
 * <p>Vanilla registers every command in {@code CommandManager}'s constructor, so
 * injecting at its tail puts ours in exactly the same place with the same lifecycle.
 * The class is a plain non-final public class and the constructor is public, so this
 * needs no access widener.
 *
 * <p>The {@code dispatcher} field is private, hence the {@code @Shadow}. It is
 * assigned inline at its declaration and never reassigned, so it is non-null by the
 * time the constructor tail runs.
 *
 * <p>Registration happens regardless of backend. With {@code backend=anvil} the
 * commands still exist but report that no database is open, which is more useful
 * than a missing command an operator has to guess about.
 */
@Mixin(CommandManager.class)
public abstract class CommandManagerMixin {

    @Shadow
    @Final
    private CommandDispatcher<ServerCommandSource> dispatcher;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void rocksmc$registerCommands(CommandManager.RegistrationEnvironment env,
            CallbackInfo ci) {
        try {
            RocksMcCommand.register(this.dispatcher);
        } catch (RuntimeException e) {
            // A failure here must not stop the server from starting: commands are
            // operator convenience, not load-bearing storage. Losing them silently
            // would be worse than a loud log line, so it is an error rather than a
            // warning.
            RocksMc.logger().error("rocksmc: could not register /rocksmc commands. "
                + "Storage is unaffected; maintenance commands will be missing.", e);
        }
    }
}
