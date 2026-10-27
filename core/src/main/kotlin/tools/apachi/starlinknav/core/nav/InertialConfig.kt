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
    /**
     * The dish's GNSS regime (port-side, 2026-10-27; off in [CFG2]): Starlink-only accuracy, the switch back, slot windows
     * and the stall detector. A nested group: with its 25 parameters inline the data class would exceed the JVM's 255
     * parameter slots (a double takes two) and could not load. Read through the properties of the same names below;
     * change with `copy(so = so.copy(...))`.
     */
    val so: StarlinkOnlyConfig = StarlinkOnlyConfig(),
) {
    // [so], read as if its parameters were this class's own (what the filter's code and the KDoc of [StarlinkOnlyConfig] use)
    val soEnabled: Boolean get() = so.soEnabled
    val soAccM: Double get() = so.soAccM
    val soAccDvK: Double get() = so.soAccDvK
    val soAccDvWindowS: Double get() = so.soAccDvWindowS
    val soAccMaxM: Double get() = so.soAccMaxM
    val soDecayS: Double get() = so.soDecayS
    val soSwitchReanchorM: Double get() = so.soSwitchReanchorM
    val soSwitchHoldS: Double get() = so.soSwitchHoldS
    val soSwitchQuietS: Double get() = so.soSwitchQuietS
    val soWanderTauS: Double get() = so.soWanderTauS
    val soUnconvergedAccM: Double get() = so.soUnconvergedAccM
    val soStatusStaleS: Double get() = so.soStatusStaleS
    val soRSpeed: Double get() = so.soRSpeed
    val soHolds: Boolean get() = so.soHolds
    val soForwardAxis: Boolean get() = so.soForwardAxis
    val slotAware: Boolean get() = so.slotAware
    val slotStepBeforeS: Double get() = so.slotStepBeforeS
    val slotStepAfterS: Double get() = so.slotStepAfterS
    val slotWallSlackS: Double get() = so.slotWallSlackS
    val outageBeforeS: Double get() = so.outageBeforeS
    val outageAfterS: Double get() = so.outageAfterS
    val stallDetector: Boolean get() = so.stallDetector
    val stallFrozenS: Double get() = so.stallFrozenS
    val stallTwoPointS: Double get() = so.stallTwoPointS
    val stallAgeS: Double get() = so.stallAgeS
    val stallTurnDegS: Double get() = so.stallTurnDegS
    val stallSpeedMps: Double get() = so.stallSpeedMps

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
            so = StarlinkOnlyConfig(soEnabled = false, slotAware = false, stallDetector = false),
        )

        /** CFG2 with the shipped detector (h = 9 m, hold 25 s): what `nav2.py` runs for the port comparison. */
        val CFG2_H9 = CFG2.copy(cusumH = 9.0, holdMax = 25.0)
    }
}

/**
 * The dish's GNSS regime (port-side, 2026-10-27; off in [InertialConfig.CFG2]): Starlink-only accuracy, the switch back,
 * slot windows and the stall detector, as [InertialConfig.so]. Numbers from the 2026-10-26 drive (a phone, mock off; one
 * 5-min Starlink-only stretch with the phone's own GNSS as the reference, 296 fixes) and the 2026-10-24 drives (D1 67 %
 * valid, D2 jammed).
 */
data class StarlinkOnlyConfig(
    /**
     * Honest accuracy and the filter changes while the dish says its GNSS is invalid or inhibited (`gps_stats.gps_valid`
     * false or `inhibit_gps` true: the position comes from the Starlink link alone) and the re-anchor on the way back.
     * Off: the regime is still computed and reported, but no floor, no re-anchor and no change of the filter's inputs.
     */
    val soEnabled: Boolean = true,
    /**
     * The told 68 % radius while Starlink-only, m: the p68 of |AUTO − reference| over the 296 referenced Starlink-only
     * fixes (p50 54, p95 138; along p68 78, cross p68 17). A constant 60 m covered only 52 % of them.
     */
    val soAccM: Double = 85.0,
    /**
     * Weight of the speed-change term, m per m/s of |dish speed now − dish speed [soAccDvWindowS] ago|: a knob with
     * default 0, not a calibrated model (the Starlink-only error showed no usable dependence on the dish speed or its
     * change over 10-30 s: correlation −0.04 / −0.25 on ~5 effective samples).
     */
    val soAccDvK: Double = 0.0,
    /** The window of the speed-change term, s: the 10-30 s over which the note looked for a dependence (none found). */
    val soAccDvWindowS: Double = 30.0,
    /** Cap of the Starlink-only accuracy with the speed-change term, m. */
    val soAccMaxM: Double = 200.0,
    /**
     * After the dish's GNSS comes back the floor decays as [soAccM] · e^(−Δt / this), s: measured, the AUTO error slid
     * from ~75 m to 31 / 25 / 22 / 19 / 21 / 13 / 8 m at 0 / 1 / 2 / 3 / 5 / 10 / 20 s after `gps_valid` turned true
     * (no snap). With 15 s the floor is 70 m at 3 s (19 measured), 44 at 10 s (13), 22 at 20 s (8), 12 at 30 s, then
     * [accFloorM] takes over; nothing is left 5 decay times after the switch. 10 s (the first guess from those two
     * flips) covered the error in the 30 s after the slower of the two recorded switches only 57-61 % of the time (the
     * dish was still 13-15 m off at 17-45 s), 15 s covers it 89-93 %, 20 s 100 % but tells 41 m for a 15 m error.
     */
    val soDecayS: Double = 15.0,
    /**
     * At the switch back to dish GNSS the position covariance is loosened by this much (σ per axis, m) at the next AUTO
     * fix, so the dish's correction is taken without a gate fight: the chord across the flip had 13-25 m of excess on
     * the two recorded flips. Not applied on the way into Starlink-only (the wander grows slowly, τ ≈ 45 s).
     */
    val soSwitchReanchorM: Double = 25.0,
    /**
     * A sideways hold a gate reject opened this many s or less before the switch back is dropped with the re-anchor: the
     * dish flips between polls, so the first fix of the new regime can still carry the old flag (one fix of lag) and
     * its step was rejected against the Starlink-only track.
     */
    val soSwitchHoldS: Double = 2.0,
    /**
     * No sideways holds (acting detector or gate, in the TRUSTED regime too) for up to this long after the switch
     * back, s: the dish converges onto its GNSS fix over the measured slide (31 → 8 m in 20 s), so AUTO's sideways
     * motion in that window is the correction, not a fault, and a hold would keep the Starlink-only error while the
     * floor still covers it. Synthetic: a 15 m cross slide with τ 8 s fires the CUSUM (h ≈ 8 m·s with the adapted σ)
     * at 5-15 s after the flip. A deliberate addition to spec §5 ("GNSS: exactly today's behaviour plus the decaying
     * floor"): without it the spec's own test 3(b) opens a hold against the converging dish. The window is scaled by
     * what the Starlink-only dwell could have built (like the floor at the switch back, [soWanderTauS]): a flapping
     * dish (dwells of 1 s) gets next to no window, a stretch of a minute or more the full one. 0 = off.
     */
    val soSwitchQuietS: Double = 20.0,
    /**
     * Growth time of the Starlink-only wander, s (§0: τ_along ≈ 45 s, one recorded GNSS → Starlink-only flip, each fix
     * 1-2 m from the last). The floor at the switch back and the quiet window are [soAccM] / [soSwitchQuietS] times
     * min(1, dwell / this) (plus what a previous switch back still had to decay): after a 1 s dwell the dish is a
     * metre or two from its GNSS solution, not 85 m.
     */
    val soWanderTauS: Double = 45.0,
    /**
     * Floor of the told accuracy while `pnt_filter_convergence_state` says UNCONVERGED (1), whatever the regime, m: the
     * 68 boot samples of the 2026-10-26 drive were the only ones, all with `gps_valid` false.
     */
    val soUnconvergedAccM: Double = 150.0,
    /**
     * Without a status carrying gps_stats for this long the reported regime is UNKNOWN (the floor of the last known
     * regime stays), s: the poller drops to one call per 3 s while the dish is unreachable, so 5 s would flap.
     */
    val soStatusStaleS: Double = 10.0,
    /**
     * σ of the dish speed measurement while Starlink-only, m/s, instead of [rSpeed]: its error was −5.1..+5.6 m/s
     * (p5..p95, σ ≈ 3.3) against the reference, following the true acceleration with gain 0.3 over 3-8 s. The speed
     * state's variance becomes honest (steady state σ_v ≈ 2 m/s), so dead reckoning's accuracy grows as it should.
     */
    val soRSpeed: Double = 3.5,
    /**
     * Sideways holds while Starlink-only (the user asked for the sideways correction in the jammed case). False: the
     * acting detector's confirm is downgraded to none and a gate reject never opens a hold while Starlink-only (only
     * the [nReanchor]-reject re-anchor); the evaluation reports both.
     */
    val soHolds: Boolean = true,
    /**
     * Learn the phone's forward axis from the dish speed while Starlink-only. False: that speed is a lagging filter
     * state (gain 0.3 over 3-8 s) and the fit would learn garbage. The ZUPT stays (it also needs the IMU still).
     */
    val soForwardAxis: Boolean = false,
    /**
     * Slot-aware gating while Starlink-only: a step whose fix time lies in the first [slotStepAfterS] (or the last
     * [slotStepBeforeS]) of a 15 s GPS-time slot, or near a `did_switch` outage, is re-anchoring, not a fault. The
     * Starlink-only solution steps preferentially in the first ~3 s after a GPS-time multiple of 15 s (persistent level
     * changes of 15-20 m median, 71 % corrective) and the big snaps (100-600 m) lie within −5..+15 s of an outage start
     * carrying `did_switch`; with dish GNSS valid the boundary statistic is flat.
     */
    val slotAware: Boolean = true,
    /** The boundary window reaches this far before a 15 s GPS-time multiple, s: a margin for the slot clock's own error. */
    val slotStepBeforeS: Double = 0.5,
    /** ... and this far after it, s: the steps lie in the first ~3 s after the boundary (§0 slot statistics). */
    val slotStepAfterS: Double = 3.0,
    /** Widening of the slot windows on both sides while the slot clock comes from the phone's wall clock (may be ~1 s off), s. */
    val slotWallSlackS: Double = 1.0,
    /** A fix this long before the start of a `did_switch` outage is near it, s (the big snaps lie within −5..+15 s of the start). */
    val outageBeforeS: Double = 5.0,
    /** ... and this long after its end, s (the same −5..+15 s statistic; the status reports an outage 0.7-1.7 s after its start). */
    val outageAfterS: Double = 15.0,
    /**
     * The stall detector (every regime): a dish that serves the same position for [stallFrozenS] while the car moves, two
     * positions in alternation over [stallTwoPointS], or a fix clock (`gps_time_s`) that stopped advancing for
     * [stallAgeS] over three diagnostics is refused and the filter dead-reckons until it moves again. The Mini never
     * stalled on any recorded drive (the signatures come from other dishes): the detector must stay quiet there.
     */
    val stallDetector: Boolean = true,
    /**
     * The dish computes a solution every ~1.08 s and the app asks every second, so one repeat every ~12 s is normal and
     * two in a row rare; this many s of the same position while moving is not, s.
     */
    val stallFrozenS: Double = 5.0,
    /** Two positions in alternation (≤ 2 distinct, ≥ 3 changes, ≥ 6 answers) over at least this long, s. */
    val stallTwoPointS: Double = 8.0,
    /**
     * The fix clock not advancing while the receipts advanced by more than this over ≥ 3 diagnostics, s. Never from the
     * age's level: the phone's wall clock may be off by any amount with no receiver clock (the jammed case).
     */
    val stallAgeS: Double = 10.0,
    /** "Moving" for the detector: a dish heading rate above this, °/s, ... */
    val stallTurnDegS: Double = 3.0,
    /** ... or the latest dish speed, or the filter's speed state, at or above this, m/s (or the IMU not still). */
    val stallSpeedMps: Double = 2.0,
)

internal const val D2R = Math.PI / 180.0
internal const val R2D = 180.0 / Math.PI
internal fun sq(x: Double) = x * x
