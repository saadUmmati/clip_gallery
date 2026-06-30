package com.example.gallery.app.viewmodel


import androidx.lifecycle.*
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.gallery.app.ai.SemanticSearchEngine
import com.example.gallery.app.ai.TextEncoder
import com.example.gallery.app.ai.TimelineEngine
import com.example.gallery.app.data.db.entities.ClusterEntity
import com.example.gallery.app.data.db.entities.MediaItemEntity
import com.example.gallery.app.data.repository.ClusterRepository
import com.example.gallery.app.data.repository.DeletionRepository
import com.example.gallery.app.data.repository.MediaRepository
import com.example.gallery.app.data.repository.VaultRepository
import com.example.gallery.app.worker.AiProcessingWorker
import com.example.gallery.app.worker.MediaScanWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

// ─────────────────────────────────────────────────────────\\
//          GalleryViewModel — Tab 1: media grid
// ─────────────────────────────────────────────────────────\\
@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val clusterRepository: ClusterRepository,
    private val textEncoder: TextEncoder,
    private val searchEngine: SemanticSearchEngine,
    private val timelineEngine: TimelineEngine
) : ViewModel() {

    private val _searchQuery = MutableStateFlow<String>("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchMode = MutableStateFlow(SearchMode.TEXT)
    val searchMode: StateFlow<SearchMode> = _searchMode.asStateFlow()

    private val _semanticResults = MutableStateFlow<List<String>>(emptyList())
    val semanticResults: StateFlow<List<String>> = _semanticResults.asStateFlow()

    private val _searchStatus = MutableStateFlow<String>("")
    val searchStatus: StateFlow<String> = _searchStatus.asStateFlow()

    /**
     * Paging data stream for the main gallery grid.
     * Automatically invalidated when Room database changes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val pagedMedia: Flow<PagingData<MediaItemEntity>> = _searchQuery
        .flatMapLatest { query ->
            if (query.isBlank()) {
                mediaRepository.getAllMediaPaged()
            } else {
                mediaRepository.searchMediaPaged(query)
            }
        }.cachedIn(viewModelScope)

    // Keep allMedia LiveData for timeline and viewer (needs full list in memory)
    @OptIn(ExperimentalCoroutinesApi::class)
    val allMedia: LiveData<List<MediaItemEntity>> = _searchQuery
        .flatMapLatest { query ->
            if (query.isBlank()) {
                _semanticResults.value = emptyList()
                mediaRepository.getAllMedia()
            } else {
                mediaRepository.getAllMedia()
            }
        }.asLiveData()

    val totalCount: LiveData<Int> = mediaRepository.getTotalCount()

    val totalSize: LiveData<Long> = mediaRepository.getTotalSize()

    // ── Multi-selection state for gallery grid ──
    private val _selectedUris = MutableStateFlow<Set<String>>(emptySet())
    val selectedUris: StateFlow<Set<String>> = _selectedUris.asStateFlow()

    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()

    fun enterSelectionMode(initialUri: String) {
        _selectionMode.value = true
        _selectedUris.value = setOf(initialUri)
    }

    fun toggleSelection(uri: String) {
        val current = _selectedUris.value.toMutableSet()
        if (current.contains(uri)) {
            current.remove(uri)
        } else {
            current.add(uri)
        }
        _selectedUris.value = current
        if (current.isEmpty()) {
            _selectionMode.value = false
        }
    }

    fun clearSelection() {
        _selectedUris.value = emptySet()
        _selectionMode.value = false
    }

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    fun triggerScan(workManager: WorkManager) {
        _scanState.value = ScanState.Scanning
        val request = MediaScanWorker.buildRequest()
        workManager.enqueue(request)
        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(request.id)
                .takeWhile { it?.state?.isFinished != true }
                .collect { info ->
                    if (info != null && info.state.isFinished) {
                        _scanState.value = if (info.state == WorkInfo.State.SUCCEEDED) {
                            ScanState.Done(info.outputData.getInt(MediaScanWorker.KEY_SCANNED_COUNT, 0))
                        } else {
                            val errorMsg = info.outputData.getString("error") ?: "Scan failed"
                            ScanState.Error(errorMsg)
                        }
                    }
                }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        if (query.isNotBlank()) {
            performSemanticSearch(query)
        } else {
            _semanticResults.value = emptyList()
        }
    }

    private fun performSemanticSearch(query: String) {
        viewModelScope.launch {
            _searchStatus.value = "Searching…"
            try {
                val queryEmbedding = withContext(Dispatchers.IO) {
                    textEncoder.embed(query)
                }

                if (queryEmbedding == null) {
                    // Text encoder unavailable — fall back to filename matching
                    _searchStatus.value = ""
                    _semanticResults.value = emptyList()
                    return@launch
                }

                val allEmbeddings = withContext(Dispatchers.IO) {
                    mediaRepository.getAllProcessedEmbeddings()
                }

                val results = withContext(Dispatchers.Default) {
                    searchEngine.searchAll(queryEmbedding, allEmbeddings)
                }

                _semanticResults.value = results.map { it.uri }
                _searchStatus.value = if (results.isEmpty()) "No results" else ""
            } catch (e: Exception) {
                e.printStackTrace()
                _searchStatus.value = "Search failed"
                _semanticResults.value = emptyList()
            }
        }
    }

    enum class SearchMode { TEXT, SEMANTIC }

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

    val allMedia: LiveData<List<MediaItemEntity>> =
        mediaRepository.getAllMedia().asLiveData()

    val blurryImages: LiveData<List<MediaItemEntity>> =
        mediaRepository.getBlurryImages().asLiveData()

    val recycleBinItems: LiveData<List<MediaItemEntity>> =
        mediaRepository.getRecycleBinItems().asLiveData()

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
            _reclaimableSize.value = mediaRepository.getReclaimableSize()
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

    fun selectDuplicates(@Suppress("UNUSED_PARAMETER") clusters: List<ClusterEntity>, allMedia: List<MediaItemEntity>) {
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

    suspend fun confirmDeletion(uris: List<String>) {
        deletionRepository.confirmDeletion(uris)
    }

    fun emptyRecycleBin(launcher: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest>) {
        val items = recycleBinItems.value ?: return
        if (items.isEmpty()) return
        viewModelScope.launch {
            deletionRepository.requestPermanentDeletion(items.map { it.uri }, launcher)
        }
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
            workManager.getWorkInfoByIdFlow(request.id)
                .takeWhile { info -> info?.state?.isFinished != true }
                .collect { info ->
                    info?.progress?.let { data ->
                        val status = data.getString("status") ?: "Processing…"
                        val processed = data.getInt("processed", 0)
                        _processingState.value = AiState.Running(processed, status)
                    }
                    if (info?.state?.isFinished == true) {
                        _processingState.value = if (info.state == WorkInfo.State.SUCCEEDED) {
                            val clusters = info.outputData.getInt("clusters", 0)
                            AiState.Done(clusters)
                        } else {
                            AiState.Error(info.outputData.getString("error") ?: "Unknown error")
                        }
                    }
                }
        }
    }

    fun startProcessing(workManager: WorkManager, uris: List<String>) {
        _processingState.value = AiState.Running(0, "Starting…")
        val request = AiProcessingWorker.buildRequest(uris)
        workManager.enqueue(request)

        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(request.id)
                .takeWhile { info -> info?.state?.isFinished != true }
                .collect { info ->
                    info?.progress?.let { data ->
                        val status = data.getString("status") ?: "Processing…"
                        val processed = data.getInt("processed", 0)
                        _processingState.value = AiState.Running(processed, status)
                    }
                    if (info?.state?.isFinished == true) {
                        _processingState.value = if (info.state == WorkInfo.State.SUCCEEDED) {
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

// ─────────────────────────────────────────────────────────
// VaultViewModel — secure vault management
// ─────────────────────────────────────────────────────────
@HiltViewModel
class VaultViewModel @Inject constructor(
    private val vaultRepository: VaultRepository
) : ViewModel() {

    val vaultItems: LiveData<List<MediaItemEntity>> =
        vaultRepository.getAllVaultItems().asLiveData()

    val vaultCount: LiveData<Int> = vaultRepository.getVaultCount()
    val vaultSize: LiveData<Long> = vaultRepository.getVaultSize()

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val _selectedUris = MutableStateFlow<Set<String>>(emptySet())
    val selectedUris: StateFlow<Set<String>> = _selectedUris.asStateFlow()

    fun setUnlocked(unlocked: Boolean) {
        _isUnlocked.value = unlocked
    }

    fun toggleSelection(uri: String) {
        val current = _selectedUris.value.toMutableSet()
        if (current.contains(uri)) current.remove(uri) else current.add(uri)
        _selectedUris.value = current
    }

    fun clearSelection() {
        _selectedUris.value = emptySet()
    }

    fun moveToVault(uris: List<String>) {
        viewModelScope.launch {
            vaultRepository.moveToVault(uris)
            _selectedUris.value = emptySet()
        }
    }

    fun restoreFromVault(uris: List<String>) {
        viewModelScope.launch {
            vaultRepository.restoreFromVault(uris)
            _selectedUris.value = emptySet()
        }
    }

    fun deleteFromVault(uris: List<String>) {
        viewModelScope.launch {
            vaultRepository.deleteFromVault(uris)
            _selectedUris.value = emptySet()
        }
    }
}
