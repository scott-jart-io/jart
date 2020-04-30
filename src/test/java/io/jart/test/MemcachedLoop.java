package io.jart.test;
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

import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

import io.jart.async.AsyncPipe;
import io.jart.memcached.Memcached;
import io.jart.memcached.Memcached.Key;
import io.jart.memcached.Memcached.Value;
import io.jart.net.TcpContext;
import io.jart.net.TcpLoopCubic;
import io.jart.net.TcpOutgoing;
import io.jart.util.ByteChunker;
import io.jart.util.EventQueue;

/**
 * Async memcached implementation based on TcpLoop (which is lower leven than TcpConnection).
 */
// sync memcached implementation
public class MemcachedLoop extends TcpLoopCubic {
	private final Queue<ByteChunker> sendQ = new ConcurrentLinkedQueue<ByteChunker>();
	private final Memcached.StateMachineSession sess;
	
	/**
	 * Instantiates a new memcached loop.
	 *
	 * @param map key value store
	 * @param tcpContext the tcp context
	 * @param mss the tcp mss
	 * @param eventQueue the event queue to use for tcp bookkeeping
	 * @param startSeqNum the starting sequence num
	 * @param exec the Executor for tcp work
	 */
	public MemcachedLoop(Map<Key, Value> map, TcpContext tcpContext, int mss, EventQueue eventQueue, int startSeqNum, Executor exec) {
		super(tcpContext, mss, eventQueue, startSeqNum, exec);
		try {
			// instatiate the StateMachineSession
			this.sess = new Memcached.StateMachineSession(map, sendQ);
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Creates the TcpOutgoing to handle sending packets.
	 *
	 * @param pipe the pipe
	 * @param winSize the win size
	 * @param seqNum the seq num
	 * @return the TcpOutgoing
	 */
	protected TcpOutgoing createTcpOut(AsyncPipe<Object> pipe, int winSize, long seqNum) {
		return new TcpOutgoing(eventQueue, pipe, mss, winSize, seqNum, sendQ);
	}

	/**
	 * Kick -- keep our watchdog happy.
	 */
	private void kick() {
		eventQueue.update(exitMsg, System.nanoTime()/1000 + 15*1000*1000);		
	}
	
	private boolean done;
	
	/**
	 * Handle received data / acks / fins / etc.
	 *
	 * @param src incoming data 
	 * @param acked the number bytes acked by peer
	 * @param fin is does this packet have fin set?
	 */
	@Override
	protected void recv(ByteBuffer src, int acked, boolean fin) {
		if(!done) {
			// immediately restore the window since we handle data synchronously
			winSize.addAndGet(src.remaining());
			// hand the data off to the session
			done = !sess.recv(src) | fin;
			kick();
		}
		// if we're trying to finish and we haven't sent a fin yet and our output queue is empty...
		if(done && !tcpOut().finOut() && tcpOut().isEmpty())
			tcpOut().queueFin(); // queue a fin to send
	}
}
