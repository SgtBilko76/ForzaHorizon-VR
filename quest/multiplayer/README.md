# Multiplayer (FH1 Xbox LIVE paths)

Status 2026-11-11: **two instances race each other online** on one PC, through
our own Linux session server (`first-race.png`: lobby with both players, then
the race with "Bilko 1 / Bilko2 2" and the other car in view).

## Pieces

- `fh-live-server.py`: session server (matchmaking only). Keeps hosted
  sessions, their attributes and QoS data; peers then talk directly over UDP.
  `python3 fh-live-server.py --host 0.0.0.0 --port 36000`.
- ShiftGlue patch `quest/patches/shiftglue-live-probe.patch`:
  - `kernel/xam/live_service.{h,cpp}`: server client, the instance's address
    (`net_address`), port offset (`net_port_offset`), XNADDR from the real
    IPv4 address, online XUID per instance (0009..., only after the first
    session: the save is checked against the profile at boot).
  - `apps/xgi_app.cpp`: XSessionCreate/Delete/Join/Leave/Start/End/Modify,
    Search/SearchEx/SearchByID and GetDetails against the server; empty but
    valid XUserReadStats results (the title reads row 0 unchecked).
  - `xam_net.cpp`: XNet addresses (XnAddrToInAddr = real IP), QoS lookup and
    listen through the server, keys, XNetConnect; overlapped WSARecvFrom
    (host thread per pending receive, keyed by socket + overlapped),
    WSAGetOverlappedResult, WSACancelOverlappedIO, WSASendTo completion,
    errno -> WSA error codes, getsockname; `net_trace` + 5 s packet counters.
  - `system/xsocket.cpp`: Winsock option levels/names (0xFFFF -> SOL_SOCKET,
    big-endian values), FIONBIO/FIONREAD, bind/send on the instance's loopback
    alias and with the port offset.
  - `xam_user.cpp`: `xam_grant_privileges`, online XUID in GetXUID/SigninInfo.

| cvar | effect |
|---|---|
| `live_server` | session server `host:port` (empty: offline as before) |
| `net_address` | IPv4 address announced to peers (empty: first non-loopback) |
| `net_port_offset` | added to the title's ports on the host (several instances per host) |
| `xam_report_live_signin` | profile signed into LIVE, ethernet link up |
| `xam_grant_privileges` | grant XamUserCheckPrivilege (multiplayer, voice) |
| `net_trace` | log XNet/socket calls (first 40 per kind) and XGI messages |

## Two-instance test (headless, lavapipe, no GPU)

`duo2.sh`: server on 127.0.0.1, A = 127.0.0.2 with the owner's save (hosts),
B = 127.0.0.3 with a fresh profile `B13EBABEBABEBAB2` (joins), port offset
30000. Scripts (`mk.py` builds them; game-frame clock synced on
`pressstart.wmv`): `duo-host.fh1test`, `duo-join-fresh.fh1test`. B starts
once A's quick match session is on the server. Both launch directly
(`runb.sh`): `pinyon.py launch` refuses a second instance. Single runs:
`run.sh <script> <statedir> [cvars]`, `grid.py` tiles the captures.

Menu path: Quick Match = title menu MULTIPLAYER -> dismiss the "Horizon server
unavailable" notice -> Find Game -> default playlist -> Quick Match.

Pitfalls found:
- Two instances with the same save need different profiles: a save loaded
  under another XUID crashes the title at boot (save check) -> B uses a fresh
  profile (title menu right after the brightness screen).
- Overlapped receives must report "done" before the result is visible, and
  several may be pending per socket, or the joiner stalls after 2 packets.
- The server drops sessions untouched for `--expire` seconds (default 900).

## Next

1. Session heartbeat (UPDATE) so long lobbies don't expire on the server.
2. Quest: Android build of the patch, server address setting, Wi-Fi test.
3. Race sync over a real network (latency, packet loss).
