package com.netsniff.sample.data

import retrofit2.http.GET
import retrofit2.http.Query

interface StarWarsApi {
    @GET("people/")
    suspend fun getPeople(@Query("page") page: Int = 1): SwapiListResponse<Person>

    @GET("films/")
    suspend fun getFilms(): SwapiListResponse<Film>

    @GET("planets/")
    suspend fun getPlanets(@Query("page") page: Int = 1): SwapiListResponse<Planet>
}
