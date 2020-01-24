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

package io.jart.memcached;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.function.Function;

import com.ea.async.Async;

import io.jart.async.AsyncByteBufferReader;
import io.jart.async.AsyncByteWriter;
import io.jart.async.AsyncLoop;
import io.jart.async.AsyncRunnable;
import io.jart.util.ByteArrayChunker;
import io.jart.util.ByteChunker;

// TODO prototype-y
public class Memcached {
	public static class Key {
		public long keyHash1, keyHash2;

		public Key() {
		}

		public Key(byte[] hash) {
			setValue(hash);
		}

		public void setValue(byte[] hash) {
			keyHash1 = (int) hash[0] | (int) hash[1] << 8 | (int) hash[2] << 16 | (int) hash[3] << 24
					| (long) hash[4] << 32 | (long) hash[5] << 40 | (long) hash[6] << 48 | (long) hash[7] << 56;
			keyHash2 = (int) hash[8] | (int) hash[9] << 8 | (int) hash[10] << 16 | (int) hash[11] << 24
					| (long) hash[12] << 32 | (long) hash[13] << 40 | (long) hash[14] << 48 | (long) hash[15] << 56;
		}

		@Override
		public int hashCode() {
			return (int)keyHash1;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			Key other = (Key) obj;
			if (keyHash1 != other.keyHash1)
				return false;
			if (keyHash2 != other.keyHash2)
				return false;
			return true;
		}
	}

	public static class Value {
		public final byte[] efkvp; // expiration(4), flags(4), key(keyLen), value(...)
		public final int keyLen;
		public final long cas;

		public Value(byte[] efkvp, int keyLen, long cas) {
			this.efkvp = efkvp;
			this.keyLen = keyLen;
			this.cas = cas;
		}
	}

	public static class Header {
		public final static int SIZE = 24;

		public final ByteBuffer buf;

		public Header() {
			buf = ByteBuffer.allocate(SIZE).order(ByteOrder.BIG_ENDIAN);
		}

		public short getMagicOpcode() {
			return buf.getShort(0);
		}

		public void setMagicOpcode(short mo) {
			buf.putShort(0, mo);
		}

		public int getKeyLength() {
			return 0xffff & buf.getShort(2);
		}

		public void setKeyLength(int keyLen) {
			buf.putShort(2, (short) keyLen);
		}

		public short getExtrasLength() {
			return (short) (0xff & buf.get(4));
		}

		public void setExtrasLength(short len) {
			buf.put(4, (byte) len);
		}

		public byte getDataType() {
			return buf.get(5);
		}

		public void setDataType(byte dt) {
			buf.put(5, dt);
		}

		public short getStatus() {
			return buf.getShort(6);
		}

		public void setStatus(short status) {
			buf.putShort(6, status);
		}

		public short getVBucketId() {
			return buf.getShort(6);
		}

		public void setVBucketId(short vbid) {
			buf.putShort(6, vbid);
		}

		public long getBodyLength() {
			return 0xffffffffL & buf.getInt(8);
		}

		public void setBodyLength(long len) {
			buf.putInt(8, (int) len);
		}

		public int getOpaque() {
			return buf.getInt(12);
		}

		public void setOpaque(int opaque) {
			buf.putInt(12, opaque);
		}

		public long getCas() {
			return buf.getLong(16);
		}

		public void setCas(long cas) {
			buf.putLong(16, cas);
		}

		public byte[] get(byte[] dst, int offs) {
			buf.get(dst, offs, SIZE);
			buf.clear();
			return dst;
		}
	}

	public static class StateMachineSession {
		private static interface State extends Function<ByteBuffer, State> {}
		
		private final Map<Key, Value> map;
		private final Queue<ByteChunker> sendQ;
		private final MessageDigest md;
		private final Header header = new Header();
		private final Key gkey = new Key();
		private final byte[] mdBuf;
		// read bytes and go to next state
		private class ReadState implements State {
			private final byte[] dst;
			private int read;
			private final State nextState;
			
			public ReadState(byte[] dst, State nextState) {
				this.dst = dst;
				this.nextState = nextState;
			}

			@Override
			public State apply(ByteBuffer buf) {
				int size = Math.min(buf.remaining(), dst.length - read);
					
				buf.get(dst, read, size);
				read += size;
				return (read == dst.length) ? nextState : this;
			}
		};
		// hash incoming body and go to next state
		private class HashBodyState implements State {
			private int remaining;
			private final State nextState;
			
			public HashBodyState(State nextState) {
				remaining = (int)header.getBodyLength();
				this.nextState = nextState;
			}

			@Override
			public State apply(ByteBuffer buf) {
				int limit = buf.limit();
				int pos = buf.position();
				int size = Math.min(remaining, limit - pos);
				
				buf.limit(pos + size);
				md.update(buf);
				remaining -= size;
				buf.limit(limit);
				if(remaining > 0)
					return this;
				try {
					md.digest(mdBuf, 0, mdBuf.length);
				} catch (DigestException e) {
					return null;
				}
				md.reset();
				return nextState;
			}
		}
		// header read state
		private final State mainState = new State() {
			@Override
			public State apply(ByteBuffer buf) {
				ByteBuffer headBuf = header.buf;
				int remaining = headBuf.remaining();
				int limit = buf.limit();
				
				buf.limit(Math.min(limit, buf.position() + remaining));
				headBuf.put(buf);
				buf.limit(limit);
				if(headBuf.hasRemaining())
					return this;

				headBuf.position(0);
				
				int op = header.getMagicOpcode() ^ -0x8000;
				
				switch(op) {
				default:
					System.err.println("unknown mop: " + Integer.toString(header.getMagicOpcode(), 16));
				case 0x07:
					// exit
					return null;
				case 0x00: // Get
				case 0x09: // GetQ
				case 0x0c: // GetK
				case 0x0d: // GetKQ
					{
						byte[] k = (op == 0x0c) ? new byte[(int)header.getBodyLength()] : null;
						State nextState = new State() {
							@Override
							public State apply(ByteBuffer t) {
								if(op == 0x0c) {
									md.update(k);								
									try {
										md.digest(mdBuf, 0, mdBuf.length);
									} catch (DigestException e) {
										return null;
									}
									md.reset();									
								}
								gkey.setValue(mdBuf);
								
								Value val = map.get(gkey);

								if(val == null) { // miss
									if(op == 0x00 || op == 0x0c) { // Get[K] -- error response
										header.setMagicOpcode((short)(0x8100 | op));
										header.setExtrasLength((short)0);
										if(op == 0x0c) { // GetK
											header.setKeyLength(k.length);
											header.setBodyLength(k.length);										
										}
										else {
											header.setKeyLength(0);
											header.setBodyLength(0);
										}
										header.setStatus((short)1); // not found
										
										sendHeader();
										if(op == 0x0c) // GetK
											sendQ.offer(new ByteArrayChunker(k, 0, k.length));
									}
								}
								else { // hit
									header.setMagicOpcode((short)(0x8100 | op));
									header.setExtrasLength((short)4);
									header.setCas(val.cas);
									header.setStatus((short)0); // found
									if(op == 0x0c || op == 0x0d) { // GetK[Q] -- return key too
										header.setKeyLength(val.keyLen);
										header.setBodyLength(val.efkvp.length - 4);

										sendHeader();
										sendQ.offer(new ByteArrayChunker(val.efkvp, 4, val.efkvp.length - 4)); // body (fkvp)
									}
									else {
										header.setKeyLength(0);
										header.setBodyLength(val.efkvp.length - 4 - val.keyLen);

										sendHeader();
										sendQ.offer(new ByteArrayChunker(val.efkvp, 4, 4)); // flags									
										sendQ.offer(new ByteArrayChunker(val.efkvp, 4 + val.keyLen, val.efkvp.length - 8 - val.keyLen)); // value
									}
								}
								return mainState;
							}
						};
						if(op == 0x0c) // GetK
							return new ReadState(k, nextState);
						else
							return new HashBodyState(nextState);
					}
				case 0x01: // Set
				case 0x02: // Add
				case 0x03: // Replace
					{
						byte[] efkvp = new byte[(int)header.getBodyLength()];
						State nextState = new State() {
							@Override
							public State apply(ByteBuffer t) {
								for(int i = 0; i < 4; i++) { // swap flags + expiration
									byte tmp = efkvp[i];
									
									efkvp[i] = efkvp[i + 4];
									efkvp[i + 4] = tmp;
								}
								
								int keyLen = header.getKeyLength();
								
								md.update(efkvp, 8, keyLen);
								try {
									md.digest(mdBuf, 0, mdBuf.length);
								} catch (DigestException e) {
									return null;
								}
								md.reset();
								
								Key skey = new Key();
								
								skey.setValue(mdBuf);
								
								Value val = new Value(efkvp, keyLen, header.getCas());
								
								map.put(skey, val);
								header.setMagicOpcode((short)(0x8100 | op));
								header.setExtrasLength((short)0);
								header.setKeyLength(0);
								header.setBodyLength(0);
								header.setCas(val.cas);
								header.setStatus((short)0); // found

								sendHeader();
								return mainState;
							}
						};					
						return new ReadState(efkvp, nextState);
					}
				}
			}
		};
		private State state = mainState;
		
		private void sendHeader() {
			byte[] headerBytes = Arrays.copyOfRange(header.buf.array(), header.buf.arrayOffset(), Header.SIZE);

			header.buf.clear();
			sendQ.offer(new ByteArrayChunker(headerBytes, 0, headerBytes.length));
		}
		
		public StateMachineSession(Map<Key, Value> map, Queue<ByteChunker> sendQ) throws NoSuchAlgorithmException {
			this.map = map;
			this.sendQ = sendQ;
			this.md = MessageDigest.getInstance("sha-1");
			this.mdBuf= new byte[md.getDigestLength()];
		}
		
		public boolean recv(ByteBuffer buf) {
			while(state != null) {
				State curState = state;

				state = state.apply(buf);
				// keep advancing machine until buf is empty AND we're no longer transitioning
				if(state == curState && !buf.hasRemaining())
					break;
			}
			return state != null;
		}
	}
	
	public static class AsyncSession implements AsyncRunnable {
		private final Map<Key, Value> map;
		private final AsyncByteBufferReader abr;
		private final AsyncByteWriter abw;
		private final Executor exec;

		public AsyncSession(Map<Key, Value> map, AsyncByteBufferReader abr, AsyncByteWriter abw, Executor exec) throws NoSuchAlgorithmException {
			this.map = map;
			this.abr = abr;
			this.abw = abw;
			this.exec = exec;
		}

		public CompletableFuture<Void> run() {
			try {
				Header header = new Header();
				Key gkey = new Key();
				MessageDigest md = MessageDigest.getInstance("sha-1");
				byte[] mdBuf = new byte[md.getDigestLength()];
				BiPredicate<ByteBuffer, Boolean> mdUpdateConsumer = (ByteBuffer buf, Boolean needsCopy)->{
					md.update(buf);
					return true;
				};
				
				return AsyncLoop.doWhile(()->{
					try {
						Async.await(abr.read(header.buf));

						if(header.buf.hasRemaining()) // eof
							return AsyncLoop.cfFalse; // TODO reads below ignore eof
						
						int op = header.getMagicOpcode() ^ -0x8000;
						Key skey;
						int keyLen;
						byte[] k = null, efkvp;
						Value val;

						switch(op) {
						default:
							System.err.println("unknown mop: " + Integer.toString(header.getMagicOpcode(), 16));
						case 0x07:
							// exit
							return AsyncLoop.cfFalse;
						case 0x00: // Get
						case 0x09: // GetQ
						case 0x0c: // GetK
						case 0x0d: // GetKQ
							if(op == 0x0c) { // GetK
								Async.await(abr.read(k = new byte[(int)header.getBodyLength()]));
								md.update(k);								
							}
							else
								Async.await(abr.read(mdUpdateConsumer, header.getBodyLength()));
							md.digest(mdBuf, 0, mdBuf.length);
							md.reset();
							gkey.setValue(mdBuf);
							val = map.get(gkey);

							if(val == null) { // miss
								if(op == 0x00 || op == 0x0c) { // Get[K] -- error response
									header.setMagicOpcode((short)(0x8100 | op));
									header.setExtrasLength((short)0);
									if(op == 0x0c) { // GetK
										header.setKeyLength(k.length);
										header.setBodyLength(k.length);										
									}
									else {
										header.setKeyLength(0);
										header.setBodyLength(0);
									}
									header.setStatus((short)1); // not found
									Async.await(writeCopy(header.buf.array(), header.buf.arrayOffset(), Header.SIZE));
									if(op == 0x0c) // GetK
										Async.await(abw.write(k, 0, k.length)); // key
								}
							}
							else { // hit
								header.setMagicOpcode((short)(0x8100 | op));
								header.setExtrasLength((short)4);
								header.setCas(val.cas);
								header.setStatus((short)0); // found
								if(op == 0x0c || op == 0x0d) { // GetK[Q] -- return key too
									header.setKeyLength(val.keyLen);
									header.setBodyLength(val.efkvp.length - 4);
									Async.await(writeCopy(header.buf.array(), header.buf.arrayOffset(), Header.SIZE)); // header
									Async.await(abw.write(val.efkvp, 4, val.efkvp.length - 4)); // body (fkvp)
								}
								else {
									header.setKeyLength(0);
									header.setBodyLength(val.efkvp.length - 4 - val.keyLen);
									Async.await(writeCopy(header.buf.array(), header.buf.arrayOffset(), Header.SIZE)); // header
									Async.await(abw.write(val.efkvp, 4, 4)); // flags									
									Async.await(abw.write(val.efkvp, 4 + val.keyLen, val.efkvp.length - 8 - val.keyLen)); // value
								}
							}
							break;
						case 0x01: // Set
						case 0x02: // Add
						case 0x03: // Replace
							Async.await(abr.read(efkvp = new byte[(int)header.getBodyLength()]));
							for(int i = 0; i < 4; i++) { // swap flags + expiration
								byte tmp = efkvp[i];
								
								efkvp[i] = efkvp[i + 4];
								efkvp[i + 4] = tmp;
							}
							keyLen = header.getKeyLength();
							md.update(efkvp, 8, keyLen);
							md.digest(mdBuf, 0, mdBuf.length);
							md.reset();
							skey = new Key();
							skey.setValue(mdBuf);
							val = new Value(efkvp, keyLen, header.getCas());
							map.put(skey, val);
							header.setMagicOpcode((short)(0x8100 | op));
							header.setExtrasLength((short)0);
							header.setKeyLength(0);
							header.setBodyLength(0);
							header.setCas(val.cas);
							header.setStatus((short)0); // found
							Async.await(writeCopy(header.buf.array(), header.buf.arrayOffset(), Header.SIZE)); // header
							break;
						}
						header.buf.clear();
						return AsyncLoop.cfTrue;
					}
					catch(Throwable th) {
						throw new CompletionException(th);
					}
				}, exec);
			}
			catch(Throwable th) {
				throw new CompletionException(th);
			}
		}
		
		protected CompletableFuture<Void> writeCopy(byte[] buf, int offs, int len) {
			return abw.write(Arrays.copyOfRange(buf, offs, offs + len));
		}
	}
}