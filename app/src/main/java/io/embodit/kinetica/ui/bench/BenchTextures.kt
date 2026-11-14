package io.embodit.kinetica.ui.bench

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.Log
import io.embodit.kinetica.domain.Layout
import io.embodit.kinetica.platform.TAG
import kotlin.math.cos
import kotlin.math.sin

/**
 * Canvas textures of the brass faces (tags, readout plates, nameplate, dial) and the two abstract marks (coach glow, held
 * rim). Image up = the face's up vector (D10, checked with [orientationTest] on every face via debug `ftest on`). Text is
 * engraved-ink #2A1E14 on brass (contrast measured on the rendered pixels, ui-audit). A line wider than its box logs
 * OVERFLOW and is drawn as is (design: shorten the copy, never shrink the type).
 */
object BenchTextures {
    val INK = Color.rgb(0x2A, 0x1E, 0x14)
    val BRASS = Color.rgb(0xC9, 0xA2, 0x5A)
    private val BRASS_LIGHT = Color.rgb(0xE2, 0xC2, 0x80)
    private val BRASS_DARK = Color.rgb(0xA8, 0x82, 0x40)
    @Volatile var testF = false

    private fun brassFace(w: Int, h: Int, glow: Boolean = false): Pair<Bitmap, Canvas> {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), BRASS_LIGHT, BRASS_DARK, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        // brushed lines
        val rnd = java.util.Random(7)
        p.strokeWidth = 1f
        for (i in 0 until h step 2) {
            p.color = Color.argb(10 + rnd.nextInt(18), 255, 255, 255)
            c.drawLine(0f, i.toFloat(), w.toFloat(), i.toFloat() + rnd.nextFloat() * 2f, p)
        }
        // engraved border
        p.style = Paint.Style.STROKE
        p.strokeWidth = h * 0.025f
        p.color = Color.argb(150, 0x5A, 0x40, 0x18)
        val m = h * 0.06f
        c.drawRoundRect(RectF(m, m, w - m, h - m), h * 0.08f, h * 0.08f, p)
        if (glow) {
            p.strokeWidth = h * 0.06f
            p.color = Color.rgb(0xFF, 0xF1, 0xC8)
            p.maskFilter = BlurMaskFilter(h * 0.05f, BlurMaskFilter.Blur.NORMAL)
            c.drawRoundRect(RectF(m, m, w - m, h - m), h * 0.08f, h * 0.08f, p)
        }
        return bmp to c
    }

    private fun ink(sizePx: Float, bold: Boolean = true) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK; textSize = sizePx; textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        fontFeatureSettings = "tnum" // tabular digits: numbers do not jump in width
    }

    private fun line(c: Canvas, text: String, paint: Paint, cx: Float, cy: Float, maxW: Float, tag: String) {
        val w = paint.measureText(text)
        if (w > maxW) Log.w(TAG, "OVERFLOW $tag \"$text\" ${w.toInt()} px > ${maxW.toInt()}")
        val fm = paint.fontMetrics
        c.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2, paint)
    }

    /** Hanger tag 512 x 256 for 0.08 x 0.04 m: line 1 the hanger (1.4 cm), line 2 the size + ↕ (1.0 cm). */
    fun tag(title: String, size: String, glow: Boolean, index: Int): Bitmap {
        if (testF) return orientationTest(Layout.TAG_PX_W, Layout.TAG_PX_H)
        val w = Layout.TAG_PX_W; val h = Layout.TAG_PX_H
        val (bmp, c) = brassFace(w, h, glow)
        val pxPerCm = h / (Layout.TAG_H * 100).toFloat()
        line(c, title, ink(1.4f * pxPerCm), w / 2f, h * 0.38f, w * 0.88f, "tag$index/title")
        line(c, size, ink(1.0f * pxPerCm, bold = false), w / 2f, h * 0.73f, w * 0.88f, "tag$index/size")
        return bmp
    }

    /** Readout plate 512 x 210 for 0.11 x 0.045 m: big 2.0 cm (measured), small 1.1 cm (ideal / answer). */
    fun plate(big: String, small: String, index: Int): Bitmap {
        if (testF) return orientationTest(Layout.PLATE_PX_W, Layout.PLATE_PX_H)
        val w = Layout.PLATE_PX_W; val h = Layout.PLATE_PX_H
        val (bmp, c) = brassFace(w, h)
        val pxPerCm = h / (Layout.PLATE_H * 100).toFloat()
        line(c, big, ink(2.0f * pxPerCm), w / 2f, h * 0.36f, w * 0.90f, "plate$index/big")
        // small line 1.1 cm (v0.1.1: cap ~7.7 mm >= 0.5° from the design eye at ~0.75 m, foreshortened)
        val sp = ink(1.1f * pxPerCm, bold = false)
        // an answer can be longer than the ideal line: wrap to two short lines rather than shrink
        if (sp.measureText(small) > w * 0.90f && small.contains(' ').not() && small.length > 8) {
            val half = small.length / 2
            line(c, small.substring(0, half), sp, w / 2f, h * 0.645f, w * 0.90f, "plate$index/small")
            line(c, small.substring(half), sp, w / 2f, h * 0.885f, w * 0.90f, "plate$index/small")
        } else if (sp.measureText(small) > w * 0.90f && small.contains(' ')) {
            val words = small.split(' ')
            var a = ""; var i = 0
            while (i < words.size && sp.measureText((a + " " + words[i]).trim()) <= w * 0.90f) { a = (a + " " + words[i]).trim(); i++ }
            line(c, a, sp, w / 2f, h * 0.645f, w * 0.90f, "plate$index/small")
            line(c, words.drop(i).joinToString(" "), sp, w / 2f, h * 0.885f, w * 0.90f, "plate$index/small")
        } else line(c, small, sp, w / 2f, h * 0.76f, w * 0.90f, "plate$index/small")
        return bmp
    }

    /** Nameplate plaque 512 x 194 for 0.09 x 0.034 m: KINETICA at 1.15 cm (cap ~0.8 cm, >= 0.5° from the design eye). */
    fun nameplate(text: String): Bitmap {
        if (testF) return orientationTest(Layout.NAMEPLATE_PX_W, Layout.NAMEPLATE_PX_H)
        val w = Layout.NAMEPLATE_PX_W; val h = Layout.NAMEPLATE_PX_H
        val (bmp, c) = brassFace(w, h)
        val pxPerCm = h / (Layout.NAMEPLATE_H * 100).toFloat()
        val p = ink(1.15f * pxPerCm).apply { letterSpacing = 0.08f; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }
        line(c, text, p, w / 2f, h / 2f, w * 0.92f, "nameplate")
        return bmp
    }

    private fun triangle(c: Canvas, cx: Float, cy: Float, size: Float, right: Boolean) {
        val path = android.graphics.Path()
        val s2 = size / 2
        if (right) { path.moveTo(cx - s2 * 0.8f, cy - s2); path.lineTo(cx + s2, cy); path.lineTo(cx - s2 * 0.8f, cy + s2) }
        else { path.moveTo(cx + s2 * 0.8f, cy - s2); path.lineTo(cx - s2, cy); path.lineTo(cx + s2 * 0.8f, cy + s2) }
        path.close()
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK })
    }

    /**
     * ▶ swing key face 512 x 366 for 0.056 x 0.040 m: a drawn ▶ (still) or ■ (moving) + the word at 0.95 cm
     * (cap ~0.67 cm; the design eye needs 0.56 cm at 0.62 m, face turned 0° from the line of sight).
     */
    fun key(word: String, moving: Boolean, index: Int): Bitmap {
        if (testF) return orientationTest(Layout.KEY_PX_W, Layout.KEY_PX_H)
        val w = Layout.KEY_PX_W; val h = Layout.KEY_PX_H
        val (bmp, c) = brassFace(w, h, glow = moving)
        val pxPerCm = h / (Layout.KEY_H * 100).toFloat()
        val p = ink(0.95f * pxPerCm)
        val tw = p.measureText(word)
        val icon = 0.75f * pxPerCm
        val gap = 0.25f * pxPerCm
        val total = icon + gap + tw
        val x0 = (w - total) / 2f
        if (total > w * 0.92f) Log.w(TAG, "OVERFLOW key$index \"$word\" ${total.toInt()} px > ${(w * 0.92f).toInt()}")
        if (moving) c.drawRect(x0, h / 2f - icon * 0.42f, x0 + icon * 0.84f, h / 2f + icon * 0.42f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK })
        else triangle(c, x0 + icon / 2, h / 2f, icon, right = true)
        val fm = p.fontMetrics
        p.textAlign = Paint.Align.LEFT
        c.drawText(word, x0 + icon + gap, h / 2f - (fm.ascent + fm.descent) / 2, p)
        return bmp
    }

    /** ◀ / ▶ planet key face 256 x 256 for 0.032 m (inside the round brass key). */
    fun arrow(next: Boolean): Bitmap {
        if (testF) return orientationTest(256, 256)
        val (bmp, c) = brassFace(256, 256)
        triangle(c, 128f + (if (next) 8f else -8f), 128f, 130f, right = next)
        return bmp
    }

    /**
     * Planet label 512 x 171 for 0.12 x 0.040 m: the name at 1.2 cm (cap ~0.84 cm), g at 1.05 cm (cap ~0.74 cm; the design
     * eye needs >= 0.64 cm at 0.73 m), four dots for Moon / Mars / Earth / Jupiter with the current one ringed.
     */
    fun planetLabel(name: String, g: String, current: Int): Bitmap {
        if (testF) return orientationTest(Layout.SEL_LABEL_PX_W, Layout.SEL_LABEL_PX_H)
        val w = Layout.SEL_LABEL_PX_W; val h = Layout.SEL_LABEL_PX_H
        val (bmp, c) = brassFace(w, h)
        val pxPerCm = h / (Layout.SEL_LABEL_H * 100).toFloat()
        line(c, name, ink(1.2f * pxPerCm).apply { letterSpacing = 0.04f }, w / 2f, h * 0.27f, w * 0.92f, "planet/name")
        line(c, g, ink(1.05f * pxPerCm, bold = false), w / 2f, h * 0.60f, w * 0.92f, "planet/g")
        val colors = intArrayOf(Color.rgb(0xB8, 0xB5, 0xAE), Color.rgb(0xC0, 0x5A, 0x2E), Color.rgb(0x2E, 0x6E, 0xB4), Color.rgb(0xD8, 0xB0, 0x80))
        val r = 0.22f * pxPerCm
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0..3) {
            val cx = w / 2f + (i - 1.5f) * 0.75f * pxPerCm; val cy = h * 0.86f
            p.style = Paint.Style.FILL; p.color = colors[i]; c.drawCircle(cx, cy, r, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = if (i == current) 5f else 2f; p.color = INK
            c.drawCircle(cx, cy, if (i == current) r + 6f else r, p)
        }
        return bmp
    }

    /** A dark card with warm text (「看正前方」 1.0 m ahead, the「摇杆左右转动」hint): [cm] = font size on a face [hM] tall. */
    fun card(text: String, wPx: Int, hPx: Int, hM: Double, cm: Float, tag: String): Bitmap {
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(INK)
        val pxPerCm = hPx / (hM * 100).toFloat()
        val p = ink(cm * pxPerCm).apply { color = Color.rgb(0xFF, 0xF1, 0xD6) }
        line(c, text, p, wPx / 2f, hPx / 2f, wPx * 0.94f, tag)
        return bmp
    }

    /** A soft warm ring (coach glow / held rim / hover), transparent centre. */
    fun ring(color: Int = Color.rgb(0xFF, 0xD9, 0xA0), size: Int = 256, inner: Float = 0.30f, outer: Float = 0.42f): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = size * (outer - inner)
            maskFilter = BlurMaskFilter(size * 0.03f, BlurMaskFilter.Blur.NORMAL)
        }
        c.drawCircle(size / 2f, size / 2f, size * (inner + outer) / 2, p)
        return bmp
    }

    /** "F↑" orientation test (profile D10): the F must read upright from the user with its arrow pointing up. */
    fun orientationTest(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = h * 0.7f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        c.drawText("F↑", w / 2f, h * 0.75f, p)
        return bmp
    }
}
