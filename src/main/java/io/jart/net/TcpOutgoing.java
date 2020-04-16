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
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.log4j.Logger;

import io.jart.async.AsyncEventQueue;
import io.jart.async.AsyncPipe;
import io.jart.util.ByteChunk;
import io.jart.util.ByteChunker;
import io.jart.util.EventQueue;

// all timings in microseconds
public class TcpOutgoing {
	private final static Logger logger = Logger.getLogger(TcpOutgoing.class);

	public static interface Segment {
		public boolean isRetry();
	}
	
	static abstract class ActualSegment extends AsyncEventQueue.Event implements Segment {
		ActualSegment next; // next unacknowledged segment
		final int seqNum;
		short txCount; // 0 means unsent, negative means ignore

		ActualSegment(AsyncPipe<Object> segPipe, int seqNum) {
			super(segPipe);
			this.seqNum = seqNum;
		}
	
		@Override
		public boolean isRetry() { return true; }
		
		abstract short controlBits();
		void readyTx(TcpTxContext tx) {
			tx.setSeqNum(seqNum);
		}
		void putTo(ByteBuffer dst, TcpTxContext tx) {}
		boolean needsAck() { return true; }
		void dispose() {}
	}
	
	static class SynSegment extends ActualSegment {
		private final byte winScale;
		
		SynSegment(AsyncPipe<Object> segPipe, int seqNum, byte winScale) {
			super(segPipe, seqNum);
			this.winScale = winScale;
		}
		
		@Override
		short controlBits() { return TcpPkt.SYN; }

		public static void putOptionsTo(ByteBuffer dst, TcpTxContext tx, byte winScale) {
			dst.put((byte)3); // kind
			dst.put((byte)3); // length
			dst.put(winScale);
			dst.put((byte)0); // end of options
			tx.finishOptions();			
		}
		
		@Override
		void putTo(ByteBuffer dst, TcpTxContext tx) {
			putOptionsTo(dst, tx, winScale);
			super.putTo(dst, tx);
		}
	}
	
	static class SynAckSegment extends SynSegment {
		SynAckSegment(AsyncPipe<Object> segPipe, int seqNum, byte winScale) {
			super(segPipe, seqNum, winScale);
		}
		
		@Override
		short controlBits() { return TcpPkt.SYN | TcpPkt.ACK; }
	}
	
	static class DataSegment extends ActualSegment {
		public final ByteChunk[] byteChunks;
		public final boolean psh;
		
		DataSegment(AsyncPipe<Object> segPipe, int seqNum, ByteChunk[] byteChunks, boolean psh) {
			super(segPipe, seqNum);
			this.byteChunks = byteChunks;
			this.psh = psh;
		}
		
		@Override
		short controlBits() { return (short) (TcpPkt.ACK | (psh ? TcpPkt.PSH : 0)); }
		
		@Override
		void putTo(ByteBuffer dst, TcpTxContext tx) {
			for(ByteChunk bc: byteChunks)
				bc.putTo(dst);
		}
		
		@Override
		boolean needsAck() {
			return byteChunks.length > 0; // don't retry empty ack
		}
		
		@Override
		void dispose() {
			for(int i = 0; i < byteChunks.length; i++) {
				byteChunks[i].dispose();
				byteChunks[i] = null;
			}
		}		
	}

	static class FinSegment extends DataSegment {
		FinSegment(AsyncPipe<Object> segPipe, int seqNum, ByteChunk[] byteChunks) {
			super(segPipe, seqNum, byteChunks, true);
		}
		
		@Override
		short controlBits() { return TcpPkt.ACK | TcpPkt.FIN; }

		@Override
		boolean needsAck() { return true; }
	}
	
	static abstract class VirtualSegment implements Segment {		
		@Override
		public boolean isRetry() { return false; }
		
		void readyTx(TcpTxContext tx, TcpOutgoing out) {
			tx.setSeqNum(out.seqNum);
		}
		abstract ActualSegment actualizeTo(ByteBuffer dst, TcpTxContext tx, TcpOutgoing out);
		abstract int flagsMask();
	}
	
	static class VirtualSynSegment extends VirtualSegment {
		@Override
		ActualSegment actualizeTo(ByteBuffer dst, TcpTxContext tx, TcpOutgoing out) {
			SynSegment.putOptionsTo(dst, tx, out.desiredWinScale());
			return new SynSegment(out.pipe, out.seqNum, out.desiredWinScale());
		}
		
		@Override
		int flagsMask() { return ~SYN_SEG_QUEUED; }
	}
	
	static class VirtualSynAckSegment extends VirtualSynSegment {
		@Override
		ActualSegment actualizeTo(ByteBuffer dst, TcpTxContext tx, TcpOutgoing out) {
			SynSegment.putOptionsTo(dst, tx, out.desiredWinScale());
			return new SynAckSegment(out.pipe, out.seqNum, out.desiredWinScale());
		}
		
		@Override
		int flagsMask() { return ~SYN_ACK_SEG_QUEUED; }
	}
	
	static class VirtualDataSegment extends VirtualSegment {
		ByteChunk[] chunkTo(ByteBuffer dst, TcpOutgoing out) {
			int chunkIndex = 0;
			ByteChunk[] chunkArray = out.chunkArray;
			ByteChunker bc = out.curChunker();
			
			while(bc != null && dst.hasRemaining()) {
				if(chunkIndex >= chunkArray.length) {
					ByteChunk[] newChunkArray = new ByteChunk[chunkArray.length * 2];
					
					System.arraycopy(chunkArray, 0, newChunkArray, 0, chunkArray.length);
					chunkArray = out.chunkArray = newChunkArray;
				}
				if(!bc.chunkTo(dst, chunkArray, chunkIndex++))
					bc = out.nextChunker();
			}
			return Arrays.copyOfRange(chunkArray, 0, chunkIndex);
		}
		
		@Override
		ActualSegment actualizeTo(ByteBuffer dst, TcpTxContext tx, TcpOutgoing out) {
			return new DataSegment(out.pipe, out.seqNum, chunkTo(dst, out), out.isEmpty());
		}
		
		@Override
		int flagsMask() { return ~DATA_SEG_QUEUED; }
	}
	
	static class VirtualFinSegment extends VirtualDataSegment {
		@Override
		ActualSegment actualizeTo(ByteBuffer dst, TcpTxContext tx, TcpOutgoing out) {
			ActualSegment segment = new FinSegment(out.pipe, out.seqNum, chunkTo(dst, out));

			if(!out.isEmpty()) {
				logger.error("data queued after fin -- discarding");
				while(out.nextChunker() != null);
			}
			return segment;
		}
		
		@Override
		int flagsMask() { return ~FIN_SEG_QUEUED; }
	}

	@SuppressWarnings("serial")
	public static class Exception extends java.lang.Exception {
		public Exception() {
			super();
		}
		public Exception(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
			super(message, cause, enableSuppression, writableStackTrace);
		}
		public Exception(String message, Throwable cause) {
			super(message, cause);
		}
		public Exception(String message) {
			super(message);
		}
		public Exception(Throwable cause) {
			super(cause);
		}
	}
	
	@SuppressWarnings("serial")
	public final class RetryExceededException extends TcpOutgoing.Exception {}
	
	// "virtual" segments that don't become actual until tx time
	private static final Segment virtSynSeg = new VirtualSynSegment();
	private static final Segment virtSynAckSeg = new VirtualSynAckSegment();
	private static final Segment virtDataSeg = new VirtualDataSegment();
	private static final Segment virtFinSeg = new VirtualFinSegment();
	
	// override me
	// various basic settings
	protected byte desiredWinScale() { return 9; }
	protected short maxRetries() { return 15; }
	protected int minTimeout() { return 500; }
	protected int maxTimeout() { return 15*1000*1000; }
	
	protected final EventQueue eventQueue;
	protected final AsyncPipe<Object> pipe;
	protected final int mss;
	protected int winSize;
	protected int seqNum;
	
	// virtual segments waiting to be queued
	private static int SYN_SEG_WAITING = 0x1;
	private static int SYN_ACK_SEG_WAITING = 0x2;
	private static int DATA_SEG_WAITING = 0x4;
	private static int FIN_SEG_WAITING = 0x8;
	
	// virtual segments queued (in pipe)
	private static int SYN_SEG_QUEUED = 0x100;
	private static int SYN_ACK_SEG_QUEUED = 0x200;
	private static int DATA_SEG_QUEUED = 0x400;
	private static int FIN_SEG_QUEUED = 0x800;
	
	// got an ack for our fin
	private static int FIN_ACKED = 0x80000000;
	private static int FIN_OUT = 0x40000000;
	
	// basic state
	private int flags;
	protected int lastAck;
	protected int unackSize; // also includes sent syn/fin
	protected ActualSegment unackHead, unackTail;
	private ByteChunk[] chunkArray = new ByteChunk[4];
	private ByteChunker curChunker;
	
	private ByteChunker nextChunker() {
		if(curChunker != null)
			curChunker.dispose();
		return curChunker = sendQ.poll();
	}

	private ByteChunker curChunker() {
		return (curChunker != null) ?
				curChunker : nextChunker();
	}
	
	// rtt measurement
	private int rtt = 50*1000;
	private int rttvar = 0;
	private int srtt = rtt;
	private ActualSegment rttSeg;
	private long rttSegTime;
	
	// effective useable win size (rfc813)
	private int euWinSize() {
		int uWinSize = winSize - unackSize;

		return (uWinSize < winSize >> 2) ? 0 : uWinSize;
	}

	// rfc6298
	private int rto() { return srtt + 4 * rttvar; }

	private void cancel(ActualSegment segment) {
		eventQueue.remove(segment);
		segment.txCount = -1;
		segment.dispose();
	}
	
	// returns any syn/fin control bits
	private short acked(ActualSegment segment) {
		if(segment == rttSeg) {
			int newRtt = (int) (System.nanoTime()/1000 - rttSegTime);
			// rfc6298
			rttvar = (rttvar * 3 + Math.abs(rtt - newRtt) + 3) >>> 2;
			srtt = (srtt * 7 + newRtt + 7) >>> 3;
			rtt = newRtt;
			rttSeg = null;
		}
		cancel(segment);
		
		short synFin = (short) (segment.controlBits() & (TcpPkt.SYN | TcpPkt.FIN));
		
		flags |= ((synFin & TcpPkt.FIN) != 0) ? FIN_ACKED : 0;
		return synFin;
	}
	
	protected void fastReTx() {
		ActualSegment unackCur = unackHead;

		while(unackCur != null) {
			eventQueue.remove(unackCur);
			pipe.write(unackCur);
			unackCur = unackCur.next;
		}
	}

	// congestion control -- override me
	protected int maxPktsOut() { return Integer.MAX_VALUE; } // how many packets can we output?
	protected void pktsOut(int n) {} // note n packets output
	protected void pktsAcked(int n, long rtt) {} // note n packets acked
	protected void pktDupeAcked(int seqNum) {} // note we saw a dupe ack
	protected void pktTimeout() {} // note we saw a packet timeout
	
	public final Queue<ByteChunker> sendQ; // queue of stuff to senf

	// override me
	protected void txPut(int n) {} // put this many bytes into a tx buffer (maybe useful to track send q)
	
	public TcpOutgoing(EventQueue eventQueue, AsyncPipe<Object> pipe, int mss, int winSize, long seqNum, Queue<ByteChunker> sendQ) {
		this.eventQueue = eventQueue;
		this.pipe = pipe;
		this.mss = mss;
		this.winSize = winSize;
		this.seqNum = (int)seqNum;
		this.sendQ = (sendQ == null) ? new ConcurrentLinkedQueue<ByteChunker>() : sendQ;
	}
	
	public TcpOutgoing(EventQueue eventQueue, AsyncPipe<Object> pipe, int mss, int winSize, long seqNum) {
		this(eventQueue, pipe, mss, winSize, seqNum, null);
	}
	
	public void dispose() {
		while(unackHead != null) {
			cancel(unackHead);
			unackHead = unackHead.next;
		}
		if(curChunker != null)
			curChunker.dispose();
	}
	
	public boolean isEmpty() { return curChunker() == null; }
	public int unacked() { return unackSize; } // current unacked
	public int winSize() { return winSize; }
	public boolean finAcked() { return (flags & FIN_ACKED) != 0; } // have we seen our fin acked?
	public boolean finOut() { return (flags & FIN_OUT) != 0; }
	
	// output packets if we can
	public int output() {
		int eflags = flags | ((euWinSize() > 0 && !isEmpty()) ? DATA_SEG_WAITING : 0);

		int max = maxPktsOut();
		int n = 0;

		if((eflags & SYN_SEG_WAITING) != 0 && n < max) {
			n++;
			flags = (flags & ~SYN_SEG_WAITING) | SYN_SEG_QUEUED;
			pipe.write(virtSynSeg);
		}
		if((eflags & SYN_ACK_SEG_WAITING) != 0 && n < max) {
			n++;
			flags = (flags & ~SYN_ACK_SEG_WAITING) | SYN_ACK_SEG_QUEUED;
			pipe.write(virtSynAckSeg);
		}
		if((eflags & DATA_SEG_WAITING) != 0 && (eflags & DATA_SEG_QUEUED) == 0 && n < max) {
			n++;
			flags = (flags & ~DATA_SEG_WAITING) | DATA_SEG_QUEUED;
			pipe.write(virtDataSeg);
		}
		if((eflags & FIN_SEG_WAITING) != 0 && n < max) {
			n++;
			flags = (flags & ~FIN_SEG_WAITING) | FIN_SEG_QUEUED;
			pipe.write(virtFinSeg);
		}
		return n;
	}
	
	// update internal state w/ new ackNum/winSize from received packet
	// returns # bytes acknowledged or -1 if ack is bad
	public int update(long ackNum, int winSize) {
		int acked = unackSize - (seqNum - (int)ackNum);
		
		// check if in our unacked window
		if(acked < 0 || acked > unackSize) {
			if((int)ackNum == lastAck)
				pktDupeAcked((int)ackNum);
			return -1; // doesn't appear to be for us
		}

		ActualSegment ackedEnd;

		if(acked == unackSize) { // acked all packets
			ackedEnd = null;
			unackTail = null;
		}
		else { // acked some (or bad?)
			ackedEnd = unackHead;

			// find first unack seg
			for(;;) {
				if(ackedEnd == null)
					return -1; // didn't find a matching segment so not for us?
				if((int)ackNum == ackedEnd.seqNum)
					break;
				ackedEnd = ackedEnd.next;
			}
		}
		unackSize -= acked; // adjust before we subtract syn/fin

		int pktsAcked = 0;

		// chase down acked segments
		while(unackHead != ackedEnd) {
			// syn/fin seqnum incr doesn't count towards bytes acked
			acked -= Math.min(1, acked(unackHead));
			unackHead = unackHead.next;
			pktsAcked++;
		}
		lastAck = (int)ackNum;
		this.winSize = winSize;
		pktsAcked(pktsAcked, srtt);

		return acked;
	}
	
	// queue various special packets
	// these "jump" the sendQ!
	public void queueSyn() {
		flags |= SYN_SEG_WAITING;
	}
	
	public void queueSynAck() {
		flags |= SYN_ACK_SEG_WAITING;
	}
	
	public void queueAck() {
		// we'll happily send empty data segments as pure acks
		flags |= DATA_SEG_WAITING;
	}

	public void queueFin() {
		flags |= (FIN_SEG_WAITING | FIN_OUT);
	}

	// checks a segment for tx
	// returns true to tx , false to ignore
	// throws exceptions for non-recoverable errors
	public boolean readyTx(TcpTxContext tx, Segment segment) throws RetryExceededException {
		if(segment instanceof VirtualSegment) {
			((VirtualSegment)segment).readyTx(tx, this);
			return true;
		}

		ActualSegment actualSegment = (ActualSegment)segment;
		
		if(actualSegment.txCount < 0) // cancelled
			return false;
		if(actualSegment.txCount > maxRetries())
			throw new RetryExceededException();
		actualSegment.readyTx(tx);
		return true;
	}

	// check if a segment is ok to tx
	public boolean isValid(Segment segment) {
		return !(segment instanceof ActualSegment) || (((ActualSegment)segment).txCount >= 0);
	}
	
	// put the segment to the dst for purpose of tx
	public short putToTx(ByteBuffer dst, TcpTxContext tx, Segment segment) {
		ActualSegment actualSegment;
		
		if(segment instanceof VirtualSegment) {
			VirtualSegment virtSegment = (VirtualSegment)segment;
			int size = Math.min(mss, euWinSize());
			
			txPut(size);
			dst.limit(dst.position() + size);
			actualSegment = virtSegment.actualizeTo(dst, tx, this);
			flags &= virtSegment.flagsMask();
		}
		else {
			actualSegment = (ActualSegment)segment;
			actualSegment.putTo(dst, tx);
		}

		int size = tx.payloadSize() +
				Math.min(1, actualSegment.controlBits() & (TcpPkt.SYN | TcpPkt.FIN));

		if(actualSegment.needsAck()) {
			long now = System.nanoTime()/1000;

			if(actualSegment.txCount == 0) {
				seqNum += size;
				unackSize += size;
				// put in unack list
				if(unackTail != null)
					unackTail = unackTail.next = actualSegment;
				else
					unackHead = unackTail = actualSegment;
				if(rttSeg == null) { // if not tracking a segment for rtt, track this one
					rttSeg = actualSegment;
					rttSegTime = now;
				}
			}
			else {
				// re-tx... no longer a fit for rtt tracking
				if(rttSeg == actualSegment)
					rttSeg = null;
				pktTimeout();
			}

			long timeout = Math.max(minTimeout(), Math.min(maxTimeout(),
					(1 << actualSegment.txCount++) * rto()));

			eventQueue.update(actualSegment, now + timeout);
			pktsOut(1);
		}
		else {
			seqNum += size;
			unackSize += size;
		}
		return actualSegment.controlBits();
	}
}
