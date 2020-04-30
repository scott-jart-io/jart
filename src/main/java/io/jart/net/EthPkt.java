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

/**
 * Ethernet packet helper class.
 */
public class EthPkt {
	private static final Logger logger = Logger.getLogger(EthPkt.class);

	private static final int SNAPLEN = 65536; // max snapshot
	
	public static final int HEADER_SIZE = 14;
	public static final short ETHERTYPE_IP4 = 0x0800;
	
	private EthPkt() {} // hide constructor
	
	/**
	 * Simple validity check.
	 * Checks only that the packet is big enough to hold an Ethernet header.
	 *
	 * @param b the ByteBuffer holding the packet
	 * @return true, if successful
	 */
	public static boolean valid(ByteBuffer b) {
		int size = b.limit() - b.position();
		
		if(size < HEADER_SIZE) {
			logger.debug("below min size");
			return false;
		}
		return true;
	}
	
	/**
	 * Gets the mac address from a packet at a given position.
	 *
	 * @param b the ByteBuffer holding the packet
	 * @param pos the position of the header
	 * @return the mac address
	 */
	public static long getMac(ByteBuffer b, int pos) {
		return ((0xffff & (long)b.getShort(pos)) << 32) |
				((0xffff & (long)b.getShort(2 + pos)) << 16) |
				(0xffff & (long)b.getShort(4 + pos));		
	}
	
	/**
	 * Gets the mac address from a packet at the buffer's current position.
	 * Advances the ByteBuffer's position
	 *
	 * @param b the ByteBuffer holding the packet
	 * @return the mac address
	 */
	public static long getMac(ByteBuffer b) {
		return ((0xffff & (long)b.getShort()) << 32) |
				((0xffff & (long)b.getShort()) << 16) |
				(0xffff & (long)b.getShort());				
	}
	
	/**
	 * Put mac.
	 *
	 * @param b the b
	 * @param pos the pos
	 * @param m the m
	 */
	public static void putMac(ByteBuffer b, int pos, long m) {
		b.putShort(pos, (short) (m >> 32));
		b.putShort(2 + pos, (short) (m >> 16));
		b.putShort(4 + pos, (short) m );
	}
	
	/**
	 * Put a mac address at the current position.
	 *
	 * @param b the ByteBuffer
	 * @param m the mac address
	 */
	public static void putMac(ByteBuffer b, long m) {
		b.putShort((short) (m >> 32));
		b.putShort((short) (m >> 16));
		b.putShort((short) m );
	}
	
	/**
	 * Gets the destination mac address from a packet.
	 * ByteBuffer's position should be at the beginning of the packet.
	 *
	 * @param b the ByteBuffer
	 * @return the dst mac
	 */
	public static long getDstMac(ByteBuffer b) {
		return getMac(b, b.position());
	}

	/**
	 * Sets the destination mac address from a packet.
	 * ByteBuffer's position should be at the beginning of the packet.
	 *
	 * @param b the ByteBuffer
	 * @param m the m
	 */
	public static void setDstMac(ByteBuffer b, long m) {
		putMac(b, b.position(), m);
	}
	
	/**
	 * Gets the source mac address from a packet.
	 * ByteBuffer's position should be at the beginning of the packet.
	 *
	 * @param b the ByteBuffer
	 * @return the src mac
	 */
	public static long getSrcMac(ByteBuffer b) {
		return getMac(b, b.position() + 6);
	}
	
	/**
	 * Sets the source mac address from a packet.
	 * ByteBuffer's position should be at the beginning of the packet.
	 *
	 * @param b the ByteBuffer
	 * @param m the m
	 */
	public static void setSrcMac(ByteBuffer b, long m) {
		putMac(b, b.position() + 6, m);
	}
	
	/**
	 * Gets the ether type.
	 * ByteBuffer's position should be at the beginning of the packet.
	 *
	 * @param b the ByteBuffer
	 * @return the ether type
	 */
	public static short getEtherType(ByteBuffer b) {
		return b.getShort(12 + b.position());
	}
	
	/**
	 * Sets the ether type.
	 * ByteBuffer's position should be at the beginning of the packet.
	 *
	 * @param b the ByteBuffer
	 * @param t the t
	 */
	public static void setEtherType(ByteBuffer b, short t) {
		b.putShort(12 + b.position(), t);
	}
	
	/**
	 * Gets the position in the ByteBuffer where the payload starts.
	 * ByteBuffer's position should be at the beginning of the packet.
	 *
	 * @param b the ByteBuffer
	 * @return the int
	 */
	public static int payloadPos(ByteBuffer b) {
		return b.position() + HEADER_SIZE;
	}
	
	/**
	 * Write a packet in pcap format to the OutputStream.
	 *
	 * @param os the destination
	 * @param b the ByteBuffer
	 * @param len the length of the packet
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	public static void writePcap(OutputStream os, ByteBuffer b, int len) throws IOException {
		writePcapHeader(os);
		writePcapPacket(os, b, len);
	}
	
	/**
	 * Write a pcap header to an OutputStream.
	 *
	 * @param os the destinatoin
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
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
	
	/**
	 * Write a pcap packet.
	 *
	 * @param os the destination
	 * @param b the ByteBuffer -- position should be at start of packet
	 * @param len the length of the packet
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
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
