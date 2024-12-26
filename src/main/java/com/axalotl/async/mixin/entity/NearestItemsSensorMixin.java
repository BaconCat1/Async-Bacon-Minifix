package com.axalotl.async.mixin.entity;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ai.brain.Brain;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.sensor.NearestItemsSensor;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListSet;

@Mixin(NearestItemsSensor.class)
public class NearestItemsSensorMixin {
    @Inject(method = "sense(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/entity/mob/MobEntity;)V", at = @At("HEAD"), cancellable = true)
    private void onSense(ServerWorld serverWorld, MobEntity mobEntity, CallbackInfo ci) {
        Brain<?> brain = mobEntity.getBrain();
        List<ItemEntity> list = serverWorld.getEntitiesByClass(ItemEntity.class, mobEntity.getBoundingBox().expand(32.0, 16.0, 32.0), itemEntity -> true);
        ConcurrentSkipListSet<ItemEntity> sortedItems = new ConcurrentSkipListSet<>((item1, item2) -> {
            int distanceCompare = Double.compare(mobEntity.squaredDistanceTo(item1), mobEntity.squaredDistanceTo(item2));
            if (distanceCompare != 0) {
                return distanceCompare;
            }
            int idCompare = Integer.compare(item1.getId(), item2.getId());
            if (idCompare != 0) {
                return idCompare;
            }
            return Integer.compare(System.identityHashCode(item1), System.identityHashCode(item2));
        });
        sortedItems.addAll(list);
        ObjectArrayList<ItemEntity> sortedItemList = new ObjectArrayList<>(sortedItems);
        Optional<ItemEntity> optionalItem = sortedItemList.stream()
                .filter(itemEntity -> mobEntity.canGather(serverWorld, itemEntity.getStack()))
                .filter(itemEntity -> itemEntity.isInRange(mobEntity, 32.0))
                .filter(mobEntity::canSee)
                .findFirst();
        brain.remember(MemoryModuleType.NEAREST_VISIBLE_WANTED_ITEM, optionalItem);
        ci.cancel();
    }
}
