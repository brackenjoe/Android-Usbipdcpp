#!/usr/bin/env python3
"""Offline tests for usbip_authorize.py against a fake sysfs tree and a fake
modules directory whose entries mirror real Linux v6.8 alias tables.

    python3 -I usbip_authorize_test.py -v
"""
import contextlib
import io
import json
import os
import sys
import tempfile
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import usbip_authorize as ua  # noqa: E402

VHCI_USB = 'devices/platform/vhci_hcd.0/usb5'

ANY = 'd*dc*dsc*dp*'
MODULES_ALIAS = '\n'.join([
    '# Aliases extracted from modules themselves.',
    'alias usb:v*p*%sic02isc02ip00in* cdc_acm' % ANY,
    'alias usb:v*p*%sic02isc02ip01in* cdc_acm' % ANY,
    'alias usb:v*p*%sic02isc02ip02in* cdc_acm' % ANY,
    'alias usb:v*p*%sic02isc02ip03in* cdc_acm' % ANY,
    'alias usb:v*p*%sic02isc02ip04in* cdc_acm' % ANY,
    'alias usb:v*p*%sic02isc02ip05in* cdc_acm' % ANY,
    'alias usb:v*p*%sic02isc02ip06in* cdc_acm' % ANY,
    'alias usb:v*p*%sic08isc06ip50in* usb_storage' % ANY,
    'alias usb:v*p*%sic0Eisc01ip00in* uvcvideo' % ANY,
    'alias usb:v*p*%sic01isc01ip*in* snd_usb_audio' % ANY,
    'alias usb:v10C4pEA60%sic*isc*ip*in* cp210x' % ANY,
    'alias usb:v0BDApA153%sicFFisc*ip*in* r8152' % ANY,
    'alias usb:v0BDAp8153%sicFFisc*ip*in* r8152' % ANY,
    'alias usb:v0BDAp8176%sicFFisc*ip*in* rtl8xxxu' % ANY,
    'alias usb:v*p*%sicE0isc01ip01in* btusb' % ANY,
    'alias usb:v*p*%sic02isc06ip00in* cdc_ether' % ANY,
    'alias usb:v*p*%sic02isc02ipFFin* rndis_host' % ANY,
    'alias usb:v*p*%sicEFisc04ip01in* rndis_host' % ANY,
    'alias usb:vDEADpBEEF%sic*isc*ip*in* ghost' % ANY,
    'alias pci:v000010ECd00008168sv*sd*bc*sc*i* r8169',
    'alias usb:v1234p5678%sicFFisc*ip*in* hid-quirk' % ANY,
    '',
])
MODULES_BUILTIN_MODINFO = '\0'.join([
    'usbhid.alias=usb:v*p*%sic03isc*ip*in*' % ANY,
    'usbhid.license=GPL',
    'hid_quirk.alias=usb:v1234p5678%sicFFisc*ip*in*' % ANY,
]) + '\0'
MODULES_DEP = '\n'.join([
    'kernel/drivers/usb/class/cdc-acm.ko.zst:',
    'kernel/drivers/usb/storage/usb-storage.ko.zst:',
    'kernel/drivers/media/usb/uvc/uvcvideo.ko.zst: kernel/drivers/media/mc/mc.ko.zst',
    'kernel/sound/usb/snd-usb-audio.ko.zst: kernel/sound/core/snd-pcm.ko.zst',
    'kernel/drivers/usb/serial/cp210x.ko.zst: kernel/drivers/usb/serial/usbserial.ko.zst',
    'kernel/drivers/net/usb/r8152.ko.zst: kernel/drivers/net/mii.ko.zst',
    'kernel/drivers/net/wireless/realtek/rtl8xxxu/rtl8xxxu.ko.zst:',
    'kernel/drivers/bluetooth/btusb.ko.zst:',
    'kernel/drivers/net/usb/cdc_ether.ko.zst:',
    'kernel/drivers/net/usb/rndis_host.ko.zst:',
    '',
])
MODULES_BUILTIN = '\n'.join([
    'kernel/drivers/hid/usbhid/usbhid.ko',
    'kernel/drivers/hid/hid-quirk.ko',
    '',
])


def make_modules(d, omit=()):
    files = {'modules.alias': MODULES_ALIAS,
             'modules.builtin.modinfo': MODULES_BUILTIN_MODINFO,
             'modules.dep': MODULES_DEP,
             'modules.builtin': MODULES_BUILTIN}
    os.makedirs(d, exist_ok=True)
    for n, text in files.items():
        if n not in omit:
            with open(os.path.join(d, n), 'w') as f:
                f.write(text)


def modalias(vid, pid, spec):
    cls, sub, proto = spec
    return 'usb:v%sp%sd0100dc02dsc00dp00ic%sisc%sip%sin00' % (
        vid.upper(), pid.upper(), cls.upper(), sub.upper(), proto.upper())


class Fake:
    """A fake sysfs root (drivers_probe is a plain file) and a device builder."""

    def __init__(self, root):
        self.root = root
        os.makedirs(os.path.join(root, 'bus/usb'))
        open(os.path.join(root, 'bus/usb/drivers_probe'), 'w').close()

    def device(self, name, vid, pid, ifaces, auth='0', parent=VHCI_USB):
        d = os.path.join(self.root, parent, name)
        os.makedirs(d)
        self.put(d, 'idVendor', vid)
        self.put(d, 'idProduct', pid)
        self.put(d, 'busnum', '5')
        self.put(d, 'devnum', '7')
        for n, spec in enumerate(ifaces):
            i = os.path.join(d, '%s:1.%d' % (name, n))
            os.makedirs(i)
            self.put(i, 'bInterfaceClass', spec[0])
            self.put(i, 'bInterfaceSubClass', spec[1])
            self.put(i, 'bInterfaceProtocol', spec[2])
            self.put(i, 'modalias', modalias(vid, pid, spec))
            self.put(i, 'authorized', auth)
        return d

    @staticmethod
    def put(d, attr, value):
        with open(os.path.join(d, attr), 'w') as f:
            f.write(value if len(value) > 8 else value + '\n')

    def get(self, d, attr):
        with open(os.path.join(d, attr)) as f:
            return f.read()


class Base(unittest.TestCase):
    def setUp(self):
        td = tempfile.TemporaryDirectory()
        self.addCleanup(td.cleanup)
        self.root = os.path.realpath(td.name)
        self.mods = os.path.join(self.root, 'modules')
        make_modules(self.mods)
        self.fake = Fake(os.path.join(self.root, 'sys'))
        self.marker_dir = os.path.join(self.root, 'run-usbip-authorize')   # never the real /run
        self.writes = []
        real_write = ua.write_attr

        def spy(path, data):
            self.writes.append((os.path.relpath(path, self.fake.root), data))
            return real_write(path, data)

        p = mock.patch.object(ua, 'write_attr', spy)
        p.start()
        self.addCleanup(p.stop)
        p = mock.patch.object(ua.syslog, 'syslog')
        self.syslog = p.start()
        self.addCleanup(p.stop)
        p = mock.patch.object(ua.syslog, 'openlog')
        p.start()
        self.addCleanup(p.stop)

    def run_helper(self, path, *extra):
        out = io.StringIO()
        args = [path, '--sysfs-root', self.fake.root, '--modules-dir', self.mods,
                '--marker-dir', self.marker_dir]
        with contextlib.redirect_stdout(out):
            code = ua.main(args + list(extra))
        lines = out.getvalue().splitlines()
        rec = json.loads(lines[-1]) if lines else None
        return code, rec

    def check(self, vid, pid, ifaces, **kw):
        d = self.fake.device('5-1', vid, pid, ifaces, **kw)
        code, rec = self.run_helper(d)
        self.assertEqual(code, 0)
        return d, rec

    @staticmethod
    def decisions(rec):
        return {i['if']: i['decision'] for i in rec['interfaces']}

    def iface(self, rec, name):
        return next(i for i in rec['interfaces'] if i['if'] == name)

    def assertNoWrites(self):
        self.assertEqual(self.writes, [])

    def authorize_writes(self):
        return [os.path.basename(os.path.dirname(w[0])) for w in self.writes
                if w[0].endswith('/authorized')]

    def probe_writes(self):
        return [w[1] for w in self.writes if w[0] == 'bus/usb/drivers_probe']

    def assertAuthThenProbe(self, auth_names, probe_names):
        """Authorize writes (all '1') in this order, then exactly these probes,
        every authorize before the first probe."""
        self.assertEqual(self.authorize_writes(), auth_names)
        self.assertTrue(all(w[1] == '1' for w in self.writes
                            if w[0].endswith('/authorized')))
        self.assertEqual(self.probe_writes(), probe_names)
        kinds = ['a' if w[0].endswith('/authorized') else 'p' for w in self.writes]
        self.assertEqual(kinds, sorted(kinds))      # all 'a' before any 'p'


class Classification(Base):
    def test_ardupilot_fc(self):
        d, rec = self.check('1209', '5740', [('02', '02', '01'), ('0a', '00', '00')])
        self.assertEqual(rec['vidpid'], '1209:5740')
        self.assertEqual(self.decisions(rec), {'5-1:1.0': 'allow', '5-1:1.1': 'allow'})
        self.assertEqual(self.iface(rec, '5-1:1.0')['candidates'], ['cdc_acm'])
        self.assertEqual(self.iface(rec, '5-1:1.1')['candidates'], [])
        # the data interface is authorized, not probed
        self.assertAuthThenProbe(['5-1:1.0', '5-1:1.1'], ['5-1:1.0'])
        self.assertEqual(rec['probed'], ['5-1:1.0'])
        for n in ('5-1:1.0', '5-1:1.1'):
            self.assertEqual(self.fake.get(os.path.join(d, n), 'authorized').strip(), '1')

    def test_two_cdc_functions(self):
        ifs = [('02', '02', '01'), ('0a', '00', '00')] * 2
        _, rec = self.check('1209', '5740', ifs)
        self.assertEqual(set(self.decisions(rec).values()), {'allow'})
        self.assertAuthThenProbe(['5-1:1.%d' % n for n in range(4)], ['5-1:1.0', '5-1:1.2'])

    def test_composite_fc_with_hid(self):
        _, rec = self.check('1209', '5740',
                            [('02', '02', '01'), ('0a', '00', '00'), ('03', '00', '00')])
        self.assertEqual(self.decisions(rec), {'5-1:1.0': 'allow', '5-1:1.1': 'allow',
                                               '5-1:1.2': 'deny'})
        self.assertAuthThenProbe(['5-1:1.0', '5-1:1.1'], ['5-1:1.0'])

    def test_keyboard_denied(self):
        _, rec = self.check('046d', 'c31c', [('03', '01', '01')])
        i = self.iface(rec, '5-1:1.0')
        self.assertEqual((i['decision'], i['cls']), ('deny', '03/01/01'))
        self.assertIn('class 03', i['why'])
        self.assertNoWrites()

    def test_keyboard_denied_by_driver_path_alone(self):
        # Attributes claim a harmless class but the modalias says HID: usbhid is
        # a candidate and lives under kernel/drivers/hid/.
        d = self.fake.device('5-1', '046d', 'c31c', [('ff', '00', '00')])
        self.fake.put(d + '/5-1:1.0', 'modalias', modalias('046d', 'c31c', ('03', '01', '01')))
        _, rec = self.run_helper(d)
        i = self.iface(rec, '5-1:1.0')
        self.assertEqual(i['candidates'], ['usbhid'])
        self.assertEqual(i['decision'], 'deny')
        self.assertIn('kernel/drivers/hid/', i['why'])
        self.assertNoWrites()

    def test_mass_storage_allowed(self):
        _, rec = self.check('0781', '5581', [('08', '06', '50')])
        self.assertEqual(self.iface(rec, '5-1:1.0')['candidates'], ['usb_storage'])
        self.assertAuthThenProbe(['5-1:1.0'], ['5-1:1.0'])

    def test_uvc_camera_allowed(self):
        _, rec = self.check('046d', '0825', [('0e', '01', '00'), ('0e', '02', '00')])
        self.assertEqual(self.decisions(rec)['5-1:1.0'], 'allow')
        self.assertEqual(self.iface(rec, '5-1:1.0')['candidates'], ['uvcvideo'])
        self.assertIn('5-1:1.0', self.probe_writes())

    def test_audio_denied_interim(self):
        _, rec = self.check('0d8c', '0014', [('01', '01', '00')])
        i = self.iface(rec, '5-1:1.0')
        self.assertEqual(i['decision'], 'deny')
        self.assertIn('interim', i['why'])
        self.assertNoWrites()

    def test_audio_denied_by_sound_dir_alone(self):
        # Drop the class entry: the driver layer still denies via kernel/sound/.
        d = self.fake.device('5-1', '0d8c', '0014', [('01', '01', '00')])
        with mock.patch.object(ua, 'INTERIM_DENY_CLASSES', {}):
            _, rec = self.run_helper(d)
        i = self.iface(rec, '5-1:1.0')
        self.assertEqual(i['candidates'], ['snd_usb_audio'])
        self.assertEqual(i['decision'], 'deny')
        self.assertIn('kernel/sound/', i['why'])
        self.assertNoWrites()

    def test_cp2102_allowed(self):
        _, rec = self.check('10c4', 'ea60', [('ff', '00', '00')])
        self.assertEqual(self.iface(rec, '5-1:1.0')['candidates'], ['cp210x'])
        self.assertAuthThenProbe(['5-1:1.0'], ['5-1:1.0'])

    def test_realtek_ethernet_denied_by_driver_path(self):
        _, rec = self.check('0bda', '8153', [('ff', 'ff', '00')])
        i = self.iface(rec, '5-1:1.0')
        self.assertEqual(i['candidates'], ['r8152'])
        self.assertEqual(i['decision'], 'deny')
        self.assertIn('kernel/drivers/net/', i['why'])
        self.assertNoWrites()

    def test_wifi_dongle_denied(self):
        _, rec = self.check('0bda', '8176', [('ff', 'ff', 'ff')])
        i = self.iface(rec, '5-1:1.0')
        self.assertEqual(i['candidates'], ['rtl8xxxu'])
        self.assertEqual(i['decision'], 'deny')
        self.assertNoWrites()

    def test_bluetooth_denied(self):
        _, rec = self.check('8087', '0a2b', [('e0', '01', '01'), ('e0', '01', '01')])
        self.assertEqual(set(self.decisions(rec).values()), {'deny'})
        self.assertNoWrites()

    def test_bluetooth_denied_by_driver_path_alone(self):
        d = self.fake.device('5-1', '8087', '0a2b', [('ff', '00', '00')])
        self.fake.put(d + '/5-1:1.0', 'modalias', modalias('8087', '0a2b', ('e0', '01', '01')))
        _, rec = self.run_helper(d)
        i = self.iface(rec, '5-1:1.0')
        self.assertEqual(i['candidates'], ['btusb'])
        self.assertIn('kernel/drivers/bluetooth/', i['why'])
        self.assertNoWrites()

    def test_cdc_ecm_control_denied_data_authorized_only(self):
        # The data interface alone is authorized and has no candidate driver
        # (nothing claims it without its control interface). Acceptable: the
        # network device never appears.
        _, rec = self.check('0525', 'a4a2', [('02', '06', '00'), ('0a', '00', '00')])
        self.assertEqual(self.decisions(rec), {'5-1:1.0': 'deny', '5-1:1.1': 'allow'})
        self.assertAuthThenProbe(['5-1:1.1'], [])

    def test_non_acm_cdc_subclasses_denied(self):
        for n, sub in enumerate(('01', '03', '06', '09', '0b', '0c', '0d')):
            with self.subTest(sub=sub):
                d = self.fake.device('5-%d' % (n + 1), '0525', 'a4a2', [('02', sub, '00')])
                _, rec = self.run_helper(d)
                self.assertEqual(rec['interfaces'][0]['decision'], 'deny')
        self.assertNoWrites()

    def test_rndis_forms_denied(self):
        for n, spec in enumerate((('02', '02', 'ff'), ('ef', '04', '01'), ('ef', '01', '01'))):
            with self.subTest(spec=spec):
                d = self.fake.device('5-%d' % (n + 1), '0525', 'a4a2', [spec])
                _, rec = self.run_helper(d)
                self.assertEqual(rec['interfaces'][0]['decision'], 'deny')
        self.assertNoWrites()

    def test_rndis_denied_by_driver_path_alone(self):
        for n, spec in enumerate((('02', '02', 'ff'), ('ef', '04', '01'))):
            with self.subTest(spec=spec):
                d = self.fake.device('5-%d' % (n + 1), '0525', 'a4a2', [('ff', '00', '00')])
                self.fake.put(d + '/5-%d:1.0' % (n + 1), 'modalias',
                              modalias('0525', 'a4a2', spec))
                _, rec = self.run_helper(d)
                i = rec['interfaces'][0]
                self.assertEqual(i['candidates'], ['rndis_host'])
                self.assertEqual(i['decision'], 'deny')
        self.assertNoWrites()

    def test_acm_control_with_odd_protocol_is_not_class_denied(self):
        # 02/02/07 matches no cdc_acm entry: authorized, nothing to probe.
        _, rec = self.check('1209', '5740', [('02', '02', '07')])
        i = rec['interfaces'][0]
        self.assertEqual((i['decision'], i['candidates']), ('allow', []))
        self.assertAuthThenProbe(['5-1:1.0'], [])

    def test_sdr_without_candidates_authorized_not_probed(self):
        _, rec = self.check('1d50', '6089', [('ff', 'ff', 'ff')])
        i = rec['interfaces'][0]
        self.assertEqual((i['decision'], i['candidates']), ('allow', []))
        self.assertAuthThenProbe(['5-1:1.0'], [])
        self.assertEqual(rec['probed'], [])

    def test_unknown_module_denied(self):
        _, rec = self.check('dead', 'beef', [('ff', '00', '00')])
        i = rec['interfaces'][0]
        self.assertEqual(i['candidates'], ['ghost'])
        self.assertEqual(i['decision'], 'deny')
        self.assertIn('no known path', i['why'])
        self.assertNoWrites()

    def test_builtin_candidate_under_hid_denied(self):
        _, rec = self.check('1234', '5678', [('ff', '00', '00')])
        i = rec['interfaces'][0]
        self.assertEqual(i['candidates'], ['hid_quirk'])
        self.assertEqual(i['decision'], 'deny')
        self.assertIn('kernel/drivers/hid/', i['why'])
        self.assertNoWrites()

    def test_one_denied_candidate_denies_the_interface(self):
        # r8152 plus a harmless extra module on the same alias set.
        with open(os.path.join(self.mods, 'modules.alias'), 'a') as f:
            f.write('alias usb:v0BDAp8153%sicFFisc*ip*in* cdc_acm\n' % ANY)
        _, rec = self.check('0bda', '8153', [('ff', 'ff', '00')])
        self.assertEqual(self.iface(rec, '5-1:1.0')['candidates'], ['cdc_acm', 'r8152'])
        self.assertEqual(rec['interfaces'][0]['decision'], 'deny')

    def test_nested_device_under_hub(self):
        d = self.fake.device('5-1.2', '1209', '5740', [('02', '02', '01')],
                             parent=VHCI_USB + '/5-1')
        code, rec = self.run_helper(d)
        self.assertEqual(code, 0)
        self.assertEqual(self.decisions(rec), {'5-1.2:1.0': 'allow'})


class ModulesTables(unittest.TestCase):
    def test_path_suffixes_and_dashes(self):
        with tempfile.TemporaryDirectory() as td:
            make_modules(td)
            with open(os.path.join(td, 'modules.dep'), 'a') as f:
                f.write('kernel/drivers/a/x-y.ko.xz:\nkernel/drivers/b/z.ko.gz: q\n'
                        'kernel/drivers/c/w.ko:\n')
            m = ua.Modules(td)
            self.assertIsNone(m.error)
            self.assertEqual(m.paths['cdc_acm'], ['kernel/drivers/usb/class/cdc-acm.ko.zst'])
            self.assertEqual(m.paths['x_y'], ['kernel/drivers/a/x-y.ko.xz'])
            self.assertEqual(m.paths['z'], ['kernel/drivers/b/z.ko.gz'])
            self.assertEqual(m.paths['w'], ['kernel/drivers/c/w.ko'])
            self.assertEqual(m.paths['usbhid'], ['kernel/drivers/hid/usbhid/usbhid.ko'])
            self.assertEqual(m.paths['usb_storage'],
                             ['kernel/drivers/usb/storage/usb-storage.ko.zst'])

    def test_only_usb_aliases_kept(self):
        with tempfile.TemporaryDirectory() as td:
            make_modules(td)
            m = ua.Modules(td)
            self.assertTrue(all(p.startswith('usb:') for p, _ in m.aliases))
            self.assertNotIn('r8169', [mod for _, mod in m.aliases])
            self.assertIn('usbhid', [mod for _, mod in m.aliases])

    def test_file_cap_fails_closed(self):
        with tempfile.TemporaryDirectory() as td:
            make_modules(td)
            with mock.patch.object(ua, 'FILE_CAP', 100):
                m = ua.Modules(td)
            self.assertIsNotNone(m.error)
            self.assertEqual(m.aliases, [])


class FailClosed(Base):
    def test_each_missing_modules_file_denies_everything(self):
        for n in ua.MODULES_FILES:
            with self.subTest(missing=n):
                self.writes.clear()
                d = self.fake.device('5-%d' % (ua.MODULES_FILES.index(n) + 1), '1209', '5740',
                                     [('02', '02', '01'), ('0a', '00', '00')])
                os.rename(os.path.join(self.mods, n), os.path.join(self.mods, n + '.gone'))
                try:
                    code, rec = self.run_helper(d)
                finally:
                    os.rename(os.path.join(self.mods, n + '.gone'), os.path.join(self.mods, n))
                self.assertEqual(code, 0)
                self.assertIn(n, rec['modules_error'])
                self.assertEqual(set(self.decisions(rec).values()), {'deny'})
                self.assertIn('modules unavailable', rec['interfaces'][0]['why'])
                self.assertNoWrites()

    def test_missing_modules_dir(self):
        d = self.fake.device('5-1', '10c4', 'ea60', [('ff', '00', '00')])
        self.mods = os.path.join(self.root, 'nonexistent')
        code, rec = self.run_helper(d)
        self.assertEqual(code, 0)
        self.assertTrue(rec['modules_error'])
        self.assertEqual(self.decisions(rec), {'5-1:1.0': 'deny'})
        self.assertNoWrites()

    def test_class_deny_does_not_need_modules(self):
        d = self.fake.device('5-1', '046d', 'c31c', [('03', '01', '01')])
        self.mods = os.path.join(self.root, 'nonexistent')
        _, rec = self.run_helper(d)
        self.assertIn('class 03', rec['interfaces'][0]['why'])


class Malformed(Base):
    def _class(self, text):
        d = self.fake.device('5-1', '1209', '5740', [('02', '02', '01'), ('0a', '00', '00')])
        self.fake.put(d + '/5-1:1.0', 'bInterfaceClass', text)
        code, rec = self.run_helper(d)
        self.assertEqual(code, 0)
        self.assertEqual(self.decisions(rec), {'5-1:1.0': 'deny', '5-1:1.1': 'allow'})
        self.assertEqual(self.iface(rec, '5-1:1.0')['cls'], '?')
        self.assertAuthThenProbe(['5-1:1.1'], [])

    def test_class_garbage(self):
        self._class('zz')

    def test_class_empty(self):
        self._class('')

    def test_class_oversize(self):
        self._class('0' * 300)

    def _modalias(self, text):
        d = self.fake.device('5-1', '1209', '5740', [('02', '02', '01')])
        self.fake.put(d + '/5-1:1.0', 'modalias', text)
        code, rec = self.run_helper(d)
        self.assertEqual(code, 0)
        self.assertEqual(rec['interfaces'][0]['decision'], 'deny')
        self.assertLessEqual(len(rec['interfaces'][0]['modalias']), ua.MODALIAS_LOG)
        self.assertNoWrites()

    def test_modalias_garbage(self):
        self._modalias('zz')

    def test_modalias_empty(self):
        self._modalias('')

    def test_modalias_oversize(self):
        self._modalias('usb:' + 'A' * 300)

    def test_modalias_with_glob_characters(self):
        self._modalias('usb:v*p*d*dc*dsc*dp*ic*isc*ip*in*')

    def test_modalias_missing(self):
        d = self.fake.device('5-1', '1209', '5740', [('02', '02', '01')])
        os.remove(d + '/5-1:1.0/modalias')
        _, rec = self.run_helper(d)
        self.assertEqual(rec['interfaces'][0]['decision'], 'deny')
        self.assertNoWrites()

    def test_missing_class_attribute(self):
        d = self.fake.device('5-1', '1209', '5740', [('02', '02', '01')])
        os.remove(d + '/5-1:1.0/bInterfaceProtocol')
        _, rec = self.run_helper(d)
        self.assertEqual(rec['interfaces'][0]['decision'], 'deny')
        self.assertNoWrites()

    def test_malformed_vidpid_only_affects_log(self):
        d = self.fake.device('5-1', '10c4', 'ea60', [('ff', '00', '00')])
        self.fake.put(d, 'idVendor', 'zzzz')
        _, rec = self.run_helper(d)
        self.assertEqual(rec['vidpid'], '?:ea60')
        self.assertEqual(rec['interfaces'][0]['decision'], 'allow')


class Refusal(Base):
    def test_outside_vhci(self):
        d = self.fake.device('1-1', '1209', '5740', [('02', '02', '01')],
                             parent='devices/pci0000:00/0000:00:14.0/usb1')
        code, rec = self.run_helper(d)
        self.assertEqual(code, 1)
        self.assertEqual(rec['ev'], 'refuse')
        self.assertNoWrites()

    def test_root_hub(self):
        d = os.path.join(self.fake.root, VHCI_USB)
        os.makedirs(d)
        code, _ = self.run_helper(d)
        self.assertEqual(code, 1)
        self.assertNoWrites()

    def test_interface_path(self):
        d = self.fake.device('5-1', '1209', '5740', [('02', '02', '01')])
        code, _ = self.run_helper(d + '/5-1:1.0')
        self.assertEqual(code, 1)
        self.assertNoWrites()

    def test_vhci_name_must_be_numbered(self):
        d = self.fake.device('5-1', '1209', '5740', [('02', '02', '01')],
                             parent='devices/platform/vhci_hcd/usb5')
        code, _ = self.run_helper(d)
        self.assertEqual(code, 1)

    def test_symlink_escaping_vhci(self):
        outside = self.fake.device('1-1', '1209', '5740', [('02', '02', '01')],
                                   parent='devices/pci0000:00/usb1')
        hub = os.path.join(self.fake.root, VHCI_USB)
        os.makedirs(hub)
        link = os.path.join(hub, '5-1')
        os.symlink(outside, link)
        code, _ = self.run_helper(link)
        self.assertEqual(code, 1)
        self.assertNoWrites()

    def test_dotdot_escape(self):
        self.fake.device('1-1', '1209', '5740', [('02', '02', '01')],
                         parent='devices/pci0000:00/usb1')
        os.makedirs(os.path.join(self.fake.root, VHCI_USB))
        code, _ = self.run_helper(os.path.join(self.fake.root, VHCI_USB, '..', '..', '..',
                                               'pci0000:00/usb1/1-1'))
        self.assertEqual(code, 1)
        self.assertNoWrites()

    def test_missing_path(self):
        code, _ = self.run_helper(os.path.join(self.fake.root, VHCI_USB, '5-9'))
        self.assertEqual(code, 1)

    def test_usage_error_exits_2(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as cm:
            ua.main([])
        self.assertEqual(cm.exception.code, 2)


class Behaviour(Base):
    def test_already_authorized_left_alone(self):
        d, rec = self.check('1209', '5740', [('02', '02', '01'), ('0a', '00', '00')])
        self.writes.clear()
        # second run: both are now '1'
        code, rec = self.run_helper(d)
        self.assertEqual(code, 0)
        self.assertEqual(rec['already_authorized'], ['5-1:1.0', '5-1:1.1'])
        self.assertEqual(rec['authorized'], [])
        self.assertNoWrites()

    def test_mixed_authorized_state(self):
        d = self.fake.device('5-1', '1209', '5740', [('02', '02', '01'), ('0a', '00', '00')])
        self.fake.put(d + '/5-1:1.0', 'authorized', '1')
        _, rec = self.run_helper(d)
        self.assertEqual(rec['already_authorized'], ['5-1:1.0'])
        self.assertEqual(rec['authorized'], ['5-1:1.1'])
        self.assertAuthThenProbe(['5-1:1.1'], [])

    def test_denied_but_authorized_is_not_deauthorized(self):
        d, rec = self.check('046d', 'c31c', [('03', '01', '01')], auth='1')
        self.assertEqual(rec['interfaces'][0]['decision'], 'deny')
        self.assertNoWrites()
        self.assertEqual(self.fake.get(d + '/5-1:1.0', 'authorized').strip(), '1')

    def test_never_writes_zero(self):
        self.check('1209', '5740', [('02', '02', '01'), ('0a', '00', '00'), ('03', '00', '00')])
        self.assertNotIn('0', [w[1] for w in self.writes])

    def test_dry_run_writes_nothing(self):
        d = self.fake.device('5-1', '1209', '5740', [('02', '02', '01'), ('0a', '00', '00')])
        code, rec = self.run_helper(d, '--dry-run')
        self.assertEqual(code, 0)
        self.assertEqual(set(self.decisions(rec).values()), {'allow'})
        self.assertTrue(rec['dry_run'])
        self.assertEqual(rec['authorized'], [])
        self.assertNoWrites()
        self.assertEqual(self.fake.get(d + '/5-1:1.0', 'authorized').strip(), '0')

    def test_probe_errors_are_reported_not_fatal(self):
        d = self.fake.device('5-1', '1209', '5740', [('02', '02', '01'), ('0a', '00', '00')])
        real = ua.write_attr

        def flaky(path, data):
            if path.endswith('drivers_probe'):
                raise OSError(22, 'Invalid argument')
            return real(path, data)

        with mock.patch.object(ua, 'write_attr', flaky):
            code, rec = self.run_helper(d)
        self.assertEqual(code, 0)
        self.assertEqual(rec['probe_errors'], {'5-1:1.0': 'EINVAL'})
        self.assertEqual(rec['probed'], [])

    def test_authorize_failure_skips_probe_of_that_interface(self):
        d = self.fake.device('5-1', '1209', '5740', [('02', '02', '01'), ('0a', '00', '00')])
        real = ua.write_attr

        def flaky(path, data):
            if path.endswith('5-1:1.0/authorized'):
                raise OSError(13, 'denied')
            return real(path, data)

        with mock.patch.object(ua, 'write_attr', flaky):
            code, rec = self.run_helper(d)
        self.assertEqual(code, 0)
        self.assertEqual(rec['authorized'], ['5-1:1.1'])
        self.assertIn('5-1:1.0', rec['auth_errors'])
        self.assertEqual(self.probe_writes(), [])

    def test_log_shape(self):
        d = self.fake.device('5-1', '1209', '5740', [('02', '02', '01')])
        code, rec = self.run_helper(d)
        self.assertEqual(rec['ev'], 'authorize')
        self.assertEqual(rec['device'], '5-1')
        self.assertEqual(set(rec['interfaces'][0]),
                         {'if', 'cls', 'modalias', 'candidates', 'decision', 'why'})
        self.assertEqual(rec['interfaces'][0]['modalias'], modalias('1209', '5740', ('02', '02', '01')))
        self.syslog.assert_called_once()
        self.assertEqual(json.loads(self.syslog.call_args[0][1]), rec)

    def test_log_truncates_long_candidate_list(self):
        with open(os.path.join(self.mods, 'modules.alias'), 'a') as f:
            for n in range(40):
                f.write('alias usb:v1209p5740%sic*isc*ip*in* m%02d\n' % (ANY, n))
        with open(os.path.join(self.mods, 'modules.dep'), 'a') as f:
            for n in range(40):
                f.write('kernel/drivers/usb/misc/m%02d.ko:\n' % n)
        _, rec = self.check('1209', '5740', [('ff', '00', '00')])
        self.assertEqual(len(rec['interfaces'][0]['candidates']), ua.CANDIDATES_LOG)


class Marker(Base):
    def marker(self, name='5-1'):
        path = os.path.join(self.marker_dir, name + '.json')
        with open(path) as f:
            return json.load(f), os.stat(path)

    def test_marker_is_the_log_plus_busnum_and_devnum(self):
        d, rec = self.check('1209', '5740', [('02', '02', '01'), ('0a', '00', '00')])
        m, st = self.marker()
        self.assertEqual(m.pop('busnum'), 5)
        self.assertEqual(m.pop('devnum'), 7)
        self.assertEqual(m, rec)
        self.assertEqual(os.stat(self.marker_dir).st_mode & 0o777, 0o755)
        self.assertEqual(st.st_mode & 0o777, 0o644)
        self.assertEqual(sorted(os.listdir(self.marker_dir)), ['5-1.json'])     # no temp file left

    def test_marker_is_written_after_the_work(self):
        order = []
        real_log, real_marker = ua.log, ua.write_marker
        with mock.patch.object(ua, 'log', lambda *a, **k: (order.append('log'), real_log(*a, **k))[1]), \
                mock.patch.object(ua, 'write_marker',
                                  lambda *a, **k: (order.append('marker'), real_marker(*a, **k))[1]):
            self.check('1209', '5740', [('02', '02', '01')])
        self.assertEqual(order, ['log', 'marker'])
        self.assertEqual(self.authorize_writes(), ['5-1:1.0'])      # and the work was done by then

    def test_denied_device_gets_a_marker_too(self):
        self.check('046d', 'c31c', [('03', '01', '01')])
        m, _ = self.marker()
        self.assertEqual(m['interfaces'][0]['decision'], 'deny')

    def test_dry_run_writes_no_marker(self):
        d = self.fake.device('5-1', '1209', '5740', [('02', '02', '01')])
        self.run_helper(d, '--dry-run')
        self.assertFalse(os.path.exists(self.marker_dir))

    def test_rewrite_replaces_a_stale_marker(self):
        d, _ = self.check('1209', '5740', [('02', '02', '01')])
        self.fake.put(d, 'devnum', '9')
        self.run_helper(d)
        self.assertEqual(self.marker()[0]['devnum'], 9)
        self.assertEqual(sorted(os.listdir(self.marker_dir)), ['5-1.json'])

    def test_missing_busnum_is_null(self):
        d = self.fake.device('5-1', '1209', '5740', [('02', '02', '01')])
        os.unlink(d + '/busnum')
        self.run_helper(d)
        m, _ = self.marker()
        self.assertIsNone(m['busnum'])
        self.assertEqual(m['devnum'], 7)

    def test_marker_failure_does_not_change_the_run(self):
        open(self.marker_dir, 'w').close()          # a file where the directory should be
        d = self.fake.device('5-1', '1209', '5740', [('02', '02', '01')])
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            code, rec = self.run_helper(d)
        self.assertEqual(code, 0)
        self.assertEqual(rec['authorized'], ['5-1:1.0'])
        self.assertIn('marker_error', err.getvalue())

    def test_default_directory_is_run(self):
        self.assertEqual(ua.MARKER_DIR, '/run/usbip-authorize')


class Rules(unittest.TestCase):
    def test_rules_file(self):
        with open(os.path.join(HERE, '70-usbip-dialin.rules')) as f:
            rules = [l.strip() for l in f if l.strip() and not l.lstrip().startswith('#')]
        self.assertEqual(len(rules), 2)
        first, second = rules
        self.assertIn('ACTION=="add"', first)
        self.assertIn('KERNELS=="vhci_hcd.*"', first)
        self.assertIn('KERNEL=="usb[0-9]*"', first)
        self.assertRegex(first, r'ATTR\{interface_authorized_default\}="0"$')
        self.assertIn('ACTION=="bind"', second)
        self.assertIn('KERNEL!="usb[0-9]*"', second)
        self.assertIn('KERNELS=="vhci_hcd.*"', second)
        self.assertRegex(second, r'RUN\+="/usr/bin/python3 -I /opt/usbip-dialin/usbip_authorize\.py [^"]*"$')


class EndToEnd(unittest.TestCase):
    def test_subprocess_exit_codes(self):
        import subprocess
        with tempfile.TemporaryDirectory() as td:
            root = os.path.realpath(td)
            mods = os.path.join(root, 'modules')
            make_modules(mods)
            fake = Fake(os.path.join(root, 'sys'))
            d = fake.device('5-1', '1209', '5740', [('02', '02', '01')])
            script = os.path.join(HERE, 'usbip_authorize.py')
            common = ['--sysfs-root', fake.root, '--modules-dir', mods,
                      '--marker-dir', os.path.join(root, 'markers')]
            r = subprocess.run([sys.executable, '-I', script, d] + common,
                               capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(json.loads(r.stdout)['interfaces'][0]['decision'], 'allow')
            r = subprocess.run([sys.executable, '-I', script, root] + common,
                               capture_output=True, text=True)
            self.assertEqual(r.returncode, 1)
            r = subprocess.run([sys.executable, '-I', script], capture_output=True, text=True)
            self.assertEqual(r.returncode, 2)


if __name__ == '__main__':
    unittest.main()
