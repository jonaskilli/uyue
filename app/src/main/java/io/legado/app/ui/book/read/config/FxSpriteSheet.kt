package io.legado.app.ui.book.read.config

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import io.legado.app.model.SceneEffectCue
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.min

/**
 * 开源特效序列帧接入层（可选，零素材也能跑）。
 *
 * 把 pixelfx / Effekseer EffectMaterials / Kenney / OpenGameArt 等导出的
 * 透明 PNG 精灵图放进 assets/fx/<fxId>.png，同名 .json 描述帧信息：
 *
 * 1) Aseprite 风格（pixelfx 的 `node tools/render.mjs <id> --sheet` 直接产出）：
 *    {"frames":[{"frame":{"x":0,"y":0,"w":64,"h":64},"duration":83}, ...]}
 * 2) 简写等宽网格：{"frames":12,"fps":12,"cols":4}
 * 3) 没有 json：按横向 8 帧、12fps 处理。
 *
 * 素材不存在时 draw() 返回 false，SceneEffectView 自动回退到
 * SceneEffectExtraRenderer 的 Canvas 算法实现，因此「算法特效」与
 * 「开源素材特效」共用同一个浮层、同一个 show/clear 生命周期。
 */
object FxSpriteSheet {

    private class Sheet(
        val bitmap: Bitmap,
        val rects: List<Rect>,
        val durationsMs: IntArray
    ) {
        val totalMs: Int = durationsMs.sum()
    }

    /** null 表示该 id 没有素材，缓存下来避免每帧反复 open assets */
    private val cache = HashMap<String, Sheet?>()

    fun draw(
        c: Canvas,
        context: Context,
        cue: SceneEffectCue,
        t: Double,
        w: Double,
        h: Double,
        k: Double,
        paint: Paint
    ): Boolean {
        val id = cue.fxId ?: return false
        val sheet = load(context, id) ?: return false
        if (sheet.rects.isEmpty() || sheet.totalMs <= 0) return false

        val elapsed = ((t * 1000).toLong() % sheet.totalMs).toInt()
        var acc = 0
        var frame = 0
        for (i in sheet.durationsMs.indices) {
            acc += sheet.durationsMs[i]
            frame = i
            if (elapsed < acc) break
        }
        val src = sheet.rects[frame]

        // 让素材在屏幕上占据合适比例：以 600 逻辑像素为基准，强度 1..4 略微放大
        val scale = (min(w, h) / 600.0) * k * (0.85 + cue.level * 0.12)
        val dw = src.width() * scale
        val dh = src.height() * scale
        val left = (w * 0.5 - dw / 2).toFloat()
        val top = (h * 0.45 - dh / 2).toFloat()
        val dst = RectF(left, top, left + dw.toFloat(), top + dh.toFloat())

        // 首尾各 0.15s 淡入淡出，避免素材硬切
        val dur = cue.durationMs / 1000.0
        val fade = minOf(1.0, t / 0.15, (dur - t) / 0.15).coerceAtLeast(0.0)
        paint.alpha = (255 * fade).toInt()
        c.drawBitmap(sheet.bitmap, src, dst, paint)
        return true
    }

    fun clearCache() {
        cache.clear()
    }

    private fun load(context: Context, id: String): Sheet? {
        if (cache.containsKey(id)) return cache[id]
        val sheet = try {
            val bmp = context.assets.open("fx/$id.png").use {
                BitmapFactory.decodeStream(it)
            } ?: throw IllegalStateException("decode failed")
            val meta = try {
                context.assets.open("fx/$id.json").use {
                    JSONObject(String(it.readBytes()))
                }
            } catch (e: Exception) {
                JSONObject()
            }
            buildSheet(bmp, meta)
        } catch (e: Exception) {
            null
        }
        cache[id] = sheet
        return sheet
    }

    private fun buildSheet(bmp: Bitmap, meta: JSONObject): Sheet {
        val arr = meta.optJSONArray("frames")
        if (arr is JSONArray && arr.length() > 0 && arr.optJSONObject(0) != null) {
            val rects = mutableListOf<Rect>()
            val durations = mutableListOf<Int>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val f = item.optJSONObject("frame") ?: continue
                rects.add(
                    Rect(
                        f.optInt("x"), f.optInt("y"),
                        f.optInt("x") + f.optInt("w"),
                        f.optInt("y") + f.optInt("h")
                    )
                )
                durations.add(item.optInt("duration", 83).coerceAtLeast(16))
            }
            return Sheet(bmp, rects, durations.toIntArray())
        }

        val frames = meta.optInt("frames", 8).coerceAtLeast(1)
        val fps = meta.optInt("fps", 12).coerceAtLeast(1)
        val cols = meta.optInt("cols", frames).coerceAtLeast(1)
        val rows = ceil(frames.toDouble() / cols).toInt().coerceAtLeast(1)
        val cw = bmp.width / cols
        val ch = bmp.height / rows
        val rects = (0 until frames).map { i ->
            val x = (i % cols) * cw
            val y = (i / cols) * ch
            Rect(x, y, x + cw, y + ch)
        }
        val duration = (1000 / fps).coerceAtLeast(16)
        return Sheet(bmp, rects, IntArray(frames) { duration })
    }
}
