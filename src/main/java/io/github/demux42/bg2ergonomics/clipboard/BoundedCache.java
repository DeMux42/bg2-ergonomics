package io.github.demux42.bg2ergonomics.clipboard;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A map with a hard size limit that hands every value it lets go of to {@code release}: on overflow (least
 * recently used first), on replacement, when its key is no longer wanted, and on clear. Nothing it held outlives
 * it without being released, which is what keeps clipboard thumbnails from piling up on the GPU.
 */
public final class BoundedCache<K, V> {
    private final int max;
    private final Consumer<V> release;
    private final LinkedHashMap<K, V> map = new LinkedHashMap<>(16, 0.75f, true); //Access order: get() marks recently used

    public BoundedCache(int max, Consumer<V> release) {
        this.max = max;
        this.release = release;
    }

    public V get(K key) {
        return map.get(key);
    }

    public boolean contains(K key) {
        return map.containsKey(key); //Does not count as a use
    }

    public int size() {
        return map.size();
    }

    public void put(K key, V value) {
        V old = map.put(key, value);
        if (old != null && old != value) release.accept(old);
        Iterator<Map.Entry<K, V>> eldest = map.entrySet().iterator();
        while (map.size() > max) {
            release.accept(eldest.next().getValue());
            eldest.remove();
        }
    }

    /**
     * Releases everything whose key is not in {@code keys}.
     */
    public void retain(Collection<K> keys) {
        map.entrySet().removeIf(entry -> {
            if (keys.contains(entry.getKey())) return false;
            release.accept(entry.getValue());
            return true;
        });
    }

    public void clear() {
        map.values().forEach(release);
        map.clear();
    }
}
