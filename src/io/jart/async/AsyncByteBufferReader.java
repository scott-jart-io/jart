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

package io.jart.async;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;

public interface AsyncByteBufferReader {
	public CompletableFuture<Void> read(BiPredicate<ByteBuffer, Boolean> consumer);
	
	public default CompletableFuture<Long> read(BiPredicate<ByteBuffer, Boolean> consumer, long len) {
		long[] total = new long[1];
		
		return read((ByteBuffer buf, Boolean needsCopy)->{
			if(buf == null) {
				consumer.test(null, false);
				return false;
			}
			
			int limit = buf.limit();
			int pos = buf.position();
			
			buf.limit((int)Math.min(limit, pos + len - total[0]));
			
			boolean cont = consumer.test(buf, needsCopy);
			
			buf.limit(limit);
			return ((total[0] += (buf.position() - pos)) < len) && cont;
		}).thenApply((Void dummy)->total[0]);
	}
	
	public default CompletableFuture<Integer> read(byte[] b, int off, int len) {
		int[] size = new int[1];
		
		return read((ByteBuffer buf, Boolean needsCopy)->{
			if(buf == null)
				return false;
			
			int n = buf.remaining();
			
			buf.get(b, off + size[0], n);
			return (size[0] += n) < len;
		}, len).thenApply((Long total)->(int)(long)total);
	}
	
	public default CompletableFuture<Integer> read(byte[] b) {
		return read(b, 0, b.length);
	}
	
	public default CompletableFuture<Integer> read(byte[] b, int off, int len, byte term) {
		int[] size = new int[1];
		
		return read((ByteBuffer buf, Boolean needsCopy)->{
			if(buf == null)
				return false;
			
			int n = buf.remaining();
			
			while(n-- > 0) {
				if((b[off + size[0]++] = buf.get()) == term)
					return false;
			}

			return true;
		}, len).thenApply((Long total)->(int)(long)total);
	}
	
	public default CompletableFuture<Integer> read(byte[] b, byte term) {
		return read(b, 0, b.length, term);
	}
	
	public default CompletableFuture<Void> read(ByteBuffer dst) {
		return read((ByteBuffer buf, Boolean needsCopy)->{
			if(buf == null)
				return false;
			
			int limit = buf.limit();
			
			buf.limit(Math.min(limit, buf.position() + dst.remaining()));
			dst.put(buf);
			buf.limit(limit);
			return dst.hasRemaining();
		});
	}
}
