// Copyright (c)  2023  Xiaomi Corporation
package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

data class WaveData(
    val samples: FloatArray,
    val sampleRate: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as WaveData

        if (!samples.contentEquals(other.samples)) return false
        if (sampleRate != other.sampleRate) return false

        return true
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + sampleRate
        return result
    }
}

class WaveReader {
    companion object {

        fun readWave(
            assetManager: AssetManager,
            filename: String,
        ): WaveData {
            return readWaveFromAsset(assetManager, filename)
        }

        fun readWave(
            filename: String,
        ): WaveData {
            return readWaveFromFile(filename)
        }

        // Read a mono wave file asset
        external fun readWaveFromAsset(
            assetManager: AssetManager,
            filename: String,
        ): WaveData

        // Read a mono wave file from disk
        external fun readWaveFromFile(
            filename: String,
        ): WaveData

        init {
            try {
                try {
                    System.loadLibrary("onnxruntime")
                } catch (t: Throwable) {
                    android.util.Log.e("WaveReader", "Failed to load onnxruntime native library", t)
                }
                try {
                    System.loadLibrary("sherpa-onnx-jni")
                } catch (t: Throwable) {
                    android.util.Log.e("WaveReader", "Failed to load sherpa-onnx-jni native library", t)
                }
            } catch (t: Throwable) {
                android.util.Log.e("WaveReader", "Error initializing WaveReader native libraries", t)
            }
        }
    }
}
