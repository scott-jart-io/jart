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
import java.nio.ByteOrder;
import java.util.concurrent.Executor;

import io.jart.async.AsyncPipe;
import io.jart.net.BaseTcpTxContext;
import io.jart.net.DataLinkTxContext;
import io.jart.net.EthPkt;
import io.jart.net.Ip4Pkt;
import io.jart.net.Ip4TxContext;
import io.jart.net.IpTxContext;
import io.jart.net.PcapEthTxContext;
import io.jart.net.TcpContext;
import io.jart.net.TcpPkt;
import io.jart.net.TcpTxContext;
import io.jart.netmap.bridge.BridgeTask;
import io.jart.netmap.bridge.BufferPipeTask;
import io.jart.netmap.bridge.BufferRef;
import io.jart.netmap.bridge.BufferUnlockerTask;
import io.jart.netmap.bridge.EthTxContext;
import io.jart.netmap.bridge.EventQueue;
import io.jart.netmap.bridge.BridgeTask.Context;
import io.jart.netmap.bridge.inet.InetBufferSwapTask.IpPacket;
import io.jart.util.ThreadAffinityExecutor;

/**
 * Simple implementation of TcpContext in terms of Netmap bridge objects.
 */
public abstract class BaseTcpContext implements TcpContext {
	
	/**
	 * A factory for creating EthBufPipeTx-related objects.
	 */
	public static interface EthBufPipeTxFactory {
		
		/**
		 * Creates a new ByteBuffer for use with Ethernet packets.
		 *
		 * @return the byte buffer
		 */
		public ByteBuffer createEthBuf();
		
		/**
		 * Creates a new pipe for message passing.
		 *
		 * @return the async pipe
		 */
		public AsyncPipe<Object> createPipe();
		
		/**
		 * Creates a new DataLinkTxContext associated with the given src/dst/ethertype.
		 *
		 * @param dstMac the dst mac
		 * @param srcMac the src mac
		 * @param etherType the ether type
		 * @return the data link tx context
		 */
		public DataLinkTxContext createDlTxCtx(long dstMac, long srcMac, short etherType);
		
		/**
		 * Creates a new IpTxContext associated with the given TxContext and ByteBuffer for Ip packets.
		 *
		 * @param dlTxCtx the dl tx ctx
		 * @param ipBuf the ip buf
		 * @return the ip tx context
		 */
		public IpTxContext createIpTxContext(DataLinkTxContext dlTxCtx, ByteBuffer ipBuf);
		
		/**
		 * Gets the BufferUnlockerTask context.
		 *
		 * @return the bul context
		 */
		public BufferUnlockerTask.Context getBulContext();
	}

	/**
	 * A factory for creating BaseIp4EthBufPipeTx-related objects.
	 */
	public static class BaseIp4EthBufPipeTxFactory implements EthBufPipeTxFactory {
		protected final BridgeTask.Context bridgeContext;
		protected final BufferPipeTask.Context tx;
		protected final int txBufSize;
		protected final BufferUnlockerTask.Context bulContext;
		protected final EventQueue eventQueue;
		protected final ByteBuffer someRing;
		protected final Executor exec;

		/**
		 * Instantiates a new base ip 4 eth buf pipe tx factory.
		 *
		 * @param bridgeContext the bridge context
		 * @param tx the tx
		 * @param txBufSize the tx buf size
		 * @param bulContext the bul context
		 * @param eventQueue the event queue
		 * @param someRing the some ring
		 * @param exec the exec
		 */
		public BaseIp4EthBufPipeTxFactory(BridgeTask.Context bridgeContext, BufferPipeTask.Context tx, int txBufSize, BufferUnlockerTask.Context bulContext, EventQueue eventQueue, ByteBuffer someRing, Executor exec) {
			this.bridgeContext = bridgeContext;
			this.tx = tx;
			this.txBufSize = txBufSize;
			this.bulContext = bulContext;
			this.eventQueue = eventQueue;
			this.someRing = someRing;
			this.exec = new ThreadAffinityExecutor((exec != null) ? exec : bridgeContext.exec);
		}
		
		/**
		 * Creates a new ByteBuffer for use with Ethernet packets.
		 *
		 * @return the byte buffer
		 */
		@Override
		public ByteBuffer createEthBuf() {
			return someRing.duplicate().order(ByteOrder.BIG_ENDIAN);
		}
		
		/**
		 * Creates a new pipe for message passing.
		 *
		 * @return the async pipe
		 */
		@Override
		public AsyncPipe<Object> createPipe() {
			return new AsyncPipe<Object>(bridgeContext.taskPipeGroup);
		}
		
		/**
		 * Creates a new DataLinkTxContext associated with the given src/dst/ethertype.
		 *
		 * @param dstMac the dst mac
		 * @param srcMac the src mac
		 * @param etherType the ether type
		 * @return the data link tx context
		 */
		@Override
		public DataLinkTxContext createDlTxCtx(long dstMac, long srcMac, short etherType) {
			return new EthTxContext(tx, txBufSize, bulContext, someRing, createEthBuf(), dstMac, srcMac, etherType);
		}

		/**
		 * Creates a new BaseIp4EthBufPipeTx object.
		 *
		 * @param dlTxCtx the dl tx ctx
		 * @param ipBuf the ip buf
		 * @return the ip tx context
		 */
		@Override
		public IpTxContext createIpTxContext(DataLinkTxContext dlTxCtx, ByteBuffer ipBuf) {
			return new Ip4TxContext(dlTxCtx, TcpPkt.PROTO_TCP, Ip4Pkt.getDstAddr(ipBuf), Ip4Pkt.getSrcAddr(ipBuf));
		}
		
		/**
		 * Gets the BufferUnlockerTask context.
		 *
		 * @return the bul context
		 */
		@Override
		public BufferUnlockerTask.Context getBulContext() { return bulContext; }
	}
	
	/**
	 * Extends BaseIp4EthBufPipeTxFactory with pcap writing.
	 */
	public static class PcapIp4EthBufPipeTxFactory extends BaseIp4EthBufPipeTxFactory {
		protected OutputStream pcapOs;
		
		/**
		 * Instantiates a new pcap ip 4 eth buf pipe tx factory.
		 *
		 * @param bridgeContext the bridge context
		 * @param tx the tx
		 * @param txBufSize the tx buf size
		 * @param bulContext the bul context
		 * @param eventQueue the event queue
		 * @param someRing the some ring
		 * @param exec the exec
		 * @param pcapOs the pcap os
		 */
		public PcapIp4EthBufPipeTxFactory(Context bridgeContext, io.jart.netmap.bridge.BufferPipeTask.Context tx, int txBufSize,
				io.jart.netmap.bridge.BufferUnlockerTask.Context bulContext, EventQueue eventQueue, ByteBuffer someRing,
				Executor exec, OutputStream pcapOs) {
			super(bridgeContext, tx, txBufSize, bulContext, eventQueue, someRing, exec);
			this.pcapOs = pcapOs;
		}

		/**
		 * Creates a new pipe for message passing.
		 *
		 * @return the async pipe
		 */
		@Override
		public AsyncPipe<Object> createPipe() {
			return new PcapIpPacketPipe(bridgeContext.taskPipeGroup, bridgeContext.exec, someRing, pcapOs);
		}
		
		/**
		 * Creates a new DataLinkTxContext object.
		 *
		 * @param dstMac the dst mac
		 * @param srcMac the src mac
		 * @param etherType the ether type
		 * @return the data link tx context
		 */
		@Override
		public DataLinkTxContext createDlTxCtx(long dstMac, long srcMac, short etherType) {
			return new PcapEthTxContext(super.createDlTxCtx(dstMac, srcMac, etherType), pcapOs);
		}
	}
	
	protected final ByteBuffer ethBuf;
	private final AsyncPipe<Object> pipe;
	private final TcpTxContext tx;
	private final BufferUnlockerTask.Context bulContext;
	private final IpPacket.Alloc ipPacketAlloc;

	/**
	 * New ip packet allocator.
	 *
	 * @return the ip packet. alloc
	 */
	private static IpPacket.Alloc newIpPacketAlloc() {	
		return new IpPacket.Alloc() {
			@Override
			public IpPacket alloc(int ethPod, int ipPos, int ipPayloadPos, int endPos, BufferRef bufferRef) {
				return null; // we don't alloc, just free!
			}
			@Override
			public void free(IpPacket packet) {
			}
		};
	}
	
	/**
	 * Instantiates a new base tcp context.
	 *
	 * @param ethBuf the eth buf
	 * @param pipe the pipe
	 * @param tx the tx
	 * @param bulContext the bul context
	 * @param ipPacketAlloc the ip packet allocator
	 */
	public BaseTcpContext(ByteBuffer ethBuf, AsyncPipe<Object> pipe, TcpTxContext tx, BufferUnlockerTask.Context bulContext, IpPacket.Alloc ipPacketAlloc) {
		this.ethBuf = ethBuf;
		this.pipe = pipe;
		this.tx = tx;
		this.bulContext = bulContext;
		this.ipPacketAlloc = (ipPacketAlloc == null) ? newIpPacketAlloc() : ipPacketAlloc;
	}
	
	/**
	 * Instantiates a new base tcp context with default allocator.
	 *
	 * @param ethBuf the eth buf
	 * @param pipe the pipe
	 * @param tx the tx
	 * @param bulContext the bul context
	 */
	public BaseTcpContext(ByteBuffer ethBuf, AsyncPipe<Object> pipe, TcpTxContext tx, BufferUnlockerTask.Context bulContext) {
		this(ethBuf, pipe, tx, bulContext, null);
	}
	
	/**
	 * Instantiates a new base tcp context.
	 *
	 * @param factory the factory
	 * @param firstPacket the first packet
	 * @param ipPacketAlloc the ip packet alloc
	 */
	public BaseTcpContext(EthBufPipeTxFactory factory, IpPacket firstPacket, IpPacket.Alloc ipPacketAlloc) {
		ethBuf = factory.createEthBuf();
		pipe = factory.createPipe();
		
		ethBuf.limit(firstPacket.getEndPos());
		ethBuf.position(firstPacket.getEthPos());
		
		DataLinkTxContext dlTxCtx = factory.createDlTxCtx(EthPkt.getSrcMac(ethBuf), EthPkt.getDstMac(ethBuf), EthPkt.getEtherType(ethBuf));
		
		ethBuf.position(firstPacket.getIpPos());
		
		IpTxContext ipTxCtx = factory.createIpTxContext(dlTxCtx, ethBuf);
		
		ethBuf.position(firstPacket.getIpPayloadPos());
		
		tx = new BaseTcpTxContext(ipTxCtx, TcpPkt.getDstPort(ethBuf), TcpPkt.getSrcPort(ethBuf));
		bulContext = factory.getBulContext();
		
		pipe.write(firstPacket);
		
		this.ipPacketAlloc = (ipPacketAlloc == null) ? newIpPacketAlloc() : ipPacketAlloc;
	}
	
	/**
	 * Instantiates a new base tcp context with default allocator.
	 *
	 * @param factory the factory
	 * @param firstPacket the first packet
	 */
	public BaseTcpContext(EthBufPipeTxFactory factory, IpPacket firstPacket) {
		this(factory, firstPacket, null);
	}
	
	/**
	 * Check ip checksum.
	 *
	 * @param ipPacket the ip packet
	 * @return true, if successful
	 */
	protected abstract boolean checkIpCSum(IpPacket ipPacket);
	
	/**
	 * Check current tcp packet checksum.
	 * Override to return true for hardware offload.
	 *
	 * @return true, if successful
	 */
	protected boolean checkCSum() {
		int tcpLen = ethBuf.remaining();
		int pcsum = tx.calcPseudoHeaderPartialCSum(tcpLen);
		
		return TcpPkt.calcCSum(pcsum, ethBuf, ethBuf.position(), tcpLen) == 0;
	}
	
	/**
	 * Gets the message pipe.
	 *
	 * @return the pipe
	 */
	@Override
	public AsyncPipe<Object> getPipe() {
		return pipe;
	}

	/**
	 * Gets the TcpTxContext.
	 *
	 * @return the tx
	 */
	@Override
	public TcpTxContext getTx() {
		return tx;
	}

	/**
	 * Converts applicable received messages to ByteBuffers representing a tcp packet.
	 *
	 * @param obj the recieved obj that MAY represent a received packet
	 * @return the byte buffer or null if obj doesn't represent a received packet
	 */
	@Override
	public ByteBuffer rx(Object obj) {
		if(obj instanceof IpPacket) {
			IpPacket ipPacket = (IpPacket)obj;
			
			ethBuf.limit(ipPacket.getEndPos());
			if(checkIpCSum(ipPacket)) {
				ethBuf.position(ipPacket.getIpPayloadPos());
				
				if(checkCSum())
					return ethBuf;
			}

			BufferRef bufferRef = ipPacket.getBufferRef();
			
			bulContext.pipe.write(bulContext.bufferUnlockReqAlloc.alloc(bufferRef));
			ipPacketAlloc.free(ipPacket);
		}
		return null;
	}

	/**
	 * Call when finished with the non-null ByteBuffer returned by rx(Object obj).
	 *
	 * @param obj the obj
	 */
	@Override
	public void finishRx(Object obj) {
		IpPacket ipPacket = (IpPacket)obj;
		BufferRef bufferRef = ipPacket.getBufferRef();
		
		bulContext.pipe.write(bulContext.bufferUnlockReqAlloc.alloc(bufferRef));
		ipPacketAlloc.free(ipPacket);
	}
}
