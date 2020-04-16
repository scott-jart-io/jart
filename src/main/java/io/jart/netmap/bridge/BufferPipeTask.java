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
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.apache.log4j.Logger;

import com.ea.async.Async;

import io.jart.async.AsyncLoop;
import io.jart.async.AsyncPipe;
import io.jart.async.AsyncRunnable;
import io.jart.netmap.NetmapRing;
import io.jart.netmap.NetmapSlot;
import io.jart.netmap.Netmapping;
import io.jart.util.ThreadAffinityExecutor;

// move all available buffers from a set of tx or rx rings into a pipe
public class BufferPipeTask implements AsyncRunnable {
	public static class Context {
		public final AsyncPipe<BufferRef> pipe;
		public final Executor exec;
	
		public Context(AsyncPipe<BufferRef> pipe, Executor exec) {
			this.pipe = pipe;
			this.exec = exec;
		}
	}

	public static class BufferPipeMsg {}

	public static class BufferPipeReq extends BufferPipeTask.BufferPipeMsg {}

	public static class BufferLockReq extends BufferPipeTask.BufferPipeReq {
		public final AsyncPipe<? super BufferRef> pipe;
		public final BufferUnlockerTask.BufferLock bufferLock;
	
		public BufferLockReq(AsyncPipe<? super BufferRef> pipe, BufferUnlockerTask.BufferLock bufferLock) {
			this.pipe = pipe;
			this.bufferLock = bufferLock;
		}
	}

	@SuppressWarnings("unused")
	private final static Logger logger = Logger.getLogger(BufferPipeTask.class);

	private final BridgeTask.Context bridgeContext;
	private final Netmapping.Ring ring;
	private final BufferUnlockerTask.Context bufferUnlockerContext;
	private final Executor exec;

	public final CompletableFuture<BufferPipeTask.Context> context = new CompletableFuture<BufferPipeTask.Context>();

	public BufferPipeTask(BridgeTask.Context bridgeContext, Netmapping.Ring ring,
			BufferUnlockerTask.Context bufferUnlockerContext, Executor exec) {
		this.bridgeContext = bridgeContext;
		this.ring = ring;
		this.bufferUnlockerContext = bufferUnlockerContext;
		this.exec = new ThreadAffinityExecutor((exec != null) ? exec : bridgeContext.exec);
	}

	public BufferPipeTask(BridgeTask.Context bridgeContext, Netmapping.Ring ring,
			BufferUnlockerTask.Context bufferUnlockerContext) {
		this(bridgeContext, ring, bufferUnlockerContext, null);
	}

	@Override
	public CompletableFuture<Void> run() {
		AsyncPipe<BufferRef> pipe = new AsyncPipe<BufferRef>(bridgeContext.taskPipeGroup);

		context.complete(new BufferPipeTask.Context(pipe, exec));

		AsyncPipe<BridgeTask.BridgeMsg> bridgePipe = bridgeContext.pipe;
		AsyncPipe<Object> respPipe = new AsyncPipe<Object>(bridgeContext.taskPipeGroup);
		int slotCount = ring.slots.length;
		BufferUnlockerTask.BufferLock[] locks = new BufferUnlockerTask.BufferLock[slotCount];

		// pre-allocate all BufferLocks
		for (int i = 0; i < slotCount; i++)
			locks[i] = new BufferUnlockerTask.BufferLock(ring, i);

		Map<Long, BufferUnlockerTask.BufferLock> activeLocks = bufferUnlockerContext.locks;
		BridgeTask.BridgeReq xReq = new BridgeTask.BridgeXReq(respPipe, ring.rx, ring.nm);
		BufferUnlockerTask.RingWaitReq rwReq = new BufferUnlockerTask.RingWaitReq(respPipe, ring);
		ByteBuffer[] slots = ring.slots;
		int burst = Math.max(1, slots.length - 1);

		return AsyncLoop.doWhile(()->{
			Async.await(bufferUnlockerContext.rwLock.readLock());
			
			int head = (int) NetmapRing.getHead(ring.ring);
			int cur = (int) NetmapRing.getCur(ring.ring);
			int tail = (int) NetmapRing.getTail(ring.ring);
			int burstTail = (head + burst) % slots.length;

			while(cur != tail && cur != burstTail) {
				ByteBuffer xSlot = slots[cur];
				long bufIdx = NetmapSlot.getBufIdx(xSlot);

				activeLocks.put(bufIdx, locks[cur]);
				pipe.write(bufferUnlockerContext.bufferRefAlloc.alloc(bufIdx, NetmapSlot.getLen(xSlot)));
				cur = (cur + 1) % slotCount;
			}
			NetmapRing.setCur(ring.ring, cur);
			
			// wait for the next round
			if(cur == tail) {
				bufferUnlockerContext.rwLock.readUnlock();
				bridgePipe.write(xReq);
			}
			else {
				bufferUnlockerContext.pipe.write(rwReq);
				bufferUnlockerContext.rwLock.readUnlock();
			}
			Async.await(respPipe.read(exec));
			return AsyncLoop.cfTrue;
		}, exec);
	}
}