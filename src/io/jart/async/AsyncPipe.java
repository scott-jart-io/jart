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

// thread-safe asynchronous pipe; multi-reader/writer
// zero write pressure
// monitors quiescence -- can notify when a certain number of tasks are
// blocked on reads
public class AsyncPipe<T> {
	private final static Logger logger = Logger.getLogger(AsyncPipe.class);

	public static class Group {
		private final AsyncPipe<Object> quiescencePipe;
		private final Object quiescenceMsg;
		private final AtomicLong quiescenceThreshBlockCount;

		private static int quiescenceThresh(long qtbc) {
			return (int) (qtbc >> 32);
		}
		
		private static int blockCount(long qtbc) {
			return (int) qtbc;
		}
		
		private static long makeQTBC(int qt, int bc) {
			return (((long)qt) << 32) | bc;
		}
		
		private static boolean isQuiescent(long qtbc) {
			return blockCount(qtbc) >= quiescenceThresh(qtbc);
		}
		
		private static boolean becameQuiescent(long qtbc) {
			return blockCount(qtbc) == quiescenceThresh(qtbc);
		}

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

		public Group(AsyncPipe<? super Group> quiescencePipe) {
			this(quiescencePipe, null);
		}

		public Group() {
			this(null);
		}
		
		public boolean isQuiescent() {
			return isQuiescent(quiescenceThreshBlockCount.get());
		}

		public void adjustQuiescenceThresh(int n) {
			long qtbc = quiescenceThreshBlockCount.addAndGet(((long)n) << 32);

			if(becameQuiescent(qtbc))
				quiescencePipe.write(quiescenceMsg);
		}
		
		protected Queue<?> newQueue() {
			return new ConcurrentLinkedQueue<Object>();
		}
	}

	private static class Reader<T> extends CompletableFuture<T> implements Runnable {
		private final Queue<T> q;
		private final Executor exec;
		
		public Reader(Queue<T> q, Executor exec) {
			this.q = q;
			this.exec = exec;
		}

		public void completeAsync() {
			exec.execute(this);
		}
		
		@Override
		public void run() {
			complete(q.poll());
		}
	}
	
	private final Queue<T> msgQ;
	private final Queue<Reader<T>> readerQ;
	private final AtomicInteger readerDelta = new AtomicInteger();
	private final Group group;

	private void completeOne() {
		readerQ.poll().completeAsync();
	}
	
	@SuppressWarnings("unchecked")
	public AsyncPipe(Group group) {
		this.group = (group == null) ? new Group() : group;
		msgQ = (Queue<T>)this.group.newQueue();
		readerQ = (Queue<Reader<T>>)this.group.newQueue();
	}

	public AsyncPipe() {
		this(null);
	}

	public CompletableFuture<T> read(Executor exec) {
		Reader<T> result = new Reader<T>(msgQ, exec);

		readerQ.offer(result);
		if(readerDelta.incrementAndGet() <= 0)
			completeOne();
		else if(Group.becameQuiescent(group.quiescenceThreshBlockCount.addAndGet(1)))
			group.quiescencePipe.write(group.quiescenceMsg);
		return result;
	}

	public T poll() {
		for(;;) {
			int cur = readerDelta.get();

			if(cur >= 0)
				return null;
			if(readerDelta.weakCompareAndSet(cur, cur + 1))
				return msgQ.poll();
		}
	}

	// roughly equivalent to dst.write(fun(Async.await(read(exec))) but tracks quiescence properly
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
	
	public boolean isEmpty() {
		return readerDelta.get() >= 0;
	}
	
	public void write(T msg) {
		msgQ.offer(msg);
		if(readerDelta.decrementAndGet() >= 0) {
			group.quiescenceThreshBlockCount.addAndGet(-1);
			completeOne();
		}
	}
}
