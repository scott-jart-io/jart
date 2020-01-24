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

public abstract class TcpConnection extends TcpLoopCubic {
	private static final Logger logger = Logger.getLogger(TcpConnection.class);

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
	
	// exec is TcpLoop executor
	// connExec is executor to run non-realtime connection tasks
	public TcpConnection(TcpContext tcpContext, int mss, int writeBufSize, EventQueue eventQueue, MsgRelay msgRelay, int startSeqNum, Executor exec, Executor connExec) {
		super(tcpContext, mss, eventQueue, startSeqNum, exec);
		abbr = new AsyncByteBufferPipe(connExec);
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

	public TcpConnection(TcpContext tcpContext, int mss, EventQueue eventQueue, MsgRelay msgRelay, int startSeqNum, Executor exec, Executor connExec) {
		this(tcpContext, mss, 20*1024*1024, eventQueue, msgRelay, startSeqNum, exec, connExec);
	}
	
	@Override
	protected void connected() {
		ping();
		privateRun();
	}
	
	private CompletableFuture<Void> privateRun() {
		try {
			Async.await(connectionRun());
		}
		catch(Throwable th) {
			logger.error("connectionRun threw", th);
		}
		return close();
	}

	protected Executor executor() { return abbr.executor(); }
	protected abstract CompletableFuture<Void> connectionRun();
	
	private void ping() {
		eventQueue.update(exitMsg, System.nanoTime()/1000 + 15*1000*1000);		
	}

	@Override
	protected boolean handleMsg(Object msg) {
		if(msg == updateMsg) {
			updatePending.set(false);
			return true;
		}
		return false;
	}
	
	// thread-safe update -- can be called from any thread in any state
	// call after send() or winSize update from outside of connectionRun
	protected void update() {
		if(!updatePending.getAndSet(true))
			msgRelay.relay(tcpContext.getPipe(), updateMsg);
	}

	protected CompletableFuture<Void> shutdown() {
		shuttingDown = true;
		return shutdown;
	}
	
	protected CompletableFuture<Void> close() {
		return shutdown().thenRun(()->{
			closing = true;
		});
	}

	@Override
	protected void recv(ByteBuffer src, int acked, boolean fin) {
		PausableExecutor pexec = abbr.executor();
		
		pexec.pause();
		writerBuffer.written(acked);
		if(!finSeen) {
			abbr.write(src); // will resume pexec
			if(fin) {
				finSeen = true;
				abbr.write(null);
			}
		}
		else
			pexec.resumeSync();
		if(shuttingDown) {
			TcpOutgoing tcpOut = tcpOut();
			
			if(!tcpOut.finOut()) {
				if(tcpOut.isEmpty())
					tcpOut.queueFin();
			}
			else if(!shutdown.isDone() && tcpOut.finAcked())
				shutdown.complete(null);
		}
		if(!closing)
			ping();
	}

	protected AsyncByteBufferReader getReader() {
		return reader;
	}

	protected AsyncByteBufferReader getRawReader() {
		return abbr;
	}

	protected AsyncByteWriter getWriter() {
		return writerBuffer;
	}
}
