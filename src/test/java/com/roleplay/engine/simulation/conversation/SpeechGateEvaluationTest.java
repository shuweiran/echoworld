package com.roleplay.engine.simulation.conversation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reproducible evaluation of how many dialogue-generation opportunities SpeechGate removes. */
class SpeechGateEvaluationTest {

    private record Candidate(String name, double talkativeness, int priority) {}

    @Test
    @DisplayName("100 轮固定负载：统计 SpeechGate 相对全员调用的候选削减量")
    void fixedWorkloadReducesDialogueGenerationCandidates() {
        SpeechGate gate = new SpeechGate(0.15, 0.5, true);
        List<Candidate> candidates = List.of(
                new Candidate("Alice", 0.8, 100),
                new Candidate("Bob", 0.6, 40),
                new Candidate("Charlie", 0.5, 20),
                new Candidate("Diana", 0.2, 5),
                new Candidate("Evan", 0.1, 0));

        int rounds = 100;
        int withoutGate = rounds * candidates.size();
        int withGate = 0;

        for (int round = 1; round <= rounds; round++) {
            boolean humanSpoke = round % 3 == 0;
            boolean coldBreakRound = round % 20 == 0;
            List<SpeechGate.SpeechTrigger> triggers = round % 10 == 0
                    ? List.of(new SpeechGate.SpeechTrigger(SpeechGate.TriggerType.MENTION, "Charlie"))
                    : List.of();

            for (Candidate candidate : candidates) {
                boolean coldBreakCandidate = coldBreakRound && candidate.name().equals("Diana");
                if (gate.decide(candidate.name(), candidate.talkativeness(), candidate.priority(),
                        triggers, coldBreakCandidate, humanSpoke).speak()) {
                    withGate++;
                }
            }
        }

        double reductionPercent = (withoutGate - withGate) * 100.0 / withoutGate;
        System.out.printf("SPEECH_GATE_EVAL rounds=%d agents=%d baseline_calls=%d gated_calls=%d reduction=%.1f%%%n",
                rounds, candidates.size(), withoutGate, withGate, reductionPercent);

        assertTrue(withGate > 0, "evaluation workload must still produce speakers");
        assertTrue(withGate < withoutGate, "SpeechGate should remove unnecessary generation candidates");
    }
}
