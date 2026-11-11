#!/bin/bash
# runb.sh <dir> <script> <ip> <xuid> <name> : direct launch (second instance)
M=/mnt/Workspace/fh-mp; R=/mnt/Workspace/ForzaHorizon-VR/out/build/linux-amd64-release
cd $R && env SDL_VIDEO_DRIVER=offscreen VK_DRIVER_FILES=/usr/share/vulkan/icd.d/lvp_icd.json \
  PINYON_SHIFT_STATE_ROOT=$M/$1 PINYON_SHIFT_GAME_ROOT=/mnt/Workspace/ForzaHorizon-VR/.local/game/base \
  REX_WINDOW_HIDDEN=1 PINYON_SHIFT_FH1_RENDER_TEST_SCRIPT=$2 PINYON_SHIFT_FH1_RENDER_TEST_OUTPUT=$M/$1/out \
  timeout 3400 ./pinyon_shift --net_trace=true --xam_report_live_signin=true --xam_grant_privileges=true \
  --live_server=127.0.0.1:36000 --net_address=$3 --net_port_offset=30000 --user_xuid=$4 --user_name=$5 \
  --audio_mute=true --pinyon_shift_skip_opening_movies=true > $M/$1/stdout.log 2>&1
echo "exit $?" >> $M/$1/stdout.log
