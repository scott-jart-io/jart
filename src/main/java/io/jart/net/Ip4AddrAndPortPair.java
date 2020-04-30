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

import java.security.SecureRandom;

import io.jart.util.Murmur3;

/**
 * InetAddrAndPortPair for ipv4 addresses and ports.
 */
public class Ip4AddrAndPortPair extends InetAddrAndPortPair {
	public static final int seed = (new SecureRandom()).nextInt();
	
	public int addrA;
	public int portA;
	public int addrB;
	public int portB;

	/**
	 * Instantiates a new ip 4 addr and port pair.
	 */
	public Ip4AddrAndPortPair() {}
	
	/**
	 * Instantiates a new ip 4 addr and port pair.
	 *
	 * @param addrA the addr A
	 * @param portA the port A
	 * @param addrB the addr B
	 * @param portB the port B
	 */
	public Ip4AddrAndPortPair(int addrA, int portA, int addrB, int portB) {
		this.addrA = addrA;
		this.portA = portA;
		this.addrB = addrB;
		this.portB = portB;
	}
	
	/**
	 * Duplicate this.
	 *
	 * @return the ip 4 addr and port pair
	 */
	public Ip4AddrAndPortPair dupe() {
		return new Ip4AddrAndPortPair(addrA, portA, addrB, portB);
	}
	
	/**
	 * Hash code.
	 *
	 * @return the int
	 */
	@Override
	public int hashCode() {
		int k1 = Murmur3.mixK1(addrA);
		int h1 = Murmur3.mixH1(seed, k1);

		k1 = Murmur3.mixK1(addrB);
		h1 = Murmur3.mixH1(h1, k1);

		k1 = Murmur3.mixK1((portA << 16) | portB);
		h1 = Murmur3.mixH1(h1, k1);

		return Murmur3.fmixj(h1, 12);
	}
	
	/**
	 * Equals operator.
	 *
	 * @param otherObj the other obj
	 * @return true, if successful
	 */
	@Override
	public boolean equals(Object otherObj) {
		if(otherObj instanceof Ip4AddrAndPortPair) {
			Ip4AddrAndPortPair other = (Ip4AddrAndPortPair)otherObj;

			return addrA == other.addrA && portA == other.portA &&
				addrB == other.addrB && portB == other.portB;
		}
		return false;
	}
}
