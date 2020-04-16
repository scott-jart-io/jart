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

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.log4j.Logger;

// a thread that executes runnables in FIFO order
public class WorkerThread extends Thread implements Executor {
	private final static Logger logger = Logger.getLogger(WorkerThread.class);

	protected static final int WORKING = 0;
	protected static final int WAITING = 1;
	protected static final int WAKING = 2;

	private final Lock lock = new ReentrantLock();
	private final Condition cond = lock.newCondition();
	protected final AtomicInteger state = new AtomicInteger();
	protected final Deque<Runnable> dq = newDeque();

	public WorkerThread() {
		setDaemon(true);
	}
	
	protected Deque<Runnable> newDeque() {
		return new ConcurrentLinkedDeque<Runnable>();
	}

	protected void uncaughtException(Throwable th) {
		logger.error("worker thread command threw", th);
	}

	protected void work() {
		for(;;) {
			Runnable r = dq.pollLast();

			if(r == null)
				break;
			r.run();
		}
	}

	protected boolean wake() {
		if(state.getAndSet(WAKING) == WAITING) {
			lock.lock();
			try {
				cond.signal();
			}
			finally {
				lock.unlock();
			}
			return true;
		}
		return false;
	}
	
	@Override
	public void run() {
		for(;;) {
			try {
				work();
			}
			catch(Throwable th) {
				try {
					uncaughtException(th);
				}
				catch(Throwable dummy) {
					logger.error("exception handler threw", th);
				}
				continue;
			}
			lock.lock();
			try {
				if(state.compareAndSet(WORKING, WAITING))
					cond.awaitUninterruptibly();
				state.set(WORKING);
			}
			finally {
				lock.unlock();
			}
		}
	}

	@Override
	public void execute(Runnable command) {
		dq.offerFirst(command);
		wake();
	}
}
