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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

import io.jart.async.AsyncPipe;
import io.jart.net.DataLinkTxContext;
import io.jart.net.EthPkt;
import io.jart.net.TxContext;
import io.jart.netmap.NetmapRing;

/**
 * Implements a data link tx connection on top of bridge objects.
 */
public class EthTxContext implements DataLinkTxContext {
	
	/**
	 * Buffer implementation.
	 */
	protected class Buffer implements TxContext.Buffer {
		private BufferRef bufferRef;
		public int ethBufPos;

		/**
		 * Instantiates a new buffer.
		 *
		 * @param bufferRef the buffer ref
		 */
		public Buffer(BufferRef bufferRef) {
			this.bufferRef = bufferRef;
		}
		
		/**
		 * Use the buffer.
		 *
		 * @return the byte buffer
		 */
		public ByteBuffer use() {
			ethBufPos = (int)NetmapRing.bufOfs(someRing, bufferRef.getBufIdx());

			ethBuf.limit(ethBufPos + txBufSize);
			ethBuf.position(ethBufPos);
			
			EthPkt.putMac(ethBuf, dstMac);
			EthPkt.putMac(ethBuf, srcMac);
			ethBuf.putShort(etherType);		
			return ethBuf;
		}

		/**
		 * Finish.
		 */
		public void finish() {
			bufferRef.setLen(ethBuf.position() - ethBufPos);
			bulContext.pipe.write(bulContext.bufferUnlockReqAlloc.alloc(bufferRef));
			bufferRef = null;
		}

		/**
		 * Abort.
		 */
		public void abort() {
			tx.pipe.write(bufferRef);
		}		
	}
	
	private final BufferPipeTask.Context tx;
	private final int txBufSize;
	private final BufferUnlockerTask.Context bulContext;
	private final ByteBuffer someRing;
	protected final ByteBuffer ethBuf;
	private final long dstMac;
	private final long srcMac;
	private final short etherType;
	
	/**
	 * Instantiates a new eth tx context.
	 *
	 * @param tx the tx
	 * @param txBufSize the tx buf size
	 * @param bulContext the bul context
	 * @param someRing the some ring
	 * @param ethBuf the eth buf
	 * @param dstMac the dst mac
	 * @param srcMac the src mac
	 * @param etherType the ether type
	 */
	public EthTxContext(BufferPipeTask.Context tx, int txBufSize, BufferUnlockerTask.Context bulContext, ByteBuffer someRing, ByteBuffer ethBuf, long dstMac, long srcMac, short etherType) {
		this.tx = tx;
		this.txBufSize = txBufSize;
		this.bulContext = bulContext;
		this.someRing = someRing;
		this.ethBuf = ethBuf;
		this.dstMac = dstMac;
		this.srcMac = srcMac;
		this.etherType = etherType;
	}
	
	/**
	 * Start a transmit.
	 * Returns a Buffer for use().
	 *
	 * @param exec the Executor to run on
	 * @return the completable future which completes with a Buffer ready for use().
	 */
	@Override
	public CompletableFuture<TxContext.Buffer> startTx(Executor exec) {
		return tx.pipe.read(exec).thenApply(Buffer::new);
	}

	/**
	 * Start a transmit indirectly via a pipe.
	 * As startTx(Executor exec) but delivers the Buffer to dst as translated by fun.
	 *
	 * @param <D> the generic type
	 * @param <O> the generic type
	 * @param dst the destination pipe
	 * @param fun the function to translate the Buffer to whatever the destination pipe wants
	 * @param exec the Executor to run on
	 */
	@Override
	public<D, O extends D> void startTx(AsyncPipe<D> dst, Function<TxContext.Buffer, O> fun, Executor exec) {
		tx.pipe.transfer(dst, (BufferRef bufferRef)->fun.apply(new Buffer(bufferRef)), exec);
	}

	/**
	 * Try to synchronously start a transmit.
	 *
	 * @return the buffer or null on failure
	 */
	@Override
	public Buffer tryStartTx() {
		BufferRef bufferRef = tx.pipe.poll();
		
		return (bufferRef != null) ? new Buffer(bufferRef) : null;
	}
	
	/**
	 * Make a Buffer ready for use and return a ByteBuffer prepped for filling.
	 * We're stateful and can only use one buffer at a time.
	 *
	 * @param buffer the buffer
	 * @return the byte buffer
	 */
	// we're stateful and can only use one buffer at a time
	@Override
	public ByteBuffer use(TxContext.Buffer buffer) {
		return ((Buffer)buffer).use();
	}

	/**
	 * Finish.
	 *
	 * @param buffer the buffer
	 */
	@Override
	public void finish(TxContext.Buffer buffer) {
		((Buffer)buffer).finish();
	}

	/**
	 * Abort.
	 *
	 * @param buffer the buffer
	 */
	@Override
	public void abort(TxContext.Buffer buffer) {
		((Buffer)buffer).abort();
	}
}
