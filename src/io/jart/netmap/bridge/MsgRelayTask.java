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

// task to enable efficient bulk relay of messages to bridge tasks
// use MsgRelay class!
public class MsgRelayTask implements AsyncRunnable {
	@POJO(fieldOrder = { "pipe", "msg" })
	public static class MsgRelayReq {
		protected AsyncPipe<Object> pipe;
		protected Object msg;
	
		protected MsgRelayReq() {}

		public interface Alloc {
			MsgRelayReq alloc(AsyncPipe<Object> pipe, Object msg);
			void free(MsgRelayReq req);
		}
		
		public AsyncPipe<Object> getPipe() {
			return pipe;
		}

		public Object getMsg() {
			return msg;
		}
}

	public static class MsgRelayKick {}
	public static final MsgRelayKick msgRelayKick = new MsgRelayKick();
	
	public static class Context {
		public final MsgRelayReq.Alloc msgRelayReqAlloc;
		public final Queue<MsgRelayReq> relayReqQ;
		public final AtomicInteger relayReqCount;
		public final AsyncPipe<MsgRelayKick> pipe;

		public final Executor exec;
	
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
	
	public MsgRelayTask(BridgeTask.Context bridgeContext, MsgRelayReq.Alloc msgRelayReqAlloc) {
		this(bridgeContext, msgRelayReqAlloc, null);
	}
	
	public MsgRelayTask(BridgeTask.Context bridgeContext, Executor exec) {
		this(bridgeContext, null, exec);
	}
	
	public MsgRelayTask(BridgeTask.Context bridgeContext) {
		this(bridgeContext, null, null);
	}

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
