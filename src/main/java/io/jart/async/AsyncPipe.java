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
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.apache.log4j.Logger;

/**
 * Thread-safe asynchronous object pipe; multi-reader, multi-writer.
 *
 * Zero write pressure.
 * Monitors quiescence -- can notify when a certain number of readers are blocked.
 * 
 * @param <T> the generic type
 */
public class AsyncPipe<T> {
	private final static Logger logger = Logger.getLogger(AsyncPipe.class);

	/**
	 * A group of AsyncPipes which have a common quiescence point and pipe/object that notify of quiescence.
	 */
	public static class Group {
		private final AsyncPipe<Object> quiescencePipe;
		private final Object quiescenceMsg;
		private final AtomicLong quiescenceThreshBlockCount;

		/**
		 * Get the 32bit quiescense threshold part from a 64bit "qtbc" state.
		 *
		 * @param qtbc the qtbc
		 * @return the int
		 */
		private static int quiescenceThresh(long qtbc) {
			return (int) (qtbc >> 32);
		}
		
		/**
		 * Get the 32bit block count threshold part from a 64bit "qtbc" state.
		 *
		 * @param qtbc the qtbc
		 * @return the int
		 */
		private static int blockCount(long qtbc) {
			return (int) qtbc;
		}
		
		/**
		 * Make a 64bit "qtbc" state from 32bit quiescense threshold and 32bit block count.
		 *
		 * @param qt the qt
		 * @param bc the bc
		 * @return the long
		 */
		private static long makeQTBC(int qt, int bc) {
			return (((long)qt) << 32) | bc;
		}
		
		/**
		 * Checks if qtbc is quiescent.
		 *
		 * @param qtbc the qtbc
		 * @return true, if is quiescent
		 */
		private static boolean isQuiescent(long qtbc) {
			return blockCount(qtbc) >= quiescenceThresh(qtbc);
		}
		
		/**
		 * Check if the qtbc just became quiescent.
		 *
		 * @param qtbc the qtbc
		 * @return true, if successful
		 */
		private static boolean becameQuiescent(long qtbc) {
			return blockCount(qtbc) == quiescenceThresh(qtbc);
		}

		/**
		 * Instantiates a new group.
		 *
		 * @param quiescencePipe the quiescence pipe
		 * @param quiescenceMsg the quiescence msg
		 */
		@SuppressWarnings("unchecked")
		public Group(AsyncPipe<?> quiescencePipe, Object quiescenceMsg) {
			this.quiescencePipe = (AsyncPipe<Object>) quiescencePipe;
			if(quiescencePipe == null) {
				this.quiescenceMsg = null;
				quiescenceThreshBlockCount = new AtomicLong(makeQTBC(Integer.MAX_VALUE / 2, 0));
			}
			else {
				this.quiescenceMsg = (quiescenceMsg == null) ? this : quiescenceMsg;
				quiescenceThreshBlockCount = new AtomicLong();
			}
		}

		/**
		 * Instantiates a new group.
		 *
		 * @param quiescencePipe the quiescence pipe
		 */
		public Group(AsyncPipe<? super Group> quiescencePipe) {
			this(quiescencePipe, null);
		}

		/**
		 * Instantiates a new group.
		 */
		public Group() {
			this(null);
		}
		
		/**
		 * Checks if is quiescent.
		 *
		 * @return true, if is quiescent
		 */
		public boolean isQuiescent() {
			return isQuiescent(quiescenceThreshBlockCount.get());
		}

		/**
		 * Adjust quiescence threshhold and notify if we became quiescent.
		 *
		 * @param n the n
		 */
		public void adjustQuiescenceThresh(int n) {
			long adjust = ((long)n) << 32;
			long qtbc = quiescenceThreshBlockCount.addAndGet(adjust);

			if(isQuiescent(qtbc) && !isQuiescent(qtbc - adjust))
				quiescencePipe.write(quiescenceMsg);
		}
		
		/**
		 * Overridable queue creation -- Queue must be concurrent
		 *
		 * @return the queue
		 */
		protected Queue<?> newQueue() {
			return new ConcurrentLinkedQueue<Object>();
		}
	}

	/**
	 * Represents a reader waiting for a T.
	 *
	 * @param <T> the generic type
	 */
	private static class Reader<T> extends CompletableFuture<T> implements Runnable {
		private final Queue<T> q;
		private final Executor exec;
		
		/**
		 * Instantiates a new reader.
		 *
		 * @param q the q
		 * @param exec the exec
		 */
		public Reader(Queue<T> q, Executor exec) {
			this.q = q;
			this.exec = exec;
		}

		/**
		 * Complete async.
		 */
		public void completeAsync() {
			exec.execute(this);
		}
		
		/**
		 * Run.
		 */
		@Override
		public void run() {
			complete(q.poll());
		}
	}
	
	private final Queue<T> msgQ; // queue of messages in the pipe
	private final Queue<Reader<T>> readerQ; // queue of readers waiting for a message
	private final AtomicInteger readerDelta = new AtomicInteger(); // atomic version of readerQ.size - msgQ.size
	private final Group group; // group for this pipe

	/**
	 * Assumes there's >=1 message and >=1 Reader and matches the two up.
	 */
	private void completeOne() {
		readerQ.poll().completeAsync();
	}
	
	/**
	 * Instantiates a new async pipe with a given AsyncPipeGroup.
	 *
	 * @param group the AsyncPipeGroup (or null for no shared group)
	 */
	@SuppressWarnings("unchecked")
	public AsyncPipe(Group group) {
		this.group = (group == null) ? new Group() : group;
		msgQ = (Queue<T>)this.group.newQueue();
		readerQ = (Queue<Reader<T>>)this.group.newQueue();
	}

	/**
	 * Instantiates a new async pipe with no shared group.
	 */
	public AsyncPipe() {
		this(null);
	}

	/**
	 * Asynchronously read a message from the pipe to be delivered using the given Executor.
	 * Will block indefinitely until a message is available.
	 *
	 * @param exec the exec
	 * @return the completable future
	 */
	public CompletableFuture<T> read(Executor exec) {
		Reader<T> result = new Reader<T>(msgQ, exec);

		readerQ.offer(result);
		if(readerDelta.incrementAndGet() <= 0)
			completeOne();
		else if(Group.becameQuiescent(group.quiescenceThreshBlockCount.addAndGet(1)))
			group.quiescencePipe.write(group.quiescenceMsg);
		return result;
	}

	/**
	 * Attempt to synchronously read a message.
	 *
	 * @return the t or null if the pipe is empty
	 */
	public T poll() {
		for(;;) {
			int cur = readerDelta.get();

			if(cur >= 0)
				return null;
			if(readerDelta.compareAndSet(cur, cur + 1))
				return msgQ.poll();
		}
	}

	/**
	 * Transfer (and translate) a message from us and deliver it to dst.
	 * Roughly equivalent to dst.write(fun(Async.await(read(exec))) but tracks quiescence properly.
	 *
	 * @param <D> the generic type
	 * @param <O> the generic type
	 * @param dst the destination pipe
	 * @param fun the mapping function that maps a message from this pipe to a possible different message to write to dst
	 * @param exec the Executor to run on
	 */
	public<D, O extends D> void transfer(AsyncPipe<D> dst, Function<T, O> fun, Executor exec) {
		if(dst.group != group)
			throw new IllegalArgumentException("src and dst groups must match");

		BiConsumer<T, Throwable> readComplete = (T t, Throwable th)->{
			if(th == null) {
				try {
					dst.write(fun.apply(t));
				}
				catch(Throwable ex) {
					th = ex;
				}
			}
			group.adjustQuiescenceThresh(-1);
			if(th != null)
				logger.error("transfer threw", th);
		};

		group.adjustQuiescenceThresh(1);
		try {
			read(exec).whenComplete(readComplete);
		}
		catch(Throwable th) {
			readComplete.accept(null, th);
		}
	}
	
	/**
	 * Checks if currently empty.
	 * Doesn't have a lot of meaning if the pipe is being used by multiple threads.
	 *
	 * @return true, if is empty
	 */
	public boolean isEmpty() {
		return readerDelta.get() >= 0;
	}
	
	/**
	 * Write a message to the pipe.
	 *
	 * @param msg the msg
	 */
	public void write(T msg) {
		msgQ.offer(msg);
		if(readerDelta.decrementAndGet() >= 0) {
			group.quiescenceThreshBlockCount.addAndGet(-1);
			completeOne();
		}
	}
}
