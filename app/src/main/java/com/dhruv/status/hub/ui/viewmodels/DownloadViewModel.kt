package com.dhruv.status.hub.ui.viewmodels

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dhruv.status.hub.data.DownloadDatabase
import com.dhruv.status.hub.data.DownloadRecord
import com.dhruv.status.hub.utils.NetworkDownloadUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for managing the Download from Link feature and History.
 */
class DownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val db = DownloadDatabase.getDatabase(application)
    private val downloadDao = db.downloadDao()

    private val _downloadState = MutableStateFlow<NetworkDownloadUtils.DownloadState>(NetworkDownloadUtils.DownloadState.Idle)
    val downloadState: StateFlow<NetworkDownloadUtils.DownloadState> = _downloadState

    val downloadHistory: StateFlow<List<DownloadRecord>> = downloadDao.getAllRecords()
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

    fun downloadMedia(context: Context, info: NetworkDownloadUtils.MediaInfo, forceDownload: Boolean = false, isAudioOnly: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            NetworkDownloadUtils.downloadMedia(context, info, forceDownload, isAudioOnly) { state ->
                _downloadState.value = state
                if (state is NetworkDownloadUtils.DownloadState.Success) {
                    saveDownloadRecord(info, state.filePath, isAudioOnly)
                }
            }
        }
    }

    private fun saveDownloadRecord(info: NetworkDownloadUtils.MediaInfo, fileUri: String, isAudioOnly: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val record = DownloadRecord(
                sourceUrl = info.url,
                fileUri = fileUri,
                fileName = if (isAudioOnly) info.fileName.substringBeforeLast(".") + ".mp3" else info.fileName,
                mediaType = if (isAudioOnly) "audio" else info.mediaType,
                format = if (isAudioOnly) "mp3" else info.extension,
                fileSize = info.contentLength,
                timestamp = System.currentTimeMillis(),
                platform = info.platform,
                status = "success"
            )
            downloadDao.insertRecord(record)
        }
    }

    fun deleteRecord(record: DownloadRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            downloadDao.deleteRecord(record)
        }
    }

    fun deleteFileAndRecord(context: Context, record: DownloadRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val uri = Uri.parse(record.fileUri)
                context.contentResolver.delete(uri, null, null)
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
