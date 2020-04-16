package io.jart.test;
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

import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import io.jart.memcached.Memcached;
import io.jart.memcached.Memcached.Key;
import io.jart.memcached.Memcached.Value;
import io.jart.net.TcpConnection;
import io.jart.net.TcpContext;
import io.jart.netmap.bridge.MsgRelay;
import io.jart.util.EventQueue;

// async memcached implementation
public class MemcachedConnection extends TcpConnection {
	private final Map<Key, Value> map;
	private Memcached.AsyncSession sess;
	
	public MemcachedConnection(Map<Key, Value> map, TcpContext tcpContext, int mss, EventQueue eventQueue, MsgRelay msgRelay,  int startSeqNum, Executor exec, Executor connExec) {
		super(tcpContext, mss, eventQueue, msgRelay, startSeqNum, exec, connExec);
		this.map = map;
	}

	@Override
	protected CompletableFuture<Void> connectionRun() {
		try {
			sess = new Memcached.AsyncSession(map, getReader(), getWriter(), executor());
		} catch (NoSuchAlgorithmException e) {
			throw new CompletionException(e);
		}
		return sess.run();		
	}
}
