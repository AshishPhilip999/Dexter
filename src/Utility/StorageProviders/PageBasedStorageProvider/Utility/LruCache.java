package Utility.StorageProviders.PageBasedStorageProvider.Utility;

import java.util.LinkedHashMap;
import java.util.Map;

/*
Fixed-size cache that drops the least recently used entry when full.
Used for data pages and index pages, so memory stays bounded no matter how
large the file is. Writes are written to disk immediately (write-through), so
an evicted entry never needs to be saved.

Thread-safe: every method is synchronized. That matters even for get(),
because looking an entry up moves it to the most-recently-used position, and
several threads may read through the cache at once.
*/
public class LruCache<K, V> {
    private final int capacity;
    private final LinkedHashMap<K, V> map;

    public LruCache(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Cache capacity must be at least 1");
        }
        this.capacity = capacity;
        this.map = new LinkedHashMap<K, V>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return this.size() > LruCache.this.capacity;
            }
        };
    }

    public int getCapacity() {
        return this.capacity;
    }

    public synchronized V get(K key) {
        return this.map.get(key);
    }

    public synchronized void put(K key, V value) {
        this.map.put(key, value);
    }

    public synchronized void clear() {
        this.map.clear();
    }

    public synchronized int size() {
        return this.map.size();
    }
}
