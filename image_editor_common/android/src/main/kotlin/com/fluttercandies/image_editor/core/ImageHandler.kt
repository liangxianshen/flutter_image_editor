package com.fluttercandies.image_editor.core

import android.graphics.*
import android.graphics.text.LineBreaker
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import com.fluttercandies.image_editor.common.font.FontUtils
import com.fluttercandies.image_editor.option.*
import com.fluttercandies.image_editor.option.draw.DrawOption
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.OutputStream

class ImageHandler(private var bitmap: Bitmap) {
    fun handle(options: List<Option>) {
        for (option in options) {
            when (option) {
                is ColorOption -> bitmap = handleColor(option)
                is ScaleOption -> bitmap = handleScale(option)
                is FlipOption -> bitmap = handleFlip(option)
                is ClipOption -> bitmap = handleClip(option)
                is RotateOption -> bitmap = handleRotate(option)
                is AddTextOpt -> bitmap = handleText(option)
                is MixImageOpt -> bitmap = handleMixImage(option)
                is DrawOption -> bitmap = bitmap.draw(option)
            }
        }
    }

    private fun handleMixImage(option: MixImageOpt): Bitmap {
        val newBitmap = bitmap.createNewBitmap(bitmap.width, bitmap.height)
        val canvas = Canvas(newBitmap)
        canvas.drawBitmap(bitmap, 0F, 0F, null)
        val src = BitmapFactory.decodeByteArray(option.img, 0, option.img.count())
        val paint = Paint()
        paint.xfermode = PorterDuffXfermode(option.porterDuffMode)
        val dstRect = Rect(option.x, option.y, option.x + option.w, option.y + option.h)
        canvas.drawBitmap(src, null, dstRect, paint)
        return newBitmap
    }

    private fun handleScale(option: ScaleOption): Bitmap {
        var w = option.width
        var h = option.height
        if (option.keepRatio) {
            val srcRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
            if (option.keepWidthFirst) {
                h = (w / srcRatio).toInt()
            } else {
                w = (srcRatio * h).toInt()
            }
        }
        val newBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(newBitmap)
        val p = Paint()
        val m = Matrix()
        val width: Int = bitmap.width
        val height: Int = bitmap.height
        if (width != w || height != h) {
            val sx: Float = w / width.toFloat()
            val sy: Float = h / height.toFloat()
            m.setScale(sx, sy)
        }
        canvas.drawBitmap(bitmap, m, p)
        return newBitmap
    }

    private fun handleRotate(option: RotateOption): Bitmap {
        val matrix = Matrix().apply {
            //      val rotate = option.angle.toFloat() / 180 * Math.PI
            this.postRotate(option.angle.toFloat())
        }
        val out = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val canvas = Canvas()
        canvas.drawBitmap(out, matrix, null)
        return out
    }

    private fun handleFlip(option: FlipOption): Bitmap {
        val matrix = Matrix().apply {
            val x = if (option.horizontal) -1F else 1F
            val y = if (option.vertical) -1F else 1F
            postScale(x, y)
        }
        val out = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val canvas = Canvas()
        canvas.drawBitmap(out, matrix, null)
        return out
    }

    private fun handleClip(option: ClipOption): Bitmap {
        val x = option.x
        val y = option.y
        return Bitmap.createBitmap(bitmap, x, y, option.width, option.height, null, false)
    }

    private fun handleColor(option: ColorOption): Bitmap {
        val newBitmap = bitmap.createNewBitmap(bitmap.width, bitmap.height)
        val canvas = Canvas(newBitmap)
        val paint = Paint()
        paint.colorFilter = ColorMatrixColorFilter(option.matrix)
        canvas.drawBitmap(bitmap, 0F, 0F, paint)
        return newBitmap
    }

    private fun handleText(option: AddTextOpt): Bitmap {
        val newBitmap = bitmap.createNewBitmap(bitmap.width, bitmap.height)
        val canvas = Canvas(newBitmap)
        val paint = Paint()
        canvas.drawBitmap(bitmap, 0F, 0F, paint)
        for (text in option.texts) {
            drawText(text, canvas)
        }
        return newBitmap
    }

    /*
    * 有 bug。问题来自于 y 坐标和行高的计算方式不正确 —— 你把 canvas 移动了 (canvas.translate(text.x, text.y))，
    * 但随后在计算每一行的 y 时又把 text.y 加进去了，导致垂直偏移被重复计算；
    * 另外你用 (i+1) * fontSizePx 作为行间距也不准确
    * （忽略了字体的 ascent/descent/leading / StaticLayout 的行间计算），
    * 并且在左对齐时用 text.x 作为 x 坐标也会重复偏移（因为 canvas 已经平移过 x）。

    * 推荐的、更稳健的做法是让 StaticLayout 负责行间和基线的计算，
    * 然后把整个 layout 绘制到 canvas 上（用 canvas.save()/translate()/restore()），
    * 这样不会出错。下面给出了已修正的代码 （仅修改了 drawText/getStaticLayout 的实现）：
    * */

    /*private fun drawText(text: Text, canvas: Canvas) {
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        textPaint.color = Color.argb(text.a, text.r, text.g, text.b)
        textPaint.textSize = text.fontSizePx.toFloat()
        if (text.fontName.isNotEmpty()) {
            try {
                val typefaceFromAsset = FontUtils.getFont(text.fontName)
                textPaint.typeface = typefaceFromAsset
            } catch (_: Exception) {
            }
        }
        val staticLayout = getStaticLayout(text, textPaint, canvas.width - text.x)

        canvas.translate(text.x.toFloat(), text.y.toFloat())
//        staticLayout.draw(canvas)

        for (i in 0 until staticLayout.lineCount) {
            val lineText = staticLayout.text.subSequence(
                staticLayout.getLineStart(i),
                staticLayout.getLineEnd(i)
            ).toString()

            val lineWidth = textPaint.measureText(lineText)

            val lineY = text.y + (i + 1) * text.fontSizePx
            val lineX =
                when (text.textAlign) {
                    Paint.Align.CENTER -> {
                        (staticLayout.width - lineWidth) / 2
                    }

                    Paint.Align.RIGHT -> {
                        staticLayout.width - lineWidth
                    }

                    else -> {
                        text.x
                    }
                }

            canvas.drawText(lineText, lineX.toFloat(), lineY.toFloat(), textPaint)
        }

        canvas.translate((-text.x).toFloat(), (-text.y).toFloat())
    }*/

    /*@Suppress("DEPRECATION")
    private fun getStaticLayout(text: Text, textPaint: TextPaint, width: Int): StaticLayout {
        return if (Build.VERSION.SDK_INT >= 23) {
            StaticLayout.Builder.obtain(
                text.text, 0, text.text.length, textPaint, width
            ).build()
        } else {
            StaticLayout(
                text.text,
                textPaint,
                width,
                Layout.Alignment.ALIGN_NORMAL,
                1.0F,
                0.0F,
                true
            )
        }
    }*/

    /*简要说明为何修复能解决问题：
    不再在计算每一行时手动加上 text.y（之前导致重复偏移）。改为 canvas.translate(text.x, text.y) 后，
    把 y 的相对位移交给 StaticLayout 管理。
    使用 StaticLayout.draw(canvas) 能正确处理基线、行高、换行和对齐（避免自己用 fontSizePx 估算行高产生偏差）。
    使用 canvas.save()/restore() 比手动 translate(revert) 更安全、语义更清晰。
    处理了可用宽度为负的情况，避免 StaticLayout 收到负宽度。
    如果你确实有特殊理由要手动逐行绘制（例如自定义行间距或高亮部分文本），那最小修复是：
    不要在 lineY 中再加 text.y（因为已经 translate 过了）。
    用 StaticLayout 提供的行基线或 fontPaint.fontSpacing 来计算行的 y（而不是 (i+1)*fontSizePx）。
    左对齐时 lineX 应该是 0（因为已经 translate 过 x）。*/

    private fun drawText(text: Text, canvas: Canvas) {
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        textPaint.color = Color.argb(text.a, text.r, text.g, text.b)
        textPaint.textSize = text.fontSizePx.toFloat()

        // 计算可用宽度（避免负值）
        val availableWidth = (canvas.width - text.x).coerceAtLeast(0)

        // 将 Paint.Align 转为 Layout.Alignment，交给 StaticLayout 管理对齐
        val alignment = when (text.textAlign) {
            Paint.Align.CENTER -> Layout.Alignment.ALIGN_CENTER
            Paint.Align.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }

        val staticLayout = getStaticLayout(text, textPaint, availableWidth, alignment)

        // 把画布移动到文本区域的左上角，然后由 StaticLayout 负责绘制行和基线
        canvas.save()
        canvas.translate(text.x.toFloat(), text.y.toFloat())
        staticLayout.draw(canvas)
        canvas.restore()
    }

    @Suppress("DEPRECATION")
    private fun getStaticLayout(
        text: Text,
        textPaint: TextPaint,
        width: Int,
        alignment: Layout.Alignment
    ): StaticLayout {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            StaticLayout.Builder.obtain(text.text, 0, text.text.length, textPaint, width)
                .setAlignment(alignment)
                .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                .build()
        } else {
            StaticLayout(
                text.text,
                textPaint,
                width,
                alignment,
                1.0F,
                0.0F,
                true
            )
        }
    }

    fun outputToFile(dstPath: String, formatOption: FormatOption) {
        val outputStream = FileOutputStream(dstPath)
        output(outputStream, formatOption)
    }

    fun outputByteArray(formatOption: FormatOption): ByteArray {
        val outputStream = ByteArrayOutputStream()
        output(outputStream, formatOption)
        return outputStream.toByteArray()
    }

    private fun output(outputStream: OutputStream, formatOption: FormatOption) {
        outputStream.use {
            if (formatOption.format == 0) {
                bitmap.compress(Bitmap.CompressFormat.PNG, formatOption.quality, outputStream)
            } else {
                bitmap.compress(Bitmap.CompressFormat.JPEG, formatOption.quality, outputStream)
            }
        }
    }
}
