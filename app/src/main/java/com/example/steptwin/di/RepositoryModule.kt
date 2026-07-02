package com.example.steptwin.di

import com.example.steptwin.data.repository.TugRepositoryImpl
import com.example.steptwin.domain.repository.TugRepository
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
}
