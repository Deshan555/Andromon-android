// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.Telephony
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject

/** The methods the PC can call. Every value returned is plain JSON. */
class Api(private val ctx: Context) {

    fun call(method: String, p: JSONObject): Any = when (method) {
        "info" -> info()
        "sms.list" -> smsList(p.optInt("limit", 0))
        "sms.send" -> smsSend(p.getString("to"), p.getString("body"))
        "contacts.list" -> contactsList()
        "contacts.add" -> contactsAdd(p.getJSONArray("contacts"))
        "calls.list" -> callsList(p.optInt("limit", 0))
        "call.answer" -> CallWatch.answer(ctx)
        "call.end" -> CallWatch.end(ctx)
        "contacts.update", "contacts.delete", "contacts.photo", "contacts.setPhoto" -> ContactsEdit(ctx).call(method, p)
        "sms.restore", "calls.restore" -> Restore(ctx).call(method, p)
        "battery.health" -> BatteryLog.healthReport(ctx)
        else -> when {
            method.startsWith("mms.") -> Mms(ctx).call(method, p)
            method.startsWith("calendar.") -> CalendarApi(ctx).call(method, p)
            method.startsWith("media.") -> Media(ctx).call(method, p)
            method.startsWith("notif.") -> NotifWatch.call(ctx, method, p)
            method.startsWith("apps.") -> AppIcons.call(ctx, method, p)
            method.startsWith("push.") || method.startsWith("clip.") || method.startsWith("phone.") -> Links.call(ctx, method, p)
            method.startsWith("player.") -> Player.call(ctx, method, p)
            else -> throw IllegalArgumentException("Unknown method: $method")
        }
    }

    // ---------- info ----------

    private val permissions = listOf(
        Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG,
        Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR,
        Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS,
    )

    private fun info(): JSONObject {
        val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val perms = JSONObject()
        for (p in permissions) {
            perms.put(p.removePrefix("android.permission."), ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED)
        }
        val media = Media(ctx)
        perms.put("MEDIA_IMAGES", media.canRead("image")).put("MEDIA_VIDEO", media.canRead("video")).put("MEDIA_AUDIO", media.canRead("audio"))
        // Not a permission but an app-op Andromon sets over adb; restoring SMS needs it.
        perms.put("WRITE_SMS_OP", Restore.writeSmsAllowed(ctx) == true)
        return JSONObject()
            .put("version", pkg.longVersionCode)
            .put("versionName", pkg.versionName)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("permissions", perms)
            .put("defaultSmsApp", Telephony.Sms.getDefaultSmsPackage(ctx) ?: JSONObject.NULL)
    }

    // ---------- SMS ----------

    private fun smsList(limit: Int): JSONArray {
        val out = JSONArray()
        val projection = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
            Telephony.Sms.DATE, Telephony.Sms.DATE_SENT, Telephony.Sms.TYPE, Telephony.Sms.READ,
        )
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI, projection, null, null, "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                out.put(
                    JSONObject()
                        .put("id", c.getLong(0))
                        .put("thread", c.getLong(1))
                        .put("address", c.str(2))
                        .put("body", c.str(3))
                        .put("date", c.getLong(4))
                        .put("dateSent", c.getLong(5))
                        .put("type", c.getInt(6))
                        .put("read", c.getInt(7) == 1)
                )
                if (limit > 0 && out.length() >= limit) break
            }
        }
        return out
    }

    private fun smsSend(to: String, body: String): JSONObject {
        require(to.isNotBlank()) { "Missing phone number" }
        require(body.isNotEmpty()) { "Message is empty" }
        @Suppress("DEPRECATION")
        val sms: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The application context: SmsManager keeps the context it was made from in a static
            // cache, which held on to every stopped PC-link service.
            ctx.applicationContext.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
        val parts = sms.divideMessage(body)
        // As a non-default SMS app, the system saves the message to the Sent folder for us.
        if (parts.size > 1) sms.sendMultipartTextMessage(to, null, parts, null, null)
        else sms.sendTextMessage(to, null, body, null, null)
        return JSONObject().put("parts", parts.size)
    }

    // ---------- Contacts ----------

    private fun contactsList(): JSONArray {
        val byId = LinkedHashMap<Long, JSONObject>()
        val projection = arrayOf(
            ContactsContract.Data.CONTACT_ID, ContactsContract.Data.DISPLAY_NAME,
            ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1,
            ContactsContract.Data.DATA2, ContactsContract.Data.DATA3, ContactsContract.Data.STARRED,
        )
        val mimes = listOf(
            Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE, Organization.CONTENT_ITEM_TYPE,
            Event.CONTENT_ITEM_TYPE, Note.CONTENT_ITEM_TYPE, StructuredName.CONTENT_ITEM_TYPE,
        )
        val selection = "${ContactsContract.Data.MIMETYPE} IN (${mimes.joinToString { "?" }})"
        ctx.contentResolver.query(
            ContactsContract.Data.CONTENT_URI, projection, selection, mimes.toTypedArray(),
            "${ContactsContract.Data.DISPLAY_NAME} COLLATE NOCASE ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val contact = byId.getOrPut(id) {
                    JSONObject().put("id", id).put("name", c.str(1)).put("starred", c.getInt(6) == 1)
                        .put("phones", JSONArray()).put("emails", JSONArray())
                }
                val data1 = c.str(3)
                if (data1.isEmpty()) continue
                when (c.str(2)) {
                    Phone.CONTENT_ITEM_TYPE -> contact.getJSONArray("phones").put(
                        JSONObject().put("number", data1).put("type", phoneType(c.getInt(4), c.str(5)))
                    )
                    Email.CONTENT_ITEM_TYPE -> contact.getJSONArray("emails").put(
                        JSONObject().put("address", data1).put("type", emailType(c.getInt(4), c.str(5)))
                    )
                    Organization.CONTENT_ITEM_TYPE -> contact.put("org", data1)
                    Note.CONTENT_ITEM_TYPE -> contact.put("note", data1)
                    Event.CONTENT_ITEM_TYPE -> if (c.getInt(4) == Event.TYPE_BIRTHDAY) contact.put("birthday", data1)
                }
            }
        }
        ContactsEdit(ctx).decorate(byId) // hasPhoto, accountType, accountName, rawId, rawCount
        return JSONArray(byId.values)
    }

    /**
     * Adds contacts to the phone's local ("Phone") account.
     * Input: [{name, phones:[{number,type}], emails:[{address,type}], org?, note?, birthday?}]
     */
    private fun contactsAdd(list: JSONArray): JSONObject {
        var added = 0
        var photoBytes = 0 // queued contact photos, which count towards the binder limit
        val ops = ArrayList<ContentProviderOperation>()
        fun flush() {
            if (ops.isNotEmpty()) {
                ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                ops.clear()
            }
            photoBytes = 0
        }
        for (i in 0 until list.length()) {
            val ct = list.getJSONObject(i)
            val back = ops.size
            ops += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .withValue(ContactsContract.RawContacts.STARRED, if (ct.optBoolean("starred")) 1 else 0)
                .build()
            fun data(mime: String, vararg values: Pair<String, Any?>) {
                val b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, back)
                    .withValue(ContactsContract.Data.MIMETYPE, mime)
                for ((k, v) in values) b.withValue(k, v)
                ops += b.build()
            }
            val name = ct.optString("name").trim()
            if (name.isNotEmpty()) data(StructuredName.CONTENT_ITEM_TYPE, StructuredName.DISPLAY_NAME to name)
            ct.optJSONArray("phones")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val ph = arr.getJSONObject(j)
                    data(Phone.CONTENT_ITEM_TYPE, Phone.NUMBER to ph.getString("number"), Phone.TYPE to phoneTypeCode(ph.optString("type")))
                }
            }
            ct.optJSONArray("emails")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val em = arr.getJSONObject(j)
                    data(Email.CONTENT_ITEM_TYPE, Email.ADDRESS to em.getString("address"), Email.TYPE to emailTypeCode(em.optString("type")))
                }
            }
            ct.optString("org").takeIf { it.isNotBlank() }?.let { data(Organization.CONTENT_ITEM_TYPE, Organization.COMPANY to it) }
            ct.optString("note").takeIf { it.isNotBlank() }?.let { data(Note.CONTENT_ITEM_TYPE, Note.NOTE to it) }
            ct.optString("birthday").takeIf { it.isNotBlank() }?.let {
                data(Event.CONTENT_ITEM_TYPE, Event.START_DATE to it, Event.TYPE to Event.TYPE_BIRTHDAY)
            }
            ct.optString("photo").takeIf { it.isNotEmpty() }?.let { ContactsEdit.photoBytes(it) }?.let {
                data(ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Photo.PHOTO to it)
                photoBytes += it.size
            }
            added++
            if (ops.size > 400 || photoBytes > 300_000) flush() // stay well under the binder transaction limit
        }
        flush()
        return JSONObject().put("added", added)
    }

    private fun phoneType(code: Int, label: String) = when (code) {
        Phone.TYPE_MOBILE -> "mobile"; Phone.TYPE_HOME -> "home"; Phone.TYPE_WORK -> "work"
        Phone.TYPE_MAIN -> "main"; Phone.TYPE_FAX_WORK, Phone.TYPE_FAX_HOME -> "fax"
        Phone.TYPE_CUSTOM -> label.ifBlank { "other" }; else -> "other"
    }
    private fun phoneTypeCode(t: String) = when (t.lowercase()) {
        "mobile", "cell" -> Phone.TYPE_MOBILE; "home" -> Phone.TYPE_HOME; "work" -> Phone.TYPE_WORK
        "main" -> Phone.TYPE_MAIN; "fax" -> Phone.TYPE_FAX_WORK; else -> Phone.TYPE_OTHER
    }
    private fun emailType(code: Int, label: String) = when (code) {
        Email.TYPE_HOME -> "home"; Email.TYPE_WORK -> "work"; Email.TYPE_MOBILE -> "mobile"
        Email.TYPE_CUSTOM -> label.ifBlank { "other" }; else -> "other"
    }
    private fun emailTypeCode(t: String) = when (t.lowercase()) {
        "home" -> Email.TYPE_HOME; "work" -> Email.TYPE_WORK; "mobile" -> Email.TYPE_MOBILE; else -> Email.TYPE_OTHER
    }

    // ---------- Call log ----------

    private fun callsList(limit: Int): JSONArray {
        val out = JSONArray()
        val projection = arrayOf(
            CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE,
            CallLog.Calls.DURATION, CallLog.Calls.TYPE,
        )
        val uri: Uri = CallLog.Calls.CONTENT_URI
        ctx.contentResolver.query(uri, projection, null, null, "${CallLog.Calls.DATE} DESC")?.use { c ->
            while (c.moveToNext()) {
                out.put(
                    JSONObject()
                        .put("number", c.str(0))
                        .put("name", c.str(1))
                        .put("date", c.getLong(2))
                        .put("duration", c.getLong(3))
                        .put("type", c.getInt(4))
                )
                if (limit > 0 && out.length() >= limit) break
            }
        }
        return out
    }
}

private fun Cursor.str(i: Int): String = if (isNull(i)) "" else getString(i)
