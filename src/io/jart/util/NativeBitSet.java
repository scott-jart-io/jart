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

import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;

public class NativeBitSet extends NativeBuffer {
	public NativeBitSet(long size) {
		super((size + NativeLong.SIZE - 1) / NativeLong.SIZE);
	}

	public NativeBitSet(Pointer ptr, long size) {
		super(ptr, (size + NativeLong.SIZE - 1) / NativeLong.SIZE);
	}
	
	public boolean isSet(int offset) {
		switch(NativeLong.SIZE) {
		default:
			throw new IllegalStateException("unexpected NativeLong.SIZE");
		case 4:
			return ((buf.getInt(offset / 32) >> (offset % 32)) & 1) == 1;
		case 8:
			return ((buf.getLong(offset / 64) >> (offset % 64)) & 1) == 1;
		}
	}
	
	public void set(int offset) {
		switch(NativeLong.SIZE) {
		default:
			throw new IllegalStateException("unexpected NativeLong.SIZE");
		case 4:
			buf.putInt(offset / 32, buf.getInt(offset / 32) | (1 << (offset % 32)));
			break;
		case 8:
			buf.putLong(offset / 64, buf.getInt(offset / 64) | (1L << (offset % 64)));
			break;
		}		
	}
}
