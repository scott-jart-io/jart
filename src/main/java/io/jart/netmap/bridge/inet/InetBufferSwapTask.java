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

import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.ToIntFunction;

import org.apache.log4j.Logger;

import io.jart.async.AsyncLoop;
import io.jart.async.AsyncPipe;
import io.jart.async.AsyncRunnable;
import io.jart.net.EthPkt;
import io.jart.net.InetAddrAndPort;
import io.jart.net.InetAddrAndPortPair;
import io.jart.net.Ip4AddrAndPort;
import io.jart.net.Ip4AddrAndPortPair;
import io.jart.net.Ip4Pkt;
import io.jart.net.TcpPkt;
import io.jart.net.UdpPkt;
import io.jart.netmap.NetmapRing;
import io.jart.netmap.bridge.BridgeTask;
import io.jart.netmap.bridge.BufferPipeTask;
import io.jart.netmap.bridge.BufferRef;
import io.jart.netmap.bridge.BufferSwapTask;
import io.jart.netmap.bridge.BufferUnlockerTask;
import io.jart.pojo.Helper.POJO;

// delegate (some) received internet packets
public class InetBufferSwapTask extends BufferSwapTask {
	private static final Logger logger = Logger.getLogger(InetBufferSwapTask.class);

	public class Context {
		public final IpPacket.Alloc ipPacketAlloc;
		
		// tcp
		public final Map<InetAddrAndPort, ToIntFunction<? super IpPacket>> ip4TcpListeners;
		public final Map<InetAddrAndPortPair, ToIntFunction<? super IpPacket>> ip4TcpConns;
		
		// udp
		public final Map<InetAddrAndPort, ToIntFunction<? super IpPacket>> ip4UdpListeners;

		public Context(IpPacket.Alloc ipPacketAlloc, 
				Map<InetAddrAndPort, ToIntFunction<? super IpPacket>> ip4TcpListeners, Map<InetAddrAndPortPair, ToIntFunction<? super IpPacket>> ip4TcpConns,
				Map<InetAddrAndPort, ToIntFunction<? super IpPacket>> ip4UdpListeners) {
			this.ipPacketAlloc = ipPacketAlloc;
			this.ip4TcpListeners = ip4TcpListeners;
			this.ip4TcpConns = ip4TcpConns;
			this.ip4UdpListeners = ip4UdpListeners;
		}
		
		public ToIntFunction<? super IpPacket> ip4TcpListener(IpConnListener listener) {
			return InetBufferSwapTask.this.ip4TcpListener(listener);
		}
	}

	@POJO(fieldOrder = { "ethPos", "ipPos", "ipPayloadPos", "endPos", "bufferRef" })
	public static class IpPacket {
		protected int ethPos; // ethernet head pos (someRing-relative)
		protected int ipPos; // ip header pos
		protected int ipPayloadPos; // ip payload  pos
		protected int endPos; // end of packet
		protected BufferRef bufferRef;

		protected IpPacket() {}

		public interface Alloc {
			IpPacket alloc(int ethPod, int ipPos, int ipPayloadPos, int endPos, BufferRef bufferRef);
			void free(IpPacket packet);
		}
		
		public int getEthPos() {
			return ethPos;
		}

		public int getIpPos() {
			return ipPos;
		}

		public int getIpPayloadPos() {
			return ipPayloadPos;
		}

		public int getEndPos() {
			return endPos;
		}

		public BufferRef getBufferRef() {
			return bufferRef;
		}		
	}

	// handles an ip-based connection
	public static interface IpConnHandler extends AsyncRunnable {
		AsyncPipe<? super IpPacket> getPacketPipe(); // return the pipe to which packets will be written
		void disposeMsg(Object obj); // dispose of unhandled non-IpPacket messages in the packet pipe at connection termination time
	}

	// may be implemented by an object that is associated with an IpConnHandler
	public static interface IpConnHandlerAssociate {
		IpConnHandler getIpConnHandler();
	}
	
	// listens to (and accepts) ip-based connections
	public static interface IpConnListener {
		IpConnHandler accept(IpPacket firstPacket);
	}

	private final BridgeTask.Context bridgeContext;
	private final ByteBuffer someRing;
	private final ByteBuffer ethBuf;
	private final ByteBuffer ethPayloadBuf;
	private final ByteBuffer ipPayloadBuf;
	private final Ip4AddrAndPort ip4AddrAndPort = new Ip4AddrAndPort();
	private final Ip4AddrAndPortPair ip4AddrAndPortPair = new Ip4AddrAndPortPair();
	
	private final IpPacket.Alloc ipPacketAlloc;
	
	// tcp-over-ip4 connections and listeners
	private final Map<InetAddrAndPort, ToIntFunction<? super IpPacket>> ip4TcpListeners =
			new ConcurrentHashMap<InetAddrAndPort, ToIntFunction<? super IpPacket>>();
	private final Map<InetAddrAndPortPair, ToIntFunction<? super IpPacket>> ip4TcpConns = new ConcurrentHashMap<InetAddrAndPortPair, ToIntFunction<? super IpPacket>>();

	// udp-over-ip4 listeners
	private final Map<InetAddrAndPort, ToIntFunction<? super IpPacket>> ip4UdpListeners = new ConcurrentHashMap<InetAddrAndPort, ToIntFunction<? super IpPacket>>();

	public final CompletableFuture<Context> context = new CompletableFuture<Context>();

	public InetBufferSwapTask(BridgeTask.Context bridgeContext, BufferUnlockerTask.Context bufferUnlockerContext, BufferPipeTask.Context rx, BufferPipeTask.Context tx,
			ByteBuffer someRing, AtomicInteger swapCount, IpPacket.Alloc ipPacketAlloc, Executor exec) {
		super(bufferUnlockerContext, rx, tx, swapCount, exec);
		this.bridgeContext = bridgeContext;
		this.someRing = someRing;
		ethBuf = someRing.duplicate().order(ByteOrder.BIG_ENDIAN);
		ethPayloadBuf = someRing.duplicate().order(ByteOrder.BIG_ENDIAN);
		ipPayloadBuf = someRing.duplicate().order(ByteOrder.BIG_ENDIAN);
		try {
			if(ipPacketAlloc == null)
				ipPacketAlloc = (IpPacket.Alloc)io.jart.pojo.Helper.defaultHelper.newTrivialAlloc(IpPacket.class);
		} catch (ClassNotFoundException | NoSuchMethodException | SecurityException | InstantiationException
				| IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
			throw new RuntimeException(e);
		}
		
		this.ipPacketAlloc = ipPacketAlloc;
	}

	public InetBufferSwapTask(BridgeTask.Context bridgeContext, BufferUnlockerTask.Context bufferUnlockerContext, BufferPipeTask.Context rx, BufferPipeTask.Context tx,
			ByteBuffer someRing, AtomicInteger swapCount, IpPacket.Alloc ipPacketAlloc) {
		this(bridgeContext, bufferUnlockerContext, rx, tx, someRing, swapCount, ipPacketAlloc, null);
	}

	public InetBufferSwapTask(BridgeTask.Context bridgeContext, BufferUnlockerTask.Context bufferUnlockerContext, BufferPipeTask.Context rx, BufferPipeTask.Context tx,
			ByteBuffer someRing, AtomicInteger swapCount, Executor exec) {
		this(bridgeContext, bufferUnlockerContext, rx, tx, someRing, swapCount, null, exec);
	}

	public InetBufferSwapTask(BridgeTask.Context bridgeContext, BufferUnlockerTask.Context bufferUnlockerContext, BufferPipeTask.Context rx, BufferPipeTask.Context tx,
			ByteBuffer someRing, AtomicInteger swapCount) {
		this(bridgeContext, bufferUnlockerContext, rx, tx, someRing, swapCount, null, null);
	}

	@Override
	public CompletableFuture<Void> run() {
		context.complete(new Context(ipPacketAlloc, ip4TcpListeners, ip4TcpConns, ip4UdpListeners));
		return super.run();
	}

	private static class IpConnHandlerPacketConsumer implements ToIntFunction<IpPacket>, IpConnHandlerAssociate {
		private final IpConnHandler conn;
		private final AsyncPipe<? super IpPacket> pipe;
		
		public IpConnHandlerPacketConsumer(IpConnHandler conn) {
			this.conn = conn;
			this.pipe = conn.getPacketPipe();
		}
		
		public int applyAsInt(IpPacket packet){
			pipe.write(packet);
			return NEXT_A;
		}

		@Override
		public IpConnHandler getIpConnHandler() {
			return conn;
		}
	}
	
	private void ipAccept(Map<InetAddrAndPortPair, ToIntFunction<? super IpPacket>> conns, InetAddrAndPortPair app, IpConnListener listener, IpPacket ipPacket) {
		IpConnHandler conn = listener.accept(ipPacket);
		ToIntFunction<? super IpPacket> consumer = new IpConnHandlerPacketConsumer(conn);

		conns.put(app, consumer);

		// unlock otherwise orphaned buffers
		AsyncRunnable drainPipe = ()->{
			AsyncPipe<? super IpPacket> pipe = conn.getPacketPipe();

			for(;;) {
				Object obj = pipe.poll();

				if(obj == null)
					break;
				if(obj instanceof IpPacket) {
					IpPacket packet = (IpPacket)obj;
					BufferRef bufferRef = packet.getBufferRef();
					
					bufferUnlockerContext.pipe.write(bufferUnlockerContext.bufferUnlockReqAlloc.alloc(bufferRef));
				}
				else
					conn.disposeMsg(obj);
			}
			return AsyncLoop.cfVoid;
		};

		BiFunction<Void, Throwable, Void> handler = (Void dummy, Throwable throwable)->{
			conns.remove(app, consumer); // only remove ourself!
			drainPipe.run();
			bridgeContext.taskQueue.offer(drainPipe); // will be run at bridge quiescense
			if(throwable != null)
				logger.error("ip connection task threw", throwable);
			return (Void)null;
		};
		
		bridgeContext.taskQueue.offer(()->{
			try {
				return conn.run().handle(handler);
			}
			catch(Throwable th) {
				handler.apply(null, th);
				return AsyncLoop.cfVoid;
			}
		});		
	}

	private ToIntFunction<? super IpPacket> ip4TcpListener(IpConnListener listener) {
		return (IpPacket ipPacket)->{
			ipAccept(ip4TcpConns, ip4AddrAndPortPair.dupe(), listener, ipPacket);
			return NEXT_A;										
		};
	}
	
	protected int swapActionIp4TcpListener(int ethBufPos, int ethPayloadPos, ByteBuffer ipBuf, int ipPayloadPos, int ipLimit, ByteBuffer tcpBuf, BufferRef rxBuf) {
		ip4AddrAndPort.addr = ip4AddrAndPortPair.addrB;
		ip4AddrAndPort.port = ip4AddrAndPortPair.portB;

		ToIntFunction<? super IpPacket> listener = ip4TcpListeners.get(ip4AddrAndPort);

		if(listener != null) // got one... create a new connection task
			return listener.applyAsInt(ipPacketAlloc.alloc(ethBufPos, ethPayloadPos, ipPayloadPos, ipLimit, rxBuf));
		return SWAP | NEXT_A | NEXT_B;							
	}
	
	protected int swapActionIp4Tcp(int ethBufPos, int ethPayloadPos, ByteBuffer ipBuf, int ipPayloadPos, int ipLimit, ByteBuffer tcpBuf, BufferRef rxBuf) {
		ip4AddrAndPortPair.addrA = Ip4Pkt.getSrcAddr(ipBuf);
		ip4AddrAndPortPair.portA = TcpPkt.getSrcPort(tcpBuf);
		ip4AddrAndPortPair.addrB = Ip4Pkt.getDstAddr(ipBuf);
		ip4AddrAndPortPair.portB = TcpPkt.getDstPort(tcpBuf);

		ToIntFunction<? super IpPacket> connConsumer = ip4TcpConns.get(ip4AddrAndPortPair);

		if(connConsumer != null)
			return connConsumer.applyAsInt(ipPacketAlloc.alloc(ethBufPos, ethPayloadPos, ipPayloadPos, ipLimit, rxBuf));
		// check listeners
		return swapActionIp4TcpListener(ethBufPos, ethPayloadPos, ipBuf, ipPayloadPos, ipLimit, tcpBuf, rxBuf);
	}
	
	protected int swapActionIp4Udp(int ethBufPos, int ethPayloadPos, ByteBuffer ipBuf, int ipPayloadPos, int ipLimit, ByteBuffer tcpBuf, BufferRef rxBuf) {
		ip4AddrAndPort.addr = Ip4Pkt.getDstAddr(ipBuf);
		ip4AddrAndPort.port = TcpPkt.getDstPort(tcpBuf);

		ToIntFunction<? super IpPacket> consumer = ip4UdpListeners.get(ip4AddrAndPort);

		if(consumer != null)
			return consumer.applyAsInt(ipPacketAlloc.alloc( ethBufPos, ethPayloadPos, ipPayloadPos, ipLimit, rxBuf));
		return SWAP | NEXT_A | NEXT_B;					
	}
	
	protected int swapActionIp4(int ethBufPos, int ethPayloadPos, ByteBuffer ipBuf, BufferRef rxBuf) {
		int totalLen = Ip4Pkt.getTotalLen(ipBuf);							
		int ipLimit = ethPayloadPos + totalLen;
		int ipPayloadPos = Ip4Pkt.payloadPos(ipBuf);
		byte proto = Ip4Pkt.getProto(ipBuf);
		
		ipPayloadBuf.limit(ipLimit);
		ipPayloadBuf.position(ipPayloadPos);
		if(proto == TcpPkt.PROTO_TCP) {
			if(TcpPkt.valid(ipPayloadBuf))
				return swapActionIp4Tcp(ethBufPos, ethPayloadPos, ipBuf, ipPayloadPos, ipLimit, ipPayloadBuf, rxBuf);
		}
		else if(proto == UdpPkt.PROTO_UDP) {
			if(UdpPkt.valid(ipPayloadBuf))
				return swapActionIp4Udp(ethBufPos, ethPayloadPos, ipBuf, ipPayloadPos, ipLimit, ipPayloadBuf, rxBuf);
		}
		return SWAP | NEXT_A | NEXT_B;			
	}

	@Override
	protected int swapAction(BufferRef rxBuf, BufferRef txBuf) {
		int ethBufPos = (int)NetmapRing.bufOfs(someRing, rxBuf.getBufIdx());

		ethBuf.limit(ethBufPos + rxBuf.getLen());
		ethBuf.position(ethBufPos);
		if(EthPkt.valid(ethBuf)) {
			short etherType = EthPkt.getEtherType(ethBuf);
			int ethPayloadPos = EthPkt.payloadPos(ethBuf);

			ethPayloadBuf.limit(ethBuf.limit());
			ethPayloadBuf.position(ethPayloadPos);
			if(etherType == EthPkt.ETHERTYPE_IP4) {
				if(Ip4Pkt.valid(ethPayloadBuf))
					return swapActionIp4(ethBufPos, ethPayloadPos, ethPayloadBuf, rxBuf);
			}
		}
		return SWAP | NEXT_A | NEXT_B;					
	}
};