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
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import org.apache.log4j.Logger;

public class EthPkt {
	private static final Logger logger = Logger.getLogger(EthPkt.class);

	private static final int SNAPLEN = 65536; // max snapshot
	
	public static final int HEADER_SIZE = 14;
	public static final short ETHERTYPE_IP4 = 0x0800;
	
	// basic validity check -- does NOT verify csum
	public static boolean valid(ByteBuffer b) {
		int size = b.limit() - b.position();
		
		if(size < HEADER_SIZE) {
			logger.debug("below min size");
			return false;
		}
		return true;
	}
	
	public static long getMac(ByteBuffer b, int pos) {
		return ((0xffff & (long)b.getShort(pos)) << 32) |
				((0xffff & (long)b.getShort(2 + pos)) << 16) |
				(0xffff & (long)b.getShort(4 + pos));		
	}
	
	public static long getMac(ByteBuffer b) {
		return ((0xffff & (long)b.getShort()) << 32) |
				((0xffff & (long)b.getShort()) << 16) |
				(0xffff & (long)b.getShort());				
	}
	
	public static void putMac(ByteBuffer b, int pos, long m) {
		b.putShort(pos, (short) (m >> 32));
		b.putShort(2 + pos, (short) (m >> 16));
		b.putShort(4 + pos, (short) m );
	}
	
	public static void putMac(ByteBuffer b, long m) {
		b.putShort((short) (m >> 32));
		b.putShort((short) (m >> 16));
		b.putShort((short) m );
	}
	
	public static long getDstMac(ByteBuffer b) {
		return getMac(b, b.position());
	}

	public static void setDstMac(ByteBuffer b, long m) {
		putMac(b, b.position(), m);
	}
	
	public static long getSrcMac(ByteBuffer b) {
		return getMac(b, b.position() + 6);
	}
	
	public static void setSrcMac(ByteBuffer b, long m) {
		putMac(b, b.position() + 6, m);
	}
	
	public static short getEtherType(ByteBuffer b) {
		return b.getShort(12 + b.position());
	}
	
	public static void setEtherType(ByteBuffer b, short t) {
		b.putShort(12 + b.position(), t);
	}
	
	public static int payloadPos(ByteBuffer b) {
		return b.position() + HEADER_SIZE;
	}
	
	public static void writePcap(OutputStream os, ByteBuffer b, int len) throws IOException {
		writePcapHeader(os);
		writePcapPacket(os, b, len);
	}
	
	public static void writePcapHeader(OutputStream os) throws IOException {
		DataOutputStream dos = new DataOutputStream(os);
		
		dos.writeInt(0xa1b2c3d4); // magic
		dos.writeShort(2); // major ver
		dos.writeShort(4); // minor ver
		dos.writeInt(0); // thiszone
		dos.writeInt(0); // sigfigs
		dos.writeInt(SNAPLEN); // snaplen
		dos.writeInt(1); // network
	}
	
	public static void writePcapPacket(OutputStream os, ByteBuffer b, int len) throws IOException {
		DataOutputStream dos = new DataOutputStream(os);
		int inclLen = Math.min(len, SNAPLEN);
		
		dos.writeInt(0); // seconds
		dos.writeInt(0); // usec
		dos.writeInt(inclLen); // inclLen
		dos.writeInt(len);

		int pos = b.position();
		byte[] data = new byte[inclLen];
		
		b.get(data);
		b.position(pos);
		
		os.write(data);
	}
}
