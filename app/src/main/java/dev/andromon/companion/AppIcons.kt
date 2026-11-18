// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * App icons and names for the PC's Apps and Debloat lists. ADB can list packages, but only an app
 * on the phone can ask Android to draw an icon (adaptive icons, themed icons, resource lookups).
 */
internal object AppIcons {

    /** The PC asks in batches as rows scroll into view; this caps one request. */
    private const val MAX_PER_CALL = 100

    fun call(ctx: Context, method: String, p: JSONObject): Any = when (method) {
        "apps.icons" -> icons(ctx, p.getJSONArray("pkgs"), p.optInt("size", 96).coerceIn(32, 256))
        else -> throw IllegalArgumentException("Unknown method: $method")
    }

    /** `{ "<pkg>": { "label": "...", "icon": "<base64 PNG>" | null } }`; packages that aren't installed are left out. */
    private fun icons(ctx: Context, pkgs: JSONArray, size: Int): JSONObject {
        val pm = ctx.packageManager
        // Disabled apps, and apps removed for this user only (Debloat), still have an icon to show.
        @Suppress("DEPRECATION")
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS or
            PackageManager.MATCH_UNINSTALLED_PACKAGES
        val out = JSONObject()
        for (i in 0 until minOf(pkgs.length(), MAX_PER_CALL)) {
            val pkg = pkgs.optString(i).takeIf { it.isNotEmpty() } ?: continue
            @Suppress("DEPRECATION")
            val ai = try { pm.getApplicationInfo(pkg, flags) } catch (_: PackageManager.NameNotFoundException) { continue }
            val label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(pkg)
            val icon = runCatching { png(ai.loadIcon(pm), size) }.getOrNull()
            out.put(pkg, JSONObject().put("label", label).put("icon", icon ?: JSONObject.NULL))
        }
        return out
    }

    private fun png(d: Drawable, size: Int): String {
        val bmp = if (d is BitmapDrawable && d.bitmap != null) Bitmap.createScaledBitmap(d.bitmap, size, size, true)
        else Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { b ->
            d.setBounds(0, 0, size, size); d.draw(Canvas(b))
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
