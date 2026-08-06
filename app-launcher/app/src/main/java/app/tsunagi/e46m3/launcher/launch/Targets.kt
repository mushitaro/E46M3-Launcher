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

    /** The shipping TUNER build. Public HTTPS, so it needs nothing from us. */
    private const val TUNER_URL = "https://mss54hp-csl-convert-tuner.tsunagi.app/"

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
        // TUNER is deployed, public, MIT-licensed and has no provenance
        // constraint, so it can be reached today. It opens in a Custom Tab; the
        // moment assetlinks.json carries this app's signing fingerprint the same
        // step becomes a full-screen Trusted Web Activity with no toolbar.
        //
        // DIAG and 整備履歴 have no entry here on purpose. DIAG is 未配信 — it has
        // no remote at all — and 整備履歴 is not written. Their keys exist and are
        // visibly dead (ConsoleKeys.Face.DEAD); giving them a target that fails
        // at press time would be a worse lie than a switch that admits it is off.
        LaunchTarget(
            id = "tuner",
            labelRes = R.string.btn_tuner,
            steps = listOf(Step.Web(TUNER_URL)),
        ),
        LaunchTarget(
            id = "m_home",
            labelRes = R.string.btn_home,
            steps = listOf(Step.Internal),
        ),
    )

    fun byId(id: String): LaunchTarget? = ALL.firstOrNull { it.id == id }
}
