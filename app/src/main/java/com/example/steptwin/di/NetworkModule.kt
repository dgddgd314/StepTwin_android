package com.example.steptwin.di

import com.example.steptwin.BuildConfig
import com.example.steptwin.data.remote.RouteApi
import com.example.steptwin.data.remote.WeightApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        // 서버 주소는 local.properties(SERVER_BASE_URL) 로 주입된다. 기본값 http://172.30.1.66:8000/
        return Retrofit.Builder()
            .baseUrl(BuildConfig.SERVER_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideWeightApi(retrofit: Retrofit): WeightApi {
        return retrofit.create(WeightApi::class.java)
    }

    @Provides
    @Singleton
    fun provideRouteApi(retrofit: Retrofit): RouteApi {
        return retrofit.create(RouteApi::class.java)
    }
}
