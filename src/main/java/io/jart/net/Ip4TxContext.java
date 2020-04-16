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

public class Ip4TxContext implements IpTxContext {
	private final DataLinkTxContext txCtx;
	private final byte proto;
	private final int srcAddr;
	private final int dstAddr;
	private ByteBuffer ipBuf;
	private int ipBufPos;
	
	public Ip4TxContext(DataLinkTxContext dlCtx, byte proto, int srcAddr, int dstAddr) {
		this.txCtx = dlCtx;
		this.proto = proto;
		this.srcAddr = srcAddr;
		this.dstAddr = dstAddr;
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

	// we're stateful and can only use one buffer at a time
	@Override
	public ByteBuffer use(Buffer buffer) {
		ipBuf = txCtx.use(buffer);
		ipBufPos = ipBuf.position();
		
		ipBuf.put((byte) 0x45); // version 4 / 5 ihl
		ipBuf.put((byte) 0); // dscp / ecn
		ipBuf.position(ipBufPos + 4); // skip length
		ipBuf.putShort((short) 0); // id
		ipBuf.putShort((short) 0); // flags / frag offs
		ipBuf.put((byte) 255); // ttl
		ipBuf.put((byte) proto); // proto
		ipBuf.putShort((short) 0); // csum placeholder
		ipBuf.putInt(srcAddr);
		ipBuf.putInt(dstAddr);		
		return ipBuf;
	}

	@Override
	public void finish(Buffer buffer) {
		ipBuf.putShort(ipBufPos + 2, (short)(ipBuf.position() - ipBufPos)); // total length
		Ip4Pkt.setHeaderCSum(ipBuf, ipBufPos, Inet.calcCSum(ipBuf, ipBufPos, 20)); // csum
		ipBuf = null;
		txCtx.finish(buffer);
	}

	@Override
	public void abort(Buffer buffer) {
		ipBuf = null;
		txCtx.abort(buffer);
	}
	
	@Override
	public int calcPseudoHeaderPartialCSum(int upperLayerPacketLength) {
		return Ip4Pkt.calcPseudoHeaderPartialCSum(srcAddr, dstAddr, proto, upperLayerPacketLength);
	}
}
