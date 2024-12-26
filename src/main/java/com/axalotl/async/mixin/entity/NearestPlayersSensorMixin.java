package com.axalotl.async.mixin.entity;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.Brain;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.sensor.NearestPlayersSensor;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.predicate.entity.EntityPredicates;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Collectors;

import static net.minecraft.entity.ai.brain.sensor.Sensor.testAttackableTargetPredicate;
import static net.minecraft.entity.ai.brain.sensor.Sensor.testTargetPredicate;

@Mixin(NearestPlayersSensor.class)
public abstract class NearestPlayersSensorMixin {
    @Shadow protected abstract double getFollowRange(LivingEntity entity);

    @Inject(method = "sense", at = @At("HEAD"), cancellable = true)
    protected void sense(ServerWorld world, LivingEntity entity, CallbackInfo ci) {
        List<PlayerEntity> playersList = world.getPlayers().stream()
                .filter(EntityPredicates.EXCEPT_SPECTATOR)
                .filter(player -> entity.isInRange(player, this.getFollowRange(entity)))
                .collect(Collectors.toList());
        ConcurrentSkipListSet<PlayerEntity> sortedPlayers = new ConcurrentSkipListSet<>((player1, player2) -> {
            int distanceCompare = Double.compare(entity.squaredDistanceTo(player1), entity.squaredDistanceTo(player2));
            if (distanceCompare != 0) {
                return distanceCompare;
            }
            int idCompare = Integer.compare(player1.getId(), player2.getId());
            if (idCompare != 0) {
                return idCompare;
            }
            return Integer.compare(System.identityHashCode(player1), System.identityHashCode(player2));
        });
        sortedPlayers.addAll(playersList);
        ObjectArrayList<PlayerEntity> sortedPlayerList = new ObjectArrayList<>(sortedPlayers);
        List<PlayerEntity> visiblePlayers = sortedPlayerList.stream()
                .filter(player -> testTargetPredicate(world, entity, player))
                .toList();
        Brain<?> brain = entity.getBrain();
        brain.remember(MemoryModuleType.NEAREST_PLAYERS, sortedPlayerList);
        brain.remember(MemoryModuleType.NEAREST_VISIBLE_PLAYER, visiblePlayers.isEmpty() ? null : visiblePlayers.getFirst());
        Optional<PlayerEntity> attackablePlayer = visiblePlayers.stream()
                .filter(player -> testAttackableTargetPredicate(world, entity, player))
                .findFirst();
        brain.remember(MemoryModuleType.NEAREST_VISIBLE_TARGETABLE_PLAYER, attackablePlayer);
        ci.cancel();
    }
}
