package dev.pocketvm.core.model

import kotlinx.serialization.Serializable

/**
 * Where QEMU's built-in VNC server listens.
 *
 * PocketVM deliberately ships **no renderer**: the VNC endpoint is the product's
 * display API, and the user points any RFB client at it (desktop TigerVNC, an
 * Android VNC viewer, or `adb forward` + local client).
 *
 * [LOOPBACK] — binds 127.0.0.1. Only reachable from the phone itself, or through
 * `adb forward tcp:5900 tcp:5900`. No password needed.
 *
 * [LAN] — binds 0.0.0.0 so a PC on the same Wi-Fi can connect directly. Requires
 * a password: an unauthenticated VNC endpoint on a shared network hands anyone
 * the guest's screen and keyboard.
 */
@Serializable
enum class VncAccess { LOOPBACK, LAN }
