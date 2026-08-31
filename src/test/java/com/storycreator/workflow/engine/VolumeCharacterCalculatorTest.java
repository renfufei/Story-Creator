package com.storycreator.workflow.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class VolumeCharacterCalculatorTest {

    // ── recurring characters ──────────────────────────────────────────────────

    @Test
    void recurring_firstVolume_returnsZero() {
        assertThat(VolumeCharacterCalculator.calculateForVolume(1, 5, 1.0, 0, true)).isEqualTo(0);
    }

    @Test
    void recurring_lastVolume_returnsZero() {
        assertThat(VolumeCharacterCalculator.calculateForVolume(5, 5, 1.0, 0, true)).isEqualTo(0);
    }

    @Test
    void recurring_middleVolume_introducesCorrectly() {
        // rate=1.0: volume 2 cumulative=2, already=0 → should introduce 2
        assertThat(VolumeCharacterCalculator.calculateForVolume(2, 5, 1.0, 0, true)).isEqualTo(2);
    }

    @Test
    void recurring_singleVolume_bothFirstAndLast_returnsZero() {
        assertThat(VolumeCharacterCalculator.calculateForVolume(1, 1, 2.0, 0, true)).isEqualTo(0);
    }

    // ── non-recurring (temp) characters ──────────────────────────────────────

    @Test
    void nonRecurring_firstVolume_canIntroduce() {
        // rate=0.5: volume 1 → floor(1*0.5)=0, already=0 → 0
        assertThat(VolumeCharacterCalculator.calculateForVolume(1, 5, 0.5, 0, false)).isEqualTo(0);
    }

    @Test
    void nonRecurring_lastVolume_canIntroduce() {
        // rate=1.0: volume 5 total=5 → floor(5)=5, already=4 → 1
        assertThat(VolumeCharacterCalculator.calculateForVolume(5, 5, 1.0, 4, false)).isEqualTo(1);
    }

    @Test
    void nonRecurring_rateZero_alwaysZero() {
        assertThat(VolumeCharacterCalculator.calculateForVolume(3, 5, 0.0, 0, false)).isEqualTo(0);
    }

    // ── cumulative floor logic ────────────────────────────────────────────────

    @Test
    void cumulativeFloor_halfRate_everyOtherVolume() {
        // rate=0.5: floor(1*0.5)=0, floor(2*0.5)=1, floor(3*0.5)=1, floor(4*0.5)=2
        assertThat(VolumeCharacterCalculator.calculateForVolume(1, 6, 0.5, 0, false)).isEqualTo(0);
        assertThat(VolumeCharacterCalculator.calculateForVolume(2, 6, 0.5, 0, false)).isEqualTo(1);
        assertThat(VolumeCharacterCalculator.calculateForVolume(3, 6, 0.5, 1, false)).isEqualTo(0); // cumulative still 1
        assertThat(VolumeCharacterCalculator.calculateForVolume(4, 6, 0.5, 1, false)).isEqualTo(1); // cumulative=2
    }

    @Test
    void neverNegative_whenAlreadyIntroducedExceedsCumulative() {
        // Edge case: alreadyIntroduced > cumulative → should return 0, not negative
        assertThat(VolumeCharacterCalculator.calculateForVolume(1, 5, 0.5, 5, false)).isEqualTo(0);
    }

    @ParameterizedTest(name = "vol={0} total={1} rate={2} already={3} recurring={4} => {5}")
    @CsvSource({
        "2, 5, 1.0, 0, false, 2",
        "2, 5, 1.0, 1, false, 1",
        "2, 5, 1.0, 2, false, 0",
        "3, 5, 2.0, 4, false, 2",
        "2, 3, 0.5, 0, true,  1",  // middle volume recurring: floor(2*0.5)=1
        "1, 3, 2.0, 0, true,  0",  // first volume recurring: 0
        "3, 3, 2.0, 0, true,  0",  // last volume recurring: 0
    })
    void parameterized_variousScenarios(int vol, int total, double rate, int already, boolean recurring, int expected) {
        assertThat(VolumeCharacterCalculator.calculateForVolume(vol, total, rate, already, recurring))
                .isEqualTo(expected);
    }
}
