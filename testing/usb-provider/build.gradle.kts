// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.keepclear.android.library)
}

// Test double: a DocumentsProvider that behaves like a USB drive. Used by instrumented tests and only ever
// packaged into debug builds (`debugImplementation`); the release manifest policy rejects it.
android {
    namespace = "io.github.tomerar.keepclear.testing.usb"
}
