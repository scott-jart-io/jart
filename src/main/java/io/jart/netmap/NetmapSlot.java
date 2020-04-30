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

import com.sun.jna.Pointer;

/**
 * Helper class for Netmap slots.
 */
public class NetmapSlot {
	public final static int SIZE = 16;

	private NetmapSlot() {} // hide constructor
	
	/**
	 * Gets the buf idx.
	 *
	 * @param b the b
	 * @return the buf idx
	 */
	public static long getBufIdx(ByteBuffer b) {
		return 0xffffffffL & (long)b.getInt(0);
	}

	/**
	 * Sets the buf idx.
	 *
	 * @param b the b
	 * @param bufIdx the buf idx
	 */
	public static void setBufIdx(ByteBuffer b, long bufIdx) {
		b.putInt(0, (int)bufIdx);
	}

	/**
	 * Gets the len.
	 *
	 * @param b the b
	 * @return the len
	 */
	public static int getLen(ByteBuffer b) {
		return 0xffff & (int)b.getShort(4);
	}

	/**
	 * Sets the len.
	 *
	 * @param b the b
	 * @param len the len
	 */
	public static void setLen(ByteBuffer b, int len) {
		b.putShort(4, (short)len);
	}

	/**
	 * Gets the flags.
	 *
	 * @param b the b
	 * @return the flags
	 */
	public static int getFlags(ByteBuffer b) {
		return 0xffff & (int)b.getShort(6);
	}

	/**
	 * Sets the flags.
	 *
	 * @param b the b
	 * @param flags the flags
	 */
	public static void setFlags(ByteBuffer b, int flags) {
		b.putShort(6, (short)flags);
	}

	/**
	 * Gets the ptr.
	 *
	 * @param b the b
	 * @return the ptr
	 */
	public static Pointer getPtr(ByteBuffer b) {
		return new Pointer(b.getLong(8));
	}

	/**
	 * Sets the ptr.
	 *
	 * @param b the b
	 * @param ptr the ptr
	 */
	public static void setPtr(ByteBuffer b, Pointer ptr) {
		b.putLong(8, Pointer.nativeValue(ptr));
	}
}
