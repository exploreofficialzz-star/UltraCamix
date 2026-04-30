package com.chastechgroup.camix.di

import android.content.Context
import com.chastechgroup.camix.audio.AudioProcessor
import com.chastechgroup.camix.camera.UltraCameraManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CameraModule {

    @Provides
    @Singleton
    fun provideUltraCameraManager(
        @ApplicationContext context: Context
    ): UltraCameraManager = UltraCameraManager(context)

    @Provides
    @Singleton
    fun provideAudioProcessor(
        @ApplicationContext context: Context
    ): AudioProcessor = AudioProcessor(context)
}
