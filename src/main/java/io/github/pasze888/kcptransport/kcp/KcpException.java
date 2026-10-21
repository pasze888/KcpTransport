/*
 * KCP transport adapted from kcp-netty (MIT, (c) 2017 Zheng Sun), KcpException
 * https://github.com/szhnet/kcp-netty - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Recompiled for Netty 4.2; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp;

/**
 * @author <a href="mailto:szhnet@gmail.com">szh</a>
 */
public class KcpException extends RuntimeException {

    private static final long serialVersionUID = 7055403679773787210L;

    public KcpException() {
        super();
    }

    public KcpException(String message, Throwable cause) {
        super(message, cause);
    }

    public KcpException(String message) {
        super(message);
    }

    public KcpException(Throwable cause) {
        super(cause);
    }

}
