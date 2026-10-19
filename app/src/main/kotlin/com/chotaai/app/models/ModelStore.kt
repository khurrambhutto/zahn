package com.chotaai.app.models

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.chotaai.app.data.ModelDao
import com.chotaai.app.data.ModelEntity
import com.chotaai.app.data.SettingsRepository
import com.chotaai.llm.EngineType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

data class Transfer(
    val fileName: String,
    val bytes: Long,
    val totalBytes: Long?,
    val error: String? = null,
) {
    val progress: Float? get() = totalBytes?.takeIf { it > 0 }?.let { bytes.toFloat() / it }
}

/**
 * Model files live in the app's private storage (`files/models/`), with metadata in Room.
 * Downloads run in the app scope so they continue while you switch screens, and resume from a
 * `.part` file if interrupted.
 */
class ModelStore(
    private val context: Context,
    private val dao: ModelDao,
    private val settings: SettingsRepository,
    private val appScope: CoroutineScope,
) {
    val dir = File(context.filesDir, "models").apply { mkdirs() }
    val models: Flow<List<ModelEntity>> = dao.observeAll()

    private val _transfers = MutableStateFlow<Map<String, Transfer>>(emptyMap())
    val transfers: StateFlow<Map<String, Transfer>> = _transfers.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()

    /** Starts a download; accepts Hugging Face `resolve` or `blob` links. Returns an error message or null. */
    fun download(rawUrl: String, displayName: String? = null): String? {
        val url = normalizeUrl(rawUrl) ?: return "Not a valid URL"
        val fileName = url.substringAfterLast('/').substringBefore('?')
        if (EngineType.forFileName(fileName) == null) return "Only .gguf and .litertlm files are supported"
        if (jobs[fileName]?.isActive == true) return null

        jobs[fileName] = appScope.launch(Dispatchers.IO) {
            setTransfer(Transfer(fileName, 0, null))
            try {
                val target = File(dir, fileName)
                fetch(url, File(dir, "$fileName.part"), target, fileName)
                register(fileName, displayName ?: prettyName(fileName), target.length(), url)
                _transfers.update { it - fileName }
            } catch (e: CancellationException) {
                _transfers.update { it - fileName }
                throw e
            } catch (e: Exception) {
                val current = _transfers.value[fileName] ?: Transfer(fileName, 0, null)
                setTransfer(current.copy(error = e.message ?: "Download failed"))
            }
        }
        return null
    }

    fun cancel(fileName: String) {
        jobs.remove(fileName)?.cancel()
        File(dir, "$fileName.part").delete()
        _transfers.update { it - fileName }
    }

    fun dismissError(fileName: String) = _transfers.update { it - fileName }

    /** Copies a model picked with the system file picker into app storage. */
    fun import(uri: Uri): String? {
        val fileName = queryName(uri) ?: return "Couldn't read the file name"
        if (EngineType.forFileName(fileName) == null) return "Only .gguf and .litertlm files are supported"
        jobs[fileName] = appScope.launch(Dispatchers.IO) {
            val total = querySize(uri)
            setTransfer(Transfer(fileName, 0, total))
            try {
                val part = File(dir, "$fileName.part")
                val input = context.contentResolver.openInputStream(uri) ?: error("Couldn't open the file")
                input.use { copy(it, FileOutputStream(part), fileName, 0, total) }
                val target = File(dir, fileName)
                check(part.renameTo(target)) { "Couldn't save the file" }
                register(fileName, prettyName(fileName), target.length(), null)
                _transfers.update { it - fileName }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setTransfer(Transfer(fileName, 0, total, e.message ?: "Import failed"))
            }
        }
        return null
    }

    suspend fun delete(model: ModelEntity) = withContext(Dispatchers.IO) {
        File(dir, model.fileName).delete()
        dao.delete(model)
        if (settings.current().activeModel == model.fileName) settings.setActiveModel(null)
    }

    private suspend fun register(fileName: String, displayName: String, size: Long, url: String?) {
        val engine = EngineType.forFileName(fileName)!!
        dao.upsert(ModelEntity(fileName, displayName, engine.name, size, url, System.currentTimeMillis()))
    }

    private suspend fun fetch(url: String, part: File, target: File, fileName: String) {
        val token = settings.current().hfToken
        val resumeFrom = if (part.exists()) part.length() else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            if (token.isNotEmpty() && url.contains("huggingface.co")) setRequestProperty("Authorization", "Bearer $token")
            if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        try {
            when (val code = conn.responseCode) {
                200, 206 -> Unit
                401, 403 -> error("Access denied ($code). Gated model? Add a Hugging Face token in Models → Settings.")
                404 -> error("File not found (404)")
                else -> error("Server returned $code")
            }
            val append = conn.responseCode == 206
            val offset = if (append) resumeFrom else 0L
            val length = conn.contentLengthLong.takeIf { it > 0 }
            conn.inputStream.use { input ->
                copy(input, FileOutputStream(part, append), fileName, offset, length?.plus(offset))
            }
            check(part.renameTo(target)) { "Couldn't save the file" }
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun copy(input: InputStream, output: FileOutputStream, fileName: String, start: Long, total: Long?) {
        output.use { out ->
            val buffer = ByteArray(1 shl 16)
            var written = start
            var lastReport = 0L
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                written += n
                if (written - lastReport > 1_000_000) {
                    lastReport = written
                    setTransfer(Transfer(fileName, written, total))
                }
            }
        }
    }

    private fun setTransfer(t: Transfer) = _transfers.update { it + (t.fileName to t) }

    private fun queryName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun querySize(uri: Uri): Long? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    companion object {
        fun normalizeUrl(raw: String): String? {
            var url = raw.trim()
            if (url.isEmpty()) return null
            if (url.startsWith("hf.co/") || url.startsWith("huggingface.co/")) url = "https://$url"
            url = url.replace("://hf.co/", "://huggingface.co/")
            if (!url.startsWith("https://") && !url.startsWith("http://")) return null
            // The page link (/blob/) shows HTML; the file itself is at /resolve/.
            if (url.contains("huggingface.co/")) url = url.replace("/blob/", "/resolve/")
            return url
        }

        fun prettyName(fileName: String): String = fileName.substringBeforeLast('.')
    }
}
