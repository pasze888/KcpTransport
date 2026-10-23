/*
 * KCP transport vendored from java-kcp (Apache-2.0, (c) jinmiao / l42111996), ReusableIterator.java
 * https://github.com/l42111996/java-Kcp - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Modified for KcpTransport; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp.base.internal;

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
