#!/bin/sh
# Builds Mesa Turnip for the Quest 3 as an adrenotools package.
# Horizon OS's SELinux policy refuses IOCTL_KGSL_GPUMEM_ALLOC_ID / FREE_ID to
# apps, so stock Turnip fails at vkCreateDevice ("BO init"); the patch moves
# every allocation to the GPUOBJ ioctls, which are allowed.
#   quest/turnip/build-turnip-quest.sh [workdir]   (default /mnt/Workspace/turnip-quest)
# Then: pinyon.py android push-driver --name turnip-quest <workdir>/pkg/turnip-quest
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
W=${1:-/mnt/Workspace/turnip-quest}
MESA_COMMIT=a995eaeda4339adb0278b06dcc98136011feac36   # Turnip Gen8 V37's base
NDK=${NDK:-/mnt/Workspace/android-sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/linux-x86_64/bin}
mkdir -p "$W" && cd "$W"
[ -d .venv ] || { uv venv -q .venv && . .venv/bin/activate && uv pip install -q meson mako pyyaml packaging; }
. .venv/bin/activate
# flex without root: unpack the distribution's package.
[ -x flexroot/usr/bin/flex ] || { apt-get download flex && dpkg-deb -x flex_*.deb flexroot; }
export PATH="$W/flexroot/usr/bin:$PATH"
if [ ! -d mesa/.git ]; then
  git init -q mesa
  git -C mesa remote add origin https://gitlab.freedesktop.org/mesa/mesa.git
fi
git -C mesa fetch -q --depth=1 origin $MESA_COMMIT
git -C mesa checkout -q -f --detach FETCH_HEAD
cd mesa
git apply "$HERE/tu-kgsl-gpuobj-quest.patch"
# Android gralloc/UBWC fixes from StevenMXZ's V37 recipe (GPL-3.0 scripts,
# fetched, not vendored). Without them the game's frames reach the XR
# surface in the wrong layout (whole screen of vertical stripes).
SX=$W/sx; SXREF=A8xx
mkdir -p "$SX/aimapper"
for f in fix_gralloc_flushall.py add_aimapper_gralloc.py add_ubwc_swapchain_usage.py aimapper/u_gralloc_aimapper.c; do
  curl -sfL "https://raw.githubusercontent.com/StevenMXZ/Adreno-Tools-Drivers/$SXREF/patches/patchsv1/android/$f" -o "$SX/$f"
done
for s in fix_gralloc_flushall add_aimapper_gralloc add_ubwc_swapchain_usage; do python3 "$SX/$s.py"; done
# NDK r29 fixes (StevenMXZ/Adreno-Tools-Drivers build_turnip.sh).
sed -i 's/typedef const native_handle_t\* buffer_handle_t;/typedef void* buffer_handle_t;/g' include/android_stub/cutils/native_handle.h
sed -i 's/, hnd->handle/, (void *)hnd->handle/g' src/util/u_gralloc/u_gralloc_fallback.c
sed -i -E 's/([a-z_]+)->handle->/((const native_handle_t *)\1->handle)->/g' src/vulkan/runtime/vk_android.c
sed -i 's/anb->handle->/((const native_handle_t *)anb->handle)->/g' src/vulkan/runtime/vk_android.c
sed -i "/-Werror=gnu-empty-initializer/d" meson.build
W_ARGS="'-D__ANDROID__', '-Wno-error', '-Wno-gnu-empty-initializer', '-Wno-deprecated-declarations', '-Wno-incompatible-pointer-types-discards-qualifiers', '-Wno-incompatible-pointer-types'"
cat > ../android-aarch64.txt <<X
[binaries]
ar = '$NDK/llvm-ar'
c = ['$NDK/aarch64-linux-android35-clang']
cpp = ['$NDK/aarch64-linux-android35-clang++', '-fno-exceptions', '-fno-unwind-tables', '-fno-asynchronous-unwind-tables', '--start-no-unused-arguments', '-static-libstdc++', '--end-no-unused-arguments']
c_ld = '$NDK/ld.lld'
cpp_ld = '$NDK/ld.lld'
strip = '$NDK/llvm-strip'
pkg-config = ['env', 'PKG_CONFIG_LIBDIR=$NDK/pkg-config', '/usr/bin/pkg-config']
[built-in options]
c_args = [$W_ARGS]
cpp_args = [$W_ARGS]
[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8'
endian = 'little'
X
printf "[binaries]\nc = ['clang']\ncpp = ['clang++']\n" > ../native.txt
rm -rf ../build ../out
meson setup ../build --cross-file ../android-aarch64.txt --native-file ../native.txt \
  --prefix "$W/out" -Dbuildtype=release -Dstrip=true -Dplatforms=android -Dvideo-codecs= \
  -Dplatform-sdk-version=35 -Dandroid-stub=true -Dgallium-drivers= -Dvulkan-drivers=freedreno \
  -Dvulkan-beta=true -Dfreedreno-kmds=kgsl -Degl=disabled -Dandroid-libbacktrace=disabled
ninja -C ../build -j6 install
mkdir -p "$W/pkg/turnip-quest"
cp "$W/out/lib/libvulkan_freedreno.so" "$HERE/meta.json" "$W/pkg/turnip-quest/"
echo "package: $W/pkg/turnip-quest"
