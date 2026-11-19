package com.offerpilot.core

import com.offerpilot.core.model.Box
import com.offerpilot.core.model.OfferSource
import com.offerpilot.core.model.ScreenText
import com.offerpilot.core.model.TextNode
import com.offerpilot.core.model.Verdict
import com.offerpilot.core.parser.AdapterResult
import com.offerpilot.core.parser.UberDriverAdapter
import com.offerpilot.core.rules.OfferEvaluator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 真实 Uber Driver 订单弹窗（2026-11-19，Android 15，London ON）。
 * 无障碍节点在弹窗期间被隐藏，以下文字取自同一时刻的截图（即 OCR 路径会看到的内容），
 * 取货 / 送达地址已删除。行坐标按截图（1216×2688）近似。
 */
class RealOfferTest {
    private fun ocrScreen(vararg lines: Pair<String, Box>) =
        ScreenText(UBER, lines.map { (t, b) -> TextNode(t, bounds = b) }, 1_000L, OfferSource.OCR)

    private val popup = ocrScreen(
        "Delivery" to Box(150, 1495, 380, 1565),
        "Exclusive" to Box(470, 1495, 690, 1565),
        "CA$5.50" to Box(110, 1600, 610, 1730),
        "including estimated tip" to Box(130, 1755, 640, 1835),
        "15 min (1.3 km) total" to Box(210, 1925, 710, 2005),
        "Accept" to Box(520, 2445, 700, 2525),
    )

    @Test fun real_popup_parsed() {
        val o = assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(popup))
        assertTrue(o.complete)
        assertEquals(5.50, o.offer.estimatedPayout)
        assertEquals(1.3, o.offer.distanceKm)
        assertEquals(15.0, o.offer.durationMinutes)
        assertEquals(OfferSource.OCR, o.offer.source)
        assertTrue(o.offer.confidence >= 0.6f)
        // 达标 1.3 × 0.236 + 15 × 0.30 = 4.81；不含小费 5.50 × 0.8 = 4.40；比值 0.91 → 红（不达标）
        assertEquals(Verdict.LOSS, OfferEvaluator().evaluate(o.offer).verdict)
    }

    @Test fun earnings_pill_does_not_confuse_payout() {
        // 弹窗外顶部的今日收入 "CA$0.00" 若被一并识别，不应影响报价（0 被过滤）
        val withPill = ocrScreen("CA$0.00" to Box(407, 137, 729, 239), *popup.nodes.map { it.text to it.bounds!! }.toTypedArray())
        val o = assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(withPill))
        assertEquals(5.50, o.offer.estimatedPayout)
    }
}

/** 真机 OCR 误读修正。 */
class OcrRepairTest {
    private fun fix(s: String) = com.offerpilot.core.parser.OfferParser.repairOcrPriceLine(s).first

    @Test fun missing_decimal_point() {
        assertEquals("CA$6.78", fix("CA$678"))
        assertEquals("CA$12.50", fix("CA$1250"))
    }

    @Test fun decimal_read_as_other_char() {
        assertEquals("CA$6.78", fix("CA$6 78"))
        assertEquals("CA$6.78", fix("CA$6,78"))
        assertEquals("CA$6.78", fix("CAS6.78"))
    }

    @Test fun correct_lines_untouched_or_equivalent() {
        assertEquals("CA$5.05", fix("CA$5.05"))
        assertEquals(false, com.offerpilot.core.parser.OfferParser.repairOcrPriceLine("CA$5.05").second)
        assertEquals("15 min (3.4 km) total", fix("15 min (3.4 km) total"))
        assertEquals("including estimated tip", fix("including estimated tip"))
        assertEquals("S12", fix("S12"))
    }

    @Test fun real_second_popup_with_own_overlay_text() {
        // 真机 OCR（Uber 在桌面上方弹单，全屏截图）原样输出，含本应用悬浮条被识别出的 "F$5/ 3km A$17/h"
        val lines = listOf(
            "07:39 e", "N", "N", "A", "F$5/ 3km A$17/h", "CLARKE RD", "ARGYLE", "ARGYLE PARK", "Y Delivery Exclusive",
            "CA$5.05", "including estimated tip", "() 15 min (3.4 km) total", "HeBlSu 100", "TRAFALGAR ST", "Accept",
        )
        val screen = ScreenText(UBER, lines.map { TextNode(it) }, 1_000L, OfferSource.OCR)
        val o = assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(screen)).offer
        assertEquals(5.05, o.estimatedPayout)
        assertEquals(3.4, o.distanceKm)
        assertEquals(15.0, o.durationMinutes)
    }

    @Test fun ocr_678_parsed_as_6_78() {
        val screen = ScreenText(
            UBER, listOf("Delivery", "CA$678", "including estimated tip", "12 min (2.1 km) total", "Accept").map { TextNode(it) },
            1_000L, OfferSource.OCR,
        )
        val o = assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(screen)).offer
        assertEquals(6.78, o.estimatedPayout)
    }
}
