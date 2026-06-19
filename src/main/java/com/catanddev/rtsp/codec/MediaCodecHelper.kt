package com.catanddev.rtsp.codec

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.ConfigurationInfo
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import com.catanddev.rtsp.RtspController
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.util.Collections
import java.util.LinkedList
import java.util.Locale
import java.util.regex.Pattern


// Based on https://github.com/moonlight-stream/moonlight-android/blob/master/app/src/main/java/com/limelight/binding/video/MediaCodecHelper.java
@SuppressLint("ObsoleteSdkInt")
object MediaCodecHelper {
    private val TAG: String = MediaCodecHelper::class.java.simpleName

    private var preferredDecoders: List<String> = LinkedList()

    private var blacklistedDecoderPrefixes: LinkedList<String> = LinkedList()
    private var spsFixupBitstreamFixupDecoderPrefixes: LinkedList<String> = LinkedList()
    private var blacklistedAdaptivePlaybackPrefixes: LinkedList<String> = LinkedList()
    private var baselineProfileHackPrefixes: LinkedList<String> = LinkedList()

    private var directSubmitPrefixes: LinkedList<String> = LinkedList()

    private var constrainedHighProfilePrefixes: LinkedList<String> = LinkedList()
    private var whitelistedHevcDecoders: LinkedList<String> = LinkedList()
    private var refFrameInvalidationAvcPrefixes: LinkedList<String> = LinkedList()
    private var refFrameInvalidationHevcPrefixes: LinkedList<String> = LinkedList()
    private var useFourSlicesPrefixes: LinkedList<String> = LinkedList()
    private var qualcommDecoderPrefixes: LinkedList<String> = LinkedList()
    private var kirinDecoderPrefixes: LinkedList<String> = LinkedList()
    private var exynosDecoderPrefixes: LinkedList<String> = LinkedList()
    private var amlogicDecoderPrefixes: LinkedList<String> = LinkedList()
    private var knownVendorLowLatencyOptions: LinkedList<String> = LinkedList()

    private val SHOULD_BYPASS_SOFTWARE_BLOCK: Boolean =
        Build.HARDWARE == "ranchu" || Build.HARDWARE == "cheets" || Build.BRAND == "Android-x86"

    private var isLowEndSnapdragon = false
    private var isAdreno620 = false
    private var initialized = false

    init {

        // These decoders have low enough input buffer latency that they
        // can be directly invoked from the receive thread
        directSubmitPrefixes.add("omx.qcom")
        directSubmitPrefixes.add("omx.sec")
        directSubmitPrefixes.add("omx.exynos")
        directSubmitPrefixes.add("omx.intel")
        directSubmitPrefixes.add("omx.brcm")
        directSubmitPrefixes.add("omx.TI")
        directSubmitPrefixes.add("omx.arc")
        directSubmitPrefixes.add("omx.nvidia")

        // All Codec2 decoders
        directSubmitPrefixes.add("c2.")
    }

    init {
        refFrameInvalidationAvcPrefixes = LinkedList()

        refFrameInvalidationHevcPrefixes = LinkedList()
        refFrameInvalidationHevcPrefixes.add("omx.exynos")
        refFrameInvalidationHevcPrefixes.add("c2.exynos")

        // Qualcomm and NVIDIA may be added at runtime
    }

    init {
        preferredDecoders = LinkedList()
    }

    init {
        blacklistedDecoderPrefixes = LinkedList()

        // Blacklist software decoders that don't support H264 high profile except on systems
        // that are expected to only have software decoders (like emulators).
        if (!SHOULD_BYPASS_SOFTWARE_BLOCK) {
            blacklistedDecoderPrefixes.add("omx.google")
            blacklistedDecoderPrefixes.add("AVCDecoder")

            // We want to avoid ffmpeg decoders since they're usually software decoders,
            // but we'll defer to the Android 10 isSoftwareOnly() API on newer devices
            // to determine if we should use these or not.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                blacklistedDecoderPrefixes.add("OMX.ffmpeg")
            }
        }

        // Force these decoders disabled because:
        // 1) They are software decoders, so the performance is terrible
        // 2) They crash with our HEVC stream anyway (at least prior to CSD batching)
        blacklistedDecoderPrefixes.add("OMX.qcom.video.decoder.hevcswvdec")
        blacklistedDecoderPrefixes.add("OMX.SEC.hevc.sw.dec")
    }

    init {
        // If a decoder qualifies for reference frame invalidation,
        // these entries will be ignored for those decoders.
        spsFixupBitstreamFixupDecoderPrefixes = LinkedList()
        spsFixupBitstreamFixupDecoderPrefixes.add("omx.nvidia")
        spsFixupBitstreamFixupDecoderPrefixes.add("omx.qcom")
        spsFixupBitstreamFixupDecoderPrefixes.add("omx.brcm")

        baselineProfileHackPrefixes = LinkedList()
        baselineProfileHackPrefixes.add("omx.intel")

        blacklistedAdaptivePlaybackPrefixes = LinkedList()
        // The Intel decoder on Lollipop on Nexus Player would increase latency badly
        // if adaptive playback was enabled so let's avoid it to be safe.
        blacklistedAdaptivePlaybackPrefixes.add("omx.intel")
        // The MediaTek decoder crashes at 1080p when adaptive playback is enabled
        // on some Android TV devices with HEVC only.
        blacklistedAdaptivePlaybackPrefixes.add("omx.mtk")

        constrainedHighProfilePrefixes = LinkedList()
        constrainedHighProfilePrefixes.add("omx.intel")
    }

    init {
        whitelistedHevcDecoders = LinkedList()

        // Allow software HEVC decoding in the official AOSP emulator
        if (Build.HARDWARE == "ranchu") {
            whitelistedHevcDecoders.add("omx.google")
        }

        // Exynos seems to be the only HEVC decoder that works reliably
        whitelistedHevcDecoders.add("omx.exynos")

        // On Darcy (Shield 2017), HEVC runs fine with no fixups required. For some reason,
        // other X1 implementations require bitstream fixups. However, since numReferenceFrames
        // has been supported in GFE since late 2017, we'll go ahead and enable HEVC for all
        // device models.
        //
        // NVIDIA does partial HEVC acceleration on the Shield Tablet. I don't know
        // whether the performance is good enough to use for streaming, but they're
        // using the same omx.nvidia.h265.decode name as the Shield TV which has a
        // fully accelerated HEVC pipeline. AFAIK, the only K1 devices with this
        // partially accelerated HEVC decoder are the Shield Tablet and Xiaomi MiPad,
        // so I'll check for those here.
        //
        // In case there are some that I missed, I will also exclude pre-Oreo OSes since
        // only Shield ATV got an Oreo update and any newer Tegra devices will not ship
        // with an old OS like Nougat.
        if (!Build.DEVICE.equals("shieldtablet", ignoreCase = true) && !Build.DEVICE.equals(
                "mocha",
                ignoreCase = true
            ) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        ) {
            whitelistedHevcDecoders.add("omx.nvidia")
        }

        // Plot twist: On newer Sony devices (BRAVIA_ATV2, BRAVIA_ATV3_4K, BRAVIA_UR1_4K) the H.264 decoder crashes
        // on several configurations (> 60 FPS and 1440p) that work with HEVC, so we'll whitelist those devices for HEVC.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.DEVICE.startsWith("BRAVIA_")) {
            whitelistedHevcDecoders.add("omx.mtk")
        }

        // Amlogic requires 1 reference frame for HEVC to avoid hanging. Since it's been years
        // since GFE added support for maxNumReferenceFrames, we'll just enable all Amlogic SoCs
        // running Android 9 or later.
        //
        // NB: We don't do this on Sabrina (GCWGTV) because H.264 is lower latency when we use
        // vendor.low-latency.enable. We will still use HEVC if decoderCanMeetPerformancePointWithHevcAndNotAvc()
        // determines it's the only way to meet the performance requirements.
        //
        // With the Android 12 update, Sabrina now uses HEVC (with RFI) based upon FEATURE_LowLatency
        // support, which provides equivalent latency to H.264 now.
        //
        // FIXME: Should we do this for all Amlogic S905X SoCs?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !Build.DEVICE.equals(
                "sabrina",
                ignoreCase = true
            )
        ) {
            whitelistedHevcDecoders.add("omx.amlogic")
        }

        // Realtek SoCs are used inside many Android TV devices and can only do 4K60 with HEVC.
        // We'll enable those HEVC decoders by default and see if anything breaks.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            whitelistedHevcDecoders.add("omx.realtek")
        }

        // These theoretically have good HEVC decoding capabilities (potentially better than
        // their AVC decoders), but haven't been tested enough
        //whitelistedHevcDecoders.add("omx.rk");

        // Let's see if HEVC decoders are finally stable with C2
        whitelistedHevcDecoders.add("c2.")

        // Based on GPU attributes queried at runtime, the omx.qcom/c2.qti prefix will be added
        // during initialization to avoid SoCs with broken HEVC decoders.
    }

    init {
        useFourSlicesPrefixes = LinkedList()

        // Software decoders will use 4 slices per frame to allow for slice multithreading
        useFourSlicesPrefixes.add("omx.google")
        useFourSlicesPrefixes.add("AVCDecoder")
        useFourSlicesPrefixes.add("omx.ffmpeg")
        useFourSlicesPrefixes.add("c2.android")

        // Old Qualcomm decoders are detected at runtime
    }

    init {
        knownVendorLowLatencyOptions = LinkedList()

        knownVendorLowLatencyOptions.add("vendor.qti-ext-dec-low-latency.enable")
        knownVendorLowLatencyOptions.add("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req")
        knownVendorLowLatencyOptions.add("vendor.rtc-ext-dec-low-latency.enable")
        knownVendorLowLatencyOptions.add("vendor.low-latency.enable")
    }

    init {
        qualcommDecoderPrefixes = LinkedList()

        qualcommDecoderPrefixes.add("omx.qcom")
        qualcommDecoderPrefixes.add("c2.qti")
    }

    init {
        kirinDecoderPrefixes = LinkedList()

        kirinDecoderPrefixes.add("omx.hisi")
        kirinDecoderPrefixes.add("c2.hisi") // Unconfirmed
    }

    init {
        exynosDecoderPrefixes = LinkedList()

        exynosDecoderPrefixes.add("omx.exynos")
        exynosDecoderPrefixes.add("c2.exynos")
    }

    init {
        amlogicDecoderPrefixes = LinkedList()

        amlogicDecoderPrefixes.add("omx.amlogic")
        amlogicDecoderPrefixes.add("c2.amlogic") // Unconfirmed
    }

    private fun isPowerVR(glRenderer: String): Boolean {
        return glRenderer.lowercase(Locale.getDefault()).contains("powervr")
    }

    private fun getAdrenoVersionString(glRenderer: String): String? {
        var glRenderer = glRenderer
        glRenderer = glRenderer.lowercase(Locale.getDefault()).trim { it <= ' ' }

        if (!glRenderer.contains("adreno")) {
            return null
        }

        val modelNumberPattern = Pattern.compile("(.*)([0-9]{3})(.*)")

        val matcher = modelNumberPattern.matcher(glRenderer)
        if (!matcher.matches()) {
            return null
        }

        val modelNumber = matcher.group(2)
        if (RtspController.DEBUG)
            Log.d(TAG, "Found Adreno GPU: $modelNumber")

        return modelNumber
    }

    private fun isLowEndSnapdragonRenderer(glRenderer: String): Boolean {
        val modelNumber = getAdrenoVersionString(glRenderer)
            ?: // Not an Adreno GPU
            return false

        // The current logic is to identify low-end SoCs based on a zero in the x0x place.
        return modelNumber[1] == '0'
    }

    private fun getAdrenoRendererModelNumber(glRenderer: String): Int {
        val modelNumber = getAdrenoVersionString(glRenderer)
            ?: // Not an Adreno GPU
            return -1

        return modelNumber.toInt()
    }

    // This is a workaround for some broken devices that report
    // only GLES 3.0 even though the GPU is an Adreno 4xx series part.
    // An example of such a device is the Huawei Honor 5x with the
    // Snapdragon 616 SoC (Adreno 405).
    private fun isGLES31SnapdragonRenderer(glRenderer: String): Boolean {
        // Snapdragon 4xx and higher support GLES 3.1
        return getAdrenoRendererModelNumber(glRenderer) >= 400
    }

    fun initialize(context: Context, glRenderer: String) {
        if (initialized) {
            return
        }

        // Older Sony ATVs (SVP-DTV15) have broken MediaTek codecs (decoder hangs after rendering the first frame).
        // I know the Fire TV 2 and 3 works, so I'll whitelist Amazon devices which seem to actually be tested.
        // We still have to check Build.MANUFACTURER to catch Amazon Fire tablets.
        if (context.packageManager.hasSystemFeature("amazon.hardware.fire_tv") ||
            Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
        ) {
            // HEVC and RFI have been confirmed working on Fire TV 2, Fire TV Stick 2, Fire TV 4K Max,
            // Fire HD 8 2020, and Fire HD 8 2022 models.
            //
            // This is probably a good enough sample to conclude that all MediaTek Fire OS devices
            // are likely to be okay.
            whitelistedHevcDecoders.add("omx.mtk")
            refFrameInvalidationHevcPrefixes.add("omx.mtk")
            refFrameInvalidationHevcPrefixes.add("c2.mtk")

            // This requires setting vdec-lowlatency on the Fire TV 3, otherwise the decoder
            // never produces any output frames. See comment above for details on why we only
            // do this for Fire TV devices.
            whitelistedHevcDecoders.add("omx.amlogic")

            // Fire TV 3 seems to produce random artifacts on HEVC streams after packet loss.
            // Enabling RFI turns these artifacts into full decoder output hangs, so let's not enable
            // that for Fire OS 6 Amlogic devices. We will leave HEVC enabled because that's the only
            // way these devices can hit 4K. Hopefully this is just a problem with the BSP used in
            // the Fire OS 6 Amlogic devices, so we will leave this enabled for Fire OS 7+.
            //
            // Apart from a few TV models, the main Amlogic-based Fire TV devices are the Fire TV
            // Cubes and Fire TV 3. This check will exclude the Fire TV 3 and Fire TV Cube 1, but
            // allow the newer Fire TV Cubes to use HEVC RFI.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                refFrameInvalidationHevcPrefixes.add("omx.amlogic")
                refFrameInvalidationHevcPrefixes.add("c2.amlogic")
            }
        }

        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val configInfo = activityManager.deviceConfigurationInfo
        if (configInfo.reqGlEsVersion != ConfigurationInfo.GL_ES_VERSION_UNDEFINED) {

            if (RtspController.DEBUG)
                Log.d(TAG, "OpenGL ES version: " + configInfo.reqGlEsVersion)

            isLowEndSnapdragon = isLowEndSnapdragonRenderer(glRenderer)
            isAdreno620 = getAdrenoRendererModelNumber(glRenderer) == 620

            // Tegra K1 and later can do reference frame invalidation properly
            if (configInfo.reqGlEsVersion >= 0x30000) {
                if (RtspController.DEBUG)
                    Log.d(
                        TAG,
                        "Added omx.nvidia/c2.nvidia to reference frame invalidation support list"
                    )

                refFrameInvalidationAvcPrefixes.add("omx.nvidia")

                // Exclude HEVC RFI on Pixel C and Tegra devices prior to Android 11. Misbehaving RFI
                // on these devices can cause hundreds of milliseconds of latency, so it's not worth
                // using it unless we're absolutely sure that it will not cause increased latency.
                if (!Build.DEVICE.equals(
                        "dragon",
                        ignoreCase = true
                    ) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ) {
                    refFrameInvalidationHevcPrefixes.add("omx.nvidia")
                }

                refFrameInvalidationAvcPrefixes.add("c2.nvidia") // Unconfirmed
                refFrameInvalidationHevcPrefixes.add("c2.nvidia") // Unconfirmed
                if (RtspController.DEBUG)
                    Log.d(TAG, "Added omx.qcom/c2.qti to reference frame invalidation support list")
                refFrameInvalidationAvcPrefixes.add("omx.qcom")
                refFrameInvalidationHevcPrefixes.add("omx.qcom")
                refFrameInvalidationAvcPrefixes.add("c2.qti")
                refFrameInvalidationHevcPrefixes.add("c2.qti")
            }

            // Qualcomm's early HEVC decoders break hard on our HEVC stream. The best check to
            // tell the good from the bad decoders are the generation of Adreno GPU included:
            // 3xx - bad
            // 4xx - good
            //
            // The "good" GPUs support GLES 3.1, but we can't just check that directly
            // (see comment on isGLES31SnapdragonRenderer).
            //
            if (isGLES31SnapdragonRenderer(glRenderer)) {
                if (RtspController.DEBUG)
                    Log.d(TAG, "Added omx.qcom/c2.qti to HEVC decoders based on GLES 3.1+ support")
                whitelistedHevcDecoders.add("omx.qcom")
                whitelistedHevcDecoders.add("c2.qti")
            } else {
                blacklistedDecoderPrefixes.add("OMX.qcom.video.decoder.hevc")

                // These older decoders need 4 slices per frame for best performance
                useFourSlicesPrefixes.add("omx.qcom")
            }

            // Older MediaTek SoCs have issues with HEVC rendering but the newer chips with
            // PowerVR GPUs have good HEVC support.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isPowerVR(glRenderer)) {
                if (RtspController.DEBUG)
                    Log.d(TAG, "Added omx.mtk to HEVC decoders based on PowerVR GPU")
                whitelistedHevcDecoders.add("omx.mtk")

                // This SoC (MT8176 in GPD XD+) supports AVC RFI too, but the maxNumReferenceFrames setting
                // required to make it work adds a huge amount of latency. However, RFI on HEVC causes
                // decoder hangs on the newer GE8100, GE8300, and GE8320 GPUs, so we limit it to the
                // Series6XT GPUs where we know it works.
                if (glRenderer.contains("GX6")) {
                    if (RtspController.DEBUG)
                        Log.d(TAG, "Added omx.mtk/c2.mtk to RFI list for HEVC")
                    refFrameInvalidationHevcPrefixes.add("omx.mtk")
                    refFrameInvalidationHevcPrefixes.add("c2.mtk")
                }
            }
        }

        initialized = true
    }

    private fun isDecoderInList(decoderList: List<String>, decoderName: String): Boolean {
        check(initialized) { "MediaCodecHelper must be initialized before use" }

        for (badPrefix in decoderList) {
            if (decoderName.length >= badPrefix.length) {
                val prefix = decoderName.substring(0, badPrefix.length)
                if (prefix.equals(badPrefix, ignoreCase = true)) {
                    return true
                }
            }
        }

        return false
    }

    private fun decoderSupportsAndroidRLowLatency(
        decoderInfo: MediaCodecInfo,
        mimeType: String?
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (decoderInfo.getCapabilitiesForType(mimeType)
                        .isFeatureSupported(CodecCapabilities.FEATURE_LowLatency)
                ) {
                    if (RtspController.DEBUG)
                        Log.d(TAG, "Low latency decoding mode supported (FEATURE_LowLatency)")
                    return true
                }
            } catch (e: Exception) {
                // Tolerate buggy codecs
                e.printStackTrace()
            }
        }

        return false
    }

    private fun decoderSupportsKnownVendorLowLatencyOption(decoderName: String): Boolean {
        // It's only possible to probe vendor parameters on Android 12 and above.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            var testCodec: MediaCodec? = null
            try {
                // Unfortunately we have to create an actual codec instance to get supported options.
                testCodec = MediaCodec.createByCodecName(decoderName)

                // See if any of the vendor parameters match ones we know about
                for (supportedOption in testCodec.supportedVendorParameters) {
                    for (knownLowLatencyOption in knownVendorLowLatencyOptions) {
                        if (supportedOption.equals(knownLowLatencyOption, ignoreCase = true)) {
                            if (RtspController.DEBUG)
                                Log.d(
                                    TAG,
                                    "$decoderName supports known low latency option: $supportedOption"
                                )
                            return true
                        }
                    }
                }
            } catch (e: Exception) {
                // Tolerate buggy codecs
                e.printStackTrace()
            } finally {
                testCodec?.release()
            }
        }
        return false
    }

    private fun decoderSupportsMaxOperatingRate(decoderName: String): Boolean {
        // Operate at maximum rate to lower latency as much as possible on
        // some Qualcomm platforms. We could also set KEY_PRIORITY to 0 (realtime)
        // but that will actually result in the decoder crashing if it can't satisfy
        // our (ludicrous) operating rate requirement. This seems to cause reliable
        // crashes on the Xiaomi Mi 10 lite 5G and Redmi K30i 5G on Android 10, so
        // we'll disable it on Snapdragon 765G and all non-Qualcomm devices to be safe.
        //
        // NB: Even on Android 10, this optimization still provides significant
        // performance gains on Pixel 2.
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                isDecoderInList(qualcommDecoderPrefixes, decoderName) && !isAdreno620
    }

    fun setDecoderLowLatencyOptions(
        videoFormat: MediaFormat,
        decoderInfo: MediaCodecInfo,
        tryNumber: Int
    ): Boolean {
        // Options here should be tried in the order of most to least risky. The decoder will use
        // the first MediaFormat that doesn't fail in configure().

        var setNewOption = false

        if (tryNumber < 1) {
            // Official Android 11+ low latency option (KEY_LOW_LATENCY).
            videoFormat.setInteger("low-latency", 1)
            setNewOption = true

            // If this decoder officially supports FEATURE_LowLatency, we will just use that alone
            // for try 0. Otherwise, we'll include it as best effort with other options.
            if (decoderSupportsAndroidRLowLatency(
                    decoderInfo,
                    videoFormat.getString(MediaFormat.KEY_MIME)
                )
            ) {
                return true
            }
        }

        if (tryNumber < 2 &&
            (!Build.MANUFACTURER.equals(
                "xiaomi",
                ignoreCase = true
            ) || Build.VERSION.SDK_INT > Build.VERSION_CODES.M)
        ) {
            // MediaTek decoders don't use vendor-defined keys for low latency mode. Instead, they have a modified
            // version of AOSP's ACodec.cpp which supports the "vdec-lowlatency" option. This option is passed down
            // to the decoder as OMX.MTK.index.param.video.LowLatencyDecode.
            //
            // This option is also plumbed for Amazon Amlogic-based devices like the Fire TV 3. Not only does it
            // reduce latency on Amlogic, it fixes the HEVC bug that causes the decoder to not output any frames.
            // Unfortunately, it does the exact opposite for the Xiaomi MITV4-ANSM0, breaking it in the way that
            // Fire TV was broken prior to vdec-lowlatency :(
            //
            // On Fire TV 3, vdec-lowlatency is translated to OMX.amazon.fireos.index.video.lowLatencyDecode.
            //
            // https://github.com/yuan1617/Framwork/blob/master/frameworks/av/media/libstagefright/ACodec.cpp
            // https://github.com/iykex/vendor_mediatek_proprietary_hardware/blob/master/libomx/video/MtkOmxVdecEx/MtkOmxVdecEx.h
            videoFormat.setInteger("vdec-lowlatency", 1)
            setNewOption = true
        }

        if (tryNumber < 3) {
            if (decoderSupportsMaxOperatingRate(decoderInfo.name)) {
                videoFormat.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                setNewOption = true
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                videoFormat.setInteger(MediaFormat.KEY_PRIORITY, 0)
                setNewOption = true
            }
        }

        // MediaCodec supports vendor-defined format keys using the "vendor.<extension name>.<parameter name>" syntax.
        // These allow access to functionality that is not exposed through documented MediaFormat.KEY_* values.
        // https://cs.android.com/android/platform/superproject/+/master:hardware/qcom/sdm845/media/mm-video-v4l2/vidc/common/inc/vidc_vendor_extensions.h;l=67
        //
        // MediaCodec vendor extension support was introduced in Android 8.0:
        // https://cs.android.com/android/_/android/platform/frameworks/av/+/01c10f8cdcd58d1e7025f426a72e6e75ba5d7fc2
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Try vendor-specific low latency options
            //
            // NOTE: Update knownVendorLowLatencyOptions if you modify this code!
            if (isDecoderInList(qualcommDecoderPrefixes, decoderInfo.name)) {
                // Examples of Qualcomm's vendor extensions for Snapdragon 845:
                // https://cs.android.com/android/platform/superproject/+/master:hardware/qcom/sdm845/media/mm-video-v4l2/vidc/vdec/src/omx_vdec_extensions.hpp
                // https://cs.android.com/android/_/android/platform/hardware/qcom/sm8150/media/+/0621ceb1c1b19564999db8293574a0e12952ff6c
                //
                // We will first try both, then try vendor.qti-ext-dec-low-latency.enable alone if that fails
                if (tryNumber < 4) {
                    videoFormat.setInteger("vendor.qti-ext-dec-picture-order.enable", 1)
                    setNewOption = true
                }
                if (tryNumber < 5) {
                    videoFormat.setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
                    setNewOption = true
                }
            } else if (isDecoderInList(kirinDecoderPrefixes, decoderInfo.name)) {
                if (tryNumber < 4) {
                    // Kirin low latency options
                    // https://developer.huawei.com/consumer/cn/forum/topic/0202325564295980115
                    videoFormat.setInteger(
                        "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req",
                        1
                    )
                    videoFormat.setInteger(
                        "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy",
                        -1
                    )
                    setNewOption = true
                }
            } else if (isDecoderInList(exynosDecoderPrefixes, decoderInfo.name)) {
                if (tryNumber < 4) {
                    // Exynos low latency option for H.264 decoder
                    videoFormat.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)
                    setNewOption = true
                }
            } else if (isDecoderInList(amlogicDecoderPrefixes, decoderInfo.name)) {
                if (tryNumber < 4) {
                    // Amlogic low latency vendor extension
                    // https://github.com/codewalkerster/android_vendor_amlogic_common_prebuilt_libstagefrighthw/commit/41fefc4e035c476d58491324a5fe7666bfc2989e
                    videoFormat.setInteger("vendor.low-latency.enable", 1)
                    setNewOption = true
                }
            }
        }

        return setNewOption
    }

    fun decoderSupportsFusedIdrFrame(decoderInfo: MediaCodecInfo, mimeType: String?): Boolean {
        // If adaptive playback is supported, we can submit new CSD together with a keyframe
        try {
            if (decoderInfo.getCapabilitiesForType(mimeType).isFeatureSupported
                    (CodecCapabilities.FEATURE_AdaptivePlayback)
            ) {
                if (RtspController.DEBUG)
                    Log.d(TAG, "Decoder supports fused IDR frames (FEATURE_AdaptivePlayback)")
                return true
            }
        } catch (e: Exception) {
            // Tolerate buggy codecs
            e.printStackTrace()
        }

        return false
    }

    fun decoderSupportsAdaptivePlayback(decoderInfo: MediaCodecInfo, mimeType: String?): Boolean {
        if (isDecoderInList(blacklistedAdaptivePlaybackPrefixes, decoderInfo.name)) {
            Log.w(TAG, "Decoder blacklisted for adaptive playback")
            return false
        }

        try {
            if (decoderInfo.getCapabilitiesForType(mimeType).isFeatureSupported
                    (CodecCapabilities.FEATURE_AdaptivePlayback)
            ) {
                // This will make getCapabilities() return that adaptive playback is supported
                if (RtspController.DEBUG)
                    Log.d(TAG, "Adaptive playback supported (FEATURE_AdaptivePlayback)")
                return true
            }
        } catch (e: Exception) {
            // Tolerate buggy codecs
            e.printStackTrace()
        }

        return false
    }

    fun decoderNeedsConstrainedHighProfile(decoderName: String): Boolean {
        return isDecoderInList(constrainedHighProfilePrefixes, decoderName)
    }

    fun decoderCanDirectSubmit(decoderName: String): Boolean {
        return isDecoderInList(directSubmitPrefixes, decoderName) && !isExynos4Device
    }

    fun decoderNeedsSpsBitstreamRestrictions(decoderName: String): Boolean {
        return isDecoderInList(spsFixupBitstreamFixupDecoderPrefixes, decoderName)
    }

    fun decoderNeedsBaselineSpsHack(decoderName: String): Boolean {
        return isDecoderInList(baselineProfileHackPrefixes, decoderName)
    }

    fun getDecoderOptimalSlicesPerFrame(decoderName: String): Byte {
        return if (isDecoderInList(useFourSlicesPrefixes, decoderName)) {
            // 4 slices per frame reduces decoding latency on older Qualcomm devices
            4
        } else {
            // 1 slice per frame produces the optimal encoding efficiency
            1
        }
    }

    fun decoderSupportsRefFrameInvalidationAvc(decoderName: String, videoHeight: Int): Boolean {
        // Reference frame invalidation is broken on low-end Snapdragon SoCs at 1080p.
        if (videoHeight > 720 && isLowEndSnapdragon) {
            return false
        }

        // This device seems to crash constantly at 720p, so try disabling
        // RFI to see if we can get that under control.
        if (Build.DEVICE == "b3" || Build.DEVICE == "b5") {
            return false
        }

        return isDecoderInList(refFrameInvalidationAvcPrefixes, decoderName)
    }

    fun decoderSupportsRefFrameInvalidationHevc(decoderInfo: MediaCodecInfo): Boolean {
        // HEVC decoders seem to universally support RFI, but it can have huge latency penalties
        // for some decoders due to the number of references frames being > 1. Old Amlogic
        // decoders are known to have this problem.
        //
        // If the decoder supports FEATURE_LowLatency or any vendor low latency option,
        // we will use that as an indication that it can handle HEVC RFI without excessively
        // buffering frames.
        if (decoderSupportsAndroidRLowLatency(decoderInfo, "video/hevc") ||
            decoderSupportsKnownVendorLowLatencyOption(decoderInfo.name)
        ) {
            if (RtspController.DEBUG)
                Log.d(TAG, "Enabling HEVC RFI based on low latency option support")
            return true
        }

        return isDecoderInList(refFrameInvalidationHevcPrefixes, decoderInfo.name)
    }

    fun decoderSupportsRefFrameInvalidationAv1(decoderInfo: MediaCodecInfo): Boolean {
        // We'll use the same heuristics as HEVC for now
        if (decoderSupportsAndroidRLowLatency(decoderInfo, "video/av01") ||
            decoderSupportsKnownVendorLowLatencyOption(decoderInfo.name)
        ) {
            if (RtspController.DEBUG)
                Log.d(TAG, "Enabling AV1 RFI based on low latency option support")
            return true
        }

        return false
    }

    fun decoderIsWhitelistedForHevc(decoderInfo: MediaCodecInfo): Boolean {
        //
        // Software decoders are terrible and we never want to use them.
        // We want to catch decoders like:
        // OMX.qcom.video.decoder.hevcswvdec
        // OMX.SEC.hevc.sw.dec
        //
        if (decoderInfo.name.contains("sw")) {
            if (RtspController.DEBUG)
                Log.d(TAG, "Disallowing HEVC on software decoder: " + decoderInfo.name)
            return false
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && (!decoderInfo.isHardwareAccelerated || decoderInfo.isSoftwareOnly)) {
            if (RtspController.DEBUG)
                Log.d(TAG, "Disallowing HEVC on software decoder: " + decoderInfo.name)
            return false
        }

        // If this device is media performance class 12 or higher, we will assume any hardware
        // HEVC decoder present is fast and modern enough for streaming.
        //
        // [5.3/H-1-1] MUST NOT drop more than 2 frames in 10 seconds (i.e less than 0.333 percent frame drop) for a 1080p 60 fps video session under load.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (RtspController.DEBUG)
                Log.d(TAG, "Media performance class: " + Build.VERSION.MEDIA_PERFORMANCE_CLASS)
            if (Build.VERSION.MEDIA_PERFORMANCE_CLASS >= Build.VERSION_CODES.S) {
                if (RtspController.DEBUG)
                    Log.d(TAG, "Allowing HEVC based on media performance class")
                return true
            }
        }

        // If the decoder supports FEATURE_LowLatency, we will assume it is fast and modern enough
        // to be preferable for streaming over H.264 decoders.
        if (decoderSupportsAndroidRLowLatency(decoderInfo, "video/hevc")) {
            if (RtspController.DEBUG)
                Log.d(TAG, "Allowing HEVC based on FEATURE_LowLatency support")
            return true
        }

        // Otherwise, we use our list of known working HEVC decoders
        return isDecoderInList(whitelistedHevcDecoders, decoderInfo.name)
    }

    fun isDecoderWhitelistedForAv1(decoderInfo: MediaCodecInfo): Boolean {
        // Google didn't have official support for AV1 (or more importantly, a CTS test) until
        // Android 10, so don't use any decoder before then.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }

        //
        // Software decoders are terrible and we never want to use them.
        // We want to catch decoders like:
        // OMX.qcom.video.decoder.hevcswvdec
        // OMX.SEC.hevc.sw.dec
        //
        if (decoderInfo.name.contains("sw")) {
            if (RtspController.DEBUG)
                Log.d(TAG, "Disallowing AV1 on software decoder: " + decoderInfo.name)
            return false
        } else if (!decoderInfo.isHardwareAccelerated || decoderInfo.isSoftwareOnly) {
            if (RtspController.DEBUG)
                Log.d(TAG, "Disallowing AV1 on software decoder: " + decoderInfo.name)
            return false
        }

        // TODO: Test some AV1 decoders
        return false
    }

    @get:SuppressLint("NewApi")
    @get:Suppress("deprecation")
    private val mediaCodecList: LinkedList<MediaCodecInfo>
        get() {
            val infoList = LinkedList<MediaCodecInfo>()

            val mcl = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            Collections.addAll(infoList, *mcl.codecInfos)

            return infoList
        }

    @Throws(Exception::class)
    fun dumpDecoders(): String {
        var str = ""
        for (codecInfo in mediaCodecList) {
            // Skip encoders
            if (codecInfo.isEncoder) {
                continue
            }

            str += "Decoder: " + codecInfo.name + "\n"
            for (type in codecInfo.supportedTypes) {
                str += "\t" + type + "\n"
                val caps = codecInfo.getCapabilitiesForType(type)

                for (profile in caps.profileLevels) {
                    str += "\t\t" + profile.profile + " " + profile.level + "\n"
                }
            }
        }
        return str
    }

    private fun findPreferredDecoder(): MediaCodecInfo? {
        // This is a different algorithm than the other findXXXDecoder functions,
        // because we want to evaluate the decoders in our list's order
        // rather than MediaCodecList's order

        check(initialized) { "MediaCodecHelper must be initialized before use" }

        for (preferredDecoder in preferredDecoders) {
            for (codecInfo in mediaCodecList) {
                // Skip encoders
                if (codecInfo.isEncoder) {
                    continue
                }

                // Check for preferred decoders
                if (preferredDecoder.equals(codecInfo.name, ignoreCase = true)) {
                    if (RtspController.DEBUG)
                        Log.d(TAG, "Preferred decoder choice is " + codecInfo.name)
                    return codecInfo
                }
            }
        }

        return null
    }

    private fun isCodecBlacklisted(codecInfo: MediaCodecInfo): Boolean {
        // Use the new isSoftwareOnly() function on Android Q
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!SHOULD_BYPASS_SOFTWARE_BLOCK && codecInfo.isSoftwareOnly) {
                if (RtspController.DEBUG)
                    Log.d(TAG, "Skipping software-only decoder: " + codecInfo.name)
                return true
            }
        }

        // Check for explicitly blacklisted decoders
        if (isDecoderInList(blacklistedDecoderPrefixes, codecInfo.name)) {
            if (RtspController.DEBUG)
                Log.d(TAG, "Skipping blacklisted decoder: " + codecInfo.name)
            return true
        }

        return false
    }

    fun findFirstDecoder(mimeType: String?): MediaCodecInfo? {
        for (codecInfo in mediaCodecList) {
            // Skip encoders
            if (codecInfo.isEncoder) {
                continue
            }

            // Skip compatibility aliases on Q+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (codecInfo.isAlias) {
                    continue
                }
            }

            // Find a decoder that supports the specified video format
            for (mime in codecInfo.supportedTypes) {
                if (mime.equals(mimeType, ignoreCase = true)) {
                    // Skip blacklisted codecs
                    if (isCodecBlacklisted(codecInfo)) {
                        continue
                    }

                    if (RtspController.DEBUG)
                        Log.d(TAG, "First decoder choice is " + codecInfo.name)
                    return codecInfo
                }
            }
        }

        return null
    }

    fun findProbableSafeDecoder(mimeType: String, requiredProfile: Int): MediaCodecInfo? {
        // First look for a preferred decoder by name
        val info = findPreferredDecoder()
        if (info != null) {
            return info
        }

        // Now look for decoders we know are safe
        return try {
            // If this function completes, it will determine if the decoder is safe
            findKnownSafeDecoder(mimeType, requiredProfile)
        } catch (e: Exception) {
            // Some buggy devices seem to throw exceptions
            // from getCapabilitiesForType() so we'll just assume
            // they're okay and go with the first one we find
            findFirstDecoder(mimeType)
        }
    }

    // We declare this method as explicitly throwing Exception
    // since some bad decoders can throw IllegalArgumentExceptions unexpectedly
    // and we want to be sure all callers are handling this possibility
    @Throws(Exception::class)
    private fun findKnownSafeDecoder(mimeType: String, requiredProfile: Int): MediaCodecInfo? {
        // Some devices (Exynos devces, at least) have two sets of decoders.
        // The first set of decoders are C2 which do not support FEATURE_LowLatency,
        // but the second set of OMX decoders do support FEATURE_LowLatency. We want
        // to pick the OMX decoders despite the fact that C2 is listed first.
        // On some Qualcomm devices (like Pixel 4), there are separate low latency decoders
        // (like c2.qti.hevc.decoder.low_latency) that advertise FEATURE_LowLatency while
        // the standard ones (like c2.qti.hevc.decoder) do not. Like Exynos, the decoders
        // with FEATURE_LowLatency support are listed after the standard ones.
        for (i in 0..1) {
            for (codecInfo in mediaCodecList) {
                // Skip encoders
                if (codecInfo.isEncoder) {
                    continue
                }

                // Skip compatibility aliases on Q+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (codecInfo.isAlias) {
                        continue
                    }
                }

                // Find a decoder that supports the requested video format
                for (mime in codecInfo.supportedTypes) {
                    if (mime.equals(mimeType, ignoreCase = true)) {
                        if (RtspController.DEBUG)
                            Log.d(
                                TAG,
                                "Examining decoder capabilities of " + codecInfo.name + " (round " + (i + 1) + ")"
                            )

                        // Skip blacklisted codecs
                        if (isCodecBlacklisted(codecInfo)) {
                            continue
                        }

                        val caps = codecInfo.getCapabilitiesForType(mime)

                        if (i == 0 && !decoderSupportsAndroidRLowLatency(codecInfo, mime)) {
                            if (RtspController.DEBUG)
                                Log.d(TAG, "Skipping decoder that lacks FEATURE_LowLatency for round 1")
                            continue
                        }

                        if (requiredProfile != -1) {
                            for (profile in caps.profileLevels) {
                                if (profile.profile == requiredProfile) {
                                    if (RtspController.DEBUG)
                                        Log.d(
                                            TAG,
                                            "Decoder " + codecInfo.name + " supports required profile"
                                        )
                                    return codecInfo
                                }
                            }

                            Log.w(
                                TAG,
                                "Decoder " + codecInfo.name + " does NOT support required profile"
                            )
                        } else {
                            return codecInfo
                        }
                    }
                }
            }
        }

        return null
    }

    @Throws(Exception::class)
    fun readCpuinfo(): String {
        val cpuInfo = StringBuilder()
        BufferedReader(FileReader(File("/proc/cpuinfo"))).use { br ->
            while (true) {
                val ch = br.read()
                if (ch == -1) break
                cpuInfo.append(ch.toChar())
            }
            return cpuInfo.toString()
        }
    }

    private fun stringContainsIgnoreCase(string: String, substring: String): Boolean {
        return string.lowercase().contains(substring.lowercase())
    }

    val isExynos4Device: Boolean
        get() {
            try {
                // Try reading CPU info too look for
                val cpuInfo = readCpuinfo()

                // SMDK4xxx is Exynos 4
                if (stringContainsIgnoreCase(cpuInfo, "SMDK4")) {
                    if (RtspController.DEBUG)
                        Log.d(TAG, "Found SMDK4 in /proc/cpuinfo")
                    return true
                }

                // If we see "Exynos 4" also we'll count it
                if (stringContainsIgnoreCase(cpuInfo, "Exynos 4")) {
                    if (RtspController.DEBUG)
                        Log.d(TAG, "Found Exynos 4 in /proc/cpuinfo")
                    return true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            try {
                val systemDir = File("/sys/devices/system")
                val files = systemDir.listFiles()
                if (files != null) {
                    for (f in files) {
                        if (stringContainsIgnoreCase(f.name, "exynos4")) {
                            if (RtspController.DEBUG)
                                Log.d(TAG, "Found exynos4 in /sys/devices/system")
                            return true
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            return false
        }
}
