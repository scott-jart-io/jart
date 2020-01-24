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

public class TcpPkt {
	private static final Logger logger = Logger.getLogger(TcpPkt.class);

	public static final byte PROTO_TCP = 0x06;

	public static final short FIN = 1;
	public static final short SYN = 2;
	public static final short RST = 4;
	public static final short PSH = 8;
	public static final short ACK = 16;
	public static final short URG = 32;
	public static final short ECE = 64;
	public static final short CWR = 128;
	public static final short NS = 256;
	
	// basic validity check -- does NOT verify csum
	public static boolean valid(ByteBuffer b) {
		int size = b.limit() - b.position();
		
		if(size < 20) {
			logger.debug("below min size");
			return false;
		}
				
		int dataOffs = (getDataOffsControlBits(b) >> 10) & 0x3c;
		
		if(dataOffs < 20) {
			logger.debug("dataOffs too small");
			return false;
		}
		
		if(size < dataOffs) {
			logger.debug("size < dataOffs");
			return false;			
		}
		
		return true;
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
	
	public static long getSeqNum(ByteBuffer b) {
		return 0xffffffffL & (long)b.getInt(4 + b.position());
	}
	
	public static void setSeqNum(ByteBuffer b, long sn) {
		b.putInt(4 + b.position(), (int)sn);
	}
	
	public static long getAckNum(ByteBuffer b) {
		return 0xffffffffL & (long)b.getInt(8 + b.position());
	}
	
	public static void setAckNum(ByteBuffer b, long sn) {
		b.putInt(8 + b.position(), (int)sn);
	}

	public static short getDataOffsControlBits(ByteBuffer b) {
		return b.getShort(12 + b.position());
	}
	
	public static void setDataOffsControlBits(ByteBuffer b, short dof) {
		b.putShort(12 + b.position(), dof);
	}
	
	public static int getWinSize(ByteBuffer b) {
		return 0xffff & (int)b.getShort(14 + b.position());
	}
	
	public static void setWinSize(ByteBuffer b, int winSize) {
		b.putShort(14 + b.position(), (short)winSize);
	}
	
	public static short getCSum(ByteBuffer b) {
		return b.getShort(16 + b.position());
	}
	
	public static void setCSum(ByteBuffer b, short csum) {
		b.putShort(16 + b.position(), csum);
	}
	
	public static int getUrgPtr(ByteBuffer b) {
		return 0xffff & (int)b.getShort(18 + b.position());
	}
	
	public static void setUrgPtr(ByteBuffer b, int urgPtr) {
		b.putShort(18 + b.position(), (short)urgPtr);
	}
	
	public static int headerSize(ByteBuffer b) {
		return (getDataOffsControlBits(b) & 0xf000) >>> 10;
	}
	
	public static int optionsPos(ByteBuffer b) {
		return 20 + b.position();
	}
	
	public static int optionsSize(ByteBuffer b) {
		return headerSize(b) - 20;
	}
	
	public static int payloadPos(ByteBuffer b) {
		int dataOffs = (getDataOffsControlBits(b) >> 12) & 0xf;
		
		return b.position() + 4 * dataOffs;
	}
	
	public static short calcCSum(int pseudoHeaderPartialCSum, int tcpPartialCSum) {
		return Inet.calcFinalCSum(pseudoHeaderPartialCSum + tcpPartialCSum);
	}

	public static short calcCSum(int pseudoHeaderPartialCSum, ByteBuffer b, int pos, int size) {
		return calcCSum(pseudoHeaderPartialCSum, Inet.calcPartialCSum(b, pos, size));
	}

	public static short calcCSum(int pseudoHeaderPartialCSum, ByteBuffer b, int pos) {
		return calcCSum(pseudoHeaderPartialCSum, b, pos, b.position() - pos);
	}

	public static short calcCSum(int pseudoHeaderPartialCSum, ByteBuffer b) {
		return calcCSum(pseudoHeaderPartialCSum, b, b.position(), b.remaining());
	}
}
