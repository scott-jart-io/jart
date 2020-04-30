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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Pool that re-uses object freed in a previous cycle.
 *
 * @param <T> the generic type
 */
public class TickTockPool<T> implements Supplier<T>, Consumer<T> {
	
	/**
	 * Current state.
	 */
	private static class State {
		public final AtomicInteger allocCount = new AtomicInteger();
		public final Object[] alloc;
		public final AtomicInteger freeCount = new AtomicInteger();
		public final AtomicReferenceArray<Object> free;
		
		/**
		 * Instantiates a new state.
		 *
		 * @param alloc the alloc
		 * @param freeCount the free count
		 */
		public State(Object[] alloc, int freeCount) {
			this.alloc = alloc;
			this.free = new AtomicReferenceArray<Object>(freeCount);
		}
	}
	
	private final static Consumer<Object> noopCleaner = (Object obj)->{};

	private final Supplier<T> supplier;
	private final Consumer<Object> cleaner;
	private volatile State state;
	private int lastAllocCount, lastFreeCount;
	
	/**
	 * Instantiates a new tick tock pool.
	 *
	 * @param supplier the supplier
	 * @param cleaner the cleaner
	 */
	@SuppressWarnings("unchecked")
	public TickTockPool(Supplier<T> supplier, Consumer<T> cleaner) {
		this.supplier = supplier;
		this.state = new State(new Object[0], 16);
		this.cleaner = (cleaner == null) ? noopCleaner : (Consumer<Object>) cleaner;
	}
	
	/**
	 * Instantiates a new tick tock pool.
	 *
	 * @param supplier the supplier
	 */
	public TickTockPool(Supplier<T> supplier) {
		this(supplier, null);
	}
	
	/**
	 * Allocate a new object.
	 *
	 * @return the t
	 */
	@SuppressWarnings("unchecked")
	@Override
	public T get() {
		State state = this.state;
		int n = state.allocCount.getAndIncrement();
		
		if(n < state.alloc.length)
			return (T) state.alloc[n];
		else
			return supplier.get();
	}
	
	/**
	 * Free an object we're done with.
	 *
	 * @param t the t
	 */
	public void accept(T t) {
		State state = this.state;
		int n = state.freeCount.getAndIncrement();
		
		if(n < state.free.length())
			state.free.set(n, t);
	}
	
	/**
	 * Tick. Allow reuse anything freed prior to tick() invocation.
	 */
	public void tick() {
		State curState = state;
		
		Object[] curAlloc = curState.alloc;
		int allocCount = curState.allocCount.getAndSet(curAlloc.length);
		int newAllocCount = (allocCount + lastAllocCount) / 2;
		
		lastAllocCount = allocCount;
		
		Object[] newAlloc = new Object[newAllocCount];
		
		// start by moving over unused objects
		int n = Math.min(newAllocCount, curAlloc.length - allocCount);
		
		if(n > 0)
			System.arraycopy(curAlloc, allocCount, newAlloc, 0, n);
		else
			n = 0;
		
		AtomicReferenceArray<Object> curFree = curState.free;
		int freeCount = curState.freeCount.getAndSet(curFree.length());
		
		// clean and use freed
		int size = Math.min(curFree.length(), freeCount);
		
		for(int i = 0; n < newAllocCount && i < size; i++) {
			Object o = curFree.get(i); // race
			
			if(o != null)
				cleaner.accept(newAlloc[n++] = o);
		}
		
		// create new objects if necessary
		while(n < newAllocCount)
			newAlloc[n++] = supplier.get();
		
		int newFreeCount = (freeCount + lastFreeCount) / 2;
		
		lastFreeCount = freeCount;
		state = new State(newAlloc, newFreeCount);
	}
}
