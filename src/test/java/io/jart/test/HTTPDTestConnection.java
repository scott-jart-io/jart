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
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
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

/**
 * Example HTTPD implementation that does GETs and PUTs to/from (by default) user's home directory / www
 * as well as POSTs where it hashes POST-ed data and responds with the hash value.
 */
public class HTTPDTestConnection extends HTTPDConnection {
	private final AsyncReadThroughFileCache fc;
	private final String root;
	
	
	/**
	 * Instantiates a new HTTPD test connection.
	 *
	 * @param tcpContext the tcp context
	 * @param mss the mss
	 * @param eventQueue the event queue to use for tcp bookkeeping
	 * @param msgRelay the msg relay to use for safe unsynchronized message passing
	 * @param startSeqNum the starting sequence num
	 * @param exec the Executor to use for tcp work
	 * @param fc the file cache to use for caching GETs
	 * @param root the root folder to serve from (default home directory / www)
	 * @param connExec the Executor for non-tcp work
	 */
	public HTTPDTestConnection(TcpContext tcpContext, int mss, EventQueue eventQueue, MsgRelay msgRelay, int startSeqNum, Executor exec, AsyncReadThroughFileCache fc, String root, Executor connExec) {
		super(tcpContext, mss, eventQueue, msgRelay, startSeqNum, exec, connExec);
		this.fc = fc;
		this.root = (root != null) ? root : System.getProperty("user.home") + "/www";
	}

	/**
	 * Instantiates a new HTTPD test connection.
	 *
	 * @param tcpContext the tcp context
	 * @param mss the mss
	 * @param eventQueue the event queue to use for tcp bookkeeping
	 * @param msgRelay the msg relay to use for safe unsynchronized message passing
	 * @param startSeqNum the starting sequence num
	 * @param exec the Executor to use for tcp work
	 * @param fc the file cache to use for caching GETs
	 * @param connExec the Executor for non-tcp work
	 */
	public HTTPDTestConnection(TcpContext tcpContext, int mss, EventQueue eventQueue, MsgRelay msgRelay, int startSeqNum, Executor exec, AsyncReadThroughFileCache fc, Executor connExec) {
		this(tcpContext, mss, eventQueue, msgRelay, startSeqNum, exec, fc, null, connExec);
	}

	/**
	 * Serve GET requests.
	 *
	 * @param url the url
	 * @param headers the headers
	 * @return the completable future signaling completion
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	private CompletableFuture<Void> serveGet(String url, Header[] headers) throws IOException {
		// TODO doesn't try to gracefully handle any errors
		Path src = FileSystems.getDefault().getPath(root, url);
		long size = fc.size(src);
		Async.await(sendResponseHeader(200, "ok", Arrays.asList(new String[] {
				"content-type: text/plain",
				"content-length: " + size })));
		
		return AsyncCopiers.copy(getWriter(), fc, src, 0, size, executor()).thenApply((Long l)->(Void)null);
	}
	
	/**
	 * Serve PUT requests.
	 *
	 * @param url the url
	 * @param headers the headers
	 * @return the completable future signaling completion
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	private CompletableFuture<Void> servePut(String url, Header[] headers) throws IOException {
		// TODO doesn't try to gracefully handle any errors
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

	// match any urls that have .. in them to reject them
	private static Pattern rejectPat = Pattern.compile("^[^/]|/\\.\\./|/\\.\\.$");
	
	private static final char[] HEX_ARRAY = "0123456789abcdef".toCharArray();
	
	/**
	 * Bytes to hex string.
	 *
	 * @param bytes the bytes
	 * @return the string
	 */
	private static String bytesToHex(byte[] bytes) {
	    char[] hexChars = new char[bytes.length * 2];
	    for (int j = 0; j < bytes.length; j++) {
	        int v = bytes[j] & 0xFF;
	        hexChars[j * 2] = HEX_ARRAY[v >>> 4];
	        hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
	    }
	    return new String(hexChars);
	}
	
	/**
	 * Serve and http request.
	 *
	 * @param verb the verb
	 * @param url the url
	 * @param headers the headers
	 * @return the completable future signaling completion
	 * @throws Exception the exception
	 */
	@Override
	protected CompletableFuture<Void> serve(String verb, String url, Header[] headers) throws Exception {
		// reject anything w/ .. in it (though this is the best we try to do wrt security!)
		if(rejectPat.matcher(url).find())
			return sendResponseHeader(404, "not found", null);

		if("get".equals(verb))
			return serveGet(url, headers);
		if("put".equals(verb))
			return servePut(url, headers);
		
		// any other verbs and we'll sha any content and print out request info
		Long size = null;
		
		for(Header header: headers) {
			if(header.name.equals("content-length"))
				size = Long.valueOf(header.value);
			else if(header.name.equals("expect") && header.value.equals("100-continue"))
				Async.await(sendResponseHeader(100, "continue", null));
		}
		
		byte[] mdBuf;
		
		if(size != null) {
			MessageDigest md = MessageDigest.getInstance("sha-1");

			// hash directly from netmap buffers
			Async.await(getReader().read((ByteBuffer buf, Boolean needsCopy)->{
				md.update(buf);
				return true;
			}, size));
			
			mdBuf = new byte[md.getDigestLength()];
			md.digest(mdBuf, 0, mdBuf.length);
		}
		else
			mdBuf = null;
		
		// http response header
		Async.await(sendResponseHeader(200, "ok", Arrays.asList(new String[] { "content-type: text/plain" })));

		// dump of various bits of info
		
		// verb + url
		Async.await(getWriter().write(verb));
		Async.await(getWriter().write(crlf));
		Async.await(getWriter().write(url));
		Async.await(getWriter().write(crlf));

		// headers
		for(Header header: headers) {
			Async.await(getWriter().write(header.toString()));
			Async.await(getWriter().write(crlf));
		}
		// hash code if we have one
		if(mdBuf != null) {
			Async.await(getWriter().write(crlf));
			Async.await(getWriter().write("sha1 = " + bytesToHex(mdBuf)));
			Async.await(getWriter().write(crlf));			
		}
		return AsyncLoop.cfVoid;
	}
}
