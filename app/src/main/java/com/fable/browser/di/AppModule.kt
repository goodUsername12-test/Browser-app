package com.fable.browser.di

import android.content.Context
import androidx.room.Room
import com.fable.browser.data.db.BookmarkDao
import com.fable.browser.data.db.DownloadDao
import com.fable.browser.data.db.FableDatabase
import com.fable.browser.data.db.HistoryDao
import com.fable.browser.data.db.SavedTabDao
import com.fable.browser.data.db.SitePermissionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FableDatabase =
        Room.databaseBuilder(context, FableDatabase::class.java, "fable.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideBookmarkDao(db: FableDatabase): BookmarkDao = db.bookmarkDao()
    @Provides fun provideHistoryDao(db: FableDatabase): HistoryDao = db.historyDao()
    @Provides fun provideDownloadDao(db: FableDatabase): DownloadDao = db.downloadDao()
    @Provides fun provideSavedTabDao(db: FableDatabase): SavedTabDao = db.savedTabDao()
    @Provides fun provideSitePermissionDao(db: FableDatabase): SitePermissionDao = db.sitePermissionDao()
}
