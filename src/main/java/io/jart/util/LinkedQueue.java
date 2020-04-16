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

package io.jart.util;

import java.util.AbstractQueue;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicReference;

// simple thread safe queue that supports link pooling
public class LinkedQueue<T> extends AbstractQueue<T> {
	// this type of pool is safe as long as it only "ticks" when no queue operations are in progress
	public static class TickTockPoolHelper extends LinkedQueue<Object> {
		private TickTockPoolHelper() {}

		public static TickTockPool<?> createLinkPool() {
			return new TickTockPool<Link>(Link::new, (Link link)->{
				link.next.set(null);
				link.value = null;
			});
		}
	}

	protected static class Link {
		public final AtomicReference<Link> next;
		public Object value;
		
		public Link() {
			this.next = new AtomicReference<Link>();
		}

		public Link(Link next, Object value) {
			this.next = new AtomicReference<Link>(next);
			this.value = value;
		}
	}
	
	protected Link allocLink(Link next, Object value) {
		return new Link(next, value);
	}
	
	protected void freeLink(Link link) {}
	
	private Link nullLink() { // "null" links must be unique
		return allocLink(null, null);
	}
	
	private static boolean isNull(Link link) {
		return link == null || link.value == null;
	}
	
	private final AtomicReference<Link> head = new AtomicReference<Link>(new Link(null, null));
	private final AtomicReference<Link> tail = new AtomicReference<Link>(head.get());

	@Override
	public boolean offer(T e) {
//			E1: node = new node() # Allocate a new node from the free list
//			E2: node>value = value # Copy enqueued value into node
//			E3: node>next.ptr = NULL # Set next pointer of node to NULL
		Link node = allocLink(nullLink(), e);
//			E4: loop # Keep trying until Enqueue is done
		Link tail;
		
		for(;;) {
			Link next;
//			E5: tail = Q>Tail # Read Tail.ptr and Tail.count together
			tail = this.tail.get();
//			E6: next = tail.ptr>next # Read next ptr and count fields together
			next = tail.next.get();
//			E7: if tail == Q>Tail # Are tail and next consistent?
			if(tail == this.tail.get()) {
//			E8: if next.ptr == NULL # Was Tail pointing to the last node?
				if(isNull(next)) {
//			E9: if CAS(&tail.ptr>next, next, <node, next.count+1>) # Try to link node at the end of the linked list
					if(tail.next.compareAndSet(next, node)) {
						freeLink(next); // free the null link
//			E10: break # Enqueue is done. Exit loop
						break;
//			E11: endif
					}
//			E12: else # Tail was not pointing to the last node
				} else {
//			E13: CAS(&Q>Tail, tail, <next.ptr, tail.count+1>) # Try to swing Tail to the next node
					this.tail.compareAndSet(tail, next);
//			E14: endif
				}
//			E15: endif
			}
//			E16: endloop
		}
//			E17: CAS(&Q>Tail, tail, <node, tail.count+1>) # Enqueue is done. Try to swing Tail to the inserted node
		this.tail.compareAndSet(tail, node);
		return true;
	}

	@SuppressWarnings("unchecked")
	@Override
	public T poll() {
//			D1: loop # Keep trying until Dequeue is done
		Link head;
		T value;
		
		for(;;) {
			Link tail, next;
			
//			D2: head = Q>Head # Read Head
			head = this.head.get();
//			D3: tail = Q>Tail # Read Tail
			tail = this.tail.get();
//			D4: next = head>next # Read Head.ptr>next
			next = head.next.get();
//			D5: if head == Q>Head # Are head, tail, and next consistent?
			if(head == this.head.get()) {
//			D6: if head.ptr == tail.ptr # Is queue empty or Tail falling behind?
				if(head == tail) {
//			D7: if next.ptr == NULL # Is queue empty?
					if(isNull(next) ) {
//			D8: return FALSE # Queue is empty, couldn't dequeue
						return null;
//			D9: endif
					}
//			D10: CAS(&Q>Tail, tail, <next.ptr, tail.count+1>) # Tail is falling behind. Try to advance it
					this.tail.compareAndSet(tail, next);
//			D11: else # No need to deal with Tail
				} else {
//			# Read value before CAS, otherwise another dequeue might free the next node
//			D12: *pvalue = next.ptr>value
					value = (T) next.value;
//			D13: if CAS(&Q>Head, head, <next.ptr, head.count+1>) # Try to swing Head to the next node
					if(this.head.compareAndSet(head, next)) {
//			D14: break # Dequeue is done. Exit loop
						break;
//			D15: endif
					}
//			D16: endif
				}
//			D17: endif
			}
//			D18: endloop
		}
//			D19: free(head.ptr) # It is safe now to free the old dummy node
		freeLink(head);
//			D20: return TRUE # Queue was not empty, dequeue succeeded
		return value;
	}

	@Override
	public T peek() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Iterator<T> iterator() {
		throw new UnsupportedOperationException();
	}

	@Override
	public int size() {
		throw new UnsupportedOperationException();
	}
}
