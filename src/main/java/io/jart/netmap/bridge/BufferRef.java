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

package io.jart.netmap.bridge;

import io.jart.pojo.Helper.POJO;

/**
 * POJO reference to a Netmap buffer.
 */
@POJO(fieldOrder = { "bufIdx", "len" })
public class BufferRef {
	protected long bufIdx;
	protected int len;

	/**
	 * Instantiates a new buffer ref.
	 */
	protected BufferRef() {}

	/**
	 * The Interface Alloc.
	 */
	public interface Alloc {
		
		/**
		 * Alloc.
		 *
		 * @param bufIdx the buf idx
		 * @param len the len
		 * @return the buffer ref
		 */
		BufferRef alloc(long bufIdx, int len);
		
		/**
		 * Free.
		 *
		 * @param buf the buf
		 */
		void free(BufferRef buf);
	}
	
	/**
	 * Gets the buf idx.
	 *
	 * @return the buf idx
	 */
	public long getBufIdx() {
		return bufIdx;
	}

	/**
	 * Gets the len.
	 *
	 * @return the len
	 */
	public int getLen() {
		return len;
	}

	/**
	 * Sets the len.
	 *
	 * @param len the new len
	 */
	public void setLen(int len) {
		this.len = len;
	}
}