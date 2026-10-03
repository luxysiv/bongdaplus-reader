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

/** 1 giải đang có lịch (động theo thời gian thực: chỉ giải nào có trận mới hiện).
 * key: file giải nếu join được (để khỏi trùng mục), không thì slug trong lịch;
 * matchSlugs: mọi slug lịch từng thấy của giải (lịch chung dùng slug nội bộ
 * khác file giải, vd bundesliga vs bong-da-duc). */
data class CompEntry(
    val key: String,
    val file: String,
    val name: String,
    val hasRank: Boolean,
    val live: Int,
    val total: Int,
    val soon: String,
    val matchSlugs: Set<String> = emptySet(),
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

    /** Tên Việt ngắn cho chip giải (API đôi khi để tên Anh). */
    private val VI_NAMES = mapOf(
        "bong-da-viet-nam" to "V.League",
        "vleague-1" to "V.League",
        "bong-da-anh" to "Ngoại hạng Anh",
        "bong-da-tay-ban-nha" to "La Liga",
        "bong-da-y" to "Serie A",
        "bong-da-duc" to "Bundesliga",
        "bundesliga" to "Bundesliga",
        "bong-da-phap" to "Ligue 1",
        "ligue-1" to "Ligue 1",
        "la-liga" to "La Liga",
        "serie-a" to "Serie A",
        "champions-league-cup-c1" to "Champions League",
        "europa-league" to "Europa League",
        "uefa-europa-conference-league" to "Conference League",
        "uefa-nations-league" to "Nations League",
        "fifa-world-cup" to "World Cup",
        "uefa-european" to "Euro",
        "fa-cup" to "FA Cup",
        "carabao-cup" to "League Cup",
        "coppa-italia" to "Coppa Italia",
        "copa-del-rey" to "Cúp Nhà vua",
        "dfb-pokal" to "Cúp QG Đức",
        "coupe-de-france" to "Cúp QG Pháp",
        "cup-quoc-gia" to "Cúp QG VN",
        "hang-nhat-quoc-gia" to "Hạng Nhất VN",
        "bong-da-nu-viet-nam" to "Nữ Việt Nam",
        "sea-games" to "SEA Games",
        "asian-cup" to "Asian Cup",
        "aff-cup" to "AFF Cup",
        "copa-america" to "Copa America",
    )

    fun shortName(slug: String, fallback: String): String =
        VI_NAMES[slug] ?: fallback.ifBlank { slug }

    /** 5 giải hàng đầu châu Âu: luôn ghim đầu danh sách (kể cả trái mùa). */
    val TOP5 = listOf(
        "bong-da-anh",
        "bong-da-tay-ban-nha",
        "bong-da-y",
        "bong-da-duc",
        "bong-da-phap",
    )

    /**
     * Dựng chip giải: 5 giải hàng đầu châu Âu GHIM sẵn (luôn hiện cả trái mùa),
     * tiếp theo là các giải động đang có trận (Euro/World Cup chỉ hiện đúng mùa).
     * Join tournaments.json với lịch chung qua tournament_id; key = file giải
     * nếu join được (để khỏi trùng mục), không thì slug trong lịch.
     */
    suspend fun compEntries(): List<CompEntry> = withContext(Dispatchers.IO) {
        try {
            // Lấy tournaments kèm id để join với lịch qua tournament_id
            val arr = getArr("tournaments") ?: return@withContext emptyList()
            val tourById = mutableMapOf<String, Comp>()
            val tourByFile = mutableMapOf<String, Comp>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                val file = o.optString("file").trim()
                if (file.isBlank()) continue
                val name = o.optString("rename").ifBlank { o.optString("name") }.trim().ifBlank { file }
                val c = Comp(file, name.nfcVi().unescapeHtml(), o.optBoolean("has_rank", false))
                if (id.isNotBlank()) tourById[id] = c
                tourByFile[file] = c
            }
            data class Acc(var file: String, var name: String, var hasRank: Boolean,
                           var live: Int, var total: Int, var soon: String,
                           val slugs: MutableSet<String> = mutableSetOf())
            val map = LinkedHashMap<String, Acc>()
            try {
                val fixArr = getArr("lich-thi-dau-bong-da")
                if (fixArr != null) for (i in 0 until fixArr.length()) {
                    val o = fixArr.optJSONObject(i) ?: continue
                    val t = o.optJSONObject("tournament") ?: continue
                    val tour = tourById[t.optString("tournament_id")]
                    // Key = file giải nếu join được, khỏi trùng với mục ghim
                    val fslug = t.optString("tournament_slug").trim()
                    val key = tour?.file ?: fslug
                    if (key.isBlank()) continue
                    val a = map.getOrPut(key) {
                        Acc(tour?.file.orEmpty(),
                            shortName(tour?.file ?: key,
                                tour?.name ?: t.optString("tournament_name")),
                            tour?.hasRank == true, 0, 0, "9")
                    }
                    if (fslug.isNotBlank()) a.slugs += fslug
                    val st = o.optInt("status")
                    if (st != 0 && st != 100) a.live++
                    a.total++
                    val s = o.optString("start_time")
                    if (s.isNotBlank() && (a.soon == "9" || s < a.soon)) a.soon = s
                }
            } catch (_: Exception) { }
            // 5 giải top đầu luôn ghim (kể cả trái mùa chưa có lịch)
            val out = mutableListOf<CompEntry>()
            for (file in TOP5) {
                val dyn = map.remove(file)
                val tour = tourByFile[file]
                out += CompEntry(
                    key = file, file = file,
                    name = shortName(file, tour?.name ?: dyn?.name ?: file),
                    hasRank = tour?.hasRank ?: (dyn?.hasRank == true),
                    live = dyn?.live ?: 0, total = dyn?.total ?: 0,
                    soon = dyn?.soon ?: "9",
                    matchSlugs = dyn?.slugs ?: emptySet(),
                )
            }
            // Còn lại: giải động đang có trận, live trước
            out += map.map { (key, a) ->
                CompEntry(key, a.file, a.name.nfcVi().unescapeHtml(), a.hasRank,
                    a.live, a.total, a.soon, a.slugs)
            }.sortedWith(compareByDescending<CompEntry> { it.live }
                .thenBy { it.soon }.thenByDescending { it.total })
            out
        } catch (_: Exception) { emptyList() }
    }

    /** Lịch + kết quả FULL mùa của 1 giải ({file}-matches.json: {days, matches, ...}). */
    data class CompMatches(
        val name: String,
        val slug: String,
        val logo: String?,
        val hasRank: Boolean,
        val matches: List<ScoreMatch>,
    )

    suspend fun compMatches(file: String): CompMatches? = withContext(Dispatchers.IO) {
        try {
            val o = getObj("$file-matches") ?: return@withContext null
            val arr = o.optJSONArray("matches") ?: return@withContext null
            val list = mutableListOf<ScoreMatch>()
            for (i in 0 until arr.length()) {
                parseMatch(arr.optJSONObject(i) ?: continue)?.let { list += it }
            }
            CompMatches(
                name = shortName(file, o.optString("tournament_name")).nfcVi().unescapeHtml(),
                slug = o.optString("tournament_slug").ifBlank { file },
                logo = o.optString("tournament_logo").takeIf { it.isNotBlank() }?.let { LOGO + it },
                hasRank = o.optBoolean("has_rank", false),
                matches = list,
            )
        } catch (_: Exception) { null }
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
     * Lịch thi đấu chung TẤT CẢ các giải (lich-thi-dau-bong-da.json).
     * Sắp xếp: đang đá trước, rồi tới giờ. Lọc theo giải thì dùng matchSlugs.
     */
    suspend fun fixtures(): List<ScoreMatch> = withContext(Dispatchers.IO) {
        try {
            val arr = getArr("lich-thi-dau-bong-da") ?: return@withContext emptyList()
            val out = mutableListOf<ScoreMatch>()
            for (i in 0 until arr.length()) {
                parseMatch(arr.optJSONObject(i) ?: continue)?.let { out += it }
            }
            out.sortedWith(compareBy<ScoreMatch> { if (!it.isUpcoming && !it.isFinished) 0 else 1 }
                .thenBy { it.startTime })
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Kết quả chung TẤT CẢ các giải (ket-qua-bong-da.json). Mới nhất trước.
     */
    suspend fun results(): List<ScoreMatch> = withContext(Dispatchers.IO) {
        try {
            val arr = getArr("ket-qua-bong-da") ?: return@withContext emptyList()
            val out = mutableListOf<ScoreMatch>()
            for (i in 0 until arr.length()) {
                parseMatch(arr.optJSONObject(i) ?: continue)?.let { out += it }
            }
            out.sortedByDescending { it.startTime }
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
