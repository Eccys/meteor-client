/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.misc;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

public class ValueComparableMap<K extends Comparable<K>, V> extends TreeMap<K, V> {
    private final transient Map<K, V> valueMap;

    public ValueComparableMap(final Comparator<? super V> partialValueComparator) {
        this(partialValueComparator, new HashMap<>());
    }

    private ValueComparableMap(Comparator<? super V> partialValueComparator, HashMap<K, V> valueMap) {
        super((k1, k2) -> {
            int cmp = partialValueComparator.compare(valueMap.get(k1), valueMap.get(k2));
            return cmp != 0 ? cmp : k1.compareTo(k2);
        });

        this.valueMap = valueMap;
    }

    @Override
    public V put(K k, V v) {
        V previous = valueMap.containsKey(k) ? super.remove(k) : null;
        valueMap.put(k, v);
        super.put(k, v);
        return previous;
    }

    @Override
    public V get(Object key) {
        return valueMap.get(key);
    }

    @Override
    public V remove(Object key) {
        if (!valueMap.containsKey(key)) return null;
        V removed = super.remove(key);
        valueMap.remove(key);
        return removed;
    }

    @Override
    public void clear() {
        super.clear();
        valueMap.clear();
    }

    @Override
    public boolean containsKey(Object key) {
        return valueMap.containsKey(key);
    }

    @Override
    public V getOrDefault(Object key, V defaultValue) {
        return valueMap.getOrDefault(key, defaultValue);
    }
}
