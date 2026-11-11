// Meta Quest VR host: see quest_vr.h. The OpenXR session code follows the
// GT4 VR port's host (GT4-VR quest/host/quest_xr.cpp), trimmed to what this
// first stage needs: one curved screen, Touch controllers as an Xbox pad.

#include "quest/quest_vr.h"

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <sys/system_properties.h>

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#include <SDL3/SDL.h>
#include <rex/cvar.h>
#include <rex/perf/counter.h>
#include <rex/ui/surface_android.h>

#include "head_tracking/head_tracking.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstdlib>
#include <deque>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#define VR_LOG(...) __android_log_print(ANDROID_LOG_INFO, "FHVR", __VA_ARGS__)
#define VR_ERR(...) __android_log_print(ANDROID_LOG_ERROR, "FHVR", __VA_ARGS__)

namespace pinyon_shift::quest {
namespace {

// The game's picture: 16:9, presented by the game's own Vulkan presenter.
constexpr int32_t kSurfaceWidth = 1920;
constexpr int32_t kSurfaceHeight = 1080;
// The curved screen (GT4 VR's approved size: 5.71 m wide at 3.0 m, ~87 deg).
constexpr float kScreenDistanceM = 3.0f;
constexpr float kScreenWidthM = 5.71f;
constexpr float kScreenVerticalOffsetM = 0.0f;  // eye level
// Menu held this long is the pad's Back (View) button instead of Start.
constexpr auto kMenuHoldForBack = std::chrono::milliseconds(500);

float PropertyFloat(const char* name, float fallback) {
  char value[PROP_VALUE_MAX] = {};
  if (__system_property_get(name, value) <= 0) return fallback;
  char* end = nullptr;
  const float v = std::strtof(value, &end);
  return end != value && std::isfinite(v) ? v : fallback;
}

template <typename Fn>
bool LoadFunction(XrInstance instance, const char* name, Fn& out) {
  PFN_xrVoidFunction fn = nullptr;
  if (XR_FAILED(xrGetInstanceProcAddr(instance, name, &fn)) || !fn) return false;
  out = reinterpret_cast<Fn>(fn);
  return true;
}

using CreateSurfaceSwapchainFn = XrResult(XRAPI_PTR*)(XrSession, const XrSwapchainCreateInfo*,
                                                      XrSwapchain*, jobject*);

// ---- head-tracked view
// The game's cockpit and hood cameras turn and move with the head through
// the immersive camera mod's head layers (head_tracking, #489). The XR thread
// publishes the head pose relative to the recentred seat; the camera reads
// it once per game frame, and that frame's pose is kept so the picture can
// be shown where the head was when it was drawn (a quad that the compositor
// keeps fixed in the room while the head moves on).
constexpr float kDegrees = 57.2957795f;
constexpr float kVrVerticalFovDeg = 70.0f;  // debug.fhvr.vfov; 16:9 -> ~102 deg wide
constexpr float kQuadDistanceM = 10.0f;     // mono picture: far, so the eyes rest
constexpr int kShownLagFrames = 2;          // debug.fhvr.lagframes
constexpr float kPoseLeadMs = 33.0f;        // debug.fhvr.lead

XrQuaternionf QuatMul(const XrQuaternionf& a, const XrQuaternionf& b) {
  return {a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y, a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
          a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w, a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z};
}
XrQuaternionf QuatConj(const XrQuaternionf& q) { return {-q.x, -q.y, -q.z, q.w}; }
XrVector3f QuatRotate(const XrQuaternionf& q, const XrVector3f& v) {
  const XrQuaternionf p{v.x, v.y, v.z, 0.0f};
  const XrQuaternionf r = QuatMul(QuatMul(q, p), QuatConj(q));
  return {r.x, r.y, r.z};
}

struct HeadFrameSample {
  int64_t frame = 0;  // guest swaps counted when the camera read it
  XrPosef pose{{0, 0, 0, 1}, {0, 0, 0}};  // the head in LOCAL space
};

struct HeadShared {
  std::mutex mutex;
  bool have = false;
  head_tracking::Pose latest{};     // OpenTrack convention, for the camera
  XrPosef latest_local{{0, 0, 0, 1}, {0, 0, 0}};
  int64_t frame = -1;               // the game frame the frozen pose belongs to
  head_tracking::Pose frame_pose{};
  std::deque<HeadFrameSample> samples;
  std::chrono::steady_clock::time_point last_read{};
} g_head;

int64_t GuestFrames() {
  return rex::perf::GetTotalCounter(rex::perf::CounterId::kSourceFrameCount);
}

// head_tracking's source, on the game's thread: one pose per game frame.
bool ReadHeadPose(head_tracking::Pose* out) {
  std::lock_guard lock(g_head.mutex);
  if (!g_head.have) return false;
  const int64_t frame = GuestFrames();
  if (frame != g_head.frame) {
    g_head.frame = frame;
    g_head.frame_pose = g_head.latest;
    g_head.samples.push_back({frame, g_head.latest_local});
    while (g_head.samples.size() > 64) g_head.samples.pop_front();
  }
  g_head.last_read = std::chrono::steady_clock::now();
  *out = g_head.frame_pose;
  return true;
}

// Shared between the XR thread and the presenter's thread.
struct Shared {
  std::mutex mutex;
  std::condition_variable changed;
  ANativeWindow* surface = nullptr;
  bool failed = false;  // VR unavailable: the presenter uses the activity window
} g_shared;

std::atomic<bool> g_started{false};

// VR is unavailable (or ended): a presenter still waiting gets nullptr and
// draws into the activity's window.
void Fail() {
  std::lock_guard lock(g_shared.mutex);
  if (!g_shared.surface) g_shared.failed = true;
  g_shared.changed.notify_all();
}

// The Touch controllers as one SDL virtual Xbox pad. The game's SDL input
// driver opens it like any other gamepad.
class VirtualPad {
 public:
  struct State {
    float lx = 0, ly = 0, rx = 0, ry = 0;  // -1..1, y up
    float lt = 0, rt = 0;                  // 0..1
    bool buttons[SDL_GAMEPAD_BUTTON_COUNT] = {};
  };

  void Publish(const State& s) {
    if (!joystick_ && !Attach()) return;
    auto axis = [](float v) {
      return Sint16(std::clamp(v, -1.0f, 1.0f) * 32767.0f);
    };
    // SDL's y axes point down.
    SDL_SetJoystickVirtualAxis(joystick_, SDL_GAMEPAD_AXIS_LEFTX, axis(s.lx));
    SDL_SetJoystickVirtualAxis(joystick_, SDL_GAMEPAD_AXIS_LEFTY, axis(-s.ly));
    SDL_SetJoystickVirtualAxis(joystick_, SDL_GAMEPAD_AXIS_RIGHTX, axis(s.rx));
    SDL_SetJoystickVirtualAxis(joystick_, SDL_GAMEPAD_AXIS_RIGHTY, axis(-s.ry));
    // A virtual gamepad's triggers run from -32768 (released) to 32767
    // (SDL_virtualjoystick.c); 0 would hold both at half (gas + brake).
    auto trigger = [](float v) {
      return Sint16(std::lround(std::clamp(v, 0.0f, 1.0f) * 65535.0f) - 32768);
    };
    SDL_SetJoystickVirtualAxis(joystick_, SDL_GAMEPAD_AXIS_LEFT_TRIGGER, trigger(s.lt));
    SDL_SetJoystickVirtualAxis(joystick_, SDL_GAMEPAD_AXIS_RIGHT_TRIGGER, trigger(s.rt));
    for (int b = 0; b < SDL_GAMEPAD_BUTTON_COUNT; ++b)
      SDL_SetJoystickVirtualButton(joystick_, b, s.buttons[b]);
  }

 private:
  bool Attach() {
    // The game initialises SDL's gamepad subsystem; wait for it.
    if (!SDL_WasInit(SDL_INIT_GAMEPAD)) return false;
    SDL_VirtualJoystickDesc desc;
    SDL_INIT_INTERFACE(&desc);
    desc.type = SDL_JOYSTICK_TYPE_GAMEPAD;
    desc.naxes = SDL_GAMEPAD_AXIS_COUNT;
    desc.nbuttons = SDL_GAMEPAD_BUTTON_COUNT;
    desc.vendor_id = 0x045E;  // Microsoft
    desc.product_id = 0x028E;  // Xbox 360 controller
    desc.name = "Quest Touch";
    const SDL_JoystickID id = SDL_AttachVirtualJoystick(&desc);
    if (!id) {
      VR_ERR("virtual pad: %s", SDL_GetError());
      return false;
    }
    joystick_ = SDL_OpenJoystick(id);
    VR_LOG("virtual pad attached (id %u)%s", unsigned(id), joystick_ ? "" : " but not opened");
    return joystick_ != nullptr;
  }

  SDL_Joystick* joystick_ = nullptr;
};

class QuestXr {
 public:
  QuestXr(JavaVM* vm, jobject activity) : vm_(vm), activity_(activity) {}

  void Run() {
    JNIEnv* env = nullptr;
    if (vm_->AttachCurrentThread(&env, nullptr) != JNI_OK) {
      VR_ERR("AttachCurrentThread failed");
      Fail();
      return;
    }
    if (InitEgl() && InitInstance() && InitSession() && InitActions() && InitScreen(env)) {
      VR_LOG("OpenXR ready, waiting for the session to start");
      while (!exit_) {
        PollEvents();
        if (!running_) {
          std::this_thread::sleep_for(std::chrono::milliseconds(20));
          continue;
        }
        RunFrame();
      }
    } else {
      VR_ERR("init failed: %s", error_.c_str());
    }
    Fail();
    Destroy();
    vm_->DetachCurrentThread();
  }

 private:
  struct Actions {
    XrActionSet set = XR_NULL_HANDLE;
    XrAction a = XR_NULL_HANDLE, b = XR_NULL_HANDLE, x = XR_NULL_HANDLE, y = XR_NULL_HANDLE;
    XrAction menu = XR_NULL_HANDLE;
    XrAction lclick = XR_NULL_HANDLE, rclick = XR_NULL_HANDLE;
    XrAction ltrigger = XR_NULL_HANDLE, rtrigger = XR_NULL_HANDLE;
    XrAction lgrip = XR_NULL_HANDLE, rgrip = XR_NULL_HANDLE;
    XrAction lstick = XR_NULL_HANDLE, rstick = XR_NULL_HANDLE;
    // The vector2f binding alone has read 0,0 on Quest (GT4 VR): axes too.
    XrAction lx = XR_NULL_HANDLE, ly = XR_NULL_HANDLE, rx = XR_NULL_HANDLE, ry = XR_NULL_HANDLE;
  };

  bool SetError(const char* what, XrResult result = XR_SUCCESS) {
    error_ = what;
    if (result != XR_SUCCESS) error_ += " (XrResult " + std::to_string(int(result)) + ")";
    return false;
  }

  // OpenXR needs a graphics binding for the session; the picture itself comes
  // through the surface swapchain, so a 16x16 pbuffer context is enough.
  bool InitEgl() {
    egl_display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (egl_display_ == EGL_NO_DISPLAY || !eglInitialize(egl_display_, nullptr, nullptr))
      return SetError("eglInitialize failed");
    const EGLint config_attribs[] = {EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
                                     EGL_ALPHA_SIZE, 8, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                                     EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE};
    EGLint count = 0;
    if (!eglChooseConfig(egl_display_, config_attribs, &egl_config_, 1, &count) || count == 0)
      return SetError("no EGL config");
    const EGLint context_attribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    egl_context_ = eglCreateContext(egl_display_, egl_config_, EGL_NO_CONTEXT, context_attribs);
    if (egl_context_ == EGL_NO_CONTEXT) return SetError("eglCreateContext failed");
    const EGLint pbuffer_attribs[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
    egl_surface_ = eglCreatePbufferSurface(egl_display_, egl_config_, pbuffer_attribs);
    if (egl_surface_ == EGL_NO_SURFACE) return SetError("eglCreatePbufferSurface failed");
    if (!eglMakeCurrent(egl_display_, egl_surface_, egl_surface_, egl_context_))
      return SetError("eglMakeCurrent failed");
    return true;
  }

  bool InitInstance() {
    PFN_xrInitializeLoaderKHR initialize_loader = nullptr;
    if (!LoadFunction(XR_NULL_HANDLE, "xrInitializeLoaderKHR", initialize_loader))
      return SetError("OpenXR loader has no xrInitializeLoaderKHR");
    XrLoaderInitInfoAndroidKHR loader_info{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
    loader_info.applicationVM = vm_;
    loader_info.applicationContext = activity_;
    XrResult res = initialize_loader(
        reinterpret_cast<const XrLoaderInitInfoBaseHeaderKHR*>(&loader_info));
    if (XR_FAILED(res)) return SetError("xrInitializeLoaderKHR failed", res);

    uint32_t count = 0;
    res = xrEnumerateInstanceExtensionProperties(nullptr, 0, &count, nullptr);
    if (XR_FAILED(res)) return SetError("no OpenXR runtime", res);
    std::vector<XrExtensionProperties> props(count, {XR_TYPE_EXTENSION_PROPERTIES});
    xrEnumerateInstanceExtensionProperties(nullptr, count, &count, props.data());
    auto has = [&props](const char* name) {
      return std::any_of(props.begin(), props.end(), [name](const XrExtensionProperties& p) {
        return std::strcmp(p.extensionName, name) == 0;
      });
    };
    std::vector<const char*> extensions = {XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME,
                                           XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME,
                                           XR_KHR_ANDROID_SURFACE_SWAPCHAIN_EXTENSION_NAME};
    for (const char* ext : extensions)
      if (!has(ext)) return SetError((std::string("runtime lacks ") + ext).c_str());
    auto optional = [&](const char* name, bool& flag) {
      flag = has(name);
      if (flag) extensions.push_back(name);
    };
    optional(XR_KHR_COMPOSITION_LAYER_CYLINDER_EXTENSION_NAME, has_cylinder_);
    optional(XR_FB_COMPOSITION_LAYER_IMAGE_LAYOUT_EXTENSION_NAME, has_image_layout_);
    optional(XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME, has_refresh_);
    optional(XR_EXT_PERFORMANCE_SETTINGS_EXTENSION_NAME, has_perf_);

    XrInstanceCreateInfoAndroidKHR android_info{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
    android_info.applicationVM = vm_;
    android_info.applicationActivity = activity_;
    XrInstanceCreateInfo create_info{XR_TYPE_INSTANCE_CREATE_INFO};
    create_info.next = &android_info;
    std::strncpy(create_info.applicationInfo.applicationName, "FH1 VR",
                 XR_MAX_APPLICATION_NAME_SIZE - 1);
    create_info.applicationInfo.applicationVersion = 1;
    std::strncpy(create_info.applicationInfo.engineName, "PinyonShift",
                 XR_MAX_ENGINE_NAME_SIZE - 1);
    create_info.applicationInfo.apiVersion = XR_API_VERSION_1_0;
    create_info.enabledExtensionCount = uint32_t(extensions.size());
    create_info.enabledExtensionNames = extensions.data();
    res = xrCreateInstance(&create_info, &instance_);
    if (XR_FAILED(res)) return SetError("xrCreateInstance failed", res);
    XrInstanceProperties instance_props{XR_TYPE_INSTANCE_PROPERTIES};
    if (XR_SUCCEEDED(xrGetInstanceProperties(instance_, &instance_props)))
      VR_LOG("runtime: %s %u.%u.%u", instance_props.runtimeName,
             XR_VERSION_MAJOR(instance_props.runtimeVersion),
             XR_VERSION_MINOR(instance_props.runtimeVersion),
             XR_VERSION_PATCH(instance_props.runtimeVersion));

    XrSystemGetInfo system_info{XR_TYPE_SYSTEM_GET_INFO};
    system_info.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    res = xrGetSystem(instance_, &system_info, &system_);
    if (XR_FAILED(res)) return SetError("no head-mounted display", res);

    PFN_xrGetOpenGLESGraphicsRequirementsKHR gles_requirements = nullptr;
    if (!LoadFunction(instance_, "xrGetOpenGLESGraphicsRequirementsKHR", gles_requirements) ||
        !LoadFunction(instance_, "xrCreateSwapchainAndroidSurfaceKHR", create_surface_swapchain_))
      return SetError("runtime is missing required extension functions");
    if (has_perf_)
      has_perf_ = LoadFunction(instance_, "xrPerfSettingsSetPerformanceLevelEXT", set_perf_level_);
    if (has_refresh_)
      has_refresh_ =
          LoadFunction(instance_, "xrEnumerateDisplayRefreshRatesFB", enumerate_refresh_) &&
          LoadFunction(instance_, "xrRequestDisplayRefreshRateFB", request_refresh_);
    XrGraphicsRequirementsOpenGLESKHR requirements{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
    res = gles_requirements(instance_, system_, &requirements);
    if (XR_FAILED(res)) return SetError("xrGetOpenGLESGraphicsRequirementsKHR failed", res);
    return true;
  }

  bool InitSession() {
    XrGraphicsBindingOpenGLESAndroidKHR binding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
    binding.display = egl_display_;
    binding.config = egl_config_;
    binding.context = egl_context_;
    XrSessionCreateInfo session_info{XR_TYPE_SESSION_CREATE_INFO};
    session_info.next = &binding;
    session_info.systemId = system_;
    XrResult res = xrCreateSession(instance_, &session_info, &session_);
    if (XR_FAILED(res)) return SetError("xrCreateSession failed", res);
    XrReferenceSpaceCreateInfo space_info{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
    space_info.poseInReferenceSpace.orientation.w = 1.0f;
    space_info.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
    res = xrCreateReferenceSpace(session_, &space_info, &local_space_);
    if (XR_FAILED(res)) return SetError("LOCAL space failed", res);
    space_info.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_VIEW;
    res = xrCreateReferenceSpace(session_, &space_info, &view_space_);
    if (XR_FAILED(res)) return SetError("VIEW space failed", res);
    return true;
  }

  bool InitActions() {
    XrActionSetCreateInfo set_info{XR_TYPE_ACTION_SET_CREATE_INFO};
    std::strncpy(set_info.actionSetName, "gameplay", XR_MAX_ACTION_SET_NAME_SIZE - 1);
    std::strncpy(set_info.localizedActionSetName, "Gameplay",
                 XR_MAX_LOCALIZED_ACTION_SET_NAME_SIZE - 1);
    XrResult res = xrCreateActionSet(instance_, &set_info, &actions_.set);
    if (XR_FAILED(res)) return SetError("xrCreateActionSet failed", res);
    struct Def {
      XrAction* action;
      const char* name;
      XrActionType type;
      const char* path;
    };
    const Def defs[] = {
        {&actions_.a, "a", XR_ACTION_TYPE_BOOLEAN_INPUT, "/user/hand/right/input/a/click"},
        {&actions_.b, "b", XR_ACTION_TYPE_BOOLEAN_INPUT, "/user/hand/right/input/b/click"},
        {&actions_.x, "x", XR_ACTION_TYPE_BOOLEAN_INPUT, "/user/hand/left/input/x/click"},
        {&actions_.y, "y", XR_ACTION_TYPE_BOOLEAN_INPUT, "/user/hand/left/input/y/click"},
        {&actions_.menu, "menu", XR_ACTION_TYPE_BOOLEAN_INPUT, "/user/hand/left/input/menu/click"},
        {&actions_.lclick, "lclick", XR_ACTION_TYPE_BOOLEAN_INPUT,
         "/user/hand/left/input/thumbstick/click"},
        {&actions_.rclick, "rclick", XR_ACTION_TYPE_BOOLEAN_INPUT,
         "/user/hand/right/input/thumbstick/click"},
        {&actions_.ltrigger, "ltrigger", XR_ACTION_TYPE_FLOAT_INPUT,
         "/user/hand/left/input/trigger/value"},
        {&actions_.rtrigger, "rtrigger", XR_ACTION_TYPE_FLOAT_INPUT,
         "/user/hand/right/input/trigger/value"},
        {&actions_.lgrip, "lgrip", XR_ACTION_TYPE_FLOAT_INPUT,
         "/user/hand/left/input/squeeze/value"},
        {&actions_.rgrip, "rgrip", XR_ACTION_TYPE_FLOAT_INPUT,
         "/user/hand/right/input/squeeze/value"},
        {&actions_.lstick, "lstick", XR_ACTION_TYPE_VECTOR2F_INPUT,
         "/user/hand/left/input/thumbstick"},
        {&actions_.rstick, "rstick", XR_ACTION_TYPE_VECTOR2F_INPUT,
         "/user/hand/right/input/thumbstick"},
        {&actions_.lx, "lx", XR_ACTION_TYPE_FLOAT_INPUT, "/user/hand/left/input/thumbstick/x"},
        {&actions_.ly, "ly", XR_ACTION_TYPE_FLOAT_INPUT, "/user/hand/left/input/thumbstick/y"},
        {&actions_.rx, "rx", XR_ACTION_TYPE_FLOAT_INPUT, "/user/hand/right/input/thumbstick/x"},
        {&actions_.ry, "ry", XR_ACTION_TYPE_FLOAT_INPUT, "/user/hand/right/input/thumbstick/y"},
    };
    std::vector<XrActionSuggestedBinding> bindings;
    for (const Def& def : defs) {
      XrActionCreateInfo info{XR_TYPE_ACTION_CREATE_INFO};
      info.actionType = def.type;
      std::strncpy(info.actionName, def.name, XR_MAX_ACTION_NAME_SIZE - 1);
      std::strncpy(info.localizedActionName, def.name, XR_MAX_LOCALIZED_ACTION_NAME_SIZE - 1);
      res = xrCreateAction(actions_.set, &info, def.action);
      if (XR_FAILED(res)) return SetError("xrCreateAction failed", res);
      XrPath path = XR_NULL_PATH;
      xrStringToPath(instance_, def.path, &path);
      bindings.push_back({*def.action, path});
    }
    XrInteractionProfileSuggestedBinding suggested{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
    xrStringToPath(instance_, "/interaction_profiles/oculus/touch_controller",
                   &suggested.interactionProfile);
    suggested.suggestedBindings = bindings.data();
    suggested.countSuggestedBindings = uint32_t(bindings.size());
    res = xrSuggestInteractionProfileBindings(instance_, &suggested);
    if (XR_FAILED(res)) return SetError("Touch controller bindings refused", res);
    XrSessionActionSetsAttachInfo attach{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
    attach.countActionSets = 1;
    attach.actionSets = &actions_.set;
    res = xrAttachSessionActionSets(session_, &attach);
    if (XR_FAILED(res)) return SetError("xrAttachSessionActionSets failed", res);
    return true;
  }

  bool InitScreen(JNIEnv* env) {
    XrSwapchainCreateInfo info{XR_TYPE_SWAPCHAIN_CREATE_INFO};
    info.usageFlags = XR_SWAPCHAIN_USAGE_SAMPLED_BIT | XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
    // format, sampleCount, faceCount, arraySize and mipCount stay 0 for a
    // surface swapchain (the producer owns the format); 1s fail on Quest.
    info.width = kSurfaceWidth;
    info.height = kSurfaceHeight;
    jobject surface = nullptr;
    const XrResult res = create_surface_swapchain_(session_, &info, &swapchain_, &surface);
    if (XR_FAILED(res) || !surface) return SetError("xrCreateSwapchainAndroidSurfaceKHR failed", res);
    // The runtime returns a global reference that lives with the swapchain.
    window_ = ANativeWindow_fromSurface(env, surface);
    if (!window_) return SetError("ANativeWindow_fromSurface failed");
    return true;
  }

  void Destroy() {
    if (swapchain_ != XR_NULL_HANDLE) xrDestroySwapchain(swapchain_);
    if (local_space_ != XR_NULL_HANDLE) xrDestroySpace(local_space_);
    if (view_space_ != XR_NULL_HANDLE) xrDestroySpace(view_space_);
    if (actions_.set != XR_NULL_HANDLE) xrDestroyActionSet(actions_.set);
    if (session_ != XR_NULL_HANDLE) xrDestroySession(session_);
    if (instance_ != XR_NULL_HANDLE) xrDestroyInstance(instance_);
    // The window stays referenced: the presenter may still hold it.
    if (egl_display_ != EGL_NO_DISPLAY) {
      eglMakeCurrent(egl_display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
      if (egl_surface_ != EGL_NO_SURFACE) eglDestroySurface(egl_display_, egl_surface_);
      if (egl_context_ != EGL_NO_CONTEXT) eglDestroyContext(egl_display_, egl_context_);
    }
  }

  void PollEvents() {
    XrEventDataBuffer event{XR_TYPE_EVENT_DATA_BUFFER};
    while (xrPollEvent(instance_, &event) == XR_SUCCESS) {
      switch (event.type) {
        case XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED:
          OnState(reinterpret_cast<const XrEventDataSessionStateChanged&>(event).state);
          break;
        case XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING:
          exit_ = true;
          break;
        case XR_TYPE_EVENT_DATA_REFERENCE_SPACE_CHANGE_PENDING:
          placed_ = false;  // recentred with the Meta button
          break;
        default:
          break;
      }
      event = {XR_TYPE_EVENT_DATA_BUFFER};
    }
  }

  void OnState(XrSessionState state) {
    VR_LOG("session state %d", int(state));
    switch (state) {
      case XR_SESSION_STATE_READY: {
        XrSessionBeginInfo begin{XR_TYPE_SESSION_BEGIN_INFO};
        begin.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
        const XrResult res = xrBeginSession(session_, &begin);
        if (XR_FAILED(res)) {
          VR_ERR("xrBeginSession failed: %d", int(res));
          exit_ = true;
          break;
        }
        running_ = true;
        PickRefreshRate();
        if (has_perf_) {
          set_perf_level_(session_, XR_PERF_SETTINGS_DOMAIN_CPU_EXT,
                          XR_PERF_SETTINGS_LEVEL_SUSTAINED_HIGH_EXT);
          set_perf_level_(session_, XR_PERF_SETTINGS_DOMAIN_GPU_EXT,
                          XR_PERF_SETTINGS_LEVEL_SUSTAINED_HIGH_EXT);
        }
        // Only now: before the session runs nothing consumes the surface,
        // and a presenter waiting on a full buffer queue stalls the game.
        std::lock_guard lock(g_shared.mutex);
        if (!g_shared.surface) {
          g_shared.surface = window_;
          g_shared.changed.notify_all();
          VR_LOG("surface %dx%d handed to the game", kSurfaceWidth, kSurfaceHeight);
        }
        break;
      }
      case XR_SESSION_STATE_STOPPING:
        xrEndSession(session_);
        running_ = false;
        break;
      case XR_SESSION_STATE_EXITING:
      case XR_SESSION_STATE_LOSS_PENDING:
        exit_ = true;
        break;
      default:
        break;
    }
    focused_ = state == XR_SESSION_STATE_FOCUSED;
    if (!focused_) pad_.Publish({});
  }

  void PickRefreshRate() {
    if (!has_refresh_) return;
    uint32_t count = 0;
    if (XR_FAILED(enumerate_refresh_(session_, 0, &count, nullptr)) || count == 0) return;
    std::vector<float> rates(count);
    enumerate_refresh_(session_, count, &count, rates.data());
    // 72 Hz by default (the game runs at 60 or 30; the system default is the
    // smoothest pairing so far, as in GT4 VR); debug.fhvr.hz overrides.
    const float wanted = PropertyFloat("debug.fhvr.hz", 72.0f);
    float best = rates[0];
    for (float r : rates)
      if (std::fabs(r - wanted) < std::fabs(best - wanted)) best = r;
    request_refresh_(session_, best);
    VR_LOG("refresh rate %.0f Hz", best);
  }

  void PlaceScreen(XrTime time) {
    XrSpaceLocation location{XR_TYPE_SPACE_LOCATION};
    if (XR_FAILED(xrLocateSpace(view_space_, local_space_, time, &location))) return;
    const XrSpaceLocationFlags needed =
        XR_SPACE_LOCATION_POSITION_VALID_BIT | XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
    if ((location.locationFlags & needed) != needed) return;
    const XrQuaternionf& q = location.pose.orientation;
    const float fx = -2.0f * (q.x * q.z + q.w * q.y);
    const float fz = -(1.0f - 2.0f * (q.x * q.x + q.y * q.y));
    const float yaw = std::atan2(-fx, -fz);
    const XrVector3f& head = location.pose.position;
    screen_pose_.orientation = {0.0f, std::sin(yaw * 0.5f), 0.0f, std::cos(yaw * 0.5f)};
    screen_pose_.position = {head.x, head.y + kScreenVerticalOffsetM, head.z};
    flat_pose_.orientation = screen_pose_.orientation;
    flat_pose_.position = {head.x - std::sin(yaw) * kScreenDistanceM, screen_pose_.position.y,
                           head.z - std::cos(yaw) * kScreenDistanceM};
    placed_ = true;
    if (!first_place_) first_place_ = time;
    VR_LOG("screen placed at head height %.2f m", head.y);
  }

  bool Bool(XrAction action) {
    XrActionStateGetInfo info{XR_TYPE_ACTION_STATE_GET_INFO};
    info.action = action;
    XrActionStateBoolean state{XR_TYPE_ACTION_STATE_BOOLEAN};
    return XR_SUCCEEDED(xrGetActionStateBoolean(session_, &info, &state)) && state.isActive &&
           state.currentState;
  }
  float Float(XrAction action) {
    XrActionStateGetInfo info{XR_TYPE_ACTION_STATE_GET_INFO};
    info.action = action;
    XrActionStateFloat state{XR_TYPE_ACTION_STATE_FLOAT};
    return XR_SUCCEEDED(xrGetActionStateFloat(session_, &info, &state)) && state.isActive
               ? state.currentState
               : 0.0f;
  }
  XrVector2f Stick(XrAction vector, XrAction x, XrAction y) {
    XrActionStateGetInfo info{XR_TYPE_ACTION_STATE_GET_INFO};
    info.action = vector;
    XrActionStateVector2f state{XR_TYPE_ACTION_STATE_VECTOR2F};
    XrVector2f v{0.0f, 0.0f};
    if (XR_SUCCEEDED(xrGetActionStateVector2f(session_, &info, &state)) && state.isActive)
      v = state.currentState;
    if (v.x == 0.0f && v.y == 0.0f) v = {Float(x), Float(y)};
    return v;
  }

  // Touch -> Xbox pad. Sticks, triggers and A/B/X/Y as on the pad; grips are
  // the bumpers, stick clicks the stick buttons. Menu: Start on release,
  // Back (View) while held 0.5 s. Left grip held: the right stick is the
  // d-pad (and LB is not sent while it is used that way).
  void UpdateInput() {
    XrActiveActionSet active{actions_.set, XR_NULL_PATH};
    XrActionsSyncInfo sync{XR_TYPE_ACTIONS_SYNC_INFO};
    sync.countActiveActionSets = 1;
    sync.activeActionSets = &active;
    if (XR_FAILED(xrSyncActions(session_, &sync))) return;

    VirtualPad::State s;
    const XrVector2f l = Stick(actions_.lstick, actions_.lx, actions_.ly);
    const XrVector2f r = Stick(actions_.rstick, actions_.rx, actions_.ry);
    s.lx = l.x;
    s.ly = l.y;
    s.lt = Float(actions_.ltrigger);
    s.rt = Float(actions_.rtrigger);
    s.buttons[SDL_GAMEPAD_BUTTON_SOUTH] = Bool(actions_.a);
    s.buttons[SDL_GAMEPAD_BUTTON_EAST] = Bool(actions_.b);
    s.buttons[SDL_GAMEPAD_BUTTON_WEST] = Bool(actions_.x);
    s.buttons[SDL_GAMEPAD_BUTTON_NORTH] = Bool(actions_.y);
    s.buttons[SDL_GAMEPAD_BUTTON_LEFT_STICK] = Bool(actions_.lclick);
    s.buttons[SDL_GAMEPAD_BUTTON_RIGHT_STICK] = Bool(actions_.rclick);
    s.buttons[SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER] = Float(actions_.rgrip) > 0.5f;

    const bool lgrip = Float(actions_.lgrip) > 0.5f;
    if (lgrip) {
      if (std::fabs(r.x) > 0.5f || std::fabs(r.y) > 0.5f) dpad_used_ = true;
      s.buttons[SDL_GAMEPAD_BUTTON_DPAD_UP] = r.y > 0.5f;
      s.buttons[SDL_GAMEPAD_BUTTON_DPAD_DOWN] = r.y < -0.5f;
      s.buttons[SDL_GAMEPAD_BUTTON_DPAD_LEFT] = r.x < -0.5f;
      s.buttons[SDL_GAMEPAD_BUTTON_DPAD_RIGHT] = r.x > 0.5f;
      s.buttons[SDL_GAMEPAD_BUTTON_LEFT_SHOULDER] = !dpad_used_;
    } else {
      dpad_used_ = false;
      s.rx = r.x;
      s.ry = r.y;
    }

    // Menu: a short press is Start (sent for a few frames after release),
    // holding it is Back.
    const auto now = std::chrono::steady_clock::now();
    const bool menu = Bool(actions_.menu);
    if (menu && !menu_down_) menu_since_ = now;
    const bool held = menu && now - menu_since_ >= kMenuHoldForBack;
    if (held) menu_was_back_ = true;
    if (!menu && menu_down_ && !menu_was_back_) start_frames_ = 6;
    if (!menu) menu_was_back_ = false;
    menu_down_ = menu;
    s.buttons[SDL_GAMEPAD_BUTTON_BACK] = held;
    s.buttons[SDL_GAMEPAD_BUTTON_START] = start_frames_ > 0;
    if (start_frames_ > 0) --start_frames_;
    pad_.Publish(s);
  }

  // The head relative to the recentred seat (screen_pose_: head position,
  // yaw only), predicted a little past this frame's display, for the camera.
  void PublishHead(XrTime display_time) {
    const float lead_ms = PropertyFloat("debug.fhvr.lead", kPoseLeadMs);
    XrSpaceLocation location{XR_TYPE_SPACE_LOCATION};
    if (XR_FAILED(xrLocateSpace(view_space_, local_space_,
                                display_time + XrTime(lead_ms * 1.0e6f), &location)) ||
        !(location.locationFlags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT))
      return;
    if (!(location.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT))
      location.pose.position = screen_pose_.position;
    const XrQuaternionf seat_inv = QuatConj(screen_pose_.orientation);
    const XrQuaternionf q = QuatMul(seat_inv, location.pose.orientation);
    const XrVector3f d{location.pose.position.x - screen_pose_.position.x,
                       location.pose.position.y - screen_pose_.position.y,
                       location.pose.position.z - screen_pose_.position.z};
    const XrVector3f p = QuatRotate(seat_inv, d);
    // R = Ry(yaw) Rx(pitch) Rz(roll), OpenXR axes (x right, y up, z back).
    const float r02 = 2.0f * (q.x * q.z + q.y * q.w);
    const float r22 = 1.0f - 2.0f * (q.x * q.x + q.y * q.y);
    const float r12 = 2.0f * (q.y * q.z - q.x * q.w);
    const float r10 = 2.0f * (q.x * q.y + q.z * q.w);
    const float r11 = 1.0f - 2.0f * (q.x * q.x + q.z * q.z);
    const float yaw = std::atan2(r02, r22);
    const float pitch = std::asin(std::clamp(-r12, -1.0f, 1.0f));
    const float roll = std::atan2(r10, r11);
    // OpenTrack: positive = look right, look up, tilt right; x right, y up,
    // z forward. The position follows the head only with debug.fhvr.pos 1
    // (the default): 0 keeps the camera at the seat.
    const float pos = PropertyFloat("debug.fhvr.pos", 1.0f);
    head_tracking::Pose pose{-yaw * kDegrees, pitch * kDegrees, -roll * kDegrees,
                             p.x * pos, p.y * pos, -p.z * pos};
    std::lock_guard lock(g_head.mutex);
    g_head.latest = pose;
    g_head.latest_local = location.pose;
    g_head.have = true;
  }

  // The head pose the shown picture was drawn with: the camera's sample from
  // debug.fhvr.lagframes game frames before the newest one swapped.
  bool ShownHead(XrPosef* pose, int64_t* age_frames, bool* active) {
    const int lag = int(PropertyFloat("debug.fhvr.lagframes", float(kShownLagFrames)));
    const int64_t now_frame = GuestFrames();
    std::lock_guard lock(g_head.mutex);
    *active = g_head.have &&
              std::chrono::steady_clock::now() - g_head.last_read < std::chrono::milliseconds(300);
    if (!*active || g_head.samples.empty()) return false;
    const HeadFrameSample* best = &g_head.samples.front();
    for (const HeadFrameSample& s : g_head.samples)
      if (s.frame <= now_frame - lag) best = &s;
    *pose = best->pose;
    *age_frames = now_frame - best->frame;
    return true;
  }

  void RunFrame() {
    XrFrameWaitInfo wait_info{XR_TYPE_FRAME_WAIT_INFO};
    XrFrameState frame{XR_TYPE_FRAME_STATE};
    if (XR_FAILED(xrWaitFrame(session_, &wait_info, &frame))) return;
    XrFrameBeginInfo begin_info{XR_TYPE_FRAME_BEGIN_INFO};
    if (XR_FAILED(xrBeginFrame(session_, &begin_info))) return;
    if (focused_) UpdateInput();

    // The compositor samples a surface swapchain bottom-up; the presenter
    // writes the top row first, so flip it there.
    XrCompositionLayerImageLayoutFB flip{XR_TYPE_COMPOSITION_LAYER_IMAGE_LAYOUT_FB};
    flip.flags = XR_COMPOSITION_LAYER_IMAGE_LAYOUT_VERTICAL_FLIP_BIT_FB;
    XrCompositionLayerCylinderKHR cylinder{XR_TYPE_COMPOSITION_LAYER_CYLINDER_KHR};
    XrCompositionLayerQuad quad{XR_TYPE_COMPOSITION_LAYER_QUAD};
    const XrCompositionLayerBaseHeader* layers[1] = {};
    uint32_t layer_count = 0;
    if (frame.shouldRender) {
      // Place once more 4 s after the start, when the player sits still.
      if (placed_ && !replaced_ && first_place_ &&
          frame.predictedDisplayTime - first_place_ > 4000000000LL) {
        replaced_ = true;
        placed_ = false;
      }
      if (!placed_) PlaceScreen(frame.predictedDisplayTime);
      if (placed_) PublishHead(frame.predictedDisplayTime);
      XrPosef shown{};
      int64_t age = 0;
      bool head_active = false;
      const bool head_view = placed_ && ShownHead(&shown, &age, &head_active) &&
                             PropertyFloat("debug.fhvr.vr", 1.0f) != 0.0f;
      // The cameras take the headset's field of view only while the head
      // view is up, so chase cameras and menus keep the game's own.
      const float vfov = std::clamp(PropertyFloat("debug.fhvr.vfov", kVrVerticalFovDeg), 30.0f, 120.0f);
      head_tracking::SetFieldOfViewOverride(head_view ? vfov / kDegrees : 0.0f);
      if (head_view != head_view_shown_) {
        head_view_shown_ = head_view;
        VR_LOG("view: %s", head_view ? "head-tracked (cockpit/hood camera)" : "curved screen");
      }
      if (head_view && ++head_log_ % 720 == 0)
        VR_LOG("head view: vfov %.0f, picture %lld game frames old, frames %lld", vfov,
               (long long)age, (long long)GuestFrames());
      if (head_view) {
        // The picture where the head was when it was drawn, as wide as the
        // camera's field of view, far ahead.
        const float d = kQuadDistanceM;
        const float tan_v = std::tan(vfov * 0.5f / kDegrees);
        XrPosef pose = shown;
        const XrVector3f ahead = QuatRotate(pose.orientation, {0.0f, 0.0f, -d});
        pose.position = {pose.position.x + ahead.x, pose.position.y + ahead.y,
                         pose.position.z + ahead.z};
        if (has_image_layout_) quad.next = &flip;
        quad.space = local_space_;
        quad.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
        quad.subImage.swapchain = swapchain_;
        quad.subImage.imageRect.extent = {kSurfaceWidth, kSurfaceHeight};
        quad.pose = pose;
        quad.size = {2.0f * d * tan_v * float(kSurfaceWidth) / float(kSurfaceHeight), 2.0f * d * tan_v};
        layers[layer_count++] = reinterpret_cast<const XrCompositionLayerBaseHeader*>(&quad);
      } else if (placed_) {
        XrSwapchainSubImage sub{};
        sub.swapchain = swapchain_;
        sub.imageRect.extent = {kSurfaceWidth, kSurfaceHeight};
        const float width = PropertyFloat("debug.fhvr.screenw", kScreenWidthM);
        if (has_cylinder_) {
          if (has_image_layout_) cylinder.next = &flip;
          cylinder.space = local_space_;
          cylinder.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
          cylinder.subImage = sub;
          cylinder.pose = screen_pose_;
          cylinder.radius = kScreenDistanceM;
          cylinder.centralAngle = 2.0f * std::atan(width * 0.5f / kScreenDistanceM);
          cylinder.aspectRatio = float(kSurfaceWidth) / float(kSurfaceHeight);
          layers[layer_count++] = reinterpret_cast<const XrCompositionLayerBaseHeader*>(&cylinder);
        } else {
          if (has_image_layout_) quad.next = &flip;
          quad.space = local_space_;
          quad.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
          quad.subImage = sub;
          quad.pose = flat_pose_;
          quad.size = {width, width * kSurfaceHeight / kSurfaceWidth};
          layers[layer_count++] = reinterpret_cast<const XrCompositionLayerBaseHeader*>(&quad);
        }
      }
    }
    XrFrameEndInfo end_info{XR_TYPE_FRAME_END_INFO};
    end_info.displayTime = frame.predictedDisplayTime;
    end_info.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    end_info.layerCount = layer_count;
    end_info.layers = layer_count ? layers : nullptr;
    xrEndFrame(session_, &end_info);
  }

  JavaVM* vm_;
  jobject activity_;
  std::string error_;
  EGLDisplay egl_display_ = EGL_NO_DISPLAY;
  EGLConfig egl_config_ = nullptr;
  EGLContext egl_context_ = EGL_NO_CONTEXT;
  EGLSurface egl_surface_ = EGL_NO_SURFACE;
  XrInstance instance_ = XR_NULL_HANDLE;
  XrSystemId system_ = XR_NULL_SYSTEM_ID;
  XrSession session_ = XR_NULL_HANDLE;
  XrSpace local_space_ = XR_NULL_HANDLE, view_space_ = XR_NULL_HANDLE;
  XrSwapchain swapchain_ = XR_NULL_HANDLE;
  ANativeWindow* window_ = nullptr;
  Actions actions_;
  VirtualPad pad_;
  CreateSurfaceSwapchainFn create_surface_swapchain_ = nullptr;
  PFN_xrPerfSettingsSetPerformanceLevelEXT set_perf_level_ = nullptr;
  PFN_xrEnumerateDisplayRefreshRatesFB enumerate_refresh_ = nullptr;
  PFN_xrRequestDisplayRefreshRateFB request_refresh_ = nullptr;
  bool has_cylinder_ = false, has_image_layout_ = false, has_refresh_ = false, has_perf_ = false;
  bool running_ = false, exit_ = false, focused_ = false;
  bool placed_ = false, replaced_ = false;
  XrTime first_place_ = 0;
  XrPosef screen_pose_{{0, 0, 0, 1}, {0, 0, 0}}, flat_pose_{{0, 0, 0, 1}, {0, 0, 0}};
  bool head_view_shown_ = false;
  uint32_t head_log_ = 0;
  bool dpad_used_ = false, menu_down_ = false, menu_was_back_ = false;
  int start_frames_ = 0;
  std::chrono::steady_clock::time_point menu_since_{};
};

}  // namespace

void Start() {
  if (g_started.exchange(true)) return;
  // The presenter asks for its window here (shiftglue-sdk window_sdl.cpp).
  rex::ui::SetAndroidSurfaceProvider([]() { return WaitForSurface(30000); });
  // Mailbox presents into the XR surface froze the screen while the game
  // kept running (Quest 3, 2026-11-11); FIFO hands every frame over.
  rex::cvar::SetFlagByName("vulkan_allow_present_mode_mailbox", "false");
  rex::cvar::SetFlagByName("vulkan_allow_present_mode_immediate", "false");
  // The headset drives the cockpit and hood cameras (head-tracked view).
  head_tracking::SetPoseSource(ReadHeadPose);
  rex::cvar::SetFlagByName("head_tracking", "true");
  auto* env = static_cast<JNIEnv*>(SDL_GetAndroidJNIEnv());
  auto activity = static_cast<jobject>(SDL_GetAndroidActivity());
  JavaVM* vm = nullptr;
  if (!env || !activity || env->GetJavaVM(&vm) != JNI_OK) {
    VR_ERR("no JNI environment or activity: VR off");
    Fail();
    return;
  }
  // SDL hands out its own global reference: take another, never delete it.
  jobject global = env->NewGlobalRef(activity);
  std::thread([vm, global] {
    QuestXr xr(vm, global);
    xr.Run();
  }).detach();
  VR_LOG("XR thread started");
}

ANativeWindow* WaitForSurface(int timeout_ms) {
  if (!g_started.load()) return nullptr;
  std::unique_lock lock(g_shared.mutex);
  g_shared.changed.wait_for(lock, std::chrono::milliseconds(timeout_ms),
                            [] { return g_shared.surface || g_shared.failed; });
  if (!g_shared.surface) VR_ERR("no XR surface: the game draws to the activity window");
  return g_shared.surface;
}

}  // namespace pinyon_shift::quest

