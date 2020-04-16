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

public class UdpPkt {
	public static final byte PROTO_UDP = 0x11;
	
	// basic validity check -- does NOT verify csum
	public static boolean valid(ByteBuffer b) {
		int size = b.limit() - b.position();
		
		if(size < 8)
			return false;
		return getLength(b) <= size;
	}
	
	public static int getSrcPort(ByteBuffer b) {
		return 0xffff & (int)b.getShort(b.position());
	}
	
	public static void setSrcPort(ByteBuffer b, int port) {
		b.putShort(b.position(), (short)port);
	}

	public static int getDstPort(ByteBuffer b) {
		return 0xffff & (int)b.getShort(2 + b.position());
	}
	
	public static void setDstPort(ByteBuffer b, int port) {
		b.putShort(2 + b.position(), (short)port);
	}
	
	public static int getLength(ByteBuffer b) {
		return 0xffff & (int)b.getShort(4 + b.position());
	}
	
	public static void setLength(ByteBuffer b, int port) {
		b.putShort(4 + b.position(), (short)port);
	}
	
	public static int getCSum(ByteBuffer b) {
		return 0xffff & (int)b.getShort(6 + b.position());
	}
	
	public static void setCSum(ByteBuffer b, int port) {
		b.putShort(6 + b.position(), (short)port);
	}
	
	public static int payloadPos(ByteBuffer b) {
		return b.position() + 8;
	}
}
