#!/usr/bin/env python3
# mk.py out.fh1test "press:frame:buttons ..." "cap:start:end:step" stop
# Game-frame clock (no clock-hz), synced on pressstart.wmv; presses hold 10 frames.
import sys
out, presses, caps, stop = sys.argv[1], sys.argv[2].split(), sys.argv[3].split(), int(sys.argv[4])
L=["pinyon-shift-fh1-render-test-v1","wait 1 30000 file pressstart.wmv","input 0 0000 0 0 0 0 0 0"]
for p in presses:
    f,b=p.split(':'); f=int(f)
    L+= [f"input {f} {b} 0 0 0 0 0 0", f"input {f+10} 0000 0 0 0 0 0 0"]
for c in caps:
    a,b,s=map(int,c.split(':'))
    L+= [f"capture {f} f{f}" for f in range(a,b+1,s)]
L.append(f"stop {stop}")
open(out,'w').write("\n".join(L)+"\n")
