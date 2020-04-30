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
import java.nio.charset.StandardCharsets;

import io.jart.util.CLibrary;
import io.jart.util.NativeBuffer;

/**
 * Helper class for NMReqs.
 */
public class NMReq {
	public static final int SIZE = 60;

	private NMReq() {} // hide constructor
	
	/**
	 * Allocate.
	 *
	 * @return the native buffer
	 */
	public static NativeBuffer allocate() {
		return new NativeBuffer(SIZE);
	}

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
	 * Sets the version.
	 *
	 * @param b the b
	 * @param version the version
	 */
	public static void setVersion(ByteBuffer b, int version) {
		b.putInt(16, version);
	}

	/**
	 * Gets the offset.
	 *
	 * @param b the b
	 * @return the offset
	 */
	public static long getOffset(ByteBuffer b) {
		return 0xffffffffL & (long)b.getInt(20);
	}

	/**
	 * Sets the offset.
	 *
	 * @param b the b
	 * @param offset the offset
	 */
	public static void setOffset(ByteBuffer b, long offset) {
		b.putInt(20, (int)offset);
	}

	/**
	 * Gets the memsize.
	 *
	 * @param b the b
	 * @return the memsize
	 */
	public static long getMemsize(ByteBuffer b) {
		return 0xffffffffL & (long)b.getInt(24);
	}

	/**
	 * Sets the memsize.
	 *
	 * @param b the b
	 * @param size the size
	 */
	public static void setMemsize(ByteBuffer b, long size) {
		b.putInt(24, (int)size);
	}

	/**
	 * Gets the TX slots.
	 *
	 * @param b the b
	 * @return the TX slots
	 */
	public static long getTXSlots(ByteBuffer b) {
		return 0xffffffffL & (long)b.getInt(28);
	}

	/**
	 * Sets the TX slots.
	 *
	 * @param b the b
	 * @param slots the slots
	 */
	public static void setTXSlots(ByteBuffer b, long slots) {
		b.putInt(28, (int)slots);
	}

	/**
	 * Gets the RX slots.
	 *
	 * @param b the b
	 * @return the RX slots
	 */
	public static long getRXSlots(ByteBuffer b) {
		return 0xffffffffL & (long)b.getInt(32);
	}

	/**
	 * Sets the RX slots.
	 *
	 * @param b the b
	 * @param slots the slots
	 */
	public static void setRXSlots(ByteBuffer b, long slots) {
		b.putInt(32, (int)slots);
	}

	/**
	 * Gets the TX rings.
	 *
	 * @param b the b
	 * @return the TX rings
	 */
	public static int getTXRings(ByteBuffer b) {
		return 0xffff & b.getShort(36);
	}

	/**
	 * Sets the TX rings.
	 *
	 * @param b the b
	 * @param rings the rings
	 */
	public static void setTXRings(ByteBuffer b, int rings) {
		b.putShort(36, (short)rings);
	}

	/**
	 * Gets the RX rings.
	 *
	 * @param b the b
	 * @return the RX rings
	 */
	public static int getRXRings(ByteBuffer b) {
		return 0xffff & b.getShort(38);
	}

	/**
	 * Sets the RX rings.
	 *
	 * @param b the b
	 * @param rings the rings
	 */
	public static void setRXRings(ByteBuffer b, int rings) {
		b.putShort(38, (short)rings);
	}

	/**
	 * Gets the ring id.
	 *
	 * @param b the b
	 * @return the ring id
	 */
	public static int getRingId(ByteBuffer b) {
		return 0xffff & b.getShort(40);
	}

	/**
	 * Sets the ring id.
	 *
	 * @param b the b
	 * @param ringId the ring id
	 */
	public static void setRingId(ByteBuffer b, int ringId) {
		b.putShort(40, (short)ringId);
	}

	/**
	 * Gets the cmd.
	 *
	 * @param b the b
	 * @return the cmd
	 */
	public static int getCmd(ByteBuffer b) {
		return 0xffff & b.getShort(42);
	}

	/**
	 * Sets the cmd.
	 *
	 * @param b the b
	 * @param cmd the cmd
	 */
	public static void setCmd(ByteBuffer b, int cmd) {
		b.putShort(42, (short)cmd);
	}

	/**
	 * Gets the arg 1.
	 *
	 * @param b the b
	 * @return the arg 1
	 */
	public static int getArg1(ByteBuffer b) {
		return 0xffff & b.getShort(44);
	}

	/**
	 * Sets the arg 1.
	 *
	 * @param b the b
	 * @param arg the arg
	 */
	public static void setArg1(ByteBuffer b, int arg) {
		b.putShort(44, (short)arg);
	}

	/**
	 * Gets the arg 2.
	 *
	 * @param b the b
	 * @return the arg 2
	 */
	public static int getArg2(ByteBuffer b) {
		return 0xffff & b.getShort(46);
	}

	/**
	 * Sets the arg 2.
	 *
	 * @param b the b
	 * @param arg the arg
	 */
	public static void setArg2(ByteBuffer b, int arg) {
		b.putShort(46, (short)arg);
	}

	/**
	 * Gets the arg 3.
	 *
	 * @param b the b
	 * @return the arg 3
	 */
	public static long getArg3(ByteBuffer b) {
		return 0xffffffffL & (long)b.getInt(48);
	}

	/**
	 * Sets the arg 3.
	 *
	 * @param b the b
	 * @param arg the arg
	 */
	public static void setArg3(ByteBuffer b, long arg) {
		b.putInt(48, (int)arg);
	}

	/**
	 * Gets the flags.
	 *
	 * @param b the b
	 * @return the flags
	 */
	public static int getFlags(ByteBuffer b) {
		return b.getInt(52);
	}

	/**
	 * Sets the flags.
	 *
	 * @param b the b
	 * @param flags the flags
	 */
	public static void setFlags(ByteBuffer b, int flags) {
		b.putInt(52, flags);
	}

	// spare2
}