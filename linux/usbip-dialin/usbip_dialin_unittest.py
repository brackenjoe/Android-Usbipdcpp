#!/usr/bin/env python3
"""Offline unit tests for usbip_dialin.py: no root, no vhci, no tailnet.

    python3 -I usbip_dialin_unittest.py

Everything runs on socket.socketpair() or 127.0.0.1, a fake whois script in a
tempdir, and a fake sysfs tree in a tempdir (the module's SYSFS and VHCI
constants are pointed at it). The hardware end to end is usbip_dialin_selftest.py.
"""
import argparse
import hashlib
import hmac
import importlib.util
import json
import os
import shlex
import socket
import struct
import sys
import tempfile
import threading
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location('usbip_dialin', os.path.join(HERE, 'usbip_dialin.py'))
M = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(M)

KEY = bytes(range(32))
WRONG = bytes(range(1, 33))
PEER = '127.0.0.1'   # only handed to the fake whois


# ---------------------------------------------------------------- helpers
def pack_dev(busid, speed=3, busnum=1, devnum=2, nif=0, vid=0x1234, pid=0x5678):
    raw = busid if isinstance(busid, bytes) else busid.encode('ascii')
    return M.USB_DEVICE.pack(b'/sys/devices/x', raw.ljust(32, b'\0') if len(raw) < 32 else raw,
                             busnum, devnum, speed, vid, pid, 0x100, 0, 0, 0, 1, 1, nif)


def common(code, status=0):
    return M.OP_COMMON.pack(M.USBIP_VERSION, code, status)


def devlist_reply(devs):
    return common(M.OP_REP_DEVLIST) + struct.pack('>I', len(devs)) + b''.join(devs)


def import_reply(dev):
    return common(M.OP_REP_IMPORT) + dev


def serve_once(fn):
    """Run fn(server_end) on a thread against a socketpair; returns (client_end, thread, box)."""
    a, b = socket.socketpair()
    box = {}

    def run():
        try:
            box['ret'] = fn(b)
        except BaseException as e:      # reported by the test through box
            box['exc'] = e
        finally:
            b.close()
    t = threading.Thread(target=run, daemon=True)
    t.start()
    return a, t, box


def fake_devlist_server(reply):
    def run(s):
        M.recv_exact(s, 8, time.monotonic() + 5)
        s.sendall(reply)
    return run


def fake_import_server(reply):
    def run(s):
        M.recv_exact(s, 8 + 32, time.monotonic() + 5)
        s.sendall(reply)
    return run


def devlist(reply):
    a, t, _ = serve_once(fake_devlist_server(reply))
    try:
        return M.request_devlist(a, 2)
    finally:
        a.close()
        t.join(5)


def do_import(busid, reply):
    a, t, _ = serve_once(fake_import_server(reply))
    try:
        return M.request_import(a, busid, 2)
    finally:
        a.close()
        t.join(5)


def read_until_eof(sock, limit=2.0):
    sock.settimeout(limit)
    data = b''
    while True:
        chunk = sock.recv(4096)
        if not chunk:
            return data
        data += chunk


class Events:
    """Replace M.log with a collector."""

    def __init__(self):
        self.rows = []

    def __call__(self, ev, **kw):
        self.rows.append((ev, kw))

    def of(self, ev):
        return [kw for e, kw in self.rows if e == ev]


class Base(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = self._tmp.name
        self.addCleanup(self._tmp.cleanup)
        self.events = Events()
        self._saved = (M.log, M.SYSFS, M.VHCI, M.IF_POLL_S, M.IF_STEP_S, M.IF_SETTLE_S,
                       M.IF_HELPER_WAIT_S, M.IF_HELPER_STEP_S, M.MARKER_DIR)
        M.log = self.events
        self.addCleanup(self._restore)

    def _restore(self):
        (M.log, M.SYSFS, M.VHCI, M.IF_POLL_S, M.IF_STEP_S, M.IF_SETTLE_S,
         M.IF_HELPER_WAIT_S, M.IF_HELPER_STEP_S, M.MARKER_DIR) = self._saved

    def write(self, rel, text='', mode=None):
        path = os.path.join(self.tmp, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, 'w') as f:
            f.write(text)
        if mode is not None:
            os.chmod(path, mode)
        return path


# ---------------------------------------------------------------- bus id and speed
class BusidTests(Base):
    def test_check_device_busid(self):
        ok = ['1-3:0', '1-1', '10-2.4.1', 'a_b-C.9', 'x' * 31, '1-1:1.0']
        bad = ['', 'x' * 32, 'a/b', '../x', 'a b', 'a\nb', 'a\x00b', 'é', '1-1\udcff', 'a;b', '1-1 ']
        for b in ok:
            M.check_device(dict(busid=b, speed=3))
        for b in bad:
            with self.assertRaises(M.ProtoError, msg=repr(b)):
                M.check_device(dict(busid=b, speed=3))

    def test_devlist_accepts_nul_padded(self):
        devs = devlist(devlist_reply([pack_dev('1-3:0'), pack_dev('2-1')]))
        self.assertEqual([d['busid'] for d in devs], ['1-3:0', '2-1'])

    def test_devlist_rejects(self):
        cases = {'empty': b'', '32 chars': b'x' * 32, 'slash': b'1/2', 'space': b'1 2',
                 'high byte': b'1-\xe9', 'ctl': b'1-\x01'}
        for name, raw in cases.items():
            with self.assertRaises(M.ProtoError, msg=name):
                devlist(devlist_reply([pack_dev(raw)]))

    def test_devlist_31_chars_ok(self):
        self.assertEqual(devlist(devlist_reply([pack_dev('y' * 31)]))[0]['busid'], 'y' * 31)

    def test_import_rejects_bad_busid_in_reply(self):
        # we asked for a valid id and the reply echoes it with a slash in: mismatch or bad, either way refused
        with self.assertRaises(M.ProtoError):
            do_import('1-3', import_reply(pack_dev('1/3')))
        with self.assertRaises(M.ProtoError):
            do_import('1-3', import_reply(pack_dev('')))

    def test_import_ok(self):
        dev = do_import('1-3:0', import_reply(pack_dev('1-3:0', speed=2, busnum=1, devnum=7)))
        self.assertEqual((dev['busid'], dev['speed'], dev['devnum']), ('1-3:0', 2, 7))


class SpeedTests(Base):
    BAD = (0, 4, 6, 7, 255, 0xFFFFFFFF)
    GOOD = (1, 2, 3, 5)

    def test_devlist_speeds(self):
        for sp in self.BAD:
            with self.assertRaises(M.ProtoError, msg=str(sp)):
                devlist(devlist_reply([pack_dev('1-1', speed=sp)]))
        for sp in self.GOOD:
            self.assertEqual(devlist(devlist_reply([pack_dev('1-1', speed=sp)]))[0]['speed'], sp)

    def test_import_speeds(self):
        for sp in self.BAD:
            with self.assertRaises(M.ProtoError, msg=str(sp)):
                do_import('1-1', import_reply(pack_dev('1-1', speed=sp)))
        for sp in self.GOOD:
            self.assertEqual(do_import('1-1', import_reply(pack_dev('1-1', speed=sp)))['speed'], sp)


# ---------------------------------------------------------------- whois / allow-node
class WhoisTests(Base):
    def whois_args(self, who, allow_node=None, allow_user=('user@example',), raw=None):
        jf = self.write('who.json', raw if raw is not None else json.dumps(who))
        script = self.write('whois.py', 'import sys\nsys.stdout.write(open(%r).read())\n' % jf)
        ns = argparse.Namespace(whois_cmd=f'{shlex.quote(sys.executable)} {shlex.quote(script)}',
                                timeout=10.0, allow_user=list(allow_user))
        if allow_node is not None:
            ns.allow_node = allow_node
        return ns

    @staticmethod
    def who(sid='nSTABLE1CNTRL', login='user@example', **node):
        n = dict(ComputedName='phone', **node)
        if sid is not None:
            n['StableID'] = sid
        return dict(Node=n, UserProfile=dict(LoginName=login))

    def test_no_flag_allows_and_reports_ids(self):
        for kw in ({}, dict(allow_node=[])):
            login, info, why = M.whois_check(self.whois_args(self.who(), **kw), PEER)
            self.assertEqual((login, why), ('user@example', None))
            self.assertEqual(info, dict(node='phone', stable_id='nSTABLE1CNTRL'))

    def test_match_allows(self):
        args = self.whois_args(self.who(), allow_node=['nOTHER', 'nSTABLE1CNTRL'])
        self.assertEqual(M.whois_check(args, PEER)[2], None)

    def test_non_match_refuses(self):
        args = self.whois_args(self.who(), allow_node=['nOTHER'])
        login, info, why = M.whois_check(args, PEER)
        self.assertIsNone(login)
        self.assertEqual(why, 'node nSTABLE1CNTRL not in --allow-node')
        self.assertEqual(info['stable_id'], 'nSTABLE1CNTRL')

    def test_missing_or_non_string_stable_id_refuses(self):
        for sid in (None,):
            args = self.whois_args(self.who(sid=sid), allow_node=['nSTABLE1CNTRL'])
            login, info, why = M.whois_check(args, PEER)
            self.assertIsNone(login)
            self.assertIn('not in --allow-node', why)
            self.assertIsNone(info['stable_id'])
        who = self.who()
        who['Node']['StableID'] = 12345
        login, info, why = M.whois_check(self.whois_args(who, allow_node=['12345']), PEER)
        self.assertIsNotNone(why)
        self.assertIsNone(info['stable_id'])

    def test_existing_refusals_still_hold(self):
        self.assertIn('not in --allow-user', M.whois_check(self.whois_args(self.who(login='other@example')), PEER)[2])
        self.assertIn('tagged node', M.whois_check(self.whois_args(self.who(Tags=['tag:x'])), PEER)[2])
        self.assertIn('not JSON', M.whois_check(self.whois_args(None, raw='peer not found'), PEER)[2])
        login, info, why = M.whois_check(self.whois_args({'Node': 1}), PEER)
        self.assertIsNone(info)
        self.assertIn('no Node object', why)

    def test_refuse_login_still_names_node(self):
        login, info, why = M.whois_check(self.whois_args(self.who(login='other@example')), PEER)
        self.assertEqual(info['stable_id'], 'nSTABLE1CNTRL')


# ---------------------------------------------------------------- interface authorisation gate
class IntfAuthTests(Base):
    def hub(self, n, val='0', unit=0):
        return self.write(f'devices/platform/vhci_hcd.{unit}/usb{n}/interface_authorized_default', val)

    def test_no_hubs(self):
        M.SYSFS = self.tmp
        ok, detail = M.intf_auth_armed()
        self.assertFalse(ok)
        self.assertEqual(detail, 'no vhci root hub found')

    def test_two_hubs_zero(self):
        M.SYSFS = self.tmp
        self.hub(1, '0\n')
        self.hub(2, '0\n')
        self.assertTrue(M.intf_auth_armed()[0])

    def test_one_hub_one(self):
        M.SYSFS = self.tmp
        self.hub(1, '0\n')
        bad = self.hub(2, '1\n')
        ok, detail = M.intf_auth_armed()
        self.assertFalse(ok)
        self.assertIn(bad, detail)

    def test_other_values_not_ok(self):
        M.SYSFS = self.tmp
        for val in ('', '00', 'y', '0 1'):
            self.hub(1, val)
            self.assertFalse(M.intf_auth_armed()[0], repr(val))

    def test_unreadable_not_ok(self):
        M.SYSFS = self.tmp
        self.hub(1, '0')
        # a directory where the file should be: open() fails whatever uid runs the test
        os.makedirs(os.path.join(self.tmp, 'devices/platform/vhci_hcd.0/usb2/interface_authorized_default'))
        ok, detail = M.intf_auth_armed()
        self.assertFalse(ok)
        self.assertIn('usb2', detail)

    def test_unrelated_hub_ignored(self):
        M.SYSFS = self.tmp
        self.write('devices/platform/dwc2/usb1/interface_authorized_default', '1')
        self.assertFalse(M.intf_auth_armed()[0])         # still no vhci hub
        self.hub(1, '0')
        self.assertTrue(M.intf_auth_armed()[0])


class ServeGateTests(Base):
    def setUp(self):
        super().setUp()
        M.SYSFS = self.tmp
        M.VHCI = os.path.join(self.tmp, 'vhci_hcd.0')
        self.write('vhci_hcd.0/status', 'hub port sta spd dev      sockfd local_busid\n')
        self.args = argparse.Namespace(list_ttl=30.0, timeout=2.0)
        self.st = M.State()
        self.st.store(PEER, [dict(busid='1-3', speed=3, busnum=1, devnum=2, vid=1, pid=2, nif=0, path='')])

    def test_not_armed_sends_nothing(self):
        self.write('devices/platform/vhci_hcd.0/usb1/interface_authorized_default', '1\n')
        a, b = socket.socketpair()
        self.addCleanup(a.close)
        self.addCleanup(b.close)
        self.assertIsNone(M.serve(self.args, self.st, a, PEER, 1234))
        a.close()                                        # what handle() does after serve returns
        self.assertEqual(read_until_eof(b), b'')
        self.assertNotIn(PEER, self.st.lists)
        r = self.events.of('refuse_import')
        self.assertEqual(len(r), 1)
        self.assertEqual((r[0]['peer'], r[0]['busid']), (PEER, '1-3'))
        self.assertIn('interface_authorized_default', r[0]['reason'])
        self.assertEqual(self.events.of('import'), [])

    def test_no_hub_sends_nothing(self):
        a, b = socket.socketpair()
        self.addCleanup(a.close)
        self.addCleanup(b.close)
        M.serve(self.args, self.st, a, PEER, 1234)
        a.close()
        self.assertEqual(read_until_eof(b), b'')
        self.assertEqual(self.events.of('refuse_import')[0]['reason'], 'no vhci root hub found')

    def test_armed_sends_import_request(self):
        """Control: the same setup with the gate armed does send OP_REQ_IMPORT."""
        self.write('devices/platform/vhci_hcd.0/usb1/interface_authorized_default', '0\n')
        got = {}

        def peer(s):
            got['req'] = M.recv_exact(s, 40, time.monotonic() + 5)

        a, t, _ = serve_once(peer)
        try:
            with self.assertRaises(M.ProtoError):        # the fake peer closes without a reply
                M.serve(self.args, self.st, a, PEER, 1234)
        finally:
            a.close()
            t.join(5)
        self.assertEqual(got['req'][:8], common(M.OP_REQ_IMPORT))
        self.assertEqual(got['req'][8:].rstrip(b'\0'), b'1-3')
        self.assertEqual(self.events.of('refuse_import'), [])

    def test_devlist_path_unaffected(self):
        """No list yet: the devlist cycle runs even though the gate is not armed."""
        self.st.drop(PEER)
        a, t, box = serve_once(fake_devlist_server(devlist_reply([pack_dev('1-3')])))
        try:
            M.serve(self.args, self.st, a, PEER, 1234)
        finally:
            a.close()
            t.join(5)
        self.assertEqual([d['busid'] for d in self.st.lists[PEER][1]], ['1-3'])
        self.assertEqual(self.events.of('refuse_import'), [])


# ---------------------------------------------------------------- post-attach interface report
class ReportTests(Base):
    def setUp(self):
        super().setUp()
        M.SYSFS = self.tmp
        M.IF_POLL_S, M.IF_STEP_S, M.IF_SETTLE_S = 1.0, 0.05, 0.1
        M.IF_HELPER_WAIT_S, M.IF_HELPER_STEP_S = 0.6, 0.02
        M.MARKER_DIR = os.path.join(self.tmp, 'markers')          # never the real /run
        self.devdir = os.path.join(self.tmp, 'devices/platform/vhci_hcd.0/usb1/1-1')

    def mkintf(self, name, **files):
        d = os.path.join(self.devdir, name)
        os.makedirs(d)
        for k, v in files.items():
            with open(os.path.join(d, k), 'w') as f:
                f.write(v)
        return d

    def ids(self, bus='1', dev='4'):
        os.makedirs(self.devdir, exist_ok=True)
        for k, v in (('busnum', bus), ('devnum', dev)):
            with open(os.path.join(self.devdir, k), 'w') as f:
                f.write(v + '\n')

    def marker(self, bus=1, dev=4, name='1-1', **over):
        rec = dict(ev='authorize', device=name, interfaces=[], busnum=bus, devnum=dev)
        rec.update(over)
        os.makedirs(M.MARKER_DIR, exist_ok=True)
        with open(os.path.join(M.MARKER_DIR, name + '.json'), 'w') as f:
            f.write(json.dumps(rec) + '\n')

    def test_reports_new_interfaces_only(self):
        self.mkintf('1-1:1.9', bInterfaceClass='ff\n')
        before = M.vhci_intf_dirs()
        self.assertEqual(len(before), 1)
        self.ids()
        self.marker()

        def later():
            time.sleep(0.2)
            d = self.mkintf('1-1:1.0', bInterfaceClass='08\n', bInterfaceSubClass='06\n',
                            bInterfaceProtocol='50\n', authorized='0\n')
            os.makedirs(d + '/drv')
            os.symlink(d + '/drv', d + '/driver')
        t = threading.Thread(target=later)
        t.start()
        M.report_interfaces(PEER, '1-3', before)
        t.join()
        rows = self.events.of('interfaces')
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]['interfaces'], [dict(name='1-1:1.0', cls='08', subclass='06', protocol='50',
                                                      authorized='0', driver='drv')])
        self.assertEqual(rows[0]['helper'], 'seen')
        self.assertEqual(self.events.of('error'), [])

    def test_marker_for_this_device_is_seen_and_waited_for(self):
        self.ids()
        before = M.vhci_intf_dirs()
        self.mkintf('1-1:1.0', bInterfaceClass='02\n', authorized='0\n')
        M.IF_HELPER_WAIT_S = 3.0

        def later():
            time.sleep(0.4)
            self.marker()
        t = threading.Thread(target=later)
        t0 = time.monotonic()
        t.start()
        M.report_interfaces(PEER, '1-3', before)
        t.join()
        dt = time.monotonic() - t0
        self.assertEqual(self.events.of('interfaces')[0]['helper'], 'seen')
        self.assertGreaterEqual(dt, 0.4)
        self.assertLess(dt, 2.0)

    def test_state_is_read_after_the_marker_not_before(self):
        self.ids()
        before = M.vhci_intf_dirs()
        d = self.mkintf('1-1:1.0', bInterfaceClass='02\n', authorized='0\n')

        def later():
            time.sleep(0.3)
            with open(d + '/authorized', 'w') as f:
                f.write('1\n')
            self.marker()
        t = threading.Thread(target=later)
        t.start()
        M.report_interfaces(PEER, '1-3', before)
        t.join()
        self.assertEqual(self.events.of('interfaces')[0]['interfaces'][0]['authorized'], '1')

    def test_no_marker_is_not_seen_and_says_so(self):
        self.ids()
        before = M.vhci_intf_dirs()
        self.mkintf('1-1:1.0', bInterfaceClass='02\n', authorized='0\n')
        t0 = time.monotonic()
        M.report_interfaces(PEER, '1-3', before)
        self.assertGreaterEqual(time.monotonic() - t0, 0.55)
        row = self.events.of('interfaces')[0]
        self.assertEqual(row['helper'], 'not_seen')
        self.assertEqual(row['interfaces'][0]['authorized'], '0')       # the sysfs state is still logged
        self.assertIn('rule is not live', self.events.of('error')[0]['msg'])

    def test_stale_marker_of_the_same_name_does_not_count(self):
        self.ids(dev='9')
        self.marker(dev=4)              # an earlier attach of 1-1
        before = M.vhci_intf_dirs()
        self.mkintf('1-1:1.0', bInterfaceClass='02\n')
        M.report_interfaces(PEER, '1-3', before)
        self.assertEqual(self.events.of('interfaces')[0]['helper'], 'not_seen')

    def test_unusable_markers_do_not_count(self):
        self.ids()
        os.makedirs(M.MARKER_DIR)
        path = os.path.join(M.MARKER_DIR, '1-1.json')
        cases = {
            'not json': 'garbage\n',
            'a list': '[1, 2]\n',
            'wrong name': json.dumps(dict(ev='authorize', device='1-2', busnum=1, devnum=4)),
            'wrong event': json.dumps(dict(ev='error', device='1-1', busnum=1, devnum=4)),
            'string numbers': json.dumps(dict(ev='authorize', device='1-1', busnum='1', devnum='4')),
            'bool numbers': json.dumps(dict(ev='authorize', device='1-1', busnum=True, devnum=4)),
            'null numbers': json.dumps(dict(ev='authorize', device='1-1', busnum=None, devnum=None)),
            'oversize': ' ' * (M.MARKER_CAP + 10) + json.dumps(dict(ev='authorize', device='1-1', busnum=1, devnum=4)),
        }
        for label, text in cases.items():
            with open(path, 'w') as f:
                f.write(text)
            self.assertFalse(M.helper_marker_seen(self.devdir), label)
        self.marker()
        self.assertTrue(M.helper_marker_seen(self.devdir))

    def test_device_without_readable_ids_is_not_seen(self):
        self.marker()
        os.makedirs(self.devdir)
        self.assertFalse(M.helper_marker_seen(self.devdir))
        with open(self.devdir + '/busnum', 'w') as f:
            f.write('x\n')
        self.assertFalse(M.helper_marker_seen(self.devdir))

    def test_no_interfaces_means_no_wait(self):
        t0 = time.monotonic()
        M.IF_POLL_S = 0.1
        M.IF_HELPER_WAIT_S = 3.0
        M.report_interfaces(PEER, '1-3', set())
        self.assertLess(time.monotonic() - t0, 1.0)
        self.assertEqual(self.events.of('interfaces')[0]['helper'], 'not_seen')
        self.assertEqual(self.events.of('error'), [])

    def test_missing_fields_are_null(self):
        before = M.vhci_intf_dirs()
        self.mkintf('1-1:1.0', bInterfaceClass='03\n')
        M.report_interfaces(PEER, '1-3', before)
        i = self.events.of('interfaces')[0]['interfaces'][0]
        self.assertEqual((i['cls'], i['subclass'], i['protocol'], i['authorized'], i['driver']),
                         ('03', None, None, None, None))

    def test_nothing_appears_logs_empty(self):
        t0 = time.monotonic()
        M.report_interfaces(PEER, '1-3', set())
        self.assertGreaterEqual(time.monotonic() - t0, 0.9)
        self.assertEqual(self.events.of('interfaces')[0]['interfaces'], [])

    def test_never_raises(self):
        def boom():
            raise RuntimeError('boom')
        saved = M.vhci_intf_dirs
        M.vhci_intf_dirs = boom
        try:
            M.report_interfaces(PEER, '1-3', set())
        finally:
            M.vhci_intf_dirs = saved
        self.assertIn('interface report', self.events.of('error')[0]['msg'])


# ---------------------------------------------------------------- mutual authentication
def box_thread(key, timeout=2.0):
    return serve_once(lambda s: M.box_auth(s, key, timeout))


class AuthTests(Base):
    def test_good_key_both_ways(self):
        a, t, box = box_thread(KEY)
        M.phone_auth(a, KEY, 2)
        t.join(5)
        self.assertNotIn('exc', box)
        a.close()

    def test_wrong_phone_key_box_sends_nothing_after_hello(self):
        a, t, box = box_thread(KEY)
        with self.assertRaises(M.ProtoError):            # no tag_b ever comes: the box closes
            M.phone_auth(a, WRONG, 2)
        t.join(5)
        self.assertIsInstance(box['exc'], M.AuthError)
        self.assertEqual(read_until_eof(a), b'')         # the hello was consumed, nothing else was sent
        a.close()

    def test_exactly_40_bytes_then_eof(self):
        a, t, box = box_thread(KEY)
        hello = M.recv_exact(a, 40, time.monotonic() + 2)
        self.assertEqual(hello[:8], b'UDIALIN1')
        a.sendall(os.urandom(32) + os.urandom(32))
        t.join(5)
        self.assertIsInstance(box['exc'], M.AuthError)
        self.assertEqual(read_until_eof(a), b'')
        a.close()

    def test_phone_rejects_wrong_tag_b(self):
        def fake_box(s):
            s.sendall(M.AUTH_MAGIC + os.urandom(32))
            M.recv_exact(s, 64, time.monotonic() + 2)
            s.sendall(os.urandom(32))
        a, t, _ = serve_once(fake_box)
        with self.assertRaises(M.AuthError):
            M.phone_auth(a, KEY, 2)
        t.join(5)
        a.close()

    def test_phone_rejects_box_with_other_key(self):
        a, t, box = box_thread(WRONG)
        with self.assertRaises(M.ProtoError):
            M.phone_auth(a, KEY, 2)
        t.join(5)
        a.close()

    def test_phone_rejects_bad_magic(self):
        a, t, _ = serve_once(lambda s: s.sendall(b'NOTMAGIC' + os.urandom(32)))
        with self.assertRaises(M.ProtoError):
            M.phone_auth(a, KEY, 2)
        t.join(5)
        a.close()

    def test_truncated_phone_times_out(self):
        a, t, box = box_thread(KEY, timeout=0.5)
        M.recv_exact(a, 40, time.monotonic() + 2)
        a.sendall(b'x' * 10)                             # short, then silence (socket stays open)
        t0 = time.monotonic()
        t.join(5)
        self.assertLess(time.monotonic() - t0, 2.0)
        self.assertIsInstance(box['exc'], M.ProtoError)
        self.assertNotIsInstance(box['exc'], M.AuthError)
        a.close()

    def test_silent_phone_times_out(self):
        a, t, box = box_thread(KEY, timeout=0.5)
        t0 = time.monotonic()
        t.join(5)
        self.assertLess(time.monotonic() - t0, 2.0)
        self.assertIn('timed out', str(box['exc']))
        a.close()

    def test_silent_box_times_out_on_phone(self):
        a, b = socket.socketpair()
        t0 = time.monotonic()
        with self.assertRaises(M.ProtoError):
            M.phone_auth(a, KEY, 0.5)
        self.assertLess(time.monotonic() - t0, 2.0)
        a.close()
        b.close()

    def test_nonce_reflection_refused(self):
        """A valid-looking tag over nonce_b || nonce_b must still be refused."""
        def reflector(s):
            hello = M.recv_exact(s, 40, time.monotonic() + 2)
            nb = hello[8:]
            tag = hmac.new(KEY, b'usbip-dialin v1 phone' + nb + nb, hashlib.sha256).digest()
            s.sendall(nb + tag)
            return read_until_eof(s, 1.0)
        c, d = socket.socketpair()
        res = {}
        pt = threading.Thread(target=lambda: res.setdefault('after', reflector(d)))
        pt.start()
        with self.assertRaises(M.AuthError):
            M.box_auth(c, KEY, 2)
        c.close()
        pt.join(5)
        d.close()
        self.assertEqual(res['after'], b'')

    def test_tags_are_label_separated(self):
        """The box tag cannot be replayed as a phone tag: the labels differ."""
        nb, np_ = b'\1' * 32, b'\2' * 32
        self.assertNotEqual(M._tag(KEY, M.AUTH_LABEL_PHONE, nb, np_), M._tag(KEY, M.AUTH_LABEL_BOX, nb, np_))
        self.assertEqual(M._tag(KEY, M.AUTH_LABEL_PHONE, nb, np_),
                         hmac.new(KEY, b'usbip-dialin v1 phone' + nb + np_, hashlib.sha256).digest())


class PskFileTests(Base):
    GOOD = '00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff'

    def test_good_files(self):
        for text in (self.GOOD, self.GOOD + '\n', self.GOOD.upper()):
            p = self.write('psk', text, 0o600)
            self.assertEqual(M.load_psk(p), bytes.fromhex(self.GOOD))
        p = self.write('psk', self.GOOD, 0o400)
        self.assertEqual(len(M.load_psk(p)), 32)

    def test_loose_modes_rejected(self):
        for mode in (0o644, 0o640, 0o604, 0o660, 0o666, 0o700 | 0o010):
            p = self.write('psk', self.GOOD, mode)
            with self.assertRaises(ValueError, msg=oct(mode)) as cm:
                M.load_psk(p)
            self.assertNotIn(self.GOOD[:8], str(cm.exception))

    def test_bad_content_rejected(self):
        bad = {'63 hex': self.GOOD[:-1], '65 hex': self.GOOD + '0', 'non-hex': 'g' + self.GOOD[1:],
               'empty': '', 'two newlines': self.GOOD + '\n\n', 'crlf': self.GOOD + '\r\n',
               'leading space': ' ' + self.GOOD, 'two lines': self.GOOD + '\n' + self.GOOD}
        for name, text in bad.items():
            p = self.write('psk', text, 0o600)
            with self.assertRaises(ValueError, msg=name) as cm:
                M.load_psk(p)
            self.assertNotIn(self.GOOD[:8], str(cm.exception))

    def test_not_a_regular_file(self):
        d = os.path.join(self.tmp, 'adir')
        os.mkdir(d, 0o700)
        with self.assertRaises(ValueError):
            M.load_psk(d)
        with self.assertRaises(ValueError):
            M.load_psk(os.path.join(self.tmp, 'missing'))


# ---------------------------------------------------------------- handle(): the whole connection path on loopback
class HandleTests(Base):
    def setUp(self):
        super().setUp()
        M.SYSFS = self.tmp                               # no hub: any import is refused, only the devlist cycle runs
        M.VHCI = os.path.join(self.tmp, 'vhci_hcd.0')
        self.write('vhci_hcd.0/status', 'hub port sta spd dev      sockfd local_busid\n')
        who = dict(Node=dict(ComputedName='pixel', StableID='nSTABLE1CNTRL'), UserProfile=dict(LoginName='user@example'))
        jf = self.write('who.json', json.dumps(who))
        script = self.write('whois.py', 'import sys\nsys.stdout.write(open(%r).read())\n' % jf)
        self.args = argparse.Namespace(
            whois_cmd=f'{shlex.quote(sys.executable)} {shlex.quote(script)}', timeout=2.0,
            allow_user=['user@example'], allow_node=[], list_ttl=30.0, auth_key=None)
        self.st = M.State()
        self.srv = socket.socket()
        self.srv.bind(('127.0.0.1', 0))
        self.srv.listen(4)
        self.addCleanup(self.srv.close)

    def connect(self):
        """(client socket, handler thread) for one handle() run on the accepted end."""
        c = socket.create_connection(self.srv.getsockname())
        conn, addr = self.srv.accept()
        self.st.active = 1
        t = threading.Thread(target=M.handle, args=(self.args, self.st, conn, addr), daemon=True)
        t.start()
        self.addCleanup(c.close)
        return c, t

    def test_allow_line_carries_node_and_stable_id(self):
        c, t = self.connect()
        self.assertEqual(M.recv_exact(c, 8, time.monotonic() + 3), common(M.OP_REQ_DEVLIST))
        c.close()
        t.join(5)
        allow = self.events.of('allow')[0]
        self.assertEqual((allow['login'], allow['node'], allow['stable_id']), ('user@example', 'pixel', 'nSTABLE1CNTRL'))

    def test_refuse_line_carries_node_and_stable_id(self):
        self.args.allow_node = ['nOTHER']
        c, t = self.connect()
        self.assertEqual(read_until_eof(c), b'')
        t.join(5)
        r = self.events.of('refuse')[0]
        self.assertEqual((r['node'], r['stable_id']), ('pixel', 'nSTABLE1CNTRL'))
        self.assertEqual(r['reason'], 'node nSTABLE1CNTRL not in --allow-node')

    def test_psk_good_then_devlist(self):
        self.args.auth_key = KEY
        c, t = self.connect()
        M.phone_auth(c, KEY, 3)
        self.assertEqual(M.recv_exact(c, 8, time.monotonic() + 3), common(M.OP_REQ_DEVLIST))
        c.close()
        t.join(5)
        self.assertEqual(len(self.events.of('auth')), 1)
        self.assertEqual(self.events.of('refuse'), [])

    def test_psk_wrong_key_refused(self):
        self.args.auth_key = KEY
        c, t = self.connect()
        with self.assertRaises(M.ProtoError):
            M.phone_auth(c, WRONG, 3)
        self.assertEqual(read_until_eof(c), b'')
        t.join(5)
        self.assertEqual(self.events.of('refuse')[0]['reason'], 'auth failed')
        self.assertEqual(self.events.of('auth'), [])
        self.assertEqual(self.events.of('list'), [])

    def test_no_psk_stream_unchanged(self):
        c, t = self.connect()
        # the first bytes are OP_REQ_DEVLIST: no auth hello
        self.assertEqual(M.recv_exact(c, 8, time.monotonic() + 3), common(M.OP_REQ_DEVLIST))
        c.sendall(devlist_reply([pack_dev('1-3')]))
        c.close()
        t.join(5)
        self.assertEqual(self.events.of('list')[0]['count'], 1)
        self.assertEqual(self.events.of('auth'), [])


if __name__ == '__main__':
    unittest.main(verbosity=1)
