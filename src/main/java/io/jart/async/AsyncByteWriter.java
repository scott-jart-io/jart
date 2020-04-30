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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * Simple interface to write ByteBuffers and bytes[]s.
 */
public interface AsyncByteWriter {
	
	/**
	 * Asyncronous write.
	 *
	 * @param buf the buf
	 * @return the completable future
	 */
	public CompletableFuture<Void> write(ByteBuffer buf);
	
	/**
	 * Attempt synchronous write.
	 *
	 * @param buf the buf
	 * @return true, if successful
	 */
	public boolean tryWrite(ByteBuffer buf);
	
	/**
	 * Asynchronous write of byte[] region.
	 *
	 * @param bytes the bytes
	 * @param offs the offs
	 * @param len the len
	 * @return the completable future
	 */
	public CompletableFuture<Void> write(byte[] bytes, int offs, int len);
	
	/**
	 * Attempt synchronous write of byte[] region.
	 *
	 * @param bytes the bytes
	 * @param offs the offs
	 * @param len the len
	 * @return true, if successful
	 */
	public boolean tryWrite(byte[] bytes, int offs, int len);
	
	/**
	 * Asynchronously write an entire byte[].
	 *
	 * @param bytes the bytes
	 * @return the completable future
	 */
	public default CompletableFuture<Void> write(byte[] bytes) {
		return write(bytes, 0, bytes.length);
	}
	
	/**
	 * Attempt to synchronously write an entire byte[].
	 *
	 * @param bytes the bytes
	 * @return true, if successful
	 */
	public default boolean tryWrite(byte[] bytes) {
		return tryWrite(bytes, 0, bytes.length);
	}
	
	/**
	 * Asynchronously write a string using the given Charset.
	 *
	 * @param str the string
	 * @param charset the charset
	 * @return the completable future
	 */
	public default CompletableFuture<Void> write(String str, Charset charset) {
		return write(str.getBytes(charset));
	}
	
	/**
	 * Attempt to synchronously write a string using the given Charset.
	 *
	 * @param str the str
	 * @param charset the charset
	 * @return null on success, byte[] representing str on failure (to pass to the more efficient
	 * write(byte[] bytes) instead of having to encode the string again w/ write(String str, Charset charset))
	 */
	public default byte[] tryWrite(String str, Charset charset) {
		byte[] bytes = str.getBytes(charset);
		
		return tryWrite(bytes) ? null : bytes;
	}
	
	/**
	 * As write(String str, Charset charset) with utf8 encoding.
	 *
	 * @param str the str
	 * @return the completable future
	 */
	public default CompletableFuture<Void> write(String str) {
		return write(str, StandardCharsets.UTF_8);
	}
	
	/**
	 * As tryWrite(String str, Charset charset) with utf8 encoding.
	 *
	 * @param str the str
	 * @return true, if successful
	 */
	public default byte[] tryWrite(String str) {
		return tryWrite(str, StandardCharsets.UTF_8);
	}
}
