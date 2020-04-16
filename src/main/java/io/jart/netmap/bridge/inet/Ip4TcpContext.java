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

package io.jart.netmap.bridge.inet;

import java.nio.ByteBuffer;

import io.jart.async.AsyncPipe;
import io.jart.net.Ip4Pkt;
import io.jart.net.TcpTxContext;
import io.jart.netmap.bridge.BufferUnlockerTask.Context;
import io.jart.netmap.bridge.inet.InetBufferSwapTask.IpPacket;
import io.jart.netmap.bridge.inet.InetBufferSwapTask.IpPacket.Alloc;

public class Ip4TcpContext extends BaseTcpContext {
	public Ip4TcpContext(ByteBuffer ethBuf, AsyncPipe<Object> pipe, TcpTxContext tx, Context bulContext,
			Alloc ipPacketAlloc) {
		super(ethBuf, pipe, tx, bulContext, ipPacketAlloc);
	}

	public Ip4TcpContext(ByteBuffer ethBuf, AsyncPipe<Object> pipe, TcpTxContext tx, Context bulContext) {
		super(ethBuf, pipe, tx, bulContext);
	}

	public Ip4TcpContext(EthBufPipeTxFactory factory, IpPacket firstPacket, Alloc ipPacketAlloc) {
		super(factory, firstPacket, ipPacketAlloc);
	}

	public Ip4TcpContext(EthBufPipeTxFactory factory, IpPacket firstPacket) {
		super(factory, firstPacket);
	}

	// override to return true for hardware offload
	@Override
	protected boolean checkIpCSum(IpPacket ipPacket) {
		ethBuf.position(ipPacket.getIpPos());
		return Ip4Pkt.calcHeaderCSum(ethBuf) == 0;
	}
}
