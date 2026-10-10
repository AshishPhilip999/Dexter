package PBStorageTests.Concurrency;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;

import Utility.StorageProviders.PageBasedStorageProvider.Utility.LruCache;

public class LruCacheTests {

    @Test
    public void evictsTheLeastRecentlyUsedEntry() {
        LruCache<Integer, String> cache = new LruCache<>(3);
        cache.put(1, "a");
        cache.put(2, "b");
        cache.put(3, "c");
        cache.get(1);            // 1 is now the most recently used
        cache.put(4, "d");       // evicts 2, the least recently used

        assertEquals(3, cache.size());
        assertEquals("a", cache.get(1));
        assertNull(cache.get(2));
        assertEquals("c", cache.get(3));
        assertEquals("d", cache.get(4));
    }

    @Test
    public void capacityMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new LruCache<Integer, Integer>(0));
    }

    @Test
    public void manyThreadsUsingOneCacheStayConsistent() throws Exception {
        int capacity = 16;
        LruCache<Integer, Integer> cache = new LruCache<>(capacity);
        Queue<String> failures = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();

        for (int t = 0; t < 8; t++) {
            final int seed = t;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    Random random = new Random(seed);
                    for (int i = 0; i < 300_000; i++) {
                        int key = random.nextInt(64);
                        if (random.nextBoolean()) {
                            cache.put(key, key * 2);
                        } else {
                            Integer value = cache.get(key);
                            if (value != null && value != key * 2) {
                                failures.add("key " + key + " returned " + value);
                            }
                        }
                    }
                } catch (Throwable e) {
                    failures.add("threw " + e);
                }
            });
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }

        start.countDown();
        long deadline = System.currentTimeMillis() + 60_000;
        for (Thread thread : threads) {
            thread.join(Math.max(1, deadline - System.currentTimeMillis()));
            if (thread.isAlive()) {
                failures.add(thread.getName() + " is stuck");
            }
        }

        assertTrue(failures.isEmpty(), failures.toString());
        assertTrue(cache.size() <= capacity, "size " + cache.size() + " exceeds capacity " + capacity);
    }
}
