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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

import io.jart.async.AsyncPipe;

public class PcapEthTxContext implements DataLinkTxContext {
	private final TxContext tx;
	private final OutputStream pcapOs;
	private ByteBuffer ethBuf;
	private int ethBufPos;
	private boolean first = true;
	
	public PcapEthTxContext(TxContext tx, OutputStream pcapOs) {
		this.tx = tx;
		this.pcapOs = pcapOs;
	}
	
	@Override
	public CompletableFuture<Buffer> startTx(Executor exec) {
		return tx.startTx(exec);
	}

	@Override
	public<D, O extends D> void startTx(AsyncPipe<D> dst, Function<Buffer, O> fun, Executor exec) {
		tx.startTx(dst, fun, exec);
	}

	@Override
	public Buffer tryStartTx() {
		return tx.tryStartTx();
	}

	// we're stateful and can only use one buffer at a time
	@Override
	public ByteBuffer use(Buffer buffer) {
		ethBuf = tx.use(buffer);
		if(ethBuf != null)
			ethBufPos = ethBuf.position() - EthPkt.HEADER_SIZE;		
		return ethBuf;
	}

	@Override
	public void finish(Buffer buffer) {
		if(ethBuf != null) {
			int pos = ethBuf.position();
			
			ethBuf.position(ethBufPos);
			try {
				if(first) {
					first = false;
					System.err.println("pcap mac dst: 0x" + Long.toHexString(EthPkt.getDstMac(ethBuf)));
					System.err.println("pcap mac src: 0x" + Long.toHexString(EthPkt.getSrcMac(ethBuf)));
					System.err.println("-----");
				}
				synchronized(pcapOs) {
					EthPkt.writePcapPacket(pcapOs, ethBuf, pos - ethBufPos);
					pcapOs.flush();
				}
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			ethBuf.position(pos);
			ethBuf = null;
		}
		tx.finish(buffer);
	}

	@Override
	public void abort(Buffer buffer) {
		ethBuf = null;
		tx.abort(buffer);
	}

}
