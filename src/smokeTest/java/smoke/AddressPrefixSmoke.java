package smoke;

import io.github.pasze888.kcptransport.KcpAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * Covers the one piece of genuinely new logic in the client wiring: the {@code kcp;} prefix.
 *
 * <p>Prefix stripping happens before the address reaches DNS, because after resolution the
 * intent has no carrier left. This class only pins the pure function; that it is actually
 * consulted at the connect site is proven by the live run, not here.
 */
public final class AddressPrefixSmoke {

    public static void main(String[] args) {
        System.out.println("[prefix] vanilla addresses stay untouched");
        for (String vanilla : new String[]{"example.com", "example.com:25566", "127.0.0.1", "localhost:25565"}) {
            KcpAddress parsed = KcpAddress.parse(vanilla);
            check(!parsed.kcp(), "plain address must not enable kcp: " + vanilla);
            check(parsed.address().equals(ServerAddress.parseString(vanilla)),
                    "plain address must be preserved exactly: " + vanilla + " -> " + parsed.address());
        }

        System.out.println("[prefix] kcp; enables and strips");
        checkKcp("kcp;example.com", "example.com", 25565);
        checkKcp("kcp;example.com:25566", "example.com", 25566);
        checkKcp("kcp;127.0.0.1:1234", "127.0.0.1", 1234);
        checkKcp("kcp;localhost", "localhost", 25565);

        System.out.println("[prefix] only a leading kcp; counts");
        // A prefix later in the string is not a prefix; the address is left alone.
        KcpAddress embedded = KcpAddress.parse("example.com/kcp;foo");
        check(!embedded.kcp(), "kcp; must only be recognised at the start");

        System.out.println("[prefix] empty payload after prefix is rejected");
        check(KcpAddress.parse("kcp;").kcp(), "kcp; alone still carries the intent");
        // Vanilla's parseString turns an empty host into the reserved "server.invalid", which
        // no DNS can resolve, so a bare "kcp;" fails at the same place a typo would.
        check("server.invalid".equals(KcpAddress.parse("kcp;").address().getHost()),
                "kcp; alone must fall back to vanilla's unresolvable placeholder host");

        System.out.println("[prefix] case sensitivity");
        check(!KcpAddress.parse("KCP;example.com").kcp(), "prefix is lowercase-only by design");
        check(!KcpAddress.parse("Kcp;example.com").kcp(), "prefix is lowercase-only by design");

        System.out.println("[prefix] address objects carry the intent to the connect screen");
        // ConnectScreen receives a ServerAddress, not the raw string: the prefix has to survive
        // inside the object until the resolver strips it.
        KcpAddress carried = KcpAddress.parse(new ServerAddress("kcp;example.com", 25566));
        check(carried.kcp(), "a prefixed address object must enable kcp");
        check("example.com".equals(carried.address().getHost()),
                "host must be stripped: " + carried.address().getHost());
        check(carried.address().getPort() == 25566, "port must survive stripping");
        ServerAddress plain = new ServerAddress("example.com", 25566);
        check(!KcpAddress.parse(plain).kcp(), "plain address object stays on tcp");
        check(KcpAddress.strip(plain) == plain, "strip must pass an unprefixed address through");

        System.out.println("[prefix] PASS: prefix parsing behaves as specified");
    }

    private static void checkKcp(String input, String host, int port) {
        KcpAddress parsed = KcpAddress.parse(input);
        check(parsed.kcp(), "must enable kcp: " + input);
        check(parsed.address().getHost().equals(host),
                "host mismatch for " + input + ": " + parsed.address().getHost());
        check(parsed.address().getPort() == port,
                "port mismatch for " + input + ": " + parsed.address().getPort());
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("FAIL: " + message);
        }
    }
}
