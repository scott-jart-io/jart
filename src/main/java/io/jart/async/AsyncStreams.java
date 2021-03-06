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

import java.util.concurrent.CompletableFuture;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import com.ea.async.Async;

/**
 * Helper class for asynchronously working with streams of CompletableFutures.
 */
public class AsyncStreams {
	private AsyncStreams() {} // hide constructor
	
	/**
	 * Asynchronously convert a Stream of CompletableFutures to an array.
	 *
	 * @param <A> the generic type
	 * @param stream the stream of CFs
	 * @param generator the array constructor
	 * @return the completable future
	 */
	public static <A> CompletableFuture<A[]> toArray(Stream<CompletableFuture<A>> stream, IntFunction<A[]> generator) {
		@SuppressWarnings("unchecked")
		CompletableFuture<A>[] cfs = stream.toArray(CompletableFuture[]::new);
		A[] result = generator.apply(cfs.length);
		
		for(int i = 0; i < cfs.length; i++)
			result[i] = Async.await(cfs[i]);
		return CompletableFuture.completedFuture(result);
	}
}
