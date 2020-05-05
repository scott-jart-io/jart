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

package io.jart.test;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.log4j.Logger;

import com.ea.async.Async;

import io.jart.async.AsyncReadThroughFileCache;
import io.jart.async.AsyncRunnable;
import io.jart.memcached.Memcached.Key;
import io.jart.memcached.Memcached.Value;
import io.jart.net.Ip4AddrAndPort;
import io.jart.net.TcpContext;
import io.jart.netmap.bridge.inet.SimpleInetBridgeTask;
import io.jart.util.FixedSizeMap;
import io.jart.util.HelpingWorkerThread;
import io.jart.util.Misc;
import io.jart.util.RTThreadFactory;
import io.jart.util.RoundRobinExecutor;

/**
 * Sample app that brings up a tcp/ip layer on top of a basic netmap bridge.
 * Toy memcached, and httpd layers available.
 */
public class NetmappingBridge {
	static {
		Async.init();
	}
	
	private static final Logger logger = Logger.getLogger(NetmappingBridge.class);

	/**
	 * HelpingWorkerThread subclass that attempts to run in real time priority and w/ cpu affinity.
	 */
	private static class NMWorkerThread extends HelpingWorkerThread {
		private static final AtomicInteger nextCpu = new AtomicInteger();
		
		/**
		 * Attempts to move to real time priority, enabled cpu affinity, and then class super.
		 */
		@Override
		public void run() {
			setName(RTThreadFactory.setRTPrio() ?  "NM RT" : "NM");
			
			int cpu = nextCpu.getAndIncrement();
			
			if(RTThreadFactory.setCpuAffinity(cpu))
				setName(getName() + " CPU " + cpu);
			super.run();
		}
	}
	
	/**
	 * Async main.
	 *
	 * @param args command line arguments
	 * @return the completable future whose completion signals primary execution has completed
	 */
	private static CompletableFuture<Void> asyncMain(String[] args) {
		try {
			String devName = args[0]; // arg 0: device name
			int mss = Integer.parseInt(args[1]); // arg 1: tcp mss
			int threadCount = Integer.parseInt(args[2]); // arg 2: thread count
			
			// create threadCount worker threads
			HelpingWorkerThread[] workerThreads = HelpingWorkerThread.createTeam(threadCount, NMWorkerThread::new);			
			// wrap worker threads in an Executor
			Executor exec = new RoundRobinExecutor(workerThreads);
			SimpleInetBridgeTask sibt = new SimpleInetBridgeTask(devName, exec);
			CompletableFuture<Void> sibtCf = sibt.run();
			// wait for either failure or SimpleInetBridgeTask.Context to become available
			CompletableFuture<Object> sibtCtxOrExit = CompletableFuture.anyOf(sibtCf, sibt.context);
			// cast to SimpleInetBridgeTask.Context (or fail if it was anything else)
			SimpleInetBridgeTask.Context sibtContext = (SimpleInetBridgeTask.Context)Async.await(sibtCtxOrExit);
			int ip4Addr = Misc.guessIp4Addr(devName);
			Executor connExec = null; // default
			SecureRandom secRand = new SecureRandom();
			// create an underlying cache for toy memcached
			Map<Key, Value> mcCache = new FixedSizeMap<Key, Value>(1024 * 1024, 512 * 1024 * 1024, (Key key, Value val)->{
				return (long)val.efkvp.length;	
			});
			// create a file cache for the httpd
			AsyncReadThroughFileCache fc = new AsyncReadThroughFileCache(512 * 1024 * 1024);

			// start memcached
			sibtContext.ip4TcpListen(new Ip4AddrAndPort(ip4Addr, 11211), (TcpContext tcpContext)->{
				return new MemcachedLoop(mcCache, tcpContext, mss, sibtContext.eventQueue, secRand.nextInt(), exec);
			});

			// start httpd
			sibtContext.ip4TcpListen(new Ip4AddrAndPort(ip4Addr, 80), (TcpContext tcpContext)->{
				return new HTTPDTestConnection(tcpContext, mss, sibtContext.eventQueue, sibtContext.msgRelay, secRand.nextInt(), exec, fc, connExec);
			});
			
			// we're done when the SimpleInetBridgeTask is done
			return sibtCf;
		} catch (Throwable th) {
			CompletableFuture<Void> cf = new CompletableFuture<Void>();
			
			logger.error("main threw exception", th);
			cf.completeExceptionally(th);
			return cf;
		}
	}
	
	/**
	 * The main method.
	 *
	 * @param args command line arguments
	 * @throws InterruptedException the interrupted exception
	 */
	public static void main(String[] args) throws InterruptedException {
		// start async main
		AsyncRunnable asyncMain = ()->asyncMain(args);
		ForkJoinTask<Void> mainTask = asyncMain.asForkJoinTask();
		ForkJoinPool.commonPool().execute(mainTask);

		// wait for async main to complete
		mainTask.join();
	}
}
