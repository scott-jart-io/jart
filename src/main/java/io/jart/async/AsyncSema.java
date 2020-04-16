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

package io.jart.async;

import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class AsyncSema {
	private final Queue<CompletableFuture<Void>> waiters;
	private final AtomicInteger permits;
	
	@SuppressWarnings("unchecked")
	public AsyncSema(int permits) {
		this.waiters = (Queue<CompletableFuture<Void>>) newQueue();
		this.permits = new AtomicInteger(permits);
	}
	
	CompletableFuture<Void> acquire() {
		CompletableFuture<Void> cf = new CompletableFuture<Void>();
		
		waiters.offer(cf);
		if(permits.decrementAndGet() >= 0)
			waiters.poll().complete(null);
		return cf;
	}

	boolean tryAcquire() {
		for(;;) {
			int curPermits = permits.get();
			
			if(curPermits <= 0)
				return false;
			if(permits.compareAndSet(curPermits, curPermits - 1))
				return true;
		}
	}

	void release() {
		if(permits.incrementAndGet() <= 0)
			waiters.poll().complete(null);
	}
	
	protected Queue<?> newQueue() {
		return new ConcurrentLinkedQueue<Object>();
	}
}
