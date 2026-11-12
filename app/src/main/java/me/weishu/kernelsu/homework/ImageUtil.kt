package me.weishu.kernelsu.homework

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** 图片解码 / 压缩 / 缩略图。 */
object ImageUtil {

    /** 上传图的长边上限。作业本上的字很小，分辨率不够就会糊成一片。 */
    const val UPLOAD_MAX_SIDE = 2048

    /** 单张 base64 的体积预算（字节）。超了就自动降质量，避免请求体过大被拒。 */
    private const val BUDGET_BYTES = 1_200_000

    /** 依次尝试的质量档位：从清晰到模糊，取第一个不超预算的。 */
    private val QUALITY_STEPS = intArrayOf(95, 92, 88, 82, 75)

    /** 质量都压不下去时，依次降到的长边。 */
    private val SIDE_STEPS = intArrayOf(2048, 1600, 1280, 1024)

    /** 解码并按最长边缩放到 maxSide。 */
    fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val in0 = context.contentResolver.openInputStream(uri)
            ?: throw IOException("打不开图片")
        in0.use { input -> BitmapFactory.decodeStream(input, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("图片读不出来")
        }
        // inSampleSize 只能取 2 的幂，直接套会让 3000px 的图被砍到 1500px 再拉回 2048，
        // 白白损失细节。所以降到「略大于目标」即可，剩下的交给下面高质量缩放。
        var sample = 1
        var side = maxOf(bounds.outWidth, bounds.outHeight)
        while (side / (sample * 2) >= maxSide) sample *= 2

        val opt = BitmapFactory.Options().apply { inSampleSize = sample }
        val bm = context.contentResolver.openInputStream(uri)
            ?.use { input -> BitmapFactory.decodeStream(input, null, opt) }
            ?: throw IOException("图片解码失败")

        val m = maxOf(bm.width, bm.height)
        return if (m > maxSide) {
            val s = maxSide / m.toFloat()
            val scaled = Bitmap.createScaledBitmap(
                bm,
                maxOf(1, (bm.width * s).toInt().coerceAtLeast(1)),
                maxOf(1, (bm.height * s).toInt().coerceAtLeast(1)),
                true
            )
            if (scaled != bm) bm.recycle()
            scaled
        } else {
            bm
        }
    }

    /**
     * 上传给模型用的 base64（JPEG），自适应体积与内存。
     *
     * 先按最高清晰度压，超出预算才逐级降级 —— 拍题图片以文字为主，
     * JPEG 质量一低边缘就糊、模型认不出，所以默认往清楚了压。
     */
    fun jpegBase64(context: Context, uri: Uri): String {
        val full = decode(context, uri, UPLOAD_MAX_SIDE)
        var best: ByteArray? = null
        try {
            for (side in SIDE_STEPS) {
                val bm = if (side == UPLOAD_MAX_SIDE) full else resize(full, side)
                try {
                    for (q in QUALITY_STEPS) {
                        val j = toJpeg(bm, q)
                        // base64 后体积约为原始的 4/3
                        if (j.size * 4 / 3 <= BUDGET_BYTES) return b64(j)
                        if (best == null) best = j
                    }
                } finally {
                    if (bm != full) bm.recycle()
                }
            }
            return best?.let { b64(it) } ?: b64(toJpeg(full, 75))
        } finally {
            full.recycle()
        }
    }

    private fun toJpeg(bm: Bitmap, quality: Int): ByteArray {
        val bo = ByteArrayOutputStream()
        bm.compress(Bitmap.CompressFormat.JPEG, quality, bo)
        return bo.toByteArray()
    }

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)

    /** 等比缩到长边 side。 */
    private fun resize(src: Bitmap, side: Int): Bitmap {
        val m = maxOf(src.width, src.height)
        if (m <= side) return src
        val s = side / m.toFloat()
        return Bitmap.createScaledBitmap(
            src,
            maxOf(1, (src.width * s).toInt().coerceAtLeast(1)),
            maxOf(1, (src.height * s).toInt().coerceAtLeast(1)),
            true
        )
    }

    /** 缩略图，用于界面预览。 */
    fun thumb(context: Context, uri: Uri, size: Int): Bitmap? = try {
        decode(context, uri, size)
    } catch (_: Throwable) {
        null
    }

    /** 把图片存成历史记录里的小图，返回路径。 */
    fun saveThumb(context: Context, uri: Uri, dir: File, name: String): String? = try {
        val bm = decode(context, uri, 360)
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, name)
        FileOutputStream(f).use { fo -> bm.compress(Bitmap.CompressFormat.JPEG, 75, fo) }
        bm.recycle()
        f.absolutePath
    } catch (_: Throwable) {
        null
    }

    /** 打开图片流；失败抛 [IOException]。 */
    fun open(context: Context, uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw IOException("打不开图片")
}
