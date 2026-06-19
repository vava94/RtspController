package com.catanddev.rtsp.codec

import android.graphics.Bitmap
import android.media.Image

abstract class ColorConverter {

    abstract fun release()

}

abstract class ColorConverterImage: ColorConverter() {

    abstract fun getBitmapFromImage(image: Image): Bitmap

}