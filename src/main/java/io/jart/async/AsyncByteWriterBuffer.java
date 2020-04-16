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

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

// helps implement an asynchronous buffer for writing bytes on top of synchronous byte submission
public abstract class AsyncByteWriterBuffer implements AsyncByteWriter {
	private static class Writer extends CompletableFuture<Void> {
		public final int size;
		
		public Writer(int size) {
			this.size = size;
		}
	}
	private static class State {
		public Writer writer;
		public int size;
	}
	private final int cap;
	private AtomicReference<State> state = new AtomicReference<State>(new State());
	
	public AsyncByteWriterBuffer(int cap) {
		this.cap = cap;
	}

	private Writer doWrite(int size) {
		Writer result = new Writer(size);
		State curState;
		State newState = new State();
		
		for(;;) {
			curState = state.get();
			
			if(curState.size + size <= cap || curState.size == 0) {
				newState.writer = null;
				newState.size = curState.size + size;
				if(state.compareAndSet(curState, newState)) {
					result.complete(null);
					break;
				}
			}
			else {
				newState.writer = result;
				newState.size = curState.size;
				if(state.compareAndSet(curState, newState))
					break;
			}
		}
		if(curState.writer != null)
			curState.writer.completeExceptionally(new IllegalStateException("writer was displaced"));
		return result;
	}
	
	private boolean doTryWrite(int size) {
		State newState = null;
		
		for(;;) {
			State curState = state.get();

			if(curState.size + size > cap && curState.size != 0)
				return false;
			if(newState == null)
				newState = new State();
			newState.size = curState.size + size;
			if(state.compareAndSet(curState, newState)) {
				if(curState.writer != null)
					curState.writer.completeExceptionally(new IllegalStateException("writer was displaced"));
				return true;
			}
		}
	}

	public int size() { return state.get().size; }
	public int cap() { return cap; }
	
	@Override
	public CompletableFuture<Void> write(ByteBuffer buf) {
		return doWrite(buf.remaining()).thenRun(()->{ submit(buf); });
	}

	@Override
	public CompletableFuture<Void> write(byte[] bytes, int offs, int len) {
		return doWrite(len).thenRun(()->{ submit(bytes, offs, len); });
	}

	@Override
	public boolean tryWrite(ByteBuffer buf) {
		if(doTryWrite(buf.remaining())) {
			submit(buf);
			return true;
		}
		return false;
	}

	@Override
	public boolean tryWrite(byte[] bytes, int offs, int len) {
		if(doTryWrite(len)) {
			submit(bytes, offs, len);
			return true;
		}
		return false;
	}

	// note that we finished writing count bytes
	public void written(int count) {
		State newState = new State();
		
		for(;;) {
			State curState = state.get();

			// try to satisfy waiting writer
			if(curState.writer != null && (curState.size == 0 || curState.size - count + curState.writer.size <= cap)) {
				newState.writer = null;
				newState.size = curState.size - count + curState.writer.size;
				if(state.compareAndSet(curState, newState)) {
					curState.writer.complete(null);
					break;
				}
			}
			else { // just account for written bytes
				newState.writer = curState.writer;
				newState.size = curState.size - count;
				if(state.compareAndSet(curState, newState))
					break;
			}
		}
	}
	
	// override me -- synchronously submit bytes
	protected abstract void submit(ByteBuffer buf);
	protected abstract void submit(byte[] bytes, int offs, int len);
}
