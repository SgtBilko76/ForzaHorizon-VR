#!/usr/bin/env python3
"""Make the Quest release's build kit (no game code in it).

The Quest release builds the game's libraries on the headset from the
player's own disc files (android/.../BuildActivity.java, quest/buildkit/
build.sh). This script prepares what the APK carries for that:

  .local/android/buildkit/lib/*.so   programs and their libraries, packaged as
                                     lib/arm64-v8a/lib*.so so the app may run
                                     them: rexglue (built here for Android),
                                     clang and lld (Termux's LLVM builds)
  .local/android/buildkit/buildkit.zip
      ndk/      the NDK sysroot headers the translated code includes
      src/      the repository's and ShiftGlue's headers it includes
      clangres/ clang's own headers
      link/     link inputs: the host objects of this build, the runtime
                libraries the APK carries, NDK stubs and static libraries
      config/   the rexglue manifest and analysis files
      build.sh  the on-device build
      LICENSES/ the toolchain's licences

It refuses to put anything from .local/ (game files, translated code) into
the kit. Run after `pinyon.py android build`, which builds the host objects
this kit links against.
"""
from __future__ import annotations

import hashlib
import io
import json
import lzma
import os
import re
import shlex
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / "out" / "build" / "android-arm64-release"
WORK = ROOT / ".local" / "android" / "buildkit"
TERMUX = "https://packages.termux.dev/apt/termux-main"
TERMUX_PACKAGES = ["clang", "lld"]
# Renamed so Android installs them (lib*.so only) and they do not clash with
# the game's libraries: the NDK's libc++_shared.so stays the game's.
RENAMES = {
    "clang-21": "libfhvr_clang.so",
    "lld": "libfhvr_lld.so",
    "libc++_shared.so": "libfhvr_cxx.so",
    "libz.so.1": "libfhvr_z.so",
    "libzstd.so.1": "libfhvr_zstd.so",
    "libxml2.so.16": "libfhvr_xml2.so",
}
KEEP = {"libLLVM.so", "libclang-cpp.so", "libffi.so", "libiconv.so"}


def run(command: list, **kwargs) -> subprocess.CompletedProcess:
    return subprocess.run([str(part) for part in command], check=True, **kwargs)


def ndk() -> Path:
    config = json.loads((ROOT / "config" / "android-toolchain.json").read_text(encoding="utf-8"))
    sdk = Path(os.environ.get("ANDROID_HOME", "/mnt/Workspace/android-sdk"))
    return sdk / "ndk" / config["android_sdk"]["ndk"] / "toolchains" / "llvm" / "prebuilt" / "linux-x86_64"


def ninja_vars(obj: str) -> dict:
    lines = (BUILD / "build.ninja").read_text(encoding="utf-8").splitlines()
    start = next(i for i, line in enumerate(lines) if line.startswith(f"build {obj}:"))
    values = {}
    for line in lines[start + 1:]:
        if not line.startswith("  "):
            break
        key, _, value = line.strip().partition(" = ")
        values[key] = value
    return values


# ---- Termux toolchain ------------------------------------------------------

def termux_toolchain() -> Path:
    cache = WORK / "termux"
    root = cache / "root"
    marker = cache / "packages.json"
    if marker.is_file():
        return root / "data" / "data" / "com.termux" / "files" / "usr"
    shutil.rmtree(cache, ignore_errors=True)
    (cache / "debs").mkdir(parents=True)
    index = urllib.request.urlopen(
        f"{TERMUX}/dists/stable/main/binary-aarch64/Packages", timeout=120).read().decode()
    packages = {}
    for block in index.split("\n\n"):
        fields = dict(line.split(": ", 1) for line in block.splitlines()
                      if ": " in line and not line.startswith(" "))
        if "Package" in fields:
            packages[fields["Package"]] = fields
    wanted, stack = {}, list(TERMUX_PACKAGES)
    while stack:
        name = stack.pop()
        if name in wanted or name not in packages:
            continue
        wanted[name] = packages[name]
        for dependency in re.split(r",\s*", packages[name].get("Depends", "")):
            if dependency:
                stack.append(dependency.split()[0].split("|")[0])
    root.mkdir()
    for name, fields in sorted(wanted.items()):
        data = urllib.request.urlopen(f"{TERMUX}/{fields['Filename']}", timeout=300).read()
        if hashlib.sha256(data).hexdigest() != fields["SHA256"]:
            raise SystemExit(f"{name}: download does not match the index's SHA-256")
        (cache / "debs" / Path(fields["Filename"]).name).write_bytes(data)
        # A .deb is an ar archive; its data.tar.* holds the files.
        members, offset = {}, 8
        while offset < len(data):
            header = data[offset:offset + 60]
            member = header[:16].decode().strip().rstrip("/")
            size = int(header[48:58].decode().strip())
            members[member] = data[offset + 60:offset + 60 + size]
            offset += 60 + size + (size & 1)
        payload_name = next(member for member in members if member.startswith("data.tar"))
        payload = members[payload_name]
        if payload_name.endswith(".xz"):
            payload = lzma.decompress(payload)
        elif payload_name.endswith(".zst"):
            payload = subprocess.run(["zstd", "-dc"], input=payload, capture_output=True,
                                     check=True).stdout
        with tarfile.open(fileobj=io.BytesIO(payload)) as archive:
            archive.extractall(root, filter="tar")
    marker.write_text(json.dumps({name: fields["Version"] for name, fields in wanted.items()},
                                 indent=2) + "\n")
    return root / "data" / "data" / "com.termux" / "files" / "usr"


def patchelf(*arguments) -> None:
    run(["uv", "run", "-q", "--with", "patchelf", "patchelf", *arguments])


def package_tools(usr: Path, strip: Path) -> list[Path]:
    """The programs and libraries the app runs, renamed and relinked."""
    out = WORK / "lib"
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True)
    sources = {"clang-21": usr / "bin" / "clang-21", "lld": usr / "bin" / "lld"}
    for name in list(RENAMES) + sorted(KEEP):
        if name not in sources:
            sources[name] = usr / "lib" / name
    rexglue = BUILD / "rexglue-artifacts" / "rexglue"
    run(["ninja", "-C", BUILD, "rexglue"], stdout=subprocess.DEVNULL)
    files = []
    for name, source in sources.items():
        target = out / RENAMES.get(name, name)
        shutil.copyfile(source.resolve(), target)
        files.append(target)
    target = out / "libfhvr_rexglue.so"
    shutil.copyfile(rexglue, target)
    run([strip, "--strip-all", target])
    files.append(target)
    for path in files:
        os.chmod(path, 0o755)
        if path.name != "libfhvr_rexglue.so":
            arguments = ["--remove-rpath"]
            for old, new in RENAMES.items():
                if old.startswith("lib"):
                    arguments += ["--replace-needed", old, new]
            if path.name.startswith("libfhvr_") and path.name not in (
                    "libfhvr_clang.so", "libfhvr_lld.so"):
                arguments += ["--set-soname", path.name]
            patchelf(*arguments, path)
    for path in files:
        needed = subprocess.run(["readelf", "-d", path], capture_output=True, text=True).stdout
        for library in re.findall(r"NEEDED.*\[(.*)\]", needed):
            system = library in ("libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so",
                                 "libOpenSLES.so")
            if not system and library != "librexruntime.so" and \
                    library != "libc++_shared.so" and not (out / library).is_file():
                raise SystemExit(f"{path.name} needs {library}, which is not packaged")
    return files


# ---- Kit -------------------------------------------------------------------

def header_dependencies(ndk_root: Path) -> set[Path]:
    generated = ROOT / ".local" / "generated"
    files = []
    for module, obj_dir in (("default", "pinyon_shift_recomp.dir"),
                            ("speech", "pinyon_shift_SpeechFacade_default.dir"),
                            ("xmedia", "pinyon_shift_XMediaFacade_default.dir")):
        fixed = ["pinyon_shift_init.cpp", "pinyon_shift_folds.cpp", "pinyon_shift_recomp.0.cpp",
                 "module_registry.cpp" if module == "default" else "pinyon_shift_register.cpp"]
        values = ninja_vars(f"CMakeFiles/{obj_dir}/.local/generated/{module}/pinyon_shift_recomp.0.cpp.o")
        # Without CMake's precompiled header (-Xclang pairs): the scan
        # includes the generated pch header itself.
        tokens, flags, i = shlex.split(values["FLAGS"]), [], 0
        while i < len(tokens):
            if tokens[i] == "-Xclang":
                i += 2
                continue
            if tokens[i] != "-Winvalid-pch":
                flags.append(tokens[i])
            i += 1
        for name in fixed + ["pinyon_shift_pch.h"]:
            files.append((generated / module / name, values, flags))
    deps = set()
    for source, values, flags in files:
        command = ["clang++-21", "--target=aarch64-none-linux-android33",
                   f"--sysroot={ndk_root / 'sysroot'}", *shlex.split(values["DEFINES"]),
                   *shlex.split(values["INCLUDES"]), *flags, "-M"]
        if source.suffix == ".h":
            command += ["-x", "c++-header"]
        else:
            command += ["-include", str(source.parent / "pinyon_shift_pch.h")]
        result = subprocess.run(command + [str(source)], capture_output=True, text=True)
        if result.returncode:
            raise SystemExit(f"header scan of {source.name} failed: {result.stderr[-800:]}")
        for token in result.stdout.replace("\\\n", " ").split():
            if not token.endswith(":"):
                deps.add(Path(token).resolve())
    return deps


def kit(ndk_root: Path, usr: Path, strip: Path) -> Path:
    stage = WORK / "kit"
    shutil.rmtree(stage, ignore_errors=True)
    stage.mkdir(parents=True)
    local = (ROOT / ".local").resolve()
    for dep in sorted(header_dependencies(ndk_root)):
        if dep.is_relative_to(local):
            continue  # translated code: the device makes its own
        if dep.is_relative_to(ndk_root):
            target = stage / "ndk" / dep.relative_to(ndk_root)
        elif dep.is_relative_to(ROOT):
            target = stage / "src" / dep.relative_to(ROOT)
        elif str(dep).startswith("/usr/lib/llvm"):
            continue  # the host clang's own headers; the kit has Termux's
        else:
            raise SystemExit(f"unexpected header {dep}")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(dep, target)
    shutil.copytree(ndk_root / "sysroot" / "usr" / "include" / "bits",
                    stage / "ndk" / "sysroot" / "usr" / "include" / "bits", dirs_exist_ok=True)
    shutil.copytree(usr / "lib" / "clang" / "21" / "include", stage / "clangres" / "include")
    # Link inputs, from this build's libmain.so link line.
    link = stage / "link"
    (link / "sys").mkdir(parents=True)
    (link / "host").mkdir()
    api = ndk_root / "sysroot" / "usr" / "lib" / "aarch64-linux-android"
    for name in ("crtbegin_so.o", "crtend_so.o", "libdl.so", "libEGL.so", "libGLESv3.so",
                 "libandroid.so", "liblog.so", "libm.so", "libOpenSLES.so", "libc.so", "libc++.so"):
        shutil.copy2(api / "33" / name, link / "sys" / name)
    shutil.copy2(api / "libc++_shared.so", link / "sys")
    builtins = ndk_root / "lib" / "clang" / "21" / "lib" / "linux"
    shutil.copy2(builtins / "aarch64" / "libunwind.a", link / "sys")
    shutil.copy2(builtins / "aarch64" / "libatomic.a", link / "sys")
    shutil.copy2(builtins / "libclang_rt.builtins-aarch64-android.a", link / "sys")
    shutil.copy2(ROOT / "thirdparty" / "openxr" / "lib" / "arm64-v8a" / "libopenxr_loader.so",
                 link / "sys")
    for name in ("librexgpu-fh1.so", "librexruntime.so", "libspdlog.a", "libfmt.a", "libSDL3.a",
                 "libxxhash.a"):
        shutil.copy2(BUILD / "rexglue-artifacts" / name, link / name)
    commands = subprocess.run(["ninja", "-C", BUILD, "-t", "commands", "libmain.so"],
                              capture_output=True, text=True, check=True).stdout.splitlines()[-1]
    objects = re.findall(r"CMakeFiles/pinyon_shift(?:_host)?\.dir/\S+\.o", commands)
    if not objects:
        raise SystemExit("no host objects on the libmain.so link line")
    order = []
    for obj in objects:
        name = obj.replace("/", "_")
        shutil.copy2(BUILD / obj, link / "host" / name)
        order.append(name)
    (link / "host" / "order.txt").write_text("\n".join(order) + "\n")
    shutil.copy2(ROOT / "config" / "performance" / "symbol-order-base.txt", link)
    # Debug sections of this project's objects and libraries (the NDK's
    # stubs and static libraries stay as shipped).
    for path in [*(link / "host").glob("*.o"), *link.glob("*.a"), *link.glob("*.so")]:
        run([strip, "--strip-debug", path])
    # rexglue's manifest and analysis files, and the build itself.
    (stage / "config" / "rexglue" / "analysis").mkdir(parents=True)
    shutil.copy2(ROOT / "config" / "rexglue" / "pinyon_shift_manifest.toml",
                 stage / "config" / "rexglue")
    for path in (ROOT / "config" / "rexglue" / "analysis").glob("*.toml"):
        shutil.copy2(path, stage / "config" / "rexglue" / "analysis")
    shutil.copy2(ROOT / "quest" / "buildkit" / "build.sh", stage / "build.sh")
    licenses = stage / "LICENSES"
    licenses.mkdir()
    for path in (usr / "share" / "doc").glob("*/*"):
        if path.is_file():
            shutil.copy2(path, licenses / f"{path.parent.name}-{path.name}")
    versions = json.loads((WORK / "termux" / "packages.json").read_text())
    (licenses / "README.txt").write_text(
        "The build tools in this app are Termux's builds (https://termux.dev,\n"
        "sources: https://github.com/termux/termux-packages), unmodified except\n"
        "for library names, of:\n" + "".join(f"  {name} {version}\n" for name, version in
                                             sorted(versions.items())))
    # Nothing translated from the game may be in the kit.
    for path in stage.rglob("*"):
        if path.is_file() and ("generated" in path.parts or "pinyon_shift_recomp" in path.name):
            raise SystemExit(f"translated code in the kit: {path}")
    archive = WORK / "buildkit.zip"
    archive.unlink(missing_ok=True)
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zip_file:
        for path in sorted(stage.rglob("*")):
            if path.is_file():
                zip_file.write(path, path.relative_to(stage))
    return archive


def main() -> int:
    ndk_root = ndk()
    strip = ndk_root / "bin" / "llvm-strip"
    usr = termux_toolchain()
    tools = package_tools(usr, strip)
    archive = kit(ndk_root, usr, strip)
    print(json.dumps({
        "tools": {path.name: path.stat().st_size for path in tools},
        "tools_bytes": sum(path.stat().st_size for path in tools),
        "kit": str(archive), "kit_bytes": archive.stat().st_size,
        "kit_unpacked_bytes": sum(path.stat().st_size for path in (WORK / "kit").rglob("*")
                                  if path.is_file()),
    }, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
