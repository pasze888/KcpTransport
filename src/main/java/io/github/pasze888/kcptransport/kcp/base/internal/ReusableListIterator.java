/*
 * KCP transport vendored from java-kcp (Apache-2.0, (c) jinmiao / l42111996), ReusableListIterator.java
 * https://github.com/l42111996/java-Kcp - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Modified for KcpTransport; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp.base.internal;

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
    @Override
    ReusableListIterator<E> rewind();


    /**
     * Reset the iterator and specify index of the first element to be returned from the iterator.
     *
     * @param index index of the first element to be returned from the iterator
     * @return this iterator
     */
    ReusableListIterator<E> rewind(int index);

}
