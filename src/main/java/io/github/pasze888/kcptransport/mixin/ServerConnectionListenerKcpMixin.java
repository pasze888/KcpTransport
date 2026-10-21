package io.github.pasze888.kcptransport.mixin;

import io.github.pasze888.kcptransport.KcpServerListener;
import java.net.InetAddress;
import net.minecraft.server.network.ServerConnectionListener;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Starts the KCP listener as soon as the TCP listener came up.
 *
 * <p>{@code startTcpServerListener} is called on server startup and again by
 * {@code IntegratedServer} when a single-player world opens its LAN port; stopping uses
 * {@code ServerConnectionListener#stop}, which closes everything in the shared channel
 * list the KCP future was added to.
 */
@Mixin(ServerConnectionListener.class)
public abstract class ServerConnectionListenerKcpMixin {

    @Inject(method = "startTcpServerListener", at = @At("TAIL"))
    private void kcpTransport$startKcpListener(@Nullable InetAddress address, int port, CallbackInfo ci) {
        KcpServerListener.start((ServerConnectionListener) (Object) this, address, port);
    }
}
