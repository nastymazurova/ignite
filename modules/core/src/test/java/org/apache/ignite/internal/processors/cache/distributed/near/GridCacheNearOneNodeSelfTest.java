/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.distributed.near;

import org.apache.ignite.*;
import org.apache.ignite.cache.*;
import org.apache.ignite.cache.GridCache;
import org.apache.ignite.cache.store.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.internal.processors.cache.*;
import org.apache.ignite.spi.discovery.tcp.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.*;
import org.apache.ignite.transactions.*;
import org.apache.ignite.testframework.junits.common.*;

import javax.cache.configuration.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

import static org.apache.ignite.cache.CacheAtomicityMode.*;
import static org.apache.ignite.cache.CacheDistributionMode.*;
import static org.apache.ignite.cache.CacheMode.*;
import static org.apache.ignite.transactions.IgniteTxConcurrency.*;
import static org.apache.ignite.transactions.IgniteTxIsolation.*;

/**
 * Single node test for near cache.
 */
public class GridCacheNearOneNodeSelfTest extends GridCommonAbstractTest {
    /** Cache store. */
    private static TestStore store = new TestStore();

    /**
     *
     */
    public GridCacheNearOneNodeSelfTest() {
        super(true /*start grid. */);
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        store.reset();

        cache().removeAll();

        assertEquals("DHT entries: " + dht().entries(), 0, dht().size());
        assertEquals("Near entries: " + near().entries(), 0, near().size());
        assertEquals("Cache entries: " + cache().entrySet(), 0, cache().size());
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override protected IgniteConfiguration getConfiguration() throws Exception {
        IgniteConfiguration cfg = super.getConfiguration();

        TcpDiscoverySpi disco = new TcpDiscoverySpi();

        disco.setIpFinder(new TcpDiscoveryVmIpFinder(true));

        cfg.setDiscoverySpi(disco);

        CacheConfiguration cacheCfg = defaultCacheConfiguration();

        cacheCfg.setCacheMode(PARTITIONED);
        cacheCfg.setBackups(1);
        cacheCfg.setAtomicityMode(TRANSACTIONAL);
        cacheCfg.setDistributionMode(NEAR_PARTITIONED);

        cacheCfg.setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC);

        cacheCfg.setCacheStoreFactory(new FactoryBuilder.SingletonFactory(store));
        cacheCfg.setReadThrough(true);
        cacheCfg.setWriteThrough(true);
        cacheCfg.setLoadPreviousValue(true);

        cfg.setCacheConfiguration(cacheCfg);

        return cfg;
    }

    /** @throws Exception If failed. */
    public void testRemove() throws Exception {
        GridCache<Integer, String> near = cache();

        assertEquals("DHT entries: " + dht().entries(), 0, dht().size());
        assertEquals("Near entries: " + near().entries(), 0, near().size());
        assertEquals("Cache entries: " + cache().entrySet(), 0, cache().size());

        for (int i = 0; i < 10; i++)
            near.put(i, Integer.toString(i));

        assertEquals("DHT entries: " + dht().entries(), 10, dht().size());
        assertEquals("Near entries: " + near().entries(), 10, near().size());
        assertEquals("Cache entries: " + cache().entrySet(), 10, cache().size());

        cache().remove(0);

        assertEquals("DHT entries: " + dht().entries(), 9, dht().size());
        assertEquals("Near entries: " + near().entries(), 9, near().size());
        assertEquals("Cache entries: " + cache().entrySet(), 9, cache().size());

        cache().removeAll();

        assertEquals("DHT entries: " + dht().entries(), 0, dht().size());
        assertEquals("Near entries: " + near().entries(), 0, near().size());
        assertEquals("Cache entries: " + cache().entrySet(), 0, cache().size());
    }

    /** @throws Exception If failed. */
    public void testReadThrough() throws Exception {
        GridCache<Integer, String> near = cache();

        GridCache<Integer, String> dht = dht();

        String s = near.get(1);

        assert s != null;
        assertEquals(s, "1");

        assertEquals(1, near.size());
        assertEquals(1, near.size());

        String d = dht.peek(1);

        assert d != null;
        assertEquals(d, "1");

        assert dht.size() == 1;
        assert dht.size() == 1;

        assert store.hasValue(1);
    }

    /**
     * Test Optimistic repeatable read write-through.
     *
     * @throws Exception If failed.
     */
    @SuppressWarnings({"ConstantConditions"})
    public void testOptimisticTxWriteThrough() throws Exception {
        GridCache<Integer, String> near = cache();
        GridCacheAdapter<Integer, String> dht = dht();

        try (IgniteTx tx = cache().txStart(OPTIMISTIC, REPEATABLE_READ) ) {
            near.putx(2, "2");
            near.put(3, "3");

            assert "2".equals(near.get(2));
            assert "3".equals(near.get(3));

            GridCacheEntryEx<Integer, String> entry = dht.peekEx(2);

            assert entry == null || entry.rawGetOrUnmarshal(false) == null : "Invalid entry: " + entry;
            assert dht.peek(3) != null;

            tx.commit();
        }

        assert "2".equals(near.get(2));
        assert "3".equals(near.get(3));

        assert "2".equals(dht.get(2));
        assert "3".equals(dht.get(3));

        assertEquals(2, near.size());
        assertEquals(2, near.size());

        assertEquals(2, dht.size());
        assertEquals(2, dht.size());
    }

    /** @throws Exception If failed. */
    public void testSingleLockPut() throws Exception {
        IgniteCache<Integer, String> near = jcache();

        near.lock(1).lock();

        try {
            near.put(1, "1");
            near.put(2, "2");

            String one = near.getAndPut(1, "3");

            assertNotNull(one);
            assertEquals("1", one);
        }
        finally {
            near.lock(1).unlock();
        }
    }

    /** @throws Exception If failed. */
    public void testSingleLock() throws Exception {
        IgniteCache<Integer, String> near = jcache();

        Lock lock = near.lock(1);

        lock.lock();

        try {
            near.put(1, "1");

            assertEquals("1", near.localPeek(1));
            assertEquals("1", dht().peek(1));

            assertEquals("1", near.get(1));
            assertEquals("1", near.getAndRemove(1));

            assertNull(near.localPeek(1));
            assertNull(dht().peek(1));

            assertTrue(near.isLocalLocked(1, false));
            assertTrue(near.isLocalLocked(1, true));
        }
        finally {
            near.lock(1).unlock();
        }

        assertFalse(near.isLocalLocked(1, false));
        assertFalse(near.isLocalLocked(1, true));
    }

    /** @throws Exception If failed. */
    public void testSingleLockReentry() throws Exception {
        IgniteCache<Integer, String> near = jcache();

        near.lock(1).lock();

        try {
            near.put(1, "1");

            assertEquals("1", near.localPeek(1));
            assertEquals("1", dht().peek(1));

            assertTrue(near.isLocalLocked(1, false));
            assertTrue(near.isLocalLocked(1, true));

            near.lock(1).lock(); // Reentry.

            try {
                assertEquals("1", near.get(1));
                assertEquals("1", near.getAndRemove(1));

                assertNull(near.localPeek(1));
                assertNull(dht().peek(1));

                assertTrue(near.isLocalLocked(1, false));
                assertTrue(near.isLocalLocked(1, true));
            }
            finally {
                near.lock(1).unlock();
            }

            assertTrue(near.isLocalLocked(1, false));
            assertTrue(near.isLocalLocked(1, true));
        }
        finally {
            near.lock(1).unlock();
        }

        assertFalse(near.isLocalLocked(1, false));
        assertFalse(near.isLocalLocked(1, true));
    }

    /** @throws Exception If failed. */
    public void testTransactionSingleGet() throws Exception {
        GridCache<Integer, String> cache = cache();

        cache.put(1, "val1");

        assertEquals("val1", dht().peek(1));
        assertNull(near().peekNearOnly(1));

        IgniteTx tx = cache.txStart(PESSIMISTIC, REPEATABLE_READ);

        assertEquals("val1", cache.get(1));

        tx.commit();

        assertEquals("val1", dht().peek(1));
        assertNull(near().peekNearOnly(1));
    }

    /** @throws Exception If failed. */
    public void testTransactionSingleGetRemove() throws Exception {
        GridCache<Integer, String> cache = cache();

        cache.put(1, "val1");

        assertEquals("val1", dht().peek(1));
        assertNull(near().peekNearOnly(1));

        IgniteTx tx = cache.txStart(PESSIMISTIC, REPEATABLE_READ);

        assertEquals("val1", cache.get(1));

        assertTrue(cache.removex(1));

        tx.commit();

        assertNull(dht().peek(1));
        assertNull(near().peekNearOnly(1));
    }

    /**
     *
     */
    private static class TestStore extends CacheStoreAdapter<Integer, String> {
        /** Map. */
        private ConcurrentMap<Integer, String> map = new ConcurrentHashMap<>();

        /** Create flag. */
        private volatile boolean create = true;

        /**
         *
         */
        void reset() {
            map.clear();

            create = true;
        }

        /** @param create Create flag. */
        void create(boolean create) {
            this.create = create;
        }

        /** @return Create flag. */
        boolean isCreate() {
            return create;
        }

        /**
         * @param key Key.
         * @return Value.
         */
        String value(Integer key) {
            return map.get(key);
        }

        /**
         * @param key Key.
         * @return {@code True} if has value.
         */
        boolean hasValue(Integer key) {
            return map.containsKey(key);
        }

        /** @return {@code True} if empty. */
        boolean isEmpty() {
            return map.isEmpty();
        }

        /** {@inheritDoc} */
        @Override public String load(Integer key) {
            if (!create)
                return map.get(key);

            String s = map.putIfAbsent(key, key.toString());

            return s == null ? key.toString() : s;
        }

        /** {@inheritDoc} */
        @Override public void write(javax.cache.Cache.Entry<? extends Integer, ? extends String> e) {
            map.put(e.getKey(), e.getValue());
        }

        /** {@inheritDoc} */
        @Override public void delete(Object key) {
            map.remove(key);
        }
    }
}
