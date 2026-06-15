package com.example.gallery.app.viewmodel


import androidx.lifecycle.*
import androidx.work.WorkManager
import com.example.gallery.app.data.db.entities.ClusterEntity
import com.example.gallery.app.data.db.entities.MediaItemEntity
import com.example.gallery.app.data.repository.ClusterRepository
import com.example.gallery.app.data.repository.DeletionRepository
import com.example.gallery.app.data.repository.MediaRepository
import com.example.gallery.app.worker.AiProcessingWorker
import com.example.gallery.app.worker.MediaScanWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─────────────────────────────────────────────────────────
// GalleryViewModel — Tab 1: media grid
// ─────────────────────────────────────────────────────────
@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val mediaRepository: MediaRepository
) : ViewModel() {

    val allMedia: LiveData<List<MediaItemEntity>> =
        mediaRepository.getAllMedia().asLiveData()

    val totalCount: LiveData<Int> = mediaRepository.getTotalCount()

    val totalSize: LiveData<Long> = mediaRepository.getTotalSize()

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    fun triggerScan(workManager: WorkManager) {
        _scanState.value = ScanState.Scanning
        val request = MediaScanWorker.buildRequest()
        workManager.enqueue(request)
        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(request.id).collect { info ->
                if (info?.state?.isFinished == true) {
                    _scanState.value = if (info.state.name == "SUCCEEDED")
                        ScanState.Done(info.outputData.getInt(MediaScanWorker.KEY_SCANNED_COUNT, 0))
                    else
                        ScanState.Error("Scan failed")
                }
            }
        }
    }

    sealed class ScanState {
        object Idle : ScanState()
        object Scanning : ScanState()
        data class Done(val count: Int) : ScanState()
        data class Error(val message: String) : ScanState()
    }
}

// ─────────────────────────────────────────────────────────
// OptimizeViewModel — Tab 2: smart cleanup workspace
// ─────────────────────────────────────────────────────────
@HiltViewModel
class OptimizeViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val clusterRepository: ClusterRepository,
    private val deletionRepository: DeletionRepository
) : ViewModel() {

    val allClusters: LiveData<List<ClusterEntity>> =
        clusterRepository.getAllClusters().asLiveData()

    val blurryImages: LiveData<List<MediaItemEntity>> =
        mediaRepository.getBlurryImages().asLiveData()

    val recycleBinCount: LiveData<Int> = deletionRepository.getRecycleBinCount()

    val recycleBinSize: LiveData<Long> = deletionRepository.getRecycleBinSize()

    private val _selectedUris = MutableStateFlow<Set<String>>(emptySet())
    val selectedUris: StateFlow<Set<String>> = _selectedUris.asStateFlow()

    private val _deleteState = MutableStateFlow<DeleteState>(DeleteState.Idle)
    val deleteState: StateFlow<DeleteState> = _deleteState.asStateFlow()

    private val _reclaimableSize = MutableLiveData<Long>()
    val reclaimableSize: LiveData<Long> = _reclaimableSize

    init {
        loadReclaimableSize()
    }

    private fun loadReclaimableSize() {
        viewModelScope.launch {
            _reclaimableSize.value = mediaRepository.getReclaimableSize() ?: 0L
        }
    }

    fun toggleSelection(uri: String) {
        _selectedUris.update { current ->
            if (uri in current) current - uri else current + uri
        }
    }

    fun selectAll(uris: List<String>) {
        _selectedUris.value = uris.toSet()
    }

    fun clearSelection() {
        _selectedUris.value = emptySet()
    }

    fun selectBlurryImages(items: List<MediaItemEntity>) {
        _selectedUris.value = items.map { it.uri }.toSet()
    }

    fun selectDuplicates(clusters: List<ClusterEntity>, allMedia: List<MediaItemEntity>) {
        // Select all non-best-shot images from clusters with >1 member
        val toDelete = allMedia.filter { item ->
            !item.isBestShot && item.clusterId != null
        }.map { it.uri }.toSet()
        _selectedUris.value = toDelete
    }

    /**
     * Moves selected images to the recycle bin.
     * Emits a UndoReady state so the UI can show a 5-second undo snackbar.
     */
    fun moveSelectedToRecycleBin(allMedia: List<MediaItemEntity>) {
        val uris = _selectedUris.value
        if (uris.isEmpty()) return

        viewModelScope.launch {
            _deleteState.value = DeleteState.Moving
            val items = allMedia.filter { it.uri in uris }
            deletionRepository.moveToRecycleBin(items)
            _deleteState.value = DeleteState.UndoReady(uris.toList())
            clearSelection()
        }
    }

    fun undoRecycleBin(uris: List<String>) {
        viewModelScope.launch {
            deletionRepository.restoreFromRecycleBin(uris)
            _deleteState.value = DeleteState.Idle
        }
    }

    fun confirmUndoExpired() {
        _deleteState.value = DeleteState.Idle
    }

    sealed class DeleteState {
        object Idle : DeleteState()
        object Moving : DeleteState()
        data class UndoReady(val uris: List<String>) : DeleteState()
        object Deleted : DeleteState()
    }
}

// ─────────────────────────────────────────────────────────
// AIViewModel — manages AI processing pipeline state
// ─────────────────────────────────────────────────────────
@HiltViewModel
class AIViewModel @Inject constructor(
    private val clusterRepository: ClusterRepository
) : ViewModel() {

    private val _processingState = MutableStateFlow<AiState>(AiState.Idle)
    val processingState: StateFlow<AiState> = _processingState.asStateFlow()

    fun startProcessing(workManager: WorkManager) {
        _processingState.value = AiState.Running(0, "Starting…")
        val request = AiProcessingWorker.buildRequest()
        workManager.enqueue(request)

        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(request.id).collect { info ->
                info?.progress?.let { data ->
                    val status = data.getString("status") ?: "Processing…"
                    val processed = data.getInt("processed", 0)
                    _processingState.value = AiState.Running(processed, status)
                }
                if (info?.state?.isFinished == true) {
                    _processingState.value = if (info.state.name == "SUCCEEDED") {
                        val clusters = info.outputData.getInt("clusters", 0)
                        AiState.Done(clusters)
                    } else {
                        AiState.Error(info.outputData.getString("error") ?: "Unknown error")
                    }
                }
            }
        }
    }

    sealed class AiState {
        object Idle : AiState()
        data class Running(val processed: Int, val status: String) : AiState()
        data class Done(val clusters: Int) : AiState()
        data class Error(val message: String) : AiState()
    }
}

// ─────────────────────────────────────────────────────────
// ClusterDetailViewModel — cluster detail screen
// ─────────────────────────────────────────────────────────
@HiltViewModel
class ClusterDetailViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val clusterRepository: ClusterRepository,
    private val deletionRepository: DeletionRepository
) : ViewModel() {

    private val _clusterId = MutableStateFlow<Int?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val clusterMembers: LiveData<List<MediaItemEntity>> = _clusterId
        .filterNotNull()
        .flatMapLatest { id -> mediaRepository.getMediaByCluster(id) }
        .asLiveData()

    fun loadCluster(id: Int) {
        _clusterId.value = id
    }
}
