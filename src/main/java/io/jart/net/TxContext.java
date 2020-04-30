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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

import io.jart.async.AsyncPipe;

/**
 * Base interface for transmission contexts.
 */
public interface TxContext {
	
	/**
	 * Base interface for packet buffers.
	 */
	public interface Buffer {}
	
	/**
	 * Start a transmit.
	 * Returns a Buffer for use().
	 *
	 * @param exec the Executor to run on
	 * @return the completable future which completes with a Buffer ready for use().
	 */
	public CompletableFuture<Buffer> startTx(Executor exec);
	
	/**
	 * Start a transmit indirectly via a pipe.
	 * As startTx(Executor exec) but delivers the Buffer to dst as translated by fun.
	 *
	 * @param <D> the generic type
	 * @param <O> the generic type
	 * @param dst the destination pipe
	 * @param fun the function to translate the Buffer to whatever the destination pipe wants
	 * @param exec the Executor to run on
	 */
	public<D, O extends D> void startTx(AsyncPipe<D> dst, Function<Buffer, O> fun, Executor exec);
	
	/**
	 * Try to synchronously start a transmit.
	 *
	 * @return the buffer or null on failure
	 */
	public Buffer tryStartTx();
	
	/**
	 * Make a Buffer ready for use and return a ByteBuffer prepped for filling.
	 *
	 * @param buffer the buffer
	 * @return the byte buffer
	 */
	public ByteBuffer use(Buffer buffer);
	
	/**
	 * Mark a Buffer as successfully finished.
	 *
	 * @param buffer the buffer
	 */
	public void finish(Buffer buffer);
	
	/**
	 * Mark a Buffer as aborted.
	 *
	 * @param buffer the buffer
	 */
	// abort it
	public void abort(Buffer buffer);
}
