package net.wilux.objects;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.block.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.SynchronizeRecipesS2CPacket;
import net.minecraft.network.packet.s2c.play.UnlockRecipesS2CPacket;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.ShapelessRecipe;
import net.minecraft.recipe.book.CraftingRecipeCategory;
import net.minecraft.recipe.book.RecipeBookCategory;
import net.minecraft.recipe.book.RecipeBookOptions;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.Pair;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.wilux.PolyWorks;
import net.wilux.objects.base.item.GuiItem;
import net.wilux.objects.base.block.PolyHorizontalFacingBlock;
import net.wilux.recipespoofing.RecipeSpoofHandler;
import net.wilux.register.Registered;
import net.wilux.stackstorage.StoredStack;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.Math.max;
import static net.wilux.util.ServerCast.asServer;

public class XTerm {
    public static class XTermBlock extends PolyHorizontalFacingBlock {
        public XTermBlock(Settings settings, BlockState northPolymerState, BlockState eastPolymerState, BlockState southPolymerState, BlockState westPolymerState) {
            super(settings, northPolymerState, eastPolymerState, southPolymerState, westPolymerState);
        }

        @Override
        public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
            var splayer = asServer(player, world);
            XTermScreenHandler.open(splayer);

            return ActionResult.SUCCESS;
        }
    }

    public static class XTermSpoofer extends RecipeSpoofHandler.RecipeSpoof<ShapelessRecipe> {
        private static final int MAX_COUNT_PER_ITEM = 999_999;

        private final Map<Identifier, StoredStack> items;
        private final RecipeBookOptions recipeBookSettings;

        public XTermSpoofer(ServerPlayerEntity splayer) {
            super(splayer);

            recipeBookSettings = new RecipeBookOptions();
            recipeBookSettings.setGuiOpen(RecipeBookCategory.CRAFTING, true);
            recipeBookSettings.setGuiOpen(RecipeBookCategory.FURNACE, true);
            recipeBookSettings.setGuiOpen(RecipeBookCategory.SMOKER, true);
            recipeBookSettings.setGuiOpen(RecipeBookCategory.BLAST_FURNACE, true);

            List<Pair<ItemStack, Integer>> tempItems = Stream.of(
                    new Pair<Item, Integer>(Items.DIAMOND, 10),
                    new Pair<Item, Integer>(Items.DIRT, 333),
                    new Pair<Item, Integer>(Items.SPONGE, 99),
                    new Pair<Item, Integer>(Items.STONE, 100),
                    new Pair<Item, Integer>(Items.COBBLESTONE, 1003),
                    new Pair<Item, Integer>(Items.OAK_LOG, 2001)
            ).map(x -> new Pair<ItemStack, Integer>(new ItemStack(x.getLeft()), x.getRight())).toList();

            this.items = tempItems.stream().collect(Collectors.toMap(
                kv -> new Identifier(PolyWorks.MOD_ID, "xterm."+kv.getLeft().getItem().getTranslationKey()),
                kv -> new StoredStack(kv.getLeft(), kv.getRight(), MAX_COUNT_PER_ITEM)
            ));
        }

        public void forget(Identifier recipe) {
            var unlockRecipes = new UnlockRecipesS2CPacket(UnlockRecipesS2CPacket.Action.REMOVE, List.of(recipe), List.of(), recipeBookSettings);
            splayer.networkHandler.sendPacket(unlockRecipes);
        }
        public void reRemember(Identifier recipe) {
            var unlockRecipes = new UnlockRecipesS2CPacket(UnlockRecipesS2CPacket.Action.INIT, List.of(recipe), List.of(), recipeBookSettings);
            splayer.networkHandler.sendPacket(unlockRecipes);
        }

        public @Nullable StoredStack.InTransfer insertAsManyAsPossible(ItemStack itemStack) {
            // Todo? this method could be significantly faster
            Map.Entry<Identifier, StoredStack> matchingEntry = this.items.entrySet().stream()
                    .filter(entry -> ItemStack.canCombine(itemStack, entry.getValue().stackCopy()))
                    .findFirst()
                    .orElse(null);

            if (matchingEntry == null) {
                PolyWorks.LOGGER.warn("Tried inserting a new item, that is not supported yet");
                return null;
            }

            StoredStack matchingStoredStack = matchingEntry.getValue();
            Identifier matchingIdentifier = matchingEntry.getKey();
            StoredStack.InTransfer transfer = matchingStoredStack.insert(itemStack);
            if (transfer == null) {
                return null;
            }

            reRemember(matchingIdentifier);
            return transfer;
        }

        public @Nullable StoredStack.OutTransfer takeLargestStackIfExists(Identifier recipe) {
            var storedStack = this.items.get(recipe);
            if (storedStack == null) {
                PolyWorks.LOGGER.warn("Player tried to take item that is not contained");
                return null;
            }
            return storedStack.takeLargestStack();
        }

        private Collection<RecipeEntry<?>> getSpoofRecipeList() {
            // TODO? Organize categories
            return this.items.entrySet().stream().map(entry -> {
                var id = entry.getKey();
                var itemStack = entry.getValue().stackCopy();
                DefaultedList<Ingredient> inputIngredients = DefaultedList.of();
                inputIngredients.add(Ingredient.ofItems(Registered.GUI_ITEMS.XTERM_EMPTY.polymerModelData.item())); // Add poly base item instead of fancy nbt
                ShapelessRecipe fakeCraftRecipe = new ShapelessRecipe("", CraftingRecipeCategory.BUILDING, itemStack, inputIngredients);
                return new RecipeEntry<ShapelessRecipe>(id, fakeCraftRecipe);
            }).collect(Collectors.toList());
        }

        @Override
        public void enter() {
            Collection<RecipeEntry<?>> recipeEntries = this.getSpoofRecipeList();
            var declareRecipes = new SynchronizeRecipesS2CPacket(recipeEntries);

            Collection<Identifier> fakeKeys = recipeEntries.stream().map(RecipeEntry::id).collect(Collectors.toList());
            var unlockRecipes = new UnlockRecipesS2CPacket(UnlockRecipesS2CPacket.Action.INIT, fakeKeys, List.of(), this.recipeBookSettings);

            this.splayer.networkHandler.sendPacket(declareRecipes);
            this.splayer.networkHandler.sendPacket(unlockRecipes);
        }

        @Override
        public void exit() {
            super.exit();
            splayer.getRecipeBook().sendInitRecipesPacket(splayer);
        }
    }

    public static class XTermScreenHandler extends CraftingScreenHandler {
        protected static final class XTermDigits {
            public static final List<GuiItem> COLLECTION_XTERM_DIGIT_NXX_S = Arrays.asList(null, Registered.GUI_ITEMS.XTERM_DIGIT_1XXS, Registered.GUI_ITEMS.XTERM_DIGIT_2XXS, Registered.GUI_ITEMS.XTERM_DIGIT_3XXS, Registered.GUI_ITEMS.XTERM_DIGIT_4XXS, Registered.GUI_ITEMS.XTERM_DIGIT_5XXS, Registered.GUI_ITEMS.XTERM_DIGIT_6XXS, Registered.GUI_ITEMS.XTERM_DIGIT_7XXS, Registered.GUI_ITEMS.XTERM_DIGIT_8XXS, Registered.GUI_ITEMS.XTERM_DIGIT_9XXS);
            public static final List<GuiItem> COLLECTION_XTERM_DIGIT_N0X_S = Arrays.asList(null, Registered.GUI_ITEMS.XTERM_DIGIT_10XS, Registered.GUI_ITEMS.XTERM_DIGIT_20XS, Registered.GUI_ITEMS.XTERM_DIGIT_30XS, Registered.GUI_ITEMS.XTERM_DIGIT_40XS, Registered.GUI_ITEMS.XTERM_DIGIT_50XS, Registered.GUI_ITEMS.XTERM_DIGIT_60XS, Registered.GUI_ITEMS.XTERM_DIGIT_70XS, Registered.GUI_ITEMS.XTERM_DIGIT_80XS, Registered.GUI_ITEMS.XTERM_DIGIT_90XS);
            public static final List<GuiItem> COLLECTION_XTERM_DIGIT_N00_S = Arrays.asList(null, Registered.GUI_ITEMS.XTERM_DIGIT_100S, Registered.GUI_ITEMS.XTERM_DIGIT_200S, Registered.GUI_ITEMS.XTERM_DIGIT_300S, Registered.GUI_ITEMS.XTERM_DIGIT_400S, Registered.GUI_ITEMS.XTERM_DIGIT_500S, Registered.GUI_ITEMS.XTERM_DIGIT_600S, Registered.GUI_ITEMS.XTERM_DIGIT_700S, Registered.GUI_ITEMS.XTERM_DIGIT_800S, Registered.GUI_ITEMS.XTERM_DIGIT_900S);
            public static final List<GuiItem> COLLECTION_XTERM_DIGIT_NXX_K = Arrays.asList(null, Registered.GUI_ITEMS.XTERM_DIGIT_1XXK, Registered.GUI_ITEMS.XTERM_DIGIT_2XXK, Registered.GUI_ITEMS.XTERM_DIGIT_3XXK, Registered.GUI_ITEMS.XTERM_DIGIT_4XXK, Registered.GUI_ITEMS.XTERM_DIGIT_5XXK, Registered.GUI_ITEMS.XTERM_DIGIT_6XXK, Registered.GUI_ITEMS.XTERM_DIGIT_7XXK, Registered.GUI_ITEMS.XTERM_DIGIT_8XXK, Registered.GUI_ITEMS.XTERM_DIGIT_9XXK);
            public static final List<GuiItem> COLLECTION_XTERM_DIGIT_N0X_K = Arrays.asList(null, Registered.GUI_ITEMS.XTERM_DIGIT_10XK, Registered.GUI_ITEMS.XTERM_DIGIT_20XK, Registered.GUI_ITEMS.XTERM_DIGIT_30XK, Registered.GUI_ITEMS.XTERM_DIGIT_40XK, Registered.GUI_ITEMS.XTERM_DIGIT_50XK, Registered.GUI_ITEMS.XTERM_DIGIT_60XK, Registered.GUI_ITEMS.XTERM_DIGIT_70XK, Registered.GUI_ITEMS.XTERM_DIGIT_80XK, Registered.GUI_ITEMS.XTERM_DIGIT_90XK);
            public static final List<GuiItem> COLLECTION_XTERM_DIGIT_N00_K = Arrays.asList(null, Registered.GUI_ITEMS.XTERM_DIGIT_100K, Registered.GUI_ITEMS.XTERM_DIGIT_200K, Registered.GUI_ITEMS.XTERM_DIGIT_300K, Registered.GUI_ITEMS.XTERM_DIGIT_400K, Registered.GUI_ITEMS.XTERM_DIGIT_500K, Registered.GUI_ITEMS.XTERM_DIGIT_600K, Registered.GUI_ITEMS.XTERM_DIGIT_700K, Registered.GUI_ITEMS.XTERM_DIGIT_800K, Registered.GUI_ITEMS.XTERM_DIGIT_900K);
            public static final List<GuiItem> COLLECTION_XTERM_DIGIT_NXX_M = Arrays.asList(null, Registered.GUI_ITEMS.XTERM_DIGIT_1XXM, Registered.GUI_ITEMS.XTERM_DIGIT_2XXM, Registered.GUI_ITEMS.XTERM_DIGIT_3XXM, Registered.GUI_ITEMS.XTERM_DIGIT_4XXM, Registered.GUI_ITEMS.XTERM_DIGIT_5XXM, Registered.GUI_ITEMS.XTERM_DIGIT_6XXM, Registered.GUI_ITEMS.XTERM_DIGIT_7XXM, Registered.GUI_ITEMS.XTERM_DIGIT_8XXM, Registered.GUI_ITEMS.XTERM_DIGIT_9XXM);
            public static final List<GuiItem> COLLECTION_XTERM_DIGIT_N0X_M = Arrays.asList(null, Registered.GUI_ITEMS.XTERM_DIGIT_10XM, Registered.GUI_ITEMS.XTERM_DIGIT_20XM, Registered.GUI_ITEMS.XTERM_DIGIT_30XM, Registered.GUI_ITEMS.XTERM_DIGIT_40XM, Registered.GUI_ITEMS.XTERM_DIGIT_50XM, Registered.GUI_ITEMS.XTERM_DIGIT_60XM, Registered.GUI_ITEMS.XTERM_DIGIT_70XM, Registered.GUI_ITEMS.XTERM_DIGIT_80XM, Registered.GUI_ITEMS.XTERM_DIGIT_90XM);
            public static final List<GuiItem> COLLECTION_XTERM_DIGIT_N00_M = Arrays.asList(null, Registered.GUI_ITEMS.XTERM_DIGIT_100M, Registered.GUI_ITEMS.XTERM_DIGIT_200M, Registered.GUI_ITEMS.XTERM_DIGIT_300M, Registered.GUI_ITEMS.XTERM_DIGIT_400M, Registered.GUI_ITEMS.XTERM_DIGIT_500M, Registered.GUI_ITEMS.XTERM_DIGIT_600M, Registered.GUI_ITEMS.XTERM_DIGIT_700M, Registered.GUI_ITEMS.XTERM_DIGIT_800M, Registered.GUI_ITEMS.XTERM_DIGIT_900M);
        }

        public static final int RESULT_ID = 0;
        private static final int INPUT_START = 1;
        private static final int INPUT_END = 10;

        private static final int INPUT_DRAWOVERRIDE_L = 1;
        private static final int INPUT_DRAWOVERRIDE_R = 2;
        private static final int INPUT_DRAWOVERRIDE_COUNT = 3;

        private static final int INVENTORY_START = 10;
        private static final int INVENTORY_END = 37;
        private static final int HOTBAR_START = 37;
        private static final int HOTBAR_END = 46;

        private final XTermSpoofer spoofer;

        public XTermScreenHandler(int syncId, PlayerInventory playerInventory, XTermSpoofer spoofer) {
            super(syncId, playerInventory);
            this.spoofer = spoofer;

            this.setStackInSlot(INPUT_DRAWOVERRIDE_L, 0, Registered.GUI_ITEMS.XTERM_L.getStack());
            this.setStackInSlot(INPUT_DRAWOVERRIDE_R, 0, Registered.GUI_ITEMS.XTERM_R.getStack());
            this.setStackInSlot(INPUT_DRAWOVERRIDE_COUNT, 0, Registered.GUI_ITEMS.XTERM_EMPTY.getStack());
            this.setStackInSlot(INPUT_START+3, 0, Registered.GUI_ITEMS.XTERM_EMPTY.getStack());
            this.setStackInSlot(INPUT_START+5, 0, Registered.GUI_ITEMS.XTERM_EMPTY.getStack());
            this.setStackInSlot(INPUT_START+6, 0, Registered.GUI_ITEMS.XTERM_EMPTY.getStack());
            this.setStackInSlot(INPUT_START+7, 0, Registered.GUI_ITEMS.XTERM_EMPTY.getStack());
            this.setStackInSlot(INPUT_START+8, 0, Registered.GUI_ITEMS.XTERM_EMPTY.getStack());
            this.sendContentUpdates();
        }

        public void onRecipeBookClick(Identifier recipe, boolean craftAll) {
            PolyWorks.LOGGER.info("onRecipeBookClick {recipe="+recipe+", craftAll="+craftAll+"}");

            var stackTransfer = spoofer.takeLargestStackIfExists(recipe);
            if (stackTransfer == null) return;

            ItemStack itemStackToTransfer = stackTransfer.itemStackToExtract;
            ItemStack visualStackCopy = itemStackToTransfer.copyWithCount(1);
            boolean tookItem = false;
            if (craftAll) {
                if (this.insertItem(itemStackToTransfer, INVENTORY_START, HOTBAR_END, true)) {
                    tookItem = true;
                }
            } else {
                // Unsure how to handle non shiftclicks
                /*var cursorStack = getCursorStack();
                if (cursorStack.isEmpty()) {
                    setCursorStack(itemStackToTransfer);
                    tookItem = true;
                }*/
            }
            int remainingItems = stackTransfer.resolveWith(tookItem);
            PolyWorks.LOGGER.info("Transfered items out of terminal. "+remainingItems+" now remain.");

            if (remainingItems > 0) {
                this.setVisualOutput(visualStackCopy, remainingItems);
            } else {
                this.spoofer.forget(recipe); // should be handled elsewhere?
                this.setVisualOutput(ItemStack.EMPTY, 0);
            }

            this.sendContentUpdates();
        }

        /**
         *
         * @param mutItemStack items to take from
         * @return wether any items where inserted
         */
        private boolean insertAsManyAsPossible(ItemStack mutItemStack) {
            var result = spoofer.insertAsManyAsPossible(mutItemStack);
            if (result == null) {
                return false;
            }
            this.setVisualOutput(mutItemStack.copy(), result.resolveWith(true, mutItemStack));
            return true;
        }

        private void setVisualOutput(ItemStack mutItemStack, int count) {

            if (count == 0) {
                this.setStackInSlot(RESULT_ID, this.nextRevision(), ItemStack.EMPTY);
                this.setStackInSlot(INPUT_DRAWOVERRIDE_COUNT, this.nextRevision(), Registered.GUI_ITEMS.XTERM_EMPTY.getDefaultStack());
                return;
            }

            int numberToDraw;
            int magnitude;
            if (count > 1_000_000_000) {
                throw new RuntimeException("Number too large");
            } else if (count >= 1_000_000) {
                magnitude = 2;
                numberToDraw = count / 1_000_000;
            } else if (count >= 1_000) {
                magnitude = 1;
                numberToDraw = count / 1_000;
            } else {
                magnitude = 0;
                numberToDraw = count;
            }

            if (numberToDraw == 1) {
                mutItemStack.setCount(1);
                this.setStackInSlot(RESULT_ID, this.nextRevision(), mutItemStack);
                switch (magnitude) {
                    case 0 -> this.setStackInSlot(INPUT_DRAWOVERRIDE_COUNT, this.nextRevision(), Registered.GUI_ITEMS.XTERM_EMPTY.getDefaultStack());
                    case 1 -> this.setStackInSlot(INPUT_DRAWOVERRIDE_COUNT, this.nextRevision(), Registered.GUI_ITEMS.XTERM_DIGIT_SPECIAL_1K.getDefaultStack());
                    case 2 -> this.setStackInSlot(INPUT_DRAWOVERRIDE_COUNT, this.nextRevision(), Registered.GUI_ITEMS.XTERM_DIGIT_SPECIAL_1M.getDefaultStack());
                }
                return;
            }

            if (numberToDraw < 100) {
                mutItemStack.setCount(numberToDraw);
                this.setStackInSlot(RESULT_ID, this.nextRevision(), mutItemStack);
                switch (magnitude) {
                    case 0 -> this.setStackInSlot(INPUT_DRAWOVERRIDE_COUNT, this.nextRevision(), Registered.GUI_ITEMS.XTERM_EMPTY.getDefaultStack());
                    case 1 -> this.setStackInSlot(INPUT_DRAWOVERRIDE_COUNT, this.nextRevision(), Registered.GUI_ITEMS.XTERM_DIGIT_SPECIAL_K.getDefaultStack());
                    case 2 -> this.setStackInSlot(INPUT_DRAWOVERRIDE_COUNT, this.nextRevision(), Registered.GUI_ITEMS.XTERM_DIGIT_SPECIAL_M.getDefaultStack());
                }
                return;
            }

            int doubleDigits = numberToDraw % 100;
            int hundredDigit = numberToDraw / 100;
            mutItemStack.setCount(max(1, doubleDigits));

            if (doubleDigits == 0) {
                this.setStackInSlot(RESULT_ID, this.nextRevision(), mutItemStack);
                switch (magnitude) {
                    case 0 -> this.setStackInSlot(INPUT_DRAWOVERRIDE_COUNT, this.nextRevision(), XTermDigits.COLLECTION_XTERM_DIGIT_N00_S.get(hundredDigit).getDefaultStack());
                    case 1 -> this.setStackInSlot(INPUT_DRAWOVERRIDE_COUNT, this.nextRevision(), XTermDigits.COLLECTION_XTERM_DIGIT_N00_K.get(hundredDigit).getDefaultStack());
                    case 2 -> this.setStackInSlot(INPUT_DRAWOVERRIDE_COUNT, this.nextRevision(), XTermDigits.COLLECTION_XTERM_DIGIT_N00_M.get(hundredDigit).getDefaultStack());
                }
                return;
            }

            if (doubleDigits < 10) {
                this.setStackInSlot(RESULT_ID, this.nextRevision(), mutItemStack);
                switch (magnitude) {
                    case 0 -> this.setStackInSlot(INPUT_DRAWOVERRIDE_COUNT, this.nextRevision(), XTermDigits.COLLECTION_XTERM_DIGIT_N0X_S.get(hundredDigit).getDefaultStack());
                    case 1 -> this.setStackInSlot(INPUT_DRAWOVERRIDE_COUNT, this.nextRevision(), XTermDigits.COLLECTION_XTERM_DIGIT_N0X_K.get(hundredDigit).getDefaultStack());
                    case 2 -> this.setStackInSlot(INPUT_DRAWOVERRIDE_COUNT, this.nextRevision(), XTermDigits.COLLECTION_XTERM_DIGIT_N0X_M.get(hundredDigit).getDefaultStack());
                }
                return;
            }

            this.setStackInSlot(RESULT_ID, this.nextRevision(), mutItemStack);
            switch (magnitude) {
                case 0 -> this.setStackInSlot(INPUT_DRAWOVERRIDE_COUNT, this.nextRevision(), XTermDigits.COLLECTION_XTERM_DIGIT_NXX_S.get(hundredDigit).getDefaultStack());
                case 1 -> this.setStackInSlot(INPUT_DRAWOVERRIDE_COUNT, this.nextRevision(), XTermDigits.COLLECTION_XTERM_DIGIT_NXX_K.get(hundredDigit).getDefaultStack());
                case 2 -> this.setStackInSlot(INPUT_DRAWOVERRIDE_COUNT, this.nextRevision(), XTermDigits.COLLECTION_XTERM_DIGIT_NXX_M.get(hundredDigit).getDefaultStack());
            }
        }

        @Override
        public ItemStack quickMove(PlayerEntity player, int slotId) {
            if (INPUT_START <= slotId && slotId < INPUT_END) return ItemStack.EMPTY;
            if (slotId == RESULT_ID) return ItemStack.EMPTY;

            Slot slot = this.slots.get(slotId);
            if (!slot.hasStack()) return ItemStack.EMPTY;

            PolyWorks.LOGGER.info("Relevant Quickmove with slot="+slotId);
            ItemStack slotStack = slot.getStack();
            var insertOk = this.insertAsManyAsPossible(slotStack);
            if (!insertOk) return ItemStack.EMPTY;

            PolyWorks.LOGGER.info("Quickmove success!"+slotId);
            slot.setStack(ItemStack.EMPTY);
            return ItemStack.EMPTY;
        }

        @Override
        public boolean canInsertIntoSlot(ItemStack stack, Slot slot) {
            if (INPUT_START <= slot.id && slot.id <= INPUT_END) { return false; }
            if (slot.id == RESULT_ID) { return false; }
            return super.canInsertIntoSlot(stack, slot);
        }

        @Override
        public void onClosed(PlayerEntity player) {
            super.onClosed(player);
            this.spoofer.exit();
        }

        @Override
        public void fillInputSlots(boolean craftAll, RecipeEntry<?> recipe, ServerPlayerEntity splayer) {
            assert false; // This should never be called by vanilla since the selected recipe will always be invalid.
        }

        public static void open(ServerPlayerEntity splayer) {
            var xtermContents = new XTermSpoofer(splayer);
            xtermContents.enter();
            var screenHandler = new SimpleNamedScreenHandlerFactory(
                    (syncId, playerInventory, player) -> new XTermScreenHandler(syncId, playerInventory, xtermContents),
                    Text.literal("XTerm")
            );
            splayer.openHandledScreen(screenHandler);
        }
    }

    public static class Util {
        public static int commandOpen(CommandContext<ServerCommandSource> context) {
            context.getSource().sendFeedback(() -> Text.literal("hello world"), false);
            ServerPlayerEntity splayer = context.getSource().getPlayer();
            XTermScreenHandler.open(splayer);
            return 1;
        }
    }
}
