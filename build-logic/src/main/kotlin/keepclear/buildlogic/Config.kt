// SPDX-License-Identifier: GPL-3.0-or-later
package keepclear.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/** Single source of SDK levels (ADR 0003). */
object KeepClearSdk {
    const val MIN = 30
    const val TARGET = 36
    const val COMPILE = 37
}

const val APPLICATION_ID = "io.github.tomerar.keepclear"

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.lib(alias: String) = findLibrary(alias).get()
