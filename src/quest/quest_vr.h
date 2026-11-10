#pragma once
// Meta Quest VR host (Android only, PINYON_SHIFT_QUEST_VR).
//
// An OpenXR session on its own thread shows the game's picture on a curved
// screen in front of the player. The game's Vulkan presenter draws into an
// ordinary Android Surface that OpenXR's XR_KHR_android_surface_swapchain
// hands out, exactly as it would draw into the activity's window, so the
// renderer needs no VR-specific code for this first stage. The Touch
// controllers feed an SDL virtual Xbox gamepad.

struct ANativeWindow;

namespace pinyon_shift::quest {

// Starts the XR thread once (later calls do nothing). Call before the
// presenter creates its surface.
void Start();

// The surface the presenter draws into: blocks until the XR session runs
// (up to timeout_ms), then returns the XR surface, or nullptr when VR is
// unavailable (the caller falls back to the activity's window).
ANativeWindow* WaitForSurface(int timeout_ms);

}  // namespace pinyon_shift::quest
