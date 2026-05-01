package com.chastechgroup.camix.di

import android.content.Context
import com.chastechgroup.camix.audio.AudioProcessor
import com.chastechgroup.camix.audio.SoundManager
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

    @Provides @Singleton
    fun provideUltraCameraManager(@ApplicationContext ctx: Context) = UltraCameraManager(ctx)

    @Provides @Singleton
    fun provideAudioProcessor(@ApplicationContext ctx: Context) = AudioProcessor(ctx)

    @Provides @Singleton
    fun provideSoundManager(@ApplicationContext ctx: Context) = SoundManager(ctx)
}
