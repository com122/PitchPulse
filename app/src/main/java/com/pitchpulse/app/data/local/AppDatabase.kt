package com.pitchpulse.app.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [PitchEntity::class, MatchEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pitchDao(): PitchDao
    abstract fun matchDao(): MatchDao

    companion object {
        const val DEMO_PITCH_ID = "demo-pitch-7"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "pitchpulse.db")
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        val now = System.currentTimeMillis()
                        val values = ContentValues().apply {
                            put("id", DEMO_PITCH_ID)
                            put("name", "标准 7 人制演示球场")
                            put("pitchType", 7)
                            put("centerLat", 22.543100)
                            put("centerLng", 113.942000)
                            put("lengthMeters", 60f)
                            put("widthMeters", 40f)
                            put("bearingDegrees", 28f)
                            put("createdAt", now)
                            put("updatedAt", now)
                        }
                        db.insert("pitches", SQLiteDatabase.CONFLICT_IGNORE, values)
                    }
                })
                .build()
    }
}
