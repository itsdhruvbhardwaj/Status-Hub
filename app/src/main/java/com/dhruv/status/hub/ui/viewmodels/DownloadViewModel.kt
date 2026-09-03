package com.dhruv.status.hub.ui.viewmodels

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dhruv.status.hub.data.DownloadDatabase
import com.dhruv.status.hub.data.DownloadRecord
import com.dhruv.status.hub.utils.DownloadManager
import com.dhruv.status.hub.utils.NetworkDownloadUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for managing the Download from Link feature and History.
 * Restored to original simple download flow.
 */
class DownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val db = DownloadDatabase.getDatabase(application)
    private val downloadDao = db.downloadDao()

    private val _downloadState = MutableStateFlow<NetworkDownloadUtils.DownloadState>(NetworkDownloadUtils.DownloadState.Idle)
    val downloadState: StateFlow<NetworkDownloadUtils.DownloadState> = _downloadState

    val allDownloads: StateFlow<List<DownloadRecord>> = downloadDao.getAllRecords()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun analyzeUrl(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            NetworkDownloadUtils.analyzeUrl(url) { state ->
                _downloadState.value = state
            }
        }
    }

    /**
     * Enqueues a download using the native format extracted from the source.
     */
    fun enqueueDownload(
        context: Context,
        info: NetworkDownloadUtils.MediaInfo,
        format: NetworkDownloadUtils.MediaFormat?,
        isAudioOnly: Boolean = false
    ) {
        val extension = format?.extension ?: info.extension
        val mediaType = if (isAudioOnly || format?.isAudio == true) "audio" else info.mediaType

        val record = DownloadRecord(
            sourceUrl = info.url,
            fileName = info.fileName,
            mediaType = mediaType,
            format = extension,
            quality = format?.quality ?: "Default",
            platform = info.platform,
            status = "QUEUED",
            thumbnailUrl = info.thumbnailUrl,
            downloadUrl = format?.url ?: info.url,
            totalBytes = format?.size ?: -1L
        )

        DownloadManager.enqueue(context, record)
        resetState()
    }

    fun pauseDownload(context: Context, id: Long) = DownloadManager.pause(context, id)
    fun resumeDownload(context: Context, id: Long) = DownloadManager.resume(context, id)
    fun cancelDownload(context: Context, id: Long) = DownloadManager.cancel(context, id)

    fun deleteRecord(record: DownloadRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            downloadDao.deleteRecord(record)
        }
    }

    fun deleteFileAndRecord(context: Context, record: DownloadRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                record.fileUri?.let { uriString ->
                    context.contentResolver.delete(Uri.parse(uriString), null, null)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            downloadDao.deleteRecord(record)
        }
    }

    fun resetState() {
        _downloadState.value = NetworkDownloadUtils.DownloadState.Idle
    }
}
