package com.pitchpulse.app.di

import android.content.Context
import com.pitchpulse.app.data.file.ReferenceFileStore
import com.pitchpulse.app.data.file.TrackFileStore
import com.pitchpulse.app.data.repository.ReferenceRepository
import com.pitchpulse.app.data.local.AppDatabase
import com.pitchpulse.app.data.repository.ImportRepository
import com.pitchpulse.app.data.repository.MatchRepository
import com.pitchpulse.app.data.repository.MockWatchSyncRepository
import com.pitchpulse.app.data.repository.PitchRepository
import com.pitchpulse.app.data.repository.ThumbnailRepository
import com.pitchpulse.app.data.repository.WatchSyncRepository
import com.pitchpulse.app.data.settings.SettingsRepository

/** Manual dependency container (no DI framework needed at this size). */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    val database: AppDatabase = AppDatabase.build(appContext)
    val trackStore = TrackFileStore(appContext.filesDir)
    val settingsRepository = SettingsRepository(appContext)
    val thumbnailRepository = ThumbnailRepository(appContext.filesDir, trackStore)

    val pitchRepository = PitchRepository(database.pitchDao(), database.matchDao(), trackStore, thumbnailRepository)
    val matchRepository = MatchRepository(database.matchDao(), database.pitchDao(), trackStore, thumbnailRepository)

    val watchSyncRepository: WatchSyncRepository = MockWatchSyncRepository(
        trackStore, pitchRepository, matchRepository, thumbnailRepository, settingsRepository,
    )

    val referenceFileStore = ReferenceFileStore(appContext.filesDir)
    val referenceRepository = ReferenceRepository(referenceFileStore)

    val importRepository = ImportRepository(
        appContext.contentResolver, appContext.cacheDir, trackStore, watchSyncRepository,
        pitchRepository, settingsRepository, referenceRepository,
    )
}
