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

public class NetmapRing {
	public static long getBufOfs(ByteBuffer b) {
		return b.getLong(0);
	}
	
	public static long getNumSlots(ByteBuffer b) {
		return 0xffffffffL & (long)b.getInt(8);
	}

	public static long getNRBufSize(ByteBuffer b) {
		return 0xffffffffL & (long)b.getInt(12);
	}

	public static int getRingId(ByteBuffer b) {
		return 0xffff & (int)b.getShort(16);
	}

	public static int getDir(ByteBuffer b) {
		return 0xffff & (int)b.getShort(18);
	}

	public static long getHead(ByteBuffer b) {
		return 0xffffffffL & (long)b.getInt(20);
	}

	public static void setHead(ByteBuffer b, long head) {
		b.putInt(20, (int)head);
	}

	public static long getCur(ByteBuffer b) {
		return 0xffffffffL & (long)b.getInt(24);
	}

	public static void setCur(ByteBuffer b, long cur) {
		b.putInt(24, (int)cur);
	}

	public static long getTail(ByteBuffer b) {
		return 0xffffffffL & (long)b.getInt(28);
	}

	public static void setTail(ByteBuffer b, long tail) {
		b.putInt(28, (int)tail);
	}

	public static long getFlags(ByteBuffer b) {
		return 0xffffffffL & (long)b.getInt(32);
	}

	public static void setFlags(ByteBuffer b, long flags) {
		b.putInt(32, (int)flags);
	}
	
	public static ByteBuffer slot(ByteBuffer b, long i) {
		b.position((int) (256 + i * NetmapSlot.SIZE));
		return b.slice().order(ByteOrder.nativeOrder());
	}
	
	public static long ringSpace(ByteBuffer b) {
		long ret = getTail(b) - getHead(b);
		
		if(ret < 0)
			ret += getNumSlots(b);
		return ret;
	}

	public static boolean ringEmpty(ByteBuffer b) {
		return getHead(b) == getTail(b);
	}

	public static long ringNext(ByteBuffer b, long i) {
		return ((i + 1) == getNumSlots(b)) ? 0 : (i + 1);
	}

	// returns offset from this ring to given buffer
	public static long bufOfs(ByteBuffer b, long index) {
		long bufSize = getNRBufSize(b);
		
		return getBufOfs(b) + index * bufSize;
	}
}
