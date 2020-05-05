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

package io.jart.net;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;

import org.apache.log4j.Logger;

import com.ea.async.Async;

import io.jart.async.AsyncByteBufferPipe;
import io.jart.async.AsyncByteBufferReader;
import io.jart.async.AsyncByteWriter;
import io.jart.async.AsyncByteWriterBuffer;
import io.jart.netmap.bridge.MsgRelay;
import io.jart.util.ByteArrayChunker;
import io.jart.util.ByteBufferChunker;
import io.jart.util.EventQueue;
import io.jart.util.PausableExecutor;

/**
 * Extends a TcpLoop(Cubic) with simple async read and write.
 */
public abstract class TcpConnection extends TcpLoopCubic {
	private static final Logger logger = Logger.getLogger(TcpConnection.class);

	/**
	 * Message to send to our pipe to update our state.
	 */
	private static class UpdateMsg {};
	private static final UpdateMsg updateMsg = new UpdateMsg();

	private final AsyncByteBufferPipe abbr;
	private final AsyncByteBufferReader reader;
	private final AsyncByteWriterBuffer writerBuffer;
	private final MsgRelay msgRelay;
	private final AtomicBoolean updatePending = new AtomicBoolean();
	private boolean finSeen;
	private boolean shuttingDown;
	private boolean closing;
	private CompletableFuture<Void> shutdown = new CompletableFuture<Void>();
	
	/**
	 * Instantiates a new tcp connection.
	 *
	 * @param tcpContext the tcp context
	 * @param mss the tcp mss
	 * @param writeBufSize the write buffer size
	 * @param eventQueue the event queue for tcp work
	 * @param msgRelay the msg relay for safe unsynchronized message passing
	 * @param startSeqNum the start sequence num for tcp
	 * @param exec the Executor for tcp work
	 * @param connExec the Executor for non-tcp work
	 */
	public TcpConnection(TcpContext tcpContext, int mss, int writeBufSize, EventQueue eventQueue, MsgRelay msgRelay, int startSeqNum, Executor exec, Executor connExec) {
		super(tcpContext, mss, eventQueue, startSeqNum, exec);
		// "raw" reader -- doesn't update window size
		abbr = new AsyncByteBufferPipe(connExec);
		// reader -- updates tcp window size as data is consumed
		this.reader = new AsyncByteBufferReader() {
			@Override
			public CompletableFuture<Void> read(BiPredicate<ByteBuffer, Boolean> consumer) {
				return abbr.read((ByteBuffer buf, Boolean needsCopy)->{
					if(buf == null)
						return consumer.test(null, false);

					int pos = buf.position();
					
					try {
						return consumer.test(buf, needsCopy);
					}
					finally {
						winSize.addAndGet(buf.position() - pos);
					}
				});
			}
		};
		// writer -- adds submitted data to the TcpOutgoing's send queue
		this.writerBuffer = new AsyncByteWriterBuffer(writeBufSize) {
			@Override
			protected void submit(ByteBuffer buf) {
				tcpOut().sendQ.offer(new ByteBufferChunker(buf));
				update();
			}
			@Override
			protected void submit(byte[] bytes, int offs, int len) {
				tcpOut().sendQ.offer(new ByteArrayChunker(bytes, offs, len));
				update();
			}
		};
		this.msgRelay = msgRelay;
	}

	/**
	 * Instantiates a new tcp connection with sa default write buffer size of 20MB.
	 *
	 * @param tcpContext the tcp context
	 * @param mss the tcp mss
	 * @param eventQueue the event queue for tcp work
	 * @param msgRelay the msg relay for safe unsynchronized message passing
	 * @param startSeqNum the start sequence num for tcp
	 * @param exec the Executor for tcp work
	 * @param connExec the Executor for non-tcp work
	 */
	public TcpConnection(TcpContext tcpContext, int mss, EventQueue eventQueue, MsgRelay msgRelay, int startSeqNum, Executor exec, Executor connExec) {
		this(tcpContext, mss, 20*1024*1024, eventQueue, msgRelay, startSeqNum, exec, connExec);
	}
	
	/**
	 * Basic setup at time of connection.
	 */
	@Override
	protected void connected() {
		kick();
		privateRun();
	}
	
	/**
	 * Internal run logic -- runs connectionRun and close()s after connectionRun completes.
	 *
	 * @return the completable future
	 */
	private CompletableFuture<Void> privateRun() {
		try {
			Async.await(connectionRun());
		}
		catch(Throwable th) {
			logger.error("connectionRun threw", th);
		}
		return close();
	}

	/**
	 * Executor associated with our raw reader.
	 *
	 * @return the executor
	 */
	protected Executor executor() { return abbr.executor(); }
	
	/**
	 * Implement me -- connection handling code should run here.
	 *
	 * @return the completable future
	 */
	protected abstract CompletableFuture<Void> connectionRun();
	
	/**
	 * Kick out watchdog timer.
	 */
	protected void kick() {
		eventQueue.update(exitMsg, System.nanoTime()/1000 + 15*1000*1000);		
	}

	/**
	 * Handle msg.
	 *
	 * @param msg the msg
	 * @return true, if successful
	 */
	@Override
	protected boolean handleMsg(Object msg) {
		if(msg == updateMsg) {
			updatePending.set(false);
			return true;
		}
		return false;
	}
	
	/**
	 * Thread-safe update -- can be called from any thread in any state to cause the connection to update itself.
	 * Call after send() or winSize update from outside of connectionRun.
	 */
	protected void update() {
		if(!updatePending.getAndSet(true))
			msgRelay.relay(tcpContext.getPipe(), updateMsg);
	}

	/**
	 * Shutdown connection.
	 *
	 * @return the completable future which completes when shutdown is complete
	 */
	protected CompletableFuture<Void> shutdown() {
		shuttingDown = true;
		return shutdown;
	}
	
	/**
	 * Close a connection.
	 *
	 * @return the completable future which completes when close is complete
	 */
	protected CompletableFuture<Void> close() {
		return shutdown().thenRun(()->{
			closing = true;
		});
	}

	/**
	 * Implement TcpLoop receive.
	 *
	 * @param src the src
	 * @param acked the acked
	 * @param fin the fin
	 */
	@Override
	protected void recv(ByteBuffer src, int acked, boolean fin) {
		PausableExecutor pexec = abbr.executor();
		
		pexec.pause(); // pause the reader's executor so blocked async read can execute synchronously
		writerBuffer.written(acked); // tell writerBuffer we've fully written acked number of bytes
		if(!finSeen) {
			// if we've not already seen a fin already, pass on received bytes to the reader
			if(src.hasRemaining())
				abbr.write(src); // will resume pexec
			else if(!fin)
				pexec.resumeSync();
			if(fin) {
				// if this packet represents a fin, remember it
				finSeen = true;
				// and close the reader
				abbr.write(null); // will resume pexec
			}
		}
		else
			pexec.resumeSync();
		if(shuttingDown) {
			TcpOutgoing tcpOut = tcpOut();
			
			if(!tcpOut.finOut()) { // shutting down but haven't sent fin yet as TcpOutgoing q wasn't emoty
				if(tcpOut.isEmpty()) // empty now, so queue up a fin
					tcpOut.queueFin();
			}
			else if(!shutdown.isDone() && tcpOut.finAcked()) // if we haven't already marked shutdown complete and we got a fin, mark it now!
				shutdown.complete(null);
		}
		if(!closing) // if we're not actively closing then kick the watchdog when we receive a packet
			kick();
	}

	/**
	 * Gets the reader.
	 * Updates tcp window size on read to reflect that we've consumed any read data.
	 *
	 * @return the reader
	 */
	protected AsyncByteBufferReader getReader() {
		return reader;
	}

	/**
	 * Gets the "raw" reader.
	 * Tcp window size is NOT updated on reads. If using the raw reader, don't forget to eventually update the tcp window size.
	 *
	 * @return the raw reader
	 */
	protected AsyncByteBufferReader getRawReader() {
		return abbr;
	}

	/**
	 * Gets the writer.
	 *
	 * @return the writer
	 */
	protected AsyncByteWriter getWriter() {
		return writerBuffer;
	}
}
