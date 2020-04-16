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

package io.jart.netmap;

public class Netmap {
	public static final String NETMAP_GIT_HASH = "b316518f5174d13fccc1203a38dffea62e79a634";
	public static final int NETMAP_API = 14;
	
	public static final long NIOCREGIF = 0xc03c6992L;
	
	public static final int NR_REG_ALL_NIC	= 1;
	public static final int NR_REG_SW	= 2;
	public static final int NR_REG_NIC_SW	= 3;
	public static final int NR_REG_ONE_NIC	= 4;
	public static final int NR_REG_PIPE_MASTER = 5; /* deprecated, use "x{y" port name syntax */
	public static final int NR_REG_PIPE_SLAVE = 6;  /* deprecated, use "x}y" port name syntax */
	public static final int NR_REG_NULL     = 7;

	public static final int NETMAP_HW_RING = 0x4000;  // single NIC ring pair 
	public static final int NETMAP_SW_RING = 0x2000;  // only host ring pair 

	public static final int NETMAP_RING_MASK = 0x0fff;  // the ring number 

	public static final int NETMAP_NO_TX_POLL = 0x1000;  // no automatic txsync on poll 

	public static final int NETMAP_DO_RX_POLL = 0x8000;  // DO automatic rxsync on poll 
		
	public static final int NETMAP_BDG_ATTACH = 1;       // attach the NIC 
	public static final int NETMAP_BDG_DETACH = 2;       // detach the NIC 
	public static final int NETMAP_BDG_REGOPS = 3;       // register bridge callbacks 
	public static final int NETMAP_BDG_LIST = 4;       // get bridge's info 
	public static final int NETMAP_BDG_VNET_HDR = 5;       // set the port virtio-net-hdr length 
	public static final int NETMAP_BDG_NEWIF = 6;       // create a virtual port 
	public static final int NETMAP_BDG_DELIF = 7;       // destroy a virtual port 
	public static final int NETMAP_PT_HOST_CREATE = 8;       // create ptnetmap kthreads 
	public static final int NETMAP_PT_HOST_DELETE = 9;       // delete ptnetmap kthreads 
	public static final int NETMAP_BDG_POLLING_ON = 10;      // delete polling kthread 
	public static final int NETMAP_BDG_POLLING_OFF = 11;      // delete polling kthread 
	public static final int NETMAP_VNET_HDR_GET = 12;      // get the port virtio-net-hdr length 
	public static final int NETMAP_BDG_HOST = 1;       // nr_arg1 value for NETMAP_BDG_ATTACH 
	public static final int NR_REG_MASK = 0xf; // to extract NR_REG_* mode from nr_flags 
	
	public static final int NS_BUF_CHANGED = 0x0001;	/* buf_idx changed */
	public static final int NS_REPORT = 0x0002;	/* ask the hardware to report results */
	public static final int NS_FORWARD = 0x0004;	/* pass packet 'forward' */
	public static final int NS_NO_LEARN	= 0x0008;	/* disable bridge learning */
	public static final int NS_INDIRECT	= 0x0010;	/* userspace buffer */
	public static final int NS_MOREFRAG	= 0x0020;	/* packet has more fragments */
}