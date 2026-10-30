package com.manbear.block;

import com.manbear.ManbearConfig;
import com.manbear.entity.ManbearEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class BearTrapBlock extends Block {
    public static final BooleanProperty ARMED = BooleanProperty.create("armed");
    /** true when laid by the Manbear: springing it brings him running. */
    public static final BooleanProperty HUNTER_SET = BooleanProperty.create("hunter_set");
    private static final VoxelShape SHAPE = Block.box(1, 0, 1, 15, 3, 15);

    public BearTrapBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(ARMED, true).setValue(HUNTER_SET, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ARMED, HUNTER_SET);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return Shapes.empty();
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos below = pos.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction dir, BlockState neighbor, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (dir == Direction.DOWN && !canSurvive(state, level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, dir, neighbor, level, pos, neighborPos);
    }

    @Override
    public void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (level.isClientSide || !state.getValue(ARMED)) return;
        if (!(entity instanceof LivingEntity living) || entity instanceof ManbearEntity) return;
        if (entity instanceof Player p && (p.isCreative() || p.isSpectator())) return;
        if (state.getValue(HUNTER_SET) && !(entity instanceof Player)) return;   // his traps only catch people
        if (!entity.onGround()) return;

        level.setBlock(pos, state.setValue(ARMED, false), 3);
        level.playSound(null, pos, SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 1.6F, 0.5F);
        level.playSound(null, pos, SoundEvents.CHAIN_BREAK, SoundSource.BLOCKS, 1.4F, 0.6F);

        living.hurt(level.damageSources().generic(), ManbearConfig.TRAP_DAMAGE.get().floatValue());
        int root = ManbearConfig.TRAP_ROOT_TICKS.get();
        living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, root, 6, false, false, true));
        living.addEffect(new MobEffectInstance(MobEffects.JUMP, root, 128, false, false, false));

        if (state.getValue(HUNTER_SET) && entity instanceof ServerPlayer sp) {
            ManbearEntity.onTrapSprung(sp, pos);
        }
    }
}
