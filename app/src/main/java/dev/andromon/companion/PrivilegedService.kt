// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.content.Context
import android.os.Binder
import org.json.JSONObject
import kotlin.system.exitProcess

/**
 * The Shizuku backend of [Privileged]: a Shizuku "UserService". Shizuku starts this class from the
 * companion's own APK in a process of its own, as the shell user (or root, when Shizuku runs as
 * root), and hands its binder to the companion only. It serves exactly the commands the
 * PC-started helper serves ([PrivilegedCommands]); there is no way to pass it a command line.
 *
 * It is not a normal app process: no Application, no usable Context. R8 keeps the class and its
 * constructors (proguard-rules.pro), because Shizuku creates it by name.
 */
class PrivilegedService() : IPrivilegedService.Stub() {

    /** Shizuku 13+ prefers this constructor; the Context is of no use here. */
    @Suppress("unused", "UNUSED_PARAMETER")
    constructor(context: Context) : this()

    /**
     * The companion's uid, looked up once. Shizuku only gives this binder to the app that asked
     * for the service; checking the caller as well costs nothing. Null when it couldn't be read.
     */
    private val ownerAppId: Int? by lazy {
        runCatching {
            PrivilegedCommands.run("pm", "list", "packages", "-U", PrivilegedCommands.PKG).lineSequence()
                .firstOrNull { it.startsWith("package:${PrivilegedCommands.PKG} ") }
                ?.substringAfter("uid:")?.trim()?.substringBefore(',')?.toIntOrNull()?.rem(100_000)
        }.getOrNull()
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun call(method: String?, paramsJson: String?): String {
        val reply = try {
            val caller = Binder.getCallingUid()
            val owner = ownerAppId
            if (owner != null && caller % 100_000 != owner) throw SecurityException("Not the companion app")
            JSONObject().put("result", PrivilegedCommands.call(method ?: "", JSONObject(paramsJson?.ifEmpty { null } ?: "{}")))
        } catch (e: Throwable) {
            JSONObject().put("error", e.message ?: e.javaClass.simpleName)
        }
        return reply.toString()
    }
}
