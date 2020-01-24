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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;

import org.checkerframework.checker.nullness.qual.NonNull;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.RemovalCause;

public class AsyncReadThroughFileCache {
	public static class ReadAhead implements AsyncByteArrayReader {
		private final AsyncReadThroughFileCache cache;
		private final Path path;
		private final CompletableFuture<byte[]>[] reads;
		private int curChunk;
		
		@SuppressWarnings("unchecked")
		public ReadAhead(AsyncReadThroughFileCache cache, Path path, int readAheadChunks, int firstChunk) {
			this.cache = cache;
			this.path = path;
			this.reads = new CompletableFuture[readAheadChunks];
			for(int i = 0; i < readAheadChunks; i++)
				reads[i] = cache.readChunk(path, i);
			this.curChunk = readAheadChunks;
		}
		
		public ReadAhead(AsyncReadThroughFileCache cache, Path path, int firstChunk) {
			this(cache, path, Math.max(1, (128*1024) / cache.chunkSize()), firstChunk);
		}
		
		public ReadAhead(AsyncReadThroughFileCache cache, Path path) {
			this(cache, path, 0);
		}

		public CompletableFuture<byte[]> read() {
			int i = curChunk % reads.length;
			CompletableFuture<byte[]> result = reads[i];
			
			reads[i] = cache.readChunk(path, curChunk++);
			return result;
		}

		public byte[] tryRead() {
			int i = curChunk % reads.length;
			byte[] result = reads[i].getNow(null);
			
			if(result != null)
				reads[i] = cache.readChunk(path, curChunk++);
			return result;
		}
	}
	
	public static final byte[] EOFChunk = AsyncByteArrayReader.EOF;
	
	private static final CompletableFuture<byte[]> cfEOFData = CompletableFuture.completedFuture(EOFChunk);
	
	@SuppressWarnings("serial")
	private static final Set<OpenOption> openOptions = new HashSet<OpenOption>() {{
		add(StandardOpenOption.READ);
	}};
	
	public static class ReadResult {
		public final byte[] data;
		public final int offset;
		
		public ReadResult(byte[] data, int offset) {
			this.data = data;
			this.offset = offset;
		}
		
		public int length() { return data.length - offset; }
	}
	
	public static final ReadResult EOFRR = new ReadResult(EOFChunk, 0);

	private static class ChunkKey {
		public final Path path;
		public final int chunkNum;
		
		public ChunkKey(Path path, int chunkNum) {
			this.path = path;
			this.chunkNum = chunkNum;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + chunkNum;
			result = prime * result + ((path == null) ? 0 : path.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			ChunkKey other = (ChunkKey) obj;
			if (chunkNum != other.chunkNum)
				return false;
			if (path == null) {
				if (other.path != null)
					return false;
			} else if (!path.equals(other.path))
				return false;
			return true;
		}
	}
	
	private final @NonNull AsyncLoadingCache<ChunkKey, byte[]> chunkCache;
	private final @NonNull LoadingCache<Path, AsynchronousFileChannel> channelCache;
	private final int chunkSize;
	
	private CompletableFuture<byte[]> issueRead(Path path, int chunkNum) throws IOException {
		AsynchronousFileChannel fc = channelCache.get(path);
		long offset = chunkSize * (long)chunkNum;
		long fileSize = fc.size();
		
		if(offset >= fileSize)
			return cfEOFData;
		
		CompletableFuture<byte[]> cf = new CompletableFuture<byte[]>();
		byte[] data = new byte[(int)Math.min(chunkSize, fileSize - offset)];
		ByteBuffer buf = ByteBuffer.wrap(data);
		@SuppressWarnings("unchecked")
		CompletionHandler<Integer, Void>[] ch = new CompletionHandler[1];
		
		ch[0] = new CompletionHandler<Integer, Void>() {
			@Override
			public void completed(Integer result, Void dummy) {
				if(!buf.hasRemaining() || result < 0)
					cf.complete(data);
				else
					fc.read(buf, offset + buf.position(), null, ch[0]);
			}
			@Override
			public void failed(Throwable exc, Void attachment) {
				cf.completeExceptionally(exc);
			}
		};
		fc.read(buf, offset, null, ch[0]);
		return cf;
	}

	public AsyncReadThroughFileCache(long size, int channels, int chunkSize, ExecutorService exec) {
		ExecutorService fexec = (exec != null) ? exec : ForkJoinPool.commonPool();

		channelCache = Caffeine.newBuilder()
				.maximumSize(channels)
				.removalListener((Path path, AsynchronousFileChannel channel, RemovalCause cause)->{
						try {
							channel.close();
						} catch (IOException e) {
							throw new RuntimeException(e);
						}
					})
				.executor(fexec)
				.build((Path path)->AsynchronousFileChannel.open(path, openOptions, fexec));
		chunkCache = Caffeine.newBuilder()
				.maximumWeight(size)
				.weigher((ChunkKey key, byte[] chunk)->chunk.length)
				.executor(fexec)
				.buildAsync((ChunkKey key, Executor dummy)->{
					try {
						return issueRead(key.path, key.chunkNum);
					} catch (IOException e) {
						throw new RuntimeException(e);
					}	
				});
		this.chunkSize = chunkSize;
	}
	
	public AsyncReadThroughFileCache(long size, int channels, int chunkSize) {
		this(size, channels, chunkSize, null);
	}
	
	public AsyncReadThroughFileCache(long size, int chunkSize, ExecutorService exec) {
		this(size, 1000, chunkSize, exec);
	}
	
	public AsyncReadThroughFileCache(long size, int chunkSize) {
		this(size, chunkSize, null);
	}
	
	public AsyncReadThroughFileCache(long size, ExecutorService exec) {
		this(size, 4096, exec);
	}
	
	public AsyncReadThroughFileCache(long size) {
		this(size, null);
	}

	public CompletableFuture<byte[]> readChunk(Path path, int chunkNum) {
		return chunkCache.get(new ChunkKey(path, chunkNum));
	}
	
	public CompletableFuture<byte[]> getChunkIfRead(Path path, int chunkNum) {
		return chunkCache.getIfPresent(new ChunkKey(path, chunkNum));
	}
	
	public byte[] getChunkNow(Path path, int chunkNum) {
		CompletableFuture<byte[]> chunkCf = getChunkIfRead(path, chunkNum);
		
		return (chunkCf == null) ? null : chunkCf.getNow(null);
	}
	
	public CompletableFuture<ReadResult> read(Path path, long offset) {
		int chunkNum = (int) (offset / chunkSize);
		CompletableFuture<byte[]> dataCF = readChunk(path, chunkNum);
		
		return dataCF.thenApply((byte[] chunk)->{
			return (chunk == EOFChunk) ? EOFRR :
					new ReadResult(chunk, (int)Math.min(chunk.length, offset - chunkSize * (long)chunkNum));
		});
	}

	public long size(Path path) throws IOException {
		return channelCache.get(path).size();
	}
	
	public int chunkSize() {
		return chunkSize;
	}
}
