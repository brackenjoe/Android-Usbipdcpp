#!/usr/bin/env python3
"""usbip-authorize: let a phone's USB device bind anything except input and network.

Run by udev (70-usbip-dialin.rules) on the `bind` event of a USB device that
sits on a vhci_hcd root hub. Each vhci root hub has interface_authorized_default
set to 0, so every interface of an attached device starts unauthorized and binds
nothing (Linux v6.8, drivers/usb/core/sysfs.c interface_authorized_default_store;
message.c:2108 sets intf->authorized from HCD_INTF_AUTHORIZED at set_configuration;
driver.c:340 usb_probe_interface and driver.c:552 usb_driver_claim_interface both
refuse an unauthorized interface). This helper authorizes the interfaces it
accepts, every one first (a multi-interface driver needs them all, per
Documentation/usb/authorization.rst), then writes each accepted interface that
some driver could match to /sys/bus/usb/drivers_probe.

    usbip_authorize.py DEVICE_SYSFS_PATH [--sysfs-root /sys] [--dry-run]
                       [--modules-dir /lib/modules/<release>]

An interface is denied if either layer says so:
  A. Class: HID (03), wireless controller (e0: Bluetooth, RNDIS), non-ACM CDC
     (02 with subclass other than 02: ECM, NCM, EEM, MBIM, WDM...), 02/02/ff and
     ef with subclass 01 or 04 (RNDIS; rndis_host matches those in v6.8), and,
     until the kernel is fixed, audio (01).
  B. Drivers: network adapters and WiFi dongles are mostly vendor class (ff)
     matched by VID:PID, so a class list misses them. The interface's `modalias`
     is matched against the kernel's own alias tables in the modules directory
     (modules.alias for loadable modules, modules.builtin.modinfo for built-in
     ones). Each candidate module is placed by modules.dep / modules.builtin; any
     candidate under DENY_DIRS (hid, input, net, bluetooth, staging, kernel/net,
     and sound for now) or with no known path denies the interface. A modules
     file that is missing or unreadable denies everything (fail closed).
An interface with no candidate driver is authorized and not probed: userspace
libusb tools (an SDR) need it authorized to claim it. A malformed or unreadable
attribute or modalias denies that interface.

Never writes 0, never deauthorizes, never touches an interface already
authorized (it only reports it). Standard library only, no subprocess, no network.

Marker: after a real run (never with --dry-run) and after the log line, it writes
the same JSON plus the device's "busnum" and "devnum" (from sysfs, ints or null)
to MARKER_DIR/<device name>.json (/run/usbip-authorize, 0755; the file 0644; a
temp file renamed over it, so a reader sees all of it or the old one). The
service's interfaces report waits for it: it is how it knows the interfaces it is
about to read are settled, and a marker that never comes means this rule is not
live. A failure to write it is logged on stderr and never changes the exit code.

Log: one JSON object per run, to syslog (journald) and stdout.
Exit: 0 it ran (even if everything was denied), 1 the path was refused, 2 usage.
"""
import argparse
import errno
import fnmatch
import json
import os
import re
import sys
import syslog

# Hard class denies, on bInterfaceClass. HID: keyboards and mice.
DENY_CLASSES = {
    '03': 'HID',
    'e0': 'wireless controller (Bluetooth, RNDIS)',
}
# INTERIM: until the running kernel has the fixes for the two ALSA usb-audio CVEs
# (Ubuntu 6.8.0-148); remove then. Dropping these two constants lifts the audio deny.
INTERIM_DENY_CLASSES = {
    '01': 'audio (interim, ALSA usb-audio CVEs)',
}
INTERIM_DENY_DIRS = ('kernel/sound/',)

# A candidate driver under any of these paths denies the interface.
DENY_DIRS = (
    'kernel/drivers/hid/',
    'kernel/drivers/input/',
    'kernel/drivers/net/',
    'kernel/drivers/bluetooth/',
    'kernel/drivers/staging/',
    'kernel/net/',
)

MARKER_DIR = '/run/usbip-authorize'      # --marker-dir overrides it, for tests only

ATTR_CAP = 64           # bytes read from any sysfs attribute
MODALIAS_CAP = 256
MODALIAS_LOG = 120      # modalias characters kept in the log
CANDIDATES_LOG = 16     # candidate modules kept per interface in the log
FILE_CAP = 16 << 20     # bytes read from any modules file
MODULES_FILES = ('modules.alias', 'modules.builtin.modinfo', 'modules.dep',
                 'modules.builtin')

DEVNAME_RE = re.compile(r'\d+-[\d.]+')
HEX2_RE = re.compile(r'[0-9a-fA-F]{2}')
HEX4_RE = re.compile(r'[0-9a-fA-F]{4}')
MODALIAS_RE = re.compile(
    r'usb:v[0-9A-F]{4}p[0-9A-F]{4}d[0-9A-F]{4}dc[0-9A-F]{2}dsc[0-9A-F]{2}'
    r'dp[0-9A-F]{2}ic[0-9A-F]{2}isc[0-9A-F]{2}ip[0-9A-F]{2}in[0-9A-F]{2}')
KO_RE = re.compile(r'(.*?)\.ko(?:\.[a-z0-9]+)?')


def log(ev, **kw):
    kw = dict(ev=ev, **kw)
    line = json.dumps(kw, separators=(',', ':'))
    try:
        syslog.syslog(syslog.LOG_INFO, line)
    except OSError:
        pass
    print(line, flush=True)


class Refused(Exception):
    """The path is not a device on a vhci root hub."""


def read_attr(path, cap=ATTR_CAP):
    """The attribute's text with one trailing newline removed, read with a size
    cap. Raises OSError, or ValueError when it is longer than the cap."""
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        raw = os.read(fd, cap + 1)
    finally:
        os.close(fd)
    if len(raw) > cap:
        raise ValueError('attribute too long')
    text = raw.decode('ascii')
    return text[:-1] if text.endswith('\n') else text


def write_attr(path, data):
    """One open, one write, one close: drivers_probe is write-only sysfs."""
    fd = os.open(path, os.O_WRONLY)
    try:
        os.write(fd, data.encode('ascii'))
    finally:
        os.close(fd)


def hexattr(path, pattern):
    """Lowercased hex attribute, or None when missing, unreadable or malformed."""
    try:
        text = read_attr(path)
    except (OSError, ValueError):
        return None
    return text.lower() if pattern.fullmatch(text) else None


def resolve_device(path, root):
    """(real device dir, device name) or Refused."""
    real = os.path.realpath(path)
    prefix = os.path.join(os.path.realpath(root), 'devices', 'platform') + os.sep
    if not real.startswith(prefix):
        raise Refused('not under devices/platform')
    parts = real[len(prefix):].split(os.sep)
    # vhci_hcd.N / usbN / [hub ports...] / device
    if len(parts) < 3 or not re.fullmatch(r'vhci_hcd\.\d+', parts[0]) \
            or not re.fullmatch(r'usb\d+', parts[1]):
        raise Refused('not under a vhci_hcd root hub')
    if not all(DEVNAME_RE.fullmatch(p) for p in parts[2:]):
        raise Refused('not a device name')
    if not os.path.isdir(real):
        raise Refused('not a directory')
    return real, parts[-1]


# ---------------------------------------------------------------- drivers
def norm(module):
    return module.replace('-', '_')


def read_file(path):
    with open(path, 'rb') as f:
        data = f.read(FILE_CAP + 1)
    if len(data) > FILE_CAP:
        raise ValueError('file too large')
    return data.decode('utf-8', 'replace')


class Modules:
    """The kernel's alias and path tables. `error` is set, and the tables left
    empty, when any file is missing, unreadable or over the cap: callers then
    deny everything."""

    def __init__(self, directory):
        self.aliases = []       # (usb: glob, module)
        self.paths = {}         # module -> [kernel/... path]
        self.error = None
        try:
            self._load(directory)
        except (OSError, ValueError) as e:
            self.aliases, self.paths = [], {}
            self.error = '%s: %s' % (getattr(e, 'filename', None) or directory,
                                     errno.errorcode.get(getattr(e, 'errno', 0) or 0, str(e)))

    def _load(self, d):
        text = {n: read_file(os.path.join(d, n)) for n in MODULES_FILES}
        # modules.alias: "alias <pattern> <module>"
        for line in text['modules.alias'].splitlines():
            f = line.split()
            if len(f) == 3 and f[0] == 'alias' and f[1].startswith('usb:'):
                self.aliases.append((f[1], norm(f[2])))
        # modules.builtin.modinfo: NUL-separated "<module>.alias=<pattern>"
        for rec in text['modules.builtin.modinfo'].split('\0'):
            key, eq, val = rec.partition('=')
            if eq and key.endswith('.alias') and val.startswith('usb:'):
                self.aliases.append((val, norm(key[:-len('.alias')])))
        # modules.dep: "<path>.ko[.zst]: deps"; modules.builtin: "<path>.ko"
        paths = [l.partition(':')[0].strip() for l in text['modules.dep'].splitlines()]
        paths += [l.strip() for l in text['modules.builtin'].splitlines()]
        for p in paths:
            m = KO_RE.fullmatch(os.path.basename(p))
            if m:
                self.paths.setdefault(norm(m.group(1)), []).append(p)

    def candidates(self, modalias):
        """Sorted modules whose alias pattern matches this modalias."""
        return sorted({m for pat, m in self.aliases if fnmatch.fnmatchcase(modalias, pat)})

    def deny_reason(self, module):
        """Why this module may not bind, or None."""
        paths = self.paths.get(module)
        if not paths:
            return '%s: no known path' % module
        for p in paths:
            for d in DENY_DIRS:
                if p.startswith(d):
                    return '%s under %s' % (module, d)
            for d in INTERIM_DENY_DIRS:
                if p.startswith(d):
                    return '%s under %s (interim)' % (module, d)
        return None


def class_deny_reason(cls, sub, proto):
    """Why the class triple is denied outright, or None."""
    if cls in DENY_CLASSES:
        return 'class %s %s' % (cls, DENY_CLASSES[cls])
    if cls in INTERIM_DENY_CLASSES:
        return 'class %s %s' % (cls, INTERIM_DENY_CLASSES[cls])
    if cls == '02' and sub != '02':
        return 'class 02 non-ACM CDC'
    if (cls, sub, proto) == ('02', '02', 'ff'):
        return 'RNDIS 02/02/ff'
    if cls == 'ef' and sub in ('01', '04'):
        return 'RNDIS ef/%s' % sub
    return None


def judge(idir, modules):
    """(cls text, modalias, candidates, deny reason or None) for one interface."""
    triple = [hexattr(os.path.join(idir, a), HEX2_RE) for a in
              ('bInterfaceClass', 'bInterfaceSubClass', 'bInterfaceProtocol')]
    if None in triple:
        return '?', '', [], 'class attributes unreadable or malformed'
    cls = '/'.join(triple)
    why = class_deny_reason(*triple)
    if why:
        return cls, '', [], why
    if modules.error:
        return cls, '', [], 'modules unavailable: ' + modules.error
    try:
        modalias = read_attr(os.path.join(idir, 'modalias'), MODALIAS_CAP)
    except (OSError, ValueError):
        return cls, '', [], 'modalias unreadable'
    if not MODALIAS_RE.fullmatch(modalias):
        return cls, modalias[:MODALIAS_LOG], [], 'modalias malformed'
    cands = modules.candidates(modalias)
    for m in cands:
        why = modules.deny_reason(m)
        if why:
            return cls, modalias, cands, why
    return cls, modalias, cands, None


def write_marker(directory, devname, devdir, rec):
    """MARKER_DIR/<devname>.json: REC plus busnum and devnum, written to a temp file in the same
    directory and renamed over the final name. Returns an error string, or None."""
    tmp = None
    try:
        os.makedirs(directory, mode=0o755, exist_ok=True)
        os.chmod(directory, 0o755)
        body = dict(rec)
        for k in ('busnum', 'devnum'):
            try:
                body[k] = int(read_attr(os.path.join(devdir, k)))
            except (OSError, ValueError):
                body[k] = None
        tmp = os.path.join(directory, '.%s.json.%d.tmp' % (devname, os.getpid()))
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o644)
        try:
            os.write(fd, (json.dumps(body, separators=(',', ':')) + '\n').encode('ascii'))
            os.fchmod(fd, 0o644)
        finally:
            os.close(fd)
        os.replace(tmp, os.path.join(directory, devname + '.json'))
        return None
    except OSError as e:
        if tmp:
            try:
                os.unlink(tmp)
            except OSError:
                pass
        return '%s: %s' % (getattr(e, 'filename', None) or directory, errno.errorcode.get(e.errno or 0, str(e)))


def authorize(devdir, devname, root, dry_run, modules, marker_dir=None):
    vid = hexattr(os.path.join(devdir, 'idVendor'), HEX4_RE)
    pid = hexattr(os.path.join(devdir, 'idProduct'), HEX4_RE)
    ifre = re.compile(re.escape(devname) + r':\d+\.\d+')
    names = sorted(e.name for e in os.scandir(devdir)
                   if ifre.fullmatch(e.name) and e.is_dir(follow_symlinks=False))

    records, already, auth_errors = [], [], {}
    to_write, probe_wanted = [], set()
    for name in names:
        idir = os.path.join(devdir, name)
        cls, modalias, cands, why = judge(idir, modules)
        rec = {'if': name, 'cls': cls, 'modalias': modalias[:MODALIAS_LOG],
               'candidates': cands[:CANDIDATES_LOG],
               'decision': 'deny' if why else 'allow', 'why': why or ''}
        records.append(rec)
        if why:
            continue
        try:
            state = read_attr(os.path.join(idir, 'authorized'))
        except (OSError, ValueError) as e:
            auth_errors[name] = 'read: ' + errno.errorcode.get(getattr(e, 'errno', 0), str(e))
            continue
        if state == '0':
            to_write.append(name)
            if cands:
                probe_wanted.add(name)
        elif state == '1':
            already.append(name)
        else:
            auth_errors[name] = 'authorized reads %r' % state[:8]

    authorized, probed, probe_errors = [], [], {}
    if not dry_run:
        for name in to_write:
            try:
                write_attr(os.path.join(devdir, name, 'authorized'), '1')
                authorized.append(name)
            except OSError as e:
                auth_errors[name] = 'write: ' + errno.errorcode.get(e.errno, str(e))
        # every authorize before any probe: a multi-interface driver needs them all
        probe_file = os.path.join(os.path.realpath(root), 'bus', 'usb', 'drivers_probe')
        for name in authorized:
            if name not in probe_wanted:
                continue
            try:
                write_attr(probe_file, name)
                probed.append(name)
            except OSError as e:
                # EINVAL/ENODEV: no loaded driver took it, which is normal
                probe_errors[name] = errno.errorcode.get(e.errno, str(e))

    rec = dict(device=devname,
               vidpid='%s:%s' % (vid or '?', pid or '?'),
               interfaces=records, already_authorized=already,
               authorized=authorized, probed=probed,
               auth_errors=auth_errors, probe_errors=probe_errors,
               modules_error=modules.error, dry_run=dry_run)
    log('authorize', **rec)
    if not dry_run and marker_dir:
        err = write_marker(marker_dir, devname, devdir, dict(ev='authorize', **rec))
        if err:
            print(json.dumps(dict(ev='marker_error', device=devname, why=err), separators=(',', ':')),
                  file=sys.stderr, flush=True)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('device', help='sysfs path of the USB device')
    ap.add_argument('--sysfs-root', default='/sys')
    ap.add_argument('--modules-dir', default=None,
                    help='default /lib/modules/<running kernel release>')
    ap.add_argument('--dry-run', action='store_true',
                    help='classify and log; write nothing (no marker either)')
    ap.add_argument('--marker-dir', default=MARKER_DIR, help=argparse.SUPPRESS)
    args = ap.parse_args(argv)
    syslog.openlog('usbip-authorize')
    try:
        devdir, devname = resolve_device(args.device, args.sysfs_root)
    except Refused as e:
        log('refuse', path=args.device[:200], why=str(e))
        return 1
    modules = Modules(args.modules_dir or '/lib/modules/' + os.uname().release)
    try:
        authorize(devdir, devname, args.sysfs_root, args.dry_run, modules, args.marker_dir)
    except OSError as e:
        log('error', device=devname, why=errno.errorcode.get(e.errno, str(e)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
