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



import java.io.IOException;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

import com.ea.async.Async;

import io.jart.async.AsyncCopiers;
import io.jart.async.AsyncLoop;
import io.jart.async.AsyncReadThroughFileCache;
import io.jart.net.TcpContext;
import io.jart.net.http.HTTPDConnection;
import io.jart.netmap.bridge.MsgRelay;
import io.jart.util.EventQueue;

public class HTTPDTestConnection extends HTTPDConnection {
	private final AsyncReadThroughFileCache fc;
	private final String root;
	
	
	public HTTPDTestConnection(TcpContext tcpContext, int mss, EventQueue eventQueue, MsgRelay msgRelay, int startSeqNum, Executor exec, AsyncReadThroughFileCache fc, String root, Executor connExec) {
		super(tcpContext, mss, eventQueue, msgRelay, startSeqNum, exec, connExec);
		this.fc = fc;
		this.root = (root != null) ? root : System.getProperty("user.home") + "/www";
	}

	public HTTPDTestConnection(TcpContext tcpContext, int mss, EventQueue eventQueue, MsgRelay msgRelay, int startSeqNum, Executor exec, AsyncReadThroughFileCache fc, Executor connExec) {
		this(tcpContext, mss, eventQueue, msgRelay, startSeqNum, exec, fc, null, connExec);
	}

	private CompletableFuture<Void> serveGet(String url, Header[] headers) throws IOException {
		Path src = FileSystems.getDefault().getPath(root, url);
		long size = fc.size(src);
		Async.await(sendResponseHeader(200, "ok", Arrays.asList(new String[] {
				"content-type: text/plain",
				"content-length: " + size })));
		
		return AsyncCopiers.copy(getWriter(), fc, src, 0, size, executor()).thenApply((Long l)->(Void)null);
	}
	
	private CompletableFuture<Void> servePut(String url, Header[] headers) throws IOException {
		Path dst = FileSystems.getDefault().getPath(root, url);
		Long size = null;
		
		for(Header header: headers) {
			if(header.name.equals("content-length")) {
				size = Long.valueOf(header.value);
				break;
			}
		}
		
		AsynchronousFileChannel afc = AsynchronousFileChannel.open(dst, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		
		Async.await(AsyncCopiers.copy(afc, 0, getReader(), size, executor()));
		return sendResponseHeader(200, "ok", null);
	}

	private static Pattern rejectPat = Pattern.compile("^[^/]|/\\.\\./|/\\.\\.$");
	
	@Override
	protected CompletableFuture<Void> serve(String verb, String url, Header[] headers) throws Exception {
		if(rejectPat.matcher(url).find())
			return sendResponseHeader(404, "not found", null);

		if("get".equals(verb))
			return serveGet(url, headers);
		if("put".equals(verb))
			return servePut(url, headers);
		
		Async.await(sendResponseHeader(200, "ok", Arrays.asList(new String[] { "content-type: text/plain" })));

		Async.await(getWriter().write(verb));
		Async.await(getWriter().write(crlf));
		Async.await(getWriter().write(url));
		Async.await(getWriter().write(crlf));
		for(Header header: headers) {
			Async.await(getWriter().write(header.name + ": " + header.value));
			Async.await(getWriter().write(crlf));
		}
		return AsyncLoop.cfVoid;
	}
}
