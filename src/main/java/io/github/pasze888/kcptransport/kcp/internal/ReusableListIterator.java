/*
 * KCP transport adapted from kcp-netty (MIT, (c) 2017 Zheng Sun), ReusableListIterator
 * https://github.com/szhnet/kcp-netty - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Recompiled for Netty 4.2; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp.internal;

import java.util.ListIterator;

/**
 * Reusable list iterator
 *
 * @author <a href="mailto:szhnet@gmail.com">szh</a>
 */
public interface ReusableListIterator<E> extends ListIterator<E>, ReusableIterator<E> {

    /**
     * This method is identical to {@code rewind(0)}.
     *
     * @return this iterator
     */
    ReusableListIterator<E> rewind();


    /**
     * Reset the iterator and specify index of the first element to be returned from the iterator.
     *
     * @param index index of the first element to be returned from the iterator
     * @return this iterator
     */
    ReusableListIterator<E> rewind(int index);

}
