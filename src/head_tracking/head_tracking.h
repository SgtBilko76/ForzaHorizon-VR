#pragma once
// Head tracking (FB-12.14, #489): turns and moves the cockpit camera with the
// player's head, from OpenTrack's "UDP over network" output (TrackIR, webcams
// with OpenTrack's face trackers, phones and VR headsets through OpenTrack).
// The built-in immersive camera mod adds one camera layer per axis; the
// layer update sets each layer's factor from the pose here.

#include <array>
#include <cstdint>
#include <optional>
#include <span>
#include <string_view>

#include <rex/cvar.h>

REXCVAR_DECLARE(bool, head_tracking);
REXCVAR_DECLARE(int32_t, head_tracking_port);
REXCVAR_DECLARE(std::string, head_tracking_invert);
REXCVAR_DECLARE(std::string, head_tracking_test_pose);

namespace pinyon_shift::head_tracking {

// Yaw, pitch and roll in degrees (positive: look right, look up, tilt
// right) and x, y, z in metres (right, up, forward), in that order.
using Pose = std::array<float, 6>;
enum Axis { kYaw, kPitch, kRoll, kX, kY, kZ };

// One OpenTrack UDP packet: six little-endian doubles, x, y, z in
// centimetres and yaw, pitch, roll in degrees. Non-finite values are 0.
std::optional<Pose> ParseOpenTrack(std::span<const uint8_t> packet);

// "yaw,pitch,..." names (any of yaw, pitch, roll, x, y, z) to negate.
std::array<bool, 6> ParseInvert(std::string_view text);

// The pose to apply now: the test pose when one is set, else the latest
// packet (eased back to centre after a second without one), with the
// inverted axes negated. All zero while head tracking is off.
Pose Current();

// Starts or stops the receiver to follow the settings; cheap to call every
// frame.
void Update();

// A pose source inside the app (a VR runtime's head pose) instead of
// OpenTrack: asked each time the camera reads the pose; returns false when it
// has none. With a source set, no UDP receiver runs.
using PoseSource = bool (*)(Pose* out);
void SetPoseSource(PoseSource source);

// A vertical field of view (radians) every camera uses instead of its own,
// 0 for none (the cameras keep theirs). Set by the VR view.
void SetFieldOfViewOverride(float radians);
float FieldOfViewOverride();

}  // namespace pinyon_shift::head_tracking
