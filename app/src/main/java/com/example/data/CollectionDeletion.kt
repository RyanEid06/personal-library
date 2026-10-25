package com.example.data

import android.content.Context
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.work.*
import java.util.concurrent.TimeUnit

@Entity(tableName = "pending_collection_deletions", indices = [Index(value = ["collectionId"], unique = true)])
data class PendingCollectionDeletion(
    @PrimaryKey val operationId: String,
    val collectionId: String,
    val name: String,
    val undoUntil: Long,
    val trashedAt: Long
)

@Entity(tableName = "collection_deletion_items", primaryKeys = ["operationId", "itemId"])
data class CollectionDeletionItem(val operationId: String, val itemId: String)

/** Retained until old collection exclusions have safely crossed the preferences boundary. */
@Entity(tableName = "privacy_migration_memberships", primaryKeys = ["itemId", "collectionId"])
data class PrivacyMigrationMembership(val itemId: String, val collectionId: String)

data class CaptureReminder(val time: Long, val exact: Boolean, val dateOnly: Boolean, val localDate: String?)

class CollectionDeletionWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val operation = inputData.getString("operation") ?: return Result.failure()
        return try {
            val repository = LibraryRepository(applicationContext)
            repository.completeOwnerFeedbackMigration()
            if (repository.finalizeCollectionDeletion(operation)) Result.success() else Result.retry()
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
    }

    companion object {
        fun enqueue(context: Context, operation: PendingCollectionDeletion) {
            val work = OneTimeWorkRequestBuilder<CollectionDeletionWorker>()
                .setInputData(workDataOf("operation" to operation.operationId))
                .setInitialDelay((operation.undoUntil - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("delete_collection_${operation.operationId}", ExistingWorkPolicy.KEEP, work)
        }
    }
}
