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

package io.jart.net;

import java.util.Queue;

import org.apache.log4j.Logger;

import io.jart.async.AsyncPipe;
import io.jart.util.ByteChunker;
import io.jart.util.EventQueue;

public class TcpOutgoingCubic extends TcpOutgoing {
	private static final Logger logger = Logger.getLogger(TcpOutgoingCubic.class);

	private static final double us2s = 1./1000000.;
	private static final double ns2s = 1./1000000000.;

	private static final boolean tcpFriendliness = true;
	private static final boolean fastConvergence = true;
	private static final double Beta = 0.2;
	private static final double C = 0.4;
	
	private int WlastMax;
	private double epochStart;
	private int originPoint;
	private double dMin;
	private int Wtcp;
	private double K;
	private int ackCnt;
	
	private int cwnd = 10;
	private int cwndCnt;
	private int ssthresh = 4096*4096;

	private int pktsOutstanding;
	
	private int lastDupeAck;
	private int dupeAckCount;
	
	private int cubicUpdate(double tcpTimeStamp) {
		ackCnt++;
		if(epochStart == Double.NEGATIVE_INFINITY) {
			epochStart = tcpTimeStamp;
			if(cwnd < WlastMax) {
				K = Math.cbrt((WlastMax - cwnd) / C);
				originPoint = WlastMax;
			}
			else {
				K = 0;
				originPoint = cwnd;
			}
			ackCnt = 1;
			Wtcp = cwnd;
		}
		
		double t = tcpTimeStamp + dMin - epochStart;
		double tMinusK = t - K;
		double target = originPoint + C * tMinusK * tMinusK * tMinusK;
		int cnt = (int)Math.min(Integer.MAX_VALUE, (target > cwnd) ? cwnd / (target - cwnd) : 100. * cwnd);
		
		return tcpFriendliness ? cubicTcpFriendliness(cnt) : cnt;
	}
	
	private int cubicTcpFriendliness(int cnt) {
		Wtcp = (int)Math.min(Integer.MAX_VALUE, Wtcp + ((3 * Beta) * ackCnt) / ((2 - Beta) * cwnd));
		ackCnt = 0;
		if(Wtcp > cwnd) {
			int maxCnt = cwnd / (Wtcp - cwnd);
			
			cnt = Math.min(cnt, maxCnt);
		}
		return cnt;
	}
	
	private void cubicReset() {
		WlastMax = 0;
		epochStart = Double.NEGATIVE_INFINITY;
		originPoint = 0;
		dMin = Double.POSITIVE_INFINITY;
		Wtcp = 0;
		K = 0;
		ackCnt = 0;
	}

	@Override
	protected int maxPktsOut() { return cwnd - pktsOutstanding; }

	@Override
	protected void pktsOut(int n) { pktsOutstanding += n; }
	
	@Override
	protected void pktsAcked(int n, long rtt) {
		if(n > 0) {
			double tcpTimeStamp = System.nanoTime() * ns2s;
	
			pktsOutstanding -= n;
			while(n-- != 0) {
				// On each ACK:
				dMin = Math.min(dMin, rtt * us2s);
				if(cwnd <= ssthresh)
					cwnd++;
				else {
					int cnt = cubicUpdate(tcpTimeStamp);
					
					if(cwndCnt > cnt) {
						cwnd = Math.min(cwnd + 1, 4096*4096); // arbitrary (but large) cap
						cwndCnt = 0;
					}
					else
						cwndCnt++;
				}
			}
		}
	}
	
	@Override
	protected void pktDupeAcked(int seqNum) {
		// Packet loss:
		epochStart = Double.NEGATIVE_INFINITY;
		WlastMax = (cwnd < WlastMax && fastConvergence) ? (int)(cwnd * ((2 - Beta) / 2)) : cwnd;
		ssthresh = cwnd = (int)(cwnd * (1 - Beta));

		// fast re-tx
		dupeAckCount = (seqNum == lastDupeAck) ? dupeAckCount + 1 : 1;
		lastDupeAck = seqNum;
		
		logger.debug("dupe ack " + lastDupeAck + " x " + dupeAckCount);
		
		if(dupeAckCount == 3)
			fastReTx();
	}

	@Override
	protected void pktTimeout() {
		// Timeout:
		cubicReset();

		logger.debug("pktTimeout");
	}

	public TcpOutgoingCubic(EventQueue eventQueue, AsyncPipe<Object> pipe, int mss, int winSize, long seqNum,
			Queue<ByteChunker> sendQ) {
		super(eventQueue, pipe, mss, winSize, seqNum, sendQ);
		cubicReset();
	}

	public TcpOutgoingCubic(EventQueue eventQueue, AsyncPipe<Object> pipe, int mss, int winSize, long seqNum) {
		this(eventQueue, pipe, mss, winSize, seqNum, null);
	}
}
