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

import java.nio.ByteBuffer;

/**
 * Helper class for internet packets.
 */
public class Inet {
	private Inet() {}
	
	/**
	 * Calculate partial checksum.
	 *
	 * @param b the ByteBuffer
	 * @param pos the position for the data's start
	 * @param size the number of bytes to checksum
	 * @return the partial checksum
	 */
	public static int calcPartialCSum(ByteBuffer b, int pos, int size) {
		long sum;
		int align = Math.min(8 - (pos & 7), size);
		
		// align
		if((align & 1) != 0) {
			sum = 0xff & b.get(pos);
			pos += 1;
			size -= 1;
		}
		else
			sum = 0;
		if((align & 2) != 0) {
			sum += 0xffff & b.getShort(pos);
			pos += 2;
			size -= 2;
		}
		if((align & 4) != 0) {
			sum += 0xffffffffL & b.getInt(pos);
			pos += 4;
			size -= 4;
		}
		// 8-byte reads
		while(size > 7) {
			long v = b.getLong(pos);
			
			sum += (v >>> 32) + (0xffffffffL & v);
			pos += 8;
			size -= 8;
		}
		// clean up the rest
		if(size > 3) {
			sum += 0xffffffffL & b.getInt(pos);
			pos += 4;
			size -= 4;
		}
		if(size > 1) {
			sum += 0xffff & b.getShort(pos);
			pos += 2;
			size -= 2;
		}
		if(size > 0)
			sum += (0xff & b.get(pos)) << 8;
		
		sum = (sum >>> 32) + (0xffffffffL & sum);
		sum = (sum >>> 32) + (0xffffffffL & sum);
		sum = (sum >>> 16) + (0xffff & sum);
		return (int)sum;
	}
	
	/**
	 * Calculate a final checksum from a partial.
	 *
	 * @param partialSum the partial sum
	 * @return the checksum
	 */
	public static short calcFinalCSum(int partialSum) {
		partialSum = (partialSum >>> 16) + (0xffff & partialSum);
		partialSum = (partialSum >>> 16) + (0xffff & partialSum);
		return (short)~partialSum;		
	}
	
	/**
	 * Calculate a final checksum for data in a ByteBuffer.
	 *
	 * @param b the ByteBuffer
	 * @param pos the pos
	 * @param size the size
	 * @return the checksum
	 */
	public static short calcCSum(ByteBuffer b, int pos, int size) {
		return calcFinalCSum(calcPartialCSum(b, pos, size));
	}
	
	/**
	 * Calculate a final checksum for data in a ByteBuffer starting at pos and ending at the ByteBuffer's current position.
	 *
	 * @param b the ByteBuffer
	 * @param pos the pos
	 * @return the checksum
	 */
	public static short calcCSum(ByteBuffer b, int pos) {
		return calcCSum(b, pos, b.position() - pos);
	}

	/**
	 * Calculate a final checksum for data in a ByteBuffer in range [position(), limit()].
	 *
	 * @param b the ByteBuffer
	 * @return the checksum
	 */
	public static short calcCSum(ByteBuffer b) {
		return calcCSum(b, b.position(), b.remaining());
	}
}
