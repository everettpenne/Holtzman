package com.everettpenne.holtzman

import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.everettpenne.holtzman.save.PhotoSaver
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** A burst's JPEG is a landscape bitmap in sensor orientation; whoever shows it must be told to turn it, by the file and by the media library. */
@RunWith(AndroidJUnit4::class)
class OrientationSaveTest {
    @Test fun aSavedJpegCarriesItsRotationInTheFileAndInTheMediaLibrary() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val saver = PhotoSaver(resolver)
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF336699.toInt()) }
        val uri = saver.writeJpeg("orientation_test_${System.nanoTime()}.jpg", System.currentTimeMillis(), bmp, 90, 90)
        try {
            val inExif = resolver.openInputStream(uri)!!.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }
            assertEquals("EXIF orientation in the file", ExifInterface.ORIENTATION_ROTATE_90, inExif)
            val cached = resolver.query(uri, arrayOf(MediaStore.MediaColumns.ORIENTATION), null, null, null)!!.use { it.moveToFirst(); it.getInt(0) }
            assertEquals("orientation the media library holds for the file", 90, cached)
        } finally { resolver.delete(uri, null, null) }
    }
}
