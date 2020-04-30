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

/**
 * Implement an AsyncByteWriter that asynchronously accepts bytes but serially submits them while keeping
 * a cap on the maximum number of bytes submitted but not "written".
 */
public abstract class AsyncByteWriterBuffer implements AsyncByteWriter {
	
	/**
	 * Class representing a queued writer waiting (and associated size requirement).
	 */
	private static class Writer extends CompletableFuture<Void> {
		public final int size;
		
		/**
		 * Instantiates a new writer.
		 *
		 * @param size the size
		 */
		public Writer(int size) {
			this.size = size;
		}
	}
	
	/**
	 * Class representing our State at any given time -- Object instead of members for atomic operations.
	 */
	private static class State {
		public Writer writer; // waiting writer if any
		public int size; // total size of bytes submitted but not written
	}
	private final int cap; // "maximum" number of bytes to have submitted but not written
	private AtomicReference<State> state = new AtomicReference<State>(new State());

	/**
	 * Instantiates a new async byte writer buffer.
	 *
	 * @param cap the "maximum" number of bytes allowed in submitted-but-not-written state
	 */
	public AsyncByteWriterBuffer(int cap) {
		this.cap = cap;
	}

	/**
	 * Create a Writer that will complete when it can submit size bytes of data.
	 *
	 * Writer is completed immediately if size wouldn't exceed the cap OR nothing is submitted-but-not-written
	 * 
	 * @param size the size
	 * @return the writer
	 */
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
	
	/**
	 * Try to synchronously account for size bytes written.
	 *
	 * @param size the size
	 * @return true, if successful
	 */
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

	/**
	 * Get number of bytes submitted but not written.
	 *
	 * @return the int
	 */
	public int size() { return state.get().size; }
	
	/**
	 * Get the capacity supplied at instantiation time.
	 *
	 * @return the int
	 */
	public int cap() { return cap; }
	
	/**
	 * Write a ByteBuffer.
	 * Multiple concurrent writes are not allowed. A write's CF must complete before writing again.
	 *
	 * @param buf the source ByteBuffer
	 * @return the completable future which completes when the buffer is submitted (but not necessarily written)
	 */
	@Override
	public CompletableFuture<Void> write(ByteBuffer buf) {
		return doWrite(buf.remaining()).thenRun(()->{ submit(buf); });
	}

	/**
	 * Write a region of a byte[].
	 * Multiple concurrent writes are not allowed. A write's CF must complete before writing again.
	 *
	 * @param bytes the source byte[]
	 * @param offs the offset into bytes
	 * @param len the number of bytes to write
	 * @return the completable future which completes when the buffer is submitted (but not necessarily written)
	 */
	@Override
	public CompletableFuture<Void> write(byte[] bytes, int offs, int len) {
		return doWrite(len).thenRun(()->{ submit(bytes, offs, len); });
	}

	/**
	 * Try to synchronously write (submit) a ByteBuffer.
	 * No async write may be incomplete when calling.
	 *
	 * @param buf the buf
	 * @return true, if successful
	 */
	@Override
	public boolean tryWrite(ByteBuffer buf) {
		if(doTryWrite(buf.remaining())) {
			submit(buf);
			return true;
		}
		return false;
	}

	/**
	 * Try to synchronously write (submit) a region of a byte[].
	 * No async write may be incomplete when calling.
	 *
	 * @param bytes the bytes
	 * @param offs the offs
	 * @param len the len
	 * @return true, if successful
	 */
	@Override
	public boolean tryWrite(byte[] bytes, int offs, int len) {
		if(doTryWrite(len)) {
			submit(bytes, offs, len);
			return true;
		}
		return false;
	}

	/**
	 * Needs to be called when submitted bytes have been fully written.
	 *
	 * @param count the count
	 */
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
	
	/**
	 * Override me -- synchronously submit bytes (make sure "written" is called when completed).
	 *
	 * @param buf the buf
	 */
	protected abstract void submit(ByteBuffer buf);
	
	/**
	 * Override me -- synchronously submit bytes (make sure "written" is called when completed).
	 *
	 * @param bytes the bytes
	 * @param offs the offs
	 * @param len the len
	 */
	protected abstract void submit(byte[] bytes, int offs, int len);
}
