package com.example

import com.example.geodesy.FreeTextCoordinateParser
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class FreeTextCoordinateParserTest {

    private val EPS = 0.01

    @Test
    fun testMgrs_InText() {
        val text = "Координаты точки высадки: 36U UA 24182 91607, прибыть до рассвета."
        val result = FreeTextCoordinateParser.parse(text)
        assertEquals(1, result.points.size)
        val p = result.points[0]
        assertEquals(50.4501, p.lat, EPS)
        assertEquals(30.5234, p.lon, EPS)
        assertTrue(p.label.contains("MGRS"))
        assertFalse(p.outsideUkraine)
    }

    @Test
    fun testMgrs_ZoneLessThan10_Warning() {
        val text = "Координаты цели: 6U UA 24182 91607, огонь по готовности."
        val result = FreeTextCoordinateParser.parse(text)
        assertEquals(0, result.points.size)
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings[0].contains("номер зоны 6 < 10"))
    }

    @Test
    fun testGaussKruger_ExplicitLabels_BothOrders() {
        // X then Y
        val text1 = "Позиция: X=5412345 Y=6312345 (зона 6)"
        val res1 = FreeTextCoordinateParser.parse(text1)
        assertEquals(1, res1.points.size)
        assertTrue(res1.points[0].label.contains("Гаусс-Крюгер"))

        // Y then X
        val text2 = "Ориентир 2: Y: 6312345, X: 5412345"
        val res2 = FreeTextCoordinateParser.parse(text2)
        assertEquals(1, res2.points.size)
        assertTrue(res2.points[0].label.contains("Гаусс-Крюгер"))
        assertEquals(res1.points[0].lat, res2.points[0].lat, EPS)
        assertEquals(res1.points[0].lon, res2.points[0].lon, EPS)
    }

    @Test
    fun testTruncatedGaussKruger_NoLabels() {
        // 6 digits each: X=412345 (millions omitted -> candidate 5 412 345), Y=312345 (zone omitted -> candidate zone 6 = 6 312 345)
        val text = "Только цифры: 412345 312345"
        val result = FreeTextCoordinateParser.parse(text)
        assertEquals(1, result.points.size)
        val p = result.points[0]
        assertTrue(p.label.contains("Гаусс-Крюгер"))
        assertEquals(6, p.label.filter { it.isDigit() }.toInt())
        assertFalse(p.outsideUkraine)
    }

    @Test
    fun testDecimalDegrees_WithDateProtection() {
        val text = "встреча 12.05.2026, координаты 48.4647, 35.0462"
        val result = FreeTextCoordinateParser.parse(text)
        assertEquals(1, result.points.size)
        val p = result.points[0]
        assertEquals(48.4647, p.lat, EPS)
        assertEquals(35.0462, p.lon, EPS)
        assertEquals("DEC", p.label)
        assertFalse(p.outsideUkraine)
    }

    @Test
    fun testUkrainianShDFormat() {
        val text = "Донесение: цель обнаружена Ш49.64 Д36.97, требуется подтверждение"
        val result = FreeTextCoordinateParser.parse(text)
        assertEquals(1, result.points.size)
        val p = result.points[0]
        assertEquals(49.64, p.lat, EPS)
        assertEquals(36.97, p.lon, EPS)
        assertEquals("Ш/Д", p.label)
        assertFalse(p.outsideUkraine)
    }

    @Test
    fun testMultiplePoints_DifferentFormats_MaskingWorks() {
        val text = """
            Сводка по целям:
            1. MGRS: 36U UA 24182 91607 (штаб)
            2. Дата 18.09.2026 14:30 позиция Ш49.64 Д36.97
            3. Запасной пункт: 48°27'53.2"N 35°02'46.1"E
            4. Дополнительно: X=5412345, Y=6312345
        """.trimIndent()

        val result = FreeTextCoordinateParser.parse(text)
        assertEquals(4, result.points.size)

        val labels = result.points.map { it.label }
        assertTrue(labels.any { it.contains("MGRS") })
        assertTrue(labels.any { it == "Ш/Д" })
        assertTrue(labels.any { it == "DMS" })
        assertTrue(labels.any { it.contains("Гаусс-Крюгер") })
    }

    @Test
    fun testDmsVariants() {
        // DMS with hyphens
        val text1 = "Координаты: 48-27-53.2, 35-02-46.1"
        val res1 = FreeTextCoordinateParser.parse(text1)
        assertEquals(1, res1.points.size)
        assertEquals(48.4647, res1.points[0].lat, EPS)
        assertEquals(35.0461, res1.points[0].lon, EPS)

        // DMS without symbols (three integers with space)
        val text2 = "Координаты: 48 27 53.2 N, 35 02 46.1 E"
        val res2 = FreeTextCoordinateParser.parse(text2)
        assertEquals(1, res2.points.size)
        assertEquals(48.4647, res2.points[0].lat, EPS)
        assertEquals(35.0461, res2.points[0].lon, EPS)

        // DM format
        val text3 = "Координаты: 48° 27.887' N, 35° 02.771' E"
        val res3 = FreeTextCoordinateParser.parse(text3)
        assertEquals(1, res3.points.size)
        assertEquals(48.4647, res3.points[0].lat, EPS)
        assertEquals(35.0461, res3.points[0].lon, EPS)
    }

    @Test
    fun testPrepositions_DoNotForceHemisphereAxis() {
        // "в" и "с" — предлоги, не должны читаться как обозначение полушария.
        // Числа рядом с ними по-прежнему МОГУТ быть найдены другими эвристиками
        // (диапазон/UA_BOX), но не должны получить принудительную, потенциально
        // неверную ось только из-за соседства с предлогом.
        val result = FreeTextCoordinateParser.parse(
            "выступаем в 15.00, с 3 бойцами дойдём до рубежа, доложить в 300 м"
        )
        // Ни одно из этих чисел не должно дать координатную пару —
        // это обычный текст без координат.
        assertTrue(result.points.isEmpty())
    }

    @Test
    fun testDecimalPair_NotMisorderedByAdjacentPreposition() {
        // Настоящая пара координат, но с предлогом "в" случайно прямо перед ней.
        val result = FreeTextCoordinateParser.parse("прибыли в 48.4647, 35.0462")
        assertEquals(1, result.points.size)
        // Широта Украины ~48-54, долгота ~22-40 — проверяем, что порядок не спутан
        // из-за предлога "в" перед первым числом.
        assertEquals(48.4647, result.points[0].lat, 0.0001)
        assertEquals(35.0462, result.points[0].lon, 0.0001)
    }
}
