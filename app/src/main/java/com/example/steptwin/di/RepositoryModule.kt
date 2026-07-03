package com.example.steptwin.di

import com.example.steptwin.data.repository.RoutePreviewRepositoryImpl
import com.example.steptwin.data.repository.TugRepositoryImpl
import com.example.steptwin.data.repository.VoiceAssistantRepositoryImpl
import com.example.steptwin.domain.repository.RoutePreviewRepository
import com.example.steptwin.domain.repository.TugRepository
import com.example.steptwin.domain.repository.VoiceAssistantRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindTugRepository(impl: TugRepositoryImpl): TugRepository

    @Binds
    @Singleton
    abstract fun bindRoutePreviewRepository(
        impl: RoutePreviewRepositoryImpl,
    ): RoutePreviewRepository

    @Binds
    @Singleton
    abstract fun bindVoiceAssistantRepository(
        impl: VoiceAssistantRepositoryImpl,
    ): VoiceAssistantRepository
}
