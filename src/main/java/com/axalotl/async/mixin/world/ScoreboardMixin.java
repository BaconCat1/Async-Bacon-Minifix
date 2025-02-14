package com.axalotl.async.mixin.world;

import com.axalotl.async.parallelised.ConcurrentCollections;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Scores;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

@Mixin(Scoreboard.class)
public class ScoreboardMixin {
    @Shadow
    private final Map<String, Scores> scores = ConcurrentCollections.newHashMap();
}