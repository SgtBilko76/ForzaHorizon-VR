#!/bin/bash
# quest-vs-pc.sh: LAN test. Session server on 0.0.0.0:36000 + a headless PC
# instance (fresh profile "Bilko2", 192.168.178.22) that opens a quick match
# lobby and waits; the Quest joins it through MULTIPLAYER -> Find Game ->
# Quick Match. Quest args: quest/multiplayer/android-arguments.txt.
M=/mnt/Workspace/fh-mp; R=/mnt/Workspace/ForzaHorizon-VR
PC_IP=${PC_IP:-192.168.178.22}
python3 $R/quest/multiplayer/fh-live-server.py --host 0.0.0.0 --expire 7200 > $M/server-lan.log 2>&1 &
SRV=$!
python3 $M/tools/mk.py $M/scripts/pc-host.fh1test \
  "60:0010 200:1000 330:0002 360:1000 500:1000 560:1000 640:1000 720:1000 800:1000 900:1000 1000:1000 1100:1000" \
  "850:1250:100 1500:30000:1500" 30100
rm -rf $M/p && mkdir -p $M/p
cd $R/out/build/linux-amd64-release && env SDL_VIDEO_DRIVER=offscreen \
  VK_DRIVER_FILES=/usr/share/vulkan/icd.d/lvp_icd.json \
  PINYON_SHIFT_STATE_ROOT=$M/p PINYON_SHIFT_GAME_ROOT=$R/.local/game/base REX_WINDOW_HIDDEN=1 \
  PINYON_SHIFT_FH1_RENDER_TEST_SCRIPT=$M/scripts/pc-host.fh1test \
  PINYON_SHIFT_FH1_RENDER_TEST_OUTPUT=$M/p/out \
  timeout 7200 ./pinyon_shift --net_trace=true --xam_report_live_signin=true \
  --xam_grant_privileges=true --live_server=127.0.0.1:36000 --net_address=$PC_IP \
  --net_port_offset=30000 --user_xuid=B13EBABEBABEBAB2 --user_name=Bilko2 \
  --audio_mute=true --pinyon_shift_skip_opening_movies=true > $M/p/stdout.log 2>&1
kill $SRV
