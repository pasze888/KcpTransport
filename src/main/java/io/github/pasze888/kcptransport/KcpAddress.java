package io.github.pasze888.kcptransport;

import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * Splits the {@code kcp;} opt-in prefix off a server address.
 *
 * <p>The prefix is the player's explicit decision to carry this one connection over KCP, so it
 * has to be read before the address is handed to DNS: once resolved, the intent has nowhere to
 * live. That is why this runs at the connect-thread's construction point and rewrites the
 * captured address in place.
 *
 * <p>Only the semicolon form is usable. {@code kcp://host} and {@code kcp:host} both get parsed
 * as a port by Guava's {@code HostAndPort} and fail the whole address. The prefix is
 * deliberately lowercase-only: it is a literal, not something to guess at.
 *
 * <p>This is a pure function over strings. It does not touch the network and does not decide
 * whether the peer actually speaks KCP.
 */
public final class KcpAddress {

    /** The opt-in marker, including its separator. */
    private static final String PREFIX = "kcp;";

    private final boolean kcp;
    private final ServerAddress address;

    private KcpAddress(boolean kcp, ServerAddress address) {
        this.kcp = kcp;
        this.address = address;
    }

    /**
     * Parses {@code raw} into the transport decision plus the address that decision applies to.
     *
     * <p>An address without the prefix comes back exactly as vanilla would have parsed it, so a
     * player who never types the prefix sees no change at all.
     */
    public static KcpAddress parse(String raw) {
        if (!raw.startsWith(PREFIX)) {
            return new KcpAddress(false, ServerAddress.parseString(raw));
        }
        return new KcpAddress(true, ServerAddress.parseString(raw.substring(PREFIX.length())));
    }

    /**
     * Parses an already-built address, for callers that never held the raw string.
     *
     * <p>Reconstructing the string from {@code getHost() + ":" + getPort()} would be wrong: it
     * would write the default port into an address that never named one. Only the host can
     * carry a prefix, so only the host is examined.
     */
    public static KcpAddress parse(ServerAddress address) {
        return new KcpAddress(enables(address == null ? null : address.getHost()), strip(address));
    }

    /**
     * Returns {@code address} with the prefix removed, or the same instance when it carries none.
     *
     * <p>Called at the DNS boundary, which is the last moment the intent can still be read: the
     * resolver is the one place both the connect path and the server-list ping pass through.
     */
    public static ServerAddress strip(ServerAddress address) {
        if (address == null) {
            return null;
        }
        String host = address.getHost();
        if (!host.startsWith(PREFIX)) {
            return address;
        }
        return new ServerAddress(host.substring(PREFIX.length()), address.getPort());
    }

    /** Whether {@code raw} is an address the player opted into carrying over KCP. */
    public static boolean enables(String raw) {
        return raw != null && raw.startsWith(PREFIX);
    }

    /** Whether this connection should be carried over KCP. */
    public boolean kcp() {
        return kcp;
    }

    /** The address with any prefix removed, safe to hand to DNS. */
    public ServerAddress address() {
        return address;
    }
}
