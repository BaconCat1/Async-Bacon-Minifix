package com.axalotl.async;

import com.axalotl.async.config.AsyncConfig;
import com.axalotl.async.parallelised.ConcurrentCollections;
import com.axalotl.async.parallelised.thread.ExecutorManager;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.entity.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.vehicle.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class ParallelProcessor {
    private static final Logger LOGGER = LogManager.getLogger();

    @Getter
    @Setter
    private static MinecraftServer server;

    public static final AtomicInteger currentEntities = new AtomicInteger();
    private static final AtomicInteger threadPoolID = new AtomicInteger();
    private static ExecutorManager tickPool;
    private static final Set<UUID> blacklistedEntity = ConcurrentHashMap.newKeySet();
    private static final Map<String, Set<Thread>> mcThreadTracker = ConcurrentCollections.newHashMap();
    private static final Set<Class<?>> specialEntities = Set.of(
            FallingBlockEntity.class,
            PlayerEntity.class,
            ServerPlayerEntity.class
    );

    public static void setupThreadPool(int parallelism) {
        tickPool = new ExecutorManager(parallelism, thread -> {
            thread.setName("Async-Tick-Pool-Thread-" + threadPoolID.getAndIncrement());
            registerThread("Async-Tick", thread);
            thread.setDaemon(true);
            thread.setPriority(Math.min(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            thread.setContextClassLoader(Async.class.getClassLoader());
            thread.setUncaughtExceptionHandler((t, e) -> LOGGER.error("Uncaught exception in thread {}: {}", t.getName(), e));
        });
        LOGGER.info("Initialized ThreadPool with {} threads", parallelism);
    }

    public static void registerThread(String poolName, Thread thread) {
        mcThreadTracker.computeIfAbsent(poolName, key -> ConcurrentHashMap.newKeySet()).add(thread);
    }

    private static boolean isThreadInPool(Thread thread) {
        return mcThreadTracker.getOrDefault("Async-Tick", Set.of()).contains(thread);
    }

    public static boolean isServerExecutionThread() {
        return isThreadInPool(Thread.currentThread());
    }

    public static void callEntityTick(Consumer<Entity> tickConsumer, Entity entity) {
        if (shouldTickSynchronously(entity)) {
            tickSynchronously(tickConsumer, entity);
        } else {
            if (tickPool != null) {
                tickPool.schedule(() -> {
                    try {
                        performAsyncEntityTick(tickConsumer, entity);
                    } catch (Exception e) {
                        logEntityError("Error in async tick, switching to synchronous", entity, e);
                        tickSynchronously(tickConsumer, entity);
                        blacklistedEntity.add(entity.getUuid());
                    }
                }, 1);
            } else {
                tickSynchronously(tickConsumer, entity);
            }
        }
    }

    public static boolean shouldTickSynchronously(Entity entity) {
        return AsyncConfig.disabled ||
                blacklistedEntity.contains(entity.getUuid()) ||
                specialEntities.contains(entity.getClass()) ||
                AsyncConfig.synchronizedEntities.contains(EntityType.getId(entity.getType())) ||
                isPortalTickRequired(entity) ||
                entity.hasPlayerRider() ||
                entity instanceof ProjectileEntity ||
                entity instanceof AbstractMinecartEntity;
    }

    private static boolean isPortalTickRequired(Entity entity) {
        return entity.portalManager != null && entity.portalManager.isInPortal();
    }

    private static void tickSynchronously(Consumer<Entity> tickConsumer, Entity entity) {
        try {
            tickConsumer.accept(entity);
        } catch (Exception e) {
            logEntityError("Error during synchronous tick", entity, e);
        }
    }

    private static void performAsyncEntityTick(Consumer<Entity> tickConsumer, Entity entity) {
        currentEntities.incrementAndGet();
        try {
            tickConsumer.accept(entity);
        } finally {
            currentEntities.decrementAndGet();
        }
    }

    public static void postEntityTick() {
        if (!AsyncConfig.disabled) {
            try {
                server.getWorlds().forEach(world -> {
                    world.getChunkManager().executeQueuedTasks();
                    world.getChunkManager().mainThreadExecutor.runTasks(() -> !tickPool.hasPendingTasks());
                });
            } catch (CompletionException e) {
                LOGGER.error("Critical error during entity tick processing", e);
                server.shutdown();
            }
        }
    }

    public static void stop() {
        if (tickPool != null) {
            tickPool.shutdown();
        }
    }

    private static void logEntityError(String message, Entity entity, Throwable e) {
        LOGGER.error("{} Entity Type: {}, UUID: {}", message, entity.getType().getName(), entity.getUuid(), e);
    }
}
