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
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import com.ea.async.Async;

public class AsyncCopiers {
	private AsyncCopiers() {}
	
	private static byte[] copyLoop(AsyncByteWriter dst, byte[] chunk, AsyncByteArrayReader src, long[] skip, long[] len) {
		for(;;) {
			if(Math.min(chunk.length, len[0]) <= 0)
				return AsyncByteArrayReader.EOF;
			
			int size = (int)Math.max(0, Math.min(len[0], chunk.length - skip[0]));
			
			if(size > 0 && !dst.tryWrite(chunk, (int)skip[0], size))
				return chunk;
			else {
				skip[0] = Math.max(0, skip[0] - size);
				len[0] -= size;
			}
			chunk = src.tryRead();
			
			if(chunk == null)
				return null;
		}
	}
	
	// copy up to len bytes from src to dst, skipping skip bytes from src
	public static CompletableFuture<Long> copy(AsyncByteWriter dst, AsyncByteArrayReader src, long skip, long len, Executor exec) {
		long[] skipLeft = new long[] { skip };
		long[] left = new long[] { len };

		return AsyncLoop.doWhile(()->{
			byte[] chunk = copyLoop(dst, Async.await(src.read()), src, skipLeft, left);

			if(chunk != null) {
				if(chunk.length == 0)
					return AsyncLoop.cfFalse;

				int size = (int)Math.max(0, Math.min(left[0], chunk.length - skipLeft[0]));
			
				Async.await(dst.write(chunk, (int)skipLeft[0], size));
				skipLeft[0] = Math.max(0, skipLeft[0] - size);
				left[0] -= size;
			}
			return AsyncLoop.cfTrue;
		}, exec).thenApply((Void dummy)->len - left[0]);
	}

	// copy up to len bytes of file at path starting at offset to dst via cache
	public static CompletableFuture<Long> copy(AsyncByteWriter dst, AsyncReadThroughFileCache cache, Path path, long offset, long len, Executor exec) {
		AsyncByteArrayReader src = new AsyncReadThroughFileCache.ReadAhead(cache, path, (int)(offset / cache.chunkSize()));

		return copy(dst, src, offset % cache.chunkSize(), len, exec);
	}
	
	// copy up to len bytes of src to dst file starting at offset with given chunkSize and >=1 parallel writes
	public static CompletableFuture<Long> copy(AsynchronousFileChannel dst, long offset, AsyncByteBufferReader src, long len, int chunkSize, int parallel, Executor exec) {
		long[] offs = new long[] { offset };
		long[] left = new long[] { len };
		AtomicLong written = new AtomicLong();
		AsyncSema sema = new AsyncSema(parallel);
		CompletableFuture<Boolean> cont = CompletableFuture.completedFuture(true);
		
		Async.await(AsyncLoop.doWhile(()->{
			if(left[0] <= 0) // wrote all bytes requested
				return AsyncLoop.cfFalse;
						
			int size = (int)Math.min(chunkSize, left[0]);
			ByteBuffer buf = ByteBuffer.allocate(size);
			
			Async.await(src.read(buf));
			buf.flip();
			if(!buf.hasRemaining()) // EOF
				return AsyncLoop.cfFalse;
			
			@SuppressWarnings("unchecked")
			CompletionHandler<Integer, Void>[] handler = new CompletionHandler[1];
			long chunkOffset = offset + offs[0];
			
			offs[0] += size;
			left[0] -= size;
			handler[0] = new CompletionHandler<Integer, Void>() {
				@Override
				public void completed(Integer result, Void dummy) {
					written.addAndGet(result);
					if(buf.hasRemaining())
						dst.write(buf, chunkOffset + buf.position(), (Void)null, handler[0]);
					else
						sema.release();
				}
				@Override
				public void failed(Throwable exc, Void dummy) {
					cont.obtrudeException(exc);
					sema.release();
				}
			};
			Async.await(sema.acquire());
			dst.write(buf, chunkOffset, (Void)null, handler[0]);
			return cont;
		}, exec));

		// wait for any in-flight writes to finish
		for(int i = 0; i < parallel; i++)
			Async.await(sema.acquire());
		cont.getNow(null); // propagate an exception if we have one

		return CompletableFuture.completedFuture(written.get());
	}
	
	// default parallel writes to 2
	public static CompletableFuture<Long> copy(AsynchronousFileChannel dst, long offset, AsyncByteBufferReader src, long len, int chunkSize, Executor exec) {
		return copy(dst, offset, src, len, chunkSize, 2, exec);
	}

	// default chunk size to 128k
	public static CompletableFuture<Long> copy(AsynchronousFileChannel dst, long offset, AsyncByteBufferReader src, long len, Executor exec) {
		return copy(dst, offset, src, len, 128*1024, exec);
	}
}
