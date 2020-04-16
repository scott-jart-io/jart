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

import java.util.concurrent.ThreadFactory;

import org.apache.log4j.Logger;

import com.sun.jna.Memory;

public class RTThreadFactory implements ThreadFactory {
	private final static Logger logger = Logger.getLogger(ThreadFactory.class);

	public static boolean setRTPrio() {
		try {
			if(Misc.IS_FREEBSD) {
				Memory buf = new Memory(8);
				
				CLibrary.INSTANCE.thr_self(buf);
				
				long lwpid = buf.getLong(0);
				
				buf.setShort(0, CLibrary.RTP_PRIO_REALTIME);
				buf.setShort(2, (short)2); // high priority
				
				CLibrary.INSTANCE.rtprio_thread(CLibrary.RTP_SET, (int)lwpid, buf);
			}
			else { // linux
				Memory schedParam = new Memory(4);
				int prio = CLibrary.INSTANCE.sched_get_priority_min(CLibrary.LINUX_SCHED_RR);
				int tid = (int) CLibrary.INSTANCE.syscall(CLibrary.SYS_gettid);
	
				schedParam.setInt(0, prio);
				CLibrary.INSTANCE.sched_setscheduler(tid, CLibrary.LINUX_SCHED_RR, schedParam);
			}
			return true;
		}
		catch(Throwable throwable) {
			logger.warn("error trying to set realtime priority", throwable);
			return false;
		}
	}
	
	public static Runnable wrap(Runnable r) {
		return new Runnable() {
			@Override
			public void run() {
				setRTPrio();
				r.run();
			}			
		};
	}
	
	@Override
	public Thread newThread(Runnable r) {
		return new Thread(wrap(r));
	}
}
