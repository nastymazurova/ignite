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

package org.apache.ignite.internal.processors.cache.distributed;

import org.apache.ignite.*;
import org.apache.ignite.cache.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.spi.discovery.tcp.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.*;
import org.apache.ignite.testframework.*;
import org.apache.ignite.testframework.junits.common.*;
import org.jetbrains.annotations.*;

import java.util.*;
import java.util.concurrent.*;

import static org.apache.ignite.cache.CacheAtomicityMode.*;
import static org.apache.ignite.cache.CacheDistributionMode.*;
import static org.apache.ignite.cache.CachePreloadMode.*;
import static org.apache.ignite.cache.CacheWriteSynchronizationMode.*;

/**
 * Test cases for multi-threaded tests.
 */
@SuppressWarnings({"FieldCanBeLocal"})
public abstract class GridCacheLockAbstractTest extends GridCommonAbstractTest {
    /** Grid1. */
    private static Ignite ignite1;

    /** Grid2. */
    private static Ignite ignite2;

    /** (for convenience). */
    private static IgniteCache<Integer, String> cache1;

    /** (for convenience). */
    private static IgniteCache<Integer, String> cache2;

    /** Ip-finder. */
    private static TcpDiscoveryIpFinder ipFinder = new TcpDiscoveryVmIpFinder(true);

    /**
     *
     */
    protected GridCacheLockAbstractTest() {
        super(false /*start grid. */);
    }

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        TcpDiscoverySpi disco = new TcpDiscoverySpi();

        disco.setIpFinder(ipFinder);

        cfg.setDiscoverySpi(disco);

        cfg.setCacheConfiguration(cacheConfiguration());

        return cfg;
    }

    /**
     * @return Cache configuration.
     */
    protected CacheConfiguration cacheConfiguration() {
        CacheConfiguration cacheCfg = defaultCacheConfiguration();

        cacheCfg.setCacheMode(cacheMode());
        cacheCfg.setWriteSynchronizationMode(FULL_ASYNC);
        cacheCfg.setPreloadMode(SYNC);
        cacheCfg.setAtomicityMode(TRANSACTIONAL);
        cacheCfg.setDistributionMode(NEAR_PARTITIONED);

        return cacheCfg;
    }

    /**
     * @return Cache mode.
     */
    protected abstract CacheMode cacheMode();

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        ignite1 = startGrid(1);
        ignite2 = startGrid(2);

        cache1 = ignite1.jcache(null);
        cache2 = ignite2.jcache(null);
    }

    /** {@inheritDoc} */
    @Override protected void afterTestsStopped() throws Exception {
        stopAllGrids();
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        super.afterTest();

        info("Executing afterTest() callback...");

        info("Before 1st removeAll().");

        cache1.removeAll();

        info("Before 2nd removeAll().");

        cache2.removeAll();

        assert cache1.size() == 0 : "Cache is not empty: " + cache1;
        assert cache2.size() == 0 : "Cache is not empty: " + cache2;
    }

    /**
     * @return Partitioned flag.
     */
    protected boolean isPartitioned() {
        return false;
    }

    /**
     * @param k Key to check.
     * @param idx Grid index.
     * @return {@code True} if locked.
     */
    private boolean locked(Integer k, int idx) {
        if (isPartitioned())
            return near(idx).isLockedNearOnly(k);

        return cache(idx).isLocked(k);
    }

    /**
     * @param keys Keys to check.
     * @param idx Grid index.
     * @return {@code True} if locked.
     */
    private boolean locked(Iterable<Integer> keys, int idx) {
        if (isPartitioned())
            return near(idx).isAllLockedNearOnly(keys);

        for (Integer key : keys) {
            if (!cache(idx).isLocked(key))
                return false;
        }

        return true;
    }

    /**
     * @throws Exception If test failed.
     */
    @SuppressWarnings({"TooBroadScope"})
    public void testLockSingleThread() throws Exception {
        int k = 1;
        String v = String.valueOf(k);

        info("Before lock for key: " + k);

        cache1.lock(k).lock();

        info("After lock for key: " + k);

        try {
            assert cache1.isLocalLocked(k, false);
            assert cache1.isLocalLocked(k, true);

            // Put to cache.
            cache1.put(k, v);

            info("Put " + k + '=' + k + " key pair into cache.");
        }
        finally {
            cache1.lock(k).unlock();

            info("Unlocked key: " + k);
        }

        assert !locked(k, 1);
        assert !cache1.isLocalLocked(k, true);
    }

    /**
     * @throws Exception If test failed.
     */
    @SuppressWarnings({"TooBroadScope"})
    public void testLock() throws Exception {
        final int kv = 1;

        final CountDownLatch l1 = new CountDownLatch(1);
        final CountDownLatch l2 = new CountDownLatch(1);

        GridTestThread t1 = new GridTestThread(new Callable<Object>() {
            @Nullable @Override public Object call() throws Exception {
                info("Before lock for key: " + kv);

                cache1.lock(kv).lock();

                info("After lock for key: " + kv);

                try {
                    assert cache1.isLocalLocked(kv, false);
                    assert cache1.isLocalLocked(kv, true);

                    l1.countDown();

                    info("Let thread2 proceed.");

                    cache1.put(kv, Integer.toString(kv));

                    info("Put " + kv + '=' + Integer.toString(kv) + " key pair into cache.");
                }
                finally {
                    Thread.sleep(1000);

                    cache1.lockAll(Collections.singleton(kv)).unlock();

                    info("Unlocked key in thread 1: " + kv);
                }

                l2.await();

                assert !cache1.isLocalLocked(kv, true);
                assert !locked(kv, 1);

                return null;
            }
        });

        GridTestThread t2 = new GridTestThread(new Callable<Object>() {
            @Nullable @Override public Object call() throws Exception {
                info("Waiting for latch1...");

                l1.await();

                cache2.lock(kv).lock();

                try {
                    String v = cache2.get(kv);

                    assert v != null : "Value is null for key: " + kv;

                    assertEquals(Integer.toString(kv), v);
                }
                finally {
                    cache2.lockAll(Collections.singleton(kv)).unlock();

                    info("Unlocked key in thread 2: " + kv);
                }

                assert !locked(kv, 2);
                assert !cache2.isLocalLocked(kv, true);

                Thread.sleep(1000);

                l2.countDown();

                return null;
            }
        });

        t1.start();
        t2.start();

        t1.join();
        t2.join();

        t1.checkError();
        t2.checkError();
    }

    /**
     * @throws Exception If test failed.
     */
    public void testLockAndPut() throws Exception {
        final CountDownLatch l1 = new CountDownLatch(1);
        final CountDownLatch l2 = new CountDownLatch(1);

        GridTestThread t1 = new GridTestThread(new Callable<Object>() {
            @Nullable @Override public Object call() throws Exception {
                cache1.lock(1).lock();

                info("Locked cache key: 1");

                try {
                    assert cache1.isLocalLocked(1, false);
                    assert cache1.isLocalLocked(1, true);

                    info("Verified that cache key is locked: 1");

                    cache1.put(1, "1");

                    info("Put key value pair into cache: 1='1'");

                    l1.countDown();

                    info("Released latch1");

                    // Hold lock for a bit.
                    Thread.sleep(50);

                    info("Woke up from sleep.");
                }
                finally {
                    cache1.lockAll(Collections.singleton(1)).unlock();

                    info("Unlocked cache key: 1");
                }

                l2.await();

                assert !locked(1, 1);
                assert !cache1.isLocalLocked(1, true);

                return null;
            }
        });

        GridTestThread t2 = new GridTestThread(new Callable<Object>() {
            @Nullable @Override public Object call() throws Exception {
                info("Beginning to await on latch 1");

                l1.await();

                info("Finished awaiting on latch 1");

                assertEquals("1", cache1.get(1));

                info("Retrieved value from cache for key: 1");

                cache1.put(1, "2");

                info("Put key-value pair into cache: 1='2'");

                assertEquals("2", cache1.getAndRemove(1));

                l2.countDown();

                info("Removed key from cache: 1");

                return null;
            }
        });

        t1.start();
        t2.start();

        t1.join();
        t2.join();

        t1.checkError();
        t2.checkError();
    }

    /**
     * @throws Exception If test failed.
     */
    @SuppressWarnings({"TooBroadScope"})
    public void testLockTimeoutTwoThreads() throws Exception {
        int keyCnt = 1;

        final Set<Integer> keys = new HashSet<>();

        for (int i = 1; i <= keyCnt; i++)
            keys.add(i);

        final CountDownLatch l1 = new CountDownLatch(1);
        final CountDownLatch l2 = new CountDownLatch(1);

        IgniteFuture<?> fut1 = GridTestUtils.runMultiThreadedAsync(new Callable<Object>() {
                @Nullable @Override public Object call() throws Exception {
                    info("Before lock for keys.");

                    cache1.lockAll(keys).lock();

                    info("After lock for keys.");

                    try {
                        for (Integer key : keys) {
                            assert cache1.isLocalLocked(key, false);
                            assert cache1.isLocalLocked(key, true);
                        }

                        l1.countDown();

                        info("Let thread2 proceed.");

                        for (int i : keys) {
                            info("Before put key: " + i);

                            cache1.put(i, Integer.toString(i));

                            if (i % 50 == 0)
                                info("Stored key pairs in cache: " + i);
                        }
                    }
                    finally {
                        l2.await();

                        info("Before unlock keys in thread 1: " + keys);

                        cache1.lockAll(keys).unlock();

                        info("Unlocked entry for keys.");
                    }

                    assert !locked(keys, 1);

                    return null;
                }
            }, 1, "TEST-THREAD-1");

        IgniteFuture<?> fut2 = GridTestUtils.runMultiThreadedAsync(new Callable<Object>() {
                @Nullable @Override public Object call() throws Exception {
                    info("Waiting for latch1...");

                    try {
                        l1.await();

                        // This call should not acquire the lock since
                        // other thread is holding it.
                        assert !cache1.lockAll(keys).tryLock();

                        info("Before unlock keys in thread 2: " + keys);

                        cache1.lockAll(keys).unlock();

                        // The keys should still be locked.
                        for (Integer key : keys)
                            assert cache1.isLocalLocked(key, false);
                    }
                    finally {
                        l2.countDown();
                    }

                    return null;
                }
            }, 1, "TEST-THREAD-2");

        fut1.get();
        fut2.get();
    }
}
