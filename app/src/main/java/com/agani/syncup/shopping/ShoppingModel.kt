package com.agani.syncup.shopping

import com.agani.syncup.music.ToolTint
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToLong

/**
 * One shopping list (a "folder"): its name, its colour (a [ToolTint] name) and its place among the
 * lists. Synced as kind "shoplist".
 */
internal data class ShopList(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val color: String = ToolTint.GREEN.name,
    val order: Int = 0,
    val updatedMs: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
    /** Not uploaded yet. */
    val dirty: Boolean = true,
    /** Made while signed out — joins the account at the next sign-in. */
    val local: Boolean = false,
) {
    val tint: ToolTint get() = shopTint(color)

    fun syncJson(): JSONObject = JSONObject().put("name", name).put("color", color).put("order", order)

    fun storeJson(): JSONObject = syncJson().put("id", id).put("updated_ms", updatedMs).put("deleted", deleted).put("dirty", dirty).put("local", local)

    companion object {
        /** The list everyone starts with. A fixed id, so two phones never make it twice. */
        const val DEFAULT_ID = "groceries"

        fun groceries() = ShopList(DEFAULT_ID, "Groceries", ToolTint.GREEN.name, 0, updatedMs = 1, dirty = false)

        fun fromJson(id: String, o: JSONObject, updatedMs: Long, deleted: Boolean) = ShopList(
            id = id,
            name = o.optString("name").ifBlank { "List" },
            color = o.optString("color").ifBlank { ToolTint.GREEN.name },
            order = o.optInt("order"),
            updatedMs = updatedMs,
            deleted = deleted,
            dirty = false,
        )

        fun fromStore(o: JSONObject) = fromJson(o.optString("id"), o, o.optLong("updated_ms"), o.optBoolean("deleted"))
            .copy(dirty = o.optBoolean("dirty"), local = o.optBoolean("local"))
    }
}

/** One thing to buy, in a list. Synced as kind "shopitem". */
internal data class ShopItem(
    val id: String = UUID.randomUUID().toString(),
    val listId: String,
    val name: String,
    val qty: Double = 1.0,
    /** A [QtyUnit] label: pcs, kg, g, L, ml, pack, dozen. */
    val unit: String = QtyUnit.PCS.label,
    /** An [Aisle] id. */
    val aisle: String = Aisle.OTHER.id,
    val note: String = "",
    val bought: Boolean = false,
    val boughtMs: Long = 0,
    val order: Int = 0,
    val updatedMs: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
    val dirty: Boolean = true,
    val local: Boolean = false,
) {
    val qtyUnit: QtyUnit get() = QtyUnit.of(unit)
    val aisleOf: Aisle get() = Aisle.of(aisle)

    /** "1 kg", "2 L", "1 dozen", "3". */
    val qtyText: String get() = qtyText(qty, qtyUnit)

    fun syncJson(): JSONObject = JSONObject()
        .put("list", listId).put("name", name).put("qty", qty).put("unit", unit).put("aisle", aisle)
        .put("note", note).put("bought", bought).put("bought_ms", boughtMs).put("order", order)

    fun storeJson(): JSONObject = syncJson().put("id", id).put("updated_ms", updatedMs).put("deleted", deleted).put("dirty", dirty).put("local", local)

    companion object {
        fun fromJson(id: String, o: JSONObject, updatedMs: Long, deleted: Boolean) = ShopItem(
            id = id,
            listId = o.optString("list").ifBlank { ShopList.DEFAULT_ID },
            name = o.optString("name").ifBlank { "Item" },
            qty = o.optDouble("qty", 1.0).takeIf { !it.isNaN() && it > 0 } ?: 1.0,
            unit = QtyUnit.of(o.optString("unit")).label,
            aisle = Aisle.of(o.optString("aisle")).id,
            note = o.optString("note"),
            bought = o.optBoolean("bought"),
            boughtMs = o.optLong("bought_ms"),
            order = o.optInt("order"),
            updatedMs = updatedMs,
            deleted = deleted,
            dirty = false,
        )

        fun fromStore(o: JSONObject) = fromJson(o.optString("id"), o, o.optLong("updated_ms"), o.optBoolean("deleted"))
            .copy(dirty = o.optBoolean("dirty"), local = o.optBoolean("local"))
    }
}

/** A thing bought before (kept on this phone only), for the quick chips: its last amount and aisle. */
internal data class PastItem(val name: String, val qty: Double, val unit: String, val aisle: String, val usedMs: Long) {
    fun json(): JSONObject = JSONObject().put("name", name).put("qty", qty).put("unit", unit).put("aisle", aisle).put("used_ms", usedMs)

    companion object {
        fun from(o: JSONObject): PastItem? {
            val name = o.optString("name").trim()
            if (name.isEmpty()) return null
            return PastItem(name, o.optDouble("qty", 1.0).takeIf { !it.isNaN() && it > 0 } ?: 1.0, QtyUnit.of(o.optString("unit")).label, Aisle.of(o.optString("aisle")).id, o.optLong("used_ms"))
        }
    }
}

/** How an amount is counted, and how far − / + move it. */
internal enum class QtyUnit(val label: String, val step: Double, val start: Double) {
    PCS("pcs", 1.0, 1.0),
    KG("kg", 0.5, 1.0),
    G("g", 50.0, 500.0),
    L("L", 0.5, 1.0),
    ML("ml", 50.0, 500.0),
    PACK("pack", 1.0, 1.0),
    DOZEN("dozen", 1.0, 1.0),
    ;

    private val counted: Boolean get() = this == PCS || this == PACK || this == DOZEN
    private val big: Boolean get() = this == KG || this == L
    private val small: Boolean get() = this == G || this == ML

    fun up(q: Double): Double = (snap(q) + step).coerceAtMost(9999.0)

    /** Never below one step. */
    fun down(q: Double): Double = if (q - step >= step - 1e-9) snap(q - step) else minOf(q, step)

    private fun snap(q: Double): Double = (q / step).roundToLong().coerceAtLeast(1) * step

    /** The same amount in [to] where that makes sense (1 kg → 1,000 g), else [to]'s usual start. */
    fun convert(q: Double, to: QtyUnit): Double = when {
        to == this -> q
        big && to.small -> to.snap(q * 1000)
        small && to.big -> to.snap(q / 1000)
        (big && to.big) || (small && to.small) -> q
        counted && to.counted -> to.snap(q)
        else -> to.start
    }

    companion object {
        fun of(label: String): QtyUnit = entries.firstOrNull { it.label.equals(label.trim(), ignoreCase = true) } ?: PCS
    }
}

/** 1 → "1", 0.5 → "0.5", 1.25 → "1.25". */
internal fun fmtNum(d: Double): String {
    val r = (d * 100).roundToLong() / 100.0
    return if (r == Math.floor(r)) r.toLong().toString() else String.format(Locale.US, "%.2f", r).trimEnd('0').trimEnd('.')
}

/** "1 kg", "2 L", "1 dozen", "2 packs", and just "3" for pieces. */
internal fun qtyText(qty: Double, unit: QtyUnit): String {
    val n = fmtNum(qty)
    return when (unit) {
        QtyUnit.PCS -> n
        QtyUnit.PACK -> if (qty == 1.0) "1 pack" else "$n packs"
        QtyUnit.DOZEN -> "$n dozen"
        else -> "$n ${unit.label}"
    }
}

/** The colours a list can have, in the picker's order. */
internal val ShopColors = listOf(
    ToolTint.GREEN, ToolTint.ORANGE, ToolTint.SKY, ToolTint.SLATE, ToolTint.VIOLET,
    ToolTint.PINK, ToolTint.RED, ToolTint.AMBER, ToolTint.LIME, ToolTint.INDIGO,
)

internal fun shopTint(name: String): ToolTint = ToolTint.entries.firstOrNull { it.name == name } ?: ToolTint.GREEN
