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

/**
 * ByteChunker implementation in terms of a region of a byte[].
 */
public class ByteArrayChunker extends ByteChunker {
	private byte[] chunk;
	private int offs;
	private int len;
	
	/**
	 * Instantiates a new byte array chunker.
	 *
	 * @param chunk the chunk
	 * @param offs the offs
	 * @param len the len
	 */
	public ByteArrayChunker(byte[] chunk, int offs, int len) {
		this.chunk = chunk;
		this.offs = offs;
		this.len = len;
	}

	/**
	 * Chunk to a given ByteBuffer and record new ByteChunk to chunkArray[chunkIndex].
	 *
	 * @param dst the dst
	 * @param chunkArray the chunk array
	 * @param chunkIndex the chunk index
	 * @return true, if successful
	 */
	@Override
	public boolean chunkTo(ByteBuffer dst, ByteChunk[] chunkArray, int chunkIndex) {
		int plen = Math.min(len, dst.remaining());

		chunkArray[chunkIndex] = new ByteArrayChunk(chunk, offs, plen);
		dst.put(chunk, offs, plen);
		offs += plen;
		len -= plen;
		return len > 0;
	}
}
