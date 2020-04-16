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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.Executor;

import io.jart.async.AsyncPipe;
import io.jart.net.EthPkt;
import io.jart.netmap.bridge.inet.InetBufferSwapTask.IpPacket;

// AsyncPipe that will log any IpPackets written to the pipe to a given stream in pcap packet format
public class PcapIpPacketPipe extends AsyncPipe<Object> {
	private final ByteBuffer someRing;
	private final OutputStream pcapOs;
	
	public PcapIpPacketPipe(Group group, Executor exec, ByteBuffer someRing, OutputStream pcapOs) {
		super(group);
		this.someRing = someRing.duplicate().order(ByteOrder.BIG_ENDIAN);
		this.pcapOs = pcapOs;
	}

	public PcapIpPacketPipe(Group group, ByteBuffer someRing, OutputStream pcapOs) {
		this(group, null, someRing, pcapOs);
	}

	public PcapIpPacketPipe(Executor exec, ByteBuffer someRing, OutputStream pcapOs) {
		this(null, exec, someRing, pcapOs);
	}

	public PcapIpPacketPipe(ByteBuffer someRing, OutputStream pcapOs) {
		this(null, null, someRing, pcapOs);
	}

	@Override
	public void write(Object msg) {
		if(msg instanceof IpPacket) {
			IpPacket ipp = (IpPacket)msg;
			
			someRing.position(ipp.getEthPos());

			synchronized(pcapOs) {
				try {
					EthPkt.writePcapPacket(pcapOs, someRing, ipp.getEndPos() - ipp.getEthPos());
					pcapOs.flush();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		super.write(msg);
	}
}
