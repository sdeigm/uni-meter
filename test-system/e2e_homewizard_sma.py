#!/usr/bin/env python3
"""
End-to-end test: HomeWizard P1 (simulated) -> uni-meter -> Speedwire -> SMA Sunny Boy (simulated).

* A fake HomeWizard P1 meter serves /api/v1/data over HTTP.
* uni-meter is started with the HomeWizardP1 input and the SmaEM output.
* A fake Sunny Boy joins the Speedwire multicast group and decodes the energy meter telegrams.

The SmaEM output is configured with ttl = 0, so multicast packets never leave this host
and a real inverter on the LAN cannot receive them.
"""
import argparse
import collections
import glob
import json
import os
import queue
import shutil
import socket
import struct
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
GROUP = "239.12.255.254"
SERIAL = 1900999999  # test-only serial, never register it in a real inverter
FORGET_S = 5

# ---------------------------------------------------------------- fake HomeWizard P1

class FakeP1:
    """Serves scenario data, or with 'upstream' proxies a real P1 and records what it returned."""
    def __init__(self, upstream=None):
        self.lock = threading.Lock()
        self.data = {}
        self.requests = 0
        self.upstream = upstream
        self.history = collections.deque(maxlen=20)
        self.hang = False  # simulate a Wi-Fi drop: accept the connection but never answer

    def fetch_upstream(self):
        with urllib.request.urlopen(self.upstream + "/api/v1/data", timeout=3) as r:
            data = json.loads(r.read())
        with self.lock:
            self.history.append(data)
        return data

    def set(self, data):
        with self.lock:
            self.data = dict(data)

    def handler(self):
        p1 = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path != "/api/v1/data":
                    self.send_error(404)
                    return
                hung = time.time()
                while p1.hang and time.time() - hung < 60:
                    time.sleep(0.1)
                if p1.upstream:
                    try:
                        p1.set(p1.fetch_upstream())
                    except Exception as e:
                        self.send_error(502, str(e))
                        return
                with p1.lock:
                    body = json.dumps(p1.data).encode()
                    p1.requests += 1
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        return Handler

# ---------------------------------------------------------------- fake SMA Sunny Boy

def decode_speedwire(pkt):
    """Independent decoder for SMA energy meter (protocol 0x6069) telegrams."""
    if len(pkt) < 28 or pkt[:4] != b"SMA\0":
        return None
    if struct.unpack(">H", pkt[16:18])[0] != 0x6069:
        return None
    susy, serial, ts = struct.unpack(">HII", pkt[18:28])
    values, pos = {}, 28
    while pos + 4 <= len(pkt):
        (obis,) = struct.unpack(">I", pkt[pos:pos + 4])
        pos += 4
        if obis >> 24 == 0x90:  # software version -> end of data
            break
        channel, kind = (obis >> 16) & 0xFF, (obis >> 8) & 0xFF
        if kind == 8:
            (v,) = struct.unpack(">Q", pkt[pos:pos + 8]); pos += 8
            values[f"{channel}.8.0"] = v / 3_600_000.0   # kWh
        else:
            (v,) = struct.unpack(">I", pkt[pos:pos + 4]); pos += 4
            values[f"{channel}.4.0"] = v
    return {"susy": susy, "serial": serial, "ts": ts, "values": values, "raw": bytes(pkt)}


OBIS_NAMES = {
    1: "P+ total (import)", 2: "P- total (export)", 3: "Q+ total", 4: "Q- total",
    9: "S+ total", 10: "S- total", 13: "power factor", 14: "frequency",
    21: "P+ L1", 22: "P- L1", 23: "Q+ L1", 24: "Q- L1", 29: "S+ L1", 30: "S- L1",
    31: "I L1", 32: "U L1", 33: "PF L1",
    41: "P+ L2", 42: "P- L2", 43: "Q+ L2", 44: "Q- L2", 49: "S+ L2", 50: "S- L2",
    51: "I L2", 52: "U L2", 53: "PF L2",
    61: "P+ L3", 62: "P- L3", 63: "Q+ L3", 64: "Q- L3", 69: "S+ L3", 70: "S- L3",
    71: "I L3", 72: "U L3", 73: "PF L3",
}


def dump_speedwire(pkt):
    """Print a Speedwire telegram as annotated hex."""
    def row(off, n, text):
        print(f"  {off:04x}  {pkt[off:off + n].hex(' '):<36}  {text}")

    print(f"--- raw Speedwire telegram, {len(pkt)} bytes ---")
    row(0, 4, "signature 'SMA\\0'")
    row(4, 4, "tag 0x02A0 (group), length 4")
    row(8, 4, "group 1")
    row(12, 2, f"data length {struct.unpack('>H', pkt[12:14])[0]}")
    row(14, 2, "tag 0x0010 (SMA net 2)")
    row(16, 2, "protocol 0x6069 (energy meter)")
    susy, serial, ts = struct.unpack(">HII", pkt[18:28])
    row(18, 2, f"susy-id {susy}")
    row(20, 4, f"serial {serial}")
    row(24, 4, f"timestamp {ts} ms")
    pos = 28
    while pos + 4 <= len(pkt):
        (obis,) = struct.unpack(">I", pkt[pos:pos + 4])
        if obis >> 24 == 0x90:
            row(pos, 8, f"software version {pkt[pos + 4]}.{pkt[pos + 5]}.{pkt[pos + 6]}.{chr(pkt[pos + 7])}")
            row(pos + 8, len(pkt) - pos - 8, "end of data")
            break
        ch, kind = (obis >> 16) & 0xFF, (obis >> 8) & 0xFF
        name = OBIS_NAMES.get(ch, "?")
        if kind == 8:
            (v,) = struct.unpack(">Q", pkt[pos + 4:pos + 12])
            row(pos, 12, f"{ch}.8.0 {name} counter = {v / 3_600_000:.3f} kWh")
            pos += 12
        else:
            (v,) = struct.unpack(">I", pkt[pos + 4:pos + 8])
            if ch in (31, 51, 71):
                text = f"{v / 1000:.3f} A"
            elif ch in (32, 52, 72):
                text = f"{v / 1000:.1f} V"
            elif ch in (13, 33, 53, 73):
                text = f"{v / 1000:.3f}"
            elif ch == 14:
                text = f"{v / 1000:.2f} Hz"
            else:
                text = f"{v / 10:.1f} W/var/VA"
            row(pos, 8, f"{ch}.4.0 {name} = {text}")
            pos += 8


class FakeSunnyBoy(threading.Thread):
    def __init__(self, port):
        super().__init__(daemon=True)
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("", port))
        mreq = struct.pack("4s4s", socket.inet_aton(GROUP), socket.inet_aton("0.0.0.0"))
        self.sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
        self.sock.settimeout(0.5)
        self.latest = None
        self.count = 0
        self.last_rx = 0.0
        self.telegrams = queue.Queue()
        self.running = True

    def run(self):
        while self.running:
            try:
                pkt, _ = self.sock.recvfrom(2048)
            except socket.timeout:
                continue
            t = decode_speedwire(pkt)
            if t and t["serial"] == SERIAL:
                self.latest, self.count = t, self.count + 1
                self.last_rx = time.time()
                self.telegrams.put(t)

    def grid_power(self, telegram=None):
        """Net grid power as the inverter sees it (W, + = import, - = export)."""
        v = (telegram or self.latest)["values"]
        return {
            "total": (v["1.4.0"] - v["2.4.0"]) / 10.0,
            "l1": (v["21.4.0"] - v["22.4.0"]) / 10.0,
            "l2": (v["41.4.0"] - v["42.4.0"]) / 10.0,
            "l3": (v["61.4.0"] - v["62.4.0"]) / 10.0,
            "voltage_l1": v["32.4.0"] / 1000.0,
            "import_kwh": v["1.8.0"],
            "export_kwh": v["2.8.0"],
        }

# ---------------------------------------------------------------- uni-meter

def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def manifest_classpath(jar):
    """Resolve the jar's manifest Class-Path entries from the local Maven repository."""
    import zipfile
    with zipfile.ZipFile(jar) as z:
        mf = z.read("META-INF/MANIFEST.MF").decode().replace("\r", "").replace("\n ", "")
    names = next(l for l in mf.splitlines() if l.startswith("Class-Path:")).split()[1:]
    index = {os.path.basename(p): p for p in glob.glob(
        os.path.expanduser("~/.m2/repository/**/*.jar"), recursive=True)}
    missing = [n for n in names if n not in index]
    if missing:
        sys.exit(f"dependencies missing in ~/.m2: {missing}")
    return [jar] + [index[n] for n in names]


def start_uni_meter(workdir, p1_port, http_port, sw_port, phase_mode, interval="500ms", target=GROUP):
    tgz = sorted(glob.glob(os.path.join(REPO, "target", "uni-meter-*.tgz")))
    jars = sorted(glob.glob(os.path.join(REPO, "target", "uni-meter-*.jar")))
    if tgz:
        with tarfile.open(tgz[-1]) as tf:
            tf.extractall(workdir)
        classpath = [glob.glob(os.path.join(workdir, "*", "lib", "uni-meter-*.jar"))[0]]
    elif jars:
        # prefer freshly compiled classes over a possibly stale jar
        classpath = [os.path.join(REPO, "target", "classes")] + manifest_classpath(jars[-1])
    else:
        sys.exit("uni-meter jar not found - build with 'mvn -DskipTests package' first")

    conf = os.path.join(workdir, "uni-meter.conf")
    with open(conf, "w") as f:
        f.write(f"""
uni-meter {{
  output = "uni-meter.output-devices.sma-em"
  input  = "uni-meter.input-devices.homewizard-p1"
  http-server {{ interface = "127.0.0.1", port = {http_port} }}
  mdns {{ type = "none" }}
  output-devices.sma-em {{
    group = "{target}"
    port = {sw_port}
    ttl = 0
    forget-interval = {FORGET_S}s
    send-interval = {interval}
    serial-number = {SERIAL}
  }}
  input-devices.homewizard-p1 {{
    url = "http://127.0.0.1:{p1_port}"
    polling-interval = {interval}
    request-timeout = 2s
    power-phase-mode = "{phase_mode}"
    energy-phase-mode = "{phase_mode}"
  }}
}}
""")
    logback = os.path.join(workdir, "logback.xml")
    with open(logback, "w") as f:
        f.write("""<configuration>
  <appender name="F" class="ch.qos.logback.core.FileAppender">
    <file>""" + os.path.join(workdir, "uni-meter.log") + """</file>
    <encoder><pattern>%d %-5level %logger - %msg%n</pattern></encoder>
  </appender>
  <root level="INFO"><appender-ref ref="F"/></root>
</configuration>""")
    return subprocess.Popen(
        ["java", f"-Dconfig.file={conf}", f"-Dlogback.configurationFile={logback}",
         "-cp", os.pathsep.join(classpath), "com.deigmueller.uni_meter.application.Application"],
        stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)

# ---------------------------------------------------------------- scenarios

def p1_payload(l1, l2, l3, imp, exp, v=(231.0, 229.5, 230.2)):
    return {
        "wifi_ssid": "test", "meter_model": "SIMULATED", "smr_version": 50,
        "active_power_w": l1 + l2 + l3,
        "active_power_l1_w": l1, "active_power_l2_w": l2, "active_power_l3_w": l3,
        "active_voltage_l1_v": v[0], "active_voltage_l2_v": v[1], "active_voltage_l3_v": v[2],
        "active_frequency_hz": 50.0,
        "total_power_import_kwh": imp, "total_power_export_kwh": exp,
    }


SCENARIOS = [
    ("Evening: importing from grid", p1_payload(820, 310, 125, 13779.338, 2051.120)),
    ("Midday: PV surplus, exporting", p1_payload(-1450, 120, -60, 13779.400, 2052.875)),
    ("Balanced: near zero", p1_payload(15, -10, 0, 13779.410, 2052.900)),
    ("Single-phase meter (L2/L3 missing)",
     {k: v for k, v in p1_payload(640, 0, 0, 13780.0, 2053.0).items()
      if not k.endswith(("_l2_w", "_l3_w", "_l2_v", "_l3_v"))}),
]


def wait_match(sunny, expected, timeout):
    deadline, last = time.time() + timeout, None
    while time.time() < deadline:
        if sunny.latest:
            last = sunny.grid_power()
            if all(abs(last[k] - v) <= tol for k, (v, tol) in expected.items()):
                return True, last
        time.sleep(0.2)
    return False, last


def matches(got, reading):
    """True if a decoded telegram carries exactly the values of a P1 reading."""
    phases = [reading.get(f"active_power_l{i}_w") for i in (1, 2, 3)]
    exp = {
        # SmaEM sends total = L1+L2+L3; the meter rounds phases separately, so its own total may differ
        "total": sum(phases) if None not in phases else reading["active_power_w"],
        "import_kwh": reading["total_power_import_kwh"], "export_kwh": reading["total_power_export_kwh"],
    }
    if None not in phases:
        exp.update({"l1": phases[0], "l2": phases[1], "l3": phases[2]})
    tol = {"import_kwh": 0.001, "export_kwh": 0.001}
    return all(abs(got[k] - v) <= tol.get(k, 0.6) for k, v in exp.items())


def run_live(p1, sunny, duration, dump=0):
    """Check every telegram against the real P1 readings that were proxied to uni-meter."""
    print(f"{'time':8} {'grid total':>11} {'L1':>7} {'L2':>7} {'L3':>7} {'U1':>6} "
          f"{'import kWh':>11} {'export kWh':>11}  check (vs meter total)")
    checked = matched = 0
    start = time.time()
    while time.time() - start < duration:
        try:
            t = sunny.telegrams.get(timeout=1)
        except queue.Empty:
            continue
        with p1.lock:
            readings = list(p1.history)
        if not readings:
            continue  # telegram sent before the first poll (still default values)
        g = sunny.grid_power(t)
        hit = next((r for r in reversed(readings[-5:]) if matches(g, r)), None)
        ok = hit is not None
        if ok and dump > 0:
            dump -= 1
            keys = [k for k in hit if k.startswith(("active_power", "active_voltage", "total_power_"))
                    and "_t1_" not in k and "_t2_" not in k and "average" not in k]
            print("\n--- real P1 reading (source) ---")
            print("  " + json.dumps({k: hit[k] for k in keys}))
            dump_speedwire(t["raw"])
            print()
        checked += 1
        matched += ok
        dev = f"{g['total'] - hit['active_power_w']:+.0f}W" if hit else ""
        print(f"{time.strftime('%H:%M:%S')} {g['total']:+10.1f}W {g['l1']:+7.1f} {g['l2']:+7.1f} "
              f"{g['l3']:+7.1f} {g['voltage_l1']:6.1f} {g['import_kwh']:11.3f} {g['export_kwh']:11.3f}  "
              f"{'OK' if ok else 'MISMATCH'} {dev}")
    print(f"\ntelegrams checked: {checked}, matching a real P1 reading: {matched}, "
          f"P1 polls proxied: {p1.requests}")
    # allow a stray first telegram carrying the startup defaults
    return 0 if checked >= duration / 2 and checked - matched <= 1 else 1


def run_outage(p1, sunny):
    """P1 stops answering (Wi-Fi drop): telegrams must stop, then resume quickly after recovery."""
    results = []
    p1.hang = True
    start = time.time()
    stopped = None
    while time.time() - start < FORGET_S + 10:
        if sunny.last_rx and time.time() - sunny.last_rx > 2.0:
            stopped = sunny.last_rx - start
            break
        time.sleep(0.2)
    ok = stopped is not None and stopped <= FORGET_S + 1.5
    results.append(ok)
    print(f"[{'PASS' if ok else 'FAIL'}] P1 outage: inverter stops receiving telegrams")
    print(f"       last telegram {stopped:.1f}s after outage start (forget-interval {FORGET_S}s)"
          if stopped is not None else "       telegrams never stopped")

    time.sleep(3)  # let a few polls hang
    count = sunny.count
    p1.hang = False
    start = time.time()
    while sunny.count == count and time.time() - start < 30:
        time.sleep(0.1)
    resumed = time.time() - start
    ok = sunny.count > count and resumed < 5
    results.append(ok)
    print(f"[{'PASS' if ok else 'FAIL'}] P1 recovery: telegrams resume")
    print(f"       first telegram {resumed:.1f}s after P1 answered again")
    return results.count(False)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep", action="store_true", help="keep temp dir with uni-meter.log")
    ap.add_argument("--p1-url", help="use a real HomeWizard P1 (e.g. http://192.168.1.50) instead of scenarios")
    ap.add_argument("--duration", type=int, default=30, help="live mode duration in seconds")
    ap.add_argument("--dump", type=int, default=0, help="print raw annotated bytes of the first N telegrams")
    ap.add_argument("--unicast", action="store_true", help="send Speedwire to 127.0.0.1 instead of multicast")
    args = ap.parse_args()

    p1 = FakeP1(args.p1_url.rstrip("/") if args.p1_url else None)
    p1_server = ThreadingHTTPServer(("127.0.0.1", 0), p1.handler())
    threading.Thread(target=p1_server.serve_forever, daemon=True).start()
    p1_port = p1_server.server_address[1]

    sw_port = free_port()  # non-standard port: extra isolation from real SMA devices
    sunny = FakeSunnyBoy(sw_port)
    sunny.start()

    workdir = tempfile.mkdtemp(prefix="uni-meter-e2e-")
    if p1.upstream:
        info = json.loads(urllib.request.urlopen(p1.upstream + "/api", timeout=3).read())
        print(f"real HomeWizard P1 : {p1.upstream} ({info.get('product_type')}, fw {info.get('firmware_version')})")
    else:
        p1.set(SCENARIOS[0][1])
    target = "127.0.0.1" if args.unicast else GROUP
    proc = start_uni_meter(workdir, p1_port, free_port(), sw_port, "tri-phase",
                           "1s" if p1.upstream else "500ms", target)
    print(f"P1 endpoint        : http://127.0.0.1:{p1_port}/api/v1/data"
          f"{' (recording proxy)' if p1.upstream else ' (simulated)'}")
    print(f"fake Sunny Boy     : receiving on {target}:{sw_port} "
          f"({'unicast' if args.unicast else 'multicast, ttl 0, host-local'})")
    print(f"uni-meter          : pid {proc.pid}, log {workdir}/uni-meter.log\n")

    failures = 0
    try:
        if p1.upstream:
            failures = run_live(p1, sunny, args.duration, args.dump)
        for name, data in ([] if p1.upstream else SCENARIOS):
            p1.set(data)
            l1 = data.get("active_power_l1_w", 0)
            l2 = data.get("active_power_l2_w", 0)
            l3 = data.get("active_power_l3_w", 0)
            total = data["active_power_w"]
            expected = {
                "total": (total, 1.0),
                "import_kwh": (data["total_power_import_kwh"], 0.01),
                "export_kwh": (data["total_power_export_kwh"], 0.01),
            }
            if "active_power_l2_w" in data:
                expected.update({"l1": (l1, 1.0), "l2": (l2, 1.0), "l3": (l3, 1.0),
                                 "voltage_l1": (data["active_voltage_l1_v"], 0.1)})
            ok, got = wait_match(sunny, expected, timeout=40 if name == SCENARIOS[0][0] else 10)
            failures += 0 if ok else 1
            print(f"[{'PASS' if ok else 'FAIL'}] {name}")
            print(f"       P1 sends   : total={total:+.0f} W  L1={l1:+.0f} L2={l2:+.0f} L3={l3:+.0f}  "
                  f"import={data['total_power_import_kwh']} kWh export={data['total_power_export_kwh']} kWh")
            if got:
                print(f"       Inverter got: total={got['total']:+.1f} W  L1={got['l1']:+.1f} "
                      f"L2={got['l2']:+.1f} L3={got['l3']:+.1f}  U1={got['voltage_l1']:.1f} V  "
                      f"import={got['import_kwh']:.3f} kWh export={got['export_kwh']:.3f} kWh")
            else:
                print("       Inverter got: no telegram")
        if not p1.upstream and not failures:
            failures += run_outage(p1, sunny)
        if sunny.latest:
            print(f"\ntelegrams received: {sunny.count}, susy-id {sunny.latest['susy']}, "
                  f"serial {sunny.latest['serial']}; P1 polls served: {p1.requests}")
    finally:
        proc.terminate()
        try:
            proc.wait(10)
        except subprocess.TimeoutExpired:
            proc.kill()
        sunny.running = False
        p1_server.shutdown()
        if failures or args.keep:
            print(f"uni-meter log kept in {workdir}/uni-meter.log")
        else:
            shutil.rmtree(workdir, ignore_errors=True)

    print("\nRESULT:", "ALL PASSED" if failures == 0 else f"{failures} FAILED")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
