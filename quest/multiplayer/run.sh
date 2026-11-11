#!/bin/bash
# run.sh <script> <statedir> [extra cvars...] : lavapipe headless run + png grid of captures
S=$1; D=$2; shift 2
cd /mnt/Workspace/fh-mp && rm -rf $D && mkdir -p $D && cp -r seed/user $D/
cd /mnt/Workspace/ForzaHorizon-VR && VK_DRIVER_FILES=/usr/share/vulkan/icd.d/lvp_icd.json SDL_VIDEO_DRIVER=offscreen timeout 900 python3 tools/pinyon.py launch --base-disc --hidden --skip-shader-preparation --state-root /mnt/Workspace/fh-mp/$D --render-test-script $S --render-test-output /mnt/Workspace/fh-mp/$D/out --timeout 880 --json -- --net_trace=true "$@" > /mnt/Workspace/fh-mp/$D/launch.json 2>&1
tail -1 /mnt/Workspace/fh-mp/$D/launch.json | cut -c1-120
python3 "$(dirname "$0")"/grid.py /mnt/Workspace/fh-mp/$D/out
