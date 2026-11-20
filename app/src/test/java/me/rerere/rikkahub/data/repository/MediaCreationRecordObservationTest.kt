package me.rerere.rikkahub.data.repository

import android.app.Application
import androidx.paging.PagingSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.mediagen.model.MediaKind
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.nativeRoom
import me.rerere.rikkahub.data.model.MediaCreationOutput
import me.rerere.rikkahub.data.model.MediaCreationRecord
import me.rerere.rikkahub.data.model.MediaCreationStatus
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MediaCreationRecordObservationTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: MediaCreationRepository
    private lateinit var record: MediaCreationRecord

    @Before
    fun setUp() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        database = nativeRoom(app, "media-observe-${Uuid.random()}.db")
        repository = MediaCreationRepository(app, database, database.mediaCreationDao(), JsonInstant)
        record = MediaCreationRecord(
            sessionId = repository.newSession().id,
            providerId = Uuid.random(),
            providerName = "fixture",
            modelId = "fixture",
            kind = MediaKind.IMAGE,
            prompt = "one",
            status = MediaCreationStatus.RUNNING,
        )
        repository.insertRecord(record)
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun visibleRecordReceivesFinalOutputsWithoutLoadingAnotherPage() = runBlocking {
        withTimeout(10_000) {
            val page = database.mediaCreationDao().nodePagingSource(record.sessionId.toString())
                .load(PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = true))
            val snapshot = (page as PagingSource.LoadResult.Page).data.single().record
            val visible = repository.observeRecord(Uuid.parse(snapshot.id)).produceIn(this)
            try {
                assertEquals(MediaCreationStatus.RUNNING, visible.receive()?.status)
                repository.updateRecord(record.id) { it.copy(status = MediaCreationStatus.DOWNLOADING) }
                assertEquals(MediaCreationStatus.DOWNLOADING, visible.receive()?.status)
                val output = MediaCreationOutput("records/result.png", "image/png")
                repository.updateRecord(record.id) { it.copy(status = MediaCreationStatus.SUCCEEDED, outputs = listOf(output)) }
                val ready = visible.receive()
                assertEquals(MediaCreationStatus.SUCCEEDED, ready?.status)
                assertEquals(listOf(output), ready?.outputs)
                assertEquals(MediaCreationStatus.RUNNING.name, snapshot.status)
            } finally {
                visible.cancel()
            }
        }
    }

    @Test
    fun alreadyFailedAndCancelledVersionsRetryInPlaceWhileThePageStillShowsTheirOldStatus() = runBlocking {
        withTimeout(10_000) {
            for (initial in listOf(MediaCreationStatus.FAILED, MediaCreationStatus.CANCELLED)) {
                repository.updateRecord(record.id) { it.copy(status = initial, error = "first attempt") }
                val page = database.mediaCreationDao().nodePagingSource(record.sessionId.toString())
                    .load(PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = true))
                val snapshot = (page as PagingSource.LoadResult.Page).data.single().record
                assertEquals(initial.name, snapshot.status)
                val visible = repository.observeRecord(Uuid.parse(snapshot.id)).produceIn(this)
                try {
                    assertEquals(initial, visible.receive()?.status)
                    repository.updateRecord(record.id) { it.copy(status = MediaCreationStatus.PREPARING, error = null) }
                    assertEquals(MediaCreationStatus.PREPARING, visible.receive()?.status)
                    repository.updateRecord(record.id) { it.copy(status = MediaCreationStatus.RUNNING) }
                    assertEquals(MediaCreationStatus.RUNNING, visible.receive()?.status)
                    if (initial == MediaCreationStatus.FAILED) {
                        repository.updateRecord(record.id) { it.copy(status = MediaCreationStatus.CANCELLED) }
                        assertEquals(MediaCreationStatus.CANCELLED, visible.receive()?.status)
                    } else {
                        repository.updateRecord(record.id) { it.copy(status = MediaCreationStatus.FAILED, error = "offline") }
                        val failed = visible.receive()
                        assertEquals(MediaCreationStatus.FAILED, failed?.status)
                        assertEquals("offline", failed?.error)
                        repository.deleteRecord(record)
                        assertNull(visible.receive())
                    }
                } finally {
                    visible.cancel()
                }
            }
        }
    }

    @Test
    fun completingSiblingVersionDoesNotReplaceTheSelectedVersionsRecord() = runBlocking {
        withTimeout(10_000) {
            val sibling = record.copy(id = Uuid.random(), prompt = "another")
            repository.insertRecord(sibling)
            assertEquals(sibling.id, repository.getSelectedRecordsOfSession(record.sessionId).single().id)
            assertEquals(sibling.id, repository.observeRecord(sibling.id).first()?.id)
            repository.switchVersion(record.nodeId, -1)
            val selected = repository.getSelectedRecordsOfSession(record.sessionId).single()
            val visible = repository.observeRecord(selected.id).produceIn(this)
            try {
                assertEquals(record.id, visible.receive()?.id)
                val otherOutput = MediaCreationOutput("records/other.png", "image/png")
                repository.updateRecord(sibling.id) { it.copy(status = MediaCreationStatus.SUCCEEDED, outputs = listOf(otherOutput)) }
                val selectedOutput = MediaCreationOutput("records/selected.png", "image/png")
                repository.updateRecord(record.id) { it.copy(status = MediaCreationStatus.SUCCEEDED, outputs = listOf(selectedOutput)) }
                val ready = visible.receive()
                assertEquals(record.id, ready?.id)
                assertEquals(listOf(selectedOutput), ready?.outputs)
            } finally {
                visible.cancel()
            }
        }
    }
}
