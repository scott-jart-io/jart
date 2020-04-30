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
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Helper class for doing asyncronous loops (without accidentally building up huge CF chains).
 */
public class AsyncLoop {
	public static final CompletableFuture<Boolean> cfTrue = CompletableFuture.completedFuture(true);
	public static final CompletableFuture<Boolean> cfFalse = CompletableFuture.completedFuture(false);
	public static final CompletableFuture<Void> cfVoid = CompletableFuture.completedFuture((Void)null);
	
	/**
	 * Hide constructor
	 */
	private AsyncLoop() {}
	
	/**
	 * Asynchronous do while loop.
	 *
	 * @param body Supplier representing the loop body -- supplies a CompletableFuture whose result, if true, causes the loop to continue.
	 * @param exec the Executor to run the loop in (null will default to ForkJoinPool commonPool0
	 * @return the completable future to indicate completion of the loop
	 */
	public static CompletableFuture<Void> doWhile(Supplier<CompletableFuture<Boolean>> body, Executor exec) {
		Executor fexec = (exec != null) ? exec : ForkJoinPool.commonPool();
		CompletableFuture<Void> result = new CompletableFuture<Void>();
		@SuppressWarnings("unchecked")
		BiConsumer<Boolean, Throwable>[] complete = new BiConsumer[1];

		complete[0] = (Boolean cont, Throwable throwable)->{
			if(throwable != null)
				result.completeExceptionally(throwable);
			else if(cont)
				body.get().whenCompleteAsync(complete[0], fexec);
			else
				result.complete(null);
		};

		complete[0].accept(true, null);
		return result;
	}

	/**
	 * Asynchronous generic iteration.
	 *
	 * @param <T> the generic type
	 * @param iter the iterator which supplies a future for a T each iteration
	 * @param consumer the consumer which synchronously consumes the T produced by the iterator and if it results in true, continues the iteratio
	 * @param exec the Executor to run on
	 * @return the completable future
	 */
	public static<T> CompletableFuture<Void> iterate(Supplier<CompletableFuture<T>> iter, Predicate<T> consumer, Executor exec) {
		Executor fexec = (exec != null) ? exec : ForkJoinPool.commonPool();
		CompletableFuture<Void> result = new CompletableFuture<Void>();
		@SuppressWarnings("unchecked")
		BiConsumer<Boolean, Throwable>[] complete = new BiConsumer[1];

		complete[0] = (Boolean cont, Throwable throwable)->{
			if(throwable != null)
				result.completeExceptionally(throwable);
			else if(cont)
				iter.get().thenApply((T t)->consumer.test(t)).whenCompleteAsync(complete[0], fexec);
			else
				result.complete(null);
		};

		complete[0].accept(true, null);
		return result;		
	}
}
