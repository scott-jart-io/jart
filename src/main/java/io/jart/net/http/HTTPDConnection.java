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

package io.jart.net.http;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import org.apache.log4j.Logger;

import com.ea.async.Async;

import io.jart.async.AsyncByteWriter;
import io.jart.async.AsyncLoop;
import io.jart.net.TcpConnection;
import io.jart.net.TcpContext;
import io.jart.netmap.bridge.MsgRelay;
import io.jart.util.EventQueue;

public abstract class HTTPDConnection extends TcpConnection {
	private static final Logger logger = Logger.getLogger(HTTPDConnection.class);
	private static final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss O");
	private static final ZoneId dateZoneId = ZoneId.of("GMT");	
	
	protected static final byte[] crlf = new byte[] { (byte)'\r', (byte)'\n' };

	@SuppressWarnings("serial")
	protected static class HTTPError extends Exception {
		public final int status;
		
		public HTTPError(int status, String msg) {
			super(msg);
			this.status = status;
		}

		public HTTPError(int status, String msg, Throwable cause) {
			super(msg, cause);
			this.status = status;
		}
	}

	protected static class Header {
		public final String name;
		public final String value;
		
		public Header(String name, String value) {
			this.name = name;
			this.value = value;
		}
		
		public String toString() {
			return name + ": " + value;
		}
	}

	public HTTPDConnection(TcpContext tcpContext, int mss, EventQueue eventQueue, MsgRelay msgRelay, int startSeqNum, Executor exec, Executor connExec) {
		super(tcpContext, mss, eventQueue, msgRelay, startSeqNum, exec, connExec);
	}

	protected CompletableFuture<Void> sendResponseHeader(int status, String msg, Iterable<? extends CharSequence> headers) {
		AsyncByteWriter writer = getWriter();
		String baseStr = "HTTP/1.1 " + status + " " + msg + "\r\n" +
				"Server: jart/0.1\r\n" + 
				"Date: " + dateFormatter.format(ZonedDateTime.now(dateZoneId)) + "\r\n";

		Async.await(writer.write(baseStr));
		if(headers != null) {
			String headerStr = String.join("\r\n", headers);
			
			if(headerStr.length() > 0)
				Async.await(writer.write(headerStr + "\r\n"));
		}
		return writer.write(crlf);
	}
	
	protected CompletableFuture<Void> error(HTTPError error) {
		Throwable cause = error.getCause();
		List<String> headers;
		
		if(cause != null) {
			String uuid = UUID.randomUUID().toString();
			
			headers = Arrays.asList("X-Cause-Id: " + uuid);
			logger.error("httpd error {" + uuid + "}", cause);
		}
		else
			headers = null;
		return sendResponseHeader(error.status, error.getMessage(), headers);
	}
	
	protected CompletableFuture<String> readLine(byte[] buf, int[] offset, int[] len) {
		int startOffs = offset[0];
		int n = Async.await(getReader().read(buf, offset[0], len[0], (byte)'\n'));
		int endOffs = offset[0] += n;

		len[0] -= n;
		if(endOffs > startOffs && buf[endOffs - 1] == (byte)'\n')
			endOffs--;
		else
			return CompletableFuture.completedFuture((String)null);
		
		if(endOffs > startOffs && buf[endOffs - 1] == (byte)'\r')
			endOffs--;
		
		return CompletableFuture.completedFuture(new String(buf, startOffs, endOffs - startOffs, StandardCharsets.UTF_8));
	}
	
	protected abstract CompletableFuture<Void> serve(String verb, String url, Header[] headers) throws Exception;
	
	@Override
	protected CompletableFuture<Void> connectionRun() {
		try {
			String verb, url;
			Header[] headers;
			
			// lots of missing null checks that will result in 500 instead of better errors
			{
				byte[] reqBuf = new byte[2048]; // max line length in bytes
				int[] offset = new int[1];
				int[] len = new int[] { 65536 }; // max total req len
				
				String reqLine = Async.await(readLine(reqBuf, offset, len));			
				String[] reqParts = reqLine.split("\\s+");

				if(reqParts.length != 3)
					throw new HTTPError(413, "invalid request line");
				if(!reqParts[2].toLowerCase().equals("http/1.1"))
					throw new HTTPError(505, "unsupported version");
				
				List<Header> headerList = new ArrayList<Header>();
				
				Async.await(AsyncLoop.doWhile(()->{
					offset[0] = 0;
					
					String headerLine = Async.await(readLine(reqBuf, offset, len));
					
					if(headerLine.length() == 0)
						return AsyncLoop.cfFalse;
					
					String[] headerParts = headerLine.split(":\\s+", 2);
					
					if(headerParts.length != 2)
						throw new CompletionException(new HTTPError(400, "invalid header"));
					
					headerList.add(new Header(headerParts[0].toLowerCase(), headerParts[1]));
					return AsyncLoop.cfTrue;
				}, executor()));
				verb = reqParts[0].toLowerCase();
				url = reqParts[1];
				headers = headerList.toArray(new Header[0]);
			}

			Async.await(serve(verb, url, headers));
		}
		catch(HTTPError ex) {
			return error(ex);
		}
		catch(Throwable th) {
			return error(new HTTPError(500, "internal error", th));
		}
		return AsyncLoop.cfVoid;
	}
}
