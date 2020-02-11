# JART

----
## What is JART?

JARTs are Java-based Asynchronous Real Time sockets.

JART uses [JNA](https://github.com/java-native-access/jna) to build a pure Java TCP/IP stack upon [Netmap](https://github.com/luigirizzo/netmap). It is implemented in imperative style (no TCP state machine) using asynchronous programming with the help of [ea-async](https://github.com/electronicarts/ea-async).

JART runs on both Linux (with the proper Netmap kernel module) and FreeBSD with Netmap enabled. FreeBSD 12.1+ has Netmap in the kernel by default so it works "out of the box".

While JART has proven fairly robust in limited testing, it is still a work-in-progress and may not be suitable for production use. Pull requests welcome!

----
## Why JART?

- pure java -- no JNI required
- async -- imperative style using async/await; implementation is easier to follow than a state machine (your mileage may vary)
- real time -- normally responds to received packets in the same network cycle
- efficient -- zero copy packet processing; clients can read from/write to Netmap buffers directly
- highly concurrent -- multiple async tasks run on multiple worker threads in parallel
- per-task/connection dynamic thread/cpu affinity -- cache-friendly
- performant -- toy [memcached](https://memcached.org/) implementation (included) performs significantly better than native memcached in informal testing with [memtier\_benchmark](https://github.com/RedisLabs/memtier_benchmark)
- abstract writes -- concrete output generation can be deferred until packet composition time

----
## Sample app quick start

* build with Maven (or in Eclipse using the Maven plug-in) using NetmappingBridge as entry point
* configure nic:
----
FreeBSD (12.1+; or earlier, built with recent Netmap support):

    sudo chmod 0666 /dev/netmap # if not running as root
    sudo ifconfig em0 -rxcsum -txcsum -tso -lro

Linux: (assuming kernel module is loaded and device's mode bits are properly set)

    sudo ethtool -K eth0 tx off rx off gso off tso off gro off lro off

* run with network and threading parameters:

----
    java -jar jart.jar network_device tcp_mss worker_thread_count

#### Ex:
----
    java -jar jart.jar em0 1460 4

### NetmappingBridge

The sample app runs two services:

* toy memcached implementation (binary protocol only, only a handful of commands supported; enough to run memtier_benchmark) on port 11211
* toy httpd implementation that will GET static files from and PUT static files to (by default) the www subdirectory under your home directory, and hash using sha1 anything POST-ed; the sha1 consumes data directly from Netmap packets; runs on port 80

