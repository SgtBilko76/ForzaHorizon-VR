# ForzaHorizon-VR

*Forza Horizon* (Xbox 360, 2012) as a standalone VR game for Meta Quest 3 and
PICO 4 Ultra (one APK; PICO untested so far), built on [Pinyon Shift](https://github.com/arcanite24/pinyon-shift), which
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

## VR camera mods

The head-tracked view drives the immersive camera mod's head layers, so
the game needs `immersive_camera` (Pinyon Shift's own, packaged in the APK)
and `zz-archive-patches`, the player's own `media/camera.zip` with the mod's
layers merged into its CameraPhysics.xml. That one is game data, so
`VrMods.java` builds it on the headset at each start when its inputs
changed, as `tools/build-mod-archives.py` does on a PC (`XmlMerge.java`,
`FhArchive.java`; `libfhvr_archive.so` exposes the runtime's LZX decoder),
and enables both mods. The result matches the PC tool's byte for byte.

## Stereoscopic 3D

The game draws one picture per frame; drawing it twice would halve the
frame rate. The engine makes the second eye from that picture and its scene
depth instead (`quest/patches/shiftglue-vr-stereo.patch`, which adds
`fh1_stereo.cpp`): at every swap the guest output becomes twice as wide, and
each scene pixel moves sideways by its disparity for each eye,
`IPD / 2 * (1 / depth - 1 / picture distance)` in pixels at the game's field
of view. The nearest pixel wins where several land; holes uncovered behind
nearer objects take the farther neighbour's colour. The HUD (from the
HUD-less copy's coverage) stays flat at the picture's distance, and frames
without a scene depth (menus) show the same picture to both eyes.
`quest_vr.cpp` gives each eye its half of the side-by-side picture as its
own layer, measures the player's eye distance from the headset's eye poses
and sets the picture distance per view: 10 m for the head-tracked view, 3 m
for the curved screen. It is always on in the VR build (`fh1_vr_stereo`,
set when the XR surface is handed to the game).

## Hand steering

`vr_hand_steering` (off by default; SETTINGS > VR HAND STEERING, right
under RESUME in the pause menu's SETTINGS screen, or Menu + both grips held
for a second while playing, which saves the same setting through
`quest::SetSettingSaver`) turns the Touch controllers into the player's
hands, only in the head-tracked cockpit and hood views, where the grips are
not needed for the menus' LB/RB tabs:

- A grip pressed anywhere else takes the steering wheel. Turning the hands
  (both, as on a wheel, or one around the wheel's centre) turns it by as much
  as they turn from where they took it, ±90 degrees for full lock, sent as the
  pad's left stick. Letting go centres it and the stick steers again.
- A right grip pressed beside the right knee (seat frame 0.25 m right,
  0.55 m below the eyes, 0.25 m ahead; `debug.fhvr.leverx/y/z`) takes a
  sequential lever: push forward 5 cm to shift up (the pad's B), pull back to
  shift down (X), then back to the middle. The hand ticks when it reaches the
  lever.
- The game's pad rumble (bumps, kerbs, impacts, engine) plays on the hands
  that hold the wheel (`debug.fhvr.rumble` scales it); Touch controllers have
  no motor to push back like a wheel's force feedback.
- With the grips taken, the camera button (RB) moves to the right stick's
  click; LB (clutch) and the left grip's d-pad are not available.

## Meta Quest and PICO

The same APK runs on both. Where the runtime lacks Meta's
`XR_KHR_android_surface_swapchain` (PICO), the game's picture goes through a
SurfaceTexture copied into a standard GLES swapchain
(`setprop debug.fhvr.texpath 1` forces that path on Quest for testing); the
PICO controllers are bound through `XR_BD_controller_interaction`. A PICO VR
app can't show flat windows, so the setup and build screens run on an OpenXR
VR panel on both headsets (`src/quest/vr_shell.cpp`, `VrShell.java`).
The older PICO 4 / Neo 3 lack Vulkan 1.3 and Android 13 and are not supported.

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
