package dev.probe.sample.data

import com.google.gson.annotations.SerializedName

data class SwapiListResponse<T>(
    val count: Int,
    val next: String?,
    val previous: String?,
    val results: List<T>
)

data class Person(
    val name: String,
    val height: String,
    val mass: String,
    val gender: String,
    @SerializedName("birth_year") val birthYear: String,
    @SerializedName("eye_color") val eyeColor: String,
    @SerializedName("hair_color") val hairColor: String,
    val url: String
)

data class Film(
    val title: String,
    val director: String,
    val producer: String,
    @SerializedName("release_date") val releaseDate: String,
    @SerializedName("episode_id") val episodeId: Int,
    @SerializedName("opening_crawl") val openingCrawl: String,
    val url: String
)

data class Planet(
    val name: String,
    val climate: String,
    val terrain: String,
    val population: String,
    val diameter: String,
    val gravity: String,
    val url: String
)
