package id.cctv.monitor.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTimelineTest {
    private val dayStart = 1_700_000_000_000L
    private val dayEnd = dayStart + 24L * 60 * 60 * 1000
    private val continuous = RecordingClip(101, dayStart, dayStart + 3_600_000L)
    private val person = RecordingClip(
        101, dayStart + 1_800_000L, dayStart + 2_100_000L,
        eventKind = PlaybackEventKind.PERSON
    )

    @Test fun ratio_isClamped() {
        assertEquals(0f, PlaybackTimeline.ratio(dayStart - 1, dayStart, dayEnd))
        assertEquals(1f, PlaybackTimeline.ratio(dayEnd + 1, dayStart, dayEnd))
        assertEquals(0.5f, PlaybackTimeline.ratio(dayStart + 43_200_000L, dayStart, dayEnd))
    }

    @Test fun clipAt_eventWinsOverContinuous() {
        val picked = PlaybackTimeline.clipAt(
            listOf(continuous, person), 1_950_000f / 86_400_000f, dayStart, dayEnd
        )
        assertEquals(person, picked)
    }

    @Test fun clipAt_gapPicksNearestClip() {
        val later = RecordingClip(101, dayStart + 10_800_000L, dayStart + 10_900_000L)
        val picked = PlaybackTimeline.clipAt(
            listOf(continuous, later), 0.15f, dayStart, dayEnd
        )
        assertEquals(later, picked)
    }

    @Test fun filter_onlyReturnsMatchingKinds() {
        val clips = listOf(continuous, person)
        assertEquals(2, PlaybackTimeline.filter(clips, PlaybackFilter.ALL).size)
        assertEquals(listOf(person), PlaybackTimeline.filter(clips, PlaybackFilter.PERSON))
        assertEquals(0, PlaybackTimeline.filter(clips, PlaybackFilter.VEHICLE).size)
    }

    @Test fun step_clampsToAvailableClips() {
        assertEquals(-1, PlaybackTimeline.step(0, -1, 1))
        assertEquals(0, PlaybackTimeline.step(2, 0, -1))
        assertEquals(1, PlaybackTimeline.step(2, 0, 1))
        assertEquals(1, PlaybackTimeline.step(2, 1, 1))
    }

    @Test fun clipAt_samePriorityShorterWins() {
        val longPerson = RecordingClip(
            101, dayStart, dayStart + 3_600_000L, eventKind = PlaybackEventKind.PERSON
        )
        val shortPerson = RecordingClip(
            101, dayStart + 1_800_000L, dayStart + 2_100_000L,
            eventKind = PlaybackEventKind.PERSON
        )
        assertEquals(
            shortPerson,
            PlaybackTimeline.clipAt(
                listOf(longPerson, shortPerson),
                PlaybackTimeline.ratio(dayStart + 1_900_000L, dayStart, dayEnd),
                dayStart,
                dayEnd
            )
        )
    }

    @Test fun clipAt_exactBoundaryChoosesLaterClip() {
        val first = RecordingClip(101, dayStart, dayStart + 3_600_000L)
        val second = RecordingClip(101, dayStart + 3_600_000L, dayStart + 7_200_000L)
        assertEquals(
            second,
            PlaybackTimeline.clipAt(
                listOf(first, second),
                PlaybackTimeline.ratio(dayStart + 3_600_000L, dayStart, dayEnd),
                dayStart,
                dayEnd
            )
        )
    }

    @Test fun stepState_contiguousClipsAdvanceTwice() {
        val first = RecordingClip(101, dayStart, dayStart + 3_600_000L)
        val second = RecordingClip(101, dayStart + 3_600_000L, dayStart + 7_200_000L)
        val third = RecordingClip(101, dayStart + 7_200_000L, dayStart + 10_800_000L)
        val clips = listOf(first, second, third)
        val one = PlaybackTimeline.stepState(clips, dayStart, dayStart, dayEnd, 1)!!
        assertEquals(1, one.first)
        val two = PlaybackTimeline.stepState(clips, one.second, dayStart, dayEnd, 1)!!
        assertEquals(2, two.first)
        assertEquals(third.startMillis, two.second)
    }

    @Test fun stepState_twoConsecutiveStepsAdvance() {
        val laterA = RecordingClip(101, dayStart + 10_800_000L, dayStart + 10_900_000L)
        val laterB = RecordingClip(101, dayStart + 21_600_000L, dayStart + 21_700_000L)
        val clips = listOf(continuous, laterA, laterB)
        val first = PlaybackTimeline.stepState(clips, dayStart, dayStart, dayEnd, 1)!!
        assertEquals(1, first.first)
        assertEquals(laterA.startMillis, first.second)
        val second = PlaybackTimeline.stepState(clips, first.second, dayStart, dayEnd, 1)!!
        assertEquals(2, second.first)
        assertEquals(laterB.startMillis, second.second)
    }

    @Test fun safePicked_atEndFallsBackToStart() {
        assertEquals(continuous.startMillis, PlaybackTimeline.safePicked(continuous, continuous.endMillis))
        assertEquals(
            continuous.startMillis,
            PlaybackTimeline.safePicked(continuous, continuous.endMillis + 5_000L)
        )
        assertEquals(
            dayStart + 1_800_000L,
            PlaybackTimeline.safePicked(continuous, dayStart + 1_800_000L)
        )
    }

    @Test fun pickedMillis_clampsAndSeekOffset() {
        val picked = PlaybackTimeline.pickedMillis(continuous, continuous.startMillis - 5_000L)
        assertEquals(continuous.startMillis, picked)
        assertEquals(0L, PlaybackTimeline.seekOffset(continuous, picked))
        assertEquals(
            1_800_000L,
            PlaybackTimeline.seekOffset(continuous, dayStart + 1_800_000L)
        )
    }
}
