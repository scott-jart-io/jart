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

import java.util.function.Supplier;

/**
 * Simple, effective load balancing extension to WorkerThread.
 * after exhausting its own queue, helps a peer --
 * executes own queue in FIFO, like WorkerThread
 * helps from peer queue in LIFO order
 */
public class HelpingWorkerThread extends WorkerThread {
	private HelpingWorkerThread peer;
	private HelpingWorkerThread helper;

	/**
	 * Do some work.
	 */
	@Override
	protected void work() {
		// drain our own queue
		super.work();

		HelpingWorkerThread peer = this.peer;
		
		if(peer != null) {
			// help peer
			for(;;) {
				Runnable r = peer.dq.pollFirst(); // take newer runnables
				
				if(r == null)
					break;
				r.run();
			}
		}
	}

	/**
	 * Execute a command.
	 *
	 * @param command the command
	 */
	@Override
	public void execute(Runnable command) {
		dq.offerFirst(command);
		if(!wake() && helper != null)
			helper.wake();
	}
	
	/**
	 * Gets the peer.
	 *
	 * @return the peer
	 */
	public HelpingWorkerThread getPeer() {
		return peer;
	}

	/**
	 * Sets the peer.
	 *
	 * @param peer the new peer
	 */
	public void setPeer(HelpingWorkerThread peer) {
		peer.helper = this;
		this.peer = peer;
	}
	
	/**
	 * Create a team of HelperWorkerThreads.
	 *
	 * @param threadCount the size of the team
	 * @param threadSupplier the Supplier for new HelpingWorkerThreads
	 * @return array of HelpingWorkerThreads
	 */
	public static HelpingWorkerThread[] createTeam(int threadCount, Supplier<HelpingWorkerThread> threadSupplier) {
		HelpingWorkerThread[] workerThreads = new HelpingWorkerThread[threadCount];
		
		if(threadCount > 0)
			workerThreads[0] = threadSupplier.get();
		for(int i = 1; i < threadCount; i++)
			(workerThreads[i] = threadSupplier.get()).setPeer(workerThreads[i-1]);
		if(threadCount > 1)
			workerThreads[0].setPeer(workerThreads[threadCount - 1]);
		for(int i = 0; i < threadCount; i++)
			workerThreads[i].start();
		return workerThreads;
	}
	
	/**
	 * Create a team of HelperWorkerThreads.
	 *
	 * @param threadCount the size of the team
	 * @return array of HelpingWorkerThreads
	 */
	public static HelpingWorkerThread[] createTeam(int threadCount) {
		return createTeam(threadCount, HelpingWorkerThread::new);
	}
}
