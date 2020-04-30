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

package io.jart.netmap;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import io.jart.util.CLibrary;

/**
 * Helper class to for NetmapIF data.
 */
public class NetmapIF {
	private NetmapIF() {} // hide constructor
	
	/**
	 * Gets the name.
	 *
	 * @param b the b
	 * @return the name
	 */
	public static String getName(ByteBuffer b) {
		byte[] bytes = new byte[CLibrary.IFNAMSIZE];

		b.position(0);
		b.get(bytes);

		int len = 0;

		while(len < CLibrary.IFNAMSIZE && bytes[len] != 0)
			len++;

		return new String(bytes, 0, len, StandardCharsets.UTF_8);
	}

	/**
	 * Sets the name.
	 *
	 * @param b the b
	 * @param s the s
	 */
	public static void setName(ByteBuffer b, String s) {
		byte[] bytes = s.getBytes(StandardCharsets.UTF_8);

		if(bytes.length < CLibrary.IFNAMSIZE) {
			b.position(0);
			b.put(bytes);
			b.put((byte) 0);
		}
		else
			b.put(bytes, 0, CLibrary.IFNAMSIZE);
	}

	/**
	 * Gets the version.
	 *
	 * @param b the b
	 * @return the version
	 */
	public static int getVersion(ByteBuffer b) {
		return b.getInt(16);
	}

	/**
	 * Gets the flags.
	 *
	 * @param b the b
	 * @return the flags
	 */
	public static int getFlags(ByteBuffer b) {
		return b.getInt(20);
	}

	/**
	 * Gets the TX rings.
	 *
	 * @param b the b
	 * @return the TX rings
	 */
	public static long getTXRings(ByteBuffer b) {
		return 0xffffffffL & (long)b.getInt(24);
	}

	/**
	 * Gets the RX rings.
	 *
	 * @param b the b
	 * @return the RX rings
	 */
	public static long getRXRings(ByteBuffer b) {
		return 0xffffffffL & (long)b.getInt(28);
	}
	
	/**
	 * Gets the bufs head.
	 *
	 * @param b the b
	 * @return the bufs head
	 */
	public static long getBufsHead(ByteBuffer b) {
		return 0xffffffffL & (long)b.getInt(32);
	}

	/**
	 * Sets the bufs head.
	 *
	 * @param b the b
	 * @param bufsHead the bufs head
	 */
	public static void setBufsHead(ByteBuffer b, long bufsHead) {
		b.putInt(32, (int)bufsHead);
	}

	/**
	 * Gets the host TX rings.
	 *
	 * @param b the b
	 * @return the host TX rings
	 */
	public static long getHostTXRings(ByteBuffer b) {
		return 0xffffffffL & (long)b.getInt(36);
	}

	/**
	 * Gets the host RX rings.
	 *
	 * @param b the b
	 * @return the host RX rings
	 */
	public static long getHostRXRings(ByteBuffer b) {
		return 0xffffffffL & (long)b.getInt(40);
	}
	
	// spare3
	
	/**
	 * Gets the ring offs.
	 *
	 * @param b the b
	 * @param i the i
	 * @return the ring offs
	 */
	public static long getRingOffs(ByteBuffer b, int i) {
		return b.getLong(56 + 8 * i);
	}
	
	/**
	 * Tx ring.
	 *
	 * @param b the b
	 * @param i the i
	 * @return the byte buffer
	 */
	public static ByteBuffer txRing(ByteBuffer b, int i) {
		b.position((int)getRingOffs(b, i));
		return b.slice().order(ByteOrder.nativeOrder());
	}

	/**
	 * Rx ring.
	 *
	 * @param b the b
	 * @param i the i
	 * @return the byte buffer
	 */
	public static ByteBuffer rxRing(ByteBuffer b, int i) {
		b.position((int)getRingOffs(b, (int) (i + getTXRings(b) + getHostTXRings(b))));
		return b.slice().order(ByteOrder.nativeOrder());
	}
}
