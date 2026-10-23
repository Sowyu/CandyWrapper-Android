package org.anikodev.candywrapper.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.anikodev.candywrapper.core.orchestrator.BotColor
import org.anikodev.candywrapper.core.orchestrator.BotGaze
import org.anikodev.candywrapper.core.orchestrator.BotGeometry
import org.anikodev.candywrapper.core.orchestrator.BotIdentity
import org.anikodev.candywrapper.core.orchestrator.BotMood
import org.anikodev.candywrapper.core.orchestrator.BotShape

class BotGeometryTest {
    /** 21 yaws by 15 pitches, beyond the maximum turn so the clamp does the work. */
    @Test
    fun everyPoseKeepsTheEyesOffTheEdge() {
        val start = System.nanoTime()
        val reach = BotGeometry.maxTurn * 1.2
        val poses = (0 until 21).flatMap { i ->
            (0 until 15).map { j -> BotGaze(-reach + 2 * reach * i / 20, -reach + 2 * reach * j / 14) }
        }
        assertEquals(315, poses.size)
        for (shape in BotShape.entries) {
            for (pose in poses) {
                val gaze = BotGeometry.clamp(pose, shape)
                assertTrue(BotGeometry.eyesClear(shape, gaze), "$shape brings an eye too close to the edge at $gaze")
            }
        }
        println("315-pose sweep across ${BotShape.entries.size} shapes: ${(System.nanoTime() - start) / 1_000_000} ms")
    }

    @Test
    fun everyShapeCanTurn() {
        for (shape in BotShape.entries) {
            val limits = BotGeometry.turnLimits(shape)
            assertEquals(32, limits.size)
            assertTrue(BotGeometry.eyesClear(shape, BotGaze.FRONT), "$shape fails facing front")
            assertTrue(limits.min() > 0.15, "$shape can barely turn: ${limits.toList()}")
        }
    }

    @Test
    fun theStarTurnsTheLeast() {
        assertEquals(BotShape.STAR, BotShape.entries.minBy { BotGeometry.turnLimits(it).sum() })
    }

    @Test
    fun posesShareTheirPointCount() {
        val identity = BotIdentity.of(BotShape.PUMPKIN, BotColor.BLUE)
        assertEquals(BotColor.ORANGE, identity.color)
        val a = BotGeometry.pose(identity, BotGaze.FRONT, 40f)
        val b = BotGeometry.pose(identity, BotGaze(0.6, -0.4), 40f)
        assertEquals(List(14) { 64 * 2 }, a.body.map { it.size })
        assertEquals(List(2) { 16 * 2 }, a.eyes.map { it.size })
        assertEquals(a.body.map { it.size }, b.body.map { it.size })
        assertEquals(a.eyes.map { it.size }, b.eyes.map { it.size })
        assertEquals(8, assertNotNull(a.stem).size)
        assertEquals(a.stem.size, assertNotNull(b.stem).size)
        assertEquals(BotGeometry.lineWidth(identity, 40f), a.lineWidth)
        for (pose in listOf(a, b)) for (poly in pose.body) for (coordinate in poly) {
            assertTrue(coordinate - pose.lineWidth / 2 >= -0.5f, "Body crosses the top or left edge")
            assertTrue(coordinate + pose.lineWidth / 2 <= 40.5f, "Body crosses the bottom or right edge")
        }
        // The stem sits above the centre in Android's downward-pointing y coordinates.
        assertTrue(a.stem.indices.filter { it % 2 == 1 }.all { a.stem[it] < 20f })
        assertNull(BotGeometry.pose(BotIdentity.ORCHESTRATOR, BotGaze.FRONT, 40f).stem)
    }

    @Test
    fun theOrchestratorKeepsThePickedLook() {
        assertEquals(BotIdentity.ORCHESTRATOR, BotIdentity.fromRaw(null, null))
        assertEquals(BotIdentity.ORCHESTRATOR, BotIdentity.fromRaw(-1, 11))
        assertEquals(BotIdentity.ORCHESTRATOR, BotIdentity.fromRaw(14, -1))
        assertEquals(BotIdentity.of(BotShape.DROP, BotColor.TEAL), BotIdentity.fromRaw(7, 6))
        assertEquals(BotIdentity.of(BotShape.DROP, BotColor.VIOLET), BotIdentity.fromRaw(7, null))
        assertEquals(BotIdentity.of(BotShape.FLOWER, BotColor.TEAL), BotIdentity.fromRaw(null, 6))
        assertEquals(BotColor.ORANGE, BotIdentity.fromRaw(13, 6).color)
        assertEquals(0xFF8A3A, BotColor.ORANGE.rgb)
    }

    @Test
    fun restingGazeMatchesTheMoodAndIdentity() {
        val identity = BotIdentity.ORCHESTRATOR
        assertEquals(BotGaze(0.0, 0.5), BotMood.NEEDS_YOU.restingGaze(identity))
        for (mood in listOf(BotMood.BLOCKED, BotMood.ARCHIVED)) {
            assertEquals(BotGaze(0.0, -0.45), mood.restingGaze(identity))
        }
        for (mood in listOf(BotMood.IDLE, BotMood.WORKING, BotMood.UNREAD)) {
            assertEquals(-0.15, mood.restingGaze(identity).yaw, 1e-12)
            assertEquals(0.0, mood.restingGaze(identity).pitch, 1e-12)
            assertEquals(BotGaze(0.05, 0.06), mood.restingGaze(BotIdentity.of(BotShape.DROP, BotColor.TEAL)))
        }
    }
}
