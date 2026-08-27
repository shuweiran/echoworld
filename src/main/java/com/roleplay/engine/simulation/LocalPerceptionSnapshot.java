package com.roleplay.engine.simulation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** 服务端生成的局部感知；不可感知角色不会出现在模型上下文中。 */
public record LocalPerceptionSnapshot(List<Peer> peers) {
    public record Peer(String name, long distance, String audibility) {}

    public static LocalPerceptionSnapshot from(AgentState self, Collection<AgentState> all, HearingSystem hearing) {
        if (self == null || all == null || hearing == null) return new LocalPerceptionSnapshot(List.of());
        List<Peer> peers = new ArrayList<>();
        for (AgentState other : all) {
            // “我感知到对方”是对方→我的单向传播，而非互相能听的组队条件。
            if (other == self || !hearing.canHear(other, self, SpeechVolume.NORMAL)) continue;
            long distance = Math.round(self.distanceTo(other));
            peers.add(new Peer(other.getAgentName(), distance, distance < 35 ? "清晰" : distance < 90 ? "可听到" : "模糊"));
        }
        peers.sort(Comparator.comparingLong(Peer::distance));
        return new LocalPerceptionSnapshot(List.copyOf(peers));
    }

    public String toPrompt() {
        if (peers.isEmpty()) return "【局部感知】你身边没有能感知到的人；不要推断场外角色或事件。\n";
        StringBuilder out = new StringBuilder("【局部感知】附近共 ").append(peers.size()).append(" 人：\n");
        for (Peer p : peers) out.append("- ").append(p.name()).append("：").append(p.distance()).append("px，").append(p.audibility()).append("\n");
        return out.toString();
    }
}
