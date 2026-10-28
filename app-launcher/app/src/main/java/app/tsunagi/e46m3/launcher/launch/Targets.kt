package app.tsunagi.e46m3.launcher.launch

import android.provider.Settings
import app.tsunagi.e46m3.launcher.R

/**
 * THE button → intent table. The single source of truth for every component
 * name in this app.
 *
 * Ordering notes that are not obvious and cost real debugging to establish:
 *
 * RADIO and VIDEO deliberately lead with what looks like the "wrong" activity.
 * `/sdcard/Iconfig/Iconfig.ini` on this unit records the OEM menu's own enable
 * flags, and they contradict the obvious choice:
 *
 *     com.ts.main.radio.RadioMainActivity,0     <- DISABLED on this unit
 *     com.ts.can.CanExRadioActivity,1           <- ENABLED
 *     com.ts.main.Media.DvdMainActivity,0       <- DISABLED
 *     com.ts.main.Media.USBMainActivity,1       <- ENABLED
 *
 * Starting a screen the OEM has configured off tends to yield a dead or
 * misconfigured UI, so the enabled one leads. The flags could be stale defaults,
 * which is exactly why both are present and why [TargetLauncher] remembers
 * whichever step actually wins.
 *
 * EQ leads with com.ts.set.dsp.SetDspMainActivity, which is probably NOT
 * reachable: it exists in MainUI's manifest string pool, but it is absent from
 * the 39 activities dumpsys shows in the action.MAIN bucket — and that bucket is
 * effectively MainUI's exported whitelist. It stays first because it is the
 * nicest destination if it happens to work; SetMainActivity (verified present in
 * the bucket) is the guaranteed catch.
 */
object Targets {

    private const val MAIN_UI = "com.ts.MainUI"

    // ── The M tools ──────────────────────────────────────────────────────
    // The WORKS builds, not the public ones (operator, 2026-10-28). WORKS is
    // where work on the car happens (tsunagi-m-release §1), and this console is
    // in the car.
    //
    // Both sit behind m3's owner gate, so the first launch on a unit asks for
    // sign-in; Chrome remembers it afterwards. The gate also decides whether
    // these open FULL SCREEN: it must serve /.well-known/assetlinks.json to a
    // request with no session, or Chrome silently falls back to a toolbar and
    // says nothing anywhere (docs/04 §10.5). Measured 2026-10-28, both WORKS
    // URLs answered 401 there — see docs/04 for what has to land first.

    /** MSS54HP CSL CONVERT /// TUNER — WORKS. MAPPING category. */
    private const val TUNER_URL = "https://mss54hp-csl-convert-tuner-preview.pages.dev/"

    /** E46M3 /// MONITORING — WORKS. MONITORING category; WORKS only, no public build. */
    private const val MONITORING_URL = "https://e46m3-monitoring-preview.pages.dev/"

    val ALL: List<LaunchTarget> = listOf(
        LaunchTarget(
            id = "settings",
            labelRes = R.string.btn_settings,
            steps = listOf(
                Step.Component("com.android.settings", "com.android.settings.Settings"),
                Step.Implicit(Settings.ACTION_SETTINGS),
                Step.PackageMain("com.android.settings"),
            ),
        ),
        LaunchTarget(
            id = "android_link",
            labelRes = R.string.btn_android_link,
            steps = listOf(
                Step.PackageMain("net.easyconn"),
            ),
        ),
        // ⚠ CarPlay cannot be reached with PackageMain, and this cost a trip to
        // the car to find out. `com.ts.carplayapp` declares its entry point as
        //
        //     Action:   android.intent.action.MAIN
        //     Category: android.intent.category.DEFAULT
        //     Category: android.intent.category.MYLAUNCHER      <- not LAUNCHER
        //
        // `getLaunchIntentForPackage` looks for MAIN+LAUNCHER (or MAIN+INFO) and
        // finds neither, so it returns null and the step never even attempts a
        // start — the key dimmed itself and reported NO_INTENT. The vendor uses
        // its own MYLAUNCHER category throughout (MainUI alone declares 35), so
        // an explicit component is the only reliable route. Phase 0 guessed the
        // class right and the package wrong: it is com.ts.carplayapp, not
        // com.autochips.carplayapp.
        LaunchTarget(
            id = "carplay",
            labelRes = R.string.btn_carplay,
            steps = listOf(
                Step.Component("com.ts.carplayapp", "com.autochips.carplayapp.LoadingActivity"),
                Step.PackageMain("com.ts.carplayapp"),
                Step.PackageMain("com.autochips.carplay"),
            ),
        ),
        LaunchTarget(
            id = "bluetooth",
            labelRes = R.string.btn_bluetooth,
            steps = listOf(
                Step.Component(MAIN_UI, "com.ts.bt.BtConnectActivity"),
                Step.Component(MAIN_UI, "com.ts.bt.BtMusicActivity"),
                Step.Implicit(Settings.ACTION_BLUETOOTH_SETTINGS),
            ),
        ),
        // ⚠ The Iconfig.ini reading was wrong, and the car said so.
        //
        // /sdcard/Iconfig/Iconfig.ini records `RadioMainActivity,0` and
        // `CanExRadioActivity,1`, which was read as "0 = disabled, 1 = enabled"
        // and used to put CanExRadio first. On the unit, CanExRadio starts
        // without throwing — so it is recorded as the winner — and then does
        // nothing at all: no screen, no audio. RadioMainActivity is the actual
        // FM/AM tuner, presets and all. Whatever those flags mean, it is not
        // that, and an inference from a config file lost to an experiment.
        //
        // CanExRadio is not kept as a fallback. A step that succeeds silently
        // while doing nothing is worse than no step: it would be remembered as
        // the winner and hide the real failure. If the tuner ever stops
        // resolving, the notice line saying RADIO UNAVAILABLE is the honest
        // outcome.
        LaunchTarget(
            id = "radio",
            labelRes = R.string.btn_radio,
            steps = listOf(
                Step.Component(MAIN_UI, "com.ts.main.radio.RadioMainActivity"),
            ),
        ),

        // EQ. `com.ts.set.dsp.SetDspMainActivity` is gone from this list: it is
        // not a registered activity on this unit at all, only a string in the
        // manifest pool, and it answered SECURITY when pressed.
        //
        // SettingSoundActivity IS the equaliser — ten bands, presets, the lot —
        // but it carries no intent-filter, so it is very probably not exported
        // to us either. It leads anyway: the fallback below costs one caught
        // exception, TargetLauncher remembers whichever wins, and the prize for
        // being wrong about "probably" is landing on the real EQ instead of one
        // tap short of it.
        //
        // SetMainActivity is the OEM settings root; 音声オプション is the second
        // entry. musicfx is dropped — it is not installed on this unit.
        LaunchTarget(
            id = "eq",
            labelRes = R.string.btn_eq,
            steps = listOf(
                Step.Component(MAIN_UI, "com.ts.set.SettingSoundActivity"),
                Step.Component(MAIN_UI, "com.ts.set.SetMainActivity"),
            ),
        ),
        LaunchTarget(
            id = "video",
            labelRes = R.string.btn_video,
            steps = listOf(
                Step.Component(MAIN_UI, "com.ts.main.Media.USBMainActivity"),
                Step.Component(MAIN_UI, "com.ts.main.Media.DvdMainActivity"),
                Step.PackageMain("com.ts.dvdplayer"),
            ),
        ),
        LaunchTarget(
            id = "maps",
            labelRes = R.string.btn_maps,
            steps = listOf(
                Step.PackageMain("com.google.android.apps.maps"),
                Step.Implicit("android.intent.action.VIEW", "geo:0,0"),
            ),
        ),
        LaunchTarget(
            id = "apps",
            labelRes = R.string.btn_apps,
            steps = listOf(Step.Internal),
        ),
        LaunchTarget(
            id = "m",
            labelRes = R.string.btn_m,
            steps = listOf(Step.Internal),
        ),

        // ── M mode ───────────────────────────────────────────────────────────
        // Two web tools, both WORKS builds, both opened through Chrome as
        // Trusted Web Activities. Full screen depends on a two-sided Digital
        // Asset Links statement: this app's half is @string/asset_statements,
        // and each origin must serve /.well-known/assetlinks.json naming this
        // package and signing certificate — to an anonymous request.
        //
        // 整備履歴 still has no entry here, and its socket (3,0) stays a blanking
        // plate. It is not written, and giving a key a target that fails at
        // press time is a worse lie than a socket that admits it is empty.
        LaunchTarget(
            id = "tuner",
            labelRes = R.string.btn_tuner,
            steps = listOf(Step.Web(TUNER_URL)),
        ),
        LaunchTarget(
            id = "monitoring",
            labelRes = R.string.btn_monitoring,
            steps = listOf(Step.Web(MONITORING_URL)),
        ),
        LaunchTarget(
            id = "m_home",
            labelRes = R.string.btn_home,
            steps = listOf(Step.Internal),
        ),
    )

    fun byId(id: String): LaunchTarget? = ALL.firstOrNull { it.id == id }
}
