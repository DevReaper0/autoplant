package xyz.devreaper0.autoplant;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class AutoPlant implements ClientModInitializer {
    private static final String MOD_ID = "autoplant";

    private static boolean enabled = false;

    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "general"));

    private static final KeyMapping TOGGLE_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.autoplant.toggle", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY));

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (TOGGLE_KEY.consumeClick()) {
                enabled = !enabled;

                if (client.player != null) {
                    client.player.sendOverlayMessage(Component.literal("Auto Plant: " + (enabled ? "ON" : "OFF")));
                }
            }

            if (enabled) {
                tryPlant(client);
            }
        });
    }

    private static void tryPlant(Minecraft client) {
        if (client.player == null || client.level == null || client.gameMode == null) {
            return;
        }

        double reach = client.player.blockInteractionRange();
        Vec3 eyePos = client.player.getEyePosition();
        BlockPos origin = client.player.blockPosition();
        int radius = (int) Math.ceil(reach);

        for (int x = origin.getX() - radius; x <= origin.getX() + radius; x++) {
            for (int y = origin.getY() - radius; y <= origin.getY() + radius; y++) {
                for (int z = origin.getZ() - radius; z <= origin.getZ() + radius; z++) {
                    BlockPos supportPos = new BlockPos(x, y, z);
                    BlockState supportState = client.level.getBlockState(supportPos);

                    if (supportState.isAir() || supportState.getFluidState().is(Fluids.WATER)) {
                        continue;
                    }

                    CollisionContext collisionContext = CollisionContext.of(client.player);
                    VoxelShape shape = supportState.getShape(client.level, supportPos, collisionContext);

                    if (shape.isEmpty()) {
                        continue;
                    }

                    AABB bounds = shape.bounds();

                    for (Direction direction : Direction.values()) {
                        Vec3 hitPos = getHitPosition(supportPos, bounds, direction);

                        if (eyePos.distanceToSqr(hitPos) > reach * reach) {
                            continue;
                        }

                        BlockHitResult hit = new BlockHitResult(hitPos, direction, supportPos, false);
                        BlockHitResult sight = client.level.clip(new ClipContext(eyePos, hitPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, collisionContext));

                        if (!sight.getBlockPos().equals(supportPos)) {
                            continue;
                        }

                        for (InteractionHand hand : InteractionHand.values()) {
                            ItemStack stack = client.player.getItemInHand(hand);

                            if (!(stack.getItem() instanceof BlockItem blockItem)) {
                                continue;
                            }

                            if (!(blockItem.getBlock() instanceof VegetationBlock)) {
                                continue;
                            }

                            if (!canPlant(client, stack, blockItem, hand, hit)) {
                                continue;
                            }

                            InteractionResult result = client.gameMode.useItemOn(client.player, hand, hit);

                            if (result.consumesAction()) {
                                return;
                            } else if (result == InteractionResult.FAIL) {
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    private static Vec3 getHitPosition(BlockPos pos, AABB bounds, Direction direction) {
        double x = (bounds.minX + bounds.maxX) * 0.5;
        double y = (bounds.minY + bounds.maxY) * 0.5;
        double z = (bounds.minZ + bounds.maxZ) * 0.5;

        switch (direction) {
            case DOWN -> y = bounds.minY + 0.001;
            case UP -> y = bounds.maxY - 0.001;
            case NORTH -> z = bounds.minZ + 0.001;
            case SOUTH -> z = bounds.maxZ - 0.001;
            case WEST -> x = bounds.minX + 0.001;
            case EAST -> x = bounds.maxX - 0.001;
        }

        return new Vec3(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
    }

    private static boolean canPlant(Minecraft client, ItemStack stack, BlockItem blockItem, InteractionHand hand, BlockHitResult hit) {
        if (client.player == null || client.level == null) {
            return false;
        }

        BlockPos supportPos = hit.getBlockPos();
        BlockPos placementPos = supportPos.relative(hit.getDirection());

        BlockState existingState = client.level.getBlockState(placementPos);

        if (existingState.getFluidState().is(Fluids.WATER)) {
            return false;
        }

        BlockPlaceContext context = new BlockPlaceContext(
                client.player,
                hand,
                stack,
                hit
        );

        if (!context.canPlace()) {
            return false;
        }

        BlockState placementState = blockItem.getBlock().getStateForPlacement(context);

        if (placementState == null) {
            return false;
        }

        return existingState.canBeReplaced() && placementState.canSurvive(client.level, placementPos);
    }
}