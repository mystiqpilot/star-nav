package tools.apachi.starlinknav.service

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import tools.apachi.starlinknav.core.dish.CallTarget
import tools.apachi.starlinknav.core.dish.CapabilityBook
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.DishCallResult

/**
 * What the dish and the router did the last time each call was tried ([CapabilityBook]), kept in the app's private
 * preferences so the controls stay greyed out, with the device's own words, after a restart. Fed by every result the
 * app sees: the service's poller, the Dish screens' pollers, the probe, the Debug data export and every command.
 *
 * The book is keyed by a hash of the device id, never the id: the key of the dish and of the router are learned from
 * the first answer that carries an id (and remembered, so the book is usable before the first answer after a start).
 * Nothing but outcome classes, the device's error text and times is stored: no payload, no Wi-Fi secret. Only a call whose
 * outcome or words changed is recorded ([CapabilityBook.hasNews]), so the book changes rarely.
 */
object CapabilityStore {
    private const val PREFS = "capabilities"

    private var prefs: SharedPreferences? = null
    private val state = MutableStateFlow(CapabilityBook())
    private val dish = MutableStateFlow("")
    private val router = MutableStateFlow("")

    val book: StateFlow<CapabilityBook> = state

    /** Key of the dish ([CapabilityBook.keyOf] of its id); empty until one answered. */
    val dishKey: StateFlow<String> = dish

    /** Key of the router; empty until one answered. */
    val routerKey: StateFlow<String> = router

    /** From [tools.apachi.starlinknav.App.onCreate]. */
    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        state.value = CapabilityBook.fromJson(p.getString("book", null))
        dish.value = p.getString("dishKey", "").orEmpty()
        router.value = p.getString("routerKey", "").orEmpty()
    }

    fun record(result: DishCallResult, nowMs: Long = System.currentTimeMillis()) {
        learnKey(result)
        val key = (if (result.kind.target == CallTarget.ROUTER) router else dish).value
        if (key.isEmpty()) return
        // Only news: a poll that says what the last one said leaves the book alone, so nothing that watches it (every
        // control card) recomposes each second and the preferences are not rewritten for it.
        if (!state.value.hasNews(key, result, nowMs)) return
        state.update { it.record(key, result, nowMs) }
        save()
    }

    fun recordAll(results: List<DishCallResult>) = results.forEach(::record)

    /** Greyed out because the device said it will not do [kind] for this client (null = not). The reason is its own words. */
    fun unavailableReason(kind: DishCallKind): String? {
        val key = (if (kind.target == CallTarget.ROUTER) router else dish).value
        return state.value.reason(key, kind)
    }

    /** Forgets what the dish refused or does not implement (after a firmware or account change); the router's stays. */
    fun forgetDish() {
        val key = dish.value
        if (key.isEmpty()) return
        state.update { it.forget(key) }
        save()
    }

    fun forgetRouter() {
        val key = router.value
        if (key.isEmpty()) return
        state.update { it.forget(key) }
        save()
    }

    /** Writes the book now (a screen closing, a command done). */
    fun flush() = save()

    private fun learnKey(r: DishCallResult) {
        if (!r.ok) return
        val resp = r.response ?: return
        if (r.kind.target == CallTarget.DISH) {
            val id = resp.dish_get_status?.device_info?.id ?: resp.get_device_info?.device_info?.id ?: resp.dish_get_diagnostics?.id
            setKey(dish, id, "dishKey")
        } else {
            val id = resp.wifi_get_status?.device_info?.id ?: resp.get_device_info?.device_info?.id ?: resp.wifi_get_diagnostics?.id
            setKey(router, id, "routerKey")
        }
    }

    private fun setKey(flow: MutableStateFlow<String>, id: String?, pref: String) {
        if (id.isNullOrEmpty()) return
        val key = CapabilityBook.keyOf(id)
        if (flow.value == key) return
        flow.value = key
        prefs?.edit { putString(pref, key) }
    }

    /** The book changes only on news (see [record]), so every change is written; `apply` batches the writes. */
    private fun save() {
        val p = prefs ?: return
        // A book that cannot be written is lost, never a crash of the screen or the service that fed it.
        val json = runCatching { state.value.toJson() }.getOrNull() ?: return
        p.edit { putString("book", json) }
    }
}
