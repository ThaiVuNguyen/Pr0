package com.pr0gramm.app.orm

import android.database.sqlite.SQLiteDatabase
import com.pr0gramm.app.Instant
import com.pr0gramm.app.util.arrayOfStrings
import com.pr0gramm.app.util.logger
import com.pr0gramm.app.util.mapToList

/**
 */
data class BenisRecord(val time: Long, val benis: Int) {
    companion object {
        private val logger = logger("BenisRecord")

        fun findValuesLaterThan(db: SQLiteDatabase, ownerId: Int, time: Instant): List<BenisRecord> {
            return db
                    .query("benis_record", arrayOf("time", "benis"), "time >= ? and owner_id=?", arrayOfStrings(time.millis, ownerId), null, null, "time ASC")
                    .mapToList { BenisRecord(getLong(0), getInt(1)) }
        }

        fun storeValue(db: SQLiteDatabase, ownerId: Int, benis: Int) {
            db.execSQL("INSERT INTO benis_record (owner_id, time, benis) VALUES (?, ?, ?)",
                    arrayOfStrings(ownerId, System.currentTimeMillis(), benis))
        }

        fun prepareDatabase(db: SQLiteDatabase) {
            logger.info { "setting up benis_record table" }
            db.execSQL("CREATE TABLE IF NOT EXISTS benis_record (id INTEGER PRIMARY KEY AUTOINCREMENT, benis INTEGER, time INTEGER, owner_id INTEGER)")

            logger.info { "create index on benis_record(time) if it does not exist" }
            db.execSQL("CREATE INDEX IF NOT EXISTS benis_record__time ON benis_record(time)")
        }
    }
}
