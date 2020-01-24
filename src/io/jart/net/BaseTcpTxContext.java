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
import java.util.function.Function;

import io.jart.async.AsyncPipe;

public class BaseTcpTxContext implements TcpTxContext {
	private final IpTxContext txCtx;
	private final short srcPort;
	private final short dstPort;
	protected ByteBuffer tcpBuf;
	protected int tcpBufPos;
	protected int seqNum, ackNum;
	protected short controlBits;
	protected short winSize;
	protected short urgPtr;
	
	public BaseTcpTxContext(IpTxContext txCtx, int srcPort, int dstPort) {
		this.txCtx = txCtx;
		this.srcPort = (short)srcPort;
		this.dstPort = (short)dstPort;
	}

	@Override
	public CompletableFuture<Buffer> startTx(Executor exec) {
		return txCtx.startTx(exec);
	}

	@Override
	public<D, O extends D> void startTx(AsyncPipe<D> dst, Function<Buffer, O> fun, Executor exec) {
		txCtx.startTx(dst, fun, exec);
	}

	@Override
	public Buffer tryStartTx() {
		return txCtx.tryStartTx();
	}

	// csum by default (could stub out for hardware csum)
	protected void setCsum() {
		int len = tcpBuf.position() - tcpBufPos;
		int pseudoHeaderPartialCSum = txCtx.calcPseudoHeaderPartialCSum(len);
		
		tcpBuf.putShort(tcpBufPos + 16, TcpPkt.calcCSum(pseudoHeaderPartialCSum, tcpBuf, tcpBufPos, len));		
	}
	
	// we're stateful and can only use one buffer at a time
	@Override
	public ByteBuffer use(Buffer buffer) {
		tcpBuf = txCtx.use(buffer);
		tcpBufPos = tcpBuf.position();
		
		tcpBuf.putShort(srcPort);
		tcpBuf.putShort(dstPort);
		tcpBuf.putInt(seqNum);
		tcpBuf.putInt(ackNum);
		tcpBuf.putShort((short) (0x5000 | controlBits));
		tcpBuf.putShort(winSize);
		tcpBuf.putShort((short) 0); // csum
		tcpBuf.putShort(urgPtr);		
		return tcpBuf;
	}

	@Override
	public void finish(short controlBits, Buffer buffer) {
		int dataOffs = tcpBuf.getShort(tcpBufPos + 12) & ~0x1ff;
		
		tcpBuf.putShort(tcpBufPos + 12, (short) (dataOffs | controlBits));
		finish(buffer);
	}
	
	@Override
	public void finish(Buffer buffer) {
		setCsum();
		tcpBuf = null;
		txCtx.finish(buffer);
	}

	@Override
	public void abort(Buffer buffer) {
		tcpBuf = null;
		txCtx.abort(buffer);
	}

	@Override
	public long getSeqNum() {
		return 0xffffffffL & seqNum;
	}

	@Override
	public void setSeqNum(long seqNum) {
		this.seqNum = (int)seqNum;		
	}

	@Override
	public long getAckNum() {
		return 0xffffffffL & ackNum;
	}

	@Override
	public void setAckNum(long ackNum) {
		this.ackNum = (int)ackNum;
	}

	@Override
	public short getControlBits() {
		return controlBits;
	}

	@Override
	public void setControlBits(short controlBits) {
		this.controlBits = controlBits;
	}

	@Override
	public int getWinSize() {
		return 0xffff & winSize;
	}

	@Override
	public void setWinSize(int winSize) {
		this.winSize = (short)winSize;
	}
	
	@Override
	public void finishOptions() {
		int headSize = tcpBuf.position() - tcpBufPos;

		tcpBuf.putShort(tcpBufPos + 12, (short) ((headSize << 10) | controlBits));
	}

	@Override
	public short payloadSize() {
		int headSize = (tcpBuf.getShort(tcpBufPos + 12) & 0xf000) >> 10;
		
		return (short) (tcpBuf.position() - (tcpBufPos + headSize));
	}
	
	@Override
	public int calcPseudoHeaderPartialCSum(int tcpPacketLength) {
		return txCtx.calcPseudoHeaderPartialCSum(tcpPacketLength);
	}

}
