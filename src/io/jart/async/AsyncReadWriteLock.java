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
import java.util.concurrent.atomic.AtomicLong;

public class AsyncReadWriteLock {
	private final Queue<CompletableFuture<Void>> readWaiters = new ConcurrentLinkedQueue<CompletableFuture<Void>>();
	private final Queue<CompletableFuture<Void>> writeWaiters = new ConcurrentLinkedQueue<CompletableFuture<Void>>();
	private final AtomicLong state = new AtomicLong();
	
	private static long makeState(int held, int rwaiting, int wwaiting) {
		return ((long)held << 42) | ((long)rwaiting << 21) | wwaiting;
	}
	
	private static int getHeld(long state) {
		return (int) (state >> 42);
	}
	
	private static int getRWaiting(long state) {
		return (int) ((state >> 21) & 0x1fffff);
	}
	
	private static int getWWaiting(long state) {
		return (int) (state & 0x1fffff);
	}

	public CompletableFuture<Void> readLock() {
		CompletableFuture<Void> cf = new CompletableFuture<Void>();
		
		readWaiters.offer(cf);
		for(;;) {
			long curState = state.get();
			int held = getHeld(curState);
			int rwaiting = getRWaiting(curState);
			int wwaiting = getWWaiting(curState);
			
			if(held < 0 || wwaiting > 0) { // write held or writer waiting
				if(state.compareAndSet(curState,
						makeState(held, rwaiting + 1, wwaiting))) // add rwaiting
					break;
			}
			else if(state.compareAndSet(curState, // held >= 0 && wwaiting <= 0
					makeState(held + 1, rwaiting, wwaiting))) {
				readWaiters.poll().complete(null); // got one!
				break;
			}
		}
		return cf;
	}
	public CompletableFuture<Void> writeLock() {
		CompletableFuture<Void> cf = new CompletableFuture<Void>();
		
		writeWaiters.offer(cf);
		for(;;) {
			long curState = state.get();
			int held = getHeld(curState);
			int rwaiting = getRWaiting(curState);
			int wwaiting = getWWaiting(curState);

			if(held != 0) { // writers need to wait if anyone holds the lock
				if(state.compareAndSet(curState,
						makeState(held, rwaiting, wwaiting + 1))) // add wwaiting
					break;
			}
			else if(state.compareAndSet(curState, // held == 0 -- try to get it
					makeState(-1, rwaiting, wwaiting))) {
				writeWaiters.poll().complete(null);
				break;
			}
		}
		
		return cf;
	}
	public void readUnlock() {
		for(;;) {
			long curState = state.get();
			int held = getHeld(curState);
			int rwaiting = getRWaiting(curState);
			int wwaiting = getWWaiting(curState);
			
			if(held == 1 && wwaiting > 0) { // last holder w/ wwaiter -- try to give to wwaiter
				if(state.compareAndSet(curState, makeState(-1, rwaiting, wwaiting - 1))) {
					writeWaiters.poll().complete(null);
					break;
				}
			} // held != 1 -- other readers holding it
			else if(state.compareAndSet(curState, makeState(held - 1, rwaiting, wwaiting)))
				break;
		}
	}
	public void writeUnlock() {
		for(;;) {
			long curState = state.get();
			int held = getHeld(curState);
			int rwaiting = getRWaiting(curState);
			int wwaiting = getWWaiting(curState);
			
			if(rwaiting > 0) { // try to give to readers first
				if(state.compareAndSet(curState, makeState(rwaiting, 0, wwaiting))) {
					for(int i = 0; i < rwaiting; i++)
						readWaiters.poll().complete(null);
					break;
				}
			}
			else if(wwaiting > 0) { // try to give to a writer
				if(state.compareAndSet(curState, makeState(held, rwaiting, wwaiting - 1))) {
					writeWaiters.poll().complete(null);
					break;
				}
			} // no one waiting
			else if(state.compareAndSet(curState, makeState(0, rwaiting, wwaiting))) // just give it up
				break;
		}
	}
}
