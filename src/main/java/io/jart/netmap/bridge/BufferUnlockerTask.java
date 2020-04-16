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
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import org.apache.log4j.Logger;

import com.ea.async.Async;

import io.jart.async.AsyncLoop;
import io.jart.async.AsyncPipe;
import io.jart.async.AsyncReadWriteLock;
import io.jart.async.AsyncRunnable;
import io.jart.netmap.Netmap;
import io.jart.netmap.NetmapRing;
import io.jart.netmap.NetmapSlot;
import io.jart.netmap.Netmapping;
import io.jart.pojo.Helper.POJO;
import io.jart.util.ThreadAffinityExecutor;

// handle buffer tracking and (swap and) unlock requests
public class BufferUnlockerTask implements AsyncRunnable {
	public static class Context {
		public final AsyncReadWriteLock rwLock;
		public final BufferRef.Alloc bufferRefAlloc;
		public final BufferUnlockReq.Alloc bufferUnlockReqAlloc;
		public final BufferSwapSlotsAndUnlockReq.Alloc bufferSwapSlotsAndUnlockReqAlloc;
		public final AsyncPipe<BufferUnlockerTask.BufferUnlockerReq> pipe;
		public final Map<Long, BufferUnlockerTask.BufferLock> locks;
		public final Executor exec;
	
		public Context(AsyncReadWriteLock rwLock, BufferRef.Alloc bufferRefAlloc, BufferUnlockReq.Alloc bufferUnlockReqAlloc, BufferSwapSlotsAndUnlockReq.Alloc bufferSwapSlotsAndUnlockReqAlloc, AsyncPipe<BufferUnlockerTask.BufferUnlockerReq> pipe, Map<Long, BufferUnlockerTask.BufferLock> locks, Executor exec) {
			this.rwLock = rwLock;
			this.bufferRefAlloc = bufferRefAlloc;
			this.bufferUnlockReqAlloc = bufferUnlockReqAlloc;
			this.bufferSwapSlotsAndUnlockReqAlloc = bufferSwapSlotsAndUnlockReqAlloc;
			this.pipe = pipe;
			this.locks = locks;
			this.exec = exec;
		}
	}

	public static class BufferLock {
		public final Netmapping.Ring ring;
		public final long slot;
	
		public BufferLock(Netmapping.Ring ring, long slot) {
			this.ring = ring;
			this.slot = slot;
		}
	}

	public static class BufferUnlockerMsg {
	}

	public static class BufferUnlockerReq extends BufferUnlockerTask.BufferUnlockerMsg {
	}

	public static class BufferUnlockerResp extends BufferUnlockerTask.BufferUnlockerMsg {
	}

	public static class RingWaitReq extends BufferUnlockerReq {
		public final AsyncPipe<? super RingWaitResp> pipe;
		public final Netmapping.Ring ring;

		public RingWaitReq(AsyncPipe<? super RingWaitResp> pipe, Netmapping.Ring ring) {
			this.pipe = pipe;
			this.ring = ring;
		}
	}
	
	public static class RingWaitResp extends BufferUnlockerResp {
	}
	
	@POJO(fieldOrder = { "buf" })
	public static class BufferUnlockReq extends BufferUnlockerTask.BufferUnlockerReq {
		protected BufferRef buf;
	
		protected BufferUnlockReq() {}
		
		public interface Alloc {
			BufferUnlockReq alloc(BufferRef buf);
			void free(BufferUnlockReq req);
		}

		public BufferRef getBuf() {
			return buf;
		}
	}

	@POJO(fieldOrder = { "bufA", "bufB" })
	public static class BufferSwapSlotsAndUnlockReq extends BufferUnlockerTask.BufferUnlockerReq {
		protected BufferRef bufA;
		protected BufferRef bufB;
	
		protected BufferSwapSlotsAndUnlockReq() {}

		public interface Alloc {
			BufferSwapSlotsAndUnlockReq alloc(BufferRef bufA, BufferRef bufB);
			void free(BufferSwapSlotsAndUnlockReq req);
		}

		public BufferRef getBufA() {
			return bufA;
		}

		public BufferRef getBufB() {
			return bufB;
		}
	}

	private final static Logger logger = Logger.getLogger(BufferUnlockerTask.class);

	private final BridgeTask.Context bridgeContext;
	private final BufferRef.Alloc bufferRefAlloc;
	private final BufferUnlockReq.Alloc bufferUnlockReqAlloc;
	private final BufferSwapSlotsAndUnlockReq.Alloc bufferSwapSlotsAndUnlockReqAlloc;
	private final Executor exec;

	public final CompletableFuture<BufferUnlockerTask.Context> context = new CompletableFuture<BufferUnlockerTask.Context>();

	public BufferUnlockerTask(BridgeTask.Context bridgeContext, BufferRef.Alloc bufferRefAlloc, BufferUnlockReq.Alloc bufferUnlockReqAlloc, BufferSwapSlotsAndUnlockReq.Alloc bufferSwapSlotsAndUnlockReqAlloc, Executor exec) {
		this.bridgeContext = bridgeContext;
		
		try {
			if(bufferRefAlloc == null)
					bufferRefAlloc = (BufferRef.Alloc)io.jart.pojo.Helper.defaultHelper.newTrivialAlloc(BufferRef.class);
			if(bufferUnlockReqAlloc == null)
					bufferUnlockReqAlloc = (BufferUnlockReq.Alloc)io.jart.pojo.Helper.defaultHelper.newTrivialAlloc(BufferUnlockReq.class);
			if(bufferSwapSlotsAndUnlockReqAlloc == null)
					bufferSwapSlotsAndUnlockReqAlloc = (BufferSwapSlotsAndUnlockReq.Alloc)io.jart.pojo.Helper.defaultHelper.newTrivialAlloc(BufferSwapSlotsAndUnlockReq.class);
		} catch (ClassNotFoundException | NoSuchMethodException | SecurityException | InstantiationException
				| IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
			throw new RuntimeException(e);
		}
		this.bufferRefAlloc = bufferRefAlloc;
		this.bufferUnlockReqAlloc = bufferUnlockReqAlloc;
		this.bufferSwapSlotsAndUnlockReqAlloc = bufferSwapSlotsAndUnlockReqAlloc;
		this.exec = new ThreadAffinityExecutor((exec != null) ? exec : bridgeContext.exec);
	}

	public BufferUnlockerTask(BridgeTask.Context bridgeContext, BufferRef.Alloc bufferRefAlloc, BufferUnlockReq.Alloc bufferUnlockReqAlloc, BufferSwapSlotsAndUnlockReq.Alloc bufferSwapSlotsAndUnlockReqAlloc) {
		this(bridgeContext, bufferRefAlloc, bufferUnlockReqAlloc, bufferSwapSlotsAndUnlockReqAlloc, null);
	}

	public BufferUnlockerTask(BridgeTask.Context bridgeContext, Executor exec) {
		this(bridgeContext, null, null, null, exec);
	}

	public BufferUnlockerTask(BridgeTask.Context bridgeContext) {
		this(bridgeContext, null, null, null, null);
	}
	
	@Override
	public CompletableFuture<Void> run() {
		AsyncReadWriteLock rwLock = new AsyncReadWriteLock();
		AsyncPipe<BufferUnlockerTask.BufferUnlockerReq> pipe = new AsyncPipe<BufferUnlockerTask.BufferUnlockerReq>(bridgeContext.taskPipeGroup);
		Map<Long, BufferUnlockerTask.BufferLock> locks = new ConcurrentHashMap<Long, BufferUnlockerTask.BufferLock>();
		context.complete(new BufferUnlockerTask.Context(rwLock, bufferRefAlloc, bufferUnlockReqAlloc, bufferSwapSlotsAndUnlockReqAlloc, pipe, locks, exec));

		Map<Netmapping.Ring, AsyncPipe<? super RingWaitResp>> ringWaits = new HashMap<Netmapping.Ring, AsyncPipe<? super RingWaitResp>>();
		Set<Netmapping.Ring> ringsTouched = new HashSet<Netmapping.Ring>();
		RingWaitResp rwResp = new RingWaitResp();
		
		return AsyncLoop.doWhile(()->{
			BufferUnlockerTask.BufferUnlockerReq req = Async.await(pipe.read(exec));

			Async.await(rwLock.writeLock());

			do {
				if (req instanceof BufferUnlockerTask.BufferUnlockReq) {
					BufferUnlockerTask.BufferUnlockReq uReq = (BufferUnlockerTask.BufferUnlockReq) req;
					BufferUnlockerTask.BufferLock lock = locks.remove(uReq.getBuf().getBufIdx());
					if (lock == null)
						logger.error("couldn't find BufferLock for unlock request of buf " + uReq.getBuf().getBufIdx());
					else {
						ringsTouched.add(lock.ring);
						
						ByteBuffer xRing = lock.ring.ring;
						ByteBuffer[] xSlots = lock.ring.slots;
						int head = (int) NetmapRing.getHead(xRing);
						ByteBuffer headSlot = xSlots[head];

						if (lock.slot != head) { // swap!
							ByteBuffer curSlot = xSlots[(int) lock.slot];
							long headBufIdx = NetmapSlot.getBufIdx(headSlot);

							NetmapSlot.setBufIdx(curSlot, headBufIdx);
							NetmapSlot.setFlags(curSlot, NetmapSlot.getFlags(curSlot) | Netmap.NS_BUF_CHANGED);
							NetmapSlot.setLen(curSlot, NetmapSlot.getLen(headSlot));

							NetmapSlot.setBufIdx(headSlot, uReq.getBuf().getBufIdx());
							NetmapSlot.setFlags(headSlot,
									NetmapSlot.getFlags(headSlot) | Netmap.NS_BUF_CHANGED);

							locks.put(headBufIdx, lock);
						}
						NetmapSlot.setLen(headSlot, uReq.getBuf().getLen());
						NetmapRing.setHead(xRing, NetmapRing.ringNext(xRing, head));
					}
					bufferRefAlloc.free(uReq.getBuf());
					bufferUnlockReqAlloc.free(uReq);
				}
				else if (req instanceof BufferUnlockerTask.BufferSwapSlotsAndUnlockReq) {
					BufferUnlockerTask.BufferSwapSlotsAndUnlockReq sReq = (BufferUnlockerTask.BufferSwapSlotsAndUnlockReq) req;
					BufferUnlockerTask.BufferLock lockA = locks.remove(sReq.getBufA().getBufIdx());
					BufferUnlockerTask.BufferLock lockB = locks.remove(sReq.getBufB().getBufIdx());

					if (lockA == null)
						logger.error("couldn't find BufferLock for A buffer " + sReq.getBufA());
					else if (lockB == null)
						logger.error("couldn't find BufferLock for B buffer " + sReq.getBufB());
					else if (lockA.ring == lockB.ring)
						logger.error("attempting to swap between same rings");
					else {
						ringsTouched.add(lockA.ring);
						ringsTouched.add(lockB.ring);

						long headA = NetmapRing.getHead(lockA.ring.ring);
						long headB = NetmapRing.getHead(lockB.ring.ring);
						ByteBuffer headASlot = lockA.ring.slots[(int) headA];
						ByteBuffer headBSlot = lockB.ring.slots[(int) headB];

						if (headA == lockA.slot) { // already head for a
							if (headB != lockB.slot) { // only a at head -- b => a => b ring head =>
								ByteBuffer slotB = lockB.ring.slots[(int) lockB.slot];
								long bufIdxHeadB = NetmapSlot.getBufIdx(headBSlot);

								// b ring head => b
								NetmapSlot.setBufIdx(slotB, bufIdxHeadB);
								NetmapSlot.setLen(slotB, NetmapSlot.getLen(headBSlot));
								NetmapSlot.setFlags(slotB, NetmapSlot.getFlags(slotB) | Netmap.NS_BUF_CHANGED);

								locks.put(bufIdxHeadB, lockB);
							}
							// else just swap -- below
						}
						else if (headB == lockB.slot) { // only b at head -- a => b => a ring head =>
							ByteBuffer slotA = lockA.ring.slots[(int) lockA.slot];
							long bufIdxHeadA = NetmapSlot.getBufIdx(headASlot);

							// a ring head => a
							NetmapSlot.setBufIdx(slotA, bufIdxHeadA);
							NetmapSlot.setLen(slotA, NetmapSlot.getLen(headASlot));
							NetmapSlot.setFlags(slotA, NetmapSlot.getFlags(slotA) | Netmap.NS_BUF_CHANGED);

							locks.put(bufIdxHeadA, lockA);
						}
						else { // none at head -- 4 way swap -- a => b head => b => a head =>
							ByteBuffer slotA = lockA.ring.slots[(int) lockA.slot];
							long bufIdxHeadA = NetmapSlot.getBufIdx(headASlot);
							ByteBuffer slotB = lockB.ring.slots[(int) lockB.slot];
							long bufIdxHeadB = NetmapSlot.getBufIdx(headBSlot);

							// a ring head => a
							NetmapSlot.setBufIdx(slotA, NetmapSlot.getBufIdx(headASlot));
							NetmapSlot.setLen(slotA, NetmapSlot.getLen(headASlot));
							NetmapSlot.setFlags(slotA, NetmapSlot.getFlags(slotA) | Netmap.NS_BUF_CHANGED);
							// b ring head => b
							NetmapSlot.setBufIdx(slotB, NetmapSlot.getBufIdx(headBSlot));
							NetmapSlot.setLen(slotB, NetmapSlot.getLen(headBSlot));
							NetmapSlot.setFlags(slotB, NetmapSlot.getFlags(slotB) | Netmap.NS_BUF_CHANGED);

							locks.put(bufIdxHeadA, lockA);
							locks.put(bufIdxHeadB, lockB);
						}

						// b => a ring head
						NetmapSlot.setBufIdx(headASlot, sReq.getBufB().getBufIdx());
						NetmapSlot.setLen(headASlot, sReq.getBufB().getLen());
						NetmapSlot.setFlags(headASlot, NetmapSlot.getFlags(headASlot) | Netmap.NS_BUF_CHANGED);
						// advance a
						NetmapRing.setHead(lockA.ring.ring, NetmapRing.ringNext(lockA.ring.ring, headA));

						// a => b ring head
						NetmapSlot.setBufIdx(headBSlot, sReq.getBufA().getBufIdx());
						NetmapSlot.setLen(headBSlot, sReq.getBufA().getLen());
						NetmapSlot.setFlags(headBSlot, NetmapSlot.getFlags(headBSlot) | Netmap.NS_BUF_CHANGED);
						// advance b
						NetmapRing.setHead(lockB.ring.ring, NetmapRing.ringNext(lockB.ring.ring, headB));
					}
					bufferRefAlloc.free(sReq.getBufA());
					bufferRefAlloc.free(sReq.getBufB());
					bufferSwapSlotsAndUnlockReqAlloc.free(sReq);
				}
				else if(req instanceof RingWaitReq) {
					RingWaitReq rwReq = (RingWaitReq)req;
					
					ringWaits.put(rwReq.ring, rwReq.pipe);
				}
				else
					logger.error("unrecognized buffer unlocker req " + req.getClass());
				
				req = pipe.poll();
			} while (req != null);

			for(Netmapping.Ring ring: ringsTouched) {
				AsyncPipe<? super RingWaitResp> wpipe = ringWaits.remove(ring);
				
				if(wpipe != null)
					wpipe.write(rwResp);
			}
			ringsTouched.clear();
			
			rwLock.writeUnlock();
			
			return AsyncLoop.cfTrue;
		}, exec);
	}
}