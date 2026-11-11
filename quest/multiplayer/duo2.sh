#!/bin/bash
# duo2.sh: server + A (host, 127.0.0.2) + B (join, 127.0.0.3), both direct launches, same profile id
M=/mnt/Workspace/fh-mp; R=/mnt/Workspace/ForzaHorizon-VR
python3 $R/quest/multiplayer/fh-live-server.py --host 127.0.0.1 --expire 7200 > $M/server.log 2>&1 &
SRV=$!
rm -rf $M/a $M/b && mkdir -p $M/a $M/b && cp -r $M/seed/user $M/a/
$M/tools/runb.sh a $M/scripts/duo-host.fh1test 127.0.0.2 B13EBABEBABEBABE Bilko &
A=$!
for i in $(seq 1 600); do grep -q 'create .* flags 0x2d' $M/server.log && break; sleep 2; done
echo "host session up after $((i*2)) s"
$M/tools/runb.sh b $M/scripts/duo-join-fresh.fh1test 127.0.0.3 B13EBABEBABEBAB2 Bilko2 &
B=$!
wait $B; echo "B done: $(tail -1 $M/b/stdout.log)"
wait $A; echo "A done: $(tail -1 $M/a/stdout.log)"
kill $SRV
