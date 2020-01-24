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

public class Ip4AddrAndPort extends InetAddrAndPort {
	public static final int seed = (new SecureRandom()).nextInt();
	
	public int addr;
	public int port;
	
	public Ip4AddrAndPort() {}
	
	public Ip4AddrAndPort(int addr, int port) {
		this.addr = addr;
		this.port = port;
	}
	
	public Ip4AddrAndPort dupe() {
		return new Ip4AddrAndPort(addr, port);
	}
	
	@Override
	public int hashCode() {
		int k1 = Murmur3.mixK1(addr);
		int h1 = Murmur3.mixH1(seed, k1);

		k1 = Murmur3.mixK1(port);
		h1 = Murmur3.mixH1(h1, k1);

		return Murmur3.fmixj(h1, 6);
	}
	
	@Override
	public boolean equals(Object otherObj) {
		if(otherObj instanceof Ip4AddrAndPort) {
			Ip4AddrAndPort other = (Ip4AddrAndPort)otherObj;

			return addr == other.addr && port == other.port;
		}
		return false;
	}
}
