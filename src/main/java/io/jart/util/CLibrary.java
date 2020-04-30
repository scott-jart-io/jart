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
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.LastErrorException;

/**
 * JNA methods as well as various c lib constants.
 */
public interface CLibrary extends Library {
	CLibrary INSTANCE = (CLibrary)Native.load("c", CLibrary.class);

	public static final int IFNAMSIZE = 16;

	public static final int O_RDONLY = 0;
	public static final int O_WRONLY = 1;
	public static final int O_RDWR = 2;
	
	public static final short POLLIN = 1;
	public static final short POLLPRI = 2;
	public static final short POLLOUT = 4;
	public static final short POLLERR = 8;
	public static final short POLLHUP = 16;
	public static final short POLLNVAL = 32;

	public static final int PROT_EXEC = 4;
	public static final int PROT_READ = 1;
	public static final int PROT_WRITE = 2;
	public static final int PROT_NONE = 0;
	public static final int MAP_SHARED = 1;
	public static final int MAP_PRIVATE = 2;
	
	/**
	 * Open.
	 *
	 * @param path the path
	 * @param mode the mode
	 * @return the int
	 * @throws LastErrorException the last error exception
	 */
	int open(String path, int mode) throws LastErrorException;
	
	/**
	 * Read.
	 *
	 * @param fd the fd
	 * @param buf the buf
	 * @param count the count
	 * @return the long
	 * @throws LastErrorException the last error exception
	 */
	long read(int fd, Pointer buf, long count) throws LastErrorException;
	
	/**
	 * Write.
	 *
	 * @param fd the fd
	 * @param buf the buf
	 * @param count the count
	 * @return the long
	 * @throws LastErrorException the last error exception
	 */
	long write(int fd, Pointer buf, long count) throws LastErrorException;
	
	/**
	 * Close.
	 *
	 * @param fd the fd
	 * @return the int
	 * @throws LastErrorException the last error exception
	 */
	int close(int fd) throws LastErrorException;

	/**
	 * Ioctl.
	 *
	 * @param fd the fd
	 * @param cmd the cmd
	 * @param args the args
	 * @return the int
	 * @throws LastErrorException the last error exception
	 */
	int ioctl(int fd, long cmd, Object...args) throws LastErrorException;
    
    /**
     * Poll.
     *
     * @param fdsPtr the fds ptr
     * @param nfds the nfds
     * @param timeout the timeout
     * @return the int
     * @throws LastErrorException the last error exception
     */
    int poll(Pointer fdsPtr, long nfds, int timeout) throws LastErrorException;

    /**
     * Pipe.
     *
     * @param fdsPtr the fds ptr
     * @return the int
     * @throws LastErrorException the last error exception
     */
    int pipe(Pointer fdsPtr) throws LastErrorException;
    
    /**
     * Mmap.
     *
     * @param addr the addr
     * @param length the length
     * @param prot the prot
     * @param flags the flags
     * @param fd the fd
     * @param offset the offset
     * @return the pointer
     * @throws LastErrorException the last error exception
     */
    Pointer mmap(Pointer addr, long length, int prot, int flags, int fd, long offset) throws LastErrorException;
    
    /**
     * Munmap.
     *
     * @param addr the addr
     * @param length the length
     * @return the int
     * @throws LastErrorException the last error exception
     */
    int munmap(Pointer addr, long length) throws LastErrorException;

    // FreeBSD
    
    public static final short RTP_PRIO_REALTIME = 2;
    public static final short RTP_PRIO_FIFO = 10;
    public static final short RTP_PRIO_NORMAL = 2;
    public static final short RTP_PRIO_IDLE = 4;
    
    public static final int RTP_LOOKUP = 0;
    public static final int RTP_SET = 1;
    
    /*
     * Valid cpulevel_t values.
     */
    public static final int CPU_LEVEL_ROOT          = 1;       /* All system cpus. */
    public static final int CPU_LEVEL_CPUSET        = 2;       /* Available cpus for which. */
    public static final int CPU_LEVEL_WHICH         = 3;       /* Actual mask/id for which. */

    /*
     * Valid cpuwhich_t values.
     */
    public static final int CPU_WHICH_TID           = 1;       /* Specifies a thread id. */
    public static final int CPU_WHICH_PID           = 2;       /* Specifies a process id. */
    public static final int CPU_WHICH_CPUSET        = 3;       /* Specifies a set id. */
    public static final int CPU_WHICH_IRQ           = 4;       /* Specifies an irq #. */
    public static final int CPU_WHICH_JAIL          = 5;       /* Specifies a jail id. */
    public static final int CPU_WHICH_DOMAIN        = 6;       /* Specifies a NUMA domain id. */
    public static final int CPU_WHICH_INTRHANDLER   = 7;       /* Specifies an irq # (not ithread). */
    public static final int CPU_WHICH_ITHREAD       = 8;       /* Specifies an irq's ithread. */
    
    /**
     * Thr self.
     *
     * @param id the id
     * @return the int
     * @throws LastErrorException the last error exception
     */
    int thr_self(Pointer id) throws LastErrorException;
    
    /**
     * Rtprio thread.
     *
     * @param function the function
     * @param lwpid the lwpid
     * @param rpt the rpt
     * @return the int
     * @throws LastErrorException the last error exception
     */
    int rtprio_thread(int function, int lwpid, Pointer rpt) throws LastErrorException;
    
    /**
     * Cpuset getaffinity.
     *
     * @param level the level
     * @param which the which
     * @param id the id
     * @param setsize the setsize
     * @param mask the mask
     * @return the int
     * @throws LastErrorException the last error exception
     */
    int cpuset_getaffinity(int level, int which, long id, long setsize, Pointer mask) throws LastErrorException;
    
    /**
     * Cpuset setaffinity.
     *
     * @param level the level
     * @param which the which
     * @param id the id
     * @param setsize the setsize
     * @param mask the mask
     * @return the int
     * @throws LastErrorException the last error exception
     */
    int cpuset_setaffinity(int level, int which, long id, long setsize, Pointer mask) throws LastErrorException;

    // Linux
    
	public static final int LINUX_SCHED_OTHER = 0; // order is different on bsd for some reason
	public static final int LINUX_SCHED_FIFO = 1;
	public static final int LINUX_SCHED_RR = 2;
	
    /**
     * Sched getparam.
     *
     * @param pid the pid
     * @param param the param
     * @return the int
     * @throws LastErrorException the last error exception
     */
    int sched_getparam(int pid, Pointer param) throws LastErrorException;
    
    /**
     * Sched setparam.
     *
     * @param pid the pid
     * @param param the param
     * @return the int
     * @throws LastErrorException the last error exception
     */
    int sched_setparam(int pid, Pointer param) throws LastErrorException;
    
    /**
     * Sched get priority max.
     *
     * @param policy the policy
     * @return the int
     * @throws LastErrorException the last error exception
     */
    int sched_get_priority_max(int policy) throws LastErrorException;
    
    /**
     * Sched get priority min.
     *
     * @param policy the policy
     * @return the int
     * @throws LastErrorException the last error exception
     */
    int sched_get_priority_min(int policy) throws LastErrorException;
    
    /**
     * Sched setscheduler.
     *
     * @param pid the pid
     * @param policy the policy
     * @param param the param
     * @return the int
     * @throws LastErrorException the last error exception
     */
    int sched_setscheduler(int pid, int policy, Pointer param) throws LastErrorException;
    
    /**
     * Sched getscheduler.
     *
     * @param pid the pid
     * @return the int
     * @throws LastErrorException the last error exception
     */
    int sched_getscheduler(int pid) throws LastErrorException;
    
    public static final long SYS_gettid = 186;
    
    /**
     * Syscall.
     *
     * @param number the number
     * @param args the args
     * @return the long
     */
    long syscall(long number, Object ...args);
}
