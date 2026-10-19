#!/usr/bin/env python3
"""Offline checks for usbip_dialin.py: no phone, no tailnet, no gadget.

    usbip_dialin_selftest.py --mock PATH [--mocks-dir DIR] [--user LOGIN]

PATH, or the environment variable USBIPDCPP_MOCK, is usbipdcpp's mock_cdc_acm
example. With neither, the selftest exits 2 and says how to build it. DIR
(default: PATH's directory) must also hold mock_keyboard, mock_ecm,
mock_rndis, mock_audio and mock_msc, for the interface-authorisation checks.

It needs the interface-authorisation design LIVE: usbip_authorize.py and
70-usbip-dialin.rules installed (byte for byte as in this directory) and every
vhci root hub reading interface_authorized_default 0 (README.md, Install, does
both). Without them the selftest exits 3 at once and
says which is missing (a service that refuses to import would otherwise fail
every attach check for the wrong reason).

Runs as an ordinary user and starts the service with `sudo -n` (the vhci
attach file is root-only). The vhci_hcd module must already be loaded: the
selftest never loads a module. The fixtures:

  - usbipdcpp's userspace mock_cdc_acm (`-b 1-1`): a software CDC ACM port
    that echoes every byte. Never a kernel gadget (usbip-vudc, g_serial): that
    loopback oopsed a test machine (CVE-2026-64331).
  - A Python dialer standing in for the phone's socat loop: connect to the
    service (source address chosen with bind, 127.0.0.x), then to the mock,
    splice both ways, redial 1 s after either side closes.
  - A fake whois command: 127.0.0.1 is an allowed user, .2 a tagged node, .3
    "peer not found" with exit 1, .4 another user, .5 "peer not found" with
    exit 0, .6 JSON with no UserProfile, .7 Tags a string; .1 (no Tags key, the
    real shape of an untagged node), .8 (Tags null) and .9 (Tags []) are allowed.
  - Fake phones (raw sockets) for the malformed-reply cases.

1. Attach: an allowed dialer gets a new vhci port in use on the hs hub and a
   ttyACM under vhci_hcd.0, udev says ID_MM_DEVICE_IGNORE=1, a 64 B, 4 KiB and
   1 MiB round trip through the tty comes back intact, `usbip port` shows the record.
2. The dialer killed: port free and tty gone; started again: attached again.
3. Refused peers (tagged, not found, other user, non-JSON, no UserProfile, Tags a string): logged
   with a reason, closed before a byte, nothing attached.
4. Garbage, silence, a bad version, a short list, an oversize list, an import
   the phone refuses, the exact request bytes: closed, the service runs on, and
   a good dialer then attaches.
5. No ModemManager line since the start.
6. A listen port that is busy is retried and bound within 4 s of freeing.
7. The default listen address (--ip-cmd) failing twice, then found.
8. A list older than --list-ttl is not imported from.
9. No --allow-user: the service refuses to start.
10. Interface authorisation, with the rule live and the hubs armed. Each mock is
    attached through the service, one at a time. mock_cdc_acm (check 1): both
    interfaces authorised and bound to cdc_acm. mock_keyboard, mock_ecm,
    mock_rndis and mock_audio: every interface the design denies reads
    authorized 0 with no driver bound, and no input, net or sound device
    appears under vhci_hcd. mock_msc: usb-storage (or uas) binds and a block
    device appears; it is never mounted and never opened, only udev's own probing
    reads it. For each: the helper's marker (/run/usbip-authorize/<device>.json,
    matched by busnum and devnum) shows it ran, its syslog record (read with
    `sudo -n journalctl`: the helper is root's) says the same, its live decisions
    equal `usbip_authorize.py --dry-run` against the box's real /lib/modules, and
    the service's `interfaces` line has helper "seen" and equals the sysfs state.
    The CDC data interface of ECM and RNDIS (class 0a, no driver of its own) is
    authorised by design and binds nothing.
11. Disarm: one vhci root hub set back to 1 (sudo -n tee): the service logs
    refuse_import and attaches nothing. Re-armed in a finally, by an `add` event on
    the root hubs (rule 1 of the udev rule does the write: that proves the rule the
    boot relies on; a direct write is the fallback, and the rule's failure a FAIL);
    then an attach works again. At the very end, in every run, both hubs are
    verified to read 0.
12. A second service told to look for markers where the helper never writes (what a
    service sees with the rule absent; the live rule is not touched) attaches the
    mock, logs the `interfaces` line with helper "not_seen" and an error line that
    says the rule is not live, and still reports the sysfs state.

Only the vhci ports this run attached are detached (`sudo -n usbip detach -p N`)
and only if the kernel has not already. Fixtures and the service are stopped by
PID, never by pattern. Nothing is mounted and no loaded module is touched. Exit 0
when every check passes, 2 for a missing mock, 3 when the design is not live.
"""
import argparse
import glob
import json
import os
import signal
import socket
import select
import struct
import subprocess
import sys
import tempfile
import time
import traceback
import tty
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
SERVICE = os.path.join(HERE, 'usbip_dialin.py')
MOCK_ENV = 'USBIPDCPP_MOCK'
MOCK_HINT = """\
no mock_cdc_acm: pass --mock PATH or set USBIPDCPP_MOCK.
Build it from usbipdcpp (https://github.com/yunsmall/usbipdcpp), which needs
cmake, libasio-dev, libspdlog-dev, libcxxopts-dev and pkgconf:
    cd usbipdcpp
    cmake -S . -B build -DUSBIPDCPP_USE_PKGCONF_ASIO=ON \\
      -DUSBIPDCPP_BUILD_LIBUSB_COMPONENTS=OFF -DUSBIPDCPP_BUILD_TESTS=OFF \\
      -DCMAKE_BUILD_TYPE=Release
    cmake --build build --target mock_cdc_acm -j8
The binary is build/mock_cdc_acm."""
ECHO_DEADLINE_S = 20
VHCI = '/sys/devices/platform/vhci_hcd.0'
HUB_GLOB = '/sys/devices/platform/vhci_hcd.*/usb*/interface_authorized_default'   # as the service's gate reads
HELPER = os.path.join(HERE, 'usbip_authorize.py')
MARKER_DIR = '/run/usbip-authorize'     # where the helper leaves its marker (world-readable)
INSTALLED = (   # (repo file, where the deploy puts it)
    (HELPER, '/opt/usbip-dialin/usbip_authorize.py'),
    (os.path.join(HERE, '70-usbip-dialin.rules'), '/etc/udev/rules.d/70-usbip-dialin.rules'),
)
MOCK_NAMES = ('mock_keyboard', 'mock_ecm', 'mock_rndis', 'mock_audio', 'mock_msc')
MOCKS_HINT = """\
build the interface-authorisation mocks next to mock_cdc_acm (same configuration):
    cmake --build usbipdcpp/build --target mock_keyboard mock_ecm mock_rndis mock_audio mock_msc -j4"""
ALLOW, DENY = 'allow', 'deny'
# What the design says of each mock's interfaces, by index: (decision, drivers that may be bound).
# An empty set means authorised or not, but no driver. The CDC data interface (class 0a) has
# no driver of its own (cdc_ether and rndis_host match the communications interface), so the
# helper authorises it and probes nothing; with the communications interface denied, nothing claims it.
EXPECT = {
    'mock_cdc_acm': [(ALLOW, {'cdc_acm'}), (ALLOW, {'cdc_acm'})],
    'mock_keyboard': [(DENY, set())],
    'mock_ecm': [(DENY, set()), (ALLOW, set())],
    'mock_rndis': [(DENY, set()), (ALLOW, set())],
    'mock_audio': [(DENY, set()), (DENY, set())],
    'mock_msc': [(ALLOW, {'usb-storage', 'uas'})],
}

VERSION = 0x0111
DEVLIST_REQ = struct.pack('>HHI', VERSION, 0x8005, 0)
fails = []


def ok(cond, what):
    print(('ok:   ' if cond else 'FAIL: ') + what, flush=True)
    if not cond:
        fails.append(what)
    return cond


def round_trip(fd, n):
    """Send n random bytes through the non-blocking tty fd and read them back.
    A deadline, not a blocking read: a stalled path prints BAD instead of
    hanging (a blocking read once sat in the kernel until the port was
    detached). Returns (intact, a line to print)."""
    msg = os.urandom(n)
    got = b''
    sent = 0
    t0 = time.time()
    while len(got) < n and time.time() - t0 < ECHO_DEADLINE_S:
        r, w, _ = select.select([fd], [fd] if sent < n else [], [], 0.5)
        if w:
            sent += os.write(fd, msg[sent:sent + 512])
        if r:
            got += os.read(fd, 65536)
    dt = max(time.time() - t0, 1e-6)
    good = got == msg
    state = 'ok' if good else f'BAD got {len(got)} sent {sent}'
    return good, f'{n} {state} {dt:.3f}s {n / dt / 1024:.0f} KiB/s'


def echo_check(dev, sizes):
    """round_trip for each size on dev, opened raw and non-blocking. True when all are intact."""
    fd = os.open(dev, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
    try:
        tty.setraw(fd)
        results = []
        for n in sizes:
            good, line = round_trip(fd, n)
            print('      ' + line, flush=True)
            results.append(good)
        return all(results)
    finally:
        os.close(fd)


def wait_for(fn, timeout, step=0.1):
    """fn() truthy within timeout: its value, else None."""
    end = time.monotonic() + timeout
    while True:
        v = fn()
        if v:
            return v
        if time.monotonic() >= end:
            return None
        time.sleep(step)


# ---------------------------------------------------------------- vhci
def vhci_rows():
    with open(VHCI + '/status') as f:
        rows = []
        for ln in f.read().splitlines()[1:]:
            p = ln.split()
            rows.append(dict(hub=p[0], port=int(p[1]), sta=int(p[2]), spd=int(p[3]),
                             lbusid=p[6] if len(p) > 6 else ''))
    return rows


def used_ports():
    return {r['port']: r for r in vhci_rows() if r['sta'] != 4}


def ttys():
    return {os.path.basename(p) for p in glob.glob(VHCI + '/usb*/*/*/tty/ttyACM*')}


# ---------------------------------------------------------------- interface authorisation
def read_text(path):
    try:
        with open(path) as f:
            return f.read().strip()
    except OSError:
        return None


def hub_values():
    """{interface_authorized_default path: its text, or None when unreadable} for every vhci root hub."""
    return {p: read_text(p) for p in sorted(glob.glob(HUB_GLOB))}


def preconditions():
    """What is missing for the design to be live, as printable lines (empty: live)."""
    bad = []
    hubs = hub_values()
    if not hubs:
        bad.append('no vhci root hub has interface_authorized_default (is vhci_hcd loaded?)')
    for path, val in hubs.items():
        if val != '0':
            bad.append(f'{path} reads {val!r}, not the armed 0')
    for repo, live in INSTALLED:
        try:
            same = open(repo, 'rb').read() == open(live, 'rb').read()
        except OSError as e:
            bad.append(f'{live}: {e.strerror or e}')
            continue
        if not same:
            bad.append(f'{live} differs from {repo}')
    return bad


def set_hub(path, value):
    """Write 0 or 1 to a root hub's interface_authorized_default (sudo -n tee: it is root-only)."""
    r = subprocess.run(['sudo', '-n', 'tee', path], input=str(value).encode(), stdout=subprocess.DEVNULL,
                       stderr=subprocess.PIPE)
    return r.returncode == 0


def rearm_by_rule(path):
    """Re-arm a root hub the way the box arms it: an `add` event on the vhci_hcd's root hubs
    (not on the devices below them), on which rule 1 of the udev rule writes 0. True when
    PATH reads 0 afterwards."""
    hcd = os.path.dirname(os.path.dirname(path))
    subprocess.run(['sudo', '-n', 'udevadm', 'trigger', '--action=add', '--settle', f'--parent-match={hcd}',
                    '--sysname-match=usb[0-9]*'], capture_output=True, timeout=60)
    return read_text(path) == '0'


def class_snapshot():
    """{class: {name: real sysfs path}} for the classes a driver we deny would add devices to, and block."""
    out = {}
    for c in ('input', 'net', 'sound', 'block'):
        out[c] = {n: os.path.realpath(os.path.join('/sys/class', c, n)) for n in sorted(os.listdir('/sys/class/' + c))} \
            if os.path.isdir('/sys/class/' + c) else {}
    return out


def on_vhci(path):
    return '/platform/vhci_hcd.' in path


def interface_state(idir):
    """The fields the service's `interfaces` line carries, read the way it reads them."""
    try:
        driver = os.path.basename(os.readlink(idir + '/driver'))
    except OSError:
        driver = None
    return dict(name=os.path.basename(idir), cls=read_text(idir + '/bInterfaceClass'),
                subclass=read_text(idir + '/bInterfaceSubClass'),
                protocol=read_text(idir + '/bInterfaceProtocol'),
                authorized=read_text(idir + '/authorized'), driver=driver)


def helper_marker(devdir):
    """The helper's marker (MARKER_DIR/<name>.json, the record it logged plus busnum and devnum) for the
    device now at DEVDIR, or None: missing, unreadable, or left by an earlier device of the same name
    (its busnum and devnum are not this device's)."""
    name = os.path.basename(devdir)
    try:
        ids = (int(read_text(devdir + '/busnum')), int(read_text(devdir + '/devnum')))
        with open(f'{MARKER_DIR}/{name}.json') as f:
            rec = json.load(f)
    except (OSError, TypeError, ValueError):
        return None
    ok_shape = isinstance(rec, dict) and rec.get('ev') == 'authorize' and rec.get('device') == name
    return rec if ok_shape and (rec.get('busnum'), rec.get('devnum')) == ids else None


def journal_record(mark_us, devname):
    """The helper's syslog record (identifier usbip-authorize) for a real run (not a --dry-run) on DEVNAME
    since MARK_US (epoch microseconds), or None. The helper runs as root, so its lines are in the system
    journal: `sudo -n journalctl`, not the user's own."""
    since = datetime.fromtimestamp(mark_us / 1e6 - 5).strftime('%Y-%m-%d %H:%M:%S')
    r = subprocess.run(['sudo', '-n', 'journalctl', '-t', 'usbip-authorize', '-o', 'json', '--no-pager', '-q',
                        '--since', since], capture_output=True, text=True)
    for ln in r.stdout.splitlines():
        try:
            j = json.loads(ln)
            msg = json.loads(j['MESSAGE'])
            ts = int(j['__REALTIME_TIMESTAMP'])
        except (ValueError, KeyError, TypeError):
            continue
        if ts >= mark_us and isinstance(msg, dict) and msg.get('ev') == 'authorize' \
                and msg.get('device') == devname and not msg.get('dry_run'):
            return msg
    return None


def dry_run(devdir):
    """The helper's --dry-run on a device directory, against the box's real /lib/modules. Its
    JSON record, or None."""
    r = subprocess.run([sys.executable, '-I', HELPER, devdir, '--dry-run'], capture_output=True, text=True,
                       timeout=30)
    for ln in reversed(r.stdout.splitlines()):
        try:
            return json.loads(ln)
        except ValueError:
            continue
    return None


# ---------------------------------------------------------------- the wire, built from the kernel doc's offsets
def op(code, status=0, version=VERSION):
    return struct.pack('>HHI', version, code, status)


def usb_device(busid, busnum=1, devnum=4, speed=2, vid=0x1209, pid=0x5740, nif=2):
    """Documentation/usb/usbip_protocol.rst: 312 bytes, offsets 0x00, 0x100, 0x120 ... 0x137."""
    b = bytearray(312)
    b[0:len(b'/sys/devices/fake')] = b'/sys/devices/fake'
    b[0x100:0x100 + len(busid)] = busid.encode()
    struct.pack_into('>III', b, 0x120, busnum, devnum, speed)
    struct.pack_into('>HHH', b, 0x12C, vid, pid, 0x0100)
    b[0x132:0x138] = bytes([2, 0, 0, 1, 1, nif])
    return bytes(b)


def devlist_reply(busids, nif=2):
    out = op(0x0005) + struct.pack('>I', len(busids))
    for bid in busids:
        out += usb_device(bid, nif=nif) + b'\x02\x02\x01\x00' * nif
    return out


def rx(s, n, timeout=5.0):
    """Exactly n bytes, fewer if the peer closes first."""
    got = b''
    end = time.monotonic() + timeout
    while len(got) < n and time.monotonic() < end:
        r, _, _ = select.select([s], [], [], 0.2)
        if r:
            c = s.recv(n - len(got))
            if not c:
                break
            got += c
    return got


def closed_within(s, timeout):
    """(True, seconds, extra bytes) once the service closes; (False, ...) if it does not."""
    t0 = time.monotonic()
    extra = b''
    while time.monotonic() - t0 < timeout:
        r, _, _ = select.select([s], [], [], 0.1)
        if r:
            try:
                c = s.recv(4096)
            except OSError:
                return True, time.monotonic() - t0, extra
            if not c:
                return True, time.monotonic() - t0, extra
            extra += c
    return False, time.monotonic() - t0, extra


def raw_client(port, src='127.0.0.1'):
    s = socket.socket()
    s.bind((src, 0))
    s.connect(('127.0.0.1', port))
    return s


# ---------------------------------------------------------------- fixtures
def free_port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


class Svc:
    def __init__(self, tmp, name, *extra):
        self.name = name
        self.logpath = os.path.join(tmp, f'{name}.log')
        self.extra = list(extra)
        self.proc = None
        self.pid = None

    def start(self):
        self.logf = open(self.logpath, 'w')
        self.proc = subprocess.Popen(['sudo', '-n', sys.executable, SERVICE] + self.extra,
                                     stdout=self.logf, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
        st = self.wait_ev(lambda e: e['ev'] == 'start', 10)
        self.pid = st['pid'] if st else None
        return st

    def events(self):
        out = []
        with open(self.logpath) as f:
            for ln in f:
                try:
                    out.append(json.loads(ln))
                except ValueError:
                    out.append({'ev': 'raw', 'line': ln.strip()})
        return out

    def wait_ev(self, pred, timeout, after=0):
        def look():
            for e in self.events()[after:]:
                if pred(e):
                    return e
        return wait_for(look, timeout)

    def count(self):
        return len(self.events())

    def alive(self):
        return self.proc is not None and self.proc.poll() is None

    def attached_ports(self):
        return [e['vhci_port'] for e in self.events() if e['ev'] == 'attach']

    def stop(self):
        if self.proc is None:
            return
        if self.pid and self.alive():
            subprocess.run(['sudo', '-n', 'kill', '-TERM', str(self.pid)], capture_output=True)
            try:
                self.proc.wait(5)
            except subprocess.TimeoutExpired:
                subprocess.run(['sudo', '-n', 'kill', '-KILL', str(self.pid)], capture_output=True)
        if self.alive():
            self.proc.kill()
        try:
            self.proc.wait(5)
        except subprocess.TimeoutExpired:
            pass
        self.logf.close()


def kill_pid(proc):
    if proc is not None and proc.poll() is None:
        proc.kill()
        try:
            proc.wait(5)
        except subprocess.TimeoutExpired:
            pass


def dialer_main(src, svc, mock_port):
    """The phone's socat loop: box first, then the app's port, splice, redial in 1 s.
    One thread per direction, so a full buffer one way cannot stall the other."""
    import threading
    host, port = svc.rsplit(':', 1)
    while True:
        a = b = None
        try:
            a = socket.socket()
            a.bind((src, 0))
            a.connect((host, int(port)))
            b = socket.create_connection(('127.0.0.1', int(mock_port)))
            for s in (a, b):
                s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            end = threading.Event()

            def pump(x, y):
                try:
                    while True:
                        data = x.recv(65536)
                        if not data:
                            break
                        y.sendall(data)
                except OSError:
                    pass
                end.set()
            for x, y in ((a, b), (b, a)):
                threading.Thread(target=pump, args=(x, y), daemon=True).start()
            end.wait()
        except OSError:
            pass
        finally:
            for s in (a, b):
                if s is not None:
                    s.close()
        time.sleep(1)


FAKE_WHOIS = '''#!{py}
import json, sys
ip = sys.argv[-1]
open({log!r}, 'a').write(ip + ' ' + ' '.join(sys.argv[1:-1]) + '\\n')
# Node keys as the real `tailscale whois --json` prints them (checked): an untagged node has no Tags key
def node(**extra):
    n = {{"ID": 1, "StableID": "nABC", "Name": "fake-" + ip + ".tailnet.example.", "ComputedName": "fake-" + ip,
          "ComputedNameWithHost": "fake-" + ip, "Addresses": [ip + "/32"], "AllowedIPs": [ip + "/32"],
          "Key": "nodekey:00", "DiscoKey": "discokey:00", "Endpoints": [ip + ":41641"], "HomeDERP": 9,
          "Hostinfo": {{"OS": "android"}}, "Created": "2026-01-01T00:00:00Z", "Cap": 130, "Online": True,
          "User": 1}}
    n.update(extra)
    return n
def who(n, login):
    out = {{"CapMap": {{}}, "Node": n}}
    if login is not None:
        out["UserProfile"] = {{"ID": 1, "LoginName": login, "DisplayName": "x"}}
    print(json.dumps(out))
if ip == "127.0.0.1": who(node(), {user!r})
elif ip == "127.0.0.2": who(node(Tags=["tag:webserver"]), "tagged-devices")
elif ip == "127.0.0.3": print("peer not found"); sys.exit(1)
elif ip == "127.0.0.4": who(node(), "someone-else@github")
elif ip == "127.0.0.5": print("peer not found")
elif ip == "127.0.0.6": who(node(), None)
elif ip == "127.0.0.7": who(node(Tags="tag:webserver"), {user!r})
elif ip == "127.0.0.8": who(node(Tags=None), {user!r})
elif ip == "127.0.0.9": who(node(Tags=[]), {user!r})
else: print("peer not found"); sys.exit(1)
'''

FAKE_IP = '''#!{py}
import os, sys
n = int(open({cnt!r}).read() or 0) if os.path.exists({cnt!r}) else 0
open({cnt!r}, 'w').write(str(n + 1))
if n < 2:
    sys.stderr.write("no address yet\\n"); sys.exit(1)
print("127.0.0.1")
'''


def write_exec(path, text):
    with open(path, 'w') as f:
        f.write(text)
    os.chmod(path, 0o755)


# ---------------------------------------------------------------- the checks
class Run:
    def __init__(self, a):
        self.a = a
        self.tmp = tempfile.mkdtemp(prefix='usbip-dialin-selftest.')
        self.svcs = []
        self.dialers = []
        self.mock = None
        self.mock_port = free_port()
        self.other_mocks = []           # the interface-authorisation mocks (check 10), one at a time
        self.my_ports = set()
        self.since = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
        self.whois_log = os.path.join(self.tmp, 'whois.log')
        self.whois = os.path.join(self.tmp, 'fake-whois')
        write_exec(self.whois, FAKE_WHOIS.format(py=sys.executable, log=self.whois_log, user=a.user))
        open(self.whois_log, 'w').close()

    def svc(self, name, *extra, listen=True):
        port = free_port()
        args = ['--allow-user', self.a.user, '--whois-cmd', f'{self.whois} --json']
        if listen:
            args += ['--listen', f'127.0.0.1:{port}']
        s = Svc(self.tmp, name, *args, *extra)
        s.port = port
        self.svcs.append(s)
        return s

    def dial(self, svc, src='127.0.0.1', mock_port=None):
        p = subprocess.Popen([sys.executable, os.path.abspath(__file__), '--dialer', src,
                              f'127.0.0.1:{svc.port}', str(mock_port or self.mock_port)],
                             stdin=subprocess.DEVNULL)
        self.dialers.append(p)
        return p

    def start_mock(self):
        self.mock = subprocess.Popen([self.a.mock, '-p', str(self.mock_port), '-b', '1-1'],
                                     stdout=open(os.path.join(self.tmp, 'mock.log'), 'w'),
                                     stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)

        def up():
            try:
                socket.create_connection(('127.0.0.1', self.mock_port), 1).close()
                return True
            except OSError:
                return False
        return wait_for(up, 10)

    def new_port(self, base, timeout=15):
        def look():
            d = set(used_ports()) - set(base)
            return sorted(d) if d else None
        return wait_for(look, timeout)

    def freed(self, port, timeout=5):
        return wait_for(lambda: port not in used_ports(), timeout)

    def cleanup(self):
        for d in self.dialers:
            kill_pid(d)
        for s in self.svcs:
            s.stop()
        kill_pid(self.mock)
        for m in self.other_mocks:
            kill_pid(m)
        ports = set(self.my_ports)
        for s in self.svcs:
            ports |= set(s.attached_ports())
        wait_for(lambda: not (ports & set(used_ports())), 5)
        for p in sorted(ports & set(used_ports())):        # the kernel has not detached it: ours, so ours to detach
            r = subprocess.run(['sudo', '-n', 'usbip', 'detach', '-p', str(p)], capture_output=True, text=True)
            print(f'cleanup: usbip detach -p {p}: exit {r.returncode}', flush=True)
        left = ports & set(used_ports())
        if left:
            print(f'cleanup: vhci ports still in use that this run attached: {sorted(left)}', flush=True)
            fails.append('cleanup left a port attached')
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    # -------- 1
    def check1(self, S):
        print('== 1. attach, echo, record', flush=True)
        base, tbase = set(used_ports()), ttys()
        ctx = self.begin(S)
        self.dial(S)
        new = self.new_port(base)
        ok(new is not None and len(new) == 1, f'within 15 s a new vhci port is in use: {new}')
        if not new:
            return
        port = new[0]
        self.my_ports.add(port)
        row = used_ports()[port]
        ok(row['hub'] == 'hs' and row['spd'] < 5, f'the port is on the hs hub for a speed-{row["spd"]} device: {row}')
        tty = wait_for(lambda: sorted(ttys() - tbase), 15)
        ok(tty is not None and len(tty) == 1, f'a ttyACM appears under vhci_hcd.0: {tty}')
        if not tty:
            return
        dev = '/dev/' + tty[0]
        ok(bool(wait_for(lambda: os.path.exists(dev), 10)), f'{dev} exists')

        def mm():
            r = subprocess.run(['udevadm', 'info', '-q', 'property', '-n', dev], capture_output=True, text=True)
            return 'ID_MM_DEVICE_IGNORE=1' in r.stdout.split()
        ok(bool(wait_for(mm, 10)), f'udevadm info {dev}: ID_MM_DEVICE_IGNORE=1')
        ok(echo_check(dev, (64, 4096, 1048576)), f'round trip of 64, 4096 and 1048576 bytes through {dev} intact')
        r = subprocess.run(['sudo', '-n', 'usbip', 'port'], capture_output=True, text=True)
        txt = r.stdout
        ok(f'Port {port:02d}' in txt and 'usbip://127.0.0.1:' in txt and '/1-1' in txt,
           f'`usbip port` shows Port {port:02d} at usbip://127.0.0.1:<port>/1-1')
        rec = subprocess.run(['sudo', '-n', 'cat', f'/var/run/vhci_hcd/port{port}'], capture_output=True, text=True)
        f = rec.stdout.split()
        ok(len(f) == 3 and f[0] == '127.0.0.1' and f[2] == '1-1' and rec.stdout.endswith('\n'),
           f'record /var/run/vhci_hcd/port{port} is "host port busid\\n": {rec.stdout!r}')
        self.verify_attach(S, 'mock_cdc_acm', port, ctx)
        self.port1, self.tty1 = port, tty[0]

    # -------- 2
    def check2(self, S):
        print('== 2. killed dialer detaches, redial attaches', flush=True)
        if not hasattr(self, 'port1'):
            ok(False, 'check 1 attached nothing: 2 not run')
            return
        kill_pid(self.dialers[-1])
        ok(bool(self.freed(self.port1)), f'dialer killed: vhci port {self.port1} free within 5 s')
        ok(bool(wait_for(lambda: self.tty1 not in ttys(), 5)), f'{self.tty1} gone within 5 s')
        base = set(used_ports())
        self.dial(S)
        new = self.new_port(base)
        ok(new is not None and len(new) == 1, f'dialer started again: attached again on {new}')
        if new:
            self.my_ports.add(new[0])
            kill_pid(self.dialers[-1])
            ok(bool(self.freed(new[0])), f'killed again: vhci port {new[0]} free (left clean)')

    # -------- 3
    def check3(self, S):
        print('== 3. refused peers', flush=True)
        base = set(used_ports())
        n0 = S.count()
        cases = [('127.0.0.2', 'tagged'), ('127.0.0.3', 'exit 1'), ('127.0.0.4', 'not in --allow-user')]
        ds = [self.dial(S, src) for src, _ in cases]
        for src, why in cases:
            e = S.wait_ev(lambda e, src=src: e['ev'] == 'refuse' and e['peer'] == src, 8, n0)
            ok(e is not None and why in e['reason'], f'dialer from {src} refused in the log: {e and e["reason"]!r}')
        for d in ds:
            kill_pid(d)
        extra = [('127.0.0.5', 'not JSON'), ('127.0.0.6', 'UserProfile object'),
                 ('127.0.0.7', 'not null or a list')]
        for src, why in cases + extra:
            s = raw_client(S.port, src)
            n1 = S.count()
            closed, dt, got = closed_within(s, 5)
            s.close()
            ok(closed and got == b'', f'raw client from {src}: closed after {dt:.2f} s with {len(got)} bytes sent to it')
            e = S.wait_ev(lambda e, src=src: e['ev'] == 'refuse' and e['peer'] == src, 3, n0)
            ok(e is not None and why in e['reason'], f'  logged for {src}: {e and e["reason"]!r}')
        asked = open(self.whois_log).read().split('\n')
        ok(all(any(l.startswith(ip + ' --json') for l in asked) for ip in
               ('127.0.0.2', '127.0.0.3', '127.0.0.4', '127.0.0.5', '127.0.0.6', '127.0.0.7')),
           'whois ran with the peer IP last and its own arguments first (--json)')
        ok(not [e for e in S.events()[n0:] if e['ev'] in ('attach', 'import', 'list')],
           'no list, import or attach for any refused peer')
        for src, what in (('127.0.0.8', 'Tags null'), ('127.0.0.9', 'Tags []')):
            s = raw_client(S.port, src)
            ok(rx(s, 8) == DEVLIST_REQ, f'an untagged node with {what} is allowed: asked OP_REQ_DEVLIST')
            s.close()
        ok(set(used_ports()) == base, 'no new vhci port')

    # -------- 4
    def check4(self, S):
        print('== 4. malformed peers, then a good dialer', flush=True)

        def run_case(label, act, why):
            n0 = S.count()
            s = raw_client(S.port)
            try:
                act(s)
                closed, dt, got = closed_within(s, 6)
            finally:
                s.close()
            ok(closed, f'{label}: closed by the service after {dt:.2f} s')
            e = S.wait_ev(lambda e: e['ev'] == 'error' and why in e['msg'], 3, n0)
            ok(e is not None, f'  logged as error containing {why!r}: {e and e["msg"]!r}')
            ok(S.alive(), '  service still running')
            return dt

        def first(s):
            ok(rx(s, 8) == DEVLIST_REQ, '  the service asked OP_REQ_DEVLIST: 01 11 80 05 00 00 00 00')

        def garbage(s):
            first(s)
            s.sendall(b'GARBAGE!' * 8)
        run_case('garbage after the request', garbage, 'protocol version')

        def silent(s):
            first(s)
        dt = run_case('connects and sends nothing (--timeout 2)', silent, 'timed out')
        ok(1.5 < dt < 4.0, f'  the timeout was about 2 s: {dt:.2f}')

        def never_read(s):
            pass
        run_case('connects, does not even read, sends nothing', never_read, 'timed out')

        def badver(s):
            first(s)
            s.sendall(op(0x0005, version=0x0222) + struct.pack('>I', 0))
        run_case('a reply of another protocol version', badver, 'protocol version')

        def wrongcode(s):
            first(s)
            s.sendall(op(0x0003) + struct.pack('>I', 0))
        run_case('a reply with the wrong code', wrongcode, 'reply code')

        def short(s):
            first(s)
            s.sendall(devlist_reply(['9-9'])[:100])
        run_case('a device list cut off after 100 bytes', short, 'timed out')

        def huge(s):
            first(s)
            s.sendall(op(0x0005) + struct.pack('>I', 100000))
        run_case('a list claiming 100000 devices', huge, 'claims')

        def failed(s):
            first(s)
            s.sendall(op(0x0005, status=1))
        run_case('a device list with status 1', failed, 'status')

        # the exact import request, and an import the phone refuses
        n0 = S.count()
        s = raw_client(S.port)
        first(s)
        s.sendall(devlist_reply(['9-9']))
        closed, dt, _ = closed_within(s, 4)
        s.close()
        e = S.wait_ev(lambda e: e['ev'] == 'list', 3, n0)
        ok(closed and e is not None and e['count'] == 1 and e['devices'] == ['1209:5740 9-9'],
           f'a good list: closed, logged with count and VID:PID: {e and (e["count"], e["devices"])}')
        s = raw_client(S.port)
        req = rx(s, 40)
        want = struct.pack('>HHI', VERSION, 0x8003, 0) + b'9-9'.ljust(32, b'\0')
        ok(req == want, f'next connection: OP_REQ_IMPORT for 9-9, 40 bytes exact: {req.hex()}')
        s.sendall(op(0x0003, status=1))
        closed, dt, _ = closed_within(s, 4)
        s.close()
        e = S.wait_ev(lambda e: e['ev'] == 'error' and 'refused by the peer' in e['msg'], 3, n0)
        ok(closed and e is not None, f'an import with status 1: closed, logged: {e and e["msg"]!r}')
        s = raw_client(S.port)
        ok(rx(s, 8) == DEVLIST_REQ, 'after a failed import the list is gone: the next connection asks for the list')
        s.close()
        ok(not [e for e in S.events()[n0:] if e['ev'] == 'attach'], 'no attach from any of the fake phones')

        # an import reply for another bus id
        s = raw_client(S.port)
        rx(s, 8)
        s.sendall(devlist_reply(['9-9']))
        closed_within(s, 4)
        s.close()
        s = raw_client(S.port)
        rx(s, 40)
        n1 = S.count()
        s.sendall(op(0x0003) + usb_device('8-8'))
        closed, dt, _ = closed_within(s, 4)
        s.close()
        e = S.wait_ev(lambda e: e['ev'] == 'error' and 'import reply is for' in e['msg'], 3, n1 - 0)
        ok(closed and e is not None, f'an import reply for another bus id: closed, logged: {e and e["msg"]!r}')
        ok(not [e for e in S.events()[n0:] if e['ev'] == 'attach'], 'and nothing attached')

        base = set(used_ports())
        ok(S.alive(), 'service still running before the good dialer')
        self.dial(S)
        new = self.new_port(base)
        ok(new is not None and len(new) == 1, f'a good dialer afterwards attaches: {new}')
        if new:
            self.my_ports.add(new[0])
            kill_pid(self.dialers[-1])
            ok(bool(self.freed(new[0])), f'killed: vhci port {new[0]} free (left clean)')

    # -------- 10 (and the interface part of 1)
    def begin(self, S):
        """What to remember before an attach, for verify_attach."""
        return dict(n0=S.count(), mark_us=int(time.time() * 1e6), snap=class_snapshot())

    def device_dir(self, port):
        """The attached device's directory under the vhci root hub, from the port's status row."""
        row = used_ports().get(port)
        if not row or row['lbusid'] in ('', '0-0'):
            return None
        dirs = glob.glob(f'{VHCI}/usb*/{row["lbusid"]}')
        return dirs[0] if dirs else None

    def verify_attach(self, S, name, port, ctx):
        """The authorisation checks for one attached mock. PORT is the vhci port this run attached."""
        expect = EXPECT[name]
        print(f'   -- {name}: interfaces, the helper, the service line', flush=True)
        dev = wait_for(lambda: self.device_dir(port), 15)
        if not ok(bool(dev), f'{name}: vhci port {port} names a device directory under a root hub'):
            return
        devname = os.path.basename(dev)
        live = wait_for(lambda: helper_marker(dev), 15)
        if not ok(live is not None, f'{name}: the helper ran for {devname} (its marker {MARKER_DIR}/{devname}.json, '
                                    'for this busnum and devnum) within 15 s; if not, the udev rule is not live '
                                    '(udevadm control --reload, then re-attach)'):
            return
        jr = wait_for(lambda: journal_record(ctx['mark_us'], devname), 10)
        ok(jr is not None and jr.get('interfaces') == live.get('interfaces'),
           f'{name}: the helper\'s syslog record (sudo -n journalctl -t usbip-authorize) says what its marker says')
        line = S.wait_ev(lambda e: e['ev'] == 'interfaces', 15, ctx['n0'])
        ok(line is not None, f'{name}: the service logged an `interfaces` line')
        if line is not None:
            ok(line.get('helper') == 'seen', f'{name}: the `interfaces` line has helper "seen": {line.get("helper")!r}')
        time.sleep(1.5)         # a driver that would bind, or a device that would register, has had its time
        names = sorted(n for n in os.listdir(dev) if n.startswith(devname + ':'))
        ifs = [interface_state(os.path.join(dev, n)) for n in names]
        ok(len(ifs) == len(expect), f'{name}: {len(expect)} interfaces: {[(i["name"], i["cls"], i["subclass"], i["protocol"]) for i in ifs]}')
        for i, st in enumerate(ifs[:len(expect)]):
            decision, drivers = expect[i]
            want_auth = '1' if decision == ALLOW else '0'
            bound = st['driver']
            driver_ok = (bound in drivers) if drivers else bound is None
            if decision == DENY:
                ok(st['authorized'] == '0' and bound is None,
                   f'{name} {st["name"]} ({st["cls"]}/{st["subclass"]}/{st["protocol"]}): denied means authorized 0 and no driver; '
                   f'read authorized {st["authorized"]}, driver {bound}')
            else:
                ok(st['authorized'] == want_auth and driver_ok,
                   f'{name} {st["name"]} ({st["cls"]}/{st["subclass"]}/{st["protocol"]}): allowed means authorized 1 and '
                   f'{"driver " + "/".join(sorted(drivers)) if drivers else "no driver of its own"}; '
                   f'read authorized {st["authorized"]}, driver {bound}')
        # the helper's live decisions against --dry-run on the same device, and against the design
        dr = dry_run(dev)
        keys = ('if', 'cls', 'modalias', 'candidates', 'decision', 'why')
        pick = lambda rec: [tuple(x.get(k) for k in keys) for x in (rec or {}).get('interfaces', [])]
        ok(dr is not None and dr.get('modules_error') is None and pick(dr) == pick(live) and bool(pick(live)),
           f'{name}: --dry-run on {devname} (real /lib/modules) gives the helper\'s live decisions: '
           f'{[(x["if"][-3:], x["decision"]) for x in live["interfaces"]]}')
        ok([x['decision'] for x in live['interfaces']] == [d for d, _ in expect],
           f'{name}: those decisions are the design\'s: {[d for d, _ in expect]}')
        ok(not live.get('auth_errors') and live.get('modules_error') is None,
           f'{name}: the helper reports no authorize error and readable module tables: {live.get("auth_errors")} {live.get("modules_error")}')
        # the service's own line
        if line is not None:
            got = {x['name']: x for x in line['interfaces']}
            same = got == {i['name']: i for i in ifs}
            ok(same, f'{name}: the service `interfaces` line equals the sysfs state'
                     + ('' if same else '; line: %s' % json.dumps(line['interfaces'])))
        # no device of a class we deny appears; the udev-probed block device of an allowed one does
        snap = class_snapshot()
        for c in ('input', 'net', 'sound'):
            new = {n: p for n, p in snap[c].items() if n not in ctx['snap'][c]}
            here = sorted(n for n, p in new.items() if on_vhci(p))
            ok(not here, f'{name}: no new /sys/class/{c} device under vhci_hcd: {here or "none"}')
            if set(new) - set(here):
                print(f'      note: unrelated new /sys/class/{c}: {sorted(set(new) - set(here))}', flush=True)
        newblk = {n: p for n, p in snap['block'].items() if n not in ctx['snap']['block'] and on_vhci(p)}
        if name == 'mock_msc':
            newblk = wait_for(lambda: {n: p for n, p in class_snapshot()['block'].items()
                                       if n not in ctx['snap']['block'] and on_vhci(p)}, 30) or {}
            ok(bool(newblk), f'{name}: a block device appears under vhci_hcd within 30 s: {sorted(newblk) or "none"}')
            held = [l.split()[0] for l in (read_text('/proc/mounts') or '').splitlines()
                    if any(l.split()[0].startswith('/dev/' + b) for b in newblk)]
            ok(not held, f'{name}: nothing mounted it (read /proc/mounts only): {held or "no mount of " + ",".join(sorted(newblk))}')
        else:
            ok(not newblk, f'{name}: no block device under vhci_hcd: {sorted(newblk) or "none"}')

    def check10(self, S):
        print('== 10. interface authorisation: each mock through the service', flush=True)
        img = os.path.join(self.tmp, 'mock_msc.img')
        with open(img, 'wb') as f:
            f.truncate(8 << 20)         # a zero-filled image: blkid finds nothing in it
        for name in MOCK_NAMES:
            print(f'-- {name}', flush=True)
            port = free_port()
            extra = ['-i', img] if name == 'mock_msc' else []
            m = subprocess.Popen([os.path.join(self.a.mocks_dir, name), '-p', str(port), '-b', '1-1'] + extra,
                                 stdout=open(os.path.join(self.tmp, name + '.log'), 'w'),
                                 stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
            self.other_mocks.append(m)
            try:
                def up():
                    try:
                        socket.create_connection(('127.0.0.1', port), 1).close()
                        return True
                    except OSError:
                        return False
                if not ok(bool(wait_for(up, 10)), f'{name} up on 127.0.0.1:{port} (pid {m.pid})'):
                    continue
                ctx = self.begin(S)
                d = self.dial(S, mock_port=port)
                e = S.wait_ev(lambda e: e['ev'] == 'attach', 20, ctx['n0'])
                if not ok(e is not None, f'{name}: attached through the service within 20 s: {e and e["vhci_port"]}'):
                    kill_pid(d)
                    continue
                vp = e['vhci_port']
                self.my_ports.add(vp)
                try:
                    self.verify_attach(S, name, vp, ctx)
                finally:
                    kill_pid(d)
                ok(bool(self.freed(vp, 8)), f'{name}: dialer killed: vhci port {vp} free (left clean)')
            finally:
                kill_pid(m)             # SIGKILL, by PID
                if m in self.other_mocks:
                    self.other_mocks.remove(m)

    # -------- 12
    def check12(self, S):
        print('== 12. no helper marker: the service says helper "not_seen"', flush=True)
        # The live rule stays as it is. A second service looks for markers in a directory the helper never
        # writes, which is what a service sees when the rule is absent; its sandbox, its wait and its log
        # line are the real ones.
        S2 = self.svc('nomarker', '--timeout', '2', '--marker-dir', os.path.join(self.tmp, 'no-markers-here'))
        S2.start()
        if not ok(bool(S2.wait_ev(lambda e: e['ev'] == 'listening', 10)), 'the second service is listening'):
            return
        d = self.dial(S2)
        e = S2.wait_ev(lambda e: e['ev'] == 'attach', 20)
        if not ok(e is not None, f'it attached the mock: {e and e["vhci_port"]}'):
            kill_pid(d)
            return
        vp = e['vhci_port']
        self.my_ports.add(vp)
        line = S2.wait_ev(lambda e: e['ev'] == 'interfaces', 20)
        kill_pid(d)
        ok(line is not None and line.get('helper') == 'not_seen',
           f'the `interfaces` line has helper "not_seen": {line and line.get("helper")!r}')
        ok(S2.wait_ev(lambda e: e['ev'] == 'error' and 'rule is not live' in e['msg'], 3) is not None,
           'and an error line says the udev rule is not live')
        ok(bool(line) and len(line['interfaces']) == 2 and all(i['authorized'] == '1' and i['driver'] == 'cdc_acm'
                                                               for i in line['interfaces']),
           'the sysfs state is still logged (the real rule did run: both interfaces authorised, cdc_acm)')
        ok(bool(self.freed(vp, 8)), f'dialer killed: vhci port {vp} free (left clean)')

    # -------- 11
    def check11(self, S):
        print('== 11. a disarmed root hub: refuse_import, nothing attached', flush=True)
        hubs = sorted(hub_values())
        if not ok(bool(hubs) and all(v == '0' for v in hub_values().values()), f'every root hub reads 0 before: {hub_values()}'):
            return
        victim = hubs[-1]       # the last: the attach goes through the first, so the gate must look at every hub
        d = None
        try:
            ok(set_hub(victim, 1) and read_text(victim) == '1', f'{victim} set to 1 (sudo -n tee)')
            base, n0 = set(used_ports()), S.count()
            d = self.dial(S)
            e = S.wait_ev(lambda e: e['ev'] == 'refuse_import', 15, n0)
            ok(e is not None and victim in e.get('reason', ''), f'the service logged refuse_import naming {victim}: {e and e.get("reason")!r}')
            time.sleep(2.5)     # more dial cycles
            kill_pid(d)
            ev = S.events()[n0:]
            ok(not [x for x in ev if x['ev'] in ('import', 'attach')], 'no import and no attach was logged')
            ok(not (set(used_ports()) - base), 'no new vhci port')
        finally:
            if d is not None:
                kill_pid(d)
            try:
                by_rule = rearm_by_rule(victim)
            except Exception as ex:             # a hung trigger must not skip the direct write below
                print(f'      rearm_by_rule raised {type(ex).__name__}: {ex}', flush=True)
                by_rule = False
            if not by_rule:
                set_hub(victim, 0)      # the hub is armed in any case; the rule's failure is reported below
            ok(by_rule, 'rule 1 re-armed it on an add event (udevadm trigger on the root hubs)')
            ok(read_text(victim) == '0', f're-armed: {victim} reads {read_text(victim)!r}')
        base, n0 = set(used_ports()), S.count()
        d = self.dial(S)
        e = S.wait_ev(lambda e: e['ev'] == 'attach', 20, n0)
        ok(e is not None, f'armed again: the same dialer attaches: {e and e["vhci_port"]}')
        if e:
            self.my_ports.add(e['vhci_port'])
        kill_pid(d)
        if e:
            ok(bool(self.freed(e['vhci_port'], 8)), f'killed: vhci port {e["vhci_port"]} free (left clean)')

    def ensure_armed(self):
        """The end of every run: every root hub reads 0, writing 0 once to any that does not."""
        print('== end: root hubs armed', flush=True)
        for path, val in hub_values().items():
            if val != '0':
                set_hub(path, 0)
        now = hub_values()
        ok(bool(now) and all(v == '0' for v in now.values()), f'every root hub reads 0 at the end: {now}')

    # -------- 6
    def check6(self):
        print('== 6. bind retry', flush=True)
        holder = socket.socket()
        holder.bind(('127.0.0.1', 0))
        holder.listen(1)
        port = holder.getsockname()[1]
        S = Svc(self.tmp, 'retry', '--allow-user', self.a.user, '--whois-cmd', self.whois,
                '--listen', f'127.0.0.1:{port}')
        S.port = port
        self.svcs.append(S)
        S.start()
        e = S.wait_ev(lambda e: e['ev'] == 'bind_retry', 8)
        ok(e is not None and S.alive(), f'port busy: the service retries and runs on: {e and e["msg"]!r}')
        time.sleep(2.5)
        ok(S.alive() and not [e for e in S.events() if e['ev'] == 'listening'], 'still not bound while the port is held')
        holder.close()
        t0 = time.monotonic()
        e = S.wait_ev(lambda e: e['ev'] == 'listening', 6)
        dt = time.monotonic() - t0
        ok(e is not None and dt <= 4.0, f'bound {dt:.2f} s after the port freed (limit 4 s)')
        if e:
            s = raw_client(port)
            ok(rx(s, 8) == DEVLIST_REQ, 'and it serves: an allowed peer is asked OP_REQ_DEVLIST')
            s.close()

    # -------- 7
    def check7(self):
        print('== 7. default listen address from --ip-cmd', flush=True)
        cnt = os.path.join(self.tmp, 'ip.count')
        ipcmd = os.path.join(self.tmp, 'fake-ip')
        write_exec(ipcmd, FAKE_IP.format(py=sys.executable, cnt=cnt))
        port = free_port()
        S = Svc(self.tmp, 'ipcmd', '--allow-user', self.a.user, '--whois-cmd', self.whois,
                '--ip-cmd', f'{ipcmd} -4', '--port', str(port))
        S.port = port
        self.svcs.append(S)
        S.start()
        e = S.wait_ev(lambda e: e['ev'] == 'bind_retry', 8)
        ok(e is not None and 'no address' in e['msg'], f'the address command failing is retried: {e and e["msg"]!r}')
        e = S.wait_ev(lambda e: e['ev'] == 'listening', 10)
        ok(e is not None and e['addr'] == '127.0.0.1' and e['port'] == port,
           f'then it listens on the address the command printed: {e and (e["addr"], e["port"])}')
        if e:
            n0 = S.count()
            s = raw_client(port, '127.0.0.2')
            closed, dt, got = closed_within(s, 4)
            s.close()
            r = S.wait_ev(lambda e: e['ev'] == 'refuse', 3, n0)
            ok(closed and got == b'' and r is not None, 'and it checks peers there: a tagged node is refused')

    # -------- 8
    def check8(self):
        print('== 8. list-ttl', flush=True)
        S = self.svc('ttl', '--timeout', '2', '--list-ttl', '2')
        S.start()
        S.wait_ev(lambda e: e['ev'] == 'listening', 8)
        for wait, expect in ((0.0, 'import'), (3.2, 'list')):
            s = raw_client(S.port)
            rx(s, 8)
            s.sendall(devlist_reply(['9-9']))
            closed_within(s, 3)
            s.close()
            time.sleep(wait)
            s = raw_client(S.port)
            got = rx(s, 40, 2)
            s.close()
            if expect == 'import':
                ok(len(got) == 40 and got[2:4] == b'\x80\x03', f'a list {wait} s old: OP_REQ_IMPORT is sent')
            else:
                ok(got == DEVLIST_REQ, f'a list {wait} s old (ttl 2 s): OP_REQ_DEVLIST again, not an import')

    # -------- 9
    def check9(self):
        print('== 9. no --allow-user', flush=True)
        r = subprocess.run([sys.executable, SERVICE, '--listen', '127.0.0.1:1'], capture_output=True, text=True,
                           timeout=10)
        ok(r.returncode == 2 and '--allow-user' in r.stderr, f'exits {r.returncode} naming --allow-user')

    # -------- 5
    def check5(self):
        print('== 5. ModemManager', flush=True)
        r = subprocess.run(['journalctl', '-u', 'ModemManager', '--since', self.since, '--no-pager', '-q'],
                           capture_output=True, text=True)
        lines = [l for l in r.stdout.splitlines() if l.strip()]
        ok(r.returncode == 0 and not lines,
           f'journalctl -u ModemManager --since "{self.since}": {len(lines)} lines (exit {r.returncode})')
        for l in lines[:5]:
            print('      ' + l)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    ap.add_argument('--mock', default=os.environ.get(MOCK_ENV), help=f'mock_cdc_acm (default: ${MOCK_ENV})')
    ap.add_argument('--mocks-dir', default=None, help='where mock_keyboard, mock_ecm, mock_rndis, mock_audio and '
                    'mock_msc are built (default: the directory of --mock)')
    ap.add_argument('--user', default='selftest@example', help='the LoginName the fake whois allows')
    ap.add_argument('--dialer', nargs=3, metavar=('SRC', 'SERVICE', 'MOCK_PORT'), help=argparse.SUPPRESS)
    a = ap.parse_args()
    if a.dialer:
        dialer_main(*a.dialer)
        return 0
    if not a.mock:
        print(MOCK_HINT, file=sys.stderr)
        return 2
    if not os.access(a.mock, os.X_OK):
        print(f'{a.mock} is not an executable file.\n{MOCK_HINT}', file=sys.stderr)
        return 2
    a.mocks_dir = a.mocks_dir or os.path.dirname(os.path.abspath(a.mock))
    missing = [n for n in MOCK_NAMES if not os.access(os.path.join(a.mocks_dir, n), os.X_OK)]
    if missing:
        print(f'not executable in {a.mocks_dir}: {", ".join(missing)}\n{MOCKS_HINT}', file=sys.stderr)
        return 2
    bad = preconditions()
    if bad:
        print('FAIL: the interface-authorisation design is not live; nothing was attached:')
        for b in bad:
            print('  ' + b)
        print('  see README.md, Install: install the helper and the rule, reload udev and arm the hubs')
        return 3
    for sig in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(sig, lambda s, f: sys.exit(130))
    t0 = time.time()
    R = Run(a)
    try:
        ok(bool(R.start_mock()), f'mock_cdc_acm up on 127.0.0.1:{R.mock_port}')
        S = R.svc('main', '--timeout', '2')
        st = S.start()
        ok(st is not None and bool(S.wait_ev(lambda e: e['ev'] == 'listening', 10)),
           f'service up on 127.0.0.1:{S.port} as pid {S.pid} (sudo -n)')
        for name in ('check1', 'check2', 'check3', 'check4', 'check10', 'check12', 'check11'):
            try:
                getattr(R, name)(S)
            except Exception:
                traceback.print_exc()
                ok(False, f'{name} raised')
        for name in ('check6', 'check7', 'check8', 'check9'):
            try:
                getattr(R, name)()
            except Exception:
                traceback.print_exc()
                ok(False, f'{name} raised')
        R.check5()
        print('== service log, main instance: events', flush=True)
        ev = {}
        for e in S.events():
            ev[e['ev']] = ev.get(e['ev'], 0) + 1
        print('   ' + ', '.join(f'{k} {v}' for k, v in sorted(ev.items())), flush=True)
    except BaseException:
        traceback.print_exc()
        fails.append('aborted')
        raise
    finally:
        try:
            R.cleanup()
        finally:
            R.ensure_armed()
        print(f'\n{len(fails)} failed' if fails else f'\nall checks passed ({time.time() - t0:.0f} s)')
        for f in fails:
            print('  FAIL: ' + f)
    return 1 if fails else 0


if __name__ == '__main__':
    sys.exit(main())
