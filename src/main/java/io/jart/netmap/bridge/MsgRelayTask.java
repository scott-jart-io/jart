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

import org.apache.log4j.Logger;

import io.jart.async.AsyncLoop;
import io.jart.async.AsyncPipe;
import io.jart.async.AsyncRunnable;
import io.jart.pojo.Helper.POJO;
import io.jart.util.ThreadAffinityExecutor;

/**
 * Dependent BridgeTask to enable safe and efficient bulk relay of messages to bridge tasks.
 * Use MsgRelay class.
 */
public class MsgRelayTask implements AsyncRunnable {
	
	/**
	 * Message relay request.
	 */
	@POJO(fieldOrder = { "pipe", "msg" })
	public static class MsgRelayReq {
		protected AsyncPipe<Object> pipe; // pipe to deliver message to
		protected Object msg; // the message to deliver
	
		/**
		 * Instantiates a new msg relay req.
		 */
		protected MsgRelayReq() {}

		/**
		 * The Interface Alloc.
		 */
		public interface Alloc {
			
			/**
			 * Alloc.
			 *
			 * @param pipe the pipe
			 * @param msg the msg
			 * @return the msg relay req
			 */
			MsgRelayReq alloc(AsyncPipe<Object> pipe, Object msg);
			
			/**
			 * Free.
			 *
			 * @param req the req
			 */
			void free(MsgRelayReq req);
		}
		
		/**
		 * Gets the pipe.
		 *
		 * @return the pipe
		 */
		public AsyncPipe<Object> getPipe() {
			return pipe;
		}

		/**
		 * Gets the msg.
		 *
		 * @return the msg
		 */
		public Object getMsg() {
			return msg;
		}
}

	/**
	 * Send to "kick" use and start relaying messages.
	 */
	public static class MsgRelayKick {}
	public static final MsgRelayKick msgRelayKick = new MsgRelayKick();
	
	/**
	 * Info about running MsgRelayTask.
	 */
	public static class Context {
		public final MsgRelayReq.Alloc msgRelayReqAlloc;
		public final Queue<MsgRelayReq> relayReqQ;
		public final AtomicInteger relayReqCount;
		public final AsyncPipe<MsgRelayKick> pipe;

		public final Executor exec;
	
		/**
		 * Instantiates a new context.
		 *
		 * @param msgRelayReqAlloc the msg relay req alloc
		 * @param relayReqQ the relay req Q
		 * @param relayReqCount the relay req count
		 * @param pipe the pipe
		 * @param exec the exec
		 */
		public Context(MsgRelayReq.Alloc msgRelayReqAlloc, Queue<MsgRelayReq> relayReqQ, AtomicInteger relayReqCount, AsyncPipe<MsgRelayKick> pipe, Executor exec) {
			this.msgRelayReqAlloc = msgRelayReqAlloc;
			this.relayReqQ = relayReqQ;
			this.relayReqCount = relayReqCount;
			this.pipe = pipe;
			this.exec = exec;
		}
	}

	@SuppressWarnings("unused")
	private final static Logger logger = Logger.getLogger(MsgRelayTask.class);

	private final BridgeTask.Context bridgeContext;
	private final MsgRelayReq.Alloc msgRelayReqAlloc;
	private final Executor exec;

	public final CompletableFuture<Context> context = new CompletableFuture<Context>();
	
	/**
	 * Instantiates a new msg relay task.
	 *
	 * @param bridgeContext the bridge context
	 * @param msgRelayReqAlloc the msg relay req alloc
	 * @param exec the Executor to run on
	 */
	public MsgRelayTask(BridgeTask.Context bridgeContext, MsgRelayReq.Alloc msgRelayReqAlloc, Executor exec) {
		this.bridgeContext = bridgeContext;
		try {
			if(msgRelayReqAlloc == null)
				msgRelayReqAlloc = (MsgRelayReq.Alloc)io.jart.pojo.Helper.defaultHelper.newTrivialAlloc(MsgRelayReq.class);
		} catch (ClassNotFoundException | NoSuchMethodException | SecurityException | InstantiationException
				| IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
			throw new RuntimeException(e);
		}
		this.msgRelayReqAlloc = msgRelayReqAlloc;
		this.exec = new ThreadAffinityExecutor((exec != null) ? exec : bridgeContext.exec);
	}
	
	/**
	 * Instantiates a new msg relay task with default Executor.
	 *
	 * @param bridgeContext the bridge context
	 * @param msgRelayReqAlloc the msg relay req alloc
	 */
	public MsgRelayTask(BridgeTask.Context bridgeContext, MsgRelayReq.Alloc msgRelayReqAlloc) {
		this(bridgeContext, msgRelayReqAlloc, null);
	}
	
	/**
	 * Instantiates a new msg relay task with default allocator.
	 *
	 * @param bridgeContext the bridge context
	 * @param exec the Executor to rnu on
	 */
	public MsgRelayTask(BridgeTask.Context bridgeContext, Executor exec) {
		this(bridgeContext, null, exec);
	}
	
	/**
	 * Instantiates a new msg relay task with default allocator and Executor.
	 *
	 * @param bridgeContext the bridge context
	 */
	public MsgRelayTask(BridgeTask.Context bridgeContext) {
		this(bridgeContext, null, null);
	}

	/**
	 * Main.
	 *
	 * @return the completable future
	 */
	@Override
	public CompletableFuture<Void> run() {
		Queue<MsgRelayReq> relayReqQ = new ConcurrentLinkedQueue<MsgRelayReq>();
		AtomicInteger relayReqCount = new AtomicInteger();
		AsyncPipe<MsgRelayKick> pipe = new AsyncPipe<MsgRelayKick>(bridgeContext.taskPipeGroup);

		context.complete(new Context(msgRelayReqAlloc, relayReqQ, relayReqCount, pipe, exec));

		return AsyncLoop.iterate(()->pipe.read(exec), (MsgRelayKick dummy)->{
			int count = relayReqCount.get();
			
			// deal with requests
			while(count > 0) {
				for(int i = 0; i < count; i++) {
					MsgRelayReq req = relayReqQ.poll();
					
					req.getPipe().write(req.getMsg());
					msgRelayReqAlloc.free(req);
				}
				count = relayReqCount.addAndGet(-count);
			}
			return true;			
		}, exec);
	}

}
