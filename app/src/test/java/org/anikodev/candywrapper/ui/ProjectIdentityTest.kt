package org.anikodev.candywrapper.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** `projectIdentity.ts`: the letters T3 draws for a project, as in CandyWrapper's references ("DR" for demo-repo). */
class ProjectIdentityTest {
    @Test
    fun lettersFollowT3() {
        assertEquals("DR", projectIdentity("demo-repo").first)
        assertEquals("T3", projectIdentity("t3code").first)
        assertEquals("CA", projectIdentity("CandyWrapper-Android").first)
        assertEquals("XX", projectIdentity("x").first)
        assertEquals("PR", projectIdentity("  ").first)
    }

    @Test
    fun colourIsStableAndInRange() {
        val (_, a) = projectIdentity("demo-repo")
        assertEquals(a, projectIdentity("demo-repo").second)
        assert(a in 0 until 18)
    }
}
