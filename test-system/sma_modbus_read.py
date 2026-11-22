#!/usr/bin/env python3
"""Read-only Modbus TCP check of what an SMA inverter receives from its energy meter."""
import argparse
import socket
import struct
import time
import urllib.request
import json

NAN_U32, NAN_S32 = 0xFFFFFFFF, 0x80000000

# register: (name, type)
REGISTERS = {
    30057: ("serial number", "U32"),
    30775: ("inverter AC power W", "S32"),
    30865: ("grid purchase W (from meter)", "U32"),
    30867: ("grid feed-in W (from meter)", "U32"),
}


def read_u32(sock, unit, reg, tid):
    req = struct.pack(">HHHBBHH", tid, 0, 6, unit, 4, reg, 2)  # FC4 read input registers
    sock.sendall(req)
    hdr = sock.recv(9)
    if len(hdr) < 9 or hdr[7] & 0x80:
        return None
    data = sock.recv(hdr[8])
    return struct.unpack(">I", data[:4])[0]


def fmt(value, kind):
    if value is None:
        return "error"
    if kind == "S32":
        if value == NAN_S32:
            return "NaN"
        return str(struct.unpack(">i", struct.pack(">I", value))[0])
    return "NaN" if value in (NAN_U32, NAN_S32) else str(value)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", required=True, help="IP address of the SMA inverter")
    ap.add_argument("--unit", type=int, default=3)
    ap.add_argument("--p1-url", required=True, help="e.g. http://192.168.1.50")
    ap.add_argument("--count", type=int, default=1)
    args = ap.parse_args()

    with socket.create_connection((args.host, 502), timeout=5) as s:
        tid = 1
        for i in range(args.count):
            row = {}
            for reg, (name, kind) in REGISTERS.items():
                row[name] = fmt(read_u32(s, args.unit, reg, tid), kind)
                tid += 1
            try:
                p1 = json.loads(urllib.request.urlopen(args.p1_url + "/api/v1/data", timeout=3).read())
                row["P1 active_power_w"] = p1["active_power_w"]
            except Exception as e:
                row["P1 active_power_w"] = f"error: {e}"
            print(time.strftime("%H:%M:%S"), json.dumps(row))
            if i + 1 < args.count:
                time.sleep(2)


if __name__ == "__main__":
    main()
