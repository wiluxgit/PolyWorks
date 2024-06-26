package net.wilux.objects.base.blockentity;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.wilux.PolyWorks;
import net.wilux.polyattchments.BlockAttachment;
import org.jetbrains.annotations.Nullable;


public abstract class BlockEntityWithAttachments<TBlockAttachment extends BlockAttachment> extends BlockEntity {
    public BlockEntityWithAttachments(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public abstract void initializeAttachment(World world, BlockPos pos, BlockState blockState);
    public abstract void removeAttachment();
    public abstract @Nullable TBlockAttachment getAttachment();

    public void tick(World world, BlockPos pos, BlockState blockState) { // Kinda stupid this is needed, but world is not accessible in new
        assert this.world != null;
        if (this.getAttachment() == null) {
            PolyWorks.LOGGER.info("initializeAttachment: "+this.getClass().getSimpleName());
            this.initializeAttachment(world, pos, blockState);
        }
    }

    @Override
    public void markRemoved() {
        PolyWorks.LOGGER.info("markRemoved: "+this.getClass().getSimpleName());
        removeAttachment();
        super.markRemoved();
    }
}
