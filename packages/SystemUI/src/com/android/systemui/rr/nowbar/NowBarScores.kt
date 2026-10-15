/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.nowbar

import android.app.PendingIntent
import android.app.smartspace.SmartspaceAction
import android.app.smartspace.SmartspaceConfig
import android.app.smartspace.SmartspaceManager
import android.app.smartspace.SmartspaceSession
import android.app.smartspace.SmartspaceTarget
import android.app.smartspace.uitemplatedata.HeadToHeadTemplateData
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.util.Log
import com.android.systemui.res.R
import java.util.concurrent.Executor

/**
 * Live game scores for the Now Bar: Google's At a Glance through a Smartspace session, or the
 * cards Smartspacer sends in the old Pixel launcher broadcast. Only scoreboards get through.
 */
class NowBarScores(
    private val context: Context,
    private val handler: Handler,
    private val onChanged: () -> Unit,
) {
    var items: List<NowBarItem> = emptyList()
        private set

    private var source = SOURCE_OFF
    private var session: SmartspaceSession? = null

    private val targetsListener = SmartspaceSession.OnTargetsAvailableListener { targets ->
        if (source != SOURCE_GLANCE) return@OnTargetsAvailableListener
        set(targets.mapNotNull(::glanceItem))
    }

    fun setSource(value: Int) {
        if (value == source) return
        stop()
        source = value
        when (value) {
            SOURCE_GLANCE -> startGlance()
            SOURCE_SMARTSPACER -> active = this
        }
    }

    private fun startGlance() {
        val manager = context.getSystemService(SmartspaceManager::class.java) ?: return
        try {
            session = manager.createSmartspaceSession(
                SmartspaceConfig.Builder(context, UI_SURFACE).setSmartspaceTargetCount(10).build()
            ).also {
                it.addOnTargetsAvailableListener(
                    Executor { r -> handler.post(r) }, targetsListener)
                it.requestSmartspaceUpdate()
            }
        } catch (e: Exception) {
            Log.w(TAG, "No Smartspace session", e)
        }
    }

    private fun stop() {
        session?.let {
            try {
                it.removeOnTargetsAvailableListener(targetsListener)
                it.close()
            } catch (e: Exception) {
                // Gone with the service.
            }
        }
        session = null
        if (active === this) active = null
        set(emptyList())
    }

    private fun set(list: List<NowBarItem>) {
        if (list.isEmpty() && items.isEmpty()) return
        items = list
        onChanged()
    }

    // At a Glance

    private fun glanceItem(target: SmartspaceTarget): NowBarItem? {
        if (target.featureType != SmartspaceTarget.FEATURE_SPORTS) return null
        val h2h = target.templateData as? HeadToHeadTemplateData
        val score = (if (h2h != null) headToHead(target, h2h) else extrasScore(target))
            ?: return null
        val action = target.headerAction ?: target.baseAction
        return item("glance:${target.smartspaceTargetId}", score, action?.let(::intentOf))
    }

    private fun headToHead(target: SmartspaceTarget, h2h: HeadToHeadTemplateData): NowBarScore? {
        val first = h2h.headToHeadFirstCompetitorText?.text?.toString()?.trim() ?: return null
        val second = h2h.headToHeadSecondCompetitorText?.text?.toString()?.trim() ?: return null
        val m1 = NAME_SCORE.matchEntire(first)
        val m2 = NAME_SCORE.matchEntire(second)
        val status = h2h.headToHeadTitle?.text ?: target.headerAction?.subtitle
        val logo1 = h2h.headToHeadFirstCompetitorIcon?.icon?.loadDrawable(context)
        val logo2 = h2h.headToHeadSecondCompetitorIcon?.icon?.loadDrawable(context)
        return if (m1 != null && m2 != null) {
            score(m1.groupValues[1], m2.groupValues[1], m1.groupValues[2], m2.groupValues[2],
                logo1, logo2, status)
        } else {
            // Before the game: names only.
            score(first, second, "", "", logo1, logo2, status)
        }
    }

    private fun extrasScore(target: SmartspaceTarget): NowBarScore? {
        for (action in listOfNotNull(target.headerAction, target.baseAction)) {
            val extras = action.extras ?: continue
            val team1 = extras.getString("firstCompetitorName") ?: continue
            val team2 = extras.getString("secondCompetitorName") ?: continue
            return score(team1, team2, extras.getString("firstCompetitorScore") ?: "",
                extras.getString("secondCompetitorScore") ?: "",
                logo(extras.getParcelable("firstCompetitorLogo", Bitmap::class.java)),
                logo(extras.getParcelable("secondCompetitorLogo", Bitmap::class.java)),
                extras.getString("matchTimeSummary") ?: action.subtitle)
        }
        return null
    }

    private fun intentOf(action: SmartspaceAction): PendingIntent? =
        action.pendingIntent ?: action.intent?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

    // Smartspacer

    private fun onSmartspacer(bytes: ByteArray) {
        if (source != SOURCE_SMARTSPACER) return
        val cards = try {
            SmartspaceCards.parse(bytes)
        } catch (e: Exception) {
            Log.w(TAG, "Bad Smartspacer update", e)
            return
        }
        val now = System.currentTimeMillis()
        set(cards.filter { !it.discard && (it.expiry == 0L || it.expiry > now) }.mapNotNull {
            val title = it.title ?: return@mapNotNull null
            val score = textScore(title, it.subtitle) ?: return@mapNotNull null
            // Taps open the card: intents from a broadcast anyone can send aren't followed.
            item("smartspacer:${it.id}", score, null)
        })
    }

    /** "Lakers 101 - 99 Celtics", or "Lakers vs Celtics" before the game. */
    private fun textScore(title: String, status: String?): NowBarScore? {
        TEXT_SCORE.matchEntire(title.trim())?.let {
            val (team1, score1, score2, team2) = it.destructured
            return score(team1, team2, score1, score2, null, null, status)
        }
        TEXT_VERSUS.matchEntire(title.trim())?.let {
            val (team1, team2) = it.destructured
            return score(team1, team2, "", "", null, null, status)
        }
        return null
    }

    // Both

    private fun score(team1: String, team2: String, score1: String, score2: String,
            logo1: Drawable?, logo2: Drawable?, status: CharSequence?): NowBarScore? {
        val t1 = team1.trim()
        val t2 = team2.trim()
        if (!isTeam(t1) || !isTeam(t2)) return null
        val s1 = score1.trim()
        val s2 = score2.trim()
        // Both scores, or none before the game; never one.
        if ((s1.isEmpty() || s2.isEmpty()) && s1 != s2) return null
        if (s1.isNotEmpty() && (!SCORE.matches(s1) || !SCORE.matches(s2))) return null
        return NowBarScore(t1, t2, s1, s2, logo1, logo2, status?.toString()?.trim())
    }

    private fun item(key: String, score: NowBarScore, intent: PendingIntent?) = NowBarItem(
        key = key,
        type = NowBarType.SPORTS,
        packageName = null,
        icon = context.getDrawable(R.drawable.rr_nowbar_sports),
        art = null,
        title = if (score.score1.isEmpty()) {
            context.getString(R.string.now_bar_versus, score.team1, score.team2)
        } else {
            "${score.team1} ${score.score1} – ${score.score2} ${score.team2}"
        },
        text = score.status,
        contentIntent = intent,
        score = score,
    )

    private fun isTeam(name: String) = name.length in 2..40 && name.any { it.isLetter() }

    private fun logo(bitmap: Bitmap?): Drawable? =
        bitmap?.let { BitmapDrawable(context.resources, it) }

    /** Smartspacer sends its cards here, as Google's app once sent them to the Pixel launcher. */
    class SmartspacerReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val bytes = intent.getByteArrayExtra(EXTRA_CARD) ?: return
            val scores = active ?: return
            scores.handler.post { scores.onSmartspacer(bytes) }
        }
    }

    companion object {
        const val SOURCE_OFF = 0
        const val SOURCE_GLANCE = 1
        const val SOURCE_SMARTSPACER = 2

        private const val TAG = "NowBarScores"
        private const val UI_SURFACE = "lockscreen"
        private const val EXTRA_CARD = "SMARTSPACE_CARD"
        private val NAME_SCORE = Regex("""^(\D{2,40}?)\s+(\d{1,3})$""")
        private val SCORE = Regex("""^\d{1,3}$""")
        private val TEXT_SCORE =
            Regex("""^(\D{2,40}?)\s+(\d{1,3})\s*[-–:]\s*(\d{1,3})\s+(\D{2,40})$""")
        private val TEXT_VERSUS =
            Regex("""^(\D{2,40}?)\s+(?:vs\.?|v\.?|@)\s+(\D{2,40})$""", RegexOption.IGNORE_CASE)

        @Volatile
        private var active: NowBarScores? = null
    }
}

/** The few fields of Google's old Smartspace protobuf the Now Bar reads, decoded by hand. */
internal object SmartspaceCards {
    class Card(
        val id: Int,
        val discard: Boolean,
        val title: String?,
        val subtitle: String?,
        val expiry: Long,
    )

    fun parse(bytes: ByteArray): List<Card> {
        val cards = ArrayList<Card>()
        Reader(bytes, 0, bytes.size).forEach { field, reader ->
            if (field == 1) cards += card(reader.bytes()) else reader.skip()
        }
        return cards
    }

    private fun card(r: Reader): Card {
        var id = 0
        var discard = false
        var expiry = 0L
        // During the event first, then before, then after; static texts last.
        val messages = arrayOfNulls<Pair<String?, String?>>(6)
        r.forEach { field, reader ->
            when (field) {
                1 -> discard = reader.varint() != 0L
                2 -> id = reader.varint().toInt()
                4 -> messages[0] = message(reader.bytes())
                3 -> messages[1] = message(reader.bytes())
                5 -> messages[2] = message(reader.bytes())
                15 -> messages[3] = message(reader.bytes())
                14 -> messages[4] = message(reader.bytes())
                16 -> messages[5] = message(reader.bytes())
                12 -> reader.bytes().forEach { f, e -> if (f == 1) expiry = e.varint() else e.skip() }
                else -> reader.skip()
            }
        }
        val message = messages.firstOrNull { it != null && (it.first != null || it.second != null) }
        return Card(id, discard, message?.first, message?.second, expiry)
    }

    private fun message(r: Reader): Pair<String?, String?> {
        var title: String? = null
        var subtitle: String? = null
        r.forEach { field, reader ->
            when (field) {
                1 -> title = text(reader.bytes())
                2 -> subtitle = text(reader.bytes())
                else -> reader.skip()
            }
        }
        return title to subtitle
    }

    private fun text(r: Reader): String? {
        var text: String? = null
        r.forEach { field, reader -> if (field == 1) text = reader.string() else reader.skip() }
        return text
    }

    private class Reader(private val data: ByteArray, private var pos: Int, private val end: Int) {
        private var wireType = 0

        fun forEach(block: (field: Int, reader: Reader) -> Unit) {
            while (pos < end) {
                val tag = varint()
                wireType = (tag and 7).toInt()
                block((tag ushr 3).toInt(), this)
            }
        }

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(pos < end && shift < 64) { "truncated varint" }
                val b = data[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        fun bytes(): Reader {
            require(wireType == 2) { "not length-delimited" }
            val length = varint().toInt()
            require(length >= 0 && pos + length <= end) { "bad length" }
            return Reader(data, pos, pos + length).also { pos += length }
        }

        fun string(): String {
            val r = bytes()
            return String(data, r.pos, r.end - r.pos, Charsets.UTF_8)
        }

        fun skip() {
            when (wireType) {
                0 -> varint()
                1 -> pos += 8
                2 -> bytes()
                5 -> pos += 4
                else -> throw IllegalArgumentException("wire type $wireType")
            }
            require(pos <= end) { "truncated field" }
        }
    }
}
