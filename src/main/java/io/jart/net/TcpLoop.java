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

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.log4j.Logger;

import com.ea.async.Async;

import io.jart.async.AsyncEventQueue;
import io.jart.async.AsyncLoop;
import io.jart.async.AsyncPipe;
import io.jart.async.AsyncRunnable;
import io.jart.util.EventQueue;
import io.jart.util.ThreadAffinityExecutor;

/**
 * Implement the Tcp loop.
 */
public abstract class TcpLoop implements AsyncRunnable {
	private static final Logger logger = Logger.getLogger(TcpLoop.class);
	private static final CompletableFuture<Void> cfVoid = CompletableFuture.completedFuture(null);
	private static final Throwable thExit = new Throwable("exit msg");
	
	protected final TcpContext tcpContext;
	protected final int mss;
	protected final EventQueue eventQueue;
	protected final int startSeqNum;
	protected final Executor exec;

	protected final AsyncEventQueue.Event exitMsg;

	protected final AtomicInteger winSize = new AtomicInteger(8*1024*1024);
	private TcpOutgoing tcpOut;
	private short invalidCb, requiredCb;
	private boolean explicitAck; // we want to explicitly send an ack when appropriate
	protected byte winScale, peerWinScale;
	protected long ackNum;
	private Queue<TcpOutgoing.Segment> txQ = new LinkedList<TcpOutgoing.Segment>();

	/**
	 * TcpOutgoing associated with this loop.
	 *
	 * @return the tcp outgoing
	 */
	protected TcpOutgoing tcpOut() { return tcpOut; }

	/**
	 * Instantiates a new tcp loop.
	 *
	 * @param tcpContext the tcp context
	 * @param mss the tcp mss
	 * @param eventQueue the event queue for use with tcp work
	 * @param startSeqNum the start sequence number for tcp
	 * @param exec the Executor to run on
	 */
	public TcpLoop(TcpContext tcpContext, int mss, EventQueue eventQueue, int startSeqNum, Executor exec) {
		this.tcpContext = tcpContext;
		this.mss = mss;
		this.eventQueue = eventQueue;
		this.startSeqNum = startSeqNum;
		this.exec = new ThreadAffinityExecutor(exec);
		this.exitMsg = new AsyncEventQueue.Event(tcpContext.getPipe());
	}

	/**
	 * Called early on to allow subclasses to initialize.
	 * Sublcasses might want to adjust receive window size here.
	 */
	protected void init() {}
	
	/**
	 * Called when the connection is established.
	 */
	protected void connected() {}
	
	/**
	 * Called when terminating.
	 * Override me, but don't forget to call super.
	 */
	protected void term() {
		if(tcpOut != null)
			tcpOut.dispose();
		// make sure any in-flight startTx is handled -- there's no race
		// here because InetBufferSwapTask drains a second time at quiescence
		winSize.set(-1);
	}
	
	/**
	 * Do any disposal needed on objects still in the pipe at termination time.
	 * Override me, but don't forget to call sup.
	 *
	 * @param obj the obj
	 */
	public void disposeMsg(Object obj) {
		if(obj instanceof TxContext.Buffer)
			tcpContext.getTx().abort((TxContext.Buffer)obj);
	}
	
	/**
	 * Implement me -- handle recv of data and/or acknowledgement of sent data and/or
	 * fin recv or acknowledgement.
	 *
	 * @param src the received data
	 * @param acked the number of sent bytes acked by this packet
	 * @param fin true in this packet has the fin flag set
	 */
	protected abstract void recv(ByteBuffer src, int acked, boolean fin);
	
	/**
	 * Handle a messsage received on the pipe that is not handled by the TcpLoop.
	 *
	 * @param obj the obj
	 * @return true, if handled, false otherwise
	 */
	protected boolean handleMsg(Object obj) { return false; }

	/**
	 * Creates the TcpOutgoing for sending data.
	 * Override me to customize TcpOutgoing instantiation.
	 *
	 * @param pipe the pipe for tcp messages
	 * @param winSize the starting window size
	 * @param seqNum the tcp sequence num
	 * @return the tcp outgoing
	 */
	protected TcpOutgoing createTcpOut(AsyncPipe<Object> pipe, int winSize, long seqNum) {
		return new TcpOutgoing(eventQueue, pipe, mss, winSize, seqNum);
	}
	
	/**
	 * Finish dealing w/ an rx-ed packet.
	 *
	 * @param msg the msg
	 */
	protected void finishRx(Object msg) {
		tcpContext.finishRx(msg);
	}

	/**
	 * Handle a syn or syn/ack packet.
	 * Parse options.
	 *
	 * @param pkt the pkt
	 */
	protected void handleSyn(ByteBuffer pkt) {
		int pos = pkt.position();
		int limit = pkt.limit();
		int optionsPos = TcpPkt.optionsPos(pkt);

		pkt.limit(optionsPos + TcpPkt.optionsSize(pkt));
		pkt.position(optionsPos);

		try {
			while(pkt.hasRemaining()) {
				byte kind = pkt.get();

				if(kind == 0) // end of options
					break;
				else if(kind == 1) // nop
					continue;
				else {
					byte len = pkt.get();

					switch((kind << 8) | len) {
					case 0x303: // window scale
						peerWinScale = pkt.get();
						winScale = tcpOut().desiredWinScale();
						break;
					default:
						pkt.position(pkt.position() + len - 2);
						break;
					}
				}
			}
		}
		catch(BufferUnderflowException e) {
			logger.debug("underflow parsing options");
		}
		pkt.limit(limit);
		pkt.position(pos);
	}

	/**
	 * Get scaled window size.
	 *
	 * @return the int
	 */
	private int scaledWinSize() {
		return Math.min(65535, winSize.get() >> winScale);
	}

	/**
	 * Request a tx buffer.
	 *
	 * @param tx the tx context
	 * @param pipe the pipe to deliver the Buffer to
	 */
	private void requestTxBuffer(TcpTxContext tx, AsyncPipe<Object> pipe) {
		tx.startTx(pipe, (TxContext.Buffer buf)->{
			if(winSize.get() < 0) { // abort if we're known termed
				tx.abort(buf);
				return exitMsg;
			}
			return buf;
		}, exec);
	}

	/**
	 * Request several tx buffers.
	 *
	 * @param tx the tx context
	 * @param pipe the pipe to deliver the Buffers to
	 * @param n the number of buffers to request
	 */
	private void requestTxBuffer(TcpTxContext tx, AsyncPipe<Object> pipe, int n) {
		while(n-- > 0)
			requestTxBuffer(tx, pipe);
	}
	
	/**
	 * Output some packets.
	 *
	 * @param tx the tx
	 * @param pipe the pipe
	 */
	private void output(TcpTxContext tx, AsyncPipe<Object> pipe) {
		int n;
		
		if((n = tcpOut.output()) > 0)
			explicitAck = false;
		else if(explicitAck || ackNum != tx.getAckNum() || scaledWinSize() != tx.getWinSize()) {
			explicitAck = false;
			tcpOut.queueAck();
			n = tcpOut.output();
		}
		requestTxBuffer(tx, pipe, n);
	}

	/**
	 * Run tcp loop.
	 *
	 * @return the completable future completed on indicating connection close (or timeout)
	 */
	@Override
	public CompletableFuture<Void> run() {
		// handshake
		{
			Object msg = tcpContext.getPipe().poll();

			if(msg != null) { // server -- we're accepting a connection
				// handle syn / send syn-ack
				ByteBuffer pktBuf = tcpContext.rx(msg);

				if(pktBuf == null) {
					logger.error("non-packet or invalid packet on entry: " + msg.getClass());
					return AsyncLoop.cfVoid;
				}

				init();

				try {
					TcpTxContext tx = tcpContext.getTx();
					AsyncPipe<Object> pipe = tcpContext.getPipe();
					short peerDocb = TcpPkt.getDataOffsControlBits(pktBuf);
					long peerAckNum = TcpPkt.getAckNum(pktBuf);
					int peerWinSize = TcpPkt.getWinSize(pktBuf);

					ackNum = TcpPkt.getSeqNum(pktBuf) + 1;

					tcpContext.finishRx(msg);

					if((peerDocb & TcpPkt.SYN) == 0 || (peerDocb & (TcpPkt.ACK | TcpPkt.RST | TcpPkt.FIN)) != 0) {
						logger.debug("expecting syn, got: " + Integer.toHexString(peerDocb));

						// send reset
						tx.setControlBits((short) (TcpPkt.RST | TcpPkt.ACK));
						tx.setWinSize(0);								
						tx.setSeqNum(peerAckNum);
						tx.setAckNum(ackNum - 1);
						
						TxContext.Buffer buffer = Async.await(tx.startTx(exec));
						
						tx.use(buffer);
						tx.finish(buffer);

						return cfVoid;
					}

					tcpOut = createTcpOut(pipe, peerWinSize << peerWinScale, startSeqNum);
					handleSyn(pktBuf);
					tcpOut.queueSynAck();
					output(tx, pipe);

					invalidCb = TcpPkt.RST | TcpPkt.SYN;
					requiredCb = TcpPkt.ACK;
				}
				catch(Throwable th) {
					logger.warn("exception in tcp loop", th);
					term();
					return AsyncLoop.cfVoid;
				}
			}
			else { // client -- we're connecting
				init();

				try {
					TcpTxContext tx = tcpContext.getTx();
					AsyncPipe<Object> pipe = tcpContext.getPipe();

					tcpOut = createTcpOut(pipe, 0, startSeqNum);	
					tcpOut.queueSyn();
					output(tx, pipe);

					//  expecting syn/ack
					invalidCb = TcpPkt.RST;
					requiredCb = TcpPkt.SYN | TcpPkt.ACK;
				}
				catch(Throwable th) {
					logger.warn("exception in tcp loop", th);
					term();
					return AsyncLoop.cfVoid;
				}
			}
		}

		AsyncPipe<Object> pipe = tcpContext.getPipe();
		TcpTxContext tx = tcpContext.getTx();

		try {
			connected();
		}
		catch(Throwable th) {
			term();
			return AsyncLoop.cfVoid;
		}

		// main tcp loop!
		return AsyncLoop.iterate(()->pipe.read(exec), (Object msg)->{
			try {		
				do {
					ByteBuffer pktBuf = tcpContext.rx(msg);

					if(pktBuf != null) // received a packet
						handlePacket(msg, pktBuf);					
					else if(msg instanceof TcpOutgoing.Segment) // segment needs sending
						handleSegment(pipe, tx, (TcpOutgoing.Segment)msg);
					else if(msg instanceof TxContext.Buffer) // a tx buffer showed up
						handleBuffer(tx, (TxContext.Buffer)msg);
					else if(msg == exitMsg)
						throw thExit;
					else if(!handleMsg(msg))
						logger.warn("failed to handle message: " + msg.getClass());
					output(tx, pipe);
					msg = pipe.poll();
				} while(msg != null);
  						
				return true;
			}
			catch(Throwable th) {
				if(th != thExit)
					logger.warn("exception in tcp loop", th);
				term();
				return false;
			}
		}, exec);
	}

	/**
	 * Handle a buffer from the main loop.
	 *
	 * @param tx the tx context
	 * @param buffer the buffer we'll used to send a queued segment (or abort if no valid segments)
	 * @throws Exception
	 */
	protected void handleBuffer(TcpTxContext tx, TxContext.Buffer buffer) throws Exception {
		try {
			// try to find a segment that still needs sending
			for(;;) {
				TcpOutgoing.Segment segment = txQ.poll();
				
				if(segment == null) { // no segments need sending!
					tx.abort(buffer);
					break;
				}
				if(tcpOut.readyTx(tx, segment)) {
					tx.setAckNum(ackNum);
					tx.setWinSize(scaledWinSize());

					ByteBuffer dst = tx.use(buffer);
					short controlBits = tcpOut.putToTx(dst, tx, segment);

					tx.finish(controlBits, buffer);
					explicitAck = false;
					break;
				}
			}
		}
		catch(Throwable th) {
			tx.abort(buffer);
			throw th;
		}
	}

	/**
	 * Handle an outgoing tcp segment from the main loop.
	 *
	 * @param pipe the main pipe
	 * @param tx the tx context
	 * @param segment the outgoing segment
	 */
	protected void handleSegment(AsyncPipe<Object> pipe, TcpTxContext tx, TcpOutgoing.Segment segment) {
		if(tcpOut.isValid(segment)) { // discard expired segments
			txQ.offer(segment);
			if(segment.isRetry()) // no buffer pre-request for retries so request now
				requestTxBuffer(tx, pipe);
		}
	}

	/**
	 * Handle a packet received from the main loop.
	 *
	 * @param msg the message from which we got pktBuf
	 * @param pktBuf the buffer holding the packet
	 */
	protected void handlePacket(Object msg, ByteBuffer pktBuf) {
		try {
			boolean wasFinAcked = tcpOut.finAcked();
			short peerDocb = TcpPkt.getDataOffsControlBits(pktBuf);
			short synFin = (short) (peerDocb & (TcpPkt.SYN | TcpPkt.FIN));						
			long peerSeqNum = TcpPkt.getSeqNum(pktBuf);
			int dseq = ((synFin & TcpPkt.SYN) != 0) ? 0 : (int)peerSeqNum - (int)ackNum;
			int headerSize = TcpPkt.headerSize(pktBuf);
			int size = pktBuf.remaining() - headerSize;

			if(dseq <= 0 && -dseq <= size) { // next packet
				size += dseq; // account for overlap

				int acked = (size <= winSize.get()) ? // ignore anything that would blow out our recv window
						tcpOut.update(TcpPkt.getAckNum(pktBuf), TcpPkt.getWinSize(pktBuf) << peerWinScale) : -1;

				if(acked >= 0) { // for us
					// check for validity
					if((peerDocb & requiredCb) != requiredCb || (peerDocb & invalidCb) != 0)
						throw new IllegalStateException("unexpected control bits: " + Integer.toHexString(peerDocb));

					// call recv if anything meaningful happened
					if(((size > 0) || (acked > 0) || (synFin != 0) ||
							(!wasFinAcked && tcpOut.finAcked()))) {
						if((synFin & TcpPkt.SYN) != 0) { // syn/ack
							// need to adopt seqnum from a syn/ack
							ackNum = peerSeqNum;
							handleSyn(pktBuf);
						}

						// don't want to see these twice
						invalidCb |= synFin;
						requiredCb &= ~synFin;
						winSize.addAndGet(-size);
						ackNum = 0xffffffffL & (ackNum + size + Math.min(1, synFin));

						// skip header
						pktBuf.position(pktBuf.position() + headerSize - dseq);
						recv(pktBuf, acked, (synFin & TcpPkt.FIN) != 0);
					}
				}
			}
			else if(dseq > 0) // future packet -- packet lost?
				explicitAck = true; // provoke an explicit ack
		}
		finally {
			finishRx(msg);
		}
	}
}
