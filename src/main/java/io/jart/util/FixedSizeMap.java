// BSD 3-Clause License
//
// Copyright (c) 2020, Scott Petersen
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//
// 1. Redistributions of source code must retain the above copyright notice, this
//    list of conditions and the following disclaimer.
//
// 2. Redistributions in binary form must reproduce the above copyright notice,
//    this list of conditions and the following disclaimer in the documentation
//    and/or other materials provided with the distribution.
//
// 3. Neither the name of the copyright holder nor the names of its
//    contributors may be used to endorse or promote products derived from
//    this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
// DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
// FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
// DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
// SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
// CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
// OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package io.jart.util;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.ToLongBiFunction;

/**
 * Implements a fixed size cache.
 * 
 * entries will be evicted in lru order to ensure a maximum "weight"
 * and entries will also evicted possibly satisfy puts --
 * put will only replace an "older" entry, but it may not be the global lru
 * threadsafe but all successful operations are "writes" including "get" as it
 * updates lru -- so maybe use striping for high concurrency
 *
 * @param <K> the key type
 * @param <V> the value type
 */
// 
public class FixedSizeMap<K, V> implements Map<K, V> {
	
	/**
	 * Cache entry.
	 * Immutable key, mutable value.
	 *
	 * @param <K> the key type
	 * @param <V> the value type
	 */
	private static class Entry<K, V> {
		
		/**
		 * Singleton to represent null values in the cache.
		 */
		public static class NullValue {};
		public static final Object nullValue = new NullValue();
		
		/**
		 * Singleton to represent a tombstone in the cache.
		 */
		public static class TombstoneValue {};
		public static final Object tombstoneValue = new TombstoneValue();

		public final int keyHash;
		public final K key;
		private final AtomicReference<V> _value;
		public final AtomicLong timestamp;
		
		/**
		 * Instantiates a new entry.
		 *
		 * @param keyHash the key hash
		 * @param key the key
		 * @param value the value
		 * @param weight the weight
		 * @param timestamp the timestamp
		 */
		public Entry(int keyHash, K key, V value, long weight, long timestamp) {
			this.keyHash = keyHash;
			this.key = key;
			this._value = new AtomicReference<V>(value);
			this.timestamp = new AtomicLong(timestamp);
		}

		/**
		 * Gets the value.
		 *
		 * @return the value
		 */
		public V getValue() {
			return _value.get();
		}
		
		/**
		 * Get-and-set but don't ever change a tombstone.
		 *
		 * @param newValue the new value
		 * @return the previous value (if tombstone, set didn't succeed)
		 */
		public V getAndSetValue(V newValue) {
			for(;;) {
				V oldValue = _value.get();
				
				if(oldValue == tombstoneValue)
					return oldValue;
				if(_value.compareAndSet(oldValue, newValue))
					return oldValue;
			}
		}

		/**
		 * Cas but always fail on existing tombstone.
		 *
		 * @param expected the expected
		 * @param newValue the new value
		 * @return true, if successful
		 */
		public boolean compareAndSetValue(V expected, V newValue) {
			for(;;) {
				V oldValue = _value.get();
				
				if(oldValue != expected || oldValue == tombstoneValue)
					return false;
				if(_value.compareAndSet(expected, newValue))
					return true;
			}			
		}
}
	private static final Entry<?, ?> invalidEntry = new Entry<Object, Object>(0, null, Entry.tombstoneValue, 0, 0);
	
	private final AtomicReferenceArray<Entry<K, V>> entries;
	private final int neighborhood;
	private final AtomicLong nextTimestamp = new AtomicLong();
	private final AtomicInteger size = new AtomicInteger();
	private final AtomicLong addedWeight = new AtomicLong();
	private final AtomicLong removedWeight = new AtomicLong();
	private final long maxWeight;
	private final ToLongBiFunction<K, V> weigher;
	private final Executor exec;
	private int evictIndex = 0;

	/**
	 * Instantiates a new fixed size map.
	 *
	 * @param maxEntries the max entries
	 * @param maxWeight the max weight
	 * @param weigher the weigher
	 * @param neighborhood the neighborhood size
	 * @param exec the Executor to run cleanup on
	 */
	@SuppressWarnings("unchecked")
	public FixedSizeMap(int maxEntries, long maxWeight, ToLongBiFunction<K, V> weigher, int neighborhood, Executor exec) {
		this.entries = new AtomicReferenceArray<Entry<K, V>>(maxEntries);
		this.maxWeight = maxWeight;
		this.weigher = weigher;
		this.neighborhood = Math.min(maxEntries, neighborhood);
		this.exec = (exec != null) ? exec : ForkJoinPool.commonPool();
		for(int i = 0; i < maxEntries; i++)
			entries.set(i, (Entry<K, V>) invalidEntry);
	}
	
	/**
	 * Instantiates a new fixed size map with default Executor.
	 *
	 * @param maxEntries the max entries
	 * @param maxWeight the max weight
	 * @param weigher the weigher
	 * @param neighborhood the neighborhood
	 */
	public FixedSizeMap(int maxEntries, long maxWeight, ToLongBiFunction<K, V> weigher, int neighborhood) {
		this(maxEntries, maxWeight, weigher, neighborhood, null);
	}
	
	/**
	 * Instantiates a new fixed size map with default neighborhood.
	 *
	 * @param maxEntries the max entries
	 * @param maxWeight the max weight
	 * @param weigher the weigher
	 * @param exec the Executor to run cleanup on
	 */
	public FixedSizeMap(int maxEntries, long maxWeight, ToLongBiFunction<K, V> weigher, Executor exec) {
		this(maxEntries, maxWeight, weigher, 44, exec);
	}
	
	/**
	 * Instantiates a new fixed size map with default neighborhood, Executor.
	 *
	 * @param maxEntries the max entries
	 * @param maxWeight the max weight
	 * @param weigher the weigher
	 */
	public FixedSizeMap(int maxEntries, long maxWeight, ToLongBiFunction<K, V> weigher) {
		this(maxEntries, maxWeight, weigher, null);
	}
	
	/**
	 * Get current size of map.
	 *
	 * @return the int
	 */
	@Override
	public int size() {
		return size.get();
	}
	
	/**
	 * Get the current weight of the map.
	 *
	 * @return the long
	 */
	public long weight() {
		return Math.max(0, addedWeight.get() - removedWeight.get());
	}
	
	/**
	 * Gets the value at the given key.
	 *
	 * @param key the key
	 * @return the v
	 */
	@Override
	public V get(Object key) {
		if(key == null)
			throw new IllegalArgumentException("key must be non-null");

		int keyHash = key.hashCode();
		int index = Math.abs(keyHash);
		int len = entries.length();
		
		for(int i = 0; i < neighborhood; i++) {
			Entry<K, V> entry = entries.get((index + i) % len);
			
			if(keyHash == entry.keyHash && key.equals(entry.key)) {
				entry.timestamp.getAndAccumulate(nextTimestamp.getAndIncrement(), Long::max);

				V value = entry.getValue();
				
				return (value == Entry.tombstoneValue) ? null : value;
			}
		}
		return null;
	}
	
	/**
	 * Adds additional weight.
	 *
	 * @param delta the delta
	 */
	private void addWeight(long delta) {
		long newWeight = addedWeight.addAndGet(delta);
		
		if(newWeight > maxWeight && (newWeight - delta) <= maxWeight) {
			// execute a collection cycle if this put us over the max
			exec.execute(()->{
				// get under max weight
				int len = entries.length();
				long tsDelta = Math.max(16, len / 16);
				
				for(;;) {
					long weightToFree = weight() - maxWeight;
					int curSize = size.get();					

					if(Math.min(weightToFree, curSize) <= 0) {
						if(addedWeight.addAndGet(-this.removedWeight.getAndSet(0)) <= maxWeight)
							return; // got to acceptable weight
						else
							continue; // didn't -- resample weight and size
					}

					// estimate a timestamp threshold where any entries w/ ts <= get freed
					long weightPerEntry = Math.max(1, (weightToFree + maxWeight) / curSize);
					long entriesToFree = Math.min(len, weightToFree / weightPerEntry);
					long timestampThresh = nextTimestamp.get() - (entriesToFree + tsDelta) / 2;
					int freedEntries = 0;
					long freedWeight = 0;
					
					// do a 16th of the table at a time so we re-estimate threshold regularly
					for(int i = 0; i <= len / 16; i++) {
						int ii = evictIndex++ % len;
						Entry<K, V> entry = entries.get(ii);
						long timestamp = entry.timestamp.get();
						
						if(timestamp <= timestampThresh) {
							V value = entry.getValue();
							
							if(value != null && value != Entry.tombstoneValue &&
									entry.timestamp.compareAndSet(timestamp, 0) && entry.compareAndSetValue(value, null)) {
								freedEntries++;
								freedWeight += weigher.applyAsLong(entry.key, value);
							}
						}
					}
					size.addAndGet(-freedEntries);
					if(addedWeight.addAndGet(-(freedWeight + this.removedWeight.getAndSet(0))) <= maxWeight)
						return;
					// slow exponential increase in aggression
					tsDelta = Math.max(tsDelta, (tsDelta * 16) / 15);
				}
			});
		}
	}
	
	/**
	 * Try to replace value in the netry.
	 *
	 * @param entry the entry
	 * @param keyHash the key hash
	 * @param key the key
	 * @param value the value
	 * @return the v
	 */
	@SuppressWarnings("unchecked")
	private V tryReplaceValue(Entry<K, V> entry, int keyHash, K key, V value) {
		if(keyHash != entry.keyHash || !key.equals(entry.key))
			return null;
		
		entry.timestamp.getAndAccumulate(nextTimestamp.getAndIncrement(), Long::max);

		V oldValue = entry.getAndSetValue(value);
		
		if(oldValue != null) {
			if(oldValue == Entry.tombstoneValue)
				return null; // tombstone -- fail
			
			// replaced valid -- adjust
			size.decrementAndGet();
			removedWeight.addAndGet(weigher.applyAsLong(key, oldValue));
		}
		else
			oldValue = (V) Entry.nullValue; // successful null replacement

		return oldValue;
	}
	
	/**
	 * Try to overwrite entry.
	 *
	 * @param index the index
	 * @param timestamp the timestamp
	 * @param newEntry the new entry
	 * @return true, if successful
	 */
	@SuppressWarnings("unchecked")
	private boolean tryOverwriteEntry(int index, long timestamp, Entry<K, V> newEntry) {
		Entry<K, V> oldEntry = entries.get(index);
		V oldValue = oldEntry.getValue();
		
		if(oldEntry.timestamp.get() != timestamp ||
				(oldValue != Entry.tombstoneValue && !oldEntry.compareAndSetValue(oldValue, (V) Entry.tombstoneValue)))
			return false;
		
		if(oldValue != null && oldValue != Entry.tombstoneValue) { // replacing a valid entry
			size.decrementAndGet();
			removedWeight.addAndGet(weigher.applyAsLong(oldEntry.key, oldValue));
		}
		return entries.compareAndSet(index, oldEntry, newEntry);
	}
	
	/**
	 * Put value at given key.
	 *
	 * @param key the key
	 * @param value the value
	 * @return the v
	 */
	@Override
	public V put(K key, V value) {
		if(key == null)
			throw new IllegalArgumentException("key must be non-null");
		if(value == null)
			throw new IllegalArgumentException("value must be non-null");

		long weight = weigher.applyAsLong(key, value);
		
		size.incrementAndGet();
		addWeight(weight);
		
		int keyHash = key.hashCode();
		int len = entries.length();
		int index = Math.abs(keyHash) % len;
		// track the 4 oldest entries
		long oldT0 = Long.MAX_VALUE, oldT1 = Long.MAX_VALUE, oldT2 = Long.MAX_VALUE, oldT3 = Long.MAX_VALUE;
		int oldI0 = index, oldI1 = index, oldI2 = index, oldI3 = index;

		for(int i = 0; i < neighborhood; i++) {
			int ii = (index + i) % len;
			Entry<K, V> entry = entries.get(ii);
			
			V oldValue = tryReplaceValue(entry, keyHash, key, value);
			
			if(oldValue != null)
				return (oldValue != Entry.nullValue) ? oldValue : null;

			// bubble sort new timestamp in
			long entryTimestamp = entry.timestamp.get();
			long tsMn;
			
			tsMn = Math.min(oldT3, entryTimestamp);
			oldI3 = (entryTimestamp == tsMn) ? ii : oldI3;
			oldT3 = tsMn;
			
			tsMn = Math.min(oldT2, oldT3);
			oldI2 = (oldT3 == tsMn) ? oldI3 : oldI2;
			oldT3 = Math.max(oldT2, oldT3);
			oldT2 = tsMn;
			
			tsMn = Math.min(oldT1, oldT2);
			oldI1 = (oldT2 == tsMn) ? oldI2 : oldI1;
			oldT2 = Math.max(oldT1, oldT2);
			oldT1 = tsMn;

			tsMn = Math.min(oldT0, oldT1);
			oldI0 = (oldT1 == tsMn) ? oldI1 : oldI0;
			oldT1 = Math.max(oldT0, oldT1);
			oldT0 = tsMn;
		}
		
		// couldn't update value.. add a new entry -- might become a zombie!
		Entry<K, V> newEntry = new Entry<K, V>(keyHash, key, value, weight, nextTimestamp.getAndIncrement());

		// try to overwrite known older (or empty) entries
		if(tryOverwriteEntry(oldI0, oldT0, newEntry) ||
				tryOverwriteEntry(oldI1, oldT1, newEntry) ||
				tryOverwriteEntry(oldI2, oldT2, newEntry))
			return null;

		// as a last resort, smash whatever is at index oldI3
		Entry<K, V> oldEntry = entries.getAndSet(oldI3, newEntry);
		
		@SuppressWarnings("unchecked")
		V oldValue = oldEntry.getAndSetValue((V) Entry.tombstoneValue);
		
		if(oldValue != null && oldValue != Entry.tombstoneValue) {
			size.decrementAndGet();
			removedWeight.addAndGet(weigher.applyAsLong(key, oldValue));
		}
		return null;
	}
	
	/**
	 * Removes the value at the given key.
	 *
	 * @param key the key
	 * @return the v
	 */
	@Override
	public V remove(Object key) {
		if(key == null)
			throw new IllegalArgumentException("key must be non-null");

		int keyHash = key.hashCode();
		int len = entries.length();
		int index = Math.abs(keyHash) % len;
		V result = null;

		// always traverse the whole neighborhood in case of zombies
		for(int i = 0; i < neighborhood; i++) {
			Entry<K, V> entry = entries.get((index + i) % len);

			if(keyHash == entry.keyHash && key.equals(entry.key)) {
				long timestamp = entry.timestamp.get();
				V oldValue = entry.getAndSetValue(null);
				
				if(oldValue != null && oldValue != Entry.tombstoneValue) {
					entry.timestamp.compareAndSet(timestamp, 0);
					size.decrementAndGet();
					removedWeight.addAndGet(weigher.applyAsLong(entry.key, oldValue));
					if(result == null)
						result = oldValue;
				}
			}
		}
		return result;
	}

	/**
	 * Clear the map.
	 */
	@Override
	public void clear() {
		int len = entries.length();
		
		for(int i = 0; i < len; i++) {
			Entry<K, V> entry = entries.get(i);
			V oldValue = entry.getAndSetValue(null);
			
			if(oldValue != null && oldValue != Entry.tombstoneValue) {
				size.decrementAndGet();
				removedWeight.addAndGet(weigher.applyAsLong(entry.key, oldValue));
			}
		}
	}
	
	/**
	 * Checks if is empty.
	 *
	 * @return true, if is empty
	 */
	@Override
	public boolean isEmpty() {
		return size() == 0;
	}
	
	/**
	 * Check for a value associated with the given key.
	 *
	 * @param key the key
	 * @return true, if successful
	 */
	@Override
	public boolean containsKey(Object key) {
		return get(key) != null;
	}
	
	/**
	 * Unsupported.
	 *
	 * @param value the value
	 * @return true, if successful
	 */
	@Override
	public boolean containsValue(Object value) {
		throw new UnsupportedOperationException();
	}
	
	/**
	 * Put all key/value pairs.
	 *
	 * @param m the m
	 */
	@Override
	public void putAll(Map<? extends K, ? extends V> m) {
		for(Map.Entry<? extends K, ? extends V> entry: m.entrySet())
			put(entry.getKey(), entry.getValue());
	}
	
	/**
	 * Unsupported.
	 *
	 * @return the sets the
	 */
	@Override
	public Set<K> keySet() {
		throw new UnsupportedOperationException();
	}
	
	/**
	 * Unsupported.
	 *
	 * @return the collection
	 */
	@Override
	public Collection<V> values() {
		throw new UnsupportedOperationException();
	}
	
	/**
	 * Unsupported.
	 *
	 * @return the sets the
	 */
	@Override
	public Set<Map.Entry<K, V>> entrySet() {
		throw new UnsupportedOperationException();
	}
}
