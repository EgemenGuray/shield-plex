# Shield TV Pro, HDR10+ and a custom Plex client

## Short answer

A custom Plex client cannot make the 2019 Shield TV Pro output HDR10+. Parsing the
HDR10+ metadata in software is easy; the problem is getting it onto the HDMI cable,
and only Nvidia's firmware can do that. The audio part of the ask (direct play with
TrueHD Atmos and DTS:X passed through untouched) already works with the official
Plex app and with Kodi, so a new client would add nothing there.

## Why HDR10+ is blocked on the Shield

HDR10+ needs two things from the player box:

1. Read the per-scene metadata (SMPTE ST 2094-40, carried as SEI messages in HEVC).
   Any app can do this. Being royalty-free and openly specified helps here.
2. Send that metadata to the TV every frame inside an HDMI Vendor-Specific InfoFrame
   (HDR10+ OUI 0x90848B). This is the part that matters, and apps cannot touch it.

On Android, InfoFrames are built by the display hardware composer (HWC) and the
kernel display driver, both of which are Nvidia's closed vendor code on the Shield.
The only door Android gives an app is to hand HDR10+ metadata to the video decoder
(`MediaCodec.PARAMETER_KEY_HDR10_PLUS_INFO`, Android 10+) and hope the vendor stack
forwards it. Nvidia's stack does not: the Shield's published spec and every firmware
release up to Shield Experience 9.x list HDR10, HLG and Dolby Vision only, and
Android never reports HDR10+ as a supported output type on it. Run
`probe-hdr10plus.sh` to confirm this on your own unit (look for type 4).

Root would not fix it. You would need to unlock the bootloader, rebuild the display
HAL and kernel driver to emit a new InfoFrame type, and you would lose Widevine L1
(Netflix, Disney+ in HD/4K) in the process. That is firmware development, not an app.

## What existing players do

| Player | HDR10+ on Shield | Notes |
|---|---|---|
| ExoPlayer / Media3 (used by Plex for Android TV) | No | Only forwards HDR10+ out-of-band for VP9 decoders that advertise the HDR10+ profile (`MediaCodecInfo.isHdr10PlusOutOfBandMetadataSupported`). HEVC HDR10+ is left to the decoder, which on the Shield drops it and outputs HDR10. |
| Kodi | No | Uses the same MediaCodec surface path; HDR signalling is entirely up to the vendor driver. |
| mpv / libplacebo | Partly | Can use HDR10+ metadata for its own tone mapping, but mpv-android cannot signal HDR to the TV, so you get SDR. Worse than letting the QN900B tone map HDR10. |

So on the Shield, HDR10+ files play as HDR10 (static metadata) and the QN900B does
its own tone mapping. That is the best this hardware can do.

## Your setup: what to set up instead

**Plex app on the Shield (recommended, no new client needed)**
- Plex > Settings > Video: Remote/Local quality "Maximum" / "Original", and turn on
  "Allow direct play" and "Allow direct stream".
- Plex > Settings > Audio: Passthrough "HDMI", and enable TrueHD, DTS, DTS-HD (and
  Dolby Digital+). DTS:X travels inside the DTS-HD MA stream, so DTS-HD passthrough
  covers it.
- Shield Settings > Display & Sound > Advanced: Surround sound "Always", and turn on
  Match content frame rate (and dynamic range if offered).

**HDMI wiring for lossless audio**
- Best: Shield into the Q990C's HDMI input, Q990C into the TV. The soundbar decodes
  TrueHD/DTS-HD itself and passes video on, and the TV's handling of DTS over eARC
  no longer matters.
- Otherwise: Shield into the QN900B, TV to the Q990C over eARC, with the TV's HDMI-eARC
  mode on Auto and Digital Output Audio Format set to Passthrough. Samsung TVs have a
  history of dropping DTS over eARC, so test a DTS:X file; if it falls back to PCM or
  silence, use the soundbar-input wiring above.

**Dolby Vision note:** the QN900B has no Dolby Vision, so the Shield sends the HDR10
base layer of DV files. Profile 7/8 files are fine; profile 5 (streaming-sourced, no
HDR10 base) will show wrong colours on this TV.

## If HDR10+ itself is the goal

The Shield cannot give it to you. Options that can, each worth testing with a known
HDR10+ file before committing:
- The QN900B's own Plex app. The TV decodes HDR10+ natively, but this sends audio over
  eARC, so the DTS caveat above applies.
- An Amlogic box running CoreELEC with Kodi and a Plex add-on. CoreELEC supports
  HDR10+ output and full audio passthrough on recent Amlogic chips (for example the
  Ugoos AM6B+). Check the CoreELEC forum for your exact model before buying.

## Files

- `probe-hdr10plus.sh`: run from a computer with adb to see what HDR types and audio
  encodings your Shield reports with the QN900B and Q990C connected.
