package tools.apachi.starlinknav.core.nav

/**
 * Every parameter of the inertial filter (prototype v2, `CFG2` of `nav2.py`, spec `PORTING2.md` §11), in SI
 * units inside the filter (m, s, rad, m/s, m/s²) and degrees where the name says DEG. [SHIPPED] is what the app
 * runs: CFG2 with the conservative acting detector (CUSUM h = 9 m, hold ≥ 25 s; decision 2026-10-15) plus the
 * port-side fixes from the v2 audit and the exit policy of the sideways hold. [FIXED_HOLD] is SHIPPED without that
 * exit policy (a fixed 25 s hold). [CFG2] is the prototype exactly (the test vectors were made with it).
 */
data class InertialConfig(
    val dt: Double = 0.1,
    val nb: Int = 60,
    // process noise (continuous)
    val qPos: Double = 1.0,
    val qPosCrDegr: Double = 0.02,
    val qVImu: Double = 1.0,
    val qVFree: Double = 2.0,
    val qPsiGyro: Double = sq(0.15 * D2R),
    val kScale: Double = 0.04,
    val qPsiDishRate: Double = sq(2.0 * D2R),
    val qPsiFree: Double = sq(6.0 * D2R),
    val qBa: Double = sq(0.02),
    val qBg: Double = sq(0.003 * D2R),
    // dish error state b (east/north, decomposed in the car frame each tick)
    val sigB: Double = 1.0,
    val tauB: Double = 60.0,
    val sigBOpenCr: Double = 40.0,
    val tauBOpen: Double = 60.0,
    val tauWalk: Double = 15.0,
    val openInitCr: Double = 15.0,
    val bClose: Double = 2.5,
    val nClose: Int = 3,
    val minOpen: Double = 3.0,
    val holdMax: Double = 25.0,
    val tRev: Double = 30.0,
    val gate: Double = 18.42,
    val nReanchor: Int = 3,
    /**
     * A gate reject opens a sideways hold only with a mount offset. Without one the filter has no car heading from the
     * dish, and a hold ran on its own heading: on 2026-10-24 (dish GNSS jammed) the held courses were 19-86° off the
     * dish heading and up to 256 m from AUTO, which kept to that heading. Without a mount the reject only re-anchors
     * to AUTO after [nReanchor] rejects, as for an along-track jump. False = the prototype.
     */
    val gateHoldNeedsMount: Boolean = true,
    // measurements
    val rAl: Double = 1.0,
    val rCr: Double = 1.0,
    val rAlBrakeK: Double = 2.0,
    val rgAl: Double = 3.0,
    val rgCr: Double = 2.5,
    val rgBrakeK: Double = 2.0,
    val rHeadMinDeg: Double = 0.4,
    val rSpeed: Double = 0.5,
    val speedSkipDecel: Double = -1.0,
    val speedLag: Double = 1.0,
    val zuptSig: Double = 0.05,
    val zuptHs: Double = 0.5,
    val zuptGyroDeg: Double = 2.0,
    val defaultLatency: Double = 0.45,
    val latMin: Double = 0.0,
    val latMax: Double = 1.2,
    // sideways detector (dish data only)
    val detMinSpeed: Double = 3.0,
    val cusumK: Double = 0.30,
    /** Threshold of the ACTING detector (opens the dish-error state), m at σv = [sigvRef]. */
    val cusumH: Double = 9.0,
    val sigvRef: Double = 0.25,
    val sigvInit: Double = 0.25,
    val sigvMin: Double = 0.15,
    val sigvMax: Double = 0.6,
    val sigvTauN: Double = 300.0,
    val healthH: Double = 0.6,
    val satsMin: Int = 7,
    /** The acting detector at all (false = `DET=False` of the prototype: dead reckoning only). */
    val detector: Boolean = true,
    /** Threshold of the WATCH-ONLY detector (never changes the state, logs `watch_suspect`), m; null = none. */
    val watchCusumH: Double? = 6.0,
    // mount calibrator
    val mntChordMinS: Double = 3.0,
    val mntChordMaxS: Double = 6.0,
    val mntMinSpeed: Double = 6.0,
    val mntStraightDeg: Double = 3.0,
    val mntN: Int = 15,
    val mntMadDeg: Double = 1.5,
    val mntRelearnN: Int = 15,
    val mntRelearnDiffDeg: Double = 5.0,
    val mntTrustS: Double = 30.0,
    val mntKeep: Int = 100,
    /**
     * Chords up to this long, the longest straight one (a longer chord's course is less noisy); at or below
     * [mntChordMaxS] the prototype's choice (the shortest chord of [mntChordMinS]..[mntChordMaxS]).
     */
    val mntLongChordS: Double = 15.0,
    /**
     * The value is taken once its median is this precise (standard error from the MAD and the sample count, degrees)
     * with a MAD up to [mntMadMaxDeg]; null = the prototype's rule (MAD ≤ [mntMadDeg]). The dish without GNSS gives
     * a noisier AUTO: on 2026-10-24 (29 min, dish GNSS jammed throughout) the MAD stayed above 1.5° and nothing
     * was learned, so the sideways correction never ran; 0.7° is what the prototype's rule allows at its minimum count.
     */
    val mntSeDeg: Double? = 0.7,
    val mntMadMaxDeg: Double = 4.0,
    /** No samples while reversing: the course is then opposite the heading (180° off the mount). False = the prototype. */
    val mntSkipReversing: Boolean = true,
    // IMU front end
    val gTau: Double = 20.0,
    val gTauFast: Double = 1.0,
    val gFastS: Double = 5.0,
    val gyroReadyAfter: Double = 5.0,
    val handleExcessDeg: Double = 30.0,
    val handleHold: Double = 3.0,
    // forward-axis epoch fit
    val forwardAxis: Boolean = true,
    val faLag: Double = 1.0,
    val faTau: Double = 200.0,
    val faNMin: Int = 40,
    val faSdDv: Double = 0.25,
    val faSdLat: Double = 0.20,
    val faRms: Double = 0.45,
    val faKMin: Double = 0.60,
    val faKMax: Double = 1.25,
    val faStableDeg: Double = 4.0,
    val faNStab: Int = 10,
    val faMinV: Double = 3.0,
    val accDb: Double = 0.5,
    // output
    val drAfter: Double = 2.5,
    val maxAccPush: Double = 300.0,
    val maxCrossPush: Double = 50.0,
    // ---- port-side changes (not in the prototype; off in [CFG2]) ----
    /**
     * Reported accuracy never below this while a position is served (audit item 4: AUTO's own error), m.
     * Android's accuracy is a 68 % radius; on drives 1-3 a 7 m floor covered the real error on 80 / 72 / 47 %
     * of serves (66 % pooled; 5 m: 49 %). The dish's sigma_m (~1.6 m) does not track the error.
     */
    val accFloorM: Double = 7.0,
    /** While the dish-error state is open (or walking back), accuracy >= |sideways offset| + its 1σ (audit item 4). */
    val accOpenOffset: Boolean = true,
    /** After a watchdog reset serve nothing until a fresh AUTO fix re-initialises (audit item 8). */
    val noRawAfterReset: Boolean = true,
    /** The start-up raw fix (before the first initialisation) is served only while younger than this, s. */
    val rawMaxAgeS: Double = 2.5,
    /**
     * No forward dead reckoning while reversing (with or without AUTO) or slower than [holdSpeed] in an AUTO gap
     * (audit item 10): the position is held, its covariance and the told accuracy grow at [holdAccRate] (while
     * reversing at least the dish speed), and while reversing the dish speed is not used as forward speed.
     */
    val reverseHold: Boolean = true,
    val holdSpeed: Double = 2.0,
    /** Accuracy growth while holding, m/s (what a car could have moved slowly or backwards). */
    val holdAccRate: Double = 3.0,
    /** AUTO chord against the car heading: reversing above this angle, forward again below [reverseOffDeg]. */
    val reverseOnDeg: Double = 120.0,
    val reverseOffDeg: Double = 60.0,
    /** Minimum AUTO chord speed and dish speed for the reversing test, m/s. */
    val reverseMinChordSpeed: Double = 1.0,
    /**
     * A car does not reverse for [flipS] s at [flipSpeed] m/s or more: "reversing" that long and fast means the mount
     * offset in use is 180° wrong (the dish was turned round since it was learned, 2026-10-26: both phones carried
     * +180° from an earlier drive, the filter held "reversing" for the whole drive, dead reckoning stood still and the
     * sideways holds ran 100 m beside the road). The offset is then flipped, the filter's heading with it, the
     * corrected value is reported as learned, and reversing is re-decided from fresh chords.
     */
    val mountFlip: Boolean = true,
    val flipSpeed: Double = 8.0,
    val flipS: Double = 20.0,
    /** Network (cell/Wi-Fi) fixes as a weak position input while dead reckoning. */
    val network: Boolean = true,
    /** Floor of the network fix σ per axis (σ = accuracy / 1.51), m: drive 2's network fixes were 40-180 m off. */
    val netSigMin: Double = 60.0,
    /** Network fixes with a larger 68 % radius are ignored (cell-tower fixes), m. */
    val netMaxAcc: Double = 150.0,
    /** χ²₂ gate of a network fix (99.9 %). */
    val netGate: Double = 13.82,
    /** The state is NETWORK_AIDED while the last accepted network fix is at most this old, s. */
    val netAidedS: Double = 30.0,
    /** Re-centre the local plane when the position is this far from its origin, m (equirectangular scale error). */
    val rebaseM: Double = 2000.0,
    /**
     * IMU samples more than this far ahead of the facade's clock (the latest dish call, serve or network fix) are
     * dropped: one bogus timestamp must not run the tick clock ahead, s.
     */
    val imuMaxAheadS: Double = 2.0,
    /** The mount-learned callback fires again when the learned offset moved this far from the last one reported, degrees. */
    val mntPersistDiffDeg: Double = 0.3,
    // ---- exit policy of the sideways hold (port-side, 2026-10-15; off in [CFG2] and [FIXED_HOLD]) ----
    /**
     * Keep holding past [holdMax] while the sideways offset is still significant against the dead reckoning's
     * cross-track uncertainty: |b_cross| ≥ [holdSigK] · ([holdSig0] + [holdSigRate] · seconds since the opening),
     * at most [holdCeilS] after the opening. (The EKF's own cross-track σ stays ~2 m in a hold while the held track
     * drifts 0.1-0.2 m/s from the dish's on drives 1-3, so the uncertainty here is that measured growth.) The ceiling
     * was 120 s; 60 s since the review (2026-10-15): a fault whose onset was never seen (slow drift) has no reference,
     * so the dish's return looks like a fault and the ceiling alone bounds how long the correct dish is held.
     */
    val holdSig: Boolean = true,
    val holdSigK: Double = 2.0,
    val holdSig0: Double = 2.0,
    val holdSigRate: Double = 0.1,
    val holdCeilS: Double = 60.0,
    /**
     * No extension past [holdMax] for a hold opened within this many s after the previous hold ended (walk back, close
     * or return): that opening is most likely the dish's return after the reference was lost, and its new reference
     * would agree with the faulty track the filter holds (review 2026-10-15: 30 m held 120 s on the correct dish).
     */
    val holdSigAfterHoldS: Double = 180.0,
    /**
     * Nor for a hold opened less than this many s after AUTO came back from a gap longer than [retGapS] (the filter's
     * start does not count): a fault that began inside the gap was taken as the track, so the dish's return would look
     * like a fault.
     */
    val holdSigTrackS: Double = 60.0,
    /**
     * The dish's return: from the opening on, AUTO's offset from the fault-free track is tracked without the filter's
     * position: the offset at the opening (for a detector opening, AUTO's sideways move over the detector's run, so a
     * ramp the filter partly followed counts in full) plus AUTO's own sideways motion against the dish heading since
     * (the chords' cross-track parts, corrected by the chords' mean drift in trusted driving). When AUTO then moves
     * sideways toward that track (one chord covering at least [retFrac] of the offset, or a detector confirm of the
     * opposite sign) and has come back by at least [retFrac] of the offset to within max([retMinM], [retK] · σ) of
     * it (after a one-chord jump: to within [retFrac] of the offset, as the reference drifts more than σ in turns),
     * AUTO is taken back at once (dish-error state reset, position re-anchored to AUTO; `dish_returning
     * cause=reference`), whether the hold is still open, walking back or over; a confirm on the way back that has not
     * arrived yet only keeps the detector from opening a new hold (`cause=reference_partial`).
     * A one-chord jump that lands short of the reference (more than max([retMinM], [retK] · σ) from it,
     * `cause=reference_loose`) takes AUTO but keeps the reference: the told accuracy stays ≥ the offset it still shows,
     * a later rise on the same side is the same fault (the hold then starts from the reference's track,
     * `onto_reference_m`), and only the drop onto the reference ends it.
     * σ² = [retSigM]² + (tan([retHeadBiasDeg]) · distance driven since the opening)². The reference is dropped after
     * an AUTO gap longer than [retGapS] or a missing dish heading, at an early close that agrees with it, [refMaxS]
     * after the hold ended, or when [retK] · σ exceeds the offset (then [retMirror] applies). While the filter follows
     * AUTO with a live reference that still shows ≥ [retMinM] of offset, the told accuracy is at least that offset.
     */
    val returnRule: Boolean = true,
    val retFrac: Double = 0.5,
    val retMinM: Double = 4.0,
    val retK: Double = 2.0,
    val retSigM: Double = 1.5,
    val retHeadBiasDeg: Double = 0.3,
    val refMaxS: Double = 180.0,
    /**
     * An AUTO gap up to this long (fix times) does not end the reference: the chord across it, against the dish heading
     * at its middle, carries the sideways motion over (a constant turn's chord points along the middle heading).
     */
    val retGapS: Double = 6.0,
    /**
     * After a reference ended without a return (expired, a longer gap, no dish heading), a new opening whose jump
     * mirrors that fault's onset (opposite sign, size within [retMirrorFrac] of it, within [retMirrorS] of the onset)
     * is the dish's return: AUTO is taken at once (`dish_returning cause=mirror`) instead of a new hold.
     */
    val retMirror: Boolean = true,
    val retMirrorFrac: Double = 0.35,
    val retMirrorS: Double = 600.0,
    /**
     * The reference's chords are corrected by the mean sideways drift of trusted AUTO chords against the dish heading
     * (mount offset or dish heading slightly off), learned over about [retBiasHorizonM] of driving, used after [retBiasMinM].
     */
    val retBiasHorizonM: Double = 3000.0,
    val retBiasMinM: Double = 500.0,
    /** The chord drift correction is capped at this angle (a slow undetected fault is not mount bias), degrees. */
    val retBiasMaxDeg: Double = 0.3,
    /** A hold goes on past [holdMax] only while the held offset and the reference's agree within max(this, retK · σ), m. */
    val holdConsM: Double = 4.0,
    /**
     * While the dish-error state is open, turning makes the along-track part of the dish error uncertain (a sideways
     * error does not stay fixed in the east/north plane when the road turns): its variance grows by
     * [leakK] · |b|² · |Δψ| per tick, so AUTO's along-track innovation in a turn goes into the dish error instead of
     * pulling the held position onto the faulty track. Also no reversing votes from AUTO chords while open.
     * Off: it cut the leak of injected faults that turn with the car, but held drive 3's real episode (454 s) 5 m
     * worse and moved its clean p95 from 12.0 to 16.8 m even with a 25 s hold (2026-10-15); kept for experiments.
     */
    val leakGuard: Boolean = false,
    val leakK: Double = 1.0,
) {
    companion object {
        /** The app's configuration (decision 2026-10-15, exit policy of the sideways hold added the same day). */
        val SHIPPED = InertialConfig()

        /** SHIPPED before the exit policy: a fixed 25 s hold, then the walk back (for comparisons). */
        val FIXED_HOLD = SHIPPED.copy(holdSig = false, returnRule = false)

        /** The prototype `CFG2` exactly: h = 6 m, hold 15 s, no watch-only detector, no port-side changes. */
        val CFG2 = InertialConfig(
            cusumH = 6.0, holdMax = 15.0, watchCusumH = null,
            accFloorM = 0.0, accOpenOffset = false, noRawAfterReset = false, rawMaxAgeS = Double.POSITIVE_INFINITY,
            reverseHold = false, network = false, rebaseM = Double.POSITIVE_INFINITY,
            holdSig = false, returnRule = false, leakGuard = false,
            mntLongChordS = 6.0, mntSeDeg = null, mntSkipReversing = false, gateHoldNeedsMount = false, mountFlip = false,
        )

        /** CFG2 with the shipped detector (h = 9 m, hold 25 s): what `nav2.py` runs for the port comparison. */
        val CFG2_H9 = CFG2.copy(cusumH = 9.0, holdMax = 25.0)
    }
}

internal const val D2R = Math.PI / 180.0
internal const val R2D = 180.0 / Math.PI
internal fun sq(x: Double) = x * x
