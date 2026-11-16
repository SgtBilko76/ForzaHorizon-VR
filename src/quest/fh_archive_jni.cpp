// The LZX (XMemCompress) decoder for the game's archive members, from the
// app's Java code: VrMods builds the VR camera mod from the player's own
// media/camera.zip on the headset, which needs its CameraPhysics.xml
// unpacked. The decoder is the runtime's (librexruntime.so, which the app
// carries anyway); the block framing is the one src/ui/hostui/fh1_archive.cpp
// reads on the PC.

#include <jni.h>

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <vector>

// librexruntime.so (thirdparty/shiftglue-sdk/include/rex/system/lzx.h).
int lzx_decompress(const void* lzx_data, size_t lzx_len, void* dest, size_t dest_len,
                   uint32_t window_size, void* window_data, size_t window_data_len);

namespace {

// XMemCompress blocks: a 0xFF marker with 16-bit uncompressed and
// compressed sizes, or a 16-bit compressed size for a full 32 KB block.
bool ParseXmemPayload(const uint8_t* compressed, size_t length, size_t uncompressed_size,
                      std::vector<uint8_t>& payload) {
  payload.clear();
  size_t offset = 0;
  size_t produced = 0;
  while (produced < uncompressed_size) {
    size_t block_compressed;
    size_t block_uncompressed;
    if (offset < length && compressed[offset] == 0xFF) {
      if (length - offset < 5) return false;
      block_uncompressed = size_t(compressed[offset + 1]) << 8 | compressed[offset + 2];
      block_compressed = size_t(compressed[offset + 3]) << 8 | compressed[offset + 4];
      offset += 5;
    } else {
      if (length - offset < 2) return false;
      block_uncompressed = std::min<size_t>(0x8000, uncompressed_size - produced);
      block_compressed = size_t(compressed[offset]) << 8 | compressed[offset + 1];
      offset += 2;
    }
    if (!block_compressed || !block_uncompressed || block_compressed > length - offset ||
        block_uncompressed > uncompressed_size - produced) {
      return false;
    }
    payload.insert(payload.end(), compressed + offset, compressed + offset + block_compressed);
    offset += block_compressed;
    produced += block_uncompressed;
  }
  return true;
}

}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL Java_studio_deimos_pinyonshift_VrMods_nativeDecompressXmem(
    JNIEnv* env, jclass, jbyteArray input, jint size) {
  if (!input || size <= 0) return nullptr;
  const jsize length = env->GetArrayLength(input);
  std::vector<uint8_t> compressed(static_cast<size_t>(length));
  env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte*>(compressed.data()));
  std::vector<uint8_t> payload;
  if (!ParseXmemPayload(compressed.data(), compressed.size(), size_t(size), payload)) return nullptr;
  std::vector<uint8_t> output(static_cast<size_t>(size));
  for (uint32_t window_size : {uint32_t(1) << 16, uint32_t(1) << 17, uint32_t(1) << 15}) {
    if (!lzx_decompress(payload.data(), payload.size(), output.data(), output.size(), window_size,
                        nullptr, 0)) {
      jbyteArray result = env->NewByteArray(size);
      if (!result) return nullptr;
      env->SetByteArrayRegion(result, 0, size, reinterpret_cast<const jbyte*>(output.data()));
      return result;
    }
  }
  return nullptr;
}
