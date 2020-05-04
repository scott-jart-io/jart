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

/**
 * Simple asynchronous read through file cache.
 * 
 * Why do we want this? Isn't the OS cache better? Yes, the OS cache is better in nearly every way.
 * But for us using it would introduce at least 1 additional data copy.
 */
public class AsyncReadThroughFileCache {
	// chunk representing end of file
	public static final byte[] EOFChunk = AsyncByteArrayReader.EOF;
	
	// pre-allocated cf for EOF
	private static final CompletableFuture<byte[]> cfEOFData = CompletableFuture.completedFuture(EOFChunk);
	
	@SuppressWarnings("serial")
	private static final Set<OpenOption> openOptions = new HashSet<OpenOption>() {{
		add(StandardOpenOption.READ);
	}};
	
       /**
        * Implement an AsyncByteArrayReader that immediately issues reads for a number of chunks.
        */
       public static class ReadAhead implements AsyncByteArrayReader {
               private final AsyncReadThroughFileCache cache;
               private final Path path;
               private final CompletableFuture<byte[]>[] reads;
               private final int firstChunk;
               private int curChunk;

               @SuppressWarnings("unchecked")
               public ReadAhead(AsyncReadThroughFileCache cache, Path path, int readAheadChunks, int firstChunk) {
                       this.cache = cache;
                       this.path = path;
                       this.reads = new CompletableFuture[readAheadChunks];
                       this.firstChunk = firstChunk;
                       this.curChunk = readAheadChunks;
                       for(int i = 0; i < readAheadChunks; i++)
                               reads[i] = cache.readChunk(path, firstChunk + i);
               }

               public ReadAhead(AsyncReadThroughFileCache cache, Path path, int firstChunk) {
                       this(cache, path, Math.max(1, (128*1024) / cache.chunkSize()), firstChunk);
               }

               public ReadAhead(AsyncReadThroughFileCache cache, Path path) {
                       this(cache, path, 0);
               }

               // reads must be serial -- may not request 2 reads at once
               public CompletableFuture<byte[]> read() {
                       int i = curChunk % reads.length;
                       CompletableFuture<byte[]> result = reads[i];

                       reads[i] = cache.readChunk(path, firstChunk + curChunk++);
                       return result;
               }

               // no async reads can be pending
               public byte[] tryRead() {
                       int i = curChunk % reads.length;
                       byte[] result = reads[i].getNow(null);

                       if(result != null)
                               reads[i] = cache.readChunk(path, firstChunk + curChunk++);
                       return result;
               }
       }

	/**
	 * Result of a read operation.
	 */
	public static class ReadResult {
		public final byte[] data; // data for this read result
		public final int offset; // offset in data at which applicable data for this read result starts
		
		/**
		 * Instantiates a new read result.
		 *
		 * @param data the data
		 * @param offset the offset
		 */
		public ReadResult(byte[] data, int offset) {
			this.data = data;
			this.offset = offset;
		}
		
		/**
		 * Length of data.
		 *
		 * @return the int
		 */
		public int length() { return data.length - offset; }
	}
	
	// ReadResult representing EOF
	public static final ReadResult EOFRR = new ReadResult(EOFChunk, 0);

	/**
	 * Key for a chunk.
	 */
	private static class ChunkKey {
		public final Path path; // file path
		public final int chunkNum; // and chunk number
		
		/**
		 * Instantiates a new chunk key.
		 *
		 * @param path the path
		 * @param chunkNum the chunk num
		 */
		public ChunkKey(Path path, int chunkNum) {
			this.path = path;
			this.chunkNum = chunkNum;
		}

		/**
		 * Hash code.
		 *
		 * @return the int
		 */
		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + chunkNum;
			result = prime * result + ((path == null) ? 0 : path.hashCode());
			return result;
		}

		/**
		 * Equals.
		 *
		 * @param obj the obj
		 * @return true, if successful
		 */
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
	
	// caffeine cache for actual chunk data
	private final @NonNull AsyncLoadingCache<ChunkKey, byte[]> chunkCache;
	// caffeine cache for file channels
	private final @NonNull LoadingCache<Path, AsynchronousFileChannel> channelCache;
	// chunk size for this cache
	private final int chunkSize;
	
	/**
	 * Issue a read.
	 *
	 * @param path the path
	 * @param chunkNum the chunk num
	 * @return the completable future
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
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

	/**
	 * Instantiates a new cache.
	 *
	 * @param size the maximum number of bytes to cache
	 * @param channels the maxmimum number of channels to have open
	 * @param chunkSize the data chunk size
	 * @param exec the Executor to run on (null means ForkJoinPool commonPool)
	 */
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
	
	/**
	 * Instantiates a new cache w/ default Executor.
	 *
	 * @param size the maximum number of bytes to cache
	 * @param channels the maxmimum number of channels to have open
	 * @param chunkSize the data chunk size
	 */
	public AsyncReadThroughFileCache(long size, int channels, int chunkSize) {
		this(size, channels, chunkSize, null);
	}
	
	/**
	 * Instantiates a new cache w/ default max open channels.
	 *
	 * @param size the maximum number of bytes to cache
	 * @param chunkSize the data chunk size
	 * @param exec the Executor to run on (null means ForkJoinPool commonPool)
	 */
	public AsyncReadThroughFileCache(long size, int chunkSize, ExecutorService exec) {
		this(size, 1000, chunkSize, exec);
	}
	
	/**
	 * Instantiates a new cache w/ default Executor and max open channels.
	 *
	 * @param size the maximum number of bytes to cache
	 * @param chunkSize the data chunk size
	 */
	public AsyncReadThroughFileCache(long size, int chunkSize) {
		this(size, chunkSize, null);
	}
	
	/**
	 * Instantiates a new cache w/ default max open channels, chunk size.
	 *
	 * @param size the maximum number of bytes to cache
	 * @param exec the Executor to run on (null means ForkJoinPool commonPool)
	 */
	public AsyncReadThroughFileCache(long size, ExecutorService exec) {
		this(size, 4096, exec);
	}
	
	/**
	 * Instantiates a new async read through file cache with all defaults.
	 *
	 * @param size the size
	 */
	public AsyncReadThroughFileCache(long size) {
		this(size, null);
	}

	/**
	 * Read a chunk.
	 *
	 * @param path the file path
	 * @param chunkNum the chunk number
	 * @return the completable future
	 */
	public CompletableFuture<byte[]> readChunk(Path path, int chunkNum) {
		return chunkCache.get(new ChunkKey(path, chunkNum));
	}
	
	/**
	 * Gets the chunk if read.
	 * May not be completed, but the read has been issued.
	 *
	 * @param path the file path
	 * @param chunkNum the chunk number
	 * @return the chunk if read
	 */
	public CompletableFuture<byte[]> getChunkIfRead(Path path, int chunkNum) {
		return chunkCache.getIfPresent(new ChunkKey(path, chunkNum));
	}
	
	/**
	 * Tries to get the chunk now.
	 *
	 * @param path the file path
	 * @param chunkNum the chunk number
	 * @return the chunk if cached or null
	 */
	public byte[] getChunkNow(Path path, int chunkNum) {
		CompletableFuture<byte[]> chunkCf = getChunkIfRead(path, chunkNum);
		
		return (chunkCf == null) ? null : chunkCf.getNow(null);
	}
	
	/**
	 * Read data at the given offset.
	 *
	 * @param path the file path
	 * @param offset the file offset
	 * @return the completable future
	 */
	public CompletableFuture<ReadResult> read(Path path, long offset) {
		int chunkNum = (int) (offset / chunkSize);
		CompletableFuture<byte[]> dataCF = readChunk(path, chunkNum);
		
		return dataCF.thenApply((byte[] chunk)->{
			return (chunk == EOFChunk) ? EOFRR :
					new ReadResult(chunk, (int)Math.min(chunk.length, offset - chunkSize * (long)chunkNum));
		});
	}

	/**
	 * Gets the size of the file at path.
	 * Faults the file into the channel cache.
	 *
	 * @param path the file path
	 * @return the size of the file
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	public long size(Path path) throws IOException {
		return channelCache.get(path).size();
	}
	
	/**
	 * Returns the chunk size provided at construction time.
	 *
	 * @return the int
	 */
	public int chunkSize() {
		return chunkSize;
	}
}
