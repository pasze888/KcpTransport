package io.github.pasze888.kcptransport.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.pasze888.kcptransport.KcpAddress;
import io.github.pasze888.kcptransport.KcpClientBootstrap;
import java.net.InetSocketAddress;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.Connection;
import net.minecraft.server.network.EventLoopGroupHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Carries the {@code kcp;} prefix from the address field into the connect call.
 *
 * <p>Two injections, and both are needed. The prefix has to be read before DNS, because
 * {@code ServerNameResolver} resolves the host the moment it is asked and after that the intent
 * has no carrier left; so the connect thread's constructor parses it, records the decision on
 * the instance, and rewrites the captured address to the clean one in place. Then the connect
 * call itself is wrapped and the recorded decision picks the channel.
 *
 * <p>A static registry keyed by host was rejected: the connect call receives the resolved
 * {@code InetSocketAddress}, so the hostname may already be an IP and the key would silently
 * miss.
 *
 * <p>Only this path is touched, and every connect that goes through it shares the behaviour:
 * multiplayer QuickPlay lands here too (verified in #18), so a prefixed QuickPlay address also
 * takes KCP. Realms and server transfers carry addresses supplied elsewhere, without a prefix, so
 * they stay on TCP. The server-list ping only strips the prefix and stays on TCP, which is handled
 * by {@link ServerNameResolverKcpMixin}.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.ConnectScreen$1")
public abstract class ConnectScreenKcpMixin {

    @Shadow
    @Mutable
    private ServerAddress val$hostAndPort;

    @Unique
    private boolean kcpTransport$useKcp;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void kcpTransport$stripPrefix(CallbackInfo ci) {
        KcpAddress parsed = KcpAddress.parse(this.val$hostAndPort);
        this.kcpTransport$useKcp = parsed.kcp();
        if (parsed.kcp()) {
            this.val$hostAndPort = parsed.address();
        }
    }

    @WrapOperation(
            method = "run",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/Connection;connect(Ljava/net/InetSocketAddress;Lnet/minecraft/server/network/EventLoopGroupHolder;Lnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;"))
    private io.netty.channel.ChannelFuture kcpTransport$connect(InetSocketAddress address,
                                                               EventLoopGroupHolder holder,
                                                               Connection connection,
                                                               Operation<io.netty.channel.ChannelFuture> original) {
        if (!this.kcpTransport$useKcp) {
            return original.call(address, holder, connection);
        }
        return KcpClientBootstrap.connect(address, holder, connection);
    }
}
