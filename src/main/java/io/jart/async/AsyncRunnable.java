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
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;

/**
 * Asynchronous runnable interface.
 */
public interface AsyncRunnable {
	
	/**
	 * Run asynchronously.
	 *
	 * @return the completable future
	 */
	public CompletableFuture<Void> run();
	
	/**
	 * Convert to a ForkJoinTask for interop w/ ForkJoinPools.
	 *
	 * @return the fork join task
	 */
	@SuppressWarnings("serial")
	public default ForkJoinTask<Void> asForkJoinTask() {
		return new ForkJoinTask<Void>() {
			@Override
			public Void getRawResult() {
				return null;
			}
			@Override
			protected void setRawResult(Void value) {
			}
			@Override
			protected boolean exec() {
				String name = Thread.currentThread().getName();
				
				Thread.currentThread().setName(name + " - AsyncRunnable");
				try {
					CompletableFuture<Void> cf = run();
					ForkJoinPool.ManagedBlocker blocker = new ForkJoinPool.ManagedBlocker() {
						@Override
						public boolean block() throws InterruptedException {
							cf.join();
							return true;
						}
						@Override
						public boolean isReleasable() {
							return cf.isDone();
						}
					};

					ForkJoinPool.managedBlock(blocker);
					return true;
				} catch (InterruptedException e) {
					return false;
				} finally {
					Thread.currentThread().setName(name);
				}
			}			
		};
	}
}
