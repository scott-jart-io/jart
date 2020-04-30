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

/**
 * Basic implemenation of UdpTxContext.
 */
public class BaseUdpTxContext implements UdpTxContext {
	private final IpTxContext txCtx;
	private final int srcPort;
	private final int dstPort;
	protected ByteBuffer udpBuf;
	protected int udpBufPos;
	
	/**
	 * Instantiates a new base udp tx context.
	 *
	 * @param txCtx the tx ctx
	 * @param srcPort the src port
	 * @param dstPort the dst port
	 */
	public BaseUdpTxContext(IpTxContext txCtx, int srcPort, int dstPort) {
		this.txCtx = txCtx;
		this.srcPort = srcPort;
		this.dstPort = dstPort;
	}
	
	/**
	 * Start tx.
	 *
	 * @param exec the exec
	 * @return the completable future
	 */
	@Override
	public CompletableFuture<Buffer> startTx(Executor exec) {
		return txCtx.startTx(exec);
	}

	/**
	 * Start tx.
	 *
	 * @param <D> the generic type
	 * @param <O> the generic type
	 * @param dst the dst
	 * @param fun the fun
	 * @param exec the exec
	 */
	@Override
	public<D, O extends D> void startTx(AsyncPipe<D> dst, Function<Buffer, O> fun, Executor exec) {
		txCtx.startTx(dst, fun, exec);
	}

	/**
	 * Try start tx.
	 *
	 * @return the buffer
	 */
	@Override
	public Buffer tryStartTx() {
		return txCtx.tryStartTx();
	}

	/**
	 * Sets the csum.
	 */
	protected void setCsum() {} // none by default

	/**
	 * Use.
	 *
	 * @param buffer the buffer
	 * @return the byte buffer
	 */
	// we're stateful and can only use one buffer at a time
	@Override
	public ByteBuffer use(Buffer buffer) {
		udpBuf = txCtx.use(buffer);
		udpBufPos = udpBuf.position();
		
		udpBuf.putShort((short) srcPort);
		udpBuf.putShort((short) dstPort);
		udpBuf.position(udpBufPos + 6); // skip length
		udpBuf.putShort((short) 0); // csum
		return udpBuf;
	}

	/**
	 * Finish.
	 *
	 * @param buffer the buffer
	 */
	@Override
	public void finish(Buffer buffer) {
		udpBuf.putShort(udpBufPos + 4, (short)(udpBuf.position() - udpBufPos)); // length
		setCsum();
		udpBuf = null;
		txCtx.finish(buffer);
	}

	/**
	 * Abort.
	 *
	 * @param buffer the buffer
	 */
	@Override
	public void abort(Buffer buffer) {
		udpBuf = null;
		txCtx.abort(buffer);
	}
}
