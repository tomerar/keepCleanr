// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.keepclear.jvm.library)
}

// M17, ADR 0011: the one HTTP/1.1 client the network backup targets share (MiniHttp). Pure JVM, no third-party
// library (PRIV-1). It declares no permission: INTERNET comes only from the target modules' manifests.
