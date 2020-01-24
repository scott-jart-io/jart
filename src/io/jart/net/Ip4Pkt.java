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

import org.apache.log4j.Logger;

public class Ip4Pkt {
	private static final Logger logger = Logger.getLogger(Ip4Pkt.class);

	public static final short IHL_MASK = 0x0f;
	public static final short VERSION_MASK = 0xf0;
	
	public static boolean valid(ByteBuffer b) {
		int size = b.limit() - b.position();

		if(size < 20) {
			logger.debug("below min size");
			return false;
		}
		
		byte vi = getVersionIHL(b);

		if((vi & VERSION_MASK) != 0x40) {
			logger.debug("wrong version");
			return false;
		}
		
		int ihlBytes = (vi & IHL_MASK) * 4;
		
		if(ihlBytes < 20) {
			logger.debug("ihl too small");
			return false;
		}
		
		int len = getTotalLen(b);
		
		if(len < ihlBytes) {
			logger.debug("len < ihlBytes " + len + " " + ihlBytes);
			return false;
		}
		if(len > size) {
			logger.debug("len > size " + len + " " + size);
			return false;
		}
		
		return true;
	}
	
	public static byte getVersionIHL(ByteBuffer b) {
		return b.get(b.position());
	}
	
	public static void setVersionIHL(ByteBuffer b, byte vi) {
		b.put(b.position(), vi);
	}
	
	public static byte getDSCPECN(ByteBuffer b) {
		return b.get(1 + b.position());
	}
	
	public static void setDSCPECN(ByteBuffer b, byte de) {
		b.put(1 + b.position(), de);
	}
	
	public static int getTotalLen(ByteBuffer b) {
		return 0xffff & (int)b.getShort(2 + b.position());
	}
	
	public static void setTotalLen(ByteBuffer b, int len) {
		b.putShort(2 + b.position(), (short)len);
	}
	
	public static int getId(ByteBuffer b) {
		return 0xfff & (int)b.getShort(4 + b.position());
	}
	
	public static void setId(ByteBuffer b, int id) {
		b.putShort(4 + b.position(), (short)id);
	}
	
	public static short getFlagsFragOffs(ByteBuffer b) {
		return b.getShort(6 + b.position());
	}
	
	public static void setFlagsFragOffs(ByteBuffer b, short fo) {
		b.putShort(6 + b.position(), fo);
	}
	
	public static byte getTTL(ByteBuffer b) {
		return b.get(8 + b.position());
	}
	
	public static void setTTL(ByteBuffer b, byte t) {
		b.put(8 + b.position(), t);
	}
	
	public static byte getProto(ByteBuffer b) {
		return b.get(9 + b.position());
	}
	
	public static void setProto(ByteBuffer b, byte p) {
		b.put(9 + b.position(), p);
	}
	
	public static short getHeaderCSum(ByteBuffer b) {
		return b.getShort(10 + b.position());
	}
	
	public static void setHeaderCSum(ByteBuffer b, int headPos, short c) {
		b.putShort(10 + headPos, c);
	}
	
	public static void setHeaderCSum(ByteBuffer b, short c) {
		setHeaderCSum(b, b.position(), c);
	}
	
	public static int getSrcAddr(ByteBuffer b) {
		return b.getInt(12 + b.position());
	}
	
	public static void setSrcAddr(ByteBuffer b, int a) {
		b.putInt(12 + b.position(), a);
	}
	
	public static int getDstAddr(ByteBuffer b) {
		return b.getInt(16 + b.position());
	}
	
	public static void setDstAddr(ByteBuffer b, int a) {
		b.putInt(16 + b.position(), a);
	}
	
	public static int getOption(ByteBuffer b, int i) {
		return b.getInt(20 + b.position() + i * 4);
	}
	
	public static void setOption(ByteBuffer b, int i, int o) {
		b.putInt(20 + b.position() + i * 4, o);
	}
	
	public static int dataOffs(ByteBuffer b) {
		return 4 * (IHL_MASK & getVersionIHL(b));
	}
	
	public static int payloadPos(ByteBuffer b) {
		return b.position() + dataOffs(b);
	}
	
	// calc csum of ip4 packet
	public static short calcHeaderCSum(ByteBuffer b) {
		return Inet.calcCSum(b, b.position(), dataOffs(b));
	}
	
	public static int calcPseudoHeaderPartialCSum(int srcAddr, int dstAddr, byte proto, int upperLayerPacketLength) {
		return (0xff & proto) +
				(0xffff & srcAddr) + (0xffff & (srcAddr >> 16)) +
				(0xffff & dstAddr) + (0xffff & (dstAddr >> 16)) +
				upperLayerPacketLength;
	}
}
