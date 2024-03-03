package com.deniscerri.ytdlnis.database.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.OPEN_READONLY
import android.os.Environment
import android.util.Log
import android.webkit.CookieManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.deniscerri.ytdlnis.BuildConfig
import com.deniscerri.ytdlnis.database.DBManager
import com.deniscerri.ytdlnis.database.models.CookieItem
import com.deniscerri.ytdlnis.database.repository.CookieRepository
import com.deniscerri.ytdlnis.ui.more.WebViewActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.net.URL
import java.util.Date

class CookieViewModel(private val application: Application) : AndroidViewModel(application) {
    private val repository: CookieRepository
    val items: LiveData<List<CookieItem>>

    val cookieHeader =
            "# Netscape HTTP Cookie File\n" +
            "# WebView Generated by the YTDLnis app\n" +
            "# This is a generated file! Do not edit."

    init {
        val dao = DBManager.getInstance(application).cookieDao
        repository = CookieRepository(dao)
        items = repository.items.asLiveData()
    }

    fun getAll(): List<CookieItem> {
        return repository.getAll()
    }

    fun getByURL(url: String) : CookieItem? {
        return repository.getByURL(url)
    }

    suspend fun insert(item: CookieItem) : Long {
        return repository.insert(item)
    }

    fun delete(item: CookieItem) = viewModelScope.launch(Dispatchers.IO) {
        repository.delete(item)
        updateCookiesFile()
    }

    fun deleteAll() = viewModelScope.launch(Dispatchers.IO) {
        repository.deleteAll()
    }

    fun update(item: CookieItem) = viewModelScope.launch(Dispatchers.IO) {
        repository.update(item)
    }

    object CookieObject {
        const val NAME = "name"
        const val VALUE = "value"
        const val SECURE = "is_secure"
        const val EXPIRY = "expires_utc"
        const val HOST = "host_key"
        const val PATH = "path"
    }

    @SuppressLint("SdCardPath")
    fun getCookiesFromDB(url: String) : Result<String> = kotlin.runCatching {
        CookieManager.getInstance().run {
            if (!hasCookies()) throw Exception("There is no cookies in the database!")
            flush()
        }
        val dbPath = File("/data/data/${BuildConfig.APPLICATION_ID}/").walkTopDown().find { it.name == "Cookies" }
            ?: throw Exception("Cookies File not found!")

        val db = SQLiteDatabase.openDatabase(
            dbPath.absolutePath, null, OPEN_READONLY
        )

        val projection = arrayOf(
            CookieObject.HOST,
            CookieObject.EXPIRY,
            CookieObject.PATH,
            CookieObject.NAME,
            CookieObject.VALUE,
            CookieObject.SECURE
        )
        val cookieList = mutableListOf<WebViewActivity.CookieItem>()
        db.query(
            "cookies", projection, null, null, null, null, null
        ).run {
            while (moveToNext()) {
                val expiry = getLong(getColumnIndexOrThrow(CookieObject.EXPIRY))
                val name = getString(getColumnIndexOrThrow(CookieObject.NAME))
                val value = getString(getColumnIndexOrThrow(CookieObject.VALUE))
                val path = getString(getColumnIndexOrThrow(CookieObject.PATH))
                val secure = getLong(getColumnIndexOrThrow(CookieObject.SECURE)) == 1L
                val hostKey = getString(getColumnIndexOrThrow(CookieObject.HOST))


                val host = if (hostKey[0] != '.') ".$hostKey" else hostKey

                val requiredHost = URL(url).host
                if(host.contains(requiredHost)){
                    cookieList.add(
                        WebViewActivity.CookieItem(
                            domain = host,
                            name = name,
                            value = value,
                            path = path,
                            secure = secure,
                            expiry = expiry
                        )
                    )
                }
            }
            close()
        }
        db.close()
        cookieList.fold(StringBuilder("")) { acc, cookie ->
            acc.append(cookie.toNetscapeFormat()).append("\n")
        }.toString()
    }

    fun updateCookiesFile() = viewModelScope.launch(Dispatchers.IO) {
        val cookies = repository.getAll()
        val cookieTXT = StringBuilder(cookieHeader)
        val cookieFile = File(application.cacheDir, "cookies.txt")
        if (cookies.isEmpty()) cookieFile.apply { writeText("") }
        cookies.forEach {
            it.content.lines().forEach {line ->
                if (! cookieTXT.contains(line)) cookieTXT.append(it.content)
            }
        }
        cookieFile.apply { writeText(cookieTXT.toString()) }
    }

    suspend fun importFromClipboard() {
        try{
            val clipboard: ClipboardManager =
                application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            var clip = clipboard.primaryClip!!.getItemAt(0).text
            Log.e("Aaa", clip.toString())

            if (clip.startsWith("# Netscape HTTP Cookie File")){
                clip = clip.removePrefix(cookieHeader)
                val cookie = CookieItem(
                    0,
                    "Cookie Import at [${Date()}]",
                    clip.toString()
                )
                insert(cookie)
                updateCookiesFile()
            }
        }catch (e: Exception){
            e.printStackTrace()
        }
    }

    fun exportToClipboard() = viewModelScope.launch {
        try{
            val clipboard: ClipboardManager =
                application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val cookieFile = File(application.cacheDir, "cookies.txt")
            if (! cookieFile.exists()) updateCookiesFile()
            cookieFile.readText().let {
                clipboard.setText(it)
            }
        }catch (e: Exception){
            e.printStackTrace()
        }
    }

    fun exportToFile(exported: (File?) -> Unit) {
        try{
            val cookieFile = File(application.cacheDir, "cookies.txt")
            if (!cookieFile.exists()) updateCookiesFile()

            val downloads = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath + File.separator + "YTDLnis_Cookies.txt")
            val file = cookieFile.copyTo(downloads, true)
            exported(file)
        }catch (e: Exception){
            e.printStackTrace()
            exported(null)
        }
    }

}