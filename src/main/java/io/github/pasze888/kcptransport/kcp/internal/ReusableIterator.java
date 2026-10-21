/*
 * KCP transport adapted from kcp-netty (MIT, (c) 2017 Zheng Sun), ReusableIterator
 * https://github.com/szhnet/kcp-netty - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Recompiled for Netty 4.2; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp.internal;

import java.util.Iterator;

/**
 * Reusable iterator
 *
 * @author <a href="mailto:szhnet@gmail.com">szh</a>
 */
public interface ReusableIterator<E> extends Iterator<E> {

    /**
     * Reset the iterator to initial state.
     *
     * @return this object
     */
    ReusableIterator<E> rewind();

}
