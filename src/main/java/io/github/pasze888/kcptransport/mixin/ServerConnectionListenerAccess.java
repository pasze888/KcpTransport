package io.github.pasze888.kcptransport.mixin;

import io.netty.channel.ChannelFuture;
import java.util.List;
import java.util.Queue;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read access to the three private fields the KCP listener needs, so it can join the same
 * bookkeeping the TCP listener already drives.
 */
@Mixin(ServerConnectionListener.class)
public interface ServerConnectionListenerAccess {

    @Accessor("server")
    MinecraftServer kcpTransport$server();

    @Accessor("channels")
    List<ChannelFuture> kcpTransport$channels();

    @Accessor("pendingConnections")
    Queue<Connection> kcpTransport$pendingConnections();
}
