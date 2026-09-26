package app.walldash.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.io.InputStream
import kotlin.math.max

/**
 * ダッシュボードの背景画像（filesDir/wallpaper.jpg）。
 *
 * 選ばれた画像はそのまま持たず、長辺を画面の長辺までに縮め、写真の向き（EXIF）を直した JPEG にして置く。
 * 数千万画素の写真をそのまま描くと、古いタブレットではメモリが足りずに落ちるため。
 */
class WallpaperStore(context: Context) {

    private val file = File(context.filesDir, "wallpaper.jpg")
    private val tmpFile = File(context.filesDir, "wallpaper.jpg.tmp")
    private val longSide = context.resources.displayMetrics.let { max(it.widthPixels, it.heightPixels) }

    /**
     * [open] が返す画像を縮小して保存する。読めない画像なら false（元の背景は残る）。
     * [open] は 2 回呼ぶ（大きさと向きを読むのと、本体を読むのと）。
     */
    fun save(open: () -> InputStream): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longSide) sample *= 2
        val decoded = open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: return false
        val rotation = runCatching { open().use { ExifInterface(it).rotationDegrees() } }.getOrDefault(0)

        val scale = (longSide.toFloat() / max(decoded.width, decoded.height)).coerceAtMost(1f)
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation.toFloat())
        }
        val fitted = if (scale < 1f || rotation != 0) {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }
        return runCatching {
            tmpFile.outputStream().use { fitted.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            if (!tmpFile.renameTo(file)) {
                file.writeBytes(tmpFile.readBytes())
                tmpFile.delete()
            }
        }.also { fitted.recycle() }.isSuccess
    }

    fun clear() {
        file.delete()
        tmpFile.delete()
    }

    /** 画面に描く大きさで読み出す。無い・読めないときは null。 */
    fun load(): Bitmap? = if (file.exists()) runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull() else null

    private fun ExifInterface.rotationDegrees(): Int = when (getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }

    companion object {
        /** Web の設定画面から送れる画像の大きさの上限。スマホの写真（数 MB）が通り、端末のメモリを圧迫しない程度。 */
        const val MAX_UPLOAD_BYTES = 25L * 1024 * 1024
    }
}
