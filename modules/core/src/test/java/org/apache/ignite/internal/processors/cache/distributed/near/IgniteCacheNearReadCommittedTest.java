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
import org.apache.ignite.configuration.*;
import org.apache.ignite.internal.processors.cache.*;
import org.apache.ignite.transactions.*;

import static org.apache.ignite.cache.CacheMode.*;
import static org.apache.ignite.transactions.TransactionConcurrency.*;
import static org.apache.ignite.transactions.TransactionIsolation.*;

/**
 *
 */
@SuppressWarnings("RedundantMethodOverride")
public class IgniteCacheNearReadCommittedTest extends GridCacheAbstractSelfTest {
    /** {@inheritDoc} */
    @Override protected int gridCount() {
        return 2;
    }

    /** {@inheritDoc} */
    @Override protected NearCacheConfiguration nearConfiguration() {
        return new NearCacheConfiguration();
    }

    /** {@inheritDoc} */
    @Override protected CacheMode cacheMode() {
        return PARTITIONED;
    }

    /**
     * @throws Exception If failed.
     */
    public void testReadCommittedCacheCleanup() throws Exception {
        IgniteCache<Integer, Integer> cache = ignite(0).jcache(null);

        Integer key = backupKey(cache);

        cache.put(key, key);

        assertEquals(1, cache.localSize());

        try (Transaction tx = ignite(0).transactions().txStart(PESSIMISTIC, READ_COMMITTED)) {
            assertEquals(key, cache.get(key));

            tx.commit();
        }

        ignite(1).jcache(null).remove(key); // Remove from primary node.

        assertEquals(0, cache.localSize());
    }
}
