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

package io.jart.util;

import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Executor that delegates execution to the most recent executor thread (when currentThread() implements Executor, like WorkerThread).
 */
public class ThreadAffinityExecutor implements Executor {
	private Executor exec; // current Executor to delegat to
	
	/**
	 * Instantiates a new thread affinity executor.
	 *
	 * @param exec the initial delegate Executor
	 */
	public ThreadAffinityExecutor(Executor exec) {
		if(exec instanceof ThreadAffinityExecutor)
			this.exec = ((ThreadAffinityExecutor)exec).exec;
		else if(exec != null)
			this.exec = exec;
		else {
			Thread currentThread = Thread.currentThread();
			
			if(currentThread instanceof Executor)
				this.exec = (Executor)currentThread;
			else
				this.exec = ForkJoinPool.commonPool();
		}
	}
	
	/**
	 * Submit the command to the current delegate Executor.
	 * When the command begins executing, if the executing thread is an Executor, that Executor becomes the new
	 * current delegate Executor.
	 *
	 * @param command the command
	 */
	@Override
	public void execute(Runnable command) {
		exec.execute(()->{
			Thread currentThread = Thread.currentThread();
			
			if(currentThread instanceof Executor) // if the current thread implements Executor, adopt it
				exec = (Executor)currentThread;
			command.run();
		});
	}

}
