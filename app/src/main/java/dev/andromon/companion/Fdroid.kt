// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.JsonReader
import android.util.JsonToken
import android.util.LruCache
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.jar.JarFile
import kotlin.concurrent.thread

/**
 * The F-Droid catalogue on the phone (the screen is [FdroidScreen]).
 *
 * F-Droid (https://f-droid.org) is a catalogue of free and open-source Android apps. The companion
 * ships none of it: when the person asks, this downloads F-Droid's own index and, for an app they
 * choose, that app's APK. Everything is checked before it is used, the same way Andromon on the
 * computer does it:
 *  - entry.jar: a small signed JAR. Its signature is checked against F-Droid's signing certificate
 *    (pinned by SHA-256 fingerprint). It names the index and its hash.
 *  - index-v2.json: every app and version, with the SHA-256 of each file. Checked against the hash
 *    from entry.jar, then boiled down to the English texts in a small database.
 *  - icons and APKs: each one is checked against its SHA-256 from the index.
 * An index older than the one already here is refused, so an old signed index can't be replayed.
 *
 * Installing goes through Android's own installer, which asks the person before anything is added.
 */
internal object Fdroid {

    private const val REPO = "https://f-droid.org/repo"
    /** SHA-256 of the certificate that signs f-droid.org's index (published on f-droid.org/docs). */
    private const val FINGERPRINT = "43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab"
    /** Of the database; bump when [build] stores something new. */
    private const val FORMAT = 1
    private const val MAX_ENTRY = 4L * 1024 * 1024
    private const val MAX_INDEX = 400L * 1024 * 1024
    private const val MAX_ICON = 2L * 1024 * 1024
    private const val MAX_APK = 2L * 1024 * 1024 * 1024
    private val SAFE_NAME = Regex("""^/[\w.\-+=@~ ]+(?:/[\w.\-+=@~ ]+)*$""") // a file inside the repo
    private val PKG = Regex("""^[A-Za-z]\w*(?:\.\w+)+$""")
    private val HEX64 = Regex("^[0-9a-f]{64}$")
    private val LINK = Regex("""^https?://[^\s"<>]{1,500}$""")

    /** A message meant for the person, in plain words. */
    class Problem(message: String) : Exception(message)

    class Version(
        val code: Long, val name: String, val file: String, val sha256: String, val size: Long, val added: Long,
        val minSdk: Int, val targetSdk: Int, val maxSdk: Int, val abis: List<String>, val perms: List<String>,
        val signers: List<String>, val beta: Boolean, val anti: List<String>, val whatsNew: String,
    )

    /** [description] is only filled by [details]. */
    class App(
        val pkg: String, val name: String, val summary: String, val description: String,
        val iconName: String, val iconSha: String, val categories: List<String>, val license: String, val author: String,
        val webSite: String, val sourceCode: String, val issueTracker: String, val changelog: String,
        val added: Long, val lastUpdated: Long, val preferredSigner: String, val versions: List<Version>,
    )

    /** The copy of an app that is on this phone. */
    class Installed(val code: Long, val versionName: String, val signers: Set<String>)

    /** An app as the screen shows it: what F-Droid has, what the phone has and the version to offer. */
    class Item(val app: App, val installed: Installed?, val suggested: Version?, val why: String) {
        /** False when the copy on the phone was signed with another key, so F-Droid's can't replace it. */
        val sameSigner: Boolean
            get() = installed == null || suggested == null || suggested.signers.isEmpty() || installed.signers.isEmpty() ||
                suggested.signers.any(installed.signers::contains)
        val canUpdate: Boolean get() = installed != null && suggested != null && suggested.code > installed.code && sameSigner
    }

    class Status(
        val timestamp: Long, val fetchedAt: Long, val apps: Int,
        /** id to name, sorted by name. */
        val categories: List<Pair<String, String>>,
        /** id to name and description. */
        val antiFeatures: Map<String, Pair<String, String>>,
    )

    class Page(val total: Int, val items: List<Item>)

    enum class Sort(val label: String) { UPDATED("Recently updated"), ADDED("Newest"), NAME("Name") }

    // ---------- the one running download ----------

    enum class Phase { DOWNLOAD, CHECK, READ, INSTALL }

    /** The catalogue download ([pkg] null) or an app's. */
    class Job(val pkg: String?, val label: String) {
        @Volatile var phase = Phase.DOWNLOAD
        @Volatile var done = 0L
        @Volatile var total = 0L
        val cancelled = AtomicBoolean(false)
        @Volatile var conn: HttpURLConnection? = null
    }

    sealed class Event {
        object Progress : Event()
        class CatalogueDone(val changed: Boolean) : Event()
        /** [pkg] null: the catalogue download failed. */
        class Failed(val pkg: String?, val message: String, val cancelled: Boolean) : Event()
        /** Android wants the person to confirm; start [intent] from the activity. */
        class Confirm(val pkg: String, val intent: Intent) : Event()
        class InstallDone(val pkg: String, val ok: Boolean, val message: String) : Event()
    }

    @Volatile var job: Job? = null
        private set
    /** The app handed to Android's installer and not answered yet, with its installer session. */
    @Volatile var installing: Pair<String, Int>? = null
        private set
    /** Called on the main thread. Events are dropped while no screen listens; [job] says what is running. */
    var listener: ((Event) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private fun emit(e: Event) { main.post { listener?.invoke(e) } }

    fun cancel() {
        val j = job ?: return
        j.cancelled.set(true)
        thread(isDaemon = true) { runCatching { j.conn?.disconnect() } }
    }

    /** Runs [work] as the one job, on its own thread. False when another one is running. */
    private fun start(j: Job, work: (Job) -> Unit): Boolean {
        synchronized(this) {
            if (job != null) return false
            job = j
        }
        thread(isDaemon = true, name = "fdroid") {
            try {
                work(j)
            } catch (e: Throwable) {
                val cancelled = j.cancelled.get()
                emit(Event.Failed(j.pkg, if (cancelled) "The download was cancelled." else friendly(e), cancelled))
            } finally {
                synchronized(this) { job = null }
                emit(Event.Progress)
            }
        }
        return true
    }

    private fun friendly(e: Throwable): String = when (e) {
        is Problem -> e.message ?: "Something went wrong."
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException,
        is javax.net.ssl.SSLException -> "Couldn't reach f-droid.org. Check the internet connection and try again."
        is IOException -> if (e.message?.contains("ENOSPC") == true) "There isn't enough free space on the phone."
            else "The download stopped. Check the internet connection and try again."
        else -> "Something went wrong. Try again."
    }

    // ---------- files ----------

    private fun dir(ctx: Context) = File(ctx.filesDir, "fdroid").apply { mkdirs() }
    private fun dbFile(ctx: Context) = File(dir(ctx), "catalog.db")
    private fun iconDir(ctx: Context) = File(dir(ctx), "icons").apply { mkdirs() }
    private fun tmpDir(ctx: Context) = File(ctx.cacheDir, "fdroid").apply { mkdirs() }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("fdroid", Context.MODE_PRIVATE)

    /** Space the catalogue, icons and downloads take up. */
    fun diskUse(ctx: Context): Long =
        (dir(ctx).walkTopDown() + tmpDir(ctx).walkTopDown()).filter { it.isFile }.sumOf { it.length() }

    /** Forgets the catalogue and everything downloaded. Apps that were installed stay. */
    fun clear(ctx: Context) {
        if (job != null) throw Problem("Wait for the running download to finish.")
        synchronized(this) {
            db?.close(); db = null; status = null
            dir(ctx).deleteRecursively()
            tmpDir(ctx).deleteRecursively()
            prefs(ctx).edit().clear().apply()
            icons.evictAll()
        }
    }

    // ---------- downloading ----------

    private fun sha256(): MessageDigest = MessageDigest.getInstance("SHA-256")
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun connect(name: String, j: Job?): HttpURLConnection {
        if (!SAFE_NAME.matches(name) || ".." in name) throw Problem("Download address not allowed.")
        val path = name.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val c = URL(REPO + path).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Andromon")
        j?.conn = c
        val code = c.responseCode
        if (code != 200) { c.disconnect(); throw Problem("Couldn't reach f-droid.org (HTTP $code).") }
        return c
    }

    /** Streams a repo file to [dest] and returns its SHA-256 and size. Progress goes to [j]. */
    private fun download(name: String, dest: File, limit: Long, j: Job?, what: String): Pair<String, Long> {
        val c = connect(name, j)
        val hash = sha256()
        var done = 0L
        var last = 0L
        try {
            dest.parentFile?.mkdirs()
            c.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (j?.cancelled?.get() == true) throw IOException("cancelled")
                        done += n
                        if (done > limit) throw Problem("$what is larger than expected.")
                        hash.update(buf, 0, n)
                        out.write(buf, 0, n)
                        if (j != null) {
                            j.done = done
                            val now = System.currentTimeMillis()
                            if (now - last > 250) { last = now; emit(Event.Progress) }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            dest.delete()
            throw e
        } finally {
            c.disconnect()
        }
        return hex(hash.digest()) to done
    }

    private fun fileSha(f: File): String {
        val hash = sha256()
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; hash.update(buf, 0, n) }
        }
        return hex(hash.digest())
    }

    // ---------- the signed entry ----------

    /**
     * Returns entry.json from a JAR signed with F-Droid's key, or throws. Android checks the JAR's
     * signature and the file's digest while the file is read; what is left to do here is to see
     * that the signer is the pinned certificate.
     */
    private fun readSignedEntry(jar: File): ByteArray {
        val bad = Problem("F-Droid's index isn't signed by F-Droid. Nothing was kept.")
        try {
            JarFile(jar, true).use { jf ->
                val entry = jf.getJarEntry("entry.json") ?: throw bad
                if (entry.size > MAX_ENTRY) throw bad
                val data = jf.getInputStream(entry).use { it.readBytes() } // reading to the end is what verifies it
                val signers = entry.codeSigners ?: throw bad
                val ok = signers.any { s ->
                    val cert = s.signerCertPath.certificates.firstOrNull() ?: return@any false
                    hex(sha256().digest(cert.encoded)) == FINGERPRINT
                }
                if (!ok) throw bad
                return data
            }
        } catch (e: Problem) {
            throw e
        } catch (_: Exception) { // a bad ZIP, a digest that doesn't match (SecurityException), …
            throw bad
        }
    }

    // ---------- updating the catalogue ----------

    /** Downloads F-Droid's index when it is newer than the one here. Ends with [Event.CatalogueDone] or [Event.Failed]. */
    fun update(ctx: Context): Boolean {
        val app = ctx.applicationContext
        return start(Job(null, "F-Droid catalogue")) { j ->
            val tmp = tmpDir(app)
            val jar = File(tmp, "entry.jar")
            download("/entry.jar", jar, MAX_ENTRY, null, "F-Droid's index")
            val unexpected = Problem("F-Droid sent an unexpected answer.")
            val entry = try { JSONObject(String(readSignedEntry(jar), Charsets.UTF_8)) } catch (e: org.json.JSONException) { throw unexpected } finally { jar.delete() }
            val ts = entry.optLong("timestamp", -1)
            val idx = entry.optJSONObject("index") ?: throw unexpected
            val name = idx.optString("name")
            val sha = idx.optString("sha256")
            val size = idx.optLong("size", 0)
            if (ts <= 0 || name.isEmpty() || !HEX64.matches(sha) || size <= 0 || size > MAX_INDEX) throw unexpected
            val have = status(app)
            if (have != null && ts < have.timestamp) throw Problem("F-Droid sent an older catalogue than the one already here. Nothing was changed.")
            if (have != null && ts == have.timestamp) {
                prefs(app).edit().putLong("fetchedAt", System.currentTimeMillis()).apply()
                synchronized(this) { status = null }
                emit(Event.CatalogueDone(false))
                return@start
            }
            j.total = size
            val index = File(tmp, "index-v2.json")
            val part = File(dir(app), "catalog.db.part")
            try {
                val (gotSha, _) = download(name, index, MAX_INDEX, j, "F-Droid's catalogue")
                if (gotSha != sha) throw Problem("The catalogue download doesn't match F-Droid's signed index. Nothing was kept.")
                j.phase = Phase.READ
                emit(Event.Progress)
                part.delete()
                val count = build(index, part, ts, j)
                if (count == 0) throw Problem("F-Droid's catalogue has an unexpected format.")
                synchronized(this) {
                    db?.close(); db = null; status = null
                    val dest = dbFile(app)
                    dest.delete()
                    File(dest.path + "-journal").delete()
                    if (!part.renameTo(dest)) throw Problem("Couldn't save the catalogue. Check the free space on the phone.")
                }
                prefs(app).edit().putLong("fetchedAt", System.currentTimeMillis()).apply()
            } finally {
                index.delete()
                part.delete()
                File(part.path + "-journal").delete()
            }
            emit(Event.CatalogueDone(true))
        }
    }

    // ---------- index-v2.json -> the database ----------

    private fun readValue(r: JsonReader): Any? = when (r.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            val m = LinkedHashMap<String, Any?>()
            r.beginObject()
            while (r.hasNext()) m[r.nextName()] = readValue(r)
            r.endObject()
            m
        }
        JsonToken.BEGIN_ARRAY -> {
            val l = ArrayList<Any?>()
            r.beginArray()
            while (r.hasNext()) l += readValue(r)
            r.endArray()
            l
        }
        JsonToken.STRING -> r.nextString()
        JsonToken.NUMBER -> r.nextString().let { it.toLongOrNull() ?: it.toDoubleOrNull() }
        JsonToken.BOOLEAN -> r.nextBoolean()
        JsonToken.NULL -> { r.nextNull(); null }
        else -> { r.skipValue(); null }
    }

    private fun obj(v: Any?): Map<*, *> = v as? Map<*, *> ?: emptyMap<Any, Any>()
    private fun arr(v: Any?): List<*> = v as? List<*> ?: emptyList<Any>()
    private fun str(v: Any?, max: Int = 400): String = (v as? String)?.take(max) ?: ""
    private fun num(v: Any?): Long = when (v) { is Long -> v; is Double -> v.toLong(); is String -> v.toLongOrNull() ?: 0; else -> 0 }
    /** A text in several languages: English, else the first one. */
    private fun text(v: Any?, max: Int): String {
        val m = v as? Map<*, *> ?: return str(v, max)
        return str(m["en-US"] ?: m["en-GB"] ?: m["en"] ?: m.values.firstOrNull(), max)
    }
    private fun link(v: Any?): String = (v as? String)?.takeIf { LINK.matches(it) } ?: ""
    private val ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "#39" to "'")
    /** Descriptions may carry simple HTML; the screen shows plain text. */
    private fun plain(s: String): String = s
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
        .replace(Regex("<li[^<>]*>", RegexOption.IGNORE_CASE), "• ")
        .replace(Regex("</(li|ul|ol|h\\d)>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</?[a-zA-Z][^<>]{0,300}>"), "")
        .replace(Regex("&(amp|lt|gt|quot|apos|nbsp|#39);")) { ENTITIES[it.groupValues[1]] ?: "" }
        .replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()

    /** name, sha256 and size of a file of the repo, or null when it isn't a plain file reference. */
    private fun fileRef(v: Any?): Triple<String, String, Long>? {
        val f = v as? Map<*, *> ?: return null
        val name = f["name"] as? String ?: return null
        val sha = f["sha256"] as? String ?: return null
        if (!SAFE_NAME.matches(name) || ".." in name || !HEX64.matches(sha)) return null
        return Triple(name, sha, num(f["size"]))
    }

    private const val SCHEMA_APPS = "CREATE TABLE apps (pkg TEXT PRIMARY KEY, name TEXT, summary TEXT, description TEXT, icon_name TEXT, " +
        "icon_sha TEXT, categories TEXT, license TEXT, author TEXT, website TEXT, source TEXT, issues TEXT, changelog TEXT, " +
        "added INTEGER, updated INTEGER, signer TEXT)"
    private const val SCHEMA_VERSIONS = "CREATE TABLE versions (pkg TEXT, code INTEGER, name TEXT, file TEXT, sha256 TEXT, size INTEGER, " +
        "added INTEGER, min_sdk INTEGER, target_sdk INTEGER, max_sdk INTEGER, abis TEXT, perms TEXT, signers TEXT, beta INTEGER, " +
        "anti TEXT, whats_new TEXT, PRIMARY KEY (pkg, code))"

    /** Reads [index] piece by piece (it is far too large to hold in memory) into a new database. Returns the number of apps. */
    private fun build(index: File, dest: File, timestamp: Long, j: Job): Int {
        val out = SQLiteDatabase.openOrCreateDatabase(dest, null)
        var count = 0
        try {
            out.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)")
            out.execSQL(SCHEMA_APPS)
            out.execSQL(SCHEMA_VERSIONS)
            out.execSQL("CREATE TABLE categories (id TEXT PRIMARY KEY, name TEXT)")
            out.execSQL("CREATE TABLE antifeatures (id TEXT PRIMARY KEY, name TEXT, description TEXT)")
            out.beginTransaction()
            val insApp = out.compileStatement("INSERT OR REPLACE INTO apps VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
            val insVer = out.compileStatement("INSERT OR REPLACE INTO versions VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
            JsonReader(index.inputStream().buffered(256 * 1024).reader(Charsets.UTF_8)).use { r ->
                r.beginObject()
                while (r.hasNext()) when (r.nextName()) {
                    "repo" -> {
                        val repo = obj(readValue(r))
                        for ((id, v) in obj(repo["categories"])) {
                            val key = (id as? String)?.take(60) ?: continue
                            out.execSQL("INSERT OR REPLACE INTO categories VALUES (?,?)", arrayOf(key, text(obj(v)["name"], 80).ifEmpty { key }))
                        }
                        for ((id, v) in obj(repo["antiFeatures"])) {
                            val key = (id as? String)?.take(60) ?: continue
                            out.execSQL("INSERT OR REPLACE INTO antifeatures VALUES (?,?,?)",
                                arrayOf(key, text(obj(v)["name"], 80).ifEmpty { key }, text(obj(v)["description"], 400)))
                        }
                    }
                    "packages" -> {
                        r.beginObject()
                        while (r.hasNext()) {
                            val pkg = r.nextName()
                            val p = obj(readValue(r))
                            if (j.cancelled.get()) throw IOException("cancelled")
                            if (addApp(insApp, insVer, pkg, p)) count++
                        }
                        r.endObject()
                    }
                    else -> r.skipValue()
                }
                r.endObject()
            }
            out.execSQL("INSERT INTO meta VALUES ('format', ?)", arrayOf(FORMAT.toString()))
            out.execSQL("INSERT INTO meta VALUES ('timestamp', ?)", arrayOf(timestamp.toString()))
            out.execSQL("INSERT INTO meta VALUES ('apps', ?)", arrayOf(count.toString()))
            out.setTransactionSuccessful()
            out.endTransaction()
        } finally {
            if (out.inTransaction()) out.endTransaction()
            out.close()
        }
        return count
    }

    private fun addApp(insApp: android.database.sqlite.SQLiteStatement, insVer: android.database.sqlite.SQLiteStatement, pkg: String, p: Map<*, *>): Boolean {
        val m = p["metadata"] as? Map<*, *> ?: return false
        if (!PKG.matches(pkg)) return false
        var versions = 0
        for (v in obj(p["versions"]).values) {
            val ver = obj(v)
            val mf = obj(ver["manifest"])
            val (file, sha, size) = fileRef(ver["file"]) ?: continue
            val code = mf["versionCode"] as? Long ?: continue
            if (!file.endsWith(".apk", ignoreCase = true) || code < 0 || size <= 0 || size > MAX_APK) continue
            val sdk = obj(mf["usesSdk"])
            val perms = (arr(mf["usesPermission"]) + arr(mf["usesPermissionSdk23"])).map { str(obj(it)["name"], 200) }.filter { it.isNotEmpty() }.distinct()
            insVer.clearBindings()
            insVer.bindString(1, pkg)
            insVer.bindLong(2, code)
            insVer.bindString(3, str(mf["versionName"], 80).ifEmpty { code.toString() })
            insVer.bindString(4, file)
            insVer.bindString(5, sha)
            insVer.bindLong(6, size)
            insVer.bindLong(7, num(ver["added"]))
            insVer.bindLong(8, num(sdk["minSdkVersion"]).coerceAtLeast(1))
            insVer.bindLong(9, num(sdk["targetSdkVersion"]))
            insVer.bindLong(10, num(mf["maxSdkVersion"]))
            insVer.bindString(11, arr(mf["nativecode"]).filterIsInstance<String>().joinToString("\n") { it.take(40) })
            insVer.bindString(12, perms.joinToString("\n"))
            insVer.bindString(13, arr(obj(mf["signer"])["sha256"]).filterIsInstance<String>().filter { HEX64.matches(it) }.joinToString("\n"))
            insVer.bindLong(14, if (arr(ver["releaseChannels"]).isNotEmpty()) 1 else 0)
            insVer.bindString(15, obj(ver["antiFeatures"]).keys.filterIsInstance<String>().joinToString("\n") { it.take(60) })
            insVer.bindString(16, plain(text(ver["whatsNew"], 4000)))
            insVer.executeInsert()
            versions++
        }
        if (versions == 0) return false
        val iconMap = m["icon"] as? Map<*, *>
        val icon = fileRef(iconMap?.get("en-US") ?: iconMap?.values?.firstOrNull())
        val signer = str(m["preferredSigner"], 64)
        insApp.clearBindings()
        insApp.bindString(1, pkg)
        insApp.bindString(2, text(m["name"], 120).ifEmpty { pkg })
        insApp.bindString(3, text(m["summary"], 300))
        insApp.bindString(4, plain(text(m["description"], 20000)))
        insApp.bindString(5, icon?.first ?: "")
        insApp.bindString(6, icon?.second ?: "")
        insApp.bindString(7, arr(m["categories"]).filterIsInstance<String>().joinToString("") { "\n" + it.take(60) } + "\n")
        insApp.bindString(8, str(m["license"], 80))
        insApp.bindString(9, str(m["authorName"], 120))
        insApp.bindString(10, link(m["webSite"]))
        insApp.bindString(11, link(m["sourceCode"]))
        insApp.bindString(12, link(m["issueTracker"]))
        insApp.bindString(13, link(m["changelog"]))
        insApp.bindLong(14, num(m["added"]))
        insApp.bindLong(15, num(m["lastUpdated"]))
        insApp.bindString(16, if (HEX64.matches(signer)) signer else "")
        insApp.executeInsert()
        return true
    }

    // ---------- reading the database ----------

    private var db: SQLiteDatabase? = null
    private var status: Status? = null

    /** The catalogue's database, or null when there is none yet. Call with the lock held. */
    private fun open(ctx: Context): SQLiteDatabase? {
        db?.let { return it }
        val f = dbFile(ctx)
        if (!f.exists()) return null
        return try {
            val d = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
            val format = d.rawQuery("SELECT value FROM meta WHERE key = 'format'", null).use { if (it.moveToFirst()) it.getString(0) else null }
            if (format != FORMAT.toString()) { d.close(); null } else d.also { db = it }
        } catch (_: Exception) { null }
    }

    /** What is known about the catalogue on the phone; null when it hasn't been downloaded. */
    fun status(ctx: Context): Status? = synchronized(this) {
        status?.let { return it }
        val d = open(ctx) ?: return null
        try {
            fun meta(key: String) = d.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0).toLongOrNull() ?: 0 else 0 }
            val cats = d.rawQuery("SELECT id, name FROM categories ORDER BY name COLLATE NOCASE", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
            }
            val anti = d.rawQuery("SELECT id, name, description FROM antifeatures", null).use { c ->
                buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1) to c.getString(2)) }
            }
            Status(meta("timestamp"), prefs(ctx).getLong("fetchedAt", 0), meta("apps").toInt(), cats, anti).also { status = it }
        } catch (_: Exception) { null }
    }

    private const val APP_COLS = "pkg, name, summary, icon_name, icon_sha, categories, license, author, website, source, issues, changelog, added, updated, signer"
    private fun lines(s: String) = s.split("\n").filter { it.isNotEmpty() }

    private fun readApp(c: Cursor, description: String, versions: List<Version>) = App(
        c.getString(0), c.getString(1), c.getString(2), description, c.getString(3), c.getString(4), lines(c.getString(5)),
        c.getString(6), c.getString(7), c.getString(8), c.getString(9), c.getString(10), c.getString(11),
        c.getLong(12), c.getLong(13), c.getString(14), versions)

    /** Every version of [pkgs], newest first. */
    private fun versionsOf(d: SQLiteDatabase, pkgs: List<String>): Map<String, List<Version>> {
        val out = HashMap<String, MutableList<Version>>()
        for (chunk in pkgs.chunked(400)) {
            val marks = chunk.joinToString(",") { "?" }
            d.rawQuery("SELECT pkg, code, name, file, sha256, size, added, min_sdk, target_sdk, max_sdk, abis, perms, signers, beta, anti, whats_new " +
                "FROM versions WHERE pkg IN ($marks) ORDER BY code DESC", chunk.toTypedArray()).use { c ->
                while (c.moveToNext()) out.getOrPut(c.getString(0)) { mutableListOf() } += Version(
                    c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getLong(5), c.getLong(6),
                    c.getInt(7), c.getInt(8), c.getInt(9), lines(c.getString(10)), lines(c.getString(11)), lines(c.getString(12)),
                    c.getInt(13) != 0, lines(c.getString(14)), c.getString(15))
            }
        }
        return out
    }

    private fun like(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** A page of the catalogue: apps whose name, summary, package or description holds every word of [q]. */
    fun search(ctx: Context, q: String, category: String, sort: Sort, offset: Int, limit: Int): Page {
        val apps = ArrayList<App>()
        var total = 0
        synchronized(this) {
            val d = open(ctx) ?: throw Problem("Download the F-Droid catalogue first.")
            val where = ArrayList<String>()
            val args = ArrayList<String>()
            if (category.isNotEmpty()) { where += "categories LIKE ? ESCAPE '\\'"; args += "%\n${like(category)}\n%" }
            val words = q.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(6)
            for (w in words) {
                where += "(name LIKE ? ESCAPE '\\' OR summary LIKE ? ESCAPE '\\' OR pkg LIKE ? ESCAPE '\\' OR description LIKE ? ESCAPE '\\')"
                repeat(4) { args += "%${like(w)}%" }
            }
            val cond = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
            total = d.rawQuery("SELECT count(*) FROM apps$cond", args.toTypedArray()).use { it.moveToFirst(); it.getInt(0) }
            val by = when (sort) { Sort.NAME -> "name COLLATE NOCASE"; Sort.ADDED -> "added DESC"; Sort.UPDATED -> "updated DESC" }
            var order = by
            if (words.isNotEmpty() && sort != Sort.NAME) {
                // A hit in the name counts most.
                val score = words.joinToString(" + ") {
                    "(CASE WHEN lower(name) = ? THEN 8 WHEN name LIKE ? ESCAPE '\\' THEN 5 WHEN name LIKE ? ESCAPE '\\' THEN 4 " +
                        "WHEN summary LIKE ? ESCAPE '\\' OR pkg LIKE ? ESCAPE '\\' THEN 2 ELSE 1 END)"
                }
                for (w in words) { args += w; args += "${like(w)}%"; repeat(3) { args += "%${like(w)}%" } }
                order = "($score) DESC, $by"
            }
            d.rawQuery("SELECT $APP_COLS FROM apps$cond ORDER BY $order LIMIT ${limit.coerceIn(1, 200)} OFFSET ${offset.coerceAtLeast(0)}", args.toTypedArray()).use { c ->
                while (c.moveToNext()) apps += readApp(c, "", emptyList())
            }
            val versions = versionsOf(d, apps.map { it.pkg })
            for (i in apps.indices) apps[i] = withVersions(apps[i], versions[apps[i].pkg].orEmpty())
        }
        return Page(total, apps.map { item(ctx, it) })
    }

    private fun withVersions(a: App, v: List<Version>) = App(a.pkg, a.name, a.summary, a.description, a.iconName, a.iconSha, a.categories,
        a.license, a.author, a.webSite, a.sourceCode, a.issueTracker, a.changelog, a.added, a.lastUpdated, a.preferredSigner, v)

    /** The apps on this phone that F-Droid has too, those with an update first. */
    fun onPhone(ctx: Context): List<Item> {
        val pkgs = ctx.packageManager.getInstalledPackages(0).map { it.packageName }
        val apps = ArrayList<App>()
        synchronized(this) {
            val d = open(ctx) ?: throw Problem("Download the F-Droid catalogue first.")
            for (chunk in pkgs.chunked(400)) {
                d.rawQuery("SELECT $APP_COLS FROM apps WHERE pkg IN (${chunk.joinToString(",") { "?" }})", chunk.toTypedArray()).use { c ->
                    while (c.moveToNext()) apps += readApp(c, "", emptyList())
                }
            }
            val versions = versionsOf(d, apps.map { it.pkg })
            for (i in apps.indices) apps[i] = withVersions(apps[i], versions[apps[i].pkg].orEmpty())
        }
        return apps.map { item(ctx, it) }.filter { it.installed != null }
            .sortedWith(compareByDescending<Item> { it.canUpdate }.thenBy { it.app.name.lowercase() })
    }

    /** Everything about one app, or null when F-Droid doesn't have it. */
    fun details(ctx: Context, pkg: String): Item? {
        val app = synchronized(this) {
            val d = open(ctx) ?: throw Problem("Download the F-Droid catalogue first.")
            d.rawQuery("SELECT $APP_COLS, description FROM apps WHERE pkg = ?", arrayOf(pkg)).use { c ->
                if (!c.moveToFirst()) return null
                readApp(c, c.getString(15), versionsOf(d, listOf(pkg))[pkg].orEmpty())
            }
        }
        return item(ctx, app)
    }

    // ---------- which version fits this phone ----------

    private val ANDROID = mapOf(21 to "5", 22 to "5.1", 23 to "6", 24 to "7", 25 to "7.1", 26 to "8", 27 to "8.1", 28 to "9", 29 to "10",
        30 to "11", 31 to "12", 32 to "12L", 33 to "13", 34 to "14", 35 to "15", 36 to "16")
    fun androidName(sdk: Int) = ANDROID[sdk] ?: if (sdk < 21) "4" else "(API $sdk)"

    /** Why [v] can't run on this phone, or "" when it can. */
    fun fits(v: Version): String {
        val sdk = Build.VERSION.SDK_INT
        if (v.minSdk > sdk) return "Needs Android ${androidName(v.minSdk)} or newer"
        if (v.maxSdk in 1 until sdk) return "Made for older Android versions"
        if (v.abis.isNotEmpty() && v.abis.none { it in Build.SUPPORTED_ABIS }) return "Not made for this phone's processor"
        return ""
    }

    /**
     * The version to offer: the newest stable one that fits. For an app that is already on the
     * phone, one signed with the same key (Android refuses any other); otherwise one signed with
     * the key F-Droid prefers for the app.
     */
    private fun suggest(a: App, inst: Installed?): Version? {
        val ok = a.versions.filter { fits(it).isEmpty() }
        if (inst != null && inst.signers.isNotEmpty()) {
            val same = ok.filter { v -> v.signers.isEmpty() || v.signers.any(inst.signers::contains) }
            (same.firstOrNull { !it.beta } ?: same.firstOrNull())?.let { return it }
        }
        fun mine(v: Version) = a.preferredSigner.isEmpty() || v.signers.isEmpty() || a.preferredSigner in v.signers
        return ok.firstOrNull { !it.beta && mine(it) } ?: ok.firstOrNull { !it.beta } ?: ok.firstOrNull()
    }

    private fun item(ctx: Context, a: App): Item {
        val inst = installed(ctx, a.pkg)
        val v = suggest(a, inst)
        return Item(a, inst, v, if (v == null) a.versions.firstOrNull()?.let(::fits).orEmpty() else "")
    }

    /** The copy of [pkg] on this phone, or null. */
    @Suppress("DEPRECATION")
    fun installed(ctx: Context, pkg: String): Installed? = try {
        val pm = ctx.packageManager
        val pi = pm.getPackageInfo(pkg, if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        val certs = (if (Build.VERSION.SDK_INT >= 28) pi.signingInfo?.apkContentsSigners else pi.signatures)?.map { it.toByteArray() }.orEmpty()
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        Installed(code, pi.versionName ?: code.toString(), certs.map { hex(sha256().digest(it)) }.toSet())
    } catch (_: Exception) { null }

    // ---------- icons ----------

    private val icons = LruCache<String, Bitmap>(150)

    fun cachedIcon(a: App): Bitmap? = if (a.iconSha.isEmpty()) null else icons.get(a.iconSha)

    /** An app's icon, checked against the index and kept on the phone. Null when it has none or it can't be fetched. Blocks. */
    fun icon(ctx: Context, a: App): Bitmap? {
        if (a.iconSha.isEmpty() || !Regex("\\.(png|jpe?g|webp)$", RegexOption.IGNORE_CASE).containsMatchIn(a.iconName)) return null
        icons.get(a.iconSha)?.let { return it }
        return try {
            val f = File(iconDir(ctx), a.iconSha)
            if (!f.exists()) {
                val part = File(iconDir(ctx), a.iconSha + "." + Thread.currentThread().id + ".part")
                val (sha, _) = download(a.iconName, part, MAX_ICON, null, "The icon")
                if (sha != a.iconSha) { part.delete(); return null }
                if (!part.renameTo(f)) part.delete()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 144 && bounds.outHeight / (sample * 2) >= 144) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.also { icons.put(a.iconSha, it) }
        } catch (_: Exception) { null }
    }

    // ---------- an app's APK ----------

    private const val ACTION_STATUS = "dev.andromon.companion.FDROID_INSTALL"
    private const val EXTRA_PKG = "fdroid_pkg"

    /**
     * Downloads [v] of [a], checks it against the catalogue and hands it to Android's installer.
     * Ends with [Event.Confirm] (Android asks the person), then [Event.InstallDone]; or [Event.Failed].
     */
    fun install(ctx: Context, a: App, v: Version): Boolean {
        val app = ctx.applicationContext
        return start(Job(a.pkg, a.name)) { j ->
            j.total = v.size
            val folder = File(tmpDir(app), "apk").apply { mkdirs() }
            val apk = File(folder, "${a.pkg}_${v.code}.apk")
            folder.listFiles()?.forEach { if (it != apk) it.delete() } // one download is kept at a time
            if (apk.exists() && apk.length() == v.size) {
                j.phase = Phase.CHECK
                j.done = v.size
                emit(Event.Progress)
                if (fileSha(apk) != v.sha256) apk.delete()
            } else apk.delete()
            if (!apk.exists()) {
                j.phase = Phase.DOWNLOAD
                val part = File(folder, apk.name + ".part")
                val (sha, size) = download(v.file, part, v.size + 1, j, a.name)
                if (size != v.size || sha != v.sha256) {
                    part.delete()
                    throw Problem("The download of ${a.name} doesn't match F-Droid's catalogue. Nothing was kept.")
                }
                if (!part.renameTo(apk)) { part.delete(); throw Problem("Couldn't save the download. Check the free space on the phone.") }
            }
            if (j.cancelled.get()) throw IOException("cancelled")
            j.phase = Phase.INSTALL
            emit(Event.Progress)
            commit(app, a.pkg, apk)
        }
    }

    /** Copies [apk] into an installer session and commits it; the answer comes to [FdroidInstallReceiver]. */
    private fun commit(ctx: Context, pkg: String, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(pkg)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
        }
        val id = try { installer.createSession(params) } catch (e: Exception) {
            throw Problem("Android couldn't start the install. Check the free space on the phone.")
        }
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 256 * 1024) }
                    session.fsync(out)
                }
                val intent = Intent(ctx, FdroidInstallReceiver::class.java).setAction(ACTION_STATUS).putExtra(EXTRA_PKG, pkg)
                // Mutable: Android adds the result to the intent.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                installing = pkg to id
                session.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
            }
        } catch (e: Exception) {
            installing = null
            runCatching { installer.abandonSession(id) }
            throw if (e is Problem) e else Problem("Android couldn't read the download. Check the free space on the phone.")
        }
    }

    /** Forgets an install Android no longer knows about (e.g. the confirmation was swiped away). True when one was dropped. */
    fun dropStaleInstall(ctx: Context): Boolean {
        val (_, id) = installing ?: return false
        if (ctx.packageManager.packageInstaller.getSessionInfo(id) != null) return false
        installing = null
        return true
    }

    /** Android's answer to [commit]. */
    fun onInstallStatus(ctx: Context, intent: Intent) {
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                val l = listener
                if (l != null) l(Event.Confirm(pkg, confirm))
                else runCatching { ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                installing = null
                File(tmpDir(ctx), "apk").deleteRecursively()
                emit(Event.InstallDone(pkg, true, ""))
            }
            else -> {
                installing = null
                emit(Event.InstallDone(pkg, false, when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "The install was cancelled."
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "The copy on this phone is signed with a different key, or is newer. Uninstall it first, then install F-Droid's."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "There isn't enough free space on the phone."
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This app isn't made for this phone."
                    PackageInstaller.STATUS_FAILURE_INVALID -> "Android couldn't read the app's file."
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "The phone's settings don't allow this install."
                    else -> "Android couldn't install the app."
                }))
            }
        }
    }
}

/** Receives Android's answer to an install started by [Fdroid]. Not exported: only the system's own PendingIntent reaches it. */
class FdroidInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Fdroid.onInstallStatus(context.applicationContext, intent)
}
