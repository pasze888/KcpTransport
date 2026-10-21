/*
 * KCP transport adapted from kcp-netty (MIT, (c) 2017 Zheng Sun), Consts
 * https://github.com/szhnet/kcp-netty - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Recompiled for Netty 4.2; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp;

import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

/**
 * @author <a href="mailto:szhnet@gmail.com">szh</a>
 */
public class Consts {

    public static final InternalLogger sheduleUpdateLog = InternalLoggerFactory.getInstance("io.github.pasze888.kcptransport.kcp" +
            ".sheduleUpdate");

    public static final int FIXED_RECV_BYTEBUF_ALLOCATE_SIZE = SystemPropertyUtil.getInt("io.jpower.kcp" +
            ".udpRecvAllocateSize", 2048);

    public static final int CLOSE_WAIT_TIME = 5 * 1000;

}
