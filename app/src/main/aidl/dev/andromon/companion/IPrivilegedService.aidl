// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion;

/**
 * The Shizuku UserService (PrivilegedService.kt): the companion's own code, run by Shizuku with
 * shell or root rights. One call, the same fixed commands the PC-started helper serves
 * (PrivilegedCommands.kt). No shell, no free-form command lines.
 */
interface IPrivilegedService {
    /** Reserved by Shizuku: called when the service should exit. */
    void destroy() = 16777114;

    /** Runs one allow-listed command. Returns {"result": …} or {"error": "…"} as JSON text. */
    String call(String method, String paramsJson) = 1;
}
