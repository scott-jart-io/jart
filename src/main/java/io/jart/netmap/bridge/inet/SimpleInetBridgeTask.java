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

package io.jart.netmap.bridge.inet;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.apache.log4j.Logger;

import com.ea.async.Async;

import io.jart.async.AsyncLoop;
import io.jart.async.AsyncRunnable;
import io.jart.async.AsyncStreams;
import io.jart.net.EthPkt;
import io.jart.net.Ip4AddrAndPort;
import io.jart.net.TcpContext;
import io.jart.net.TcpLoop;
import io.jart.netmap.NMReq;
import io.jart.netmap.NetmapIF;
import io.jart.netmap.Netmapping;
import io.jart.netmap.bridge.BridgeTask;
import io.jart.netmap.bridge.BufferPipeTask;
import io.jart.netmap.bridge.BufferSwapTask;
import io.jart.netmap.bridge.BufferUnlockerTask;
import io.jart.netmap.bridge.EventQueue;
import io.jart.netmap.bridge.EventQueueTask;
import io.jart.netmap.bridge.MsgRelay;
import io.jart.netmap.bridge.MsgRelayTask;
import io.jart.netmap.bridge.inet.BaseTcpContext.BaseIp4EthBufPipeTxFactory;

// TODO: Auto-generated Javadoc
/**
 * The Class SimpleInetBridgeTask.
 */
public class SimpleInetBridgeTask implements AsyncRunnable {
	private static final Logger logger = Logger.getLogger(SimpleInetBridgeTask.class);

	/**
	 * The listener interface for receiving tcpLoop events.
	 * The class that is interested in processing a tcpLoop
	 * event implements this interface, and the object created
	 * with that class is registered with a component using the
	 * component's <code>addTcpLoopListener<code> method. When
	 * the tcpLoop event occurs, that object's appropriate
	 * method is invoked.
	 *
	 * @see TcpLoopEvent
	 */
	public static interface TcpLoopListener {
		
		/**
		 * Accept.
		 *
		 * @param tcpContext the tcp context
		 * @return the tcp loop
		 */
		TcpLoop accept(TcpContext tcpContext);
	}
	
	/**
	 * The Class Context.
	 */
	public class Context {
		public final MsgRelay msgRelay;
		public final EventQueue eventQueue;
		
		/**
		 * Instantiates a new context.
		 *
		 * @param msgRelay the msg relay
		 * @param eventQueue the event queue
		 */
		public Context(MsgRelay msgRelay, EventQueue eventQueue) {
			this.msgRelay = msgRelay;
			this.eventQueue = eventQueue;
		}
		
		/**
		 * Ip 4 tcp listen.
		 *
		 * @param aap the aap
		 * @param tll the tll
		 */
		public void ip4TcpListen(Ip4AddrAndPort aap, TcpLoopListener tll) {
			SimpleInetBridgeTask.this.ip4TcpListen(aap, tll);
		}
		
		/**
		 * Ip 4 tcp listener remove.
		 *
		 * @param aap the aap
		 */
		public void ip4TcpListenerRemove(Ip4AddrAndPort aap) {
			SimpleInetBridgeTask.this.ip4TcpListenerRemove(aap);
		}
	}
	
	private final String devName;
	private final Executor exec;
	private final OutputStream pcapOs;
	
	private InetBufferSwapTask.Context[] ibsContexts;
	private BaseTcpContext.EthBufPipeTxFactory[] ebptrFactorys;
	
	public final CompletableFuture<Context> context = new CompletableFuture<Context>();

	/**
	 * Ip 4 tcp listen.
	 *
	 * @param aap the aap
	 * @param tll the tll
	 */
	private void ip4TcpListen(Ip4AddrAndPort aap, TcpLoopListener tll) {
		aap = aap.dupe();

		for(int i = 0; i < ibsContexts.length; i++) {
			final int n = i % ebptrFactorys.length;
			InetBufferSwapTask.Context ibsc = ibsContexts[i];
			
			ibsc.ip4TcpListeners.put(aap, ibsc.ip4TcpListener((firstPacket)->{
				TcpContext tcpContext = new Ip4TcpContext(ebptrFactorys[n], firstPacket);
				TcpLoop tcpLoop = tll.accept(tcpContext);

				return new BaseTcpHandler(tcpContext.getPipe(), tcpLoop);
			}));
		}
	}
	
	/**
	 * Ip 4 tcp listener remove.
	 *
	 * @param aap the aap
	 */
	private void ip4TcpListenerRemove(Ip4AddrAndPort aap) {
		for(InetBufferSwapTask.Context ibsc: ibsContexts)
			ibsc.ip4TcpListeners.remove(aap);
	}
	
	/**
	 * Instantiates a new simple inet bridge task.
	 *
	 * @param devName the dev name
	 * @param exec the exec
	 * @param pcapOs the pcap os
	 */
	public SimpleInetBridgeTask(String devName, Executor exec, OutputStream pcapOs) {
		this.devName = devName;
		this.exec = exec;
		this.pcapOs = pcapOs;
	}

	/**
	 * Instantiates a new simple inet bridge task.
	 *
	 * @param devName the dev name
	 * @param exec the exec
	 */
	public SimpleInetBridgeTask(String devName, Executor exec) {
		this(devName, exec, null);
	}
	
	/**
	 * Run.
	 *
	 * @return the completable future
	 */
	@Override
	public CompletableFuture<Void> run() {
		try (Netmapping nmsw = Netmapping.regSW(devName); Netmapping nmnic = Netmapping.regNicFromSW(nmsw)) {
			if (nmnic.mem != nmsw.mem)
				throw new RuntimeException("only zero copy supported");

			int nicRxRingCount = (int) NetmapIF.getRXRings(nmnic.IF);
			int nicTxRingCount = (int) NetmapIF.getTXRings(nmnic.IF);
			int hostRxRingCount = (int) NetmapIF.getHostRXRings(nmnic.IF);
			int hostTxRingCount = (int) NetmapIF.getHostTXRings(nmnic.IF);
			
			logger.debug(String.format("nic %s", nmnic));
			logger.debug(String.format("nic has %d rx rings / %d rx ring slots / %d rx buf size",
					nicRxRingCount, NMReq.getRXSlots(nmnic.nmreq.buf), nmnic.rxBufSize));
			logger.debug(String.format("nic has %d tx rings / %d tx ring slots / %d tx buf size",
					nicTxRingCount, NMReq.getTXSlots(nmnic.nmreq.buf), nmnic.txBufSize));
			logger.debug(String.format("sw %s", nmsw));
			logger.debug(String.format("sw has %d rx rings / %d rx ring slots",
					hostRxRingCount, NMReq.getRXSlots(nmsw.nmreq.buf)));
			logger.debug(String.format("sw has %d tx rings / %d tx ring slots",
					hostTxRingCount, NMReq.getTXSlots(nmsw.nmreq.buf)));

			logger.debug("waiting for link to come up");
			Thread.sleep(10000);
			logger.debug("link up");

			int nicRxSlots = Stream.of(nmnic.rxRings).map((Netmapping.Ring r)->r.slots.length)
					.reduce(0, Integer::sum);
			int nicTxSlots = Stream.of(nmnic.txRings).map((Netmapping.Ring r)->r.slots.length)
					.reduce(0, Integer::sum);
			int hostRxSlots = Stream.of(nmsw.rxRings).map((Netmapping.Ring r)->r.slots.length)
					.reduce(0, Integer::sum);
			int hostTxSlots = Stream.of(nmsw.txRings).map((Netmapping.Ring r)->r.slots.length)
					.reduce(0, Integer::sum);
			int nicSlots = nicRxSlots + nicTxSlots;
			int hostSlots = hostRxSlots + hostTxSlots;
			@SuppressWarnings("unused")
			int totalSlots = nicSlots + hostSlots;

			AtomicInteger bridgePollCount = new AtomicInteger();
			BridgeTask bridgeTask = new BridgeTask(nmnic, nmsw, bridgePollCount, exec);
			CompletableFuture<Void> btComplete = bridgeTask.run();

			BridgeTask.Context bridgeContext = Async.await(bridgeTask.context);

			logger.debug("bridge task up");

			BufferUnlockerTask bulTask = new BufferUnlockerTask(bridgeContext);

			bridgeContext.taskQueue.offer(bulTask);
			bridgeContext.pipe.write(bridgeContext.nopMsg);

			BufferUnlockerTask.Context bulContext = Async.await(bulTask.context);

			logger.debug("bul task up");

			BufferPipeTask[] nicRxBpTasks = new BufferPipeTask[nicRxRingCount];
			
			for(int i = 0; i < nicRxRingCount; i++)
				bridgeContext.taskQueue.offer(
						nicRxBpTasks[i] = new BufferPipeTask(bridgeContext, nmnic.rxRings[i], bulContext));

			BufferPipeTask[] swRxBpTasks = new BufferPipeTask[hostRxRingCount];

			for(int i = 0; i < hostRxRingCount; i++)
				bridgeContext.taskQueue.offer(
						swRxBpTasks[i] = new BufferPipeTask(bridgeContext, nmsw.rxRings[i], bulContext));

			BufferPipeTask[] nicTxBpTasks = new BufferPipeTask[nicTxRingCount];

			for(int i = 0; i < nicTxRingCount; i++)
				bridgeContext.taskQueue.offer(
						nicTxBpTasks[i] = new BufferPipeTask(bridgeContext, nmnic.txRings[i], bulContext));

			BufferPipeTask[] swTxBpTasks = new BufferPipeTask[hostTxRingCount];

			for(int i = 0; i < hostTxRingCount; i++)
				bridgeContext.taskQueue.offer(
						swTxBpTasks[i] = new BufferPipeTask(bridgeContext, nmsw.txRings[i], bulContext));

			bridgeContext.pipe.write(bridgeContext.nopMsg);

			BufferPipeTask.Context[] nicRxBpContexts = Async.await(AsyncStreams.toArray(Stream.of(nicRxBpTasks).map((task)->task.context), BufferPipeTask.Context[]::new));
			BufferPipeTask.Context[] swRxBpContexts = Async.await(AsyncStreams.toArray(Stream.of(swRxBpTasks).map((task)->task.context), BufferPipeTask.Context[]::new));
			BufferPipeTask.Context[] nicTxBpContexts = Async.await(AsyncStreams.toArray(Stream.of(nicTxBpTasks).map((task)->task.context), BufferPipeTask.Context[]::new));
			BufferPipeTask.Context[] swTxBpContexts = Async.await(AsyncStreams.toArray(Stream.of(swTxBpTasks).map((task)->task.context), BufferPipeTask.Context[]::new));

			logger.debug("buffer pipe tasks up");

			ByteBuffer someRing = nmnic.rxRings[0].ring;
			AtomicInteger nic2swSwapCount = new AtomicInteger();
			InetBufferSwapTask nic2swBsTasks[] = new InetBufferSwapTask[nicRxRingCount];
			
			for(int i = 0; i < nicRxRingCount; i++)
				bridgeContext.taskQueue.offer(nic2swBsTasks[i] = new InetBufferSwapTask(bridgeContext, bulContext, nicRxBpContexts[i],
					swTxBpContexts[i % hostTxRingCount], someRing, nic2swSwapCount));
			
			AtomicInteger sw2nicSwapCount = new AtomicInteger();
			BufferSwapTask sw2nicBsTasks[] = new BufferSwapTask[hostRxRingCount];
			
			for(int i = 0; i < hostRxRingCount; i++)
				bridgeContext.taskQueue.offer(sw2nicBsTasks[i] = new BufferSwapTask(bulContext, swRxBpContexts[i],
						nicTxBpContexts[i % nicTxRingCount], sw2nicSwapCount));

			bridgeContext.pipe.write(bridgeContext.nopMsg);

			ibsContexts = Async.await(AsyncStreams.toArray(Stream.of(nic2swBsTasks).map((task)->task.context), InetBufferSwapTask.Context[]::new));

			Async.await(AsyncStreams.toArray(Stream.of(sw2nicBsTasks).map((task)->task.context), BufferSwapTask.Context[]::new));
			
			logger.debug("ibs tasks up");

			MsgRelayTask mrTask = new MsgRelayTask(bridgeContext);

			bridgeContext.taskQueue.offer(mrTask);
			bridgeContext.pipe.write(bridgeContext.nopMsg);

			MsgRelayTask.Context mrContext = Async.await(mrTask.context);

			logger.debug("mr task up");

			MsgRelay msgRelay = new MsgRelay(bridgeContext, mrContext);

			EventQueueTask eqTask = new EventQueueTask(bridgeContext);

			bridgeContext.taskQueue.offer(eqTask);
			bridgeContext.pipe.write(bridgeContext.nopMsg);

			EventQueueTask.Context eqContext = Async.await(eqTask.context);

			logger.debug("eq task up");

			EventQueue eventQueue = new EventQueue(eqContext);

			if(pcapOs != null) {
				EthPkt.writePcapHeader(pcapOs);
				ebptrFactorys = Stream.of(nicTxBpContexts).map((context)->new BaseTcpContext.PcapIp4EthBufPipeTxFactory(bridgeContext,
						context, nmnic.txBufSize, bulContext, eventQueue, someRing, exec, pcapOs)).toArray(BaseTcpContext.EthBufPipeTxFactory[]::new);
			}
			else
				ebptrFactorys = Stream.of(nicTxBpContexts).map((context)->new BaseIp4EthBufPipeTxFactory(bridgeContext, context, nmnic.txBufSize, bulContext, eventQueue, someRing, exec)).toArray(BaseTcpContext.EthBufPipeTxFactory[]::new);
			
			/*
			 * TODO ip4TcpConnect method
			if (false) {
				int srcPort = 9876;
				InetAddress inetAddress = InetAddress.getByName("someip");
				int dstAddr = 0;
				for (byte b : inetAddress.getAddress())
					dstAddr = dstAddr << 8 | (b & 0xff);
				int dstPort = 6379;

				long dstMac = 0xsomething; // gateway
				long srcMac = 0xmymac;

				IpTxContext ipTxContext = new Ip4TxContext(
						ebptrFactory.createDlTxCtx(dstMac, srcMac, EthPkt.ETHERTYPE_IP4),
						TcpPkt.PROTO_TCP, ip4Addr, dstAddr);
				TcpTxContext tcpTxContext = new BaseTcpTxContext(ipTxContext, srcPort, dstPort);
				ByteBuffer ethBuf = ebptrFactory.createEthBuf();
				TcpContext tcpContext = new BaseTcpContext(ethBuf, ebptrFactory.createPipe(), tcpTxContext,
						bulContext);

				ibsContext.ip4TcpConns.put(new Ip4AddrAndPortPair(dstAddr, dstPort, ip4Addr, srcPort),
						tcpContext.getPipe());
				bridgeContext.taskQueue
						.offer(new EchoTcpLoop(tcpContext, mss, eventQueue, seqNumSupplier.getAsInt(), exec));
			}
			*/
			
			context.complete(new Context(msgRelay, eventQueue));
			Async.await(btComplete);
		} catch (Exception e) {
			CompletableFuture<Void> cf = new CompletableFuture<Void>();
			
			cf.completeExceptionally(e);
			return cf;
		}
		finally {
			ibsContexts = null;
			ebptrFactorys = null;
		}
		return AsyncLoop.cfVoid;
	}

}
