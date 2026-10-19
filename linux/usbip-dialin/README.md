# usbip-dialin

A Linux service that attaches a USB device plugged into a phone, when the
**phone** opens the connection. The phone dials out to the Linux host; the host
accepts, plays the USB/IP client, and the device appears there as if it were
plugged in locally (a `ttyACM` for a serial device, say). The host never
connects to the phone, and nothing on the phone has to listen on a network
address.

Standard library Python 3, one systemd unit. Needs root, the `vhci_hcd` kernel
module, and Tailscale on the host. Tested on Linux 6.8, Python 3.12, systemd 255.

## Why

USB/IP normally has the client (the machine that wants the device) connect to
the server (the machine the device is plugged into), on TCP 3240. A phone on
cellular or a field WiFi network cannot be reached that way without a listening
port on it. The kernel does not care which end opened the TCP connection: the
vhci `attach` file takes "a socket descriptor of an established TCP
connection" and checks only that it is `SOCK_STREAM` (Linux v6.8,
`drivers/usb/usbip/vhci_sysfs.c`, `attach_store`). So the connection can be
turned round in userspace, which is all this service does.

## Protocol flow

The wire formats are the kernel's: `Documentation/usb/usbip_protocol.rst` and
`tools/usb/usbip/src/usbip_network.h` (v6.8). The service is the *client* on
an accepted socket. A usbipdcpp server (the library inside the Android app)
answers one `OP_REQ_DEVLIST` and closes, as the protocol requires, so each
device takes two connections:

1. The phone connects. The service sends `OP_REQ_DEVLIST`, reads the reply,
   keeps it as that peer's device list, and closes.
2. The phone redials. The service holds a list for that peer under `--list-ttl`
   seconds old (default 30) with a device not yet attached, so it sends
   `OP_REQ_IMPORT` for it and reads `OP_REP_IMPORT`. On status 0 it writes
   `<port> <sockfd> <devid> <speed>` to `/sys/devices/platform/vhci_hcd.0/attach`
   (devid is `busnum << 16 | devnum`; the port is a free one on the high-speed
   or super-speed hub, chosen as `usbip attach` does), writes the record
   `usbip port` reads under `/var/run/vhci_hcd/port<N>`, and closes its own
   descriptor. The kernel keeps the socket. The device leaves the list.

A bus id is trusted only inside one list-then-import cycle: it changes when
the device re-enumerates, and the list carries no serial number. Which devices
are already attached is read from the `port<N>` records whose vhci port is
still in use.

The service never detaches anything, on error or on stop: an attached device
belongs to the kernel, and a stop must not cut a transfer in progress. When
the phone's TCP connection closes, the kernel detaches the device. TCP
keepalive is set on the accepted socket (30 s idle, 10 s interval, 3 probes),
which the kernel keeps, so a phone that vanishes frees its device in about a
minute instead of the 2 h default.

Every handshake read has a `--timeout` (default 10 s). Anything malformed,
short, late or refused closes that connection and the service runs on. One
thread per connection, at most 32; a device list of more than 64 devices is
refused.

## Who may connect

The service listens on the host's tailnet IPv4 address only (`--listen
ADDR:PORT`, else the address `tailscale ip -4` prints, port 3241 or `--port`).
A failed bind, for example before `tailscale0` has an address, is retried every
2 s and is not fatal.

Every accepted peer goes through `tailscale whois --json <peer ip>`. It is
allowed only if

- the node is **untagged** (`Node.Tags` absent, null or empty; on real
  Tailscale an untagged node has no `Tags` key at all), and
- its owner's `UserProfile.LoginName` is one of the `--allow-user` values
  (repeatable).

Anything else is logged with the reason and closed **before the service sends
a byte**: a tagged node, an unknown address, another user, a `whois` that
exits non-zero or prints something that is not JSON. Without `--allow-user`
the service exits 2. The whois command can be replaced with `--whois-cmd` (the
peer IP is appended); the selftest does that.

With `--allow-node STABLEID` (repeatable), the node's `Node.StableID` must
also be listed: one phone, not every untagged device of that user. The
`allow` and `refuse` lines carry the node's name and StableID, so the ID can
be read from the log.

This is a check on who the peer is, not on what it sends. Three more layers
follow: authentication of the app, limits on the wire format, and
authorisation of the device's interfaces.

### Authentication (`--psk-file`)

Any process on an allowed node can dial the service: on a phone, any app.
With `--psk-file PATH`, every allowed connection must first pass
"usbip-dialin auth v1", a mutual challenge and response, before any USB/IP
byte:

1. host to phone: `UDIALIN1` (8 bytes), then `nonce_b` (32 random bytes);
2. phone to host: `nonce_p` (32 random bytes), then
   `HMAC-SHA256(K, "usbip-dialin v1 phone" || nonce_b || nonce_p)`;
3. the host checks the tag in constant time, and refuses a reflected nonce
   (`nonce_p == nonce_b`). On failure it logs `refuse` and closes, sending
   nothing more;
4. host to phone: `HMAC-SHA256(K, "usbip-dialin v1 box" || nonce_b || nonce_p)`.
   The phone checks it and only then opens its local USB/IP server and
   starts forwarding. A peer that does not hold the key therefore gets no
   device, even if the phone reaches the wrong host at that address.

Then the host sends its USB/IP request as before. `K` is 32 bytes, stored
as 64 hex characters in a regular file with no group or other access (the
service exits 2 otherwise). It is never logged. `phone_auth` in
`usbip_dialin.py` is the reference implementation of the phone's side.
Without `--psk-file`, the wire is unchanged.

### Wire limits

A bus id must match `[0-9A-Za-z.:_-]{1,31}`. It is written into the
`/var/run/vhci_hcd` record, which `usbip port` reads into a 32-byte buffer.
The speed must be low, full, high or super (1, 2, 3 or 5). Device lists are
capped at 64 devices, and every reply has `--timeout`.

### Interface authorisation

Once attached, a USB device is the kernel's: by default it binds whatever
driver its descriptors and IDs match. That could be a keyboard typing at
the console, or a network adapter that opens a path into the host outside
the VPN. So every vhci root hub starts with `interface_authorized_default`
0, and no interface binds anything (`70-usbip-dialin.rules`, rule 1). On the
device's `bind` event, `usbip_authorize.py` decides each interface:

- **By driver:** it resolves which drivers would bind, by matching the
  interface's `modalias` against the kernel's own `modules.alias` and
  `modules.builtin.modinfo`. If any of them lives under `drivers/hid`,
  `drivers/input`, `drivers/net`, `drivers/bluetooth`, `drivers/staging` or
  `net/`, or has no known path, the interface is denied. This catches
  network adapters and gamepads that declare a vendor class and are
  matched by vendor and product ID.
- **By class, as a second layer:** HID (03), wireless controllers (e0),
  CDC communication interfaces other than ACM, and RNDIS are denied.
- **Audio, for now:** class 01 and `sound/` drivers are denied until the
  running kernel has the fixes for two usb-audio out-of-bounds writes
  (`INTERIM_DENY_CLASSES`, `INTERIM_DENY_DIRS`).

Everything else (serial, storage, cameras and other media, vendor devices
driven from userspace) is authorised. Then each authorised interface that
has a driver is probed. If the module tables cannot be read, every
interface is denied. Linux v6.8 references are in the helper's docstring.
The service refuses to import (`refuse_import`) unless every vhci root hub
reads 0. After an attach, it logs one `interfaces` line: each interface's
class, whether it is authorised, and its driver. The helper runs from udev
after the kernel's first refusal (about 0.6 s after the device appears on
Linux 6.8), so the service waits for it: the helper leaves a marker,
`/run/usbip-authorize/<device>.json` (its log record plus the device's
`busnum` and `devnum`), and the service waits up to 5 s for a marker
matching the attached device before logging. The line's `helper` field is
`seen`, or `not_seen` with an `error` line saying the udev rule is not
live.

Log: one JSON object per line on stdout (the journal under systemd). Events:
`start`, `intf_auth`, `bind_retry`, `listening`, `accept`, `refuse` (with
the reason), `allow`, `auth`, `list`, `refuse_import`, `import`, `attach`
(with the vhci port), `interfaces`, `error`, `stop`. The helper logs to
syslog as `usbip-authorize`: one `authorize` line per device.

## Install

On the Linux host, as root. The unit and the udev rule expect these paths:

    install -d -m 0755 /opt/usbip-dialin
    install -m 0644 usbip_dialin.py usbip_dialin_sandbox_probe.py usbip_authorize.py /opt/usbip-dialin/
    install -m 0644 70-usbip-dialin.rules /etc/udev/rules.d/
    install -m 0644 usbip-dialin.service /etc/systemd/system/
    echo 'USBIP_DIALIN_OPTS=--allow-user <login>' > /etc/default/usbip-dialin
    echo vhci-hcd > /etc/modules-load.d/vhci-hcd.conf
    modprobe vhci-hcd
    udevadm control --reload
    udevadm trigger --action=add --settle \
      --parent-match=/sys/devices/platform/vhci_hcd.0 --sysname-match='usb[0-9]*'
    cat /sys/devices/platform/vhci_hcd.*/usb*/interface_authorized_default
    systemctl daemon-reload
    systemctl enable --now usbip-dialin

`<login>` is a tailnet login name (the `LoginName` in `tailscale whois`);
repeat `--allow-user` for more than one. The unit starts after
`tailscaled.service` and restarts on failure every 2 s. The service never
loads a module itself, which is why `vhci_hcd` is loaded at boot.
On Ubuntu 24.04, `vhci-hcd` comes in `linux-modules-extra-$(uname -r)`, and
the `usbip` command (used below only to check) in `linux-tools-common`
with `linux-tools-$(uname -r)`.

The `udevadm trigger` re-runs rule 1 on the root hubs that already exist
(at boot, the rule sees them appear); the `cat` must print only `0`s, or
the service refuses every import. Re-run both after `vhci_hcd` is reloaded
by hand.

Then turn on authentication and limit the service to one phone. The
fork's app does not dial without a key, so make the key first: 32 random
bytes as 64 hex characters, typed into the app once.

    install -d -m 0700 /etc/usbip-dialin
    (umask 077; python3 -c 'import secrets; print(secrets.token_hex(32))' > /etc/usbip-dialin/psk)
    cat /etc/usbip-dialin/psk

In `/etc/default/usbip-dialin`:

    USBIP_DIALIN_OPTS=--allow-user <login> --psk-file /etc/usbip-dialin/psk

then `systemctl restart usbip-dialin`; its `start` line in the journal must
show `"auth":true`. When the phone dials in, its `allow` line names its
StableID (logged before authentication). Add `--allow-node <StableID>` to
the same line and restart again. Keep the key out of backups and config
captures; its only other copy should be the phone's.

If ModemManager runs on the host, tell it to leave USB/IP devices alone. A
device attached over USB/IP sits under the `vhci_hcd` platform device whatever
its IDs, and ModemManager probes any serial port it finds there (it logged
"not supported by any plugin" and set a 9600-baud line coding on a mock device
before anything else opened it). A udev rule such as
`/etc/udev/rules.d/99-usbip-modemmanager.rules`:

    ACTION!="add|change|move|bind", GOTO="usbip_mm_end"
    SUBSYSTEMS=="platform", KERNELS=="vhci_hcd.*", ENV{ID_MM_DEVICE_IGNORE}="1"
    LABEL="usbip_mm_end"

The selftest checks for this property.

Check it:

    systemctl status usbip-dialin
    journalctl -u usbip-dialin -o cat      # one JSON line per event
    ss -ltnp 'sport = :3241'               # the tailnet address only
    usbip port                             # what is attached (needs root)

## The unit's sandbox

The service runs as root, because the vhci `attach` file is root-only, but
with an empty capability set, and the unit narrows what root can reach:

- the file system is read-only (`ProtectSystem=strict`, plus `ReadOnlyPaths=`
  for `/run` and for each top-level subtree of `/sys`), with exactly two
  exceptions: `/sys/devices/platform/vhci_hcd.0/attach`, and `/run/vhci_hcd`
  (`RuntimeDirectory`, mode 0700, kept across a stop because an attached device
  outlives the service); `UMask=0077`;
- `/home`, `/root`, `/tmp` and `/dev` are not the host's (`ProtectHome`,
  `PrivateTmp`, `PrivateDevices`);
- no capabilities, no new privileges, no module loading, kernel log, clock
  or hostname changes, no realtime scheduling, no namespaces, no writable and
  executable memory, a `@system-service` syscall filter without `@privileged`
  and `@resources`;
- sockets are `AF_UNIX` and `AF_INET` only (`AF_UNIX` for tailscaled's
  socket), and `IPAddressDeny=any` with `IPAddressAllow=100.64.0.0/10`, the
  Tailscale (CGNAT) range, so the service cannot reach anything else, nor be
  reached from the LAN.

Each option was tried, not just read. `usbip_dialin_sandbox_probe.py` runs in
a transient unit built from the unit file's own `[Service]` lines and does the
thing each option should allow or refuse (48 checks), reporting the errno:

    systemd-run --wait --pipe --collect -p ProtectSystem=strict -p ... \
        /usr/bin/python3 -I /opt/usbip-dialin/usbip_dialin_sandbox_probe.py \
        TAILNET_IP HOST_UTS_NS

with one `-p` for every sandbox line of the unit's `[Service]` section: all
of them except `Type`, `EnvironmentFile`, `ExecStart`, `Restart`,
`RestartSec`, `TimeoutStopSec` and `SyslogIdentifier`. `TAILNET_IP` is the
host's tailnet IPv4 address (`tailscale ip -4`; the probe asks for it itself if
left out) and `HOST_UTS_NS` is `readlink /proc/self/ns/uts` run outside the
unit; the ProtectHostname check needs the second, so give both for all 48
checks to run. The probe exits 0 when every check passes. Nothing it does
changes the host: it only writes where a success would be harmless and removes
what it creates.

What the probe found on systemd 255.4: `ProtectSystem=strict` left `/run`
writable and `ProtectKernelTunables` left `/sys` writable, against the man
page (the stock `systemd-timesyncd` too). The unit therefore adds the
`ReadOnlyPaths=` lines. `ProtectProc=invisible` hid nothing from a root
service and `RemoveIPC=` does nothing without `User=`, so both are left out.

## Selftest

`usbip_dialin_selftest.py` runs the service, offline: no phone, no tailnet.

    python3 usbip_dialin_selftest.py --mock PATH/TO/mock_cdc_acm [--user LOGIN]

or set `USBIPDCPP_MOCK` instead of `--mock`. With neither it exits 2 and prints
how to build the mock. Run it as an ordinary user: it starts the service with
`sudo -n`, so that user needs passwordless sudo. `vhci_hcd` must be loaded,
and the install above done: `70-usbip-dialin.rules`, the helper, and every
vhci root hub reading 0. Without them it exits 3 at once and says which is
missing. The ModemManager rule is checked later, as one failed check.

The fixtures:

- usbipdcpp's **userspace** `mock_cdc_acm` example, standing in for the app: a
  software CDC ACM serial port that echoes every byte. Build it from
  <https://github.com/yunsmall/usbipdcpp>, which needs `cmake`, `libasio-dev`,
  `libspdlog-dev`, `libcxxopts-dev` and `pkgconf`:

      cmake -S . -B build -DUSBIPDCPP_USE_PKGCONF_ASIO=ON \
        -DUSBIPDCPP_BUILD_LIBUSB_COMPONENTS=OFF -DUSBIPDCPP_BUILD_TESTS=OFF \
        -DCMAKE_BUILD_TYPE=Release
      cmake --build build --target mock_cdc_acm -j8

  The binary is `build/mock_cdc_acm`; it ignores SIGTERM, so stop it with
  SIGKILL. Never use a kernel gadget (`usbip-vudc`, `g_serial`) for this:
  that loopback oopsed a test machine (CVE-2026-64331).
- the same library's `mock_keyboard`, `mock_ecm`, `mock_rndis`, `mock_audio`
  and `mock_msc`, built the same way into the same directory (or
  `--mocks-dir`), for the interface-authorisation checks;
- a Python splice standing in for the phone's dialer, with source
  addresses on the loopback network to look like different peers;
- a fake `whois` command that answers by source address;
- fake phones on raw sockets for the malformed-reply cases.

It checks: an allowed dialer attaches on the right hub, the tty gets
`ID_MM_DEVICE_IGNORE=1`, and 64 B, 4 KiB and 1 MiB come back intact through it;
`usbip port` and the `/var/run/vhci_hcd` record agree; killing the dialer
detaches the device and redialing attaches it again; refused peers (tagged,
not found, another user, non-JSON, no `UserProfile`, `Tags` a string) get no
byte and attach nothing, while `Tags` absent, null and `[]` are allowed;
garbage, silence, a wrong version or code, a truncated or 100000-device list,
a bad status, a refused import and a mismatched bus id each close that
connection with the service still serving; a busy listen port is retried and
bound within seconds of freeing; the default address comes from `--ip-cmd`; a
list older than `--list-ttl` is not imported from; no `--allow-user` exits 2;
no ModemManager journal line appears. Interface authorisation: the serial
mock's interfaces are authorised and bind `cdc_acm`; the keyboard, the ECM
and RNDIS control interfaces and the audio interfaces bind nothing and no
input, network or sound device appears; the storage mock binds
`usb-storage`; the `interfaces` line agrees, with `helper` `seen`, or
`not_seen` when there is no marker; with a root hub set back to 1 the
service logs `refuse_import` and attaches nothing. It detaches only the vhci
ports it attached itself. Exit 0 when every check passes (214 checks on
Linux 6.8).

It cannot show a real phone, a real `tailscale whois`, or the tailnet address
going away after the bind.

## The phone side

The phone runs the [Android-Usbipdcpp fork](https://github.com/brackenjoe/Android-Usbipdcpp)
with dial-out. In the app: open it with your fingerprint, enter the key
once (Authentication above), turn on Dial-out, enter the host's tailnet
address and port 3241, start the server and share the device. It opens one
connection per shared device, only over a VPN network, runs the
authentication, and only then opens its own USB/IP server on `127.0.0.1`
and forwards between the two. After the fingerprint the key works for one
hour; a connection that is already up stays up after that, and a new one
waits for the next fingerprint. A new connection also waits while the phone
is locked, even inside the hour.

With upstream's unmodified app (tested at v0.0.10), a dial loop in Termux
does the dialing, and the service must run without `--psk-file`, since
`socat` cannot authenticate:

    while true; do socat TCP:<host tailnet ip>:3241 TCP:127.0.0.1:3240; sleep 1; done

`socat` opens the host first, then the app's port, and exits when either side
closes, so the loop redials; the redial is what turns the list into an
import. The device must be shared as well as the server started: a
running server with nothing shared answers with no exportable devices.
Upstream's app listens on every IPv4 interface while its server runs
(v0.0.10, read from source), so on a shared WiFi network others can list and
attach the phone's devices; the fork listens on `127.0.0.1` only by default. With the
loop running but no server on the phone, each dial shows as a `peer closed
after 0 of 8 bytes` line, about once a second.

## Limits

- One device per connection: an imported connection stays open. The fork's
  app opens one connection per shared device.
- No firmware flashing. A board that re-enumerates as its bootloader drops the
  attach, and Android asks for USB permission again.
- No isochronous devices (cameras, audio) and no high-bandwidth ones over a
  cellular link. Throughput was measured only on loopback, 3.7 to 5.4 MB/s.
- The bus id changes when a device re-enumerates; a replugged device is found
  again from a new device list, not by remembering the old id.
- Linux with `vhci_hcd` only, and Tailscale only: peer identity comes from
  `tailscale whois`. Not tested outside Linux 6.8.
- A restart of the service does not detach anything, so a device stays
  attached until the phone closes its connection.
- No keyboards, mice, gamepads or other input devices, no network
  adapters or Bluetooth, and for now no audio (interface authorisation).
- Interface authorisation is tested on Linux 6.8 with the selftest's mock
  devices and one flight controller. `--psk-file` is tested against
  `phone_auth`, by the app's unit tests, and end to end with one phone
  (a Galaxy S25).

## Licence

GPL-3.0.
