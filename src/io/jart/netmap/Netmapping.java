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

package io.jart.netmap;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.sun.jna.Pointer;

import io.jart.util.CLibrary;
import io.jart.util.NativeBuffer;

// roughly equivalent to nm_desc -- a small bit of api friendliness on top of Netmap
public class Netmapping implements AutoCloseable {
	public static class Ring {
		public final Netmapping nm;
		public final ByteBuffer ring;
		public final ByteBuffer[] slots;
		public final boolean rx;
		
		public Ring(Netmapping nm, ByteBuffer ring, boolean rx) {
			this.nm = nm;
			this.ring = ring;
			
			int numSlots = (int)NetmapRing.getNumSlots(ring);
			
			this.slots = new ByteBuffer[numSlots];
			
			for(int i = 0; i < numSlots; i++)
				this.slots[i] = NetmapRing.slot(ring, i);
			this.rx = rx;
		}
		
		public String toString() {
			return "head: " + NetmapRing.getHead(ring) +
					" / cur: " + NetmapRing.getCur(ring) +
					" / tail: " + NetmapRing.getTail(ring) +
					" / numslots: " + NetmapRing.getNumSlots(ring);
		}
	}
	
	public final int fd; // file descriptor
	public final NativeBuffer nmreq; // buffer holding the NMReq
	public final NativeBuffer mem; // buffer holding mmap-ed memory
	public final boolean ownMem; // do we own the mmap?
	public final ByteBuffer IF;
	public final Ring[] rxRings;
	public final int rxBufSize;
	public final Ring[] txRings;
	public final int txBufSize;
	
	public Netmapping(int fd, NativeBuffer nmreq, NativeBuffer mem, boolean ownMem) {
		this.fd = fd;
		this.nmreq = nmreq;
		this.mem = mem;
		this.ownMem = ownMem;
		
		mem.buf.position((int)NMReq.getOffset(nmreq.buf));
		
		this.IF = mem.buf.slice().order(ByteOrder.nativeOrder());

		int firstRxRing = firstRxRing(), rxRingCount = lastRxRing() - firstRxRing + 1;
		int rxBufSize = Integer.MAX_VALUE;

		rxRings = new Ring[rxRingCount];
		for(int i = 0; i < rxRingCount; i++) {
			ByteBuffer ring = NetmapIF.rxRing(IF, firstRxRing + i);
			
			rxBufSize = Math.min(rxBufSize, (int)NetmapRing.getNRBufSize(ring));
			rxRings[i] = new Ring(this, ring, true);
		}
		this.rxBufSize = rxBufSize;

		int firstTxRing = firstTxRing(), txRingCount = lastTxRing() - firstTxRing + 1;
		int txBufSize = Integer.MAX_VALUE;
		
		txRings = new Ring[txRingCount];
		for(int i = 0; i < txRingCount; i++) {
			ByteBuffer ring = NetmapIF.txRing(IF, firstTxRing + i);
			
			txBufSize = Math.min(txBufSize, (int)NetmapRing.getNRBufSize(ring));
			txRings[i] = new Ring(this, ring, false);
		}
		this.txBufSize = txBufSize;
	}

	public String toString() {
		String result = "-----\n";
		int n = 0;
		
		for(Ring ring: rxRings) {
			result += "rx" + n + " " + ring.toString() + "\n";
		}
		
		n = 0;
		for(Ring ring: txRings) {
			result += "tx" + n + " " + ring.toString() + "\n";
		}
		return result;
	}
	public int firstTxRing() {
		int reg = NMReq.getFlags(nmreq.buf) & Netmap.NR_REG_MASK;
		
		switch(reg) {
		default:
			throw new RuntimeException("unrecognized reg");
		case Netmap.NR_REG_SW:
			return (int)NetmapIF.getTXRings(IF);
		case Netmap.NR_REG_ALL_NIC:
			return 0;
		}
	}

	public int lastTxRing() {
		int reg = NMReq.getFlags(nmreq.buf) & Netmap.NR_REG_MASK;
		
		switch(reg) {
		default:
			throw new RuntimeException("unrecognized reg");
		case Netmap.NR_REG_SW:
			return (int) (NetmapIF.getTXRings(IF) + NetmapIF.getHostTXRings(IF) - 1);
		case Netmap.NR_REG_ALL_NIC:
			return (int) (NetmapIF.getTXRings(IF) - 1);
		}
	}

	public int firstRxRing() {
		int reg = NMReq.getFlags(nmreq.buf) & Netmap.NR_REG_MASK;
		
		switch(reg) {
		default:
			throw new RuntimeException("unrecognized reg");
		case Netmap.NR_REG_SW:
			return (int)NetmapIF.getRXRings(IF);
		case Netmap.NR_REG_ALL_NIC:
			return 0;
		}
	}

	public int lastRxRing() {
		int reg = NMReq.getFlags(nmreq.buf) & Netmap.NR_REG_MASK;
		
		switch(reg) {
		default:
			throw new RuntimeException("unrecognized reg");
		case Netmap.NR_REG_SW:
			return (int) (NetmapIF.getRXRings(IF) + NetmapIF.getHostRXRings(IF) - 1);
		case Netmap.NR_REG_ALL_NIC:
			return (int) (NetmapIF.getRXRings(IF) - 1);
		}
	}
	
	public void dump(PrintStream ps) {
		ps.println("--- rx");
		for(int i = firstRxRing(); i <= lastRxRing(); i++) {			
			ByteBuffer ring = NetmapIF.rxRing(IF, i);
			long head = NetmapRing.getHead(ring);
			long prev = (head + NetmapRing.getNumSlots(ring) - 1) % NetmapRing.getNumSlots(ring);
			
			ps.println("ring " + i + "; size " + NetmapRing.ringSpace(ring));
			ps.println("head: " + head + "; cur: " + NetmapRing.getCur(ring) + "; tail: " + NetmapRing.getTail(ring));
			ps.println("prev: " + prev + " (" + NetmapSlot.getBufIdx(NetmapRing.slot(ring,  prev)) + ")");
		}
		ps.println("--- tx");
		for(int i = firstTxRing(); i <= lastTxRing(); i++) {			
			ByteBuffer ring = NetmapIF.txRing(IF, i);
			long head = NetmapRing.getHead(ring);
			long prev = (head + NetmapRing.getNumSlots(ring) - 1) % NetmapRing.getNumSlots(ring);
			
			ps.println("ring " + i + "; size " + NetmapRing.ringSpace(ring));
			ps.println("head: " + head + "; cur: " + NetmapRing.getCur(ring) + "; tail: " + NetmapRing.getTail(ring));
			ps.println("prev: " + prev + " (" + NetmapSlot.getBufIdx(NetmapRing.slot(ring,  prev)) + ")");
		}
	}
	
	public static Netmapping regSW(String name) {
		int fd = CLibrary.INSTANCE.open("/dev/netmap", CLibrary.O_RDWR);
		NativeBuffer nmreq = NMReq.allocate();

		NMReq.setName(nmreq.buf, name);
		NMReq.setVersion(nmreq.buf, Netmap.NETMAP_API);
		NMReq.setFlags(nmreq.buf, Netmap.NR_REG_SW);
		CLibrary.INSTANCE.ioctl(fd, Netmap.NIOCREGIF, nmreq.ptr);

		long size = NMReq.getMemsize(nmreq.buf);
		NativeBuffer mem = new NativeBuffer(
				CLibrary.INSTANCE.mmap(Pointer.NULL, size, CLibrary.PROT_READ|CLibrary.PROT_WRITE, CLibrary.MAP_SHARED, fd, 0),
				size);

		return new Netmapping(fd, nmreq, mem, true);
	}

	public static Netmapping regNicFromSW(Netmapping nm) {
		int fd = CLibrary.INSTANCE.open("/dev/netmap", CLibrary.O_RDWR);
		NativeBuffer nmreq = NMReq.allocate();
		
		NMReq.setName(nmreq.buf, NMReq.getName(nm.nmreq.buf));
		NMReq.setVersion(nmreq.buf, Netmap.NETMAP_API);
		NMReq.setFlags(nmreq.buf, Netmap.NR_REG_ALL_NIC);
		NMReq.setArg1(nmreq.buf, 4); // netmap_user.h line 858
		
		CLibrary.INSTANCE.ioctl(fd, Netmap.NIOCREGIF, nmreq.ptr);
		long size = NMReq.getMemsize(nmreq.buf);
		boolean needMMap = (size != NMReq.getMemsize(nm.nmreq.buf)) ||
				(NMReq.getArg2(nmreq.buf) != NMReq.getArg2(nm.nmreq.buf));
		NativeBuffer mem = needMMap ? new NativeBuffer(
				CLibrary.INSTANCE.mmap(Pointer.NULL, size, CLibrary.PROT_READ|CLibrary.PROT_WRITE, CLibrary.MAP_SHARED, fd, 0),
				size) : nm.mem;

		return new Netmapping(fd, nmreq, mem, needMMap);
	}

	@Override
	public void close() throws Exception {
		if(ownMem)
			CLibrary.INSTANCE.munmap(mem.ptr, mem.buf.capacity());
		CLibrary.INSTANCE.close(fd);
	}
}