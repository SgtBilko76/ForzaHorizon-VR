# Multiplayer probe (FH1 Xbox LIVE paths)

Headless PC runs that drive the game's menus with a render-test script and log
every XNet / socket / XGI session call.

- `run.sh <script> <statedir> [cvars]` boots the Linux build on lavapipe
  (software Vulkan, no GPU, no display) with a copy of the profile in
  `/mnt/Workspace/fh-mp/seed/user`, then `grid.py` tiles the captures.
- `mp-find.fh1test`: Press Start, title menu -> MULTIPLAYER, dismiss the
  server notice, Find Game, accept the default playlist, Quick Match.

Cvars (ShiftGlue patch `quest/patches/shiftglue-live-probe.patch`):

| cvar | effect |
|---|---|
| `net_trace` | log XNet/socket calls (first 40 per kind) and XGI messages |
| `xam_report_live_signin` | profile signed into LIVE, ethernet link up, XSessionCreate fills id/host/key/nonce |
| `xam_grant_privileges` | grant XamUserCheckPrivilege (252 voice, 254 multiplayer) |

XUserReadStats (0xB0021) now returns valid, zeroed views/rows (the title
reads row 0 unchecked and crashed on null).

## Reached (one instance)

Party lobby -> "Horizon server unavailable" notice (no XP) -> Find Game ->
default playlist -> Quick Match -> XSessionSearchEx (0xB001C) finds nothing
-> the title hosts a game session (flags 0x2D) -> "QUICK MATCH
POINT-TO-POINT RACE, waiting for players to join".

## Missing for two players

1. A session service: XSessionSearchEx / SearchByID results, host
   registration (own Linux server; Xenia-WebServices REST API is a reference).
2. XNet: title XnAddr with the real LAN/WAN address, XnAddrToInAddr /
   InAddrToXnAddr, key registration, QoS lookup.
3. VDP/UDP traffic between the instances (port mapping when both run on one
   host).
