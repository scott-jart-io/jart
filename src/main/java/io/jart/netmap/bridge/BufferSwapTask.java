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

package io.jart.netmap.bridge;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.log4j.Logger;

import com.ea.async.Async;

import io.jart.async.AsyncLoop;
import io.jart.async.AsyncPipe;
import io.jart.async.AsyncRunnable;
import io.jart.util.ThreadAffinityExecutor;

/**
 * Dependent BridgeTask task to swap buffers between two BufferPipeTasks.
 */
public class BufferSwapTask implements AsyncRunnable {
	
	/**
	 * Info about a running BufferSwapTask.
	 */
	public static class Context {
		public final Executor exec;
	
		/**
		 * Instantiates a new context.
		 *
		 * @param exec the exec
		 */
		public Context(Executor exec) {
			this.exec = exec;
		}
	}

	@SuppressWarnings("unused")
	private final static Logger logger = Logger.getLogger(BufferSwapTask.class);

	protected final BufferUnlockerTask.Context bufferUnlockerContext;
	protected final Executor exec;
	private final BufferPipeTask.Context a;
	private final BufferPipeTask.Context b;
	private final AtomicInteger swapCount;

	public final CompletableFuture<BufferSwapTask.Context> context = new CompletableFuture<BufferSwapTask.Context>();

	/**
	 * Instantiates a new buffer swap task.
	 *
	 * @param bufferUnlockerContext the buffer unlocker context
	 * @param a the a
	 * @param b the b
	 * @param swapCount the swap count
	 * @param exec the Executor to run on
	 */
	public BufferSwapTask(BufferUnlockerTask.Context bufferUnlockerContext, BufferPipeTask.Context a, BufferPipeTask.Context b,
			AtomicInteger swapCount, Executor exec) {
		this.bufferUnlockerContext = bufferUnlockerContext;
		this.a = a;
		this.b = b;
		this.swapCount = swapCount;
		this.exec = new ThreadAffinityExecutor((exec != null) ? exec : bufferUnlockerContext.exec);
	}

	/**
	 * Instantiates a new buffer swap task with a default Executor.
	 *
	 * @param bufferUnlockerContext the buffer unlocker context
	 * @param a the a
	 * @param b the b
	 * @param swapCount the swap count
	 */
	public BufferSwapTask(BufferUnlockerTask.Context bufferUnlockerContext, BufferPipeTask.Context a, BufferPipeTask.Context b,
			AtomicInteger swapCount) {
		this(bufferUnlockerContext, a, b, swapCount, null);
	}

	protected static final int SWAP = 1; // swap buffers
	protected static final int NEXT_A = 2; // get the next "a" buffer
	protected static final int NEXT_B = 4; // get the next "b" buffer
	
	/**
	 * Return the swap action to perform for a pair of BufferRefs.
	 * Default action is to swap both buffers and then get new buffers.
	 * Override me to route buffers elsewhere.
	 *
	 * @param a the a
	 * @param b the b
	 * @return the int
	 */
	protected int swapAction(BufferRef a, BufferRef b) {
		return SWAP | NEXT_A | NEXT_B; // swap buffers then get two new ones
	}
	
	private BufferRef aBuf, bBuf;
	
	/**
	 * Main.
	 *
	 * @return the completable future
	 */
	@Override
	public CompletableFuture<Void> run() {
		context.complete(new BufferSwapTask.Context(exec));

		AsyncPipe<BufferRef> aPipe = a.pipe;
		AsyncPipe<BufferRef> bPipe = b.pipe;

		return AsyncLoop.doWhile(()->{
			if (aBuf == null) {
				aBuf = Async.await(aPipe.read(exec));
				if (bBuf == null)
					bBuf = bPipe.poll();
			}
			if (bBuf == null)
				bBuf = Async.await(bPipe.read(exec));

			do {
				int action = swapAction(aBuf, bBuf);
				
				if((action & SWAP) == SWAP) {
					bufferUnlockerContext.pipe.write(bufferUnlockerContext.bufferSwapSlotsAndUnlockReqAlloc.alloc(aBuf, bBuf));
					swapCount.incrementAndGet();
				}
				if((action & NEXT_A) == NEXT_A)
					aBuf = aPipe.poll();
				if((action & NEXT_B) == NEXT_B)
					bBuf = bPipe.poll();
			} while (aBuf != null && bBuf != null);
			return AsyncLoop.cfTrue;
		}, exec);
	}
}