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

import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.log4j.Logger;

/**
 * Delegates tasks to provided Executor (or ForkJoinPool).
 * allows pausing which queues tasks but doesn't execute them
 * as well as running tasks synchronously
 */
public class PausableExecutor implements Executor {
	private final static Logger logger = Logger.getLogger(PausableExecutor.class);

	/**
	 * Link in waiting Runnable linked list.
	 */
	private static class Link {
		public final Runnable runnable;
		public Link next;
		
		/**
		 * Instantiates a new link.
		 *
		 * @param runnable the runnable
		 */
		public Link(Runnable runnable) {
			this.runnable = runnable;
		}
	}
	private static final Link sentinel = new Link(null);
	
	/**
	 * Reverse the linked list.
	 *
	 * @param cur the cur
	 * @return the link
	 */
	private static Link reverse(Link cur) {
		Link prev = null;
		
		while(cur != null && cur != sentinel) {
			Link next = cur.next;
			
			cur.next = prev;
			prev = cur;
			cur = next;
		}
		return prev;
	}
	
	private final Executor exec;
	private final AtomicReference<Link> head = new AtomicReference<Link>();
	
	/**
	 * Handle uncaught exception.
	 *
	 * @param th the th
	 */
	protected void uncaught(Throwable th) {
		logger.error("pausableexecutor runnable threw", th);
	}

	/**
	 * Instantiates a new pausable executor.
	 *
	 * @param exec the Executor to run on.
	 */
	public PausableExecutor(Executor exec) {
		this.exec = (exec == null) ? ForkJoinPool.commonPool() : exec;
	}

	/**
	 * Instantiates a new pausable executor with a default Executor.
	 */
	public PausableExecutor() {
		this(null);
	}

	/**
	 * Execute a command.
	 * Submit to wrapped Executor immediately unless paused. If paused, queue command until resumed.
	 *
	 * @param command the command
	 */
	@Override
	public void execute(Runnable command) {
		Link newLink = null;
		
		for(;;) {
			Link cur = head.get();
			
			if(cur == null) {
				exec.execute(command);
				break;
			}
			if(newLink == null)
				newLink = new Link(command);
			newLink.next = cur;
			if(head.compareAndSet(cur, newLink))
				break;
		}
	}

	/**
	 * Pause execution of commands.
	 */
	public void pause() {
		head.compareAndSet(null, sentinel);
	}
		
	/**
	 * Resume executing commands in wrapped Executor.
	 */
	public void resume() {
		Link cur = reverse(head.getAndSet(null));
		
		while(cur != null) {
			exec.execute(cur.runnable);
			cur = cur.next;
		}
	}

	/**
	 * Run each runnable in a linked list.
	 *
	 * @param cur the head of the list
	 */
	private void run(Link cur) {
		while(cur != null) {
			try {
				cur.runnable.run();
			}
			catch(Throwable th) {
				uncaught(th);
			}
			cur = cur.next;
		}		
	}

	/**
	 * Resume synchronously.
	 * Synchronously drain the queue (running any commands on this thread -- including newly submitted runnables) until empty and then
	 * resume submitting runnables to the wrapped Executor.
	 */
	public void resumeSync() {
		for(;;) {
			Link cur = head.getAndSet(sentinel);
			
			if(cur == sentinel)
				break;
			run(reverse(cur));
		}
		
		run(reverse(head.getAndSet(null)));
	}
}
