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

package io.jart.util;
import java.nio.ByteBuffer;

public class PollFD {
	public static final int SIZE = 8;
	
	public static NativeBuffer allocate(int n) {
		return new NativeBuffer(n * SIZE);
	}
	
	public static int getFD(ByteBuffer b, int i) {
		return b.getInt(i * SIZE);
	}
	
	public static void setFD(ByteBuffer b, int i, int fd) {
		b.putInt(i * SIZE, fd);
	}
	
	public static short getEvents(ByteBuffer b, int i) {
		return b.getShort(i * SIZE + 4);
	}
	
	public static void setEvents(ByteBuffer b, int i, int events) {
		b.putShort(i * SIZE + 4, (short)events);
	}

	public static short getREvents(ByteBuffer b, int i) {
		return b.getShort(i * SIZE + 6);
	}
	
	public static void setREvents(ByteBuffer b, int i, int revents) {
		b.putShort(i * SIZE + 6, (short)revents);
	}
}