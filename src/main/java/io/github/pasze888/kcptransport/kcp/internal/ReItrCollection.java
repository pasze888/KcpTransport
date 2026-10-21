/*
 * KCP transport adapted from kcp-netty (MIT, (c) 2017 Zheng Sun), ReItrCollection
 * https://github.com/szhnet/kcp-netty - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Recompiled for Netty 4.2; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp.internal;

import java.util.Collection;

/**
 * @author <a href="mailto:szhnet@gmail.com">szh</a>
 */
public interface ReItrCollection<E> extends Collection<E> {

    @Override
    ReusableIterator<E> iterator();

}
