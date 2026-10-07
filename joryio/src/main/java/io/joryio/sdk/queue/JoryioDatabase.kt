package io.joryio.sdk.queue

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

/**
 * Room database for Joryio SDK - the unsent-event queue.
 *
 * Schemas are exported to `joryio/schemas/` (exportSchema = true) and every
 * version bump ships a real [Migration]. It used to be exportSchema = false
 * with fallbackToDestructiveMigration(), so the v1 -> v2 upgrade (eventId) did
 * not migrate the queue - it DELETED it, silently, on the first launch of the
 * new SDK. Every event a user tracked offline before updating the app was lost.
 */
@Database(
    entities = [EventEntity::class],
    version = 2, // v2 adds EventEntity.eventId (idempotency id)
    exportSchema = true
)
@TypeConverters(EventConverters::class)
internal abstract class JoryioDatabase : RoomDatabase() {

    abstract fun eventDao(): EventDao

    companion object {
        @Volatile
        private var INSTANCE: JoryioDatabase? = null

        /**
         * v1 -> v2: add the NOT NULL `eventId` column. SQLite cannot add a NOT
         * NULL column without a default, and the entity declares none, so the
         * table is rebuilt; each existing row gets a fresh random id (it was
         * never sent with one, so there is nothing to deduplicate against).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `events_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`type` TEXT NOT NULL, `properties` TEXT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, `userId` TEXT, " +
                        "`anonymousId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, " +
                        "`eventId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`retryCount` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO `events_new` (`id`, `type`, `properties`, `timestamp`, `userId`, " +
                        "`anonymousId`, `sessionId`, `eventId`, `createdAt`, `retryCount`) " +
                        "SELECT `id`, `type`, `properties`, `timestamp`, `userId`, `anonymousId`, " +
                        "`sessionId`, lower(hex(randomblob(16))), `createdAt`, `retryCount` FROM `events`"
                )
                db.execSQL("DROP TABLE `events`")
                db.execSQL("ALTER TABLE `events_new` RENAME TO `events`")
            }
        }

        /** File name of the queue for one SDK key. */
        fun fileName(sdkKey: String): String = "joryio_${sdkKey.hashCode()}.db"

        /**
         * Where the queue lives: the app's NO-BACKUP directory.
         *
         * Android Auto Backup copies `databases/` to a new phone. A restored
         * queue would replay the previous device's unsent events under an
         * identity the new install no longer has. noBackupFilesDir is excluded
         * from every backup by the platform, so no app-side backup rule is
         * needed for it. A queue left in `databases/` by an older SDK is moved
         * here on first open.
         */
        fun databaseFile(context: Context, sdkKey: String): File {
            val name = fileName(sdkKey)
            val target = File(context.noBackupFilesDir, name)
            if (!target.exists()) {
                val legacy = context.getDatabasePath(name)
                if (legacy.exists()) {
                    target.parentFile?.mkdirs()
                    for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                        val from = File(legacy.path + suffix)
                        if (from.exists()) from.renameTo(File(target.path + suffix))
                    }
                }
            }
            return target
        }

        fun getInstance(context: Context, sdkKey: String): JoryioDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    JoryioDatabase::class.java,
                    databaseFile(context.applicationContext, sdkKey).absolutePath
                )
                    .addMigrations(MIGRATION_1_2)
                    // Downgrade only (an app rolled back to an older SDK): the
                    // older code cannot read the newer schema. Upgrades always
                    // migrate.
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { INSTANCE = it }
            }
        }

        fun clearInstance() {
            INSTANCE = null
        }
    }
}
