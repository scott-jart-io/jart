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

import java.lang.reflect.InvocationTargetException;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import io.jart.async.AsyncEventQueue;
import io.jart.async.AsyncLoop;
import io.jart.async.AsyncPipe;
import io.jart.async.AsyncRunnable;
import io.jart.async.AsyncEventQueue.Event;
import io.jart.pojo.Helper.POJO;
import io.jart.util.ThreadAffinityExecutor;

// task for an event queue driven by a bridge task's event queue
// (allows more parallelism in event handling vs making the bridge task deal with
// ALL events in the system)
public class EventQueueTask implements AsyncRunnable {
	public static class EventReq {
		protected AsyncEventQueue.Event event;
		
		protected EventReq() {}
		
		public AsyncEventQueue.Event getEvent() {
			return event;
		}
	}
	
	@POJO(fieldOrder = { "event", "time" })
	public static class EventUpdateReq extends EventReq {
		protected long time;

		protected EventUpdateReq() {}
		
		public interface Alloc {
			EventUpdateReq alloc(AsyncEventQueue.Event event, long time);
			void free(EventUpdateReq req);
		}
		
		public long getTime() {
			return time;
		}
	}
	
	@POJO(fieldOrder = { "event" })
	public static class EventRemoveReq extends EventReq {
		protected EventRemoveReq() {}

		public interface Alloc {
			EventRemoveReq alloc(AsyncEventQueue.Event event);
			void free(EventRemoveReq req);
		}		
	}
	
	public static class EventQueueKick {}
	public static final EventQueueKick eventQueueKick = new EventQueueKick();

	public static class Context {
		public final EventUpdateReq.Alloc eventUpdateReqAlloc;
		public final EventRemoveReq.Alloc eventRemoveReqAlloc;
		public final Queue<EventReq> eventReqQ;
		public final AtomicInteger eventReqCount;
		public final AsyncPipe<EventQueueKick> pipe;
		public final Executor exec;
		
		public Context(EventUpdateReq.Alloc eventUpdateReqAlloc, EventRemoveReq.Alloc eventRemoveReqAlloc, Queue<EventReq> eventReqQ, AtomicInteger eventReqCount, AsyncPipe<EventQueueKick> pipe, Executor exec) {
			this.eventUpdateReqAlloc = eventUpdateReqAlloc;
			this.eventRemoveReqAlloc = eventRemoveReqAlloc;
			this.eventReqQ = eventReqQ;
			this.eventReqCount = eventReqCount;
			this.pipe = pipe;
			this.exec = exec;
		}
	}
	
	private final BridgeTask.Context bridgeContext;
	private final EventUpdateReq.Alloc eventUpdateReqAlloc;
	private final EventRemoveReq.Alloc eventRemoveReqAlloc;
	private final Executor exec;

	public final CompletableFuture<Context> context = new CompletableFuture<Context>();
	
	public EventQueueTask(BridgeTask.Context bridgeContext, EventUpdateReq.Alloc eventUpdateReqAlloc, EventRemoveReq.Alloc eventRemoveReqAlloc, Executor exec) {
		this.bridgeContext = bridgeContext;
		try {
			if(eventUpdateReqAlloc == null)
				eventUpdateReqAlloc = (EventUpdateReq.Alloc)io.jart.pojo.Helper.defaultHelper.newTrivialAlloc(EventUpdateReq.class);
			if(eventRemoveReqAlloc == null)
				eventRemoveReqAlloc = (EventRemoveReq.Alloc)io.jart.pojo.Helper.defaultHelper.newTrivialAlloc(EventRemoveReq.class);
		} catch (ClassNotFoundException | NoSuchMethodException | SecurityException | InstantiationException
				| IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
			throw new RuntimeException(e);
		}
		this.eventUpdateReqAlloc = eventUpdateReqAlloc;
		this.eventRemoveReqAlloc = eventRemoveReqAlloc;
		this.exec = new ThreadAffinityExecutor((exec != null) ? exec : bridgeContext.exec);
	}
	
	public EventQueueTask(BridgeTask.Context bridgeContext, EventUpdateReq.Alloc eventUpdateReqAlloc, EventRemoveReq.Alloc eventRemoveReqAlloc) {
		this(bridgeContext, eventUpdateReqAlloc, eventRemoveReqAlloc, null);
	}
	
	public EventQueueTask(BridgeTask.Context bridgeContext, Executor exec) {
		this(bridgeContext, null, null, exec);
	}
	
	public EventQueueTask(BridgeTask.Context bridgeContext) {
		this(bridgeContext, null, null, null);		
	}
	
	@Override
	public CompletableFuture<Void> run() {
		Queue<EventReq> eventReqQ = new ConcurrentLinkedQueue<EventReq>();
		AtomicInteger eventReqCount = new AtomicInteger();
		AsyncPipe<Object> pipe = new AsyncPipe<Object>(bridgeContext.taskPipeGroup);
		@SuppressWarnings("unchecked")
		AsyncPipe<EventQueueKick> kickPipe = (AsyncPipe<EventQueueKick>)(AsyncPipe<?>)pipe;

		context.complete(new Context(eventUpdateReqAlloc, eventRemoveReqAlloc, eventReqQ, eventReqCount, kickPipe, exec));

		AsyncEventQueue eventQueue = new AsyncEventQueue();
		AsyncEventQueue.Event event = new AsyncEventQueue.Event((AsyncPipe<? super Event>) pipe);
		
		eventQueue.updateTime(System.nanoTime()/1000, false);
		
		return AsyncLoop.iterate(()->pipe.read(exec), (Object dummy)->{
			int count = eventReqCount.get();
			
			// deal with requests
			while(count > 0) {
				for(int i = 0; i < count; i++) {
					EventReq req = eventReqQ.poll();
					
					eventQueue.remove(req.getEvent());
					if(req instanceof EventUpdateReq) {
						EventUpdateReq updReq = (EventUpdateReq)req;
						
						req.getEvent().setTime(updReq.getTime());
						eventQueue.add(updReq.getEvent());
						eventUpdateReqAlloc.free(updReq);
					}
					else
						eventRemoveReqAlloc.free((EventRemoveReq)req);
				}
				count = eventReqCount.addAndGet(-count);
			}
			
			eventQueue.updateTime(System.nanoTime()/1000, true);
			
			long time = eventQueue.nextUpdateTime();
			
			bridgeContext.pipe.write(new BridgeTask.BridgeEventQueueUpdateReq(event, time));
			return true;
		}, exec);
	}

}
