package com.agani.syncup.forest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForestRulesTest {
    /** Epoch ms for a date and a time of day in IST (UTC + 5:30). */
    private fun ms(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0): Long = msAt(y, m, d, h * 60.0 + min)

    private fun msAt(y: Int, m: Int, d: Int, minute: Double): Long =
        (ForestRules.epochDay(y, m, d) * 1440 - 330) * 60_000L + Math.round(minute * 60_000)

    /** Installed long ago: no catch-up. */
    private val longAgo = ms(2026, 1, 1, 9, 0)

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): ForestNow = ForestRules.at(ms(y, m, d, h, min), longAgo)

    private fun ForestNow.sprites(): List<String> = things.map { it.sprite }

    // ------------------------------------------------------------------ sun and moon
    @Test
    fun sunriseAndSunsetAtJolarpettai() {
        val (rise, set) = ForestRules.sunTimes(2026, 10, 22)
        assertEquals(6 * 60 + 6.0, rise, 2.0)
        assertEquals(17 * 60 + 53.5, set, 2.5)
        assertEquals("6:06 AM", ForestRules.hm(rise))
        assertEquals("5:54 PM", ForestRules.hm(set))
        // exactly the mockup's numbers
        assertEquals(366.2785, rise, 0.001)
        assertEquals(1073.7788, set, 0.001)
        val now = at(2026, 10, 22, 12, 0)
        assertEquals(rise, now.sunrise, 1e-9)
        assertEquals(ForestTime.DAY, now.time)
        assertEquals(ForestTime.NIGHT, at(2026, 10, 22, 21, 30).time)
        assertEquals(ForestTime.DAWN, at(2026, 10, 22, 6, 30).time)
        assertEquals(ForestTime.DUSK, at(2026, 10, 22, 17, 30).time)
    }

    @Test
    fun theRealMoon() {
        val now = at(2026, 10, 22, 21, 30)
        assertEquals("Waxing gibbous", now.moon.name)
        assertTrue(now.moon.waxing)
        assertEquals(0.87, now.moon.lit, 0.015)
        assertEquals(ForestDate(2026, 10, 26), now.moon.nextFull)
        assertEquals("Waxing gibbous · 87% lit", ForestRules.moonTitle(now))
        assertEquals("Full moon in 4 days · Mon 26 Oct", ForestRules.moonLine(now))
        // shown only at night
        assertTrue(ForestExtra.MOON in now.extras)
        assertFalse(ForestExtra.MOON in at(2026, 10, 22, 12, 0).extras)
    }

    // ------------------------------------------------------------------ the month
    @Test
    fun phasesFor28To31DayMonths() {
        assertEquals(28, ForestRules.monthLength(2027, 2))
        assertEquals(29, ForestRules.monthLength(2028, 2))
        assertEquals(30, ForestRules.monthLength(2026, 11))
        assertEquals(31, ForestRules.monthLength(2026, 10))
        for (n in 28..31) {
            for (d in 1..n - 4) assertEquals(ForestPhase.GREEN, ForestRules.phaseOf(d, n))
            assertEquals(ForestPhase.PICNIC, ForestRules.phaseOf(n - 3, n))
            assertEquals(ForestPhase.DRY, ForestRules.phaseOf(n - 2, n))
            assertEquals(ForestPhase.DESERT, ForestRules.phaseOf(n - 1, n))
            assertEquals(ForestPhase.STORM, ForestRules.phaseOf(n, n))
            // something new every green day
            for (d in 1..n - 4) assertTrue(ForestRules.arrivalsOn(d, n).isNotEmpty())
        }
        assertEquals(ForestPhase.PICNIC, at(2027, 2, 25, 12, 0).phase)
        assertEquals(ForestPhase.STORM, at(2027, 2, 28, 12, 0).phase)
        assertEquals(ForestPhase.STORM, at(2026, 10, 31, 12, 0).phase)
        // then the 1st restarts: one tree, the rainbow, nothing else yet
        val nov1 = at(2026, 11, 1, 7, 15)
        assertEquals(ForestPhase.GREEN, nov1.phase)
        assertEquals(1, nov1.day)
        assertEquals(1, nov1.trees.size)
        assertEquals(setOf(1), nov1.arrived)
        assertTrue(ForestExtra.RAINBOW in nov1.extras)
        assertEquals("Sunday visitors", ForestRules.todayTitle(nov1)) // 1 Nov 2026 is a Sunday
        assertEquals("A new month begins", ForestRules.todayTitle(at(2026, 12, 1, 8, 0)))
    }

    @Test
    fun februaryBonusThingsComeOnTheLastGreenDay() {
        assertEquals(listOf(24, 25, 26, 27), ForestRules.arrivalsOn(24, 28))
        assertEquals((1..23).toSet(), ForestRules.arrived(23, 28))
        assertEquals((1..27).toSet(), ForestRules.arrived(24, 28))
        assertEquals(listOf(25, 26, 27), ForestRules.arrivalsOn(25, 29))
        assertEquals(listOf(26, 27), ForestRules.arrivalsOn(26, 30))
        assertEquals(listOf(27), ForestRules.arrivalsOn(27, 31))
        assertEquals((1..25).toSet(), ForestRules.arrived(25, 31))

        val feb23 = at(2027, 2, 23, 10, 10)
        assertFalse("a-elephant" in feb23.sprites())
        assertFalse("a-hornbill" in feb23.sprites())
        val feb24 = at(2027, 2, 24, 10, 10)
        assertTrue("a-elephant" in feb24.sprites())
        assertTrue("a-hornbill" in feb24.sprites())
        assertTrue("a-peahen" in feb24.sprites())
        assertTrue(ForestRules.todayLines(feb24).any { it.text == "Also today: hornbills, a peahen and her chicks, a tiger, far away at dusk" })
        val feb = ForestRules.monthDays(feb24)
        assertEquals(listOf("Hornbills", "A peahen and her chicks", "A tiger, far away at dusk"), feb[23].also)
        // the tiger only shows at dusk
        val dusk = ForestRules.at(msAt(2027, 2, 24, ForestRules.sunTimes(2027, 2, 24).second - 10), longAgo)
        assertEquals(ForestTime.DUSK, dusk.time)
        assertTrue("a-tiger" in dusk.sprites())
        assertFalse("a-tiger" in feb24.sprites())
    }

    // ------------------------------------------------------------------ catch-up
    @Test
    fun catchUpOnTheInstallDay() {
        val install = ms(2026, 10, 22, 7, 0)
        val now = ForestRules.at(ms(2026, 10, 22, 13, 30), install)
        val c = now.catchUp
        assertNotNull(c)
        c!!
        assertEquals(48.571, c.minutesPerDay, 0.01) // 17 hours for 21 days
        assertEquals(9, c.day)
        assertEquals(22, c.of)
        assertEquals(9, now.day)
        assertEquals(9, now.treeCount)
        assertEquals(c.dayProgress, now.stageFraction, 1e-9)
        assertEquals(9, now.todayTree!!.day)
        assertEquals("Catching up · day 9 of 22", ForestRules.statusLine(now))
        assertEquals(
            "Your forest is new, so this month so far plays out today — about one day every 49 min. All caught up by midnight.",
            ForestRules.catchUpText(now),
        )
        // the sky stays on real time
        assertEquals(13 * 60 + 30.0, now.minute, 1e-6)
        // day 1 at the install moment, a seed
        val first = ForestRules.at(install, install)
        assertEquals(1, first.day)
        assertEquals(Stage.SEED, first.todayStage)
        // the day's tree grows through each forest-day
        val mid = ForestRules.at(install + (c.minutesPerDay * 60_000 * 4.5).toLong(), install)
        assertEquals(5, mid.day)
        assertEquals(Stage.SAPLING, mid.todayStage)
        // still catching up just before midnight, then a normal day
        assertEquals(21, ForestRules.at(ms(2026, 10, 22, 23, 59), install).day)
        val next = ForestRules.at(ms(2026, 10, 23, 0, 30), install)
        assertNull(next.catchUp)
        assertEquals(23, next.day)
        // installed on the 9th at 8 AM: one forest-day an hour, caught up at 4 PM
        val i9 = ms(2026, 10, 9, 8, 0)
        val nine = ForestRules.at(ms(2026, 10, 9, 12, 30), i9)
        assertEquals(60.0, nine.catchUp!!.minutesPerDay, 1e-9)
        assertEquals(5, nine.day)
        assertEquals(16 * 60.0, nine.catchUp!!.endsMinute, 1e-6)
        assertTrue(ForestRules.catchUpText(nine)!!.contains("about one day an hour. All caught up by 4:00 PM."))
        val caught = ForestRules.at(ms(2026, 10, 9, 16, 0), i9)
        assertNull(caught.catchUp)
        assertEquals(9, caught.day)
        // installed on the 1st: nothing to catch up
        assertNull(ForestRules.at(ms(2026, 10, 1, 10, 0), ms(2026, 10, 1, 9, 0)).catchUp)
    }

    // ------------------------------------------------------------------ Sundays
    @Test
    fun aDragonAndATRexEverySunday() {
        assertTrue(ForestDate(2026, 10, 25).sunday)
        assertFalse(ForestDate(2026, 10, 22).sunday)
        assertEquals(4, ForestDate(2026, 10, 22).weekday) // Thursday
        val sun = at(2026, 10, 25, 11, 0)
        assertTrue(sun.sunday)
        assertTrue(ForestExtra.DRAGON_FLYING in sun.extras)
        assertTrue("a-trex" in sun.sprites())
        assertEquals("Sunday visitors", ForestRules.todayTitle(sun))
        val night = at(2026, 10, 25, 22, 0)
        assertTrue(ForestExtra.DRAGON_RESTING in night.extras)
        assertFalse(ForestExtra.DRAGON_FLYING in night.extras)
        val thu = at(2026, 10, 22, 11, 0)
        assertFalse(thu.sunday)
        assertFalse("a-trex" in thu.sprites())
        assertTrue(thu.extras.none { it == ForestExtra.DRAGON_FLYING || it == ForestExtra.DRAGON_RESTING })
        // any phase of the month: Sun 28 Feb 2027 is a storm day
        val storm = at(2027, 2, 28, 12, 0)
        assertEquals(ForestPhase.STORM, storm.phase)
        assertTrue("a-trex" in storm.sprites())
        assertEquals(listOf(4, 11, 18, 25), ForestRules.monthDays(thu).filter { it.sunday }.map { it.day })
    }

    // ------------------------------------------------------------------ one tree a day
    @Test
    fun todaysTreeGrowsFromSunriseToSunsetMinus30() {
        val (rise, set) = ForestRules.sunTimes(2026, 10, 22)
        val ready = set - 30
        fun stage(frac: Double): Stage = ForestRules.at(msAt(2026, 10, 22, rise + frac * (ready - rise)), longAgo).todayStage!!
        assertEquals(Stage.SEED, ForestRules.at(ms(2026, 10, 22, 5, 0), longAgo).todayStage)
        assertEquals(Stage.SEED, stage(.1))
        assertEquals(Stage.SPROUT, stage(.25))
        assertEquals(Stage.SAPLING, stage(.5))
        assertEquals(Stage.YOUNG, stage(.75))
        assertEquals(Stage.GROWN, stage(1.001))
        val noon = ForestRules.at(msAt(2026, 10, 22, rise + .5 * (ready - rise)), longAgo)
        val t = noon.todayTree!!
        assertTrue(t.growing)
        assertEquals(22, t.day)
        assertEquals(ForestKind.NEEM, t.kind)
        assertEquals(Species.NEEM, t.kind.species)
        assertEquals("t-sapling", t.sprite)
        assertFalse(t in noon.hosts)
        assertEquals("Today's tree: Neem · sapling · grown by 5:24 PM", ForestRules.treeLine(noon)!!.text)
        assertEquals(22, noon.trees.size)
        assertEquals(1, noon.trees.count { it.growing })
        // no tree grows at the month's end
        assertNull(at(2026, 10, 29, 12, 0).todayTree)
    }

    // ------------------------------------------------------------------ depth
    @Test
    fun thingsAreDepthSortedAllMonth() {
        for ((y, m) in listOf(2026 to 10, 2026 to 11, 2027 to 2)) {
            for (d in 1..ForestRules.monthLength(y, m)) {
                for (h in listOf(5, 7, 12, 17, 22)) {
                    val now = at(y, m, d, h, 40)
                    val keys = now.things.map { it.key }
                    assertEquals("$y-$m-$d $h:40", keys.sorted(), keys)
                    assertEquals(now.trees.size, now.things.count { it.kind == ForestThing.Kind.TREE })
                    assertTrue(now.hosts.zipWithNext().all { (a, b) -> a.w >= b.w })
                }
            }
        }
    }

    @Test
    fun everyTreeHasItsOwnSpotAndTheSwingStaysOnScreen() {
        for ((y, m) in listOf(2026 to 10, 2026 to 11, 2027 to 1, 2027 to 2, 2028 to 2)) {
            for (d in 1..ForestRules.monthLength(y, m)) {
                val now = at(y, m, d, 12, 0)
                val spots = now.trees.map { it.x to it.y }
                assertEquals("$y-$m-$d spots", spots.size, spots.toSet().size)
                for (s in now.things.filter { it.sprite == "o-swing" }) assertTrue("$y-$m-$d swing x ${s.x}", s.x in 24f..336f)
            }
        }
        // A 31-day month's 28th tree has a spot of its own, at the back.
        val t28 = at(2026, 10, 29, 12, 0).trees.single { it.day == 28 }
        assertEquals(166f, t28.x)
        assertEquals(352f, t28.y)
        // The swing hangs from the near tree's meadow-side branch (24 Feb: a neem on the right → its left branch).
        val swing = at(2027, 2, 24, 10, 10).things.single { it.sprite == "o-swing" }
        assertEquals(315.474, swing.x.toDouble(), .01)
    }

    // ------------------------------------------------------------------ the same forest as the mockup
    @Test
    fun seasonsAndKindsMatchTheMockup() {
        val oct = "Summer Winter Summer Spring Autumn Winter Winter Spring Summer Spring Winter Spring Monsoon Monsoon Summer Winter " +
            "Spring Spring Monsoon Monsoon Summer Monsoon Spring Autumn Summer Monsoon Monsoon Monsoon Summer Monsoon Monsoon"
        val octKinds = "Banyan Bamboo Jasmine Jasmine Mango Jasmine Jasmine Neem Jasmine Banyan Gulmohar Gulmohar Mango Peepal Coconut " +
            "Neem Peepal Banyan Bamboo Gulmohar Bamboo Neem Banyan Gulmohar Gulmohar Neem Gulmohar Neem Mango Bamboo Mango"
        val feb = "Monsoon Winter Winter Spring Summer Autumn Autumn Autumn Winter Winter Monsoon Winter Summer Spring Autumn Winter " +
            "Autumn Summer Spring Spring Autumn Autumn Spring Summer Autumn Autumn Autumn Summer"
        val febKinds = "Neem Gulmohar Jasmine Bamboo Neem Coconut Bamboo Coconut Neem Neem Bamboo Gulmohar Neem Mango Mango Gulmohar " +
            "Neem Gulmohar Mango Coconut Mango Peepal Jasmine Banyan Neem Peepal Jasmine Banyan"
        assertEquals(oct, (1..31).joinToString(" ") { ForestRules.seasonOf(ForestDate(2026, 10, it)).title })
        assertEquals(octKinds, (1..31).joinToString(" ") { ForestRules.kindOf(ForestDate(2026, 10, it)).title })
        assertEquals(feb, (1..28).joinToString(" ") { ForestRules.seasonOf(ForestDate(2027, 2, it)).title })
        assertEquals(febKinds, (1..28).joinToString(" ") { ForestRules.kindOf(ForestDate(2027, 2, it)).title })
    }

    @Test
    fun sceneMatchesTheMockup() {
        check("22 Oct night", at(2026, 10, 22, 21, 30), OCT22_2130)
        check("28 Oct picnic", at(2026, 10, 28, 13, 0), OCT28_1300)
        check("30 Oct desert", at(2026, 10, 30, 13, 30), OCT30_1330)
        check("31 Oct storm evening", at(2026, 10, 31, 17, 20), OCT31_1720)
        check("24 Feb", at(2027, 2, 24, 10, 10), FEB24_1010)
    }

    private fun check(label: String, now: ForestNow, expected: String) {
        val rows = expected.trim().lines().map { it.trim().split(" ") }
        assertEquals("$label: count", rows.size, now.things.size)
        rows.zip(now.things).forEachIndexed { i, (r, t) ->
            val at = "$label #$i ${r[0]}"
            assertEquals(at, r[0], t.sprite)
            assertEquals(at, r[1].toDouble(), t.x.toDouble(), .01)
            assertEquals(at, r[2].toDouble(), t.baseY.toDouble(), .01)
            assertEquals(at, r[3].toDouble(), t.w.toDouble(), .01)
            assertEquals(at, r[4] == "1", t.flip)
        }
    }

    // ------------------------------------------------------------------ text
    @Test
    fun textForTheScreens() {
        val now = at(2026, 10, 22, 17, 25)
        assertEquals("Day 22 · a mongoose came", ForestRules.statusLine(now))
        assertEquals("Day 22 · 22 trees", ForestRules.homeTitle(now))
        assertEquals("A mongoose came today", ForestRules.homeSubtitle(now))
        assertEquals("A mongoose", ForestRules.todayTitle(now))
        assertEquals("Day 22 of 31 · Thu 22 Oct · 5:25 PM", ForestRules.topLine(now))
        assertEquals("22 trees", ForestRules.treeChip(now))
        assertEquals("October · 31 days", ForestRules.monthTitle(now))
        assertEquals(
            listOf("Day 23", "Day 24", "Day 25", "Day 26", "Day 28", "Days 29–30", "Day 31", "Every Sunday"),
            ForestRules.comingUp(now).map { it.label },
        )
        val days = ForestRules.monthDays(now)
        assertEquals(31, days.size)
        assertTrue(days[21].today)
        assertTrue(days[20].past)
        assertEquals("A mongoose", days[21].title)
        assertEquals(ForestPhase.PICNIC, days[27].phase)
        assertEquals("🧺", days[27].icon)

        val picnic = at(2026, 10, 28, 13, 0)
        assertEquals(ForestPicnic.SITTING, picnic.picnic)
        assertEquals("Your picnic day", ForestRules.homeSubtitle(picnic))
        assertEquals("Day 28 · your picnic day", ForestRules.statusLine(picnic))
        assertEquals(ForestPicnic.ARRIVING, at(2026, 10, 28, 9, 10).picnic)
        assertEquals(ForestPicnic.LEFT, at(2026, 10, 28, 18, 0).picnic)
        assertFalse("p-man-walk" in at(2026, 10, 28, 18, 0).sprites())

        assertEquals("The dry spell", ForestRules.homeSubtitle(at(2026, 10, 29, 12, 0)))
        assertEquals("Day 29 · 28 trees drying", ForestRules.homeTitle(at(2026, 10, 29, 12, 0)))
        assertEquals("Desert today", ForestRules.homeSubtitle(at(2026, 10, 30, 12, 0)))
        assertEquals("Storm today", ForestRules.homeSubtitle(at(2026, 10, 31, 12, 0)))
        assertEquals("The storm · a flood", ForestRules.todayTitle(at(2026, 10, 31, 12, 0)))
        assertEquals("The storm", ForestRules.todayTitle(at(2026, 10, 31, 5, 0)))
        assertEquals("Green again", ForestRules.todayTitle(at(2026, 10, 31, 17, 30)))
    }

    @Test
    fun theMonthEndsWithATornadoAVolcanoAndAFlood() {
        val dry = at(2026, 10, 29, 12, 0)
        assertTrue(dry.sprites().containsAll(listOf("o-tornado", "a-buffalo", "a-ox")))
        val desert = at(2026, 10, 30, 12, 0)
        assertTrue(desert.sprites().containsAll(listOf("a-camel", "a-rhino")))
        assertTrue(ForestExtra.VOLCANO in desert.extras)

        // The flood: none before sunrise, high at noon, going down in the afternoon, gone by the evening's green.
        assertEquals(0.0, at(2026, 10, 31, 5, 30).flood, 0.0)
        val noon = at(2026, 10, 31, 12, 0)
        assertEquals(1.0, noon.flood, 0.0)
        assertTrue(ForestExtra.FLOOD in noon.extras)
        assertTrue(noon.sprites().containsAll(listOf("a-whale", "a-dolphin", "a-fish", "a-turtle", "a-octopus", "a-jellyfish")))
        assertFalse("o-puddle" in noon.sprites())
        assertFalse("a-crab" in noon.sprites())
        val draining = at(2026, 10, 31, 15, 30)
        assertTrue(draining.flood in .3..0.7)
        assertTrue("a-crab" in draining.sprites())
        // Every sea creature floats in the water, never on the dry meadow behind it.
        for (th in draining.things) if (th.sprite in ForestRules.SWIMMERS) assertTrue(th.sprite, th.baseY > draining.floodLine)
        val evening = at(2026, 10, 31, 17, 30)
        assertEquals(0.0, evening.flood, 0.0)
        assertFalse("a-whale" in evening.sprites())
    }

    // ------------------------------------------------------------------ fixtures from forest_v2_page.py (sprite x y w flip)
    private val OCT22_2130 = """
        t-neem 186.000 334.000 71.077 0
        t-bamboo 310.000 338.000 50.432 0
        t-gulmohar-green 60.000 340.000 79.784 0
        t-bamboo 256.000 342.000 51.111 0
        t-banyan 112.000 344.000 91.875 0
        t-peepal 356.000 354.000 75.923 0
        t-neem 12.000 356.000 76.408 0
        t-coconut 220.000 358.000 63.052 0
        t-peepal 150.000 360.000 77.377 0
        t-gulmohar-green 290.000 370.000 87.780 0
        t-banyan 88.000 372.000 100.356 0
        t-jasmine 182.000 382.000 59.550 0
        t-gulmohar-green 40.000 392.000 93.644 0
        t-mango 330.000 394.000 89.896 0
        t-jasmine 242.000 396.000 61.992 0
        t-jasmine 122.000 398.000 62.341 0
        o-stream 96.000 474.000 204.750 0
        t-bamboo 300.000 428.000 65.698 0
        t-banyan 62.000 432.000 118.529 0
        a-monkey 34.738 381.033 23.706 0
        a-monkey 95.188 381.033 20.150 1
        a-parrot 67.926 325.324 14.223 1
        t-jasmine 346.000 466.000 74.204 0
        t-mango 20.000 474.000 110.250 0
        a-bulbul 25.512 373.673 13.230 0
        o-beehive 58.587 427.695 14.332 0
        a-bees 67.407 449.745 17.640 0
        a-squirrel 74.000 488.000 17.343 0
        a-doe 172.000 488.000 47.693 1
        a-deer 130.000 494.000 54.923 0
        o-mushrooms 36.000 500.000 22.260 0
        t-jasmine 350.000 532.000 85.719 0
        t-neem 18.000 540.000 120.992 0
        a-peacock-dance 248.000 540.000 67.756 0
        o-bench 300.000 540.000 55.657 0
        a-owl 20.420 489.183 24.198 0
        o-swing 56.718 536.000 65.152 0
        a-nest-chicks 20.420 492.813 19.359 0
        a-parrot 16.790 425.057 15.729 0
        o-stones 190.000 700.000 151.774 0
        o-pond 300.000 618.000 181.860 0
        a-sparrow 104.000 562.000 12.632 0
        a-mongoose 92.000 574.000 32.308 1
        a-kingfisher 350.000 590.000 26.622 1
        a-duck 300.000 598.000 25.659 0
        a-rabbit 122.000 604.000 25.935 0
        a-duck 332.000 606.000 26.027 1
        a-rabbit2 148.000 616.000 25.093 1
        a-frog 238.000 620.000 15.441 0
        a-tortoise 226.000 632.000 27.224 0
        o-rocks 112.000 646.000 61.604 0
    """
    private val OCT28_1300 = """
        t-gulmohar-green 336.000 324.000 75.519 0
        t-gulmohar-green 232.000 326.000 76.052 0
        t-neem 30.000 326.000 69.138 0
        t-gulmohar-green 140.000 328.000 76.585 0
        t-neem 186.000 334.000 71.077 0
        t-bamboo 310.000 338.000 50.432 0
        t-gulmohar-green 60.000 340.000 79.784 0
        t-bamboo 256.000 342.000 51.111 0
        t-banyan 112.000 344.000 91.875 0
        t-young 166.000 352.000 75.439 0
        t-peepal 356.000 354.000 75.923 0
        t-neem 12.000 356.000 76.408 0
        t-coconut 220.000 358.000 63.052 0
        t-peepal 150.000 360.000 77.377 0
        t-gulmohar-green 290.000 370.000 87.780 0
        t-banyan 88.000 372.000 100.356 0
        t-jasmine 182.000 382.000 59.550 0
        t-gulmohar-green 40.000 392.000 93.644 0
        t-mango 330.000 394.000 89.896 0
        t-jasmine 242.000 396.000 61.992 0
        t-jasmine 122.000 398.000 62.341 0
        o-stream 96.000 474.000 204.750 0
        t-bamboo 300.000 428.000 65.698 0
        t-banyan 62.000 432.000 118.529 0
        o-beehive 95.188 381.033 15.409 0
        a-bulbul 67.926 325.324 14.223 0
        a-bees 104.670 404.738 18.965 0
        a-elephant 232.000 452.000 94.686 1
        a-elephant-calf 186.000 458.000 50.562 1
        t-jasmine 346.000 466.000 74.204 0
        t-mango 20.000 474.000 110.250 0
        a-squirrel 74.000 488.000 17.343 0
        a-doe 172.000 488.000 47.693 1
        a-deer 130.000 494.000 54.923 0
        o-mushrooms 36.000 500.000 22.260 0
        a-fawn 190.000 500.000 30.051 1
        t-jasmine 350.000 532.000 85.719 0
        t-neem 18.000 540.000 120.992 0
        a-peacock-dance 248.000 540.000 67.756 0
        o-bench 300.000 540.000 55.657 0
        a-parrot 16.790 425.057 14.519 1
        a-hornbill -17.088 471.034 24.198 0
        o-umbrella-big 158.052 539.689 102.589 0
        o-picnic 156.000 552.000 102.589 0
        p-man-sit 145.741 541.741 49.243 0
        p-woman-sit 170.363 541.741 49.243 0
        a-peahen 204.000 556.000 37.461 0
        o-stones 190.000 700.000 151.774 0
        o-pond 300.000 618.000 181.860 0
        a-sparrow 104.000 562.000 12.632 0
        a-peachick 222.000 562.000 13.896 0
        a-peachick 232.000 566.000 14.002 0
        a-mongoose 92.000 574.000 32.308 1
        a-kingfisher 350.000 590.000 26.622 1
        a-duck 300.000 598.000 25.659 0
        a-rabbit 122.000 604.000 25.935 0
        t-banyan -4.000 606.000 171.231 0
        a-duck 332.000 606.000 26.027 1
        o-puddle 80.000 606.000 75.341 0
        o-swing 43.945 602.000 72.530 0
        a-nest-chicks -4.000 534.083 27.397 0
        a-monkey -43.383 532.371 34.246 0
        a-monkey 43.945 532.371 29.109 1
        a-parrot 4.561 451.892 22.260 0
        a-rabbit2 148.000 616.000 25.093 1
        a-frog 238.000 620.000 15.441 0
        a-tortoise 226.000 632.000 27.224 0
        o-rocks 112.000 646.000 61.604 0
    """
    private val OCT30_1330 = """
        o-dead-tree 232.000 326.000 68.447 0
        o-dead-tree 30.000 326.000 62.225 0
        o-dead-tree 186.000 334.000 63.969 0
        o-dead-tree 60.000 340.000 71.805 0
        o-dead-tree 256.000 342.000 46.000 0
        o-dead-tree 166.000 352.000 67.895 0
        o-dead-tree 356.000 354.000 68.331 0
        o-dead-tree 12.000 356.000 68.767 0
        o-dead-tree 150.000 360.000 69.639 0
        o-dead-tree 290.000 370.000 79.002 0
        o-dead-tree 88.000 372.000 90.320 0
        o-dead-tree 182.000 382.000 53.595 0
        o-dead-tree 330.000 394.000 80.906 0
        o-dead-tree 242.000 396.000 55.793 0
        o-dead-tree 300.000 428.000 59.128 0
        o-dead-tree 62.000 432.000 106.676 0
        a-camel 226.000 470.000 83.225 1
        o-dead-tree 20.000 474.000 99.225 0
        a-camel 270.000 480.000 85.163 1
        a-rhino 118.000 528.000 75.574 0
        o-dead-tree 18.000 540.000 108.893 0
        o-cactus 330.000 540.000 36.298 0
        o-dry-bush 150.000 574.000 41.354 0
        a-rhino 236.000 576.000 83.018 1
        o-cactus 72.000 600.000 40.659 0
        o-dead-tree -4.000 606.000 154.108 0
        o-dry-bush 296.000 612.000 44.300 0
    """
    private val OCT31_1720 = """
        o-dead-tree 232.000 326.000 68.447 0
        o-dead-tree 30.000 326.000 62.225 0
        o-dead-tree 186.000 334.000 63.969 0
        o-dead-tree 60.000 340.000 71.805 0
        o-dead-tree 256.000 342.000 46.000 0
        o-dead-tree 166.000 352.000 67.895 0
        o-dead-tree 356.000 354.000 68.331 0
        o-dead-tree 12.000 356.000 68.767 0
        o-dead-tree 150.000 360.000 69.639 0
        o-dead-tree 290.000 370.000 79.002 0
        o-dead-tree 88.000 372.000 90.320 0
        o-dead-tree 182.000 382.000 53.595 0
        o-shoots 346.252 387.896 21.611 0
        o-dead-tree 330.000 394.000 80.906 0
        o-dead-tree 242.000 396.000 55.793 0
        o-shoots 348.757 404.209 15.701 0
        o-shoots 246.425 404.868 19.407 0
        o-shoots 50.133 405.705 25.132 0
        o-shoots 14.175 409.224 19.577 0
        o-shoots 242.514 416.060 17.134 0
        o-shoots 114.147 425.837 22.830 0
        o-dead-tree 300.000 428.000 59.128 0
        o-dead-tree 62.000 432.000 106.676 0
        o-shoots 130.871 433.003 25.478 0
        o-shoots 81.513 436.401 23.046 0
        o-shoots 227.901 436.988 17.339 0
        o-shoots 124.243 466.929 22.680 0
        o-dead-tree 20.000 474.000 99.225 0
        o-shoots 122.305 474.873 29.571 0
        a-crab 96.000 476.000 31.645 0
        o-shoots 329.533 484.766 21.289 0
        a-crab 258.000 492.000 32.809 1
        o-shoots 192.125 496.415 24.448 0
        o-shoots 76.944 498.248 29.278 0
        o-dead-tree 18.000 540.000 108.893 0
        o-shoots 319.432 548.826 35.102 0
        o-shoots 305.241 549.299 24.651 0
        o-puddle 200.000 552.000 68.145 0
        o-shoots 261.823 552.163 23.675 0
        o-shoots 195.578 555.374 23.233 0
        o-shoots 280.842 569.753 25.297 0
        o-shoots 88.813 576.974 24.979 0
        o-shoots 161.190 585.118 31.370 0
        o-shoots 229.900 597.405 38.008 0
        o-puddle 110.000 600.000 74.542 0
        o-dead-tree -4.000 606.000 154.108 0
        o-puddle 270.000 612.000 76.141 0
        o-shoots 75.549 619.465 33.961 0
        o-shoots 196.110 627.973 38.175 0
        o-shoots 341.986 638.312 38.069 0
    """
    private val FEB24_1010 = """
        t-sprout 140.000 328.000 87.029 0
        t-peepal 186.000 334.000 71.077 0
        t-mango-fruit 310.000 338.000 75.648 0
        t-coconut 60.000 340.000 59.475 0
        t-mango-fruit 256.000 342.000 76.666 0
        t-gulmohar 112.000 344.000 80.850 0
        t-neem 356.000 354.000 75.923 0
        t-gulmohar 12.000 356.000 84.049 0
        t-mango-fruit 220.000 358.000 80.737 0
        t-mango-fruit 150.000 360.000 81.246 0
        t-bamboo 290.000 370.000 55.860 0
        t-neem 88.000 372.000 80.285 0
        t-bamboo 182.000 382.000 57.895 0
        t-gulmohar 40.000 392.000 93.644 0
        t-neem 330.000 394.000 85.615 0
        t-bamboo 242.000 396.000 60.270 0
        t-jasmine 122.000 398.000 62.341 0
        o-stream 96.000 474.000 204.750 0
        t-gulmohar 300.000 428.000 103.239 0
        o-beehive 332.004 375.348 13.421 0
        a-bulbul 300.000 345.409 12.389 0
        a-bees 340.263 395.996 16.518 0
        t-neem 62.000 432.000 94.823 0
        a-elephant 232.000 452.000 94.686 1
        a-elephant-calf 186.000 458.000 50.562 1
        t-coconut 346.000 466.000 84.510 0
        t-neem 20.000 474.000 105.000 0
        a-monkey -10.450 420.450 21.000 0
        a-monkey 53.600 416.250 17.850 1
        a-parrot 18.950 374.250 12.600 1
        a-hornbill -10.450 414.150 21.000 0
        a-squirrel 74.000 488.000 17.343 0
        a-doe 172.000 488.000 47.693 1
        a-deer 130.000 494.000 54.923 0
        o-mushrooms 36.000 500.000 22.260 0
        a-fawn 190.000 500.000 30.051 1
        t-neem 350.000 532.000 119.054 0
        a-nest-chicks 352.381 485.569 19.049 0
        o-swing 315.474 528.000 59.081 0
        a-parrot 348.810 418.899 15.477 0
        t-coconut 18.000 540.000 99.214 0
        a-peacock 248.000 540.000 60.496 0
        o-bench 300.000 540.000 55.657 0
        a-peahen 204.000 556.000 37.461 0
        o-stones 190.000 700.000 151.774 0
        o-pond 300.000 618.000 181.860 0
        a-sparrow 104.000 562.000 12.632 0
        a-peachick 222.000 562.000 13.896 0
        a-peachick 232.000 566.000 14.002 0
        a-mongoose 92.000 574.000 32.308 1
        a-kingfisher 350.000 590.000 26.622 1
        a-duck 300.000 598.000 25.659 0
        a-rabbit 122.000 604.000 25.935 0
        t-jasmine -4.000 606.000 98.629 0
        a-duck 332.000 606.000 26.027 1
        a-rabbit2 148.000 616.000 25.093 1
        a-frog 238.000 620.000 15.441 0
        a-tortoise 226.000 632.000 27.224 0
        o-rocks 112.000 646.000 61.604 0
    """
}
