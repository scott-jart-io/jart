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

/**
 * Encapsulates a transmit connection (immutable src/dst) at the Tcp layer.
 */
public interface TcpTxContext extends TxContext {
	
	/**
	 * Indicate that a Buffer is finished and ready for send.
	 *
	 * @param controlBits the Tcp control bits to write to the buffer
	 * @param buffer the buffer to tx
	 */
	public void finish(short controlBits, Buffer buffer);

	/**
	 * Gets the seq num of the current packet.
	 *
	 * @return the seq num
	 */
	public long getSeqNum();
	
	/**
	 * Sets the seq num of the current packet.
	 *
	 * @param seqNum the new seq num
	 */
	public void setSeqNum(long seqNum);
	
	/**
	 * Gets the ack num of the current packet.
	 *
	 * @return the ack num
	 */
	public long getAckNum();
	
	/**
	 * Sets the ack num of the current packet.
	 *
	 * @param ackNum the new ack num
	 */
	public void setAckNum(long ackNum);
	
	/**
	 * Gets the control bits of the current packet.
	 *
	 * @return the control bits
	 */
	public short getControlBits();
	
	/**
	 * Sets the control bits of the current packet.
	 *
	 * @param controlBits the new control bits
	 */
	public void setControlBits(short controlBits);
	
	/**
	 * Gets the win size of the current packet.
	 *
	 * @return the win size
	 */
	public int getWinSize();
	
	/**
	 * Sets the win size of the current packet.
	 *
	 * @param winSize the new win size
	 */
	public void setWinSize(int winSize);
	
	/**
	 * Finish options of the current packet.
	 */
	public void finishOptions();
	
	/**
	 * Payload size of the current packet.
	 *
	 * @return the short
	 */
	public short payloadSize();
	
	/**
	 * Calc pseudo header partial checksum of the current packet.
	 *
	 * @param tcpPacketLength the tcp packet length
	 * @return the int
	 */
	public int calcPseudoHeaderPartialCSum(int tcpPacketLength);
}
