/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * main.c - main function
 */

#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/personality.h>
#include <sys/utsname.h>
#include <unistd.h>

#include "clatd.h"
#include "common.h"
#include "config.h"
#include "logging.h"

#define DEVICEPREFIX "v4-"

/* function: handle_sigterm
 * signal handler: stop the event loop
 */
static void handle_sigterm(__attribute__((unused)) int unused) { sigterm = 1; };

/* function: print_help
 * in case the user is running this on the command line
 */
void print_help() {
  printf("android-clat arguments:\n");
  printf("-i [uplink interface]\n");
  printf("-p [plat prefix]\n");
  printf("-4 [IPv4 address]\n");
  printf("-6 [IPv6 address]\n");
  printf("-t [tun file descriptor number]\n");
  printf("-r [read socket descriptor number]\n");
  printf("-w [write socket descriptor number]\n");
}

/* function: main
 * allocate and setup the tun device, then run the event loop
 */
int main(int argc, char **argv) {
  struct tun_data tunnel;
  int opt;
  char *uplink_interface = NULL, *plat_prefix = NULL;
  char *v4_addr = NULL, *v6_addr = NULL, *tunfd_str = NULL, *read_sock_str = NULL,
       *write_sock_str = NULL;
  unsigned len;

  // Clatd binary is setuid/gid CLAT, thus when we reach here we have:
  //   $ adb shell ps | grep clat
  //                [pid] [ppid]
  //   clat          7650  1393   10785364   2612 do_sys_poll         0 S clatd-wlan0
  //   $ adb shell cat /proc/7650/status | egrep -i '^(Uid:|Gid:|Groups:)'
  //         [real][effective][saved][filesystem]
  //          [uid]   [euid]  [suid]  [fsuid]
  //   Uid:    1000    1029    1029    1029
  //          [gid]   [egid]  [sgid]  [fsgid]
  //   Gid:    1000    1029    1029    1029
  //   Groups: 1001 1002 1003 1004 1005 1006 1007 1008 1009 1010 1018 1021 1023 1024 1032 1065 3001 3002 3003 3005 3006 3007 3009 3010 3011 3012
  // This mismatch between uid & euid appears to cause periodic (every 5 minutes):
  //                                                  objhash pid  ppid             uid
  //   W ActivityManager: Stale PhantomProcessRecord {xxxxxxx 7650:1393:clatd-wlan0/1000}, removing
  // This is due to:
  //   $ adbz shell ls -ld /proc/7650
  //   dr-xr-xr-x 9 clat clat 0 2025-03-14 11:37 /proc/7650
  // which is used by
  //   //frameworks/base/core/java/com/android/internal/os/ProcessCpuTracker.java
  // which thus returns the uid 'clat' vs
  //   //frameworks/base/core/java/android/os/Process.java
  // getUidForPid() which grabs *real* 'uid' from /proc/<pid>/status and is used in:
  //   //frameworks/base/services/core/java/com/android/server/am/PhantomProcessList.java
  // (perhaps this should grab euid instead? unclear)
  //
  // However, we want to drop as many privs as possible, hence:
  gid_t egid = getegid();  // documented to never fail, hence should return AID_CLAT == 1029
  uid_t euid = geteuid();  // (ditto)
  setresgid(egid, egid, egid);  // ignore any failure
  setresuid(euid, euid, euid);  // ignore any failure
  // ideally we'd somehow drop supplementary groups too...
  // but for historical reasons that actually requires CAP_SETGID which we don't have
  // (see man 2 setgroups)
  //
  // Now we (should) have:
  // $ adb shell ps | grep clat
  // clat          5370  1479   10785364   2528 do_sys_poll         0 S clatd-wlan0
  // # adb shell cat /proc/5370/status | egrep -i '^(Uid:|Gid:|Groups:)'
  // Uid:    1029    1029    1029    1029
  // Gid:    1029    1029    1029    1029
  // Groups: 1001 1002 1003 1004 1005 1006 1007 1008 1009 1010 1018 1021 1023 1024 1032 1065 3001 3002 3003 3005 3006 3007 3009 3010 3011 3012

  while ((opt = getopt(argc, argv, "i:p:4:6:t:r:w:h")) != -1) {
    switch (opt) {
      case 'i':
        uplink_interface = optarg;
        break;
      case 'p':
        plat_prefix = optarg;
        break;
      case '4':
        v4_addr = optarg;
        break;
      case '6':
        v6_addr = optarg;
        break;
      case 't':
        tunfd_str = optarg;
        break;
      case 'r':
        read_sock_str = optarg;
        break;
      case 'w':
        write_sock_str = optarg;
        break;
      case 'h':
        print_help();
        exit(0);
      default:
        logmsg(ANDROID_LOG_FATAL, "Unknown option -%c. Exiting.", (char)optopt);
        exit(1);
    }
  }

  if (uplink_interface == NULL) {
    logmsg(ANDROID_LOG_FATAL, "clatd called without an interface");
    exit(1);
  }

  if (tunfd_str != NULL && !parse_int(tunfd_str, &tunnel.fd4)) {
    logmsg(ANDROID_LOG_FATAL, "invalid tunfd %s", tunfd_str);
    exit(1);
  }
  if (!tunnel.fd4) {
    logmsg(ANDROID_LOG_FATAL, "no tunfd specified on commandline.");
    exit(1);
  }

  if (read_sock_str != NULL && !parse_int(read_sock_str, &tunnel.read_fd6)) {
    logmsg(ANDROID_LOG_FATAL, "invalid read socket %s", read_sock_str);
    exit(1);
  }
  if (!tunnel.read_fd6) {
    logmsg(ANDROID_LOG_FATAL, "no read_fd6 specified on commandline.");
    exit(1);
  }

  if (write_sock_str != NULL && !parse_int(write_sock_str, &tunnel.write_fd6)) {
    logmsg(ANDROID_LOG_FATAL, "invalid write socket %s", write_sock_str);
    exit(1);
  }
  if (!tunnel.write_fd6) {
    logmsg(ANDROID_LOG_FATAL, "no write_fd6 specified on commandline.");
    exit(1);
  }

  len = snprintf(tunnel.device4, sizeof(tunnel.device4), "%s%s", DEVICEPREFIX, uplink_interface);
  if (len >= sizeof(tunnel.device4)) {
    logmsg(ANDROID_LOG_FATAL, "interface name too long '%s'", tunnel.device4);
    exit(1);
  }

  Global_Clatd_Config.native_ipv6_interface = uplink_interface;
  if (!plat_prefix || inet_pton(AF_INET6, plat_prefix, &Global_Clatd_Config.plat_subnet) <= 0) {
    logmsg(ANDROID_LOG_FATAL, "invalid IPv6 address specified for plat prefix: %s", plat_prefix);
    exit(1);
  }

  if (!v4_addr || !inet_pton(AF_INET, v4_addr, &Global_Clatd_Config.ipv4_local_subnet.s_addr)) {
    logmsg(ANDROID_LOG_FATAL, "Invalid IPv4 address %s", v4_addr);
    exit(1);
  }

  if (!v6_addr || !inet_pton(AF_INET6, v6_addr, &Global_Clatd_Config.ipv6_local_subnet)) {
    logmsg(ANDROID_LOG_FATAL, "Invalid source address %s", v6_addr);
    exit(1);
  }

  logmsg(ANDROID_LOG_INFO, "Starting clat version " CLATD_VERSION " on %s plat=%s v4=%s v6=%s",
         uplink_interface, plat_prefix ? plat_prefix : "(none)", v4_addr ? v4_addr : "(none)",
         v6_addr ? v6_addr : "(none)");

  {
    // Compile time detection of 32 vs 64-bit build. (note: C does not have 'constexpr')
    // Avoid use of preprocessor macros to get compile time syntax checking even on 64-bit.
    const int user_bits = sizeof(void*) * 8;
    const bool user32 = (user_bits == 32);

    // Note that on 64-bit all this personality related code simply compile optimizes out.
    // 32-bit: fetch current personality (see 'man personality': 0xFFFFFFFF means retrieve only)
    // On Linux fetching personality cannot fail.
    const int prev_personality = user32 ? personality(0xFFFFFFFFuL) : PER_LINUX;
    // 32-bit: attempt to get rid of kernel spoofing of 'uts.machine' architecture,
    // In theory this cannot fail, as PER_LINUX should always be supported.
    if (user32) (void)personality((prev_personality & ~PER_MASK) | PER_LINUX);
    // 64-bit: this will compile time evaluate to false.
    const bool was_linux32 = (prev_personality & PER_MASK) == PER_LINUX32;

    struct utsname uts = {};
    if (uname(&uts)) exit(1); // only possible error is EFAULT, but 'uts' is on stack

    // sysname is likely 'Linux', release is 'kver', machine is kernel's *true* architecture
    logmsg(ANDROID_LOG_INFO, "%d-bit userspace on %s kernel %s for %s%s.", user_bits,
           uts.sysname, uts.release, uts.machine, was_linux32 ? " (was spoofed)" : "");

    // 32-bit: try to return to the 'default' personality
    // In theory this cannot fail, because it was already previously in use.
    if (user32) (void)personality(prev_personality);
  }

  // Loop until someone sends us a signal or brings down the tun interface.
  if (signal(SIGTERM, handle_sigterm) == SIG_ERR) {
    logmsg(ANDROID_LOG_FATAL, "sigterm handler failed: %s", strerror(errno));
    exit(1);
  }

  // Apparently some network gear will refuse to perform NS for IPs that aren't DAD'ed,
  // this would then result in an ipv6-only network with working native ipv6, working
  // IPv4 via DNS64, but non-functioning IPv4 via CLAT (ie. IPv4 literals + IPv4 only apps).
  // The kernel itself doesn't do DAD for anycast ips (but does handle IPV6 MLD and handle ND).
  // So we'll spoof dad here, and yeah, we really should check for a response and in
  // case of failure pick a different IP.  Seeing as 48-bits of the IP are utterly random
  // (with the other 16 chosen to guarantee checksum neutrality) this seems like a remote
  // concern...
  // TODO: actually perform true DAD
  send_dad(tunnel.write_fd6, &Global_Clatd_Config.ipv6_local_subnet);

  event_loop(&tunnel);

  if (sigterm) {
    logmsg(ANDROID_LOG_INFO, "Shutting down clatd on %s, already received SIGTERM", uplink_interface);
  } else {
    // this implies running == false, ie. we received EOF or ENETDOWN error.
    logmsg(ANDROID_LOG_INFO, "Shutting down clatd on %s, waiting for SIGTERM", uplink_interface);
    // let's give higher level java code 15 seconds to kill us,
    // but eventually terminate anyway, in case system server forgets about us...
    // sleep() should be interrupted by SIGTERM, the handler should set 'sigterm'
    sleep(15);
    logmsg(ANDROID_LOG_INFO, "Clatd on %s %s SIGTERM", uplink_interface,
           sigterm ? "received" : "timed out waiting for");
  }
  return 0;
}
