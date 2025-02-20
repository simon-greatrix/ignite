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

package org.apache.ignite.internal.processors.cache;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.cache.Cache;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.cache.CachePeekMode;
import org.apache.ignite.cache.affinity.Affinity;
import org.apache.ignite.cache.eviction.fifo.FifoEvictionPolicy;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.configuration.NearCacheConfiguration;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.IgniteKernal;
import org.apache.ignite.internal.processors.affinity.AffinityTopologyVersion;
import org.apache.ignite.internal.util.GridEmptyCloseableIterator;
import org.apache.ignite.internal.util.typedef.T2;
import org.apache.ignite.spi.IgniteSpiCloseableIterator;
import org.junit.Test;
import static org.apache.ignite.cache.CacheMode.PARTITIONED;
import static org.apache.ignite.cache.CacheMode.REPLICATED;
import static org.apache.ignite.cache.CachePeekMode.ALL;
import static org.apache.ignite.cache.CachePeekMode.BACKUP;
import static org.apache.ignite.cache.CachePeekMode.NEAR;
import static org.apache.ignite.cache.CachePeekMode.OFFHEAP;
import static org.apache.ignite.cache.CachePeekMode.ONHEAP;
import static org.apache.ignite.cache.CachePeekMode.PRIMARY;

/**
 * Tests for methods using {@link CachePeekMode}:
 * <ul>
 *     <li>{@link IgniteCache#localPeek(Object, CachePeekMode...)}</li>
 *     <li>{@link IgniteCache#localSize(CachePeekMode...)}</li>
 *     <li>{@link IgniteCache#size(CachePeekMode...)}</li>
 *     <li>{@link IgniteCache#localEntries(CachePeekMode...)}</li>
 * </ul>
 */
public abstract class IgniteCachePeekModesAbstractTest extends IgniteCacheAbstractTest {
    /** */
    private static final int HEAP_ENTRIES = 30;

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String igniteInstanceName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(igniteInstanceName);

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected NearCacheConfiguration nearConfiguration() {
        return null;
    }

    /**
     * @return Has near cache flag.
     */
    protected boolean hasNearCache() {
        return false;
    }

    /** {@inheritDoc} */
    @Override protected CacheConfiguration cacheConfiguration(String igniteInstanceName) throws Exception {
        CacheConfiguration ccfg = super.cacheConfiguration(igniteInstanceName);

        ccfg.setBackups(1);

        if (hasNearCache())
            ccfg.setNearConfiguration(new NearCacheConfiguration());

        FifoEvictionPolicy plc = new FifoEvictionPolicy();
        plc.setMaxSize(HEAP_ENTRIES);

        ccfg.setEvictionPolicy(plc);
        ccfg.setOnheapCacheEnabled(true);

        return ccfg;
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testLocalPeek() throws Exception {
        checkAffinityPeek(0);

        checkAffinityPeek(1);

        checkStorage(0);

        checkStorage(1);
    }

    /**
     * @param nodeIdx Node index.
     * @throws Exception If failed.
     */
    private void checkAffinityPeek(int nodeIdx) throws Exception {
        IgniteCache<Integer, String> cache0 = jcache(nodeIdx);

        final String val = "1";

        Integer key = null;

        try {
            if (cacheMode() == REPLICATED) {
                key = backupKey(cache0);

                cache0.put(key, val);

                assertEquals(val, cache0.localPeek(key, ALL));
                assertEquals(val, cache0.localPeek(key, BACKUP));
                assertNull(cache0.localPeek(key, NEAR));
                assertNull(cache0.localPeek(key, PRIMARY));
            }
            else {
                key = nearKey(cache0);

                cache0.put(key, val);

                if (hasNearCache()) {
                    assertEquals(val, cache0.localPeek(key, NEAR));
                    assertEquals(val, cache0.localPeek(key, ALL));
                }
                else {
                    assertNull(cache0.localPeek(key, NEAR));
                    assertNull(cache0.localPeek(key, ALL));
                }

                assertNull(cache0.localPeek(key, PRIMARY));
                assertNull(cache0.localPeek(key, BACKUP));
            }

            Affinity<Integer> aff = ignite(0).affinity(DEFAULT_CACHE_NAME);

            for (int i = 0; i < gridCount(); i++) {
                if (i == nodeIdx)
                    continue;

                IgniteCache<Integer, String> cache = jcache(i);

                assertNull(cache.localPeek(key, NEAR));

                if (aff.isPrimary(ignite(i).cluster().localNode(), key)) {
                    assertEquals(val, cache.localPeek(key, PRIMARY));
                    assertEquals(val, cache.localPeek(key, ALL));
                    assertNull(cache.localPeek(key, BACKUP));
                    assertNull(cache.localPeek(key, NEAR));
                }
                else if (aff.isBackup(ignite(i).cluster().localNode(), key)) {
                    assertEquals(val, cache.localPeek(key, BACKUP));
                    assertEquals(val, cache.localPeek(key, ALL));
                    assertNull(cache.localPeek(key, PRIMARY));
                    assertNull(cache.localPeek(key, NEAR));
                }
                else {
                    assertNull(cache.localPeek(key, ALL));
                    assertNull(cache.localPeek(key, PRIMARY));
                    assertNull(cache.localPeek(key, BACKUP));
                    assertNull(cache.localPeek(key, NEAR));
                }
            }
        }
        finally {
            if (key != null)
                cache0.remove(key);
        }
    }

    /**
     * @param nodeIdx Node index.
     * @throws Exception If failed.
     */
    private void checkStorage(int nodeIdx) throws Exception {
        if (true) // TODO GG-11148.
            return;

        IgniteCache<Integer, String> cache0 = jcache(nodeIdx);

        List<Integer> keys = primaryKeys(cache0, 100, 10_000);

        try {
            final String val = "test_value";

            for (Integer key : keys)
                cache0.put(key, val);

            Ignite ignite = ignite(nodeIdx);

            GridCacheAdapter<Integer, String> internalCache =
                ((IgniteKernal)ignite).context().cache().internalCache(DEFAULT_CACHE_NAME);

            CacheObjectContext coctx = internalCache.context().cacheObjectContext();

            Set<Integer> swapKeys = new HashSet<>();

// TODO GG-11148.
//            SwapSpaceSpi swap = ignite.configuration().getSwapSpaceSpi();
//
//            IgniteSpiCloseableIterator<KeyCacheObject> it = swap.keyIterator(SPACE_NAME, null);

            IgniteSpiCloseableIterator<KeyCacheObject> it = new GridEmptyCloseableIterator<>();

            assertNotNull(it);

            while (it.hasNext()) {
                KeyCacheObject next = it.next();

                assertTrue(swapKeys.add((Integer)next.value(coctx, false)));
            }

            assertFalse(swapKeys.isEmpty());

            assertTrue(swapKeys.size() + HEAP_ENTRIES < 100);

            Set<Integer> offheapKeys = new HashSet<>();

// TODO GG-11148.
            Iterator<Map.Entry<Integer, String>> offheapIt = Collections.EMPTY_MAP.entrySet().iterator();

//            if (internalCache.context().isNear())
//                offheapIt = internalCache.context().near().dht().context().swap().lazyOffHeapIterator(false);
//            else
//                offheapIt = internalCache.context().swap().lazyOffHeapIterator(false);

            while (offheapIt.hasNext()) {
                Map.Entry<Integer, String> e = offheapIt.next();

                assertTrue(offheapKeys.add(e.getKey()));

                assertFalse(swapKeys.contains(e.getKey()));
            }

            assertFalse(offheapKeys.isEmpty());

            Set<Integer> heapKeys = new HashSet<>(keys);

            heapKeys.removeAll(offheapKeys);
            heapKeys.removeAll(swapKeys);

            assertFalse(heapKeys.isEmpty());

            log.info("Keys [swap=" + swapKeys.size() +
                ", offheap=" + offheapKeys.size() +
                ", heap=" + heapKeys.size() + ']');

            assertEquals(100, swapKeys.size() + offheapKeys.size() + heapKeys.size());

            for (Integer key : swapKeys) {
                assertEquals(val, cache0.localPeek(key));
                assertEquals(val, cache0.localPeek(key, PRIMARY));
                assertEquals(val, cache0.localPeek(key, ONHEAP));
                assertEquals(val, cache0.localPeek(key, ONHEAP, OFFHEAP));
                assertEquals(val, cache0.localPeek(key, PRIMARY, ONHEAP));
                assertEquals(val, cache0.localPeek(key, PRIMARY, ONHEAP, OFFHEAP));

                assertNull(cache0.localPeek(key, BACKUP));
                assertNull(cache0.localPeek(key, NEAR));

                assertNull(cache0.localPeek(key, ONHEAP));
                assertNull(cache0.localPeek(key, OFFHEAP));
            }

            for (Integer key : offheapKeys) {
                assertEquals(val, cache0.localPeek(key, OFFHEAP));
                assertEquals(val, cache0.localPeek(key, ONHEAP, OFFHEAP));
                assertEquals(val, cache0.localPeek(key, ONHEAP, OFFHEAP));
                assertEquals(val, cache0.localPeek(key, PRIMARY, OFFHEAP));

                assertNull(cache0.localPeek(key, OFFHEAP, BACKUP));
                assertNull(cache0.localPeek(key, OFFHEAP, NEAR));

                assertNull(cache0.localPeek(key, ONHEAP));
                assertNull(cache0.localPeek(key));
            }

            for (Integer key : heapKeys) {
                assertEquals(val, cache0.localPeek(key, ONHEAP));
                assertEquals(val, cache0.localPeek(key, ONHEAP));
                assertEquals(val, cache0.localPeek(key, OFFHEAP, ONHEAP));
                assertEquals(val, cache0.localPeek(key, PRIMARY, ONHEAP));

                assertNull(cache0.localPeek(key, ONHEAP, BACKUP));
                assertNull(cache0.localPeek(key, ONHEAP, NEAR));

                assertNull(cache0.localPeek(key));
                assertNull(cache0.localPeek(key, OFFHEAP));
            }
        }
        finally {
            cache0.removeAll(new HashSet<>(keys));
        }
    }

    /**
     * @param nodeIdx Node index.
     * @throws Exception If failed.
     */
    private void checkSizeAffinityFilter(int nodeIdx) throws Exception {
        IgniteCache<Integer, String> cache0 = jcache(nodeIdx);

        final int PUT_KEYS = 10;

        List<Integer> keys = null;

        try {
            if (cacheMode() == REPLICATED) {
                keys = backupKeys(cache0, 10, 0);

                for (Integer key : keys)
                    cache0.put(key, String.valueOf(key));

                assertEquals(PUT_KEYS, cache0.localSize(BACKUP));
                assertEquals(PUT_KEYS, cache0.localSize(ALL));
                assertEquals(0, cache0.localSize());
                assertEquals(0, cache0.localSize(PRIMARY));
                assertEquals(0, cache0.localSize(NEAR));

                for (int i = 0; i < gridCount(); i++) {
                    IgniteCache<Integer, String> cache = jcache(i);

                    assertEquals(0, cache.size(NEAR));
                    assertEquals(PUT_KEYS, cache.size(PRIMARY));
                    assertEquals(PUT_KEYS * (gridCount() - 1), cache.size(BACKUP));
                    assertEquals(PUT_KEYS * gridCount(), cache.size(PRIMARY, BACKUP));
                    assertEquals(PUT_KEYS * gridCount(), cache.size(ALL)); // Primary + backups.
                }
            }
            else {
                keys = nearKeys(cache0, PUT_KEYS, 0);

                for (Integer key : keys)
                    cache0.put(key, String.valueOf(key));

                if (hasNearCache()) {
                    assertEquals(0, cache0.localSize());
                    assertEquals(PUT_KEYS, cache0.localSize(ALL));
                    assertEquals(PUT_KEYS, cache0.localSize(NEAR));

                    for (int i = 0; i < gridCount(); i++) {
                        IgniteCache<Integer, String> cache = jcache(i);

                        assertEquals(PUT_KEYS, cache.size(NEAR));
                        assertEquals(PUT_KEYS, cache.size(BACKUP));
                        assertEquals(PUT_KEYS * 2, cache.size(PRIMARY, BACKUP));
                        assertEquals(PUT_KEYS * 2 + PUT_KEYS, cache.size(ALL)); // Primary + backups + near.
                    }
                }
                else {
                    assertEquals(0, cache0.localSize());
                    assertEquals(0, cache0.localSize(ALL));
                    assertEquals(0, cache0.localSize(NEAR));

                    for (int i = 0; i < gridCount(); i++) {
                        IgniteCache<Integer, String> cache = jcache(i);

                        assertEquals(0, cache.size(NEAR));
                        assertEquals(PUT_KEYS, cache.size(BACKUP));
                        assertEquals(PUT_KEYS * 2, cache.size(PRIMARY, BACKUP));
                        assertEquals(PUT_KEYS * 2, cache.size(ALL)); // Primary + backups.
                    }
                }

                assertEquals(0, cache0.localSize(BACKUP));
                assertEquals(0, cache0.localSize(PRIMARY));
            }

            checkPrimarySize(PUT_KEYS);

            Affinity<Integer> aff = ignite(0).affinity(DEFAULT_CACHE_NAME);

            for (int i = 0; i < gridCount(); i++) {
                if (i == nodeIdx)
                    continue;

                ClusterNode node = ignite(i).cluster().localNode();

                int primary = 0;
                int backups = 0;

                for (Integer key : keys) {
                    if (aff.isPrimary(node, key))
                        primary++;
                    else if (aff.isBackup(node, key))
                        backups++;
                }

                IgniteCache<Integer, String> cache = jcache(i);

                assertEquals(primary, cache.localSize(PRIMARY));
                assertEquals(backups, cache.localSize(BACKUP));
                assertEquals(primary + backups, cache.localSize(PRIMARY, BACKUP));
                assertEquals(primary + backups, cache.localSize(BACKUP, PRIMARY));
                assertEquals(primary + backups, cache.localSize(ALL));
            }

            cache0.remove(keys.get(0));

            checkPrimarySize(PUT_KEYS - 1);

            if (cacheMode() == REPLICATED) {
                assertEquals(PUT_KEYS - 1, cache0.localSize(ALL));
                assertEquals(0, cache0.localSize(PRIMARY));
                assertEquals(PUT_KEYS - 1, cache0.localSize(BACKUP));
            }
            else {
                if (hasNearCache())
                    assertEquals(PUT_KEYS - 1, cache0.localSize(ALL));
                else
                    assertEquals(0, cache0.localSize(ALL));
            }
        }
        finally {
            if (keys != null)
                cache0.removeAll(new HashSet<>(keys));
        }

        checkEmpty();
    }

    /**
     * @param nodeIdx Node index.
     * @throws Exception If failed.
     */
    private void checkPartitionSizeAffinityFilter(int nodeIdx) throws Exception {
        IgniteCache<Integer, String> cache0 = jcache(nodeIdx);

        final int PUT_KEYS = 10;

        int part = nodeIdx;

        List<Integer> keys = null;

        try {
            if (cacheMode() == REPLICATED) {
                keys = backupKeys(cache0, 10, 0);

                for (Integer key : keys)
                    cache0.put(key, String.valueOf(key));

                int partSize = 0;

                for (Integer key : keys) {
                    int keyPart = ignite(nodeIdx).affinity(DEFAULT_CACHE_NAME).partition(key);
                    if (keyPart == part)
                        partSize++;
                }

                assertEquals(PUT_KEYS, cache0.localSize(BACKUP));
                assertEquals(PUT_KEYS, cache0.localSize(ALL));
                assertEquals(partSize, cache0.localSizeLong(part, BACKUP));
                assertEquals(partSize, cache0.localSizeLong(part, ALL));
                assertEquals(0, cache0.localSizeLong(part, PRIMARY));
                assertEquals(0, cache0.localSizeLong(part, NEAR));

                for (int i = 0; i < gridCount(); i++) {
                    IgniteCache<Integer, String> cache = jcache(i);
                    assertEquals(0, cache.size(NEAR));
                    assertEquals(partSize, cache.sizeLong(part, PRIMARY));
                    assertEquals(partSize * (gridCount() - 1), cache.sizeLong(part, BACKUP));
                    assertEquals(partSize * gridCount(), cache.sizeLong(part, PRIMARY, BACKUP));
                    assertEquals(partSize * gridCount(), cache.sizeLong(part, ALL)); // Primary + backups.
                }
            }
            else {
                keys = nearKeys(cache0, PUT_KEYS, 0);

                for (Integer key : keys)
                    cache0.put(key, String.valueOf(key));

                int partSize = 0;

                for (Integer key :keys) {
                    int keyPart = ignite(nodeIdx).affinity(DEFAULT_CACHE_NAME).partition(key);
                    if (keyPart == part)
                        partSize++;
                }

                if (hasNearCache()) {
                    assertEquals(0, cache0.localSize());
                    assertEquals(0, cache0.localSizeLong(part, ALL));
                    assertEquals(0, cache0.localSizeLong(part, NEAR));

                    for (int i = 0; i < gridCount(); i++) {
                        IgniteCache<Integer, String> cache = jcache(i);

                        assertEquals(0, cache.sizeLong(part, NEAR));
                        assertEquals(partSize, cache.sizeLong(part, BACKUP));
                        assertEquals(partSize * 2, cache.sizeLong(part, PRIMARY, BACKUP));
                        assertEquals(partSize * 2, cache.sizeLong(part, ALL)); // Primary + backups + near.
                    }
                }
                else {
                    assertEquals(0, cache0.localSize());
                    //assertEquals(partitionSize, cache0.localSizeLong(partition, ALL));
                    assertEquals(0, cache0.localSizeLong(part, NEAR));

                    for (int i = 0; i < gridCount(); i++) {
                        IgniteCache<Integer, String> cache = jcache(i);

                        assertEquals(0, cache.size(NEAR));
                        assertEquals(partSize, cache.sizeLong(part, BACKUP));
                        assertEquals(partSize * 2, cache.sizeLong(part, PRIMARY, BACKUP));
                        assertEquals(partSize * 2, cache.sizeLong(part, ALL)); // Primary + backups.
                    }
                }

                assertEquals(0, cache0.localSize(BACKUP));
                assertEquals(0, cache0.localSize(PRIMARY));
            }

            checkPrimarySize(PUT_KEYS);

            Affinity<Integer> aff = ignite(0).affinity(DEFAULT_CACHE_NAME);

            for (int i = 0; i < gridCount(); i++) {
                if (i == nodeIdx)
                    continue;

                ClusterNode node = ignite(i).cluster().localNode();

                int primary = 0;
                int backups = 0;

                for (Integer key : keys) {
                    if (aff.isPrimary(node, key) && aff.partition(key) == part)
                        primary++;
                    else if (aff.isBackup(node, key) && aff.partition(key) == part)
                        backups++;
                }

                IgniteCache<Integer, String> cache = jcache(i);

                assertEquals(primary, cache.localSizeLong(part, PRIMARY));
                assertEquals(backups, cache.localSizeLong(part, BACKUP));
                assertEquals(primary + backups, cache.localSizeLong(part, PRIMARY, BACKUP));
                assertEquals(primary + backups, cache.localSizeLong(part, BACKUP, PRIMARY));
                assertEquals(primary + backups, cache.localSizeLong(part, ALL));
            }

            cache0.remove(keys.get(0));

            keys.remove(0);

            checkPrimarySize(PUT_KEYS - 1);

            int primary = 0;
            int backups = 0;

            ClusterNode node = ignite(nodeIdx).cluster().localNode();

            for (Integer key : keys) {
                if (aff.isPrimary(node, key) && aff.partition(key) == part)
                    primary++;
                else if (aff.isBackup(node, key) && aff.partition(key) == part)
                    backups++;
            }

            if (cacheMode() == REPLICATED) {
                assertEquals(primary + backups, cache0.localSizeLong(part, ALL));
                assertEquals(primary, cache0.localSizeLong(part, PRIMARY));
                assertEquals(backups, cache0.localSizeLong(part, BACKUP));
            }
            else {
                if (hasNearCache())
                    assertEquals(0, cache0.localSizeLong(part, ALL));
                else
                    assertEquals(0, cache0.localSizeLong(part, ALL));
            }
        }
        finally {
            if (keys != null)
                cache0.removeAll(new HashSet<>(keys));
        }

        checkEmpty();
    }

    /**
     * Checks size is zero.
     */
    private void checkEmpty() {
        for (int i = 0; i < gridCount(); i++) {
            IgniteCache<Integer, String> cache = jcache(i);

            assertEquals(0, cache.localSize());

            assertEquals(0, cache.size());

            for (CachePeekMode peekMode : CachePeekMode.values()) {
                assertEquals(0, cache.localSize(peekMode));

                assertEquals(0, cache.size(peekMode));
            }
        }

        checkPrimarySize(0);
    }

    /**
     * @param nodeIdx Node index.
     * @return Tuple with primary and backup keys.
     */
    private T2<List<Integer>, List<Integer>> swapKeys(int nodeIdx) {
// TODO: GG-11148.
//        SwapSpaceSpi swap = ignite(nodeIdx).configuration().getSwapSpaceSpi();
//
//        IgniteSpiCloseableIterator<KeyCacheObject> it = swap.keyIterator(SPACE_NAME, null);
        IgniteSpiCloseableIterator<KeyCacheObject> it = new GridEmptyCloseableIterator<>();

        assertNotNull(it);

        Affinity aff = ignite(nodeIdx).affinity(DEFAULT_CACHE_NAME);

        ClusterNode node = ignite(nodeIdx).cluster().localNode();

        List<Integer> primary = new ArrayList<>();
        List<Integer> backups = new ArrayList<>();

        CacheObjectContext coctx = ((IgniteEx)ignite(nodeIdx)).context().cache().internalCache(DEFAULT_CACHE_NAME)
            .context().cacheObjectContext();

        while (it.hasNext()) {
            Integer key = it.next().value(coctx, false);

            if (aff.isPrimary(node, key))
                primary.add(key);
            else {
                assertTrue(aff.isBackup(node, key));

                backups.add(key);
            }
        }

        return new T2<>(primary, backups);
    }

    /**
     * @param nodeIdx Node index.
     * @return Tuple with number of primary and backup keys.
     */
    private T2<Integer, Integer> swapKeysCount(int nodeIdx) {
        T2<List<Integer>, List<Integer>> keys = swapKeys(nodeIdx);

        return new T2<>(keys.get1().size(), keys.get2().size());
    }

    /**
     * @param nodeIdx Node index.
     * @return Tuple with primary and backup keys.
     */
    private T2<List<Integer>, List<Integer>> offheapKeys(int nodeIdx) {
        GridCacheAdapter<Integer, String> internalCache =
            ((IgniteKernal)ignite(nodeIdx)).context().cache().internalCache(DEFAULT_CACHE_NAME);

// TODO GG-11148.
        Iterator<Map.Entry<Integer, String>> offheapIt = Collections.EMPTY_MAP.entrySet().iterator();
//        if (internalCache.context().isNear())
//            offheapIt = internalCache.context().near().dht().context().swap().lazyOffHeapIterator(false);
//        else
//            offheapIt = internalCache.context().swap().lazyOffHeapIterator(false);

        Affinity aff = ignite(nodeIdx).affinity(DEFAULT_CACHE_NAME);

        ClusterNode node = ignite(nodeIdx).cluster().localNode();

        List<Integer> primary = new ArrayList<>();
        List<Integer> backups = new ArrayList<>();

        while (offheapIt.hasNext()) {
            Map.Entry<Integer, String> e = offheapIt.next();

            if (aff.isPrimary(node, e.getKey()))
                primary.add(e.getKey());
            else {
                assertTrue(aff.isBackup(node, e.getKey()));

                backups.add(e.getKey());
            }
        }

        return new T2<>(primary, backups);
    }

    /**
     * @param nodeIdx Node index.
     * @return Tuple with number of primary and backup keys.
     */
    private T2<Integer, Integer> offheapKeysCount(int nodeIdx) {
        T2<List<Integer>, List<Integer>> keys = offheapKeys(nodeIdx);

        return new T2<>(keys.get1().size(), keys.get2().size());
    }

    /**
     * @param nodeIdx Node index.
     * @param part Cache partition.
     * @return Tuple with number of primary and backup keys (one or both will be zero).
     */
    private T2<Integer, Integer> offheapKeysCount(int nodeIdx, int part) throws IgniteCheckedException {
        GridCacheContext ctx = ((IgniteEx)ignite(nodeIdx)).context().cache().internalCache(DEFAULT_CACHE_NAME).context();
        // Swap and offheap are disabled for near cache.
        IgniteCacheOffheapManager offheapManager = ctx.isNear() ? ctx.near().dht().context().offheap() : ctx.offheap();
        //First count entries...
        int cnt = (int)offheapManager.cacheEntriesCount(ctx.cacheId(), part);

        GridCacheAffinityManager aff = ctx.affinity();
        AffinityTopologyVersion topVer = aff.affinityTopologyVersion();

        //And then find out whether they are primary or backup ones.
        int primaryCnt = 0;
        int backupCnt = 0;
        if (aff.primaryByPartition(ctx.localNode(), part, topVer))
            primaryCnt = cnt;
        else if (aff.backupByPartition(ctx.localNode(), part, topVer))
            backupCnt = cnt;
        return new T2<>(primaryCnt, backupCnt);
    }

    /**
     * @param nodeIdx Node index.
     * @throws Exception If failed.
     */
    private void checkSizeStorageFilter(int nodeIdx) throws Exception {
        if (true) // TODO GG-11148.
            return;

        IgniteCache<Integer, String> cache0 = jcache(nodeIdx);

        List<Integer> primaryKeys = primaryKeys(cache0, 100, 10_000);
        List<Integer> backupKeys = backupKeys(cache0, 100, 10_000);

        try {
            final String val = "test_value";

            for (int i = 0; i < 100; i++) {
                cache0.put(primaryKeys.get(i), val);
                cache0.put(backupKeys.get(i), val);
            }

            int totalKeys = 200;

            T2<Integer, Integer> swapKeys = swapKeysCount(nodeIdx);

            assertTrue(swapKeys.get1() > 0);
            assertTrue(swapKeys.get2() > 0);

            T2<Integer, Integer> offheapKeys = offheapKeysCount(nodeIdx);

            assertTrue(offheapKeys.get1() > 0);
            assertTrue(offheapKeys.get2() > 0);

            int totalSwap = swapKeys.get1() + swapKeys.get2();
            int totalOffheap = offheapKeys.get1() + offheapKeys.get2();

            log.info("Local keys [total=" + totalKeys + ", offheap=" + offheapKeys + ", swap=" + swapKeys + ']');

            assertTrue(totalSwap + totalOffheap < totalKeys);

            assertEquals(primaryKeys.size(), cache0.localSize());
            assertEquals(totalKeys, cache0.localSize(ALL));
            assertEquals(totalOffheap, cache0.localSize(PRIMARY, BACKUP, NEAR, OFFHEAP));
            assertEquals(totalSwap, cache0.localSize(PRIMARY, BACKUP, NEAR));
            assertEquals(totalKeys - (totalOffheap + totalSwap), cache0.localSize(PRIMARY, BACKUP, NEAR, ONHEAP));
            assertEquals(totalKeys, cache0.localSize(PRIMARY, BACKUP, NEAR, OFFHEAP, ONHEAP));

            assertEquals(swapKeys.get1(), (Integer)cache0.localSize(PRIMARY));
            assertEquals(swapKeys.get2(), (Integer)cache0.localSize(BACKUP));

            assertEquals(offheapKeys.get1(), (Integer)cache0.localSize(OFFHEAP, PRIMARY));
            assertEquals(offheapKeys.get2(), (Integer)cache0.localSize(OFFHEAP, BACKUP));

            assertEquals(swapKeys.get1() + offheapKeys.get1(), cache0.localSize(OFFHEAP, PRIMARY));
            assertEquals(swapKeys.get2() + offheapKeys.get2(), cache0.localSize(OFFHEAP, BACKUP));

            assertEquals(totalSwap + totalOffheap, cache0.localSize(PRIMARY, BACKUP, NEAR, OFFHEAP));

            int globalSwapPrimary = 0;
            int globalSwapBackup = 0;

            int globalOffheapPrimary = 0;
            int globalOffheapBackup = 0;

            for (int i = 0; i < gridCount(); i++) {
                T2<Integer, Integer> swap = swapKeysCount(i);

                globalSwapPrimary += swap.get1();
                globalSwapBackup += swap.get2();

                T2<Integer, Integer> offheap = offheapKeysCount(i);

                globalOffheapPrimary += offheap.get1();
                globalOffheapBackup += offheap.get2();
            }

            int backups;

            if (cacheMode() == PARTITIONED)
                backups = 1;
            else // REPLICATED.
                backups = gridCount() - 1;

            int globalTotal = totalKeys + totalKeys * backups;
            int globalTotalSwap = globalSwapPrimary + globalSwapBackup;
            int globalTotalOffheap = globalOffheapPrimary + globalOffheapBackup;

            log.info("Global keys [total=" + globalTotal +
                ", offheap=" + globalTotalOffheap +
                ", swap=" + globalTotalSwap + ']');

            for (int i = 0; i < gridCount(); i++) {
                IgniteCache<Integer, String> cache = jcache(i);

                assertEquals(totalKeys, cache.size(PRIMARY));
                assertEquals(globalTotal, cache.size(ALL));
                assertEquals(globalTotal, cache.size(PRIMARY, BACKUP, NEAR, ONHEAP, OFFHEAP));
                assertEquals(globalTotal, cache.size(ONHEAP, OFFHEAP, PRIMARY, BACKUP));

                assertEquals(globalTotalSwap, cache.size(PRIMARY, BACKUP, NEAR));
                assertEquals(globalSwapPrimary, cache.size(PRIMARY));
                assertEquals(globalSwapBackup, cache.size(BACKUP));

                assertEquals(globalTotalOffheap, cache.size(PRIMARY, BACKUP, NEAR, OFFHEAP));
                assertEquals(globalOffheapPrimary, cache.size(OFFHEAP, PRIMARY));
                assertEquals(globalOffheapBackup, cache.size(OFFHEAP, BACKUP));

                assertEquals(globalTotalSwap + globalTotalOffheap, cache.size(PRIMARY, BACKUP, NEAR, OFFHEAP));
                assertEquals(globalSwapPrimary + globalOffheapPrimary, cache.size(OFFHEAP, PRIMARY));
                assertEquals(globalSwapBackup + globalOffheapBackup, cache.size(OFFHEAP, BACKUP));

                assertEquals(globalTotal - (globalTotalOffheap + globalTotalSwap), cache.size(PRIMARY, BACKUP, NEAR, ONHEAP));
            }
        }
        finally {
            cache0.removeAll(new HashSet<>(primaryKeys));
            cache0.removeAll(new HashSet<>(backupKeys));
        }

        checkEmpty();
    }

    /**
     * @param nodeIdx Node index.
     * @throws Exception If failed.
     */
    private void checkPartitionSizeStorageFilter(int nodeIdx) throws Exception {
        IgniteCache<Integer, String> cache0 = jcache(nodeIdx);

        int part = nodeIdx;

        List<Integer> primaryKeys = primaryKeys(cache0, 100, 10_000);
        List<Integer> backupKeys = backupKeys(cache0, 100, 10_000);

        try {
            final String val = "test_value";

            for (int i = 0; i < 100; i++) {
                cache0.put(primaryKeys.get(i), val);
                cache0.put(backupKeys.get(i), val);
            }

            int totalKeys = 200;

            T2<Integer, Integer> offheapKeys = offheapKeysCount(nodeIdx, part);

            int totalOffheap = offheapKeys.get1() + offheapKeys.get2();

            log.info("Local keys [total=" + totalKeys + ", offheap=" + offheapKeys + ']');

            assertTrue(totalOffheap < totalKeys);

            assertEquals(primaryKeys.size(), cache0.localSize());
            assertEquals(totalKeys, cache0.localSize(ALL));
            assertEquals(totalOffheap, cache0.localSizeLong(part, PRIMARY, BACKUP, NEAR, OFFHEAP));

            assertEquals((long)offheapKeys.get1(), cache0.localSizeLong(part, OFFHEAP, PRIMARY));
            assertEquals((long)offheapKeys.get2(), cache0.localSizeLong(part, OFFHEAP, BACKUP));

            int globalParitionSwapPrimary = 0;
            int globalPartSwapBackup = 0;

            int globalPartOffheapPrimary = 0;
            int globalPartOffheapBackup = 0;

            for (int i = 0; i < gridCount(); i++) {
                T2<Integer, Integer> offheap = offheapKeysCount(i, part);

                globalPartOffheapPrimary += offheap.get1();
                globalPartOffheapBackup += offheap.get2();
            }

            int backups;

            if (cacheMode() == PARTITIONED)
                backups = 1;
            else // REPLICATED.
                backups = gridCount() - 1;

            int globalTotal = totalKeys + totalKeys * backups;
            int globalPartTotalSwap = globalParitionSwapPrimary + globalPartSwapBackup;
            int globalPartTotalOffheap = globalPartOffheapPrimary + globalPartOffheapBackup;

            log.info("Global keys [total=" + globalTotal +
                    ", offheap=" + globalPartTotalOffheap +
                    ", swap=" + globalPartTotalSwap + ']');

            for (int i = 0; i < gridCount(); i++) {
                IgniteCache<Integer, String> cache = jcache(i);

                assertEquals(totalKeys, cache.size(PRIMARY));
                assertEquals(globalTotal, cache.size(ALL));
                assertEquals(globalTotal, cache.size(PRIMARY, BACKUP, NEAR, ONHEAP, OFFHEAP));
                assertEquals(globalTotal, cache.size(ONHEAP, OFFHEAP, PRIMARY, BACKUP));

                assertEquals(globalPartTotalSwap, cache.sizeLong(part, PRIMARY, BACKUP, NEAR));
                assertEquals(globalParitionSwapPrimary, cache.sizeLong(part, PRIMARY));
                assertEquals(globalPartSwapBackup, cache.sizeLong(part, BACKUP));

                assertEquals(globalPartTotalOffheap, cache.sizeLong(part, PRIMARY, BACKUP, NEAR, OFFHEAP));
                assertEquals(globalPartOffheapPrimary, cache.sizeLong(part, OFFHEAP, PRIMARY));
                assertEquals(globalPartOffheapBackup, cache.sizeLong(part, OFFHEAP, BACKUP));

                assertEquals(globalPartTotalSwap + globalPartTotalOffheap, cache.sizeLong(part, PRIMARY, BACKUP, NEAR, OFFHEAP));
                assertEquals(globalParitionSwapPrimary + globalPartOffheapPrimary, cache.sizeLong(part, OFFHEAP, PRIMARY));
                assertEquals(globalPartSwapBackup + globalPartOffheapBackup, cache.sizeLong(part, OFFHEAP, BACKUP));
            }
        }
        finally {
            cache0.removeAll(new HashSet<>(primaryKeys));
            cache0.removeAll(new HashSet<>(backupKeys));
        }

        checkEmpty();
    }

    /**
     * @param exp Expected size.
     */
    private void checkPrimarySize(int exp) {
        int size = 0;

        for (int i = 0; i < gridCount(); i++) {
            IgniteCache<Integer, String> cache = jcache(i);

            assertEquals(exp, cache.size(PRIMARY));

            size += cache.localSize(PRIMARY);

            assertEquals(exp, (int)cache.sizeAsync(PRIMARY).get());
        }

        assertEquals(exp, size);
    }

    /**
     * @param nodeIdx Node index.
     * @throws Exception If failed.
     */
    private void checkLocalEntriesStorageFilter(int nodeIdx) throws Exception {
        if (true) // TODO GG-11148.
            return;

        IgniteCache<Integer, String> cache0 = jcache(nodeIdx);

        List<Integer> primaryKeys = primaryKeys(cache0, 100, 10_000);
        List<Integer> backupKeys = backupKeys(cache0, 100, 10_000);

        try {
            final String val = "test_value-";

            for (int i = 0; i < 100; i++) {
                cache0.put(primaryKeys.get(i), val + primaryKeys.get(i));
                cache0.put(backupKeys.get(i), val + backupKeys.get(i));
            }

            int totalKeys = 200;

            T2<List<Integer>, List<Integer>> swapKeys = swapKeys(nodeIdx);

            assertTrue(!swapKeys.get1().isEmpty());
            assertTrue(!swapKeys.get2().isEmpty());

            T2<List<Integer>, List<Integer>> offheapKeys = offheapKeys(nodeIdx);

            assertTrue(!offheapKeys.get1().isEmpty());
            assertTrue(!offheapKeys.get2().isEmpty());

            List<Integer> swap = new ArrayList<>();

            swap.addAll(swapKeys.get1());
            swap.addAll(swapKeys.get2());

            assertFalse(swap.isEmpty());

            List<Integer> offheap = new ArrayList<>();

            offheap.addAll(offheapKeys.get1());
            offheap.addAll(offheapKeys.get2());

            assertFalse(offheap.isEmpty());

            List<Integer> heap = new ArrayList<>();

            heap.addAll(primaryKeys);
            heap.addAll(backupKeys);

            heap.removeAll(swap);
            heap.removeAll(offheap);

            log.info("Keys [total=" + totalKeys +
                ", offheap=" + offheap.size() +
                ", swap=" + swap.size() + ']');

            assertFalse(heap.isEmpty());

            checkLocalEntries(cache0.localEntries(), val, primaryKeys, backupKeys);
            checkLocalEntries(cache0.localEntries(ALL), val, primaryKeys, backupKeys);
            checkLocalEntries(cache0.localEntries(ONHEAP, OFFHEAP), val, primaryKeys, backupKeys);

            checkLocalEntries(cache0.localEntries(), val, swap);
            checkLocalEntries(cache0.localEntries(OFFHEAP), val, offheap);
            checkLocalEntries(cache0.localEntries(ONHEAP), val, heap);

            checkLocalEntries(cache0.localEntries(OFFHEAP), val, swap, offheap);
            checkLocalEntries(cache0.localEntries(ONHEAP), val, swap, heap);

            checkLocalEntries(cache0.localEntries(PRIMARY), val, swapKeys.get1());
            checkLocalEntries(cache0.localEntries(BACKUP), val, swapKeys.get2());
            checkLocalEntries(cache0.localEntries(OFFHEAP, PRIMARY), val, offheapKeys.get1());
            checkLocalEntries(cache0.localEntries(OFFHEAP, BACKUP), val, offheapKeys.get2());

            checkLocalEntries(cache0.localEntries(OFFHEAP, PRIMARY), val, swapKeys.get1(), offheapKeys.get1());
            checkLocalEntries(cache0.localEntries(OFFHEAP, BACKUP), val, swapKeys.get2(), offheapKeys.get2());
            checkLocalEntries(cache0.localEntries(OFFHEAP, PRIMARY, BACKUP), val, swap, offheap);
        }
        finally {
            cache0.removeAll(new HashSet<>(primaryKeys));
            cache0.removeAll(new HashSet<>(backupKeys));
        }
    }

    /**
     * @param nodeIdx Node index.
     * @throws Exception If failed.
     */
    private void checkLocalEntriesAffinityFilter(int nodeIdx) throws Exception {
        IgniteCache<Integer, String> cache0 = jcache(nodeIdx);

        final int PUT_KEYS = 10;

        List<Integer> primaryKeys = null;
        List<Integer> backupKeys = null;
        List<Integer> nearKeys = null;

        try {
            primaryKeys = primaryKeys(cache0, PUT_KEYS, 0);
            backupKeys = backupKeys(cache0, PUT_KEYS, 0);

            for (Integer key : primaryKeys)
                cache0.put(key, String.valueOf(key));
            for (Integer key : backupKeys)
                cache0.put(key, String.valueOf(key));

            nearKeys = cacheMode() == PARTITIONED ? nearKeys(cache0, PUT_KEYS, 0) : Collections.<Integer>emptyList();

            for (Integer key : nearKeys)
                cache0.put(key, String.valueOf(key));

            log.info("Keys [near=" + nearKeys + ", primary=" + primaryKeys + ", backup=" + backupKeys + ']');

            if (hasNearCache()) {
                checkLocalEntries(cache0.localEntries(), nearKeys, primaryKeys, backupKeys);
                checkLocalEntries(cache0.localEntries(ALL), nearKeys, primaryKeys, backupKeys);
                checkLocalEntries(cache0.localEntries(NEAR), nearKeys);
                checkLocalEntries(cache0.localEntries(PRIMARY, BACKUP, NEAR), nearKeys, primaryKeys, backupKeys);
                checkLocalEntries(cache0.localEntries(NEAR, PRIMARY), nearKeys, primaryKeys);
                checkLocalEntries(cache0.localEntries(NEAR, BACKUP), nearKeys, backupKeys);
            }
            else {
                checkLocalEntries(cache0.localEntries(), primaryKeys, backupKeys);
                checkLocalEntries(cache0.localEntries(ALL), primaryKeys, backupKeys);
                checkLocalEntries(cache0.localEntries(NEAR));
                checkLocalEntries(cache0.localEntries(NEAR, PRIMARY), primaryKeys);
                checkLocalEntries(cache0.localEntries(NEAR, BACKUP), backupKeys);
                checkLocalEntries(cache0.localEntries(PRIMARY, BACKUP, NEAR), primaryKeys, backupKeys);
            }

            checkLocalEntries(cache0.localEntries(PRIMARY), primaryKeys);
            checkLocalEntries(cache0.localEntries(BACKUP), backupKeys);
            checkLocalEntries(cache0.localEntries(PRIMARY, BACKUP), primaryKeys, backupKeys);
        }
        finally {
            if (primaryKeys != null)
                cache0.removeAll(new HashSet<>(primaryKeys));

            if (backupKeys != null)
                cache0.removeAll(new HashSet<>(backupKeys));

            if (nearKeys != null)
                cache0.removeAll(new HashSet<>(nearKeys));
        }
    }

    /**
     * @param entries Entries.
     * @param exp Expected entries.
     */
    private void checkLocalEntries(Iterable<Cache.Entry<Integer, String>> entries, Collection<Integer>... exp) {
        checkLocalEntries(entries, "", exp);
    }

    /**
     * @param entries Entries.
     * @param expVal Expected value.
     * @param exp Expected keys.
     */
    private void checkLocalEntries(Iterable<Cache.Entry<Integer, String>> entries,
        String expVal,
        Collection<Integer>... exp) {
        Set<Integer> allExp = new HashSet<>();

        for (Collection<Integer> col : exp)
            assertTrue(allExp.addAll(col));

        for (Cache.Entry<Integer, String> e : entries) {
            assertNotNull(e.getKey());
            assertNotNull(e.getValue());
            assertEquals(expVal + e.getKey(), e.getValue());

            assertTrue("Unexpected entry: " + e, allExp.remove(e.getKey()));
        }

        assertTrue("Expected entries not found: " + allExp, allExp.isEmpty());
    }
}
