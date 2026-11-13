#!/system/bin/sh
# The Quest release's first-start build (BuildActivity): turns the player's
# own default.xex and facades into libmain.so and the two facade libraries.
#   1. translate: rexglue codegen writes the game as C++ (about 10 minutes)
#   2. compile:   clang, $1 jobs (default 4), largest files first (about 13)
#   3. link:      lld, against the runtime the APK carries and the host
#                 objects in the kit
# Environment from the app: W work folder, K unpacked kit, T the app's native
# library folder (rexglue, clang and lld are packaged there as lib*.so, the
# one place an app may run programs from), GAME the extracted game files.
# status.txt holds "<stage> <done> <total> <seconds in stage>" for the
# progress screen. Finished files are kept, so a stopped build goes on
# where it stopped when it is run again.
set -u
J=${1:-4}
G=$W/.local/generated
O=$W/obj
OUT=$W/out
L=$K/link
CC=$T/libfhvr_clang.so
LD=$T/libfhvr_lld.so
REXGLUE=$T/libfhvr_rexglue.so
export LD_LIBRARY_PATH=$T
mkdir -p $O/default $O/speech $O/xmedia $OUT $W/pch $W/config/rexglue

now() { cut -d' ' -f1 /proc/uptime; }
T0=$(now)
log() { echo "[$(now)] $*" | tee -a $W/build.log; }
status() {
  echo "$1 $2 $3 $(echo "$(now) ${STAGE_T:-$T0}" | awk '{printf "%d", $1-$2}')" > $W/status.txt.tmp
  mv $W/status.txt.tmp $W/status.txt
}
fail() { log "$1"; status failed 0 1; exit 1; }

# 1. translate (skipped when a complete translation is there)
if [ ! -f $G/.translated ]; then
  STAGE_T=$(now); status translate 0 1
  log "translating"
  rm -rf $G
  # The manifest names the game files beside the repository; here they are
  # in the app's external storage.
  cp -r $K/config/rexglue/. $W/config/rexglue/
  sed -i "s#\.\./\.\./\.local/game/base#$GAME#g" $W/config/rexglue/*.toml
  (cd $W && $REXGLUE --log-level info --log-file $W/codegen.log codegen \
      config/rexglue/pinyon_shift_manifest.toml > $W/translate.txt 2>&1) \
    || fail "TRANSLATE FAILED: $(tail -3 $W/translate.txt)"
  for m in default speech xmedia; do
    [ -f $G/$m/pinyon_shift_recomp.0.cpp ] || fail "TRANSLATE FAILED: no $m output"
  done
  touch $G/.translated
fi
status translate 1 1

TGT="--target=aarch64-none-linux-android33 --sysroot=$K/ndk/sysroot -resource-dir $K/clangres"
DEF="-DREXGLUE_ENABLE_PERF_COUNTERS -DREX_HAS_VULKAN=1 -DREX_PHYSICAL_HEAP_4K_PAGES=1 -DREX_RUNTIME_IMPORTS=1 -DSPDLOG_COMPILED_LIB -DSPDLOG_FMT_EXTERNAL"
FL="-DANDROID -fdata-sections -ffunction-sections -funwind-tables -fstack-protector-strong -D_FORTIFY_SOURCE=2 -O3 -DNDEBUG -std=c++23 -fPIC -march=armv8.2-a -fno-strict-aliasing -pthread -w"
S=$K/src/thirdparty/shiftglue-sdk
B=$K/src/out/build/android-arm64-release
INC_SDK="-I$S/include -I$B/rexglue-sdk/include -I$S/thirdparty/simde -I$S/thirdparty/simde/simde -I$S/thirdparty/fmt/include -I$S/thirdparty/spdlog/include -I$B/rexglue-sdk/thirdparty/sdl3/include-revision -I$S/thirdparty/sdl3/include -I$S/thirdparty/xxHash/cmake_unofficial/.. -I$S/thirdparty/vulkan-headers/include -I$S/thirdparty/vulkan-memory-allocator/include -isystem $S/thirdparty/tomlplusplus/include -isystem $S/thirdparty/spirv-tools/include"

flags() {  # module
  case $1 in
    default) echo "$TGT $DEF $FL -I$K/src -I$K/src/src -I$G/default $INC_SDK" ;;
    speech)  echo "$TGT $DEF -Dpinyon_shift_SpeechFacade_default_EXPORTS $FL -fvisibility=hidden -I$G/speech $INC_SDK" ;;
    xmedia)  echo "$TGT $DEF -Dpinyon_shift_XMediaFacade_default_EXPORTS $FL -fvisibility=hidden -I$G/xmedia $INC_SDK" ;;
  esac
}
sources() {  # module -> file names in link order
  if [ $1 = default ]; then echo pinyon_shift_init.cpp module_registry.cpp pinyon_shift_folds.cpp
  else echo pinyon_shift_init.cpp pinyon_shift_register.cpp pinyon_shift_folds.cpp; fi
  n=0; while [ -f $G/$1/pinyon_shift_recomp.$n.cpp ]; do echo pinyon_shift_recomp.$n.cpp; n=$((n+1)); done
}

# 2a. precompiled headers
STAGE_T=$(now); status pch 0 3
i=0
for m in default speech xmedia; do
  if [ ! -f $W/pch/$m.pch ]; then
    $CC $(flags $m) -x c++-header $G/$m/pinyon_shift_pch.h -o $W/pch/$m.pch.tmp 2> $W/pch/$m.err \
      || fail "PCH $m FAILED: $(head -5 $W/pch/$m.err)"
    mv $W/pch/$m.pch.tmp $W/pch/$m.pch
  fi
  i=$((i+1)); status pch $i 3
done

# 2b. compile, J at a time, largest files first
: > $W/jobs.txt
for m in default speech xmedia; do
  for f in $(sources $m); do echo "$(stat -c %s $G/$m/$f) $m $f" >> $W/jobs.txt; done
done
sort -rn $W/jobs.txt | while read sz m f; do echo "$m $f"; done > $W/jobs.sorted
TOTAL=$(wc -l < $W/jobs.sorted)
{ echo 'case $1 in'; for m in default speech xmedia; do echo "$m) echo \"$(flags $m)\" ;;"; done; echo esac; } > $W/flagsof.sh
cat > $W/cc1.sh <<EOF
m=\$1; f=\$2
if [ ! -f $O/\$m/\$f.o ]; then
  if $CC \$(sh $W/flagsof.sh \$m) -include-pch $W/pch/\$m.pch -c $G/\$m/\$f -o $O/\$m/\$f.o.tmp 2> $O/\$m/\$f.err; then
    mv $O/\$m/\$f.o.tmp $O/\$m/\$f.o
  else
    rm -f $O/\$m/\$f.o.tmp; echo "FAIL \$m \$f" >> $W/fail.txt
  fi
fi
d=\$(ls $O/default/*.o $O/speech/*.o $O/xmedia/*.o 2>/dev/null | wc -l)
echo "compile \$d $TOTAL \$(echo "\$(cut -d' ' -f1 /proc/uptime) \$STAGE_T" | awk '{printf "%d", \$1-\$2}')" > $W/status.txt.\$\$
mv $W/status.txt.\$\$ $W/status.txt
EOF
rm -f $W/fail.txt $O/*/*.o.tmp
STAGE_T=$(now); export STAGE_T
DONE=$(ls $O/default/*.o $O/speech/*.o $O/xmedia/*.o 2>/dev/null | wc -l)
status compile $DONE $TOTAL
log "compiling: $DONE of $TOTAL already done, $J jobs"
xargs -P $J -n 2 sh $W/cc1.sh < $W/jobs.sorted
if [ -s $W/fail.txt ]; then
  f=$(head -1 $W/fail.txt | cut -d' ' -f2-3 | tr ' ' '/')
  fail "COMPILE FAILED ($(wc -l < $W/fail.txt) files): $(head -3 $O/$f.err)"
fi

# 3. link
STAGE_T=$(now); status link 0 3
objs() { for f in $(sources $1); do echo $O/$1/$f.o; done; }
HOST=$(while read o; do echo $L/host/$o; done < $L/host/order.txt)
COMMON="-flavor gnu -EL --fix-cortex-a53-843419 -z now -z relro -z max-page-size=16384 --use-android-relr-tags --pack-dyn-relocs=relr --hash-style=gnu --eh-frame-hdr -m aarch64linux -shared -L$L/sys --build-id=sha1 --no-undefined-version --fatal-warnings --no-undefined --gc-sections"
TAIL="-latomic -lm -lc++ -lm $L/sys/libclang_rt.builtins-aarch64-android.a -l:libunwind.a -ldl -lc $L/sys/libclang_rt.builtins-aarch64-android.a -l:libunwind.a -ldl $L/sys/crtend_so.o"
rm -f $OUT/*.tmp
$LD $COMMON -o $OUT/libmain.so.tmp $L/sys/crtbegin_so.o -Bsymbolic-functions \
  --symbol-ordering-file=$L/symbol-order-base.txt --no-warn-symbol-ordering -u JNI_OnLoad -soname libmain.so \
  $(objs default) $HOST -ldl $L/librexgpu-fh1.so -lopenxr_loader -lEGL -lGLESv3 -landroid -llog \
  $L/librexruntime.so $L/libspdlog.a $L/libfmt.a $L/libSDL3.a -lm -lOpenSLES -ldl $L/libxxhash.a \
  -landroid -llog $TAIL > $W/link.err 2>&1 || fail "LINK libmain FAILED: $(head -5 $W/link.err)"
status link 1 3
for m in speech:SpeechFacade xmedia:XMediaFacade; do
  mod=${m%%:*}; name=libpinyon_shift_${m##*:}_default.so
  $LD $COMMON -o $OUT/$name.tmp $L/sys/crtbegin_so.o -u JNI_OnLoad -soname $name $(objs $mod) \
    $L/librexruntime.so $L/libspdlog.a $L/libfmt.a $L/libSDL3.a -lm -lOpenSLES -ldl -llog -landroid \
    $L/libxxhash.a $TAIL > $W/link.err 2>&1 || fail "LINK $name FAILED: $(head -5 $W/link.err)"
  [ $mod = speech ] && status link 2 3
done
for f in libmain.so libpinyon_shift_SpeechFacade_default.so libpinyon_shift_XMediaFacade_default.so; do
  chmod 0644 $OUT/$f 2>/dev/null
  mv $OUT/$f.tmp $OUT/$f
done
# The intermediate files are not needed to play (about 1 GB).
rm -rf $O $W/pch $G
status done 1 1
log "done in $(echo "$(now) $T0" | awk '{printf "%d", $1-$2}') s"
