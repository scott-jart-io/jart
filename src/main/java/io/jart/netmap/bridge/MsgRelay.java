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

package io.jart.netmap.bridge;

import io.jart.async.AsyncPipe;

/**
 * Safely and efficiently relay messages to a dependent BridgeTask task.
 */
public class MsgRelay {
	private final BridgeTask.Context bridgeContext;
	private final MsgRelayTask.Context msgRelayContext;
	private final BridgeTask.BridgeRelayReq<MsgRelayTask.MsgRelayKick> bridgeRelayKickReq;

	/**
	 * Instantiates a new msg relay.
	 *
	 * @param bridgeContext the bridge context
	 * @param msgRelayContext the msg relay context
	 */
	public MsgRelay(BridgeTask.Context bridgeContext, MsgRelayTask.Context msgRelayContext) {
		this.bridgeContext = bridgeContext;
		this.msgRelayContext = msgRelayContext;
		this.bridgeRelayKickReq = new BridgeTask.BridgeRelayReq<MsgRelayTask.MsgRelayKick>(msgRelayContext.pipe, MsgRelayTask.msgRelayKick);
	}
	
	/**
	 * Relay.
	 * Ok to call at any time in any context.
	 *
	 * @param <T> the generic type
	 * @param pipe the pipe
	 * @param msg the msg
	 */
	@SuppressWarnings("unchecked")
	public <T> void relay(AsyncPipe<? super T> pipe, T msg) {
		msgRelayContext.relayReqQ.offer(msgRelayContext.msgRelayReqAlloc.alloc((AsyncPipe<Object>)pipe, (Object)msg));
		if(msgRelayContext.relayReqCount.getAndIncrement() == 0)
			bridgeContext.waker.wake(bridgeRelayKickReq);
	}
}
