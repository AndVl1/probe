package dev.probe.sample.data

import dev.probe.sample.SampleApplication
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object StarWarsRepository {

    private val okHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // Probe network interceptor must be LAST to capture actual request/response
        .addInterceptor(SampleApplication.networkPlugin.interceptor())
        .build()

    private val api: StarWarsApi = Retrofit.Builder()
        .baseUrl("https://swapi.py4e.com/api/")
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(StarWarsApi::class.java)

    suspend fun getPeople(page: Int = 1): Result<List<Person>> = runCatching {
        api.getPeople(page).results
    }

    suspend fun getFilms(): Result<List<Film>> = runCatching {
        api.getFilms().results
    }

    suspend fun getPlanets(page: Int = 1): Result<List<Planet>> = runCatching {
        api.getPlanets(page).results
    }
}
