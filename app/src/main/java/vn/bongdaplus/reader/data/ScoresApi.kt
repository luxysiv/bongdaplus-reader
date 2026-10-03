package vn.bongdaplus.reader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Giải đấu trong API data (slug `file` dùng cho {file}-matches.json / {file}-rankings.json). */
data class Comp(
    val file: String,
    val name: String,
    val hasRank: Boolean,
)

/** 1 trận: lịch (status=0) / live / xong (status=100, play_time=FT). */
data class ScoreMatch(
    val id: String,
    val compName: String,
    val compSlug: String,
    val compLogo: String?,
    val home: String,
    val homeLogo: String?,
    val away: String,
    val awayLogo: String?,
    val startTime: String,   // "2026-10-03 05:15:00"
    val playTime: String,    // "05:15" | "FT" | "63'"
    val homeGoals: Int,
    val awayGoals: Int,
    val status: Int,
    val round: String,
) {
    val isFinished: Boolean get() = status == 100
    val isUpcoming: Boolean get() = status == 0
    /** "2026-10-03" để gom nhóm theo ngày. */
    val day: String get() = startTime.take(10)
}

/** 1 dòng bảng xếp hạng. */
data class StandingRow(
    val pos: Int,
    val team: String,
    val logo: String?,
    val played: Int,
    val won: Int,
    val drawn: Int,
    val lost: Int,
    val gf: Int,
    val ga: Int,
    val diff: Int,
    val points: Int,
    val color: String?,
)

/**
 * API JSON chính chủ của web (mà web dùng cho Lịch/Kết quả/BXH):
 * https://data.bongdaplus.vn/data/{tournaments,lich-thi-dau-bong-da,ket-qua-bong-da,
 * {slug}-matches,{slug}-rankings}.json — logo ở /logo/.
 */
object ScoresApi {
    const val DATA = "https://data.bongdaplus.vn/data/"
    const val LOGO = "https://data.bongdaplus.vn/logo/"

    @Volatile
    private var compCache: List<Comp>? = null

    private suspend fun getArr(name: String): JSONArray? = withContext(Dispatchers.IO) {
        try {
            val html = Http.get("$DATA$name.json")?.html?.trim() ?: return@withContext null
            if (html.startsWith("[")) JSONArray(html) else null
        } catch (_: Exception) { null }
    }

    private suspend fun getObj(name: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val html = Http.get("$DATA$name.json")?.html?.trim() ?: return@withContext null
            if (html.startsWith("{")) JSONObject(html) else null
        } catch (_: Exception) { null }
    }

    /** Danh sách giải (cache RAM, có sẵn cờ hasRank để ẩn BXH khi giải không có). */
    suspend fun tournaments(): List<Comp> {
        compCache?.let { return it }
        val out = mutableListOf<Comp>()
        try {
            val arr = getArr("tournaments") ?: return emptyList()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val file = o.optString("file").trim()
                if (file.isBlank()) continue
                val name = o.optString("rename").ifBlank { o.optString("name") }.trim()
                    .ifBlank { file }
                out += Comp(file, name.nfcVi().unescapeHtml(), o.optBoolean("has_rank", false))
            }
            if (out.isNotEmpty()) compCache = out
        } catch (_: Exception) { }
        return out
    }

    private fun parseMatch(o: JSONObject): ScoreMatch? {
        return try {
            val t = o.optJSONObject("tournament")
            ScoreMatch(
                id = o.optString("match_id"),
                compName = (t?.optString("tournament_name").orEmpty()).nfcVi().unescapeHtml(),
                compSlug = t?.optString("tournament_slug").orEmpty(),
                compLogo = t?.optString("tournament_logo")?.takeIf { it.isNotBlank() }?.let { LOGO + it },
                home = o.optString("home_name").nfcVi().unescapeHtml(),
                homeLogo = o.optString("home_logo").takeIf { it.isNotBlank() }?.let { LOGO + it },
                away = o.optString("away_name").nfcVi().unescapeHtml(),
                awayLogo = o.optString("away_logo").takeIf { it.isNotBlank() }?.let { LOGO + it },
                startTime = o.optString("start_time"),
                playTime = o.optString("play_time"),
                homeGoals = o.optInt("goals_home"),
                awayGoals = o.optInt("goals_away"),
                status = o.optInt("status"),
                round = o.optString("round_name").takeIf { it.isNotBlank() && it != "0" }.orEmpty(),
            ).takeIf { it.id.isNotBlank() && it.home.isNotBlank() }
        } catch (_: Exception) { null }
    }

    /**
     * Lịch thi đấu: slug=null -> tất cả (lich-thi-dau-bong-da.json),
     * có slug -> {slug}-matches.json. Sắp xếp: đang đá trước, rồi tới giờ.
     */
    suspend fun fixtures(slug: String?): List<ScoreMatch> = withContext(Dispatchers.IO) {
        try {
            val arr = getArr(if (slug.isNullOrBlank()) "lich-thi-dau-bong-da" else "$slug-matches")
                ?: return@withContext emptyList()
            val out = mutableListOf<ScoreMatch>()
            for (i in 0 until arr.length()) {
                parseMatch(arr.optJSONObject(i) ?: continue)?.let { out += it }
            }
            out.sortedWith(compareBy<ScoreMatch> { if (!it.isUpcoming && !it.isFinished) 0 else 1 }
                .thenBy { it.startTime })
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Kết quả: slug=null -> ket-qua-bong-da.json,
     * có slug -> lọc trận đã đá xong từ {slug}-matches.json. Mới nhất trước.
     */
    suspend fun results(slug: String?): List<ScoreMatch> = withContext(Dispatchers.IO) {
        try {
            val list = if (slug.isNullOrBlank()) {
                val arr = getArr("ket-qua-bong-da") ?: return@withContext emptyList()
                List(arr.length()) { parseMatch(arr.optJSONObject(it) ?: JSONObject()) }
                    .filterNotNull()
            } else {
                fixtures(slug).filter { it.isFinished }
            }
            list.sortedByDescending { it.startTime }
        } catch (_: Exception) { emptyList() }
    }

    /** Bảng xếp hạng 1 giải ({slug}-rankings.json). Giải không có BXH -> rỗng. */
    suspend fun standings(slug: String): List<StandingRow> = withContext(Dispatchers.IO) {
        try {
            val ranks = getObj("${slug}-rankings")?.optJSONArray("ranks") ?: return@withContext emptyList()
            val out = mutableListOf<StandingRow>()
            for (i in 0 until ranks.length()) {
                val o = ranks.optJSONObject(i) ?: continue
                out += StandingRow(
                    pos = o.optInt("position", i + 1),
                    team = o.optString("team_name").nfcVi().unescapeHtml(),
                    logo = o.optString("team_logo").takeIf { it.isNotBlank() }?.let { LOGO + it },
                    played = o.optInt("matches"),
                    won = o.optInt("wins"),
                    drawn = o.optInt("draws"),
                    lost = o.optInt("losses"),
                    gf = o.optInt("scores_for"),
                    ga = o.optInt("scores_against"),
                    diff = o.optInt("scores_diff"),
                    points = o.optInt("points"),
                    color = o.optString("color").takeIf { it.isNotBlank() },
                )
            }
            out.sortedBy { it.pos }
        } catch (_: Exception) { emptyList() }
    }
}

/** Các giải ưu tiên cho chip chọn nhanh (slug khớp API data + tin tức). */
val SCORE_COMPS = listOf(
    "bong-da-viet-nam" to "V.League",
    "bong-da-anh" to "Ngoại hạng Anh",
    "bong-da-tay-ban-nha" to "La Liga",
    "bong-da-y" to "Serie A",
    "bong-da-duc" to "Bundesliga",
    "bong-da-phap" to "Ligue 1",
    "champions-league-cup-c1" to "Champions League",
    "europa-league" to "Europa League",
    "uefa-nations-league" to "Nations League",
    "fifa-world-cup" to "World Cup",
    "uefa-european" to "Euro",
    "fa-cup" to "FA Cup",
)
