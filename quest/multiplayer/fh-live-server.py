#!/usr/bin/env python3
"""Forza Horizon session server: a stand-in for the Xbox LIVE matchmaking
service of the Pinyon Shift port (client: ShiftGlue kernel/xam/live_service).

Keeps the hosted sessions and their QoS data in memory. Peers then connect
directly (UDP) to the host address a session carries. Protocol: one request
line per TCP connection, replies end with END (see live_service.h).

  python3 fh-live-server.py [--port 36000] [--expire 900]
"""

import argparse
import socketserver
import threading
import time

SESSIONS = {}  # id -> dict
LOCK = threading.Lock()
EXPIRE = 900.0


def log(text):
    print(time.strftime("%H:%M:%S"), text, flush=True)


def expire():
    now = time.time()
    for sid in [s for s, v in SESSIONS.items() if now - v["seen"] > EXPIRE]:
        log(f"expired {sid}")
        del SESSIONS[sid]


def record_line(s):
    return "S {id} {ip} {flags} {pub} {priv} {fpub} {fpriv} {attrs}".format(**s)


class Handler(socketserver.StreamRequestHandler):
    timeout = 5

    def handle(self):
        try:
            line = self.rfile.readline(1 << 20).decode("ascii", "replace").strip()
        except OSError:
            return
        parts = line.split()
        reply = []
        peer = self.client_address[0]
        with LOCK:
            expire()
            cmd = parts[0] if parts else ""
            if cmd == "CREATE" and len(parts) >= 7:
                sid, ip = parts[1], parts[2]
                old = SESSIONS.get(sid, {})
                SESSIONS[sid] = {
                    "id": sid, "ip": ip, "flags": int(parts[3]), "pub": int(parts[4]),
                    "priv": int(parts[5]), "fpub": old.get("fpub", 0),
                    "fpriv": old.get("fpriv", 0), "attrs": parts[6],
                    "qos": old.get("qos", "-"), "seen": time.time(), "from": peer,
                }
                log(f"{'update' if old else 'create'} {sid} host {ip} (from {peer}) "
                    f"flags {int(parts[3]):#x} slots {parts[4]}+{parts[5]}")
            elif cmd == "UPDATE" and len(parts) >= 4 and parts[1] in SESSIONS:
                s = SESSIONS[parts[1]]
                s["fpub"], s["fpriv"], s["seen"] = int(parts[2]), int(parts[3]), time.time()
                log(f"members {parts[1]}: {parts[2]}+{parts[3]}")
            elif cmd == "DELETE" and len(parts) >= 2:
                if SESSIONS.pop(parts[1], None):
                    log(f"delete {parts[1]}")
            elif cmd == "QOS" and len(parts) >= 3:
                if parts[1] in SESSIONS:
                    SESSIONS[parts[1]]["qos"] = parts[2]
                    log(f"qos {parts[1]}: {len(parts[2]) // 2} bytes")
            elif cmd == "GETQOS" and len(parts) >= 2:
                s = SESSIONS.get(parts[1])
                reply.append("Q " + (s["qos"] if s else "-"))
            elif cmd == "SEARCH":
                limit = int(parts[1]) if len(parts) > 1 else 16
                found = [s for s in SESSIONS.values() if s["fpub"] < s["pub"] or s["pub"] == 0]
                found.sort(key=lambda s: -s["seen"])
                reply += [record_line(s) for s in found[:limit]]
                log(f"search from {peer}: {len(reply)} session(s)")
            elif cmd == "GET" and len(parts) >= 2:
                s = SESSIONS.get(parts[1])
                if s:
                    reply.append(record_line(s))
            elif cmd == "LIST":
                reply += [record_line(s) + f" qos={s['qos'][:16]}" for s in SESSIONS.values()]
            else:
                log(f"bad request from {peer}: {line[:80]!r}")
        reply.append("END")
        try:
            self.wfile.write(("\n".join(reply) + "\n").encode("ascii"))
        except OSError:
            pass


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main():
    global EXPIRE
    parser = argparse.ArgumentParser(description="Forza Horizon session server")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=36000)
    parser.add_argument("--expire", type=float, default=EXPIRE,
                        help="seconds before an untouched session is dropped")
    args = parser.parse_args()
    EXPIRE = args.expire
    with Server((args.host, args.port), Handler) as server:
        log(f"session server on {args.host}:{args.port}")
        server.serve_forever()


if __name__ == "__main__":
    main()
