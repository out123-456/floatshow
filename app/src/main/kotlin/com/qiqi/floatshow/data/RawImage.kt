package com.qiqi.floatshow.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import kotlin.math.min

/**
 * 大图的 raw 像素缓存.
 *
 * 为什么需要:PNG 的行是顺序 zlib 流, 行 filter 还依赖上一行, 所以不存在"只解一块"的可能.
 * 每次想看看图的某一部分, 都得从文件头 inflate 到那一行. 8192 的图拖到下半部, 一次就要
 * 解压近整图. 唯一的出路是把解压结果留在磁盘, 之后按字节偏移直接读, 与压缩格式彻底脱钩.
 *
 * 文件格式: 固定 24 字节头 + 行优先的 ARGB 像素.
 * 读取用 mmap, 只把真正要读的页拉进内存, 所以显示的常驻内存只与屏幕尺寸有关, 与原图无关.
 */
object RawImage {

    private const val MAGIC = "FSRAW"
    private const val VERSION = 1

    /** 头长度: magic(5) + version(4) + width(4) + height(4) + padding(7) */
    const val HEADER = 24

    /** 一次转换里每次读写的行数, 用来给进度用, 也避免一次分配巨大数组 */
    private const val BAND = 128

    fun dir(context: Context): File =
        File(context.filesDir, "raw").also { if (!it.isDirectory) it.mkdirs() }

    /** 同一张源图固定对应同一个缓存文件, 用 uri 的 SHA-1 当文件名 */
    fun fileFor(context: Context, uri: Uri): File {
        val key = runCatching {
            val md = MessageDigest.getInstance("SHA-1")
            md.update(uri.toString().toByteArray())
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrElse { "u" + uri.toString().hashCode() }
        return File(dir(context), "$key.raw")
    }

    fun exists(context: Context, uri: Uri): Boolean {
        val file = fileFor(context, uri)
        return file.isFile && file.length() > HEADER
    }

    fun delete(context: Context, uri: Uri): Boolean = fileFor(context, uri).delete()

    /**
     * 把整图解压成裸像素文件.
     *
     * 峰值内存是一次原图大小(8192² 的 ARGB 约 256MB, RGB565 约 128MB), 解完立刻丢弃位图,
     * 只留磁盘文件. 先试 ARGB_8888 保真, 失败再退 RGB_565.
     * [onProgress] 收到 0..100, 用于线性进度条.
     */
    fun convert(context: Context, uri: Uri, onProgress: (Int) -> Unit): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return false

        var bitmap: Bitmap? = null
        for (config in CONFIGS) {
            val options = BitmapFactory.Options().apply { inPreferredConfig = config }
            bitmap = runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
            }.getOrNull()
            if (bitmap != null) break
        }
        // 整图都解不出来就没有 raw 的意义
        val source = bitmap ?: return false
        onProgress(25)

        val target = fileFor(context, uri)
        val temp = File(target.parentFile, target.name + ".tmp")
        val ok = runCatching {
            FileOutputStream(temp).use { out ->
                val header = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
                header.put(MAGIC.toByteArray())
                header.putInt(VERSION)
                header.putInt(source.width)
                header.putInt(source.height)
                out.write(header.array())

                val rows = min(BAND, source.height)
                val pixels = IntArray(source.width * rows)
                // 读写字节序必须一致, 否则每个像素的 A/R/G/B 会被倒过来
                val bytes = ByteBuffer.allocate(source.width * rows * 4).order(ByteOrder.LITTLE_ENDIAN)
                var y = 0
                while (y < source.height) {
                    val count = min(BAND, source.height - y)
                    // getPixels 支持矩形区域, 这样不必为了写一个 int 数组而整图再拷一份
                    source.getPixels(pixels, 0, source.width, 0, y, source.width, count)
                    bytes.clear()
                    bytes.asIntBuffer().put(pixels, 0, source.width * count)
                    out.write(bytes.array(), 0, source.width * count * 4)
                    y += count
                    onProgress(25 + y * 75 / source.height)
                }
            }
            if (!temp.renameTo(target)) {
                // renameTo 在跨文件系统时会失败, 退回拷贝
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            true
        }.getOrDefault(false)

        source.recycle()
        if (!ok) temp.delete()
        if (ok) onProgress(100)
        return ok
    }

    private val CONFIGS = listOf(Bitmap.Config.ARGB_8888, Bitmap.Config.RGB_565)
}

/**
 * mmap 打开的 raw 像素文件. 只做两件事: 报告尺寸, 读一段行.
 * 读取走映射区间, 没有把整份文件读进堆里.
 */
class RawReader(file: File) {

    private val raf = RandomAccessFile(file, "r")
    private val channel: FileChannel = raf.channel
    private val mapped: MappedByteBuffer

    val width: Int
    val height: Int

    init {
        val size = channel.size()
        require(size > RawImage.HEADER) { "raw 文件损坏" }
        mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
        val header = mapped.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        header.position(5)
        header.getInt() // version, 目前只有一个版本
        width = header.getInt()
        height = header.getInt()
        require(width > 0 && height > 0) { "raw 尺寸无效" }
    }

    /**
     * 读出矩形 [x0, x0+w) × [y0, y0+rows), 按行优先写进 [out] 的前 w*rows 个元素.
     *
     * 必须带列区间: 文件是按整行存的, 而调用方要的是视口那么宽的一块.
     * 曾经这里只按整行读, 8192 宽的图要往 1156 宽的数组里塞 8192*rows 个 int,
     * 越界异常被吞掉, 表现就是 raw 永远画不出来.
     *
     * 用 duplicate(): MappedByteBuffer 的 position 是共享状态, 直接改会互相打架.
     */
    fun readRows(x0: Int, y0: Int, w: Int, rows: Int, out: IntArray) {
        val view = mapped.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val rowBytes = width.toLong() * 4
        val base = RawImage.HEADER.toLong() + y0.toLong() * rowBytes + x0.toLong() * 4
        for (r in 0 until rows) {
            val start = (base + r * rowBytes).toInt()
            view.position(start)
            view.asIntBuffer().get(out, r * w, w)
        }
    }

    fun close() {
        runCatching { channel.close() }
        runCatching { raf.close() }
    }
}
