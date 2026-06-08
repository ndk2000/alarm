package com.example.cloud

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * QR 码生成工具
 */
object QrCodeUtils {

    /**
     * 将文本内容生成为 QR 码 Bitmap
     * @param content 要编码的文本（分享码）
     * @param size 二维码图片尺寸（px）
     */
    fun encodeToBitmap(content: String, size: Int = 512): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 生成带底部说明文字的二维码图片（二维码在上，文字在下）
     * @param content 要编码的文本（分享码）
     * @param caption 底部说明文字，如"由打卡闹钟分享"
     * @param qrSize 二维码尺寸（px）
     * @return 合成后的 Bitmap（宽度 = qrSize，高度 = qrSize + 文字区域）
     */
    fun encodeToBitmapWithCaption(
        content: String,
        caption: String,
        qrSize: Int = 512
    ): Bitmap? {
        val qrBitmap = encodeToBitmap(content, qrSize) ?: return null

        val captionText = "分享码: $content"
        val subText = caption

        // 计算文字区域高度
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = qrSize * 0.06f  // 相对二维码大小自适应
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.DKGRAY
            textSize = qrSize * 0.045f
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.CENTER
        }

        // 预留 padding + 两行文字高度 + 行间距
        val padding = (qrSize * 0.04f).toInt()
        val lineGap = (qrSize * 0.02f).toInt()
        val textHeight = (textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent).toInt()
        val subHeight = (subPaint.fontMetrics.descent - subPaint.fontMetrics.ascent).toInt()
        val extraHeight = padding * 2 + textHeight + lineGap + subHeight

        // 创建合成图
        val result = Bitmap.createBitmap(qrSize, qrSize + extraHeight, Bitmap.Config.RGB_565)
        val canvas = Canvas(result)
        canvas.drawColor(android.graphics.Color.WHITE)

        // 画二维码
        canvas.drawBitmap(qrBitmap, 0f, 0f, null)

        // 画第一行：分享码
        val textX = (qrSize / 2).toFloat()
        val textY = (qrSize + padding - textPaint.fontMetrics.ascent).toFloat()
        canvas.drawText(captionText, textX, textY, textPaint)

        // 画第二行：底部说明
        val subY = textY + lineGap + subHeight
        canvas.drawText(subText, textX, subY, subPaint)

        return result
    }
}
