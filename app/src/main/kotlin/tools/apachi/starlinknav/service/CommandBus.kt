package tools.apachi.starlinknav.service

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import tools.apachi.starlinknav.core.dish.CallOutcome
import tools.apachi.starlinknav.core.dish.CallTarget
import tools.apachi.starlinknav.core.dish.CommandRisk
import tools.apachi.starlinknav.core.dish.DeviceCommand
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.DishCallResult
import tools.apachi.starlinknav.core.dish.RouterCommand
import java.util.concurrent.atomic.AtomicReference

/**
 * A command the user asked for: a parameterless command kind (reboot, GNSS inhibit, ...) or a [DeviceCommand] object
 * (sleep schedule, config changes, router writes). Only commands can be asked for here; reads go through the pollers.
 */
class DeviceCall private constructor(val kind: DishCallKind, val command: DeviceCommand?) {
    /** The device this goes to. */
    val target: CallTarget get() = kind.target

    /** How much it can disturb, for the confirmation: the command's own risk when it has one, else its kind's. */
    val risk: CommandRisk get() = command?.risk ?: kind.risk ?: CommandRisk.DISRUPTIVE

    override fun toString() = "DeviceCall(${kind.label})"

    companion object {
        fun of(kind: DishCallKind): DeviceCall {
            require(kind.isCommand && !kind.parametric) { "$kind is not a parameterless command" }
            return DeviceCall(kind, null)
        }

        fun of(command: DeviceCommand): DeviceCall = DeviceCall(command.kind, command)
    }
}

/**
 * The error texts of failures the app makes itself (no device was asked). They travel in `DishCallResult.error` and in the
 * capability book like a device's own words, so the screens match them and show a translated sentence instead.
 */
object AppErrors {
    /** The router's address is neither typed in Settings nor the gateway of a Wi-Fi network. */
    const val NO_ROUTER_ADDRESS = "no router address"
}

/** What the last command of a control came to: the outcome class and the device's own words, no payload. */
data class SentCommand(val kind: DishCallKind, val outcome: CallOutcome, val message: String, val atWallMs: Long)

/**
 * The one way a command leaves the app (explicit user action only). While [NavService] runs it subscribes with
 * [attach]: the call is then sent by the service, which logs it as a dish call into the running session and feeds the
 * dish restart tracker, so a restart this app caused is reported as "Restart sent from Star Nav" and not with the
 * dish's own reason. With no service the call is sent directly from here (nothing to log or track then).
 *
 * A command runs in the bus's own application-lifetime scope, not in the scope of the screen that asked: a screen that
 * goes away while the command is out (the tab is left, the reboot takes the very card that sent it off the screen)
 * must not skip the logging, the restart tracking, the capability book or the "last outcome" line. The screen only
 * waits for the result ([launch] gives the [Deferred]); cancelling that wait cancels nothing of the command.
 *
 * Every result is also recorded in the [CapabilityStore] and, as a [SentCommand], in [last] for the screens' "last
 * outcome" lines. A command is never sent twice: the transports retry only a call that never reached the device. At
 * most one command per device is out at a time ([pending]); a second one is refused until the first has its answer.
 */
object CommandBus {
    /** Sends [call] for the bus: the service's implementation. Runs in a coroutine; blocks on the network, so use IO. */
    fun interface Handler {
        suspend fun handle(call: DeviceCall): DishCallResult
    }

    private val handler = AtomicReference<Handler?>()
    private val lastByTag = MutableStateFlow<Map<String, SentCommand>>(emptyMap())
    private val pendingTargets = MutableStateFlow<Set<CallTarget>>(emptySet())
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** True while a service has taken the bus over. */
    val viaService: Boolean get() = handler.get() != null

    /** The last result of each control, by the tag it sent with (the kind's label when it gave none). */
    val last: StateFlow<Map<String, SentCommand>> = lastByTag

    /** Drops the remembered last result of [tag] (it was overtaken by another route, such as the Starlink account sign-in). */
    fun forgetLast(tag: String) = lastByTag.update { it - tag }

    fun attach(h: Handler) = handler.set(h)

    /** Only the handler that attached is removed: a newer service instance keeps its own. */
    fun detach(h: Handler) {
        handler.compareAndSet(h, null)
    }

    /** The devices that have a command on its way. */
    val pending: StateFlow<Set<CallTarget>> = pendingTargets

    /**
     * Sends [call] in the bus's own scope and returns the pending result, which never fails (a failure is a result with
     * no HTTP status); null when a command for the same device is still out (nothing was sent). [tag] names the control
     * that asked, so two controls that send the same kind (snow melt and the update window both write the dish's
     * config) keep their own "last outcome".
     */
    fun launch(context: Context, call: DeviceCall, tag: String = call.kind.label): Deferred<DishCallResult>? {
        synchronized(lock) {
            if (call.target in pendingTargets.value) return null
            pendingTargets.value = pendingTargets.value + call.target
        }
        val app = context.applicationContext
        return scope.async {
            try {
                deliver(app, call, tag)
            } finally {
                pendingTargets.update { it - call.target }
            }
        }
    }

    private suspend fun deliver(context: Context, call: DeviceCall, tag: String): DishCallResult {
        val result = try {
            handler.get()?.handle(call) ?: sendDirect(context, call)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DishCallResult.failure(call.kind, SystemClock.elapsedRealtimeNanos(), 0, "-", "client: ${e.message}")
        }
        // The bookkeeping is the point of the bus: it is finished even if the scope is shut down right now, and a failure of
        // it never turns into an exception for whoever awaits the result (the command has been sent either way).
        withContext(NonCancellable) {
            val wallMs = System.currentTimeMillis()
            lastByTag.update {
                it + (tag to SentCommand(call.kind, result.outcome, (result.grpcMessage ?: result.error).orEmpty().take(MAX_MESSAGE), wallMs))
            }
            try {
                CapabilityStore.record(result, wallMs)
                CapabilityStore.flush()
            } catch (e: Exception) {
                // The book is a convenience; losing one entry is better than failing the command's screen.
            }
        }
        return result
    }

    /** Runs [call] on whichever client it needs; for the service and for the direct path. */
    internal fun execute(devices: Devices, call: DeviceCall): DishCallResult {
        val started = SystemClock.elapsedRealtimeNanos()
        return when (call.target) {
            CallTarget.DISH -> {
                val client = devices.dish()
                call.command?.let(client::send) ?: client.call(call.kind)
            }
            CallTarget.ROUTER -> {
                val router = devices.router()
                    ?: return DishCallResult.failure(call.kind, started, 0, "-", AppErrors.NO_ROUTER_ADDRESS)
                val command = call.command
                if (command == null) router.client.call(call.kind) else router.client.send(command as RouterCommand)
            }
        }
    }

    private suspend fun sendDirect(context: Context, call: DeviceCall): DishCallResult =
        withContext(Dispatchers.IO) { execute(Devices.shared(context), call) }

    private const val MAX_MESSAGE = 200
}
