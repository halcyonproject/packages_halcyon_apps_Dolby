# HalcyonDolby

`HalcyonDolby` is a privileged `system_ext` Android app (package `org.hlcyn.dolby`) that controls a device's Dolby DAP `AudioEffect` without hardcoding vendor parameters in app code. Device trees supply HAL behavior, profile mappings, and XML fallback defaults through a Runtime Resource Overlay (RRO) package in `/vendor/overlay`.

## Prerequisites

Before creating an overlay, confirm three pieces in your device tree:

1. A working Dolby DAP audio effect binary on vendor (for example, `libswdapaidl.so` on MediaTek or Qualcomm DAX3 HALs) is loaded in `audio_effects.xml` or the AIDL audio effects config.
2. Your vendor Dolby profile config (`/vendor/etc/dolby/dax-default.xml`) is present on the device.
3. `HalcyonDolby` is included in `PRODUCT_PACKAGES` in your device makefile.

## How overlays configure HalcyonDolby

A vendor RRO targets `org.hlcyn.dolby` and overrides booleans, integers, or string arrays from `res/values/config.xml` and `res/values/arrays.xml`. Keeping device tuning inside a `vendor: true` overlay lets one `HalcyonDolby.apk` ship unchanged across Qualcomm, MediaTek, and Motorola HALs while matching each device's `dax-default.xml`.

## RRO configuration hooks

All hooks in `res/values/config.xml` default to safe no-op values so unmodified devices stay untouched.

| Resource | Type | Default | When to override |
|---|---|---|---|
| `config_dolby_disable_audio_effect` | `bool` | `false` | Set `true` only if your HAL handles `AudioEffect.setEnabled(false)` cleanly without dying on drain; leave `false` on MediaTek `libswdapaidl.so` |
| `config_dolby_force_ieq_reapply` | `bool` | `false` | Set `true` when `dax-default.xml` pairs `<ieq-enable value="false"/>` with `<include ieq_preset="..."/>`, so selecting an already-stored preset toggles through `0` first |
| `config_dolby_port_stereo_widening` | `bool` | `false` | Set `true` if your HAL requires `setProfilePortParameter(0x2000000)` on `PORT_HEADPHONE` alongside `STEREO_WIDENING_AMOUNT` (`113`) |
| `config_dolby_min_ieq_amount` | `integer` | `0` | Set above `0` (such as `6` or `8`) when enabling an IEQ preset has no audible effect because vendor XML sets `ieq-amount` to `0` |
| `config_dolby_profile_defaults` | `string-array` | empty | Populate with per-profile defaults when your HAL registers are write-only and return `0` on `getDapParameter*` |

Each item in `config_dolby_profile_defaults` uses the format `"profileId:ieq,dialogue,bass,stereo,volume,hpVirt,spkVirt"`:

```xml
<item>0:0,3,false,4,true,false,false</item>
```

Field order is `profileId` (base ID from `dax-default.xml`), `ieqPreset` (`0`..`3`), `dialogueEnhancerAmount` (`0`..max), `bassEnhancerEnabled` (`true`/`false`), `stereoWideningAmount` (within `dolby_stereo_values`), `volumeLevelerEnabled` (`true`/`false`), `headphoneVirtEnabled` (`true`/`false`), and `speakerVirtEnabled` (`true`/`false`).

You can also override `dolby_profile_values`, `dolby_profile_entries`, `dolby_ieq_values`, and `dolby_ieq_entries` in `res/values/arrays.xml`. `HalcyonDolby` maps localized labels and icons from token strings instead of numeric profile IDs:

- Profile entry tokens: `dynamic`, `video`, `music`, `game`, `voice`
- IEQ entry tokens: `off`, `detailed`, `balanced`, `warm`

## Inspecting your vendor HAL and dax-default.xml

Run these checks against your firmware dump or pulled `/vendor` files before writing an overlay.

1. Check whether `libswdapaidl.so` uses the AIDL `State::DRAINING` path that fails with `-61 (-ENODATA)` on `AudioEffect.setEnabled(false)`:

```bash
strings vendor/lib64/soundfx/libswdapaidl.so | grep -E "State::IDLE|State::DRAINING|AHAL_DlbDapSwEffectImpl"
```

If `State::DRAINING` or `AHAL_DlbDapSwEffectImpl` is present, keep `config_dolby_disable_audio_effect` as `false`.

2. List the built-in profile IDs and check for `<ieq-enable value="false"/>` paired with `<include ieq_preset="..."/>` in `dax-default.xml`:

```bash
grep -n '<profile ' vendor/etc/dolby/dax-default.xml
grep -nE '<profile |ieq-enable|ieq-amount|ieq_preset=' vendor/etc/dolby/dax-default.xml | head -n 45
```

If the Voice profile uses ID `3` instead of `8` (or if your XML includes a `Game` profile), override `dolby_profile_values` and `dolby_profile_entries` in `res/values/arrays.xml`.

## Device examples

The table below reflects verified vendor dumps and trees across four targets.

| Device | `dolby_profile_values` | `config_dolby_disable_audio_effect` | `config_dolby_force_ieq_reapply` | Other overrides |
|---|---|---|---|---|
| `beryl` (Xiaomi MTK) | default (`0,1,2,8`) | `false` | `true` | `config_dolby_profile_defaults` from `dax-default.xml` |
| `tanzanite` (Xiaomi MTK) | `0,1,2,3` (`dynamic,video,music,voice`) | `false` | `true` | `config_dolby_profile_defaults` for IDs `0,1,2,3` |
| `rodin` (Xiaomi MTK) | default (`0,1,2,8`) | `false` | `true` | `config_dolby_profile_defaults` from `dax-default.xml` |
| Moto (`Sedona61`-style) | per XML (for example `0,1,2,3` with `game`) | `true` | `true` if XML has `ieq-enable=false` | `config_dolby_port_stereo_widening=true`, `config_dolby_min_ieq_amount` if XML is `0` |

## Adding a device RRO

Create `overlays/DolbyOverlay<Device>/` inside your device tree (`device/<vendor>/<codename>/`).

`Android.bp`:

```bp
runtime_resource_overlay {
    name: "DolbyOverlayExample",
    vendor: true,
}
```

`AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="org.hlcyn.dolby.example.overlay">
    <application android:hasCode="false" />
    <overlay
        android:targetPackage="org.hlcyn.dolby"
        android:isStatic="true"
        android:priority="100" />
</manifest>
```

`res/values/config.xml` (include only the hooks your device needs):

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <bool name="config_dolby_disable_audio_effect">false</bool>
    <bool name="config_dolby_force_ieq_reapply">true</bool>
    <string-array name="config_dolby_profile_defaults" translatable="false">
        <item>0:0,3,false,24,true,false,false</item>
        <item>1:0,7,false,24,true,false,true</item>
        <item>2:2,0,false,4,true,false,false</item>
        <item>8:1,9,false,4,true,false,false</item>
    </string-array>
</resources>
```

If your device maps profiles differently (such as `tanzanite` with Voice on ID `3`), add `res/values/arrays.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string-array name="dolby_profile_entries" translatable="false">
        <item>dynamic</item>
        <item>video</item>
        <item>music</item>
        <item>voice</item>
    </string-array>
    <string-array name="dolby_profile_values" translatable="false">
        <item>0</item>
        <item>1</item>
        <item>2</item>
        <item>3</item>
    </string-array>
</resources>
```

Then add `DolbyOverlayExample` to `PRODUCT_PACKAGES` in your device makefile (`device.mk`) alongside `HalcyonDolby`.

## Using the setup wizard

An interactive helper script inspects your `dax-default.xml` and `libswdapaidl.so`, suggests values for the hooks above, and writes the overlay directory into your device tree:

```bash
cd packages/halcyon/apps/Dolby
./scripts/setup_dolby_rro.sh
```

Saved answers are stored in `.dolby-rro.env` in the current directory so interrupted runs resume cleanly. Do not commit `.dolby-rro.env` to git.

## Verifying over ADB

After building `m HalcyonDolby DolbyOverlayExample` and flashing the updated images, verify overlay activation and toggle stability while playing audio:

```bash
adb shell cmd overlay list | grep -i dolby
adb logcat -b all -c
# Toggle Dolby off and on three times during playback and switch profiles
adb logcat -b all -d | grep -iE "XiaomiDolby|EffectHalAidl|DEAD_OBJECT|ENODATA|-61|swdap"
```

Expected output: `[x] org.hlcyn.dolby.example.overlay` in `cmd overlay list`, audio remaining active across toggles, and zero `DEAD_OBJECT` or `-61` errors in logcat.
