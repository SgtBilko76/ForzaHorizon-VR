# ForzaHorizon-VR

*Forza Horizon* (Xbox 360, 2012) as a standalone VR game for Meta Quest 3,
built on [Pinyon Shift](https://github.com/arcanite24/pinyon-shift), which
statically recompiles the game's own code to run natively. Menus, the map and
the chase cameras are on a large curved screen; the cockpit and hood cameras
are head-tracked.

Downloads and install steps: [portroyale.online](https://portroyale.online/forzahorizon.html)
and the [releases](https://github.com/SgtBilko76/ForzaHorizon-VR/releases).

## Why the first start takes 25 to 35 minutes

The app contains no code or data from the game: that belongs to Microsoft,
and a download that carried it could not be shared legally. You bring your
own disc, and on the first start the headset makes the game from it:

1. **Translating** (about 10 minutes): the game's Xbox 360 program on your
   disc is turned into C++ source code.
2. **Compiling** (about 13 minutes): that source is compiled for the
   headset's processor, four files at a time.
3. **Linking** (about a minute): the pieces become the game's libraries.

A PC build of the same game does exactly this on the player's own machine.
The headset does it once after installing or updating; later starts take
the usual few seconds. If the headset sleeps or the app is closed during the
build, it continues where it stopped. About 4 GB must be free while it runs;
the temporary files are deleted at the end.

## How it is built

- `quest/buildkit/make-buildkit.py` prepares what the APK carries for the
  on-headset build: the translator (rexglue, built for Android), LLVM clang
  and lld (Termux's builds, licences inside the kit), the headers and the
  runtime objects to link against. It refuses to add anything translated.
- `quest/buildkit/build.sh` is the build the headset runs;
  `android/java/studio/deimos/pinyonshift/BuildActivity.java` shows its
  progress and resumes it.
- The package: `python3 tools/pinyon_android.py android build`, then
  `python3 quest/buildkit/make-buildkit.py` and
  `PINYON_QUEST_RELEASE=1 python3 tools/pinyon_android.py android package`.

The engine is the `forzahorizon-vr` branch of
[SgtBilko76/shiftglue-sdk](https://github.com/SgtBilko76/shiftglue-sdk).
Pinyon Shift and ShiftGlue are BSD-3-Clause. Forza Horizon is a trademark of
Microsoft; this unofficial fan project is not affiliated with Microsoft or
Playground Games.
