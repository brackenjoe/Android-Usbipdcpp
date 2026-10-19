#!/usr/bin/env python3
"""usbip-dialin: accept a phone's USB/IP connection and attach what it offers.

USB/IP normally has the client (this host) dial the server (the phone). Here the
phone dials the host, and this service plays the USB/IP *client* on the accepted
socket. The kernel does not care which end opened the connection: the vhci
`attach` file takes a descriptor of any established TCP socket (Linux v6.8,
drivers/usb/usbip/vhci_sysfs.c, attach_store). Standard library only.

    usbip_dialin.py --allow-user LOGIN [--allow-user LOGIN ...]
                    [--listen ADDR:PORT | --port PORT] [--timeout 10]
                    [--whois-cmd 'tailscale whois --json'] [--ip-cmd 'tailscale ip -4']
                    [--list-ttl 30] [--allow-node STABLEID ...] [--psk-file PATH]

The phone side is a redial loop, for example Termux's socat:

    while true; do socat TCP:<host tailnet ip>:3241 TCP:127.0.0.1:3240; sleep 1; done

A usbipdcpp session answers one OP_REQ_DEVLIST and closes, so it takes two
connections per device. For each accepted connection from an allowed peer:

  - If the host holds a device list for that peer from under --list-ttl seconds
    ago with a device not already attached: OP_REQ_IMPORT for that bus id, read
    OP_REP_IMPORT, and on status 0 write "<port> <sockfd> <devid> <speed>" to
    vhci_hcd.0/attach, record /var/run/vhci_hcd/port<N> as `usbip attach` does,
    and close our descriptor (the kernel keeps the socket). The device leaves
    the list.
  - Otherwise: OP_REQ_DEVLIST, keep the reply as that peer's list, close. The
    phone redials and the next connection imports.

A bus id is trusted for one list-then-import cycle only: it changes when the
device re-enumerates, and the device list carries no serial.

Who may connect: the socket listens on the tailnet IPv4 (--listen, else
`tailscale ip -4`; a failed bind is retried every 2 s, never fatal). Every peer
goes through `tailscale whois --json <ip>` and is allowed only if the node has
no tags (Tags absent, null or empty) and its user's LoginName is one of --allow-user. Anything else, a
nonzero exit and non-JSON output ("peer not found") included, is logged and
closed before a byte is sent. With --allow-node (repeatable) the node's StableID
must also be listed; the `allow` and `refuse` log lines carry the node name and
StableID so the operator can read the ID from the journal.

With --psk-file (64 hex chars, mode 0600 or tighter) every allowed connection
first runs "usbip-dialin auth v1", a mutual HMAC-SHA256 challenge (see box_auth
and phone_auth); without it the byte stream is unchanged.

Before any OP_REQ_IMPORT the service checks that every vhci root hub has
interface_authorized_default = 0 (intf_auth_armed) and refuses to import
otherwise: the attached device's interfaces then stay unbound until the host
authorises them. After an attach it waits (IF_HELPER_WAIT_S) for the udev helper's
marker file for that device (usbip_authorize.py writes MARKER_DIR/<device>.json when it
has finished, with the device's busnum and devnum, which must equal the attached
device's, so an older marker of the same name never counts), then logs one
`interfaces` line with what appeared and `helper`: "seen", or "not_seen" (the rule is
not live; an `error` line says so as well).

Safety: every handshake reply has a --timeout; anything malformed or short
closes that connection and the service runs on. It never detaches anything,
on error or on stop: an attached device belongs to the kernel and the TCP
close detaches it. It never loads a module and never connects out. Needs root
(the attach file is root-only).

Log: one JSON object per line on stdout (the journal under systemd).
"""
import argparse
import errno
import glob
import hashlib
import hmac
import json
import os
import re
import select
import shlex
import signal
import socket
import stat
import struct
import subprocess
import sys
import threading
import time

USBIP_VERSION = 0x0111
OP_REQ_IMPORT, OP_REP_IMPORT = 0x8003, 0x0003
OP_REQ_DEVLIST, OP_REP_DEVLIST = 0x8005, 0x0005
ST_OK = 0

# tools/usb/usbip/src/usbip_network.h (v6.8), all big-endian and packed
OP_COMMON = struct.Struct('>HHI')                       # version code status
USB_DEVICE = struct.Struct('>256s32sIIIHHHBBBBBB')      # struct usbip_usb_device, 312 bytes
USB_INTERFACE = struct.Struct('>BBBB')                  # struct usbip_usb_interface, 4 bytes
SYSFS_BUS_ID_SIZE = 32
MAX_DEVICES = 64                                        # a sane cap on an allowed peer's ndev

# usbip_common.h: the vhci port states in the status file's "sta" column
VDEV_ST_NULL = 4
USB_SPEED_SUPER = 5                                     # linux/usb/ch9.h
# linux v6.8 include/uapi/linux/usb/ch9.h: LOW 1, FULL 2, HIGH 3, WIRELESS 4, SUPER 5.
# vhci_sysfs.c valid_args also takes WIRELESS; we refuse it.
VALID_SPEEDS = frozenset((1, 2, 3, 5))
# A bus id ends up in /var/run/vhci_hcd/port<N>, which `usbip port` reads into a 32-byte buffer.
BUSID_RE = re.compile(r'[0-9A-Za-z.:_-]{1,31}')

VHCI = '/sys/devices/platform/vhci_hcd.0'
SYSFS = '/sys'
STATE_DIR = '/var/run/vhci_hcd'                         # VHCI_STATE_PATH in usbip_common.h

BIND_RETRY_S = 2.0
IF_POLL_S, IF_STEP_S, IF_SETTLE_S = 3.0, 0.2, 0.5        # post-attach interface report timing
IF_HELPER_WAIT_S, IF_HELPER_STEP_S = 5.0, 0.1            # how long the helper's marker is waited for
MARKER_DIR = '/run/usbip-authorize'                     # usbip_authorize.py's MARKER_DIR; --marker-dir overrides, for tests
MARKER_CAP = 65536                                      # bytes read from a marker
AUTH_MAGIC = b'UDIALIN1'                                # "usbip-dialin auth v1"
AUTH_LABEL_PHONE = b'usbip-dialin v1 phone'
AUTH_LABEL_BOX = b'usbip-dialin v1 box'
NONCE_LEN = TAG_LEN = 32
MAX_CONNECTIONS = 32

_log_lock = threading.Lock()


def log(ev, **kw):
    kw = dict(t=round(time.time(), 4), ev=ev, **kw)
    line = json.dumps(kw, separators=(',', ':'))
    with _log_lock:
        print(line, flush=True)


class ProtoError(Exception):
    """A malformed, short, late or refused reply: close this connection only."""


class AuthError(ProtoError):
    """The peer failed the mutual authentication (bad tag or a reflected nonce)."""


# ---------------------------------------------------------------- wire
def recv_exact(sock, n, deadline):
    """n bytes by the monotonic deadline. The socket stays blocking: a timeout set
    on it would leave O_NONBLOCK on the file the kernel then keeps."""
    buf = bytearray()
    while len(buf) < n:
        left = deadline - time.monotonic()
        if left <= 0:
            raise ProtoError(f'timed out after {len(buf)} of {n} bytes')
        ready, _, _ = select.select([sock], [], [], left)
        if not ready:
            continue
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ProtoError(f'peer closed after {len(buf)} of {n} bytes')
        buf += chunk
    return bytes(buf)


def cstr(raw):
    return raw.split(b'\0', 1)[0].decode('ascii', 'replace')


def recv_op_common(sock, want_code, deadline):
    version, code, status = OP_COMMON.unpack(recv_exact(sock, OP_COMMON.size, deadline))
    if version != USBIP_VERSION:
        raise ProtoError(f'protocol version {version:#06x}, want {USBIP_VERSION:#06x}')
    if code != want_code:
        raise ProtoError(f'reply code {code:#06x}, want {want_code:#06x}')
    return status


def unpack_device(raw):
    (path, busid, busnum, devnum, speed, vid, pid, bcd, dclass, dsub, dproto,
     cfg, ncfg, nif) = USB_DEVICE.unpack(raw)
    return dict(path=cstr(path), busid=cstr(busid), busnum=busnum, devnum=devnum, speed=speed,
                vid=vid, pid=pid, nif=nif)


def check_device(dev):
    """ProtoError unless the bus id and speed are ones we will pass on to vhci."""
    if not BUSID_RE.fullmatch(dev['busid']):
        raise ProtoError(f'bad bus id {dev["busid"]!r}')
    if dev['speed'] not in VALID_SPEEDS:
        raise ProtoError(f'bad speed {dev["speed"]} for bus id {dev["busid"]!r}')
    return dev


def request_devlist(sock, timeout):
    """OP_REQ_DEVLIST, then parse OP_REP_DEVLIST: a list of device dicts."""
    sock.sendall(OP_COMMON.pack(USBIP_VERSION, OP_REQ_DEVLIST, 0))
    deadline = time.monotonic() + timeout
    status = recv_op_common(sock, OP_REP_DEVLIST, deadline)
    if status != ST_OK:
        raise ProtoError(f'devlist status {status}')
    ndev, = struct.unpack('>I', recv_exact(sock, 4, deadline))
    if ndev > MAX_DEVICES:
        raise ProtoError(f'devlist claims {ndev} devices')
    devices = []
    for _ in range(ndev):
        dev = unpack_device(recv_exact(sock, USB_DEVICE.size, deadline))
        for _ in range(dev['nif']):
            recv_exact(sock, USB_INTERFACE.size, deadline)      # interfaces are not kept
        devices.append(check_device(dev))
    return devices


def request_import(sock, busid, timeout):
    """OP_REQ_IMPORT, then OP_REP_IMPORT: the device dict, or ProtoError."""
    sock.sendall(OP_COMMON.pack(USBIP_VERSION, OP_REQ_IMPORT, 0)
                 + busid.encode('ascii', 'replace')[:SYSFS_BUS_ID_SIZE - 1].ljust(SYSFS_BUS_ID_SIZE, b'\0'))
    deadline = time.monotonic() + timeout
    status = recv_op_common(sock, OP_REP_IMPORT, deadline)
    if status != ST_OK:
        raise ProtoError(f'import of {busid} refused by the peer, status {status}')
    dev = check_device(unpack_device(recv_exact(sock, USB_DEVICE.size, deadline)))
    if dev['busid'] != busid:
        raise ProtoError(f'import reply is for {dev["busid"]!r}, asked for {busid!r}')
    if dev['busnum'] > 0xFFFF or dev['devnum'] > 0xFFFF:
        raise ProtoError(f'bus {dev["busnum"]} dev {dev["devnum"]} does not fit a devid')
    return dev


# ---------------------------------------------------------------- vhci
def read_status():
    """Rows of vhci_hcd.0/status (libsrc/vhci_driver.c, parse_status): the header
    line is skipped; "hub port sta spd dev sockfd local_busid"."""
    with open(VHCI + '/status') as f:
        lines = f.read().splitlines()
    rows = []
    for ln in lines[1:]:
        p = ln.split()
        if len(p) < 5:
            continue
        try:
            rows.append(dict(hub=p[0], port=int(p[1]), sta=int(p[2]), spd=int(p[3]), dev=int(p[4], 16)))
        except ValueError:
            continue
    return rows


def read_record(port):
    """(host, port, busid) from /var/run/vhci_hcd/port<N>, or None."""
    try:
        with open(f'{STATE_DIR}/port{port}') as f:
            parts = f.readline().split()
    except OSError:
        return None
    return tuple(parts) if len(parts) == 3 else None


def attached_busids(peer_ip):
    """Bus ids of this peer that a record names and whose vhci port is in use."""
    out = set()
    for row in read_status():
        if row['sta'] == VDEV_ST_NULL:
            continue
        rec = read_record(row['port'])
        if rec and rec[0] == peer_ip:
            out.add(rec[2])
    return out


def write_record(port, host, rport, busid):
    """Exactly what `usbip attach` writes (src/usbip_attach.c, record_connection)."""
    os.makedirs(STATE_DIR, mode=0o700, exist_ok=True)
    fd = os.open(f'{STATE_DIR}/port{port}', os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o700)
    try:
        os.write(fd, f'{host} {rport} {busid}\n'.encode())
    finally:
        os.close(fd)


def vhci_attach(sock, dev, peer_ip, peer_port):
    """Hand the socket to vhci_hcd on a free port of the right hub. Returns the port."""
    devid = (dev['busnum'] << 16) | dev['devnum']
    want = 'ss' if dev['speed'] >= USB_SPEED_SUPER else 'hs'
    tried = set()
    while True:
        free = [r for r in read_status() if r['hub'] == want and r['sta'] == VDEV_ST_NULL
                and r['port'] not in tried]
        if not free:
            raise ProtoError(f'no free {want} vhci port')
        port = free[0]['port']
        try:
            fd = os.open(VHCI + '/attach', os.O_WRONLY)
            try:
                os.write(fd, f'{port} {sock.fileno()} {devid} {dev["speed"]}'.encode())
            finally:
                os.close(fd)
        except OSError as e:
            if e.errno == errno.EBUSY:          # taken since the status read: next port, as usbip does
                tried.add(port)
                continue
            raise ProtoError(f'vhci attach on port {port}: {e}')
        try:
            write_record(port, peer_ip, peer_port, dev['busid'])
        except OSError as e:
            log('error', peer=peer_ip, msg=f'attached on vhci port {port} but the record was not written: {e}')
        return port


# ---------------------------------------------------------------- peers
def whois_check(args, ip):
    """(login, info, None) for an allowed peer, else (None, info, reason). info is
    {node, stable_id} once the Node object parsed, else None."""
    try:
        r = subprocess.run(shlex.split(args.whois_cmd) + [ip], capture_output=True,
                           timeout=args.timeout, stdin=subprocess.DEVNULL)
    except subprocess.TimeoutExpired:
        return None, None, 'whois timed out'
    except OSError as e:
        return None, None, f'whois could not run: {e}'
    if r.returncode != 0:
        return None, None, f'whois exit {r.returncode}: {(r.stdout + r.stderr)[:80]!r}'
    try:
        who = json.loads(r.stdout)
    except ValueError:
        return None, None, f'whois output is not JSON: {r.stdout[:80]!r}'
    node = who.get('Node') if isinstance(who, dict) else None
    prof = who.get('UserProfile') if isinstance(who, dict) else None
    info = None
    if isinstance(node, dict):
        sid = node.get('StableID')
        info = dict(node=str(node.get('ComputedName', '')), stable_id=sid if isinstance(sid, str) else None)
    if not isinstance(node, dict) or not isinstance(prof, dict):
        return None, info, 'whois JSON has no Node object and UserProfile object'
    tags = node.get('Tags')     # an untagged node has no Tags key at all (checked on real `tailscale whois --json` output)
    if isinstance(tags, list) and tags:
        return None, info, f'tagged node {tags!r}'
    if tags is not None and tags != []:
        return None, info, f'Node.Tags is not null or a list: {tags!r}'
    login = prof.get('LoginName')
    if not isinstance(login, str) or login not in args.allow_user:
        return None, info, f'login {login!r} not in --allow-user'
    allow_node = getattr(args, 'allow_node', None) or []
    if allow_node and info['stable_id'] not in allow_node:
        return None, info, f'node {info["stable_id"]} not in --allow-node'
    return login, info, None


# ---------------------------------------------------------------- mutual authentication
def load_psk(path):
    """The 32-byte key in PATH: a regular file, no group or other access, exactly
    64 hex chars and an optional newline. ValueError (never quoting the content)."""
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NONBLOCK | os.O_NOCTTY)
    except OSError as e:
        raise ValueError(f'--psk-file {path}: {e.strerror}')
    try:
        st = os.fstat(fd)
        if not stat.S_ISREG(st.st_mode):
            raise ValueError(f'--psk-file {path} is not a regular file')
        if st.st_mode & 0o077:
            raise ValueError(f'--psk-file {path} has mode {st.st_mode & 0o777:03o}; '
                             'it must not be accessible by group or other (chmod 600)')
        data = os.read(fd, 256)
    except OSError as e:
        raise ValueError(f'--psk-file {path}: {e.strerror}')
    finally:
        os.close(fd)
    if data.endswith(b'\n'):
        data = data[:-1]
    if not re.fullmatch(rb'[0-9A-Fa-f]{64}', data):
        raise ValueError(f'--psk-file {path} must hold exactly 64 hex characters')
    return bytes.fromhex(data.decode('ascii'))


def _tag(key, label, nonce_b, nonce_p):
    return hmac.new(key, label + nonce_b + nonce_p, hashlib.sha256).digest()


def box_auth(sock, key, timeout):
    """usbip-dialin auth v1, box side. Sends MAGIC + nonce_b, reads nonce_p + tag_p,
    and only on a good tag (and nonce_p != nonce_b) sends tag_b. ProtoError on any
    failure (AuthError for a bad tag), with nothing sent after the hello."""
    nonce_b = os.urandom(NONCE_LEN)
    sock.sendall(AUTH_MAGIC + nonce_b)
    got = recv_exact(sock, NONCE_LEN + TAG_LEN, time.monotonic() + timeout)
    nonce_p, tag_p = got[:NONCE_LEN], got[NONCE_LEN:]
    good = hmac.compare_digest(tag_p, _tag(key, AUTH_LABEL_PHONE, nonce_b, nonce_p))
    if not good or nonce_p == nonce_b:
        raise AuthError('auth failed')
    sock.sendall(_tag(key, AUTH_LABEL_BOX, nonce_b, nonce_p))


def phone_auth(sock, key, timeout):
    """usbip-dialin auth v1, phone side (the reference the Android fork copies):
    read MAGIC + nonce_b, send nonce_p + tag_p, read tag_b and verify it."""
    deadline = time.monotonic() + timeout
    hello = recv_exact(sock, len(AUTH_MAGIC) + NONCE_LEN, deadline)
    if hello[:len(AUTH_MAGIC)] != AUTH_MAGIC:
        raise ProtoError('bad auth magic')
    nonce_b = hello[len(AUTH_MAGIC):]
    nonce_p = os.urandom(NONCE_LEN)
    sock.sendall(nonce_p + _tag(key, AUTH_LABEL_PHONE, nonce_b, nonce_p))
    tag_b = recv_exact(sock, TAG_LEN, deadline)
    if not hmac.compare_digest(tag_b, _tag(key, AUTH_LABEL_BOX, nonce_b, nonce_p)):
        raise AuthError('box tag does not verify')


# ---------------------------------------------------------------- interface authorisation
def intf_auth_armed():
    """(ok, detail): every vhci root hub has interface_authorized_default == '0', so
    an attached device's interfaces are not bound to drivers until authorised.
    Fails closed: no hub found or any unreadable file is not ok."""
    files = sorted(glob.glob(f'{SYSFS}/devices/platform/vhci_hcd.*/usb*/interface_authorized_default'))
    if not files:
        return False, 'no vhci root hub found'
    for path in files:
        try:
            with open(path) as f:
                val = f.read().strip()
        except (OSError, ValueError) as e:
            return False, f'{path}: {e}'
        if val != '0':
            return False, f'{path} is {val!r}, want \'0\''
    return True, f'{len(files)} root hub(s) at 0'


def vhci_intf_dirs():
    """Paths of the interface directories (1-1:1.0 style) under the vhci root hubs."""
    return set(glob.glob(f'{SYSFS}/devices/platform/vhci_hcd.*/usb*/*-*/*-*:*'))


def _read_field(path):
    try:
        with open(path) as f:
            return f.read().strip()
    except (OSError, ValueError):
        return None


def describe_interface(path):
    try:
        driver = os.path.basename(os.readlink(path + '/driver'))
    except OSError:
        driver = None
    return dict(name=os.path.basename(path), cls=_read_field(path + '/bInterfaceClass'),
                subclass=_read_field(path + '/bInterfaceSubClass'),
                protocol=_read_field(path + '/bInterfaceProtocol'),
                authorized=_read_field(path + '/authorized'), driver=driver)


def device_ids(devdir):
    """(busnum, devnum) of a USB device directory as ints, or None when either does not read."""
    try:
        return int(_read_field(devdir + '/busnum')), int(_read_field(devdir + '/devnum'))
    except (TypeError, ValueError):
        return None


def helper_marker_seen(devdir):
    """True when usbip_authorize.py has finished for THIS device: MARKER_DIR/<name>.json is a
    JSON object of event authorize for that name whose busnum and devnum are the device's own.
    A marker left by an earlier attach of the same name has other numbers and does not count."""
    ids = device_ids(devdir)
    if ids is None:
        return False
    name = os.path.basename(devdir)
    try:
        with open(f'{MARKER_DIR}/{name}.json', 'rb') as f:
            raw = f.read(MARKER_CAP + 1)
        rec = json.loads(raw.decode('utf-8')) if len(raw) <= MARKER_CAP else None
    except (OSError, ValueError):
        return False
    return (isinstance(rec, dict) and rec.get('ev') == 'authorize' and rec.get('device') == name
            and type(rec.get('busnum')) is int and type(rec.get('devnum')) is int
            and (rec['busnum'], rec['devnum']) == ids)


def wait_for_helper(devdirs):
    """'seen' once every device directory has its helper marker, else 'not_seen' after IF_HELPER_WAIT_S."""
    end = time.monotonic() + IF_HELPER_WAIT_S
    while True:
        if all(helper_marker_seen(d) for d in devdirs):
            return 'seen'
        if time.monotonic() >= end:
            return 'not_seen'
        time.sleep(IF_HELPER_STEP_S)


def report_interfaces(peer, busid, before):
    """Log one `interfaces` line for the vhci interfaces that appeared since BEFORE
    (a vhci_intf_dirs() taken ahead of the attach), after the udev helper's marker for their
    device appears (`helper`: "seen") or IF_HELPER_WAIT_S passes ("not_seen": the rule is not
    live, and an `error` line says so). Read only; never raises: the device is attached and
    the kernel owns the socket."""
    try:
        new = set()
        end = time.monotonic() + IF_POLL_S
        while True:
            new = vhci_intf_dirs() - before
            if new or time.monotonic() >= end:
                break
            time.sleep(IF_STEP_S)
        helper = 'not_seen'
        if new:
            helper = wait_for_helper(sorted({os.path.dirname(p) for p in new}))
            if helper == 'seen':
                time.sleep(IF_SETTLE_S)         # the driver's own follow-up (tty, scan): after the marker
            new = vhci_intf_dirs() - before
            if helper != 'seen':
                log('error', peer=peer, msg=f'no usbip_authorize marker in {MARKER_DIR} within {IF_HELPER_WAIT_S:g} s '
                                            f'for busid {busid}: the udev rule is not live, so its interfaces stay unauthorised')
        log('interfaces', peer=peer, busid=busid, helper=helper,
            interfaces=[describe_interface(p) for p in sorted(new)])
    except Exception as e:
        try:
            log('error', peer=peer, msg=f'interface report: {type(e).__name__}: {e}')
        except Exception:
            pass


class State:
    """Per-peer device lists, and the lock around choose-then-attach."""

    def __init__(self):
        self.lock = threading.Lock()
        self.lists = {}                 # peer ip -> (monotonic time, [device dicts])
        self.active = 0

    def claim(self, ip, ttl):
        """Pop the first device of a fresh list that is not attached, else None."""
        with self.lock:
            now = time.monotonic()
            for k in [k for k, (ts, _) in self.lists.items() if now - ts >= ttl]:
                del self.lists[k]
            entry = self.lists.get(ip)
            if not entry:
                return None
            try:
                taken = attached_busids(ip)
            except OSError as e:
                log('error', peer=ip, msg=f'cannot read vhci status: {e}')
                return None
            devs = entry[1]
            for i, d in enumerate(devs):
                if d['busid'] not in taken:
                    return devs.pop(i)
            return None

    def store(self, ip, devices):
        with self.lock:
            self.lists[ip] = (time.monotonic(), devices)

    def drop(self, ip):
        with self.lock:
            self.lists.pop(ip, None)


def serve(args, st, conn, ip, sport):
    dev = st.claim(ip, args.list_ttl)
    if dev is None:
        devices = request_devlist(conn, args.timeout)
        st.store(ip, devices)
        log('list', peer=ip, count=len(devices),
            devices=[f'{d["vid"]:04x}:{d["pid"]:04x} {d["busid"]}' for d in devices])
        return
    armed, detail = intf_auth_armed()
    if not armed:                   # fail closed: an unauthorised-by-default hub is what keeps a hostile device's interfaces unbound
        log('refuse_import', peer=ip, busid=dev['busid'], reason=detail)
        st.drop(ip)
        return
    log('import', peer=ip, busid=dev['busid'])
    try:
        got = request_import(conn, dev['busid'], args.timeout)
        with st.lock:               # serialise free-port choice and write across connections
            before = vhci_intf_dirs()
            port = vhci_attach(conn, got, ip, sport)
    except Exception:
        st.drop(ip)                 # the next dial lists afresh
        raise
    log('attach', peer=ip, busid=got['busid'], vhci_port=port, vidpid=f'{got["vid"]:04x}:{got["pid"]:04x}',
        devid=(got['busnum'] << 16) | got['devnum'], speed=got['speed'])
    report_interfaces(ip, got['busid'], before)


def handle(args, st, conn, addr):
    ip, sport = addr[0], addr[1]
    try:
        log('accept', peer=ip, sport=sport)
        login, info, why = whois_check(args, ip)
        if why:
            log('refuse', peer=ip, reason=why, **(info or {}))
            return
        log('allow', peer=ip, login=login, **info)
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        conn.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
        for opt, val in (('TCP_KEEPIDLE', 30), ('TCP_KEEPINTVL', 10), ('TCP_KEEPCNT', 3)):
            if hasattr(socket, opt):    # a dead phone must not hold an attached device for 2 h
                conn.setsockopt(socket.IPPROTO_TCP, getattr(socket, opt), val)
        key = getattr(args, 'auth_key', None)
        if key:
            try:
                box_auth(conn, key, args.timeout)
            except AuthError:
                log('refuse', peer=ip, reason='auth failed')
                return
            log('auth', peer=ip)
        serve(args, st, conn, ip, sport)
    except (ProtoError, OSError, struct.error) as e:
        log('error', peer=ip, msg=str(e))
    except Exception as e:                      # a bug here must not take the service down
        log('error', peer=ip, msg=f'{type(e).__name__}: {e}')
    finally:
        conn.close()                    # after a good attach the kernel holds its own reference
        with st.lock:
            st.active -= 1


# ---------------------------------------------------------------- listener
def split_addr(text):
    host, sep, port = text.rpartition(':')
    if not sep or not port.isdigit() or not 0 < int(port) < 65536:
        raise ValueError(f'not ADDR:PORT: {text!r}')
    return host.strip('[]'), int(port)


def resolve_listen(args):
    if args.listen:
        return split_addr(args.listen)
    r = subprocess.run(shlex.split(args.ip_cmd), capture_output=True, text=True, timeout=10,
                       stdin=subprocess.DEVNULL)
    ips = r.stdout.split()
    if r.returncode != 0 or not ips:
        raise OSError(f'{args.ip_cmd!r} gave no address (exit {r.returncode}): {r.stderr.strip()[:80]}')
    return ips[0], args.port


def bind_listener(args):
    """Retry until bound: tailscale0 may have no address yet, or the port be busy."""
    last = None
    while True:
        try:
            host, port = resolve_listen(args)
            fam = socket.AF_INET6 if ':' in host else socket.AF_INET
            s = socket.socket(fam, socket.SOCK_STREAM)
            try:
                s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                s.bind((host, port))
                s.listen(16)
            except OSError:
                s.close()
                raise
            return s, host, port
        except (OSError, subprocess.SubprocessError) as e:
            if str(e) != last:                  # a line per change, not one every 2 s
                log('bind_retry', msg=str(e), every_s=BIND_RETRY_S)
                last = str(e)
            time.sleep(BIND_RETRY_S)


def main():
    global MARKER_DIR
    ap = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    ap.add_argument('--allow-user', action='append', default=[], metavar='LOGIN',
                    help='a tailnet UserProfile.LoginName allowed to attach; repeatable, at least one')
    ap.add_argument('--listen', metavar='ADDR:PORT',
                    help="where to accept (default: `tailscale ip -4` address, --port)")
    ap.add_argument('--port', type=int, default=3241, help='port on the --ip-cmd address (default 3241)')
    ap.add_argument('--ip-cmd', default='tailscale ip -4',
                    help="prints the address to listen on when --listen is not given (default 'tailscale ip -4')")
    ap.add_argument('--whois-cmd', default='tailscale whois --json',
                    help="run with the peer IP appended (default 'tailscale whois --json')")
    ap.add_argument('--timeout', type=float, default=10.0,
                    help='seconds for whois and for every handshake reply (default 10)')
    ap.add_argument('--list-ttl', type=float, default=30.0,
                    help='seconds a device list may be imported from (default 30)')
    ap.add_argument('--allow-node', action='append', default=[], metavar='STABLEID',
                    help='a tailnet Node.StableID allowed to attach; repeatable. With none, any node of an '
                         '--allow-user is allowed. Read the ID from the `allow` line in the journal')
    ap.add_argument('--psk-file', metavar='PATH',
                    help='64 hex chars (mode 0600): require the usbip-dialin auth v1 handshake on every '
                         'allowed connection. Default: off, the byte stream is unchanged')
    ap.add_argument('--marker-dir', default=MARKER_DIR, help=argparse.SUPPRESS)    # tests only
    args = ap.parse_args()
    MARKER_DIR = args.marker_dir
    args.auth_key = None
    if args.psk_file:
        try:
            args.auth_key = load_psk(args.psk_file)
        except ValueError as e:
            ap.error(str(e))
    if not args.allow_user:
        ap.error('--allow-user is required: with none, every peer would be refused')
    if args.listen:
        try:
            split_addr(args.listen)
        except ValueError as e:
            ap.error(str(e))

    def on_term(signum, frame):
        raise SystemExit(0)
    signal.signal(signal.SIGTERM, on_term)

    st = State()
    log('start', pid=os.getpid(), allow=args.allow_user, listen=args.listen or f'{args.ip_cmd!r}:{args.port}',
        timeout=args.timeout, list_ttl=args.list_ttl, allow_node=args.allow_node, auth=args.auth_key is not None)
    armed, detail = intf_auth_armed()
    log('intf_auth', ok=armed, detail=detail)
    try:
        srv, host, port = bind_listener(args)
        log('listening', addr=host, port=port)
        while True:
            conn, addr = srv.accept()
            with st.lock:
                busy = st.active >= MAX_CONNECTIONS
                if not busy:
                    st.active += 1
            if busy:
                log('refuse', peer=addr[0], reason='too many connections')
                conn.close()
                continue
            threading.Thread(target=handle, args=(args, st, conn, addr), daemon=True).start()
    except (KeyboardInterrupt, SystemExit):
        log('stop')                     # nothing is detached: the kernel owns what is attached
        return 0


if __name__ == '__main__':
    sys.exit(main())
