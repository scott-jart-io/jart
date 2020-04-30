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
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import org.apache.log4j.Logger;

import com.sun.jna.LastErrorException;
import com.sun.jna.Memory;

import io.jart.async.AsyncEventQueue;
import io.jart.async.AsyncLoop;
import io.jart.async.AsyncPipe;
import io.jart.async.AsyncRunnable;
import io.jart.netmap.Netmapping;
import io.jart.util.CLibrary;
import io.jart.util.NativeBuffer;
import io.jart.util.PollFD;
import io.jart.util.ThreadAffinityExecutor;

/**
 * Handle synchronized polling of two Netmappings, dependent tasks, msg relay requests, and timed events.
 */
public class BridgeTask implements AsyncRunnable {
	private final static Logger logger = Logger.getLogger(BridgeTask.class);

	/**
	 * Helper to wake up the BridgeTask safely from outside.
	 */
	public static class Waker {
		private final AsyncPipe<BridgeMsg> bridgePipe;
		private final int wakeupFd;
		private final AtomicInteger bridgeState;
		private final Memory wakeupByte = new Memory(1);
		
		/**
		 * Instantiates a new waker.
		 *
		 * @param bridgePipe the bridge pipe
		 * @param wakeupFd the wakeup fd
		 * @param bridgeState the bridge state
		 */
		public Waker(AsyncPipe<BridgeMsg> bridgePipe, int wakeupFd, AtomicInteger bridgeState) {
			this.bridgePipe = bridgePipe;
			this.wakeupFd = wakeupFd;
			this.bridgeState = bridgeState;
		}
		
		/**
		 * Wake up the BridgeTask with a given message.
		 *
		 * @param <T> the generic type
		 * @param msg the msg
		 */
		// send a waking message (will interrupt a blocking poll)
		public <T extends BridgeReq> void wake(T msg) {
			bridgePipe.write(msg);
			// set state to waking, write a byte to the pipe if bridge is blocked
			if(bridgeState.getAndSet(STATE_WAKING) == STATE_BLOCKING)
				CLibrary.INSTANCE.write(wakeupFd, wakeupByte, 1);
		}
	}
	
	/**
	 * Contextual information about the running BridgeTask.
	 */
	public static class Context {
		// bridge's pipe -- send bridge messages here
		public final AsyncPipe<BridgeMsg> pipe;
		// bridge's task pipe group -- bridge will only pump packets when all tasks are
		// blocked reading a pipe created w/ this group
		public final AsyncPipe.Group taskPipeGroup;
		// queue of tasks to be started following a poll
		public final Queue<AsyncRunnable> taskQueue;
		// can send this message to wake up the task
		public final BridgeMsg nopMsg;
		// enable waking the bridge during a poll operation
		public final Waker waker;
		// executor used by the bridge task
		public final Executor exec;
	
		/**
		 * Instantiates a new context.
		 *
		 * @param pipe the pipe
		 * @param taskPipeGroup the task pipe group
		 * @param taskQueue the task queue
		 * @param nopMsg the nop msg
		 * @param waker the waker
		 * @param exec the exec
		 */
		public Context(AsyncPipe<BridgeMsg> pipe, AsyncPipe.Group taskPipeGroup, Queue<AsyncRunnable> taskQueue, BridgeMsg nopMsg, Waker waker, Executor exec) {
			this.pipe = pipe;
			this.taskPipeGroup = taskPipeGroup;
			this.taskQueue = taskQueue;
			this.nopMsg = nopMsg;
			this.waker = waker;
			this.exec = exec;
		}
	}

	/**
	 * Base for all BridgeTask messages.
	 */
	public static class BridgeMsg {
	}

	/**
	 * Base class for requests to the BridgeTask.
	 */
	public static class BridgeReq extends BridgeMsg {
	}

	/**
	 * Base class for responses from the BridgeTask.
	 */
	public static class BridgeResp extends BridgeMsg {
	}

	/**
	 * Message sent to use to indicate quiescence of dependent tasks.
	 */
	static class BridgeQuiescenceMsg extends BridgeMsg {
	}

	/**
	 * Request tx or tx on a Netmapping.
	 */
	public static class BridgeXReq extends BridgeReq {
		public final AsyncPipe<? super BridgeXResp> pipe;
		public final boolean rx;
		public final Netmapping nm;
	
		/**
		 * Instantiates a new bridge X req.
		 *
		 * @param pipe the pipe on which to respond when tx or rx was completed
		 * @param rx the rx
		 * @param nm the Netmapping
		 */
		public BridgeXReq(AsyncPipe<? super BridgeXResp> pipe, boolean rx, Netmapping nm) {
			this.pipe = pipe;
			this.rx = rx;
			this.nm = nm;
		}
	}

	/**
	 * Response sent when BridgeXReq is fulfilled.
	 */
	public static class BridgeXResp extends BridgeResp {
	}

	/**
	 * Request a message be relayed to a dependent task when safe.
	 *
	 * @param <T> the generic type
	 */
	public static class BridgeRelayReq<T> extends BridgeReq {
		public final AsyncPipe<? super T> pipe;
		public final T msg;
	
		/**
		 * Instantiates a new bridge relay req.
		 *
		 * @param pipe the pipe on which to send the message when safe
		 * @param msg the msg
		 */
		public BridgeRelayReq(AsyncPipe<? super T> pipe, T msg) {
			this.pipe = pipe;
			this.msg = msg;
		}
	}

	/**
	 * Base class for event-related requests.
	 */
	public static class BridgeEventQueueReq extends BridgeReq {
		public final AsyncEventQueue.Event event;
		
		/**
		 * Instantiates a new bridge event queue req.
		 *
		 * @param event the event
		 */
		protected BridgeEventQueueReq(AsyncEventQueue.Event event) {
			this.event = event;
		}
	}
	
	/**
	 * Request that an event be removed from the event queue.
	 */
	public static class BridgeEventQueueRemoveReq extends BridgeEventQueueReq {
		
		/**
		 * Instantiates a new bridge event queue remove req.
		 *
		 * @param event the event
		 */
		public BridgeEventQueueRemoveReq(AsyncEventQueue.Event event) {
			super(event);
		}
	}
	
	/**
	 * Request that an event be updated (/added).
	 */
	public static class BridgeEventQueueUpdateReq extends BridgeEventQueueReq {
		public final long time;
		
		/**
		 * Instantiates a new bridge event queue update req.
		 *
		 * @param event the event
		 * @param time the time
		 */
		public BridgeEventQueueUpdateReq(AsyncEventQueue.Event event, long time) {
			super(event);
			this.time = time;
		}
	}
	
	private final static int STATE_RUNNING = 0; // bridge is actively serving messages
	private final static int STATE_BLOCKING = 1; // bridge is blocked on netmap device poll
	private final static int STATE_WAKING = 2; // bridge is being asked to wake up from any blocking
	
	private final static BridgeXResp xResp = new BridgeXResp(); // only need one!
	private final static Memory wakeBuf = new Memory(4096);

	private final Netmapping a;
	private final Netmapping b;
	private final AtomicInteger pollCount;
	private final Executor exec;

	public final CompletableFuture<Context> context = new CompletableFuture<Context>();

	/**
	 * Instantiates a new bridge task.
	 *
	 * @param a the "a" Netmapping
	 * @param b the "b" Netmapping
	 * @param pollCount the poll count -- incremented each time a poll is completed
	 * @param exec the Executor to run on
	 */
	public BridgeTask(Netmapping a, Netmapping b, AtomicInteger pollCount, Executor exec) {
		this.a = a;
		this.b = b;
		this.pollCount = pollCount;
		this.exec = new ThreadAffinityExecutor(exec);
	}

	/**
	 * Instantiates a new bridge task with a default Executor.
	 * 
	 * @param a the "a" Netmapping
	 * @param b the "b" Netmapping
	 * @param pollCount the poll count -- incremented each time a poll is completed
	 */
	public BridgeTask(Netmapping a, Netmapping b, AtomicInteger pollCount) {
		this(a, b, pollCount, null);
	}

	/**
	 * Main.
	 *
	 * @return the completable future
	 */
	@Override
	public CompletableFuture<Void> run() {
		AsyncPipe.Group pipeGroup = new AsyncPipe.Group();
		AsyncPipe<BridgeMsg> pipe = new AsyncPipe<BridgeMsg>(pipeGroup);
		BridgeQuiescenceMsg quiescenceMsg = new BridgeQuiescenceMsg();
		AsyncPipe.Group taskPipeGroup = new AsyncPipe.Group(pipe, quiescenceMsg) {
			@Override
			protected Queue<?> newQueue() {
				return BridgeTask.this.newQueue();
			}
		};
		Queue<AsyncRunnable> taskQueue = new ConcurrentLinkedQueue<AsyncRunnable>();
		
		int wakeFd;
		AtomicInteger state = new AtomicInteger();
		
		{
			NativeBuffer pipeFds = new NativeBuffer(8);
			
			CLibrary.INSTANCE.pipe(pipeFds.ptr);
			
			// reuse quiescence message as nop message
			context.complete(new Context(pipe, taskPipeGroup, taskQueue, quiescenceMsg, new Waker(pipe, pipeFds.buf.getInt(4), state), exec));
			wakeFd = pipeFds.buf.getInt(0);
		}

		BiConsumer<Void, Throwable> taskComplete = (Void v, Throwable throwable)->{
			// reduce quiescence threshold when a task exits
			taskPipeGroup.adjustQuiescenceThresh(-1);
			if (throwable != null)
				logger.error("bridge task threw", throwable);
		};
		NativeBuffer pollFDs = PollFD.allocate(3);

		PollFD.setFD(pollFDs.buf, 0, a.fd);
		PollFD.setFD(pollFDs.buf, 1, b.fd);
		PollFD.setFD(pollFDs.buf, 2, wakeFd);

		AsyncEventQueue eventQueue = new AsyncEventQueue();
		@SuppressWarnings("unchecked")
		AsyncPipe<? super BridgeXResp>[] xReqs = (AsyncPipe<? super BridgeXResp>[])new AsyncPipe[4];
		
		eventQueue.updateTime(System.nanoTime()/1000, false);
	
		return AsyncLoop.iterate(()->pipe.read(exec), (BridgeMsg msg)->{
			// process msgs until block
			do {
				// process msgs until pipe polling fails
				do {
					if (msg instanceof BridgeEventQueueReq) { // deal with event queue requests
						BridgeEventQueueReq eqReq = (BridgeEventQueueReq)msg;
						
						eventQueue.remove(eqReq.event);
						if(eqReq instanceof BridgeEventQueueUpdateReq) {
							eqReq.event.setTime(((BridgeEventQueueUpdateReq)eqReq).time);
							eventQueue.add(eqReq.event);
						}
					}
					else if (msg instanceof BridgeRelayReq) { // relay messages while known safe
						@SuppressWarnings("unchecked")
						BridgeRelayReq<Object> rReq = (BridgeRelayReq<Object>)msg;
						
						 rReq.pipe.write(rReq.msg);
					} 
					else if (msg instanceof BridgeXReq) { // note an xfer request
						BridgeXReq xReq = (BridgeXReq) msg;
						int i = (a == xReq.nm) ? 0 : 1;
						int n = xReq.rx ? i : i + 2;	
						int mask = xReq.rx ? CLibrary.POLLIN : CLibrary.POLLOUT;

						PollFD.setEvents(pollFDs.buf, i, PollFD.getEvents(pollFDs.buf, i) | mask);
						if(xReqs[n] != null)
							logger.warn("xReq not null at BridgeXReq: " + n);
						xReqs[n] = xReq.pipe;
					}
					else if (msg != quiescenceMsg) // quiescence msg?
						logger.error("unrecognized bridge msg " + msg.getClass());

					msg = pipe.poll();
				} while (msg != null);
	
				while (msg == null && taskPipeGroup.isQuiescent()) {
					// update queue / fire any pending events
					eventQueue.updateTime(System.nanoTime()/1000, true);

					if (!taskPipeGroup.isQuiescent()) {
						msg = pipe.poll();
						break;
					}
					
					// start any requested tasks
					for(;;) {
						AsyncRunnable task = taskQueue.poll();
						
						if(task == null)
							break;
						
						// increase  quiescence threshold when a task starts
						taskPipeGroup.adjustQuiescenceThresh(1);
						exec.execute(()->{
							try {
								task.run().whenComplete(taskComplete);
							} catch (Throwable throwable) {
								taskComplete.accept(null, throwable);
							}						
						});
					}

					if (!taskPipeGroup.isQuiescent()) {
						msg = pipe.poll();
						break;
					}
					
					tick();
					
					if (!taskPipeGroup.isQuiescent()) {
						msg = pipe.poll();
						break;
					}
					
					// try to poll netmap device
					for(;;) {
						// ensure we're in running state (breaking to serve any msgs if asked to wake)
						if(state.getAndSet(STATE_RUNNING) == STATE_WAKING) {
							msg = pipe.poll();
							if(msg != null) // if we got one, process it
								break;
						}
						// try to transition from running to blocking
						if(state.compareAndSet(STATE_RUNNING, STATE_BLOCKING)) {
							PollFD.setREvents(pollFDs.buf, 0, 0);
							PollFD.setREvents(pollFDs.buf, 1, 0);
							PollFD.setREvents(pollFDs.buf, 2, 0);
							PollFD.setEvents(pollFDs.buf, 2, CLibrary.POLLIN);

							try {
								CLibrary.INSTANCE.poll(pollFDs.ptr, 3, 50); // do nothing for up to 50ms
							} catch (LastErrorException ex) {
								logger.error("poll threw", ex);
								return false;
							}

							if((PollFD.getREvents(pollFDs.buf, 0) & CLibrary.POLLERR) != 0) {
								logger.error("pollerr on a: " + a);
								return false;
							}
							if((PollFD.getREvents(pollFDs.buf, 1) & CLibrary.POLLERR) != 0) {
								logger.error("pollerr on b: " + b);
								return false;
							}

							pollCount.incrementAndGet();

							// respond to those waiting
							for(int i = 0; i < 4; i++) {
								short mask = ((i & 2) == 0) ? CLibrary.POLLIN : CLibrary.POLLOUT;
								
								if((PollFD.getREvents(pollFDs.buf, i & 1) & mask) != 0) {
									AsyncPipe<? super BridgeXResp> bpipe = xReqs[i];
									
									if(bpipe != null) {
										xReqs[i] = null;
										bpipe.write(xResp);
									}
									PollFD.setEvents(pollFDs.buf, i & 1, PollFD.getEvents(pollFDs.buf, i & 1) & ~mask);
								}
							}

							// clear out any wake byte(s)
							if(state.getAndSet(STATE_RUNNING) == STATE_WAKING)
								CLibrary.INSTANCE.read(wakeFd, wakeBuf, wakeBuf.size());

							// check for pending message
							msg = pipe.poll();
							break;
						}
					}
				}
			}
			while(msg != null);
			return true;
		}, exec);
	}
	
	/**
	 * Create queues for taskPipeGroup.
	 *
	 * @return the queue -- must be thread safe
	 */
	protected Queue<?> newQueue() {
		return new ConcurrentLinkedQueue<Object>();
	}

	/**
	 * called last (after events, task starts) right before a poll.
	 */
	protected void tick() {}
}