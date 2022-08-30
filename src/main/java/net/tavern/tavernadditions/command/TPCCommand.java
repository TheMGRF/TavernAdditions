package net.tavern.tavernadditions.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.commands.arguments.coordinates.WorldCoordinates;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.*;

public class TPCCommand {

    private static final SimpleCommandExceptionType INVALID_POSITION = new SimpleCommandExceptionType(new TranslatableComponent("commands.teleport.invalidPosition"));

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tpc")
                .then(Commands.argument("location", Vec3Argument.vec3())
                        .executes((cmd) -> teleportToPos(cmd.getSource(),
                                Collections.singleton(cmd.getSource().getEntityOrException()),
                                cmd.getSource().getLevel(),
                                Vec3Argument.getCoordinates(cmd, "location"),
                                WorldCoordinates.current(),
                                null))));
    }

    private static int teleportToPos(CommandSourceStack commandSourceStack, Collection<? extends Entity> entities, ServerLevel level, Coordinates coords, @Nullable Coordinates offset, @Nullable LookAt lookAt) throws CommandSyntaxException {
        Vec3 vec3 = coords.getPosition(commandSourceStack);
        Vec2 vec2 = offset == null ? null : offset.getRotation(commandSourceStack);
        Set<ClientboundPlayerPositionPacket.RelativeArgument> set = EnumSet.noneOf(ClientboundPlayerPositionPacket.RelativeArgument.class);
        if (coords.isXRelative()) {
            set.add(ClientboundPlayerPositionPacket.RelativeArgument.X);
        }

        if (coords.isYRelative()) {
            set.add(ClientboundPlayerPositionPacket.RelativeArgument.Y);
        }

        if (coords.isZRelative()) {
            set.add(ClientboundPlayerPositionPacket.RelativeArgument.Z);
        }

        if (offset == null) {
            set.add(ClientboundPlayerPositionPacket.RelativeArgument.X_ROT);
            set.add(ClientboundPlayerPositionPacket.RelativeArgument.Y_ROT);
        } else {
            if (offset.isXRelative()) {
                set.add(ClientboundPlayerPositionPacket.RelativeArgument.X_ROT);
            }

            if (offset.isYRelative()) {
                set.add(ClientboundPlayerPositionPacket.RelativeArgument.Y_ROT);
            }
        }

        for (Entity entity : entities) {
            if (offset == null) {
                performTeleport(commandSourceStack, entity, level, vec3.x, vec3.y, vec3.z, set, entity.getYRot(), entity.getXRot(), lookAt);
            } else {
                performTeleport(commandSourceStack, entity, level, vec3.x, vec3.y, vec3.z, set, vec2.y, vec2.x, lookAt);
            }
        }

        if (entities.size() == 1) {
            commandSourceStack.sendSuccess(new TranslatableComponent("commands.teleport.success.location.single", entities.iterator().next().getDisplayName(), formatDouble(vec3.x), formatDouble(vec3.y), formatDouble(vec3.z)), true);
        } else {
            commandSourceStack.sendSuccess(new TranslatableComponent("commands.teleport.success.location.multiple", entities.size(), formatDouble(vec3.x), formatDouble(vec3.y), formatDouble(vec3.z)), true);
        }

        return entities.size();
    }

    private static String formatDouble(double val) {
        return String.format(Locale.ROOT, "%f", val);
    }

    private static void performTeleport(CommandSourceStack commandSourceStack, Entity entity, ServerLevel level, double x, double y, double z, Set<ClientboundPlayerPositionPacket.RelativeArgument> relativeArguments, float yaw, float pitch, @Nullable LookAt lookAt) throws CommandSyntaxException {
        net.minecraftforge.event.entity.EntityTeleportEvent.TeleportCommand event = net.minecraftforge.event.ForgeEventFactory.onEntityTeleportCommand(entity, x, y, z);
        if (event.isCanceled()) return;

        x = event.getTargetX();
        y = event.getTargetY();
        z = event.getTargetZ();
        BlockPos blockpos = new BlockPos(x, y, z);
        if (!Level.isInSpawnableBounds(blockpos)) {
            throw INVALID_POSITION.create();
        }

        float f = Mth.wrapDegrees(yaw);
        float f1 = Mth.wrapDegrees(pitch);
        if (entity instanceof ServerPlayer) {
            ChunkPos chunkpos = new ChunkPos(new BlockPos(x, y, z));
            level.getChunkSource().addRegionTicket(TicketType.POST_TELEPORT, chunkpos, 1, entity.getId());
            entity.stopRiding();
            if (((ServerPlayer) entity).isSleeping()) {
                ((ServerPlayer) entity).stopSleepInBed(true, true);
            }

            if (level == entity.level) {
                ((ServerPlayer) entity).connection.teleport(x, y, z, f, f1, relativeArguments);
            } else {
                ((ServerPlayer) entity).teleportTo(level, x, y, z, f, f1);
            }

            entity.setYHeadRot(f);
        } else {
            float f2 = Mth.clamp(f1, -90.0F, 90.0F);
            if (level == entity.level) {
                entity.moveTo(x, y, z, f, f2);
                entity.setYHeadRot(f);
            } else {
                entity.unRide();
                Entity theEntity = entity;
                entity = entity.getType().create(level);
                if (entity == null) {
                    return;
                }

                entity.restoreFrom(theEntity);
                entity.moveTo(x, y, z, f, f2);
                entity.setYHeadRot(f);
                theEntity.setRemoved(Entity.RemovalReason.CHANGED_DIMENSION);
                level.addDuringTeleport(entity);
            }
        }

        if (lookAt != null) {
            lookAt.perform(commandSourceStack, entity);
        }

        if (!(entity instanceof LivingEntity) || !((LivingEntity) entity).isFallFlying()) {
            entity.setDeltaMovement(entity.getDeltaMovement().multiply(1.0D, 0.0D, 1.0D));
            entity.setOnGround(true);
        }

        if (entity instanceof PathfinderMob) {
            ((PathfinderMob) entity).getNavigation().stop();
        }
    }

    static class LookAt {
        private final Vec3 position;
        private final Entity entity;
        private final EntityAnchorArgument.Anchor anchor;

        public LookAt(Entity p_139056_, EntityAnchorArgument.Anchor p_139057_) {
            this.entity = p_139056_;
            this.anchor = p_139057_;
            this.position = p_139057_.apply(p_139056_);
        }

        public LookAt(Vec3 p_139059_) {
            this.entity = null;
            this.position = p_139059_;
            this.anchor = null;
        }

        public void perform(CommandSourceStack commandSourceStack, Entity entity) {
            if (this.entity != null) {
                if (entity instanceof ServerPlayer) {
                    ((ServerPlayer) entity).lookAt(commandSourceStack.getAnchor(), this.entity, this.anchor);
                } else {
                    entity.lookAt(commandSourceStack.getAnchor(), this.position);
                }
            } else {
                entity.lookAt(commandSourceStack.getAnchor(), this.position);
            }
        }
    }
}
