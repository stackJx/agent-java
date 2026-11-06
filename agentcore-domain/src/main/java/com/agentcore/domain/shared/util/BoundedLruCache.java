package com.agentcore.domain.shared.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 有容量上限的缓存：条目超过上限时，丢掉最久没有被访问过的那个（LRU）。线程安全：所有方法共用一把锁。
 *
 * <p>用来存放"按会话 ID 区分"的内存状态（工具状态、会话缓存、记住的审批规则……），
 * 避免长期运行的服务里会话越来越多、内存只增不减。被丢掉的条目需要时会重新创建或从存储重新加载。
 */
public final class BoundedLruCache<K, V> {

    /** LinkedHashMap 的默认初始容量和负载因子；第三个参数 true 表示按访问顺序排列，最久没访问的排在最前。 */
    private static final int INITIAL_CAPACITY = 16;
    private static final float LOAD_FACTOR = 0.75f;
    private static final boolean ORDER_BY_ACCESS = true;

    private final int capacity;
    private final Map<K, V> entries;

    public BoundedLruCache(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1, got " + capacity);
        }
        this.capacity = capacity;
        this.entries = new LinkedHashMap<>(INITIAL_CAPACITY, LOAD_FACTOR, ORDER_BY_ACCESS) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > BoundedLruCache.this.capacity;
            }
        };
    }

    public synchronized V get(K key) {
        return entries.get(key);
    }

    public synchronized void put(K key, V value) {
        entries.put(key, value);
    }

    /** 没有这个键时放入 value 并返回 null；已经有了就不覆盖，返回已有的值。 */
    public synchronized V putIfAbsent(K key, V value) {
        return entries.putIfAbsent(key, value);
    }

    /** 取出已有的值；没有就用 create 创建一个放进去再返回。 */
    public synchronized V computeIfAbsent(K key, Function<? super K, ? extends V> create) {
        return entries.computeIfAbsent(key, create);
    }

    public synchronized void remove(K key) {
        entries.remove(key);
    }

    public synchronized int size() {
        return entries.size();
    }

    public int capacity() {
        return capacity;
    }
}
