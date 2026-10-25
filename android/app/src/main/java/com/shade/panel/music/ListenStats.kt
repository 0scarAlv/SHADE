package com.shade.panel.music

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class StatsPeriod { HOY, SEMANA, MES, ANIO, TODO }

/** One ranked row: an artist, genre or song with its listening time. */
data class StatsEntry(val name: String, val detail: String?, val hours: Double, val plays: Int)

data class StatsSummary(
    val period: StatsPeriod,
    val hours: Double,
    val listens: Int,
    val plays: Int,
    val artists: List<StatsEntry>,
    val genres: List<StatsEntry>,
    val songs: List<StatsEntry>,
    val byHour: DoubleArray,
    /** Of [hours], how much played on this phone (the rest came from Nation's other devices). */
    val phoneHours: Double = 0.0,
) {
    val isEmpty get() = listens == 0

    companion object {
        val EMPTY = StatsSummary(StatsPeriod.SEMANA, 0.0, 0, 0, emptyList(), emptyList(), emptyList(), DoubleArray(24))
    }
}

/**
 * Port of Nation's Stats/ListenStats.cs: same periods, same "play" rule, same
 * genre splitting and repair, so a number on the phone means what it means in
 * Nation. Rules live at query time, never in the stored listens.
 */
object ListenStats {
    /** Last.fm's rule: half the song or four minutes; songs without a length need 30 s. */
    fun isPlay(playedMs: Long, durationMs: Long): Boolean =
        if (durationMs > 0) playedMs >= minOf(durationMs / 2, 240_000) else playedMs >= 30_000

    fun periodStartMs(period: StatsPeriod, today: LocalDate = LocalDate.now()): Long {
        val start = when (period) {
            StatsPeriod.HOY -> today
            // Weeks start on Monday.
            StatsPeriod.SEMANA -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            StatsPeriod.MES -> today.withDayOfMonth(1)
            StatsPeriod.ANIO -> today.withDayOfYear(1)
            StatsPeriod.TODO -> return 0
        }
        return start.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    fun query(db: ListenDb, period: StatsPeriod, top: Int = 50): StatsSummary {
        val since = periodStartMs(period)
        val artists = HashMap<String, Acc>()
        val genres = HashMap<String, Acc>()
        val songs = HashMap<String, Acc>()
        val byHour = DoubleArray(24)
        var totalMs = 0.0
        var phoneMs = 0.0
        val phone = db.deviceId
        var listens = 0
        var plays = 0
        val zone = ZoneId.systemDefault()

        db.readableDatabase.rawQuery(
            "SELECT artist, title, genre, played_ms, duration_ms, started_at, track_key, device FROM listen WHERE started_at >= ? AND music = 1",
            arrayOf(since.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val artist = c.getString(0)
                val title = c.getString(1)
                val genre = if (c.isNull(2)) null else c.getString(2)
                val played = c.getLong(3)
                val play = isPlay(played, c.getLong(4))
                listens++
                if (play) plays++
                totalMs += played
                if (c.getString(7) == phone) phoneMs += played
                val hour = java.time.Instant.ofEpochMilli(c.getLong(5)).atZone(zone).hour
                byHour[hour] += played / 3_600_000.0

                // Artists group case-insensitively, like Nation's OrdinalIgnoreCase dictionary.
                add(artists, artist.lowercase(), artist, null, played, play)
                // A song tagged "Alternativo, Indie Rock" counts in full for each of its genres.
                for (g in splitGenres(genre)) add(genres, g.lowercase(), g, null, played, play)
                add(songs, c.getString(6), title, artist, played, play)
            }
        }
        return StatsSummary(
            period, totalMs / 3_600_000.0, listens, plays, rank(artists, top), rank(genres, top), rank(songs, top), byHour,
            phoneHours = phoneMs / 3_600_000.0,
        )
    }

    private class Acc(val name: String, val detail: String?) {
        var ms = 0.0
        var plays = 0
    }

    // Keyed by the grouping key, shown by the first spelling seen.
    private fun add(map: HashMap<String, Acc>, key: String, name: String, detail: String?, ms: Long, play: Boolean) {
        val acc = map.getOrPut(key) { Acc(name, detail) }
        acc.ms += ms
        if (play) acc.plays++
    }

    private fun rank(map: Map<String, Acc>, top: Int) = map.values
        .sortedByDescending { it.ms }
        .take(top)
        .map { StatsEntry(it.name, it.detail, it.ms / 3_600_000.0, it.plays) }

    // Deezer separates genres with commas; "Rap/Hip Hop" is one genre, so '/' is not a separator.
    fun splitGenres(genre: String?): List<String> =
        if (genre.isNullOrBlank()) emptyList()
        else genre.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.map(::repairGenre)

    // SpotiFLAC writes some Deezer genres with U+FFFD where the accent was ("M�sica Asi�tica").
    private val ACCENTED = listOf(
        "Música", "Asiática", "Películas", "Clásica", "Electrónica", "Brasileña", "Africana", "Árabe",
        "Latinoamericana", "Sudafricana", "Japonesa", "Coreana", "Infantil", "Canción", "Bandas", "Sonoras",
    )

    fun repairGenre(genre: String): String {
        if ('�' !in genre) return genre
        return genre.split(' ').joinToString(" ") { w ->
            if ('�' !in w) w
            else ACCENTED.firstOrNull { a ->
                a.length == w.length && a.zip(w).all { (x, y) -> y == '�' || x.lowercaseChar() == y.lowercaseChar() }
            } ?: w.replace('�', '?')
        }
    }
}
