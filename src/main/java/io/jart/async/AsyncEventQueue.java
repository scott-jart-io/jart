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

package io.jart.async;

import io.jart.util.EventQueue;

// not sure if this is a flavor of an existing queue. i can't immediately find what type if so.
// it's optimized for lots of event churn -- events being added then removed, as hopefully most
// tcp retransmission timers never have to fire!
// insert/erase are O(1); average number of moves between lists for a given event is roughly
// log2(M)/2 where M is the average distance from "now" at add time; so omega(log(M))
public class AsyncEventQueue implements EventQueue {
	public static class Event {
		Event prev;
		Event next;
		
		protected final AsyncPipe<? super Event> eventPipe;
		
		private long time;

		public Event(AsyncPipe<? super Event> pipe, long time) {
			prev = next = this;
			this.eventPipe = pipe;
			this.time = time;
		}
		
		public Event(AsyncPipe<? super Event> pipe) {
			this(pipe, Long.MAX_VALUE);
		}

		Event() {
			this(null, Long.MAX_VALUE);
		}

		public long getTime() {
			return time;
		}

		public void setTime(long time) {
			if(prev != this || next != this)
				throw new IllegalStateException("trying to set time on active event");
			this.time = time;
		}

		void add(Event event) {
			event.prev = this;
			event.next = next;
			next.prev = event;
			next = event;
		}

		void remove() {
			prev.next = next;
			next.prev = prev;
			prev = next = this;
		}
	}

	private Event[] lanes = new Event[] { new Event() }; // each lane is circular w/ a sentinel here
	private long lastTime = 0;

	private void addEvent(Event event) {
		long d = Math.max(0,  event.time - lastTime);
		int laneNo = 64 - Long.numberOfLeadingZeros(d);
		Event headTail;

		if(laneNo < lanes.length)
			headTail = lanes[laneNo];
		else {
			Event[] oldLanes = lanes;

			lanes = new Event[laneNo + 1];
			System.arraycopy(oldLanes, 0, lanes, 0, oldLanes.length);
			for(int i = oldLanes.length; i < laneNo; i++)
				lanes[i] = new Event();
			headTail = lanes[laneNo] = new Event();
		}
		headTail.add(event);
	}

	// add (if not already added somewhere)
	public void add(Event event) {
		if(event.next == event)
			addEvent(event);
	}

	@Override
	public void remove(Event event) {
		event.remove();
	}
	
	// update current time
	public void updateTime(long curTime, boolean deliver) {
		if(curTime <= lastTime)
			return; // early out if nothing to do

		lastTime = curTime;

		// iterate lanes promoting all events that should go to the next (lesser) lane
		long threshDiff = 1;

		for(int i = 1; i < lanes.length; i++, threshDiff <<= 1) {
			Event sentinel = lanes[i];
			Event cur = sentinel.prev;
			long thresh = curTime + threshDiff;

			while(cur.time < thresh) {
				Event prev = cur.prev;

				addEvent(cur);
				cur = prev;
			}
			if((sentinel.prev = cur) == sentinel)
				sentinel.next = sentinel; // emptied!
			else
				cur.next = sentinel;
		}
		if(deliver)
			this.deliver();
	}

	// return next time update will have any effect -- 
	// can be used as a conservative estimate of next time more events will 
	// become deliverable
	public long nextUpdateTime() {
		long nextUpdateTime = Long.MAX_VALUE;
		long threshDiff = 1;

		for(int i = 1; i < lanes.length; i++, threshDiff <<= 1)
			nextUpdateTime = Math.min(nextUpdateTime, lanes[i].prev.time - threshDiff + 1);		
		return nextUpdateTime;
	}

	// fire any events that have arrived
	public void deliver() {
		// fire everything in lane 0
		Event prev = lanes[0];
		Event cur = prev.next;

		// remove sentinel
		prev.remove();
		while(cur != prev) {
			prev = cur;
			cur = cur.next;
			prev.remove();
			prev.eventPipe.write(prev);
		}
	}

	@Override
	public void update(Event event, long time) {
		remove(event);
		event.setTime(time);
		addEvent(event);
	}
}
