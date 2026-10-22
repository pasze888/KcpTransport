package io.github.pasze888.kcptransport.mixin;

import io.github.pasze888.kcptransport.KcpAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Strips the {@code kcp;} prefix at the last moment before the address reaches DNS.
 *
 * <p>This is the one place both paths pass through: {@code ConnectScreen} resolving before it
 * connects, and {@code ServerStatusPinger} resolving before it pings. Stripping earlier — inside
 * {@code ServerAddress.parseString} — would also strip it before {@code ConnectScreen}'s thread
 * object is built, and the connect path would never learn that the player asked for KCP.
 * Rewriting the argument here keeps the intent readable right up to resolution, and the ping
 * still stays on TCP because it only consults the resolver.
 *
 * <p>The replacement is only built when a prefix is present; every other address is passed
 * through untouched, instance and all.
 */
@Mixin(ServerNameResolver.class)
public abstract class ServerNameResolverKcpMixin {

    @ModifyVariable(method = "resolveAddress", at = @At("HEAD"), argsOnly = true)
    private ServerAddress kcpTransport$stripPrefix(ServerAddress address) {
        return KcpAddress.strip(address);
    }
}
