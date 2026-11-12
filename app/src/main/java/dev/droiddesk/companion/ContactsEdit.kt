// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors

package dev.droiddesk.companion

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Editing contacts from Droid Desk's Contacts tab: change, delete, photos.
 *
 * A contact on Android is a merged view of one or more "raw contacts" (one per account: Google,
 * the phone itself, WhatsApp…). Edits are written to the raw contacts the person can normally
 * edit; the entries that messaging apps and SIM cards add for themselves are left alone.
 */
internal class ContactsEdit(private val ctx: Context) {

    private val cr = ctx.contentResolver

    fun call(method: String, p: JSONObject): Any = when (method) {
        "contacts.update" -> update(p.getJSONObject("contact"))
        "contacts.delete" -> delete(p.getJSONArray("ids"))
        "contacts.photo" -> photo(p.getLong("id"), p.optInt("size", 0))
        "contacts.setPhoto" -> setPhoto(p.getLong("id"), if (p.isNull("data")) null else p.getString("data"))
        else -> throw IllegalArgumentException("Unknown method: $method")
    }

    private fun requireWrite() {
        if (ctx.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("no access to change contacts")
        }
    }

    // ---------- extra fields for contacts.list ----------

    /** Adds hasPhoto, accountType, accountName, rawId and rawCount to each contact of `contacts.list`. */
    fun decorate(byId: Map<Long, JSONObject>) {
        val seen = HashMap<Long, Int>()
        try {
            cr.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts.CONTACT_ID, ContactsContract.RawContacts._ID,
                    ContactsContract.RawContacts.ACCOUNT_TYPE, ContactsContract.RawContacts.ACCOUNT_NAME),
                "${ContactsContract.RawContacts.DELETED}=0", null, "${ContactsContract.RawContacts._ID} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.isNull(0)) continue
                    val id = c.getLong(0)
                    val o = byId[id] ?: continue
                    val n = (seen[id] ?: 0) + 1
                    seen[id] = n
                    if (n == 1) {
                        o.put("rawId", c.getLong(1))
                            .put("accountType", if (c.isNull(2)) "" else c.getString(2))
                            .put("accountName", if (c.isNull(3)) "" else c.getString(3))
                    }
                }
            }
        } catch (_: Exception) {
        }
        val withPhoto = HashSet<Long>()
        try {
            cr.query(
                ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID),
                "${ContactsContract.Contacts.PHOTO_ID} IS NOT NULL OR ${ContactsContract.Contacts.PHOTO_URI} IS NOT NULL", null, null,
            )?.use { c -> while (c.moveToNext()) withPhoto += c.getLong(0) }
        } catch (_: Exception) {
        }
        for ((id, o) in byId) {
            o.put("hasPhoto", id in withPhoto).put("rawCount", seen[id] ?: 0)
            if (!o.has("rawId")) o.put("accountType", "").put("accountName", "")
        }
    }

    // ---------- raw contacts ----------

    private class Raw(val id: Long, val accountType: String?)

    private fun rawContacts(contactId: Long): List<Raw> {
        val out = ArrayList<Raw>()
        cr.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.ACCOUNT_TYPE),
            "${ContactsContract.RawContacts.CONTACT_ID}=? AND ${ContactsContract.RawContacts.DELETED}=0",
            arrayOf(contactId.toString()), "${ContactsContract.RawContacts._ID} ASC",
        )?.use { c -> while (c.moveToNext()) out += Raw(c.getLong(0), if (c.isNull(1)) null else c.getString(1)) }
        return out
    }

    /** 0 = the phone's own or a Google account, 1 = another account, 2 = an app's or SIM card's own entry. */
    private fun rank(type: String?): Int {
        val t = type?.lowercase() ?: return 0
        return when {
            APP_OWNED.any { it in t } -> 2
            t == "com.google" || "local" in t || "phone" in t || "device" in t -> 0
            else -> 1
        }
    }

    /** The raw contacts an edit applies to, best target first. */
    private fun editable(contactId: Long): List<Raw> {
        val all = rawContacts(contactId)
        if (all.isEmpty()) throw IllegalArgumentException("That contact is no longer on the phone")
        val mine = all.filter { rank(it.accountType) < 2 }
        return (mine.ifEmpty { all }).sortedBy { rank(it.accountType) }
    }

    // ---------- update ----------

    private fun update(ct: JSONObject): JSONObject {
        requireWrite()
        val contactId = ct.getLong("id")
        val raws = editable(contactId)
        val target = raws.first().id
        val ids = raws.joinToString(",") { it.id.toString() }
        val ops = ArrayList<ContentProviderOperation>()
        val mimes = listOf(
            StructuredName.CONTENT_ITEM_TYPE, Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE,
            Organization.CONTENT_ITEM_TYPE, Note.CONTENT_ITEM_TYPE,
        )
        ops += ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
            .withSelection(
                "${ContactsContract.Data.RAW_CONTACT_ID} IN ($ids) AND (${ContactsContract.Data.MIMETYPE} IN (${mimes.joinToString { "?" }})" +
                    " OR (${ContactsContract.Data.MIMETYPE}=? AND ${Event.TYPE}=${Event.TYPE_BIRTHDAY}))",
                (mimes + Event.CONTENT_ITEM_TYPE).toTypedArray(),
            ).build()

        fun data(mime: String, vararg values: Pair<String, Any?>) {
            val b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, target)
                .withValue(ContactsContract.Data.MIMETYPE, mime)
            for ((k, v) in values) if (v != null) b.withValue(k, v)
            ops += b.build()
        }
        val name = ct.optString("name").trim()
        if (name.isNotEmpty()) data(StructuredName.CONTENT_ITEM_TYPE, StructuredName.DISPLAY_NAME to name)
        ct.optJSONArray("phones")?.let { arr ->
            for (j in 0 until arr.length()) {
                val ph = arr.getJSONObject(j)
                val number = ph.optString("number").trim()
                if (number.isEmpty()) continue
                val (type, label) = ContactTypes.phone(ph.optString("type"))
                data(Phone.CONTENT_ITEM_TYPE, Phone.NUMBER to number, Phone.TYPE to type, Phone.LABEL to label)
            }
        }
        ct.optJSONArray("emails")?.let { arr ->
            for (j in 0 until arr.length()) {
                val em = arr.getJSONObject(j)
                val address = em.optString("address").trim()
                if (address.isEmpty()) continue
                val (type, label) = ContactTypes.email(em.optString("type"))
                data(Email.CONTENT_ITEM_TYPE, Email.ADDRESS to address, Email.TYPE to type, Email.LABEL to label)
            }
        }
        ct.optString("org").takeIf { it.isNotBlank() }?.let { data(Organization.CONTENT_ITEM_TYPE, Organization.COMPANY to it) }
        ct.optString("note").takeIf { it.isNotBlank() }?.let { data(Note.CONTENT_ITEM_TYPE, Note.NOTE to it) }
        ct.optString("birthday").takeIf { it.isNotBlank() }?.let {
            data(Event.CONTENT_ITEM_TYPE, Event.START_DATE to it, Event.TYPE to Event.TYPE_BIRTHDAY)
        }
        if (ct.has("starred") && !ct.isNull("starred")) {
            ops += ContentProviderOperation.newUpdate(ContactsContract.RawContacts.CONTENT_URI)
                .withSelection("${ContactsContract.RawContacts._ID} IN ($ids)", null)
                .withValue(ContactsContract.RawContacts.STARRED, if (ct.optBoolean("starred")) 1 else 0)
                .build()
        }
        cr.applyBatch(ContactsContract.AUTHORITY, ops)
        // Android may regroup raw contacts after an edit, which gives the contact a new id.
        val now = cr.query(
            ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, target),
            arrayOf(ContactsContract.RawContacts.CONTACT_ID), null, null, null,
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
        return JSONObject().put("ok", true).put("id", now ?: contactId)
    }

    // ---------- delete ----------

    private fun delete(ids: JSONArray): JSONObject {
        requireWrite()
        var deleted = 0
        val failed = JSONArray()
        for (i in 0 until ids.length()) {
            val id = ids.getLong(i)
            val ok = try {
                cr.delete(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id), null, null) > 0 ||
                    cr.delete(ContactsContract.RawContacts.CONTENT_URI, "${ContactsContract.RawContacts.CONTACT_ID}=?", arrayOf(id.toString())) > 0
            } catch (_: Exception) {
                false
            }
            if (ok) deleted++ else failed.put(id)
        }
        return JSONObject().put("deleted", deleted).put("failed", failed)
    }

    // ---------- photos ----------

    private fun photo(contactId: Long, size: Int): Any {
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        val small = size in 1..96
        val bytes = try {
            ContactsContract.Contacts.openContactPhotoInputStream(cr, uri, !small)?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } ?: return JSONObject.NULL
        if (bytes.isEmpty()) return JSONObject.NULL
        val jpeg = if (size <= 0 && Pictures.isJpeg(bytes)) bytes
        else if (small && Pictures.isJpeg(bytes)) bytes // the stored thumbnail is already 96 px
        else Pictures.toJpeg(bytes, if (size > 0) size.coerceIn(32, 2048) else 2048, 85) ?: return JSONObject.NULL
        return Base64.encodeToString(jpeg, Base64.NO_WRAP)
    }

    private fun setPhoto(contactId: Long, data: String?): Boolean {
        requireWrite()
        val raws = editable(contactId)
        if (data == null) {
            cr.delete(
                ContactsContract.Data.CONTENT_URI,
                "${ContactsContract.Data.RAW_CONTACT_ID} IN (${raws.joinToString(",") { it.id.toString() }}) AND ${ContactsContract.Data.MIMETYPE}=?",
                arrayOf(Photo.CONTENT_ITEM_TYPE),
            )
            return true
        }
        val jpeg = photoBytes(data) ?: throw IllegalArgumentException("That picture can't be read")
        val rawUri = ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, raws.first().id)
        val photoUri = Uri.withAppendedPath(rawUri, ContactsContract.RawContacts.DisplayPhoto.CONTENT_DIRECTORY)
        val fd = cr.openAssetFileDescriptor(photoUri, "rw") ?: throw IllegalStateException("The phone didn't accept the photo")
        fd.use { it.createOutputStream().use { out -> out.write(jpeg) } }
        return true
    }

    companion object {
        /** Account types whose entries belong to an app or a SIM card and aren't meant to be edited. */
        private val APP_OWNED = listOf("whatsapp", "telegram", "signal", "viber", "threema", "skype", "facebook", "sim", "icc")

        /** A base64 picture from the PC as a JPEG of at most 720 px, or null if it can't be decoded. */
        fun photoBytes(base64: String): ByteArray? = try {
            Pictures.toJpeg(Base64.decode(base64, Base64.DEFAULT), 720, 85)
        } catch (_: Exception) {
            null
        }
    }
}

/** Phone and e-mail type names as the PC uses them -> Android's (type code, custom label). */
internal object ContactTypes {
    fun phone(t: String): Pair<Int, String?> = when (t.trim().lowercase()) {
        "mobile", "cell" -> Phone.TYPE_MOBILE to null
        "home" -> Phone.TYPE_HOME to null
        "work" -> Phone.TYPE_WORK to null
        "main" -> Phone.TYPE_MAIN to null
        "fax" -> Phone.TYPE_FAX_WORK to null
        "other", "" -> Phone.TYPE_OTHER to null
        else -> Phone.TYPE_CUSTOM to t.trim()
    }

    fun email(t: String): Pair<Int, String?> = when (t.trim().lowercase()) {
        "home" -> Email.TYPE_HOME to null
        "work" -> Email.TYPE_WORK to null
        "mobile" -> Email.TYPE_MOBILE to null
        "other", "" -> Email.TYPE_OTHER to null
        else -> Email.TYPE_CUSTOM to t.trim()
    }
}
