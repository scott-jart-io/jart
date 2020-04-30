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

/**
 * EventQueue implementation that asynchronously dispatches events using AsyncPipes.
 * 
 * not sure if this is a flavor of an existing queue. i can't immediately find what type if so.
 * it's optimized for lots of event churn -- events being added then removed, as hopefully most
 * tcp retransmission timers never have to fire!
 * insert/erase are O(1); average number of moves between lists for a given event is roughly
 * log2(M)/2 where M is the average distance from "now" at add time; so omega(log(M))
 */
public class AsyncEventQueue implements EventQueue {
	
	/**
	 * An Event in a linked list of events
	 */
	public static class Event {
		// linked list references
		Event prev;
		Event next;
		
		protected final AsyncPipe<? super Event> eventPipe; // pipe to which we dispatch the event
		
		private long time; // when the event is scheduled to fire

		/**
		 * Instantiates a new event.
		 *
		 * @param pipe the pipe to which the event will be dispatched
		 * @param time the time the event is scheduled to fire
		 */
		public Event(AsyncPipe<? super Event> pipe, long time) {
			prev = next = this;
			this.eventPipe = pipe;
			this.time = time;
		}
		
		/**
		 * Instantiates a new event initially set to "never" fire (scheduled time is Long MAX_VALUE).
		 *
		 * @param pipe the pipe
		 */
		public Event(AsyncPipe<? super Event> pipe) {
			this(pipe, Long.MAX_VALUE);
		}

		/**
		 * Instantiates a new dummy event.
		 */
		Event() {
			this(null, Long.MAX_VALUE);
		}

		/**
		 * Gets the scheduled fire time.
		 *
		 * @return the time
		 */
		public long getTime() {
			return time;
		}

		/**
		 * Sets the scheduled fire time.
		 *
		 * Only valid for events that are not enqueued.
		 * 
		 * @param time the new time
		 */
		public void setTime(long time) {
			if(prev != this || next != this)
				throw new IllegalStateException("trying to set time on active event");
			this.time = time;
		}

		/**
		 * Adds the provided event after this event in the linked list.
		 *
		 * @param event the event
		 */
		void add(Event event) {
			event.prev = this;
			event.next = next;
			next.prev = event;
			next = event;
		}

		/**
		 * Removes this event from the linked list.
		 */
		void remove() {
			prev.next = next;
			next.prev = prev;
			prev = next = this;
		}
	}

	private Event[] lanes = new Event[] { new Event() }; // each lane is circular w/ a sentinel here
	private long lastTime = 0;

	/**
	 * Adds the event in its proper place in the queue.
	 *
	 * @param event the event
	 */
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

	/**
	 * Adds the event to the linked list if it isn't already in a queue.
	 *
	 * @param event the event
	 */
	// add (if not already added somewhere)
	public void add(Event event) {
		if(event.next == event)
			addEvent(event);
	}

	/**
	 * Removes the event from the queue.
	 *
	 * @param event the event
	 */
	@Override
	public void remove(Event event) {
		event.remove();
	}
	
	/**
	 * Update time of the queue and optionally delivers events that have become due.
	 *
	 * @param curTime the cur time
	 * @param deliver if true, deliver messages that have become due
	 */
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

	/**
	 * Caclulate the next time at which updateTime(long curTime, boolean deliver) will do ant work.
	 * 
	 * Does NOT guarantee that the returned time will result in any events becoming due. Just that updateTime will do work.
	 * Still, it can be used an a conservative estimate of when the next event will become due.
	 *
	 * @return the next time at which updateTime will do any work
	 */
	public long nextUpdateTime() {
		long nextUpdateTime = Long.MAX_VALUE;
		long threshDiff = 1;

		for(int i = 1; i < lanes.length; i++, threshDiff <<= 1)
			nextUpdateTime = Math.min(nextUpdateTime, lanes[i].prev.time - threshDiff + 1);		
		return nextUpdateTime;
	}

	/**
	 * Deliver an events that are already due. Does not update the current time.
	 */
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

	/**
	 * Update an event's scheduled time.
	 *
	 * @param event the event to update
	 * @param time the new delivery time time
	 */
	@Override
	public void update(Event event, long time) {
		remove(event);
		event.setTime(time);
		addEvent(event);
	}
}
