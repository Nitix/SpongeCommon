/*
 * This file is part of Sponge, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.spongepowered.common.event.tracking.phase.tick;

import com.flowpowered.math.vector.Vector3d;
import net.minecraft.entity.EntityHanging;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IProjectile;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityItemFrame;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.CombatEntry;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.api.block.BlockSnapshot;
import org.spongepowered.api.data.Transaction;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.Transform;
import org.spongepowered.api.entity.living.Ageable;
import org.spongepowered.api.entity.living.player.User;
import org.spongepowered.api.entity.projectile.Projectile;
import org.spongepowered.api.event.SpongeEventFactory;
import org.spongepowered.api.event.cause.Cause;
import org.spongepowered.api.event.cause.NamedCause;
import org.spongepowered.api.event.cause.entity.spawn.BlockSpawnCause;
import org.spongepowered.api.event.cause.entity.spawn.EntitySpawnCause;
import org.spongepowered.api.event.cause.entity.teleport.EntityTeleportCause;
import org.spongepowered.api.event.cause.entity.teleport.TeleportTypes;
import org.spongepowered.api.event.entity.MoveEntityEvent;
import org.spongepowered.api.event.entity.SpawnEntityEvent;
import org.spongepowered.api.event.item.inventory.DropItemEvent;
import org.spongepowered.api.world.World;
import org.spongepowered.common.SpongeImpl;
import org.spongepowered.common.entity.EntityUtil;
import org.spongepowered.common.event.tracking.PhaseContext;
import org.spongepowered.common.event.tracking.TrackingUtil;
import org.spongepowered.common.interfaces.world.IMixinLocation;
import org.spongepowered.common.registry.type.event.InternalSpawnTypes;
import org.spongepowered.common.util.VecHelper;
import org.spongepowered.common.world.BlockChange;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

class EntityTickPhaseState extends TickPhaseState {

    EntityTickPhaseState() {
    }

    @SuppressWarnings("unchecked")
    @Override
    public void processPostTick(PhaseContext phaseContext) {
        final Entity tickingEntity = phaseContext.getSource(Entity.class)
                .orElseThrow(TrackingUtil.throwWithContext("Not ticking on an Entity!", phaseContext));
        final Optional<User> creator = phaseContext.getOwner();
        final Optional<User> notifier = phaseContext.getNotifier();
        final User entityCreator = notifier.orElseGet(() -> creator.orElse(null));
        phaseContext.getCapturedEntitySupplier()
                .ifPresentAndNotEmpty(entities -> {
                    final List<Entity> experience = new ArrayList<Entity>(entities.size());
                    final List<Entity> nonExp = new ArrayList<Entity>(entities.size());
                    final List<Entity> breeding = new ArrayList<Entity>(entities.size());
                    final List<Entity> projectile = new ArrayList<Entity>(entities.size());
                    for (Entity entity : entities) {
                        if (entity instanceof EntityXPOrb) {
                            experience.add(entity);
                        } else if (tickingEntity instanceof Ageable && tickingEntity.getClass() == entity.getClass()) {
                            breeding.add(entity);
                        } else if (entity instanceof Projectile) {
                            projectile.add(entity);
                        } else {
                            nonExp.add(entity);
                        }
                    }

                    if (!experience.isEmpty()) {
                        final Cause.Builder builder = Cause.source(
                                EntitySpawnCause.builder()
                                        .entity(tickingEntity)
                                        .type(InternalSpawnTypes.EXPERIENCE)
                                        .build()
                        );
                        notifier.ifPresent(builder::notifier);
                        creator.ifPresent(builder::owner);
                        if (EntityUtil.isEntityDead(tickingEntity)) {
                            if (tickingEntity instanceof EntityLivingBase) {
                                CombatEntry entry = ((EntityLivingBase) tickingEntity).getCombatTracker().getBestCombatEntry();
                                if (entry != null) {
                                    if (entry.damageSrc != null) {
                                        builder.named(NamedCause.of("LastDamageSource", entry.damageSrc));
                                    }
                                }
                            }
                        }
                        final SpawnEntityEvent
                                event =
                                SpongeEventFactory.createSpawnEntityEvent(builder.build(), experience);
                        if (!SpongeImpl.postEvent(event)) {
                            for (Entity entity : event.getEntities()) {
                                if (entityCreator != null) {
                                    EntityUtil.toMixin(entity).setCreator(entityCreator.getUniqueId());
                                }
                                EntityUtil.getMixinWorld(entity).forceSpawnEntity(entity);
                            }
                        }
                    }
                    if (!breeding.isEmpty()) {
                        final Cause.Builder builder = Cause.source(
                                EntitySpawnCause.builder()
                                        .entity(tickingEntity)
                                        .type(InternalSpawnTypes.BREEDING)
                                        .build()
                        );
                        if (tickingEntity instanceof EntityAnimal) {
                            final EntityPlayer playerInLove = ((EntityAnimal) tickingEntity).getPlayerInLove();
                            if (playerInLove != null) {
                                builder.named(NamedCause.of("Player", playerInLove));
                            }
                        }
                        SpawnEntityEvent event = SpongeEventFactory.createSpawnEntityEvent(builder.build(), breeding);
                        if (!SpongeImpl.postEvent(event)) {
                            for (Entity entity : event.getEntities()) {
                                if (entityCreator != null) {
                                    EntityUtil.toMixin(entity).setCreator(entityCreator.getUniqueId());
                                }
                                EntityUtil.getMixinWorld(entity).forceSpawnEntity(entity);
                            }
                        }
                    }
                    if (!projectile.isEmpty()) {
                        final Cause.Builder builder = Cause.source(
                                EntitySpawnCause.builder()
                                        .entity(tickingEntity)
                                        .type(InternalSpawnTypes.PROJECTILE)
                                        .build()
                        );

                        notifier.ifPresent(builder::notifier);
                        creator.ifPresent(builder::owner);
                        final SpawnEntityEvent event = SpongeEventFactory.createSpawnEntityEvent(builder.build(), projectile);
                        SpongeImpl.postEvent(event);
                        if (!event.isCancelled()) {
                            for (Entity entity : event.getEntities()) {
                                if (entityCreator != null) {
                                    entity.setCreator(entityCreator.getUniqueId());
                                }
                                EntityUtil.getMixinWorld(entity).forceSpawnEntity(entity);
                            }
                        }
                    }

                    final Cause.Builder builder = Cause.source(EntitySpawnCause.builder()
                            .entity(tickingEntity)
                            .type(InternalSpawnTypes.PASSIVE)
                            .build());
                    notifier.ifPresent(builder::notifier);
                    creator.ifPresent(builder::owner);
                    final SpawnEntityEvent event = SpongeEventFactory.createSpawnEntityEvent(builder.build(), nonExp);
                    SpongeImpl.postEvent(event);
                    if (!event.isCancelled()) {
                        for (Entity entity : event.getEntities()) {
                            if (entityCreator != null) {
                                entity.setCreator(entityCreator.getUniqueId());
                            }
                            EntityUtil.getMixinWorld(entity).forceSpawnEntity(entity);
                        }
                    }
                });
        phaseContext.getCapturedItemsSupplier()
                .ifPresentAndNotEmpty(entities -> {
                    final ArrayList<Entity> capturedEntities = new ArrayList<>();
                    for (EntityItem entity : entities) {
                        capturedEntities.add(EntityUtil.fromNative(entity));
                    }
                    final Cause.Builder builder = Cause.source(EntitySpawnCause.builder()
                            .entity(tickingEntity)
                            .type(InternalSpawnTypes.DROPPED_ITEM)
                            .build());
                    notifier.ifPresent(user -> builder.named(NamedCause.notifier(user)));
                    creator.ifPresent(user -> builder.named(NamedCause.owner(user)));
                    final DropItemEvent.Custom event = SpongeEventFactory
                            .createDropItemEventCustom(builder.build(), capturedEntities);
                    SpongeImpl.postEvent(event);
                    if (!event.isCancelled()) {
                        for (Entity entity : event.getEntities()) {
                            if (entityCreator != null) {
                                EntityUtil.toMixin(entity).setCreator(entityCreator.getUniqueId());
                            }
                            EntityUtil.getMixinWorld(entity).forceSpawnEntity(entity);
                        }
                    }
                });
        phaseContext.getCapturedBlockSupplier()
                .ifPresentAndNotEmpty(blockSnapshots -> TrackingUtil.processBlockCaptures(blockSnapshots, this, phaseContext));
        phaseContext.getBlockItemDropSupplier()
                .ifPresentAndNotEmpty(map -> {
                    final List<BlockSnapshot> capturedBlocks = phaseContext.getCapturedBlocks();
                    for (BlockSnapshot snapshot : capturedBlocks) {
                        final BlockPos blockPos = ((IMixinLocation) (Object) snapshot.getLocation().get()).getBlockPos();
                        final Collection<EntityItem> entityItems = map.get(blockPos);
                        if (!entityItems.isEmpty()) {
                            final Cause.Builder builder = Cause.source(BlockSpawnCause.builder()
                                    .block(snapshot)
                                    .type(InternalSpawnTypes.DROPPED_ITEM)
                                    .build());
                            notifier.ifPresent(builder::notifier);
                            creator.ifPresent(builder::owner);
                            builder.build();
                            final List<Entity> items = entityItems.stream().map(EntityUtil::fromNative).collect(Collectors.toList());
                            final DropItemEvent.Destruct event = SpongeEventFactory.createDropItemEventDestruct(builder.build(), items);
                            SpongeImpl.postEvent(event);
                            if (!event.isCancelled()) {
                                for (Entity entity : event.getEntities()) {
                                    creator.ifPresent(user -> entity.setCreator(user.getUniqueId()));
                                    EntityUtil.getMixinWorld(entity).forceSpawnEntity(entity);
                                }
                            }
                        }
                    }

                });
        phaseContext.getCapturedItemStackSupplier()
                .ifPresentAndNotEmpty(drops -> {
                    final List<EntityItem> items = drops.stream()
                            .map(drop -> drop.create(EntityUtil.getMinecraftWorld(tickingEntity)))
                            .collect(Collectors.toList());
                    final Cause.Builder builder = Cause.source(
                            EntitySpawnCause.builder()
                                    .entity(tickingEntity)
                                    .type(InternalSpawnTypes.DROPPED_ITEM)
                                    .build()
                    );
                    notifier.ifPresent(user -> builder.named(NamedCause.notifier(user)));
                    creator.ifPresent(user -> builder.named(NamedCause.owner(user)));
                    final Cause cause = builder.build();
                    final List<Entity> entities = (List<Entity>) (List<?>) items;
                    if (!entities.isEmpty()) {
                        DropItemEvent.Custom event = SpongeEventFactory.createDropItemEventCustom(cause, entities);
                        SpongeImpl.postEvent(event);
                        if (!event.isCancelled()) {
                            for (Entity droppedItem : event.getEntities()) {
                                EntityUtil.getMixinWorld(droppedItem).forceSpawnEntity(droppedItem);
                            }
                        }
                    }
                });
        this.fireMovementEvents(EntityUtil.toNative(tickingEntity), Cause.source(tickingEntity).build());
    }

    private void fireMovementEvents(net.minecraft.entity.Entity entity, Cause cause) {
        // Ignore movement event if entity is dead, a projectile, or item.
        // Note: Projectiles are handled with CollideBlockEvent.Impact
        if (entity.isDead || entity instanceof IProjectile || entity instanceof EntityItem) {
            return;
        }

        Entity spongeEntity = (Entity) entity;

        if (entity.lastTickPosX != entity.posX
            || entity.lastTickPosY != entity.posY
            || entity.lastTickPosZ != entity.posZ
            || entity.rotationPitch != entity.prevRotationPitch
            || entity.rotationYaw != entity.prevRotationYaw) {
            // yes we have a move event.
            final double currentPosX = entity.posX;
            final double currentPosY = entity.posY;
            final double currentPosZ = entity.posZ;

            final Vector3d oldPositionVector = new Vector3d(entity.lastTickPosX, entity.lastTickPosY, entity.lastTickPosZ);
            final Vector3d currentPositionVector = new Vector3d(currentPosX, currentPosY, currentPosZ);

            Vector3d oldRotationVector = new Vector3d(entity.prevRotationPitch, entity.prevRotationYaw, 0);
            Vector3d currentRotationVector = new Vector3d(entity.rotationPitch, entity.rotationYaw, 0);
            final Transform<World> oldTransform = new Transform<>(spongeEntity.getWorld(), oldPositionVector, oldRotationVector,
                    spongeEntity.getScale());
            final Transform<World> newTransform = new Transform<>(spongeEntity.getWorld(), currentPositionVector, currentRotationVector,
                    spongeEntity.getScale());
            final MoveEntityEvent event = SpongeEventFactory.createMoveEntityEvent(cause, oldTransform, newTransform, spongeEntity);

            if (SpongeImpl.postEvent(event)) {
                entity.posX = entity.lastTickPosX;
                entity.posY = entity.lastTickPosY;
                entity.posZ = entity.lastTickPosZ;
                entity.rotationPitch = entity.prevRotationPitch;
                entity.rotationYaw = entity.prevRotationYaw;
            } else {
                Vector3d newPosition = event.getToTransform().getPosition();
                if (!newPosition.equals(currentPositionVector)) {
                    entity.posX = newPosition.getX();
                    entity.posY = newPosition.getY();
                    entity.posZ = newPosition.getZ();
                }
                if (!event.getToTransform().getRotation().equals(currentRotationVector)) {
                    entity.rotationPitch = (float) currentRotationVector.getX();
                    entity.rotationYaw = (float) currentRotationVector.getY();
                }
                //entity.setPositionWithRotation(position.getX(), position.getY(), position.getZ(), rotation.getFloorX(), rotation.getFloorY());
                    /*
                    Some thoughts from gabizou: The interesting thing here is that while this is only called
                    in World.updateEntityWithOptionalForce, by default, it supposedly handles updating the rider entity
                    of the entity being handled here. The interesting issue is that since we are setting the transform,
                    the rider entity (and the rest of the rider entities) are being updated as well with the new position
                    and potentially world, which results in a dirty world usage (since the world transfer is handled by
                    us). Now, the thing is, the previous position is not updated either, and likewise, the current position
                    is being set by us as well. So, there's some issue I'm sure that is bound to happen with this
                    logic.
                     */
                //((Entity) entity).setTransform(event.getToTransform());
            }
        }
    }

    @Override
    public Cause generateTeleportCause(PhaseContext context) {
        final Entity entity = context.getSource(Entity.class)
                .orElseThrow(TrackingUtil.throwWithContext("Expected to be ticking an entity!", context));
        return Cause
                .source(EntityTeleportCause.builder()
                        .entity(entity)
                        .type(TeleportTypes.ENTITY_TELEPORT)
                        .build()
                )
                .build();
    }

    @SuppressWarnings("OptionalGetWithoutIsPresent")
    @Override
    public void handleBlockChangeWithUser(@Nullable BlockChange blockChange, Transaction<BlockSnapshot> transaction,
        PhaseContext context) {
        if (blockChange == BlockChange.BREAK) {
            final Entity tickingEntity = context.getSource(Entity.class).get();
            final BlockPos blockPos = VecHelper.toBlockPos(transaction.getOriginal().getPosition());
            for (EntityHanging entityHanging : EntityUtil.findHangingEntities(EntityUtil.getMinecraftWorld(tickingEntity), blockPos)) {
                if (entityHanging instanceof EntityItemFrame) {
                    final EntityItemFrame frame = (EntityItemFrame) entityHanging;
                    if (tickingEntity != null && !frame.isDead) {
                        frame.dropItemOrSelf(EntityUtil.toNative(tickingEntity), true);
                    }
                    frame.setDead();
                }
            }
        }
    }


    @Override
    public void associateAdditionalBlockChangeCauses(PhaseContext context, Cause.Builder builder) {
        final Entity tickingEntity = context.getSource(Entity.class)
                .orElseThrow(TrackingUtil.throwWithContext("Not ticking on an Entity!", context));
        builder.named(NamedCause.owner(tickingEntity));
        context.getNotifier().ifPresent(builder::notifier);
    }


    @Override
    public void processPostSpawns(PhaseContext phaseContext, ArrayList<Entity> entities) {
        super.processPostSpawns(phaseContext, entities);
    }

    @Override
    public void appendExplosionContext(PhaseContext explosionContext, PhaseContext context) {
        context.getOwner().ifPresent(explosionContext::owner);
        context.getNotifier().ifPresent(explosionContext::notifier);
        final Entity tickingEntity = context.getSource(Entity.class)
                .orElseThrow(TrackingUtil.throwWithContext("Expected to be processing over a ticking entity!", context));
        explosionContext.add(NamedCause.source(tickingEntity));
    }

    @Override
    public boolean spawnEntityOrCapture(PhaseContext context, Entity entity, int chunkX, int chunkZ) {
        final Entity tickingEntity = context.getSource(Entity.class)
                .orElseThrow(TrackingUtil.throwWithContext("Not ticking on an Entity!", context));
        final Optional<User> creator = context.getOwner();
        final Optional<User> notifier = context.getNotifier();
        final User entityCreator = notifier.orElseGet(() -> creator.orElse(null));
        if (entity instanceof EntityXPOrb) {
            final Cause.Builder builder = Cause.source(
                    EntitySpawnCause.builder()
                            .entity(tickingEntity)
                            .type(InternalSpawnTypes.EXPERIENCE)
                            .build()
            );
            notifier.ifPresent(builder::notifier);
            creator.ifPresent(builder::owner);
            if (EntityUtil.isEntityDead(tickingEntity)) {
                if (tickingEntity instanceof EntityLivingBase) {
                    CombatEntry entry = ((EntityLivingBase) tickingEntity).getCombatTracker().getBestCombatEntry();
                    if (entry != null) {
                        if (entry.damageSrc != null) {
                            builder.named(NamedCause.of("LastDamageSource", entry.damageSrc));
                        }
                    }
                }
            }
            final List<Entity> experience = new ArrayList<Entity>(1);
            experience.add(entity);

            final SpawnEntityEvent
                    event =
                    SpongeEventFactory.createSpawnEntityEvent(builder.build(), experience);
            if (!SpongeImpl.postEvent(event)) {
                for (Entity anEntity : event.getEntities()) {
                    if (entityCreator != null) {
                        EntityUtil.toMixin(anEntity).setCreator(entityCreator.getUniqueId());
                    }
                    EntityUtil.getMixinWorld(entity).forceSpawnEntity(anEntity);
                }
                return true;
            }
            return false;
        } else if (tickingEntity instanceof Ageable && tickingEntity.getClass() == entity.getClass()) {
            final Cause.Builder builder = Cause.source(
                    EntitySpawnCause.builder()
                            .entity(tickingEntity)
                            .type(InternalSpawnTypes.BREEDING)
                            .build()
            );
            if (tickingEntity instanceof EntityAnimal) {
                final EntityPlayer playerInLove = ((EntityAnimal) tickingEntity).getPlayerInLove();
                if (playerInLove != null) {
                    builder.named(NamedCause.of("Player", playerInLove));
                }
            }
            final List<Entity> breeding = new ArrayList<Entity>(1);
            breeding.add(entity);
            SpawnEntityEvent event = SpongeEventFactory.createSpawnEntityEvent(builder.build(), breeding);
            if (!SpongeImpl.postEvent(event)) {
                for (Entity anEntity : event.getEntities()) {
                    if (entityCreator != null) {
                        EntityUtil.toMixin(anEntity).setCreator(entityCreator.getUniqueId());
                    }
                    EntityUtil.getMixinWorld(entity).forceSpawnEntity(anEntity);
                }
                return true;
            }
            return false;
        } else if (entity instanceof Projectile) {
            final Cause.Builder builder = Cause.source(
                    EntitySpawnCause.builder()
                            .entity(tickingEntity)
                            .type(InternalSpawnTypes.PROJECTILE)
                            .build()
            );
            final List<Entity> projectile = new ArrayList<Entity>(1);
            projectile.add(entity);

            notifier.ifPresent(builder::notifier);
            creator.ifPresent(builder::owner);
            final SpawnEntityEvent event = SpongeEventFactory.createSpawnEntityEvent(builder.build(), projectile);
            SpongeImpl.postEvent(event);
            if (!event.isCancelled()) {
                for (Entity anEntity : event.getEntities()) {
                    if (entityCreator != null) {
                        anEntity.setCreator(entityCreator.getUniqueId());
                    }
                    EntityUtil.getMixinWorld(entity).forceSpawnEntity(anEntity);
                }
                return true;
            }
            return false;
        }
        final List<Entity> nonExp = new ArrayList<Entity>(1);
        nonExp.add(entity);

        final Cause.Builder builder = Cause.source(EntitySpawnCause.builder()
                .entity(tickingEntity)
                .type(InternalSpawnTypes.PASSIVE)
                .build());
        notifier.ifPresent(builder::notifier);
        creator.ifPresent(builder::owner);
        final SpawnEntityEvent event = SpongeEventFactory.createSpawnEntityEvent(builder.build(), nonExp);
        SpongeImpl.postEvent(event);
        if (!event.isCancelled()) {
            for (Entity anEntity : event.getEntities()) {
                if (entityCreator != null) {
                    anEntity.setCreator(entityCreator.getUniqueId());
                }
                EntityUtil.getMixinWorld(entity).forceSpawnEntity(anEntity);
            }
            return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return "EntityTickPhase";
    }
}
