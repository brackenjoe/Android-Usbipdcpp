#!/usr/bin/env python3
"""Exercise usbip-dialin.service's sandbox from inside it. Standard library only.

Run in a transient unit carrying the unit file's own [Service] sandbox lines
(`systemd-run -p ...`), as root, by whatever installs the service. Each check
does the thing the option should allow or refuse and reports the errno it
got. Nothing here changes the host: writes that should be refused are
attempted only where a success would be harmless (a fresh file name), sysfs
and procfs files are opened for writing and closed without a byte written,
module loading is asked for with an invalid descriptor, and the one file it
creates in /run/vhci_hcd is removed again.

    usbip_dialin_sandbox_probe.py [TAILNET_IP [HOST_UTS_NS]]

TAILNET_IP is this host's tailnet IPv4 address; without it the probe asks
`tailscale ip -4`. HOST_UTS_NS is `readlink /proc/self/ns/uts` outside the
unit; without it the ProtectHostname namespace check fails. Several refusals
are also made by the empty capability set; those say so.

One line per check, "ok: ..." or "FAIL: ...", then a summary. Exit 0 when
every check passes.
"""
import ctypes
import errno
import mmap
import os
import socket
import subprocess
import sys

LIBC = ctypes.CDLL(None, use_errno=True)
VHCI_ATTACH = '/sys/devices/platform/vhci_hcd.0/attach'
RUN_DIR = '/var/run/vhci_hcd'
bad = 0


def say(good, what):
    global bad
    print(('ok:   ' if good else 'FAIL: ') + what, flush=True)
    if not good:
        bad = 1


def attempt(fn):
    """None on success, else the errno name."""
    try:
        fn()
        return None
    except TimeoutError:
        return 'ETIMEDOUT'          # socket.timeout carries no errno
    except OSError as e:
        return errno.errorcode.get(e.errno, str(e.errno))


def libc_errno(rc):
    return None if rc != -1 else errno.errorcode.get(ctypes.get_errno(), str(ctypes.get_errno()))


def open_close(path, flags):
    os.close(os.open(path, flags))


def create_remove(path):
    """Create path (O_EXCL), and if that worked remove it again at once."""
    os.close(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600))
    os.unlink(path)


def expect(option, what, got, want):
    """want: None for allowed, or a set of errno names."""
    if want is None:
        say(got is None, f'{option}: {what} allowed' + (f' (got {got})' if got else ''))
    else:
        say(got in want, f'{option}: {what} refused, {got or "SUCCEEDED"}')


def status_field(name):
    with open('/proc/self/status') as f:
        for ln in f:
            if ln.startswith(name + ':'):
                return ln.split(':', 1)[1].strip()


def main():
    if len(sys.argv) > 1:
        box_ip = sys.argv[1]
    else:
        r = subprocess.run(['tailscale', 'ip', '-4'], capture_output=True, text=True, timeout=10,
                           stdin=subprocess.DEVNULL)
        box_ip = r.stdout.split()[0] if r.returncode == 0 and r.stdout.split() else None
        if not box_ip:
            print('no TAILNET_IP given and `tailscale ip -4` printed none', file=sys.stderr)
            return 2
    me = os.getpid()

    # --- identity and privileges
    say(os.getuid() == 0, f'runs as root (uid {os.getuid()})')
    for f in ('CapPrm', 'CapEff', 'CapBnd', 'CapAmb'):
        v = status_field(f)
        say(int(v, 16) == 0, f'CapabilityBoundingSet=, AmbientCapabilities=: {f} {v}')
    say(status_field('NoNewPrivs') == '1', f'NoNewPrivileges: NoNewPrivs {status_field("NoNewPrivs")}')
    say(status_field('Seccomp') == '2', f'SystemCallFilter: Seccomp mode {status_field("Seccomp")}')

    # --- what it must be able to do
    expect('ReadWritePaths', f'open {VHCI_ATTACH} for writing (closed, nothing written)',
           attempt(lambda: open_close(VHCI_ATTACH, os.O_WRONLY)), None)
    expect('read', '/sys/devices/platform/vhci_hcd.0/status',
           attempt(lambda: open('/sys/devices/platform/vhci_hcd.0/status').read()), None)
    probe = f'{RUN_DIR}/sandbox-probe.{me}'
    got = attempt(lambda: os.close(os.open(probe, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o666)))
    expect('RuntimeDirectory', f'create a file in {RUN_DIR}', got, None)
    if got is None:
        mode = os.stat(probe).st_mode & 0o7777
        say(mode == 0o600, f'UMask=0077: a file asked for 0666 is {mode:04o}')
        expect('RestrictSUIDSGID', 'chmod u+s', attempt(lambda: os.chmod(probe, 0o4700)), {'EPERM'})
        os.unlink(probe)
        say(not os.path.exists(probe), f'probe file removed from {RUN_DIR}')
    st = os.stat(RUN_DIR)
    say(st.st_uid == 0 and st.st_mode & 0o777 == 0o700,
        f'RuntimeDirectoryMode: {RUN_DIR} uid {st.st_uid} mode {st.st_mode & 0o777:04o}')
    for cmd in (['tailscale', 'ip', '-4'], ['tailscale', 'whois', '--json', box_ip]):
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=10, stdin=subprocess.DEVNULL)
        say(r.returncode == 0 and r.stdout.strip(),
            f'tailscaled socket: `{" ".join(cmd)}` exit {r.returncode}, {len(r.stdout)} bytes'
            + (f', stderr {r.stderr.strip()[:120]!r}' if r.returncode else ''))
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    expect('RestrictAddressFamilies', f'AF_INET bind {box_ip}:0', attempt(lambda: s.bind((box_ip, 0))), None)
    s.close()

    # --- what it must not
    expect('ProtectSystem=strict', 'create /etc/usbip-dialin-probe',
           attempt(lambda: create_remove('/etc/usbip-dialin-probe')),
           {'EROFS'})
    expect('ProtectSystem=strict', 'create /opt/usbip-dialin/probe',
           attempt(lambda: create_remove('/opt/usbip-dialin/probe')),
           {'EROFS'})
    expect('ProtectSystem=strict', 'create /run/usbip-dialin-probe',
           attempt(lambda: create_remove('/run/usbip-dialin-probe')),
           {'EROFS'})
    expect('ProtectHome', 'list /home', attempt(lambda: os.listdir('/home')), {'EACCES', 'ENOENT'})
    expect('ProtectHome', 'list /root', attempt(lambda: os.listdir('/root')), {'EACCES', 'ENOENT'})
    tmp = os.listdir('/tmp')
    say(not [n for n in tmp if not n.startswith('systemd-private')] and len(tmp) <= 1,
        f'PrivateTmp: /tmp holds {len(tmp)} entries')
    dev = sorted(os.listdir('/dev'))
    real = [n for n in dev if n.startswith(('sd', 'nvme', 'tty', 'kmsg', 'mem', 'port', 'hidraw', 'bus'))
            and n not in ('tty',)]
    say(not real, f'PrivateDevices: /dev has {len(dev)} entries, no real devices'
        + (f' (found {real[:6]})' if real else ''))
    expect('ProtectKernelTunables, ProcSubset=pid', 'open /proc/sys/vm/swappiness for writing (nothing written)',
           attempt(lambda: open_close('/proc/sys/vm/swappiness', os.O_WRONLY)), {'EROFS', 'EACCES', 'ENOENT'})
    expect('ProtectKernelTunables', 'open /sys/kernel/mm/transparent_hugepage/enabled for writing (nothing written)',
           attempt(lambda: open_close('/sys/kernel/mm/transparent_hugepage/enabled', os.O_WRONLY)),
           {'EROFS', 'EACCES'})
    expect('ProtectKernelTunables', 'open vhci_hcd.0/detach for writing (nothing written)',
           attempt(lambda: open_close('/sys/devices/platform/vhci_hcd.0/detach', os.O_WRONLY)),
           {'EROFS', 'EACCES'})
    expect('ProtectControlGroups', 'mkdir under /sys/fs/cgroup',
           attempt(lambda: os.mkdir(f'/sys/fs/cgroup/usbip-dialin-probe.{me}')), {'EROFS', 'EACCES'})
    nr_finit_module = 313                                   # x86_64
    expect('ProtectKernelModules', 'finit_module(-1) (an invalid fd: EBADF if it were allowed)',
           libc_errno(LIBC.syscall(nr_finit_module, -1, b'', 0)), {'EPERM'})
    expect('ProtectKernelModules', 'list /usr/lib/modules',
           attempt(lambda: os.listdir('/usr/lib/modules')), {'EACCES', 'ENOENT'})
    expect('ProtectKernelLogs', 'open /proc/kmsg',
           attempt(lambda: open_close('/proc/kmsg', os.O_RDONLY)), {'EACCES', 'ENOENT', 'EPERM'})
    expect('ProtectKernelLogs', 'syslog(SYSLOG_ACTION_SIZE_BUFFER)',
           libc_errno(LIBC.klogctl(10, None, 0)), {'EPERM'})
    ts = (ctypes.c_long * 2)(0, 0)
    LIBC.clock_gettime(0, ts)
    expect('ProtectClock', 'clock_settime(CLOCK_REALTIME, now) (no CAP_SYS_TIME refuses it too)',
           libc_errno(LIBC.clock_settime(0, ts)), {'EPERM'})
    uts = os.readlink('/proc/self/ns/uts')
    say(len(sys.argv) > 2 and uts != sys.argv[2],
        f'ProtectHostname: own UTS namespace {uts}, host {sys.argv[2] if len(sys.argv) > 2 else "not given"}')
    expect('ProtectHostname', 'sethostname(own name) (no CAP_SYS_ADMIN refuses it too)',
           attempt(lambda: socket.sethostname(socket.gethostname())), {'EPERM'})
    say(not os.path.exists('/proc/meminfo'), 'ProcSubset=pid: /proc/meminfo absent')
    for fam, name in ((socket.AF_INET6, 'AF_INET6'), (socket.AF_NETLINK, 'AF_NETLINK'),
                      (socket.AF_PACKET, 'AF_PACKET')):
        expect('RestrictAddressFamilies', f'socket({name})',
               attempt(lambda: socket.socket(fam, socket.SOCK_DGRAM if fam != socket.AF_PACKET else socket.SOCK_RAW).close()),
               {'EAFNOSUPPORT'})
    # IPAddressDeny drops in the cgroup's BPF programs: a dropped SYN is retransmitted, so a
    # denied connect times out rather than failing. Both ends are this probe's own listeners, so
    # without the filter both would connect at once.
    def connect_own(host):
        lst = socket.socket()
        try:
            lst.bind((host, 0))
            lst.listen(1)
            c = socket.socket()
            c.settimeout(2)
            try:
                c.connect(lst.getsockname())
            finally:
                c.close()
        finally:
            lst.close()
    expect('IPAddressDeny=any', 'connect to its own listener on 127.0.0.1',
           attempt(lambda: connect_own('127.0.0.1')), {'ETIMEDOUT', 'EPERM'})
    expect('IPAddressAllow=100.64.0.0/10', f'connect to its own listener on {box_ip}',
           attempt(lambda: connect_own(box_ip)), None)
    expect('RestrictNamespaces', 'unshare(CLONE_NEWUSER)', attempt(lambda: os.unshare(os.CLONE_NEWUSER)), {'EPERM'})
    expect('RestrictNamespaces', 'unshare(CLONE_NEWNET)', attempt(lambda: os.unshare(os.CLONE_NEWNET)), {'EPERM'})
    expect('RestrictRealtime', 'sched_setscheduler(SCHED_FIFO)',
           attempt(lambda: os.sched_setscheduler(0, os.SCHED_FIFO, os.sched_param(1))), {'EPERM'})
    expect('LockPersonality', 'personality(PER_LINUX32)', libc_errno(LIBC.personality(0x0008)), {'EPERM'})
    expect('MemoryDenyWriteExecute', 'mmap PROT_WRITE|PROT_EXEC',
           attempt(lambda: mmap.mmap(-1, 4096, prot=mmap.PROT_READ | mmap.PROT_WRITE | mmap.PROT_EXEC).close()),
           {'EPERM', 'EACCES'})
    expect('SystemCallFilter=~@privileged', 'setuid(0) as uid 0 (allowed by the kernel itself)',
           attempt(lambda: os.setuid(0)), {'EPERM'})
    expect('SystemCallFilter=~@resources', 'setpriority(+0)', attempt(lambda: os.setpriority(os.PRIO_PROCESS, 0, os.getpriority(os.PRIO_PROCESS, 0))), {'EPERM'})

    print(f'\nsandbox probe: {"all checks passed" if not bad else "checks FAILED"}', flush=True)
    return bad


if __name__ == '__main__':
    sys.exit(main())
