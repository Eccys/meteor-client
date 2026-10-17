/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient;

import meteordevelopment.meteorclient.gui.DefaultSettingsWidgetFactory;
import meteordevelopment.meteorclient.gui.themes.meteor.MeteorGuiTheme;
import meteordevelopment.meteorclient.gui.utils.Cell;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.combat.AttributeSwap;
import meteordevelopment.meteorclient.systems.modules.combat.AutoLog;
import meteordevelopment.meteorclient.systems.modules.movement.GUIMove;
import meteordevelopment.meteorclient.utils.PreInit;
import meteordevelopment.meteorclient.utils.ReflectInit;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Pool;
import meteordevelopment.meteorclient.utils.misc.UnorderedArrayList;
import meteordevelopment.meteorclient.utils.misc.ValueComparableMap;
import meteordevelopment.meteorclient.utils.misc.Version;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MeteorCoreTest {

    @Nested
    @DisplayName("UnorderedArrayList")
    class UnorderedArrayListTests {

        private Object[] getBackingArray(UnorderedArrayList<?> list) throws Exception {
            Field itemsField = UnorderedArrayList.class.getDeclaredField("items");
            itemsField.setAccessible(true);
            return (Object[]) itemsField.get(list);
        }

        @Test
        void addAndRemoveByObjectNullsVacatedTailSlot() throws Exception {
            UnorderedArrayList<String> list = new UnorderedArrayList<>();
            list.add("alpha");
            list.add("beta");
            list.add("gamma");

            assertEquals(3, list.size());
            assertTrue(list.remove("alpha"));
            assertEquals(2, list.size());
            assertEquals("gamma", list.get(0));
            assertEquals("beta", list.get(1));

            Object[] backing = getBackingArray(list);
            assertNull(backing[2], "Vacated tail slot after remove(Object) must be nulled out");
            assertFalse(list.remove("nonexistent"));
        }

        @Test
        void removeByIndexSwapsLastElementAndNullsTailSlot() throws Exception {
            UnorderedArrayList<Integer> list = new UnorderedArrayList<>();
            list.add(10);
            list.add(20);
            list.add(30);

            Integer removed = list.remove(0);
            assertEquals(10, removed);
            assertEquals(2, list.size());
            assertEquals(30, list.get(0));
            assertEquals(20, list.get(1));

            Object[] backing = getBackingArray(list);
            assertNull(backing[2], "Vacated tail slot after remove(int) must be nulled out");
        }

        @Test
        void boundsChecksRejectOutOfRangeIndicesWithoutCorruptingSize() {
            UnorderedArrayList<String> list = new UnorderedArrayList<>();
            list.add("only");
            assertThrows(IndexOutOfBoundsException.class, () -> list.get(-1));
            assertThrows(IndexOutOfBoundsException.class, () -> list.get(1));
            assertThrows(IndexOutOfBoundsException.class, () -> list.set(1, "bad"));
            assertThrows(IndexOutOfBoundsException.class, () -> list.remove(5));
            assertEquals(1, list.size());

            list.clear();
            assertThrows(IndexOutOfBoundsException.class, () -> list.remove(0));
            assertEquals(0, list.size(), "remove(0) on cleared list must not decrement size to -1");
        }

        @Test
        void removeIfClearsTrailingReferencesAndUpdatesModCount() throws Exception {
            UnorderedArrayList<Integer> list = new UnorderedArrayList<>();
            for (int i = 1; i <= 6; i++) list.add(i);

            Iterator<Integer> failFastIterator = list.iterator();
            assertTrue(list.removeIf(n -> n % 2 == 0));
            assertEquals(List.of(1, 3, 5), list);

            Object[] backing = getBackingArray(list);
            assertNull(backing[3], "Trailing slot 3 must be nulled after removeIf");
            assertNull(backing[4], "Trailing slot 4 must be nulled after removeIf");
            assertNull(backing[5], "Trailing slot 5 must be nulled after removeIf");

            assertThrows(ConcurrentModificationException.class, failFastIterator::next);

            // When predicate matches nothing, removeIf returns false and iterator stays valid
            Iterator<Integer> unchangedIterator = list.iterator();
            assertFalse(list.removeIf(n -> n > 100));
            assertEquals(1, unchangedIterator.next());
        }

        @Test
        void dynamicCapacityGrowthAndClear() throws Exception {
            UnorderedArrayList<Integer> list = new UnorderedArrayList<>();
            for (int i = 0; i < 25; i++) list.add(i);
            assertEquals(25, list.size());

            list.clear();
            assertEquals(0, list.size());
            assertTrue(list.isEmpty());
            Object[] backing = getBackingArray(list);
            for (Object item : backing) {
                assertNull(item);
            }
        }
    }

    @Nested
    @DisplayName("Pool")
    class PoolTests {

        @Test
        void poolReusesFreedInstancesAndSupportsFreeAll() {
            AtomicInteger created = new AtomicInteger();
            Pool<StringBuilder> pool = new Pool<>(() -> {
                created.incrementAndGet();
                return new StringBuilder();
            });

            StringBuilder first = pool.get();
            StringBuilder second = pool.get();
            assertEquals(2, created.get());

            pool.free(first);
            assertSame(first, pool.get());
            assertEquals(2, created.get());

            // Test freeAll with non-Collection Iterable as well as Collection
            Iterable<StringBuilder> nonCollectionIterable = () -> List.of(first, second).iterator();
            pool.freeAll(nonCollectionIterable);
            assertSame(first, pool.get());
            assertSame(second, pool.get());
            assertEquals(2, created.get());
        }
    }

    @Nested
    @DisplayName("Version")
    class VersionTests {

        @Test
        void versionParsingAndComparison() {
            Version vZero = new Version("0.0.0");
            assertTrue(vZero.isZero());

            Version v1 = new Version("1.21.4");
            Version v2 = new Version("1.21.5");
            Version v3 = new Version("2.0.0");

            assertFalse(v1.isZero());
            assertTrue(v2.isHigherThan(v1));
            assertFalse(v1.isHigherThan(v2));
            assertFalse(v1.isHigherThan(new Version("1.21.4")));
            assertTrue(v3.isHigherThan(v2));
            assertEquals("1.21.4", v1.toString());
        }

        @Test
        void invalidVersionStringsThrowIllegalArgumentException() {
            assertThrows(IllegalArgumentException.class, () -> new Version("1.21"));
            assertThrows(IllegalArgumentException.class, () -> new Version("1.21.4.1"));
            assertThrows(IllegalArgumentException.class, () -> new Version("1.21.4."));
            assertThrows(IllegalArgumentException.class, () -> new Version("1.a.0"));
        }
    }

    @Nested
    @DisplayName("ValueComparableMap")
    class ValueComparableMapTests {

        @Test
        void ordersByValueAndHandlesMissingKeysRemoveAndClearCleanly() {
            ValueComparableMap<String, Integer> map = new ValueComparableMap<>(Comparator.naturalOrder());
            assertNull(map.put("beta", 20));
            assertNull(map.put("alpha", 10));
            assertNull(map.put("gamma", 20));

            assertEquals(List.of("alpha", "beta", "gamma"), new ArrayList<>(map.keySet()));
            assertTrue(map.containsKey("beta"));
            assertEquals(20, map.get("beta"));
            assertNull(map.get("missing"), "get() on missing key must return null instead of throwing NPE");
            assertEquals(99, map.getOrDefault("missing", 99));
            assertNull(map.remove("missing"), "remove() on missing key must return null instead of throwing NPE");

            // Updating "beta" to a smaller value than "alpha" must return old value 20 and move it to the front
            assertEquals(20, map.put("beta", 5));
            assertEquals(5, map.get("beta"));
            assertEquals(List.of("beta", "alpha", "gamma"), new ArrayList<>(map.keySet()));

            // Removing an existing key must remove it from both TreeMap and valueMap
            assertEquals(5, map.remove("beta"));
            assertFalse(map.containsKey("beta"));
            assertNull(map.get("beta"));
            assertEquals(42, map.getOrDefault("beta", 42));
            assertEquals(List.of("alpha", "gamma"), new ArrayList<>(map.keySet()));

            // Clearing the map must also clear valueMap
            map.clear();
            assertTrue(map.isEmpty());
            assertFalse(map.containsKey("alpha"));
        }
    }

    @Nested
    @DisplayName("Color")
    class ColorTests {

        @Test
        void packingUnpackingClampingAndParse() {
            Color c = new Color(300, -10, 128, 255);
            assertEquals(255, c.r);
            assertEquals(0, c.g);
            assertEquals(128, c.b);
            assertEquals(255, c.a);

            int packed = c.getPacked();
            Color unpacked = new Color(packed);
            assertEquals(c, unpacked);
            assertEquals(c.hashCode(), unpacked.hashCode());

            Color parsed = new Color();
            assertTrue(parsed.parse("12,34,56,78"));
            assertEquals(new Color(12, 34, 56, 78), parsed);
            assertTrue(parsed.parse("300,-10,50,500"));
            assertEquals(new Color(255, 0, 50, 255), parsed);
            assertFalse(parsed.parse(null));
            assertFalse(parsed.parse("invalid"));
            assertFalse(parsed.parse("10,20"));

            CompoundTag tag = parsed.toTag();
            Color fromTag = new Color().fromTag(tag);
            assertEquals(parsed, fromTag);
            assertEquals(parsed, parsed.copy());
        }

        @Test
        void hsvConversionProducesValidRgb() {
            Color red = Color.fromHsv(0.0, 1.0, 1.0);
            assertEquals(new Color(255, 0, 0, 255), red);

            Color gray = Color.fromHsv(180.0, 0.0, 0.5);
            assertEquals(127, gray.r);
            assertEquals(127, gray.g);
            assertEquals(127, gray.b);
        }
    }

    @Nested
    @DisplayName("SlotUtils and InvUtils Null-Player Safety")
    class InventorySafetyTests {

        @Test
        void slotRangePredicatesAndNullPlayerIndexToId() {
            assertTrue(SlotUtils.isHotbar(0));
            assertTrue(SlotUtils.isHotbar(8));
            assertFalse(SlotUtils.isHotbar(-1));
            assertFalse(SlotUtils.isHotbar(9));

            assertTrue(SlotUtils.isMain(9));
            assertTrue(SlotUtils.isMain(35));
            assertFalse(SlotUtils.isMain(8));
            assertFalse(SlotUtils.isMain(36));

            assertTrue(SlotUtils.isArmor(36));
            assertTrue(SlotUtils.isArmor(39));
            assertFalse(SlotUtils.isArmor(35));
            assertFalse(SlotUtils.isArmor(40));

            assertEquals(-1, SlotUtils.indexToId(0));
        }

        @Test
        void invUtilsReturnsNotFoundAndFalseWhenPlayerIsNull() {
            assertFalse(InvUtils.testInMainHand(stack -> true));
            assertFalse(InvUtils.testInOffHand(stack -> true));
            assertFalse(InvUtils.testInHands(stack -> true));
            assertFalse(InvUtils.testInHotbar(stack -> true));

            FindItemResult findAll = InvUtils.find(stack -> true);
            assertFalse(findAll.found(), "InvUtils.find must not report found() == true when player is null");
            assertEquals(-1, findAll.slot());
            assertEquals(0, findAll.count());
            assertFalse(findAll.isHotbar());
            assertFalse(findAll.isMain());
            assertFalse(findAll.isArmor());
            assertFalse(findAll.isOffhand());

            FindItemResult findHotbar = InvUtils.findInHotbar(stack -> true);
            assertFalse(findHotbar.found());
            assertEquals(-1, findHotbar.slot());

            FindItemResult findRange = InvUtils.find(stack -> true, 0, 8);
            assertFalse(findRange.found());
            assertEquals(-1, findRange.slot());

            FindItemResult findFastest = InvUtils.findFastestTool(null);
            assertFalse(findFastest.found());
            assertEquals(-1, findFastest.slot());

            assertFalse(InvUtils.swap(0, false));

            // Action chains and dropHand must safely no-op and reset state when mc.player is null
            assertDoesNotThrow(() -> InvUtils.move().from(0).to(1));
            assertDoesNotThrow(() -> InvUtils.click().slot(0));
            assertDoesNotThrow(() -> InvUtils.quickSwap().fromId(0).toId(1));
            assertDoesNotThrow(() -> InvUtils.shiftClick().slotMain(0));
            assertDoesNotThrow(() -> InvUtils.drop().slotHotbar(0));
            assertDoesNotThrow(() -> InvUtils.dropOne().slotOffhand());
            assertDoesNotThrow(InvUtils::dropHand);
        }
    }

    @Nested
    @DisplayName("Utils Pure Helpers")
    class UtilsHelperTests {

        @Test
        void stringFormattingAndFuzzySearchHelpers() {
            assertEquals("Kill Aura", Utils.nameToTitle("kill-aura"));
            assertEquals("kill-aura", Utils.titleToName("Kill Aura"));

            assertEquals(0, Utils.levenshteinDistance("meteor", "meteor", 1, 1, 1));
            assertEquals(2, Utils.searchInWords("Kill Aura", "kill aura"));
            assertEquals(0, Utils.searchInWords("Kill Aura", "scaffold"));
            assertEquals(5.0, Utils.distance(0, 0, 0, 3, 4, 0), 1e-6);
            assertEquals(25.0, Utils.squaredDistance(0, 0, 0, 3, 4, 0), 1e-6);
            assertTrue(Utils.searchTextDefault("Kill Aura", "kill", false));
            assertFalse(Utils.searchTextDefault("Kill Aura", "scaffold", false));
            assertEquals(25565, Utils.parsePort("127.0.0.1:25565"));
            assertEquals(8, Utils.parsePort("localhost:8"));
            assertEquals(-1, Utils.parsePort("localhost"));
            assertEquals("127.0.0.1", Utils.parseAddress("127.0.0.1:25565"));
        }
    }

    @Nested
    @DisplayName("ReflectInit Dependency Ordering")
    class ReflectInitTests {
        static final List<String> EXECUTION_ORDER = new ArrayList<>();

        public static class FirstTask {
            @PreInit(dependencies = SecondTask.class)
            public static void initFirst() {
                EXECUTION_ORDER.add("first");
            }
        }

        public static class SecondTask {
            @PreInit
            public static void initSecond() {
                EXECUTION_ORDER.add("second");
            }
        }

        @Test
        @SuppressWarnings("unchecked")
        void initContinuesPastEmptyReflectionsAndRespectsDependencies() throws Exception {
            Field reflectionsField = ReflectInit.class.getDeclaredField("reflections");
            reflectionsField.setAccessible(true);
            List<Reflections> reflections = (List<Reflections>) reflectionsField.get(null);
            List<Reflections> backup = new ArrayList<>(reflections);

            try {
                reflections.clear();
                EXECUTION_ORDER.clear();

                // First Reflections scans a filtered set with no @PreInit methods; second scans only MeteorCoreTest inner classes
                ClassLoader cl = ReflectInit.class.getClassLoader();
                reflections.add(new Reflections(new org.reflections.util.ConfigurationBuilder()
                    .setUrls(org.reflections.util.ClasspathHelper.forClass(Version.class, cl))
                    .addClassLoaders(cl)
                    .filterInputsBy(s -> s != null && s.contains("Version"))
                    .setScanners(Scanners.MethodsAnnotated)));
                reflections.add(new Reflections(new org.reflections.util.ConfigurationBuilder()
                    .setUrls(org.reflections.util.ClasspathHelper.forClass(MeteorCoreTest.class, cl))
                    .addClassLoaders(cl)
                    .filterInputsBy(s -> s != null && s.contains("MeteorCoreTest"))
                    .setScanners(Scanners.MethodsAnnotated)));

                ReflectInit.init(PreInit.class);
                int secondIdx = EXECUTION_ORDER.indexOf("second");
                int firstIdx = EXECUTION_ORDER.indexOf("first");
                assertTrue(secondIdx >= 0 && firstIdx > secondIdx,
                    "Dependency SecondTask must execute before FirstTask even after an empty Reflections entry: " + EXECUTION_ORDER);
            } finally {
                reflections.clear();
                reflections.addAll(backup);
            }
        }
    }

    @Nested
    @DisplayName("GUI minWidth Scaling")
    class GuiLayoutScalingTests {

        private static final TextRenderer STUB_TEXT_RENDERER = new TextRenderer() {
            @Override
            public void setAlpha(double a) {}

            @Override
            public void begin(double scale, boolean scaleOnly, boolean big) {}

            @Override
            public double getWidth(String text, int length, boolean shadow) {
                return Math.min(text.length(), length) * 8.0;
            }

            @Override
            public double getHeight(boolean shadow) {
                return 10.0;
            }

            @Override
            public double render(String text, double x, double y, Color color, boolean shadow) {
                return x + getWidth(text, text.length(), shadow);
            }

            @Override
            public boolean isBuilding() {
                return false;
            }

            @Override
            public void end() {}
        };

        private static class HeadlessMeteorGuiTheme extends MeteorGuiTheme {
            @Override
            public TextRenderer textRenderer() {
                return STUB_TEXT_RENDERER;
            }
        }

        private WTextBox findTextBox(WWidget root) {
            if (root instanceof WTextBox tb) return tb;
            if (root instanceof WContainer container) {
                for (Cell<?> cell : container.cells) {
                    WTextBox found = findTextBox(cell.widget());
                    if (found != null) return found;
                }
            }
            return null;
        }

        @Test
        void defaultSettingsWidgetFactoryDoesNotDoubleScaleMinWidthAcrossGuiScales() {
            HeadlessMeteorGuiTheme theme = new HeadlessMeteorGuiTheme();
            DefaultSettingsWidgetFactory factory = new DefaultSettingsWidgetFactory(theme);

            Settings settings = new Settings();
            SettingGroup sg = settings.getDefaultGroup();
            sg.add(new BoolSetting.Builder().name("enabled").defaultValue(true).build());
            sg.add(new StringSetting.Builder().name("wide-input").defaultValue("hello").wide().build());

            double expectedWidePixels = Math.round(Utils.getWindowWidth() - Utils.getWindowWidth() / 4.0);

            for (double guiScale : new double[]{0.75, 1.0, 2.0}) {
                theme.scale.set(guiScale);
                WWidget list = factory.create(theme, settings, "");

                double firstPassWidth = list.width;
                assertEquals(firstPassWidth / theme.scale(1), list.minWidth, 1e-6,
                    "list.minWidth must be stored in unscaled units at scale " + guiScale);

                // Second layout pass (as performed by WidgetScreen) must not compound-scale list.width
                list.calculateSize();
                assertEquals(firstPassWidth, list.width, 1e-6,
                    "Second calculateSize() pass must not inflate list.width at scale " + guiScale);

                WTextBox wideTextBox = findTextBox(list);
                assertNotNull(wideTextBox, "Wide StringSetting WTextBox should be present");
                assertEquals(expectedWidePixels, wideTextBox.width, 1e-6,
                    "Wide StringSetting WTextBox width must equal 75% of window width at scale " + guiScale);
            }
        }

        @Test
        void buttonNormalizedMinWidthPreservesPixelWidthOnRelayout() {
            HeadlessMeteorGuiTheme theme = new HeadlessMeteorGuiTheme();
            theme.scale.set(2.0);

            WButton login = theme.button("Login");
            login.calculateSize();
            double initialLoginWidth = login.width;

            // WAccount login click pattern: preserve button width while text is replaced with "..."
            login.minWidth = login.width / theme.scale(1);
            login.set("...");
            login.calculateSize();

            assertEquals(initialLoginWidth, login.width, 1e-6,
                "Normalized login.minWidth must preserve exact pixel width instead of doubling at scale 2.0");
        }
    }

    @Nested
    @DisplayName("GUIMove Screen Filtering and Key Suppression")
    class GUIMoveTests {

        @SuppressWarnings("unchecked")
        private <T> T allocateWithoutConstructor(Class<T> clazz) throws Exception {
            Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
            return (T) unsafe.allocateInstance(clazz);
        }

        @Test
        @SuppressWarnings("unchecked")
        void skipHandlesAllScreenTypesAcrossGuiInventoryAndBothModes() throws Exception {
            GUIMove guiMove = new GUIMove();
            Setting<GUIMove.Screens> screensSetting = (Setting<GUIMove.Screens>) guiMove.settings.get("guis");
            assertNotNull(screensSetting);

            Screen pauseScreen = allocateWithoutConstructor(net.minecraft.client.gui.screens.PauseScreen.class);
            Screen hangingSignScreen = allocateWithoutConstructor(net.minecraft.client.gui.screens.inventory.HangingSignEditScreen.class);
            Screen anvilScreen = allocateWithoutConstructor(net.minecraft.client.gui.screens.inventory.AnvilScreen.class);
            Screen chatScreen = allocateWithoutConstructor(net.minecraft.client.gui.screens.ChatScreen.class);
            Screen inventoryScreen = allocateWithoutConstructor(net.minecraft.client.gui.screens.inventory.InventoryScreen.class);
            Screen widgetScreen = allocateWithoutConstructor(meteordevelopment.meteorclient.gui.screens.ModulesScreen.class);

            // Screens that must ALWAYS be skipped regardless of mode
            for (GUIMove.Screens mode : GUIMove.Screens.values()) {
                screensSetting.set(mode);
                assertTrue(guiMove.skip(null), "Null screen must be skipped in mode " + mode);
                assertTrue(guiMove.skip(pauseScreen), "PauseScreen must be skipped in mode " + mode);
                assertTrue(guiMove.skip(hangingSignScreen), "HangingSignEditScreen must be skipped in mode " + mode);
                assertTrue(guiMove.skip(anvilScreen), "AnvilScreen must be skipped in mode " + mode);
                assertTrue(guiMove.skip(chatScreen), "ChatScreen must be skipped in mode " + mode);
            }

            // Screens.GUI: only WidgetScreen allowed
            screensSetting.set(GUIMove.Screens.GUI);
            assertFalse(guiMove.skip(widgetScreen), "WidgetScreen must not be skipped in GUI mode");
            assertTrue(guiMove.skip(inventoryScreen), "InventoryScreen must be skipped in GUI mode");

            // Screens.Inventory: only AbstractContainerScreen (except excluded ones like Anvil) allowed
            screensSetting.set(GUIMove.Screens.Inventory);
            assertTrue(guiMove.skip(widgetScreen), "WidgetScreen must be skipped in Inventory mode");
            assertFalse(guiMove.skip(inventoryScreen), "InventoryScreen must not be skipped in Inventory mode");

            // Screens.Both: both WidgetScreen and AbstractContainerScreen allowed, non-container screens still skipped
            screensSetting.set(GUIMove.Screens.Both);
            assertFalse(guiMove.skip(widgetScreen), "WidgetScreen must not be skipped in Both mode");
            assertFalse(guiMove.skip(inventoryScreen), "InventoryScreen must not be skipped in Both mode");
        }

        @Test
        void disableArrowsDoesNotSuppressKeysWhenScreenIsSkipped() throws Exception {
            GUIMove guiMove = new GUIMove();
            Field activeField = Module.class.getDeclaredField("active");
            activeField.setAccessible(true);
            activeField.set(guiMove, true);

            // With mc == null (no valid active container/widget screen), skip() is true so arrow/space suppression must be false
            assertTrue(guiMove.skip());
            assertFalse(guiMove.disableArrows(), "disableArrows() must return false when current screen is skipped");
        }
    }

    @Nested
    @DisplayName("AttributeSwap and AutoLog Settings & Guards")
    class CombatModuleTests {

        @Test
        @SuppressWarnings("unchecked")
        void attributeSwapIncludesSpearInWeaponOptionsAndRespectsVisibility() throws Exception {
            AttributeSwap swap = new AttributeSwap();

            Setting<Boolean> onlyOnWeapon = (Setting<Boolean>) swap.settings.get("only-on-weapon");
            Setting<Boolean> spear = (Setting<Boolean>) swap.settings.get("spear");
            assertNotNull(onlyOnWeapon);
            assertNotNull(spear, "AttributeSwap Weapon Options must include a 'spear' setting");
            assertTrue(spear.get());

            onlyOnWeapon.set(false);
            assertFalse(spear.isVisible(), "spear weapon filter should be hidden when only-on-weapon is false");

            Method canSwapByWeapon = AttributeSwap.class.getDeclaredMethod("canSwapByWeapon");
            canSwapByWeapon.setAccessible(true);
            assertTrue((Boolean) canSwapByWeapon.invoke(swap), "canSwapByWeapon() must return true when only-on-weapon is disabled");

            onlyOnWeapon.set(true);
            assertTrue(spear.isVisible(), "spear weapon filter should be visible when only-on-weapon is true");
            assertFalse((Boolean) canSwapByWeapon.invoke(swap), "canSwapByWeapon() must return false when only-on-weapon is true and player is null");

            Setting<AttributeSwap.Mode> mode = (Setting<AttributeSwap.Mode>) swap.settings.get("mode");
            Setting<Boolean> spearSwapping = (Setting<Boolean>) swap.settings.get("spear-swapping");
            Setting<Boolean> spearHitbox = (Setting<Boolean>) swap.settings.get("hitbox");
            Setting<Boolean> excludeLunge = (Setting<Boolean>) swap.settings.get("exclude-lunge-from-hitbox");
            assertNotNull(mode);
            assertNotNull(spearSwapping);
            assertNotNull(spearHitbox);
            assertNotNull(excludeLunge);

            mode.set(AttributeSwap.Mode.Smart);
            spearSwapping.set(true);
            spearHitbox.set(true);
            assertTrue(excludeLunge.isVisible());
            spearHitbox.set(false);
            assertFalse(excludeLunge.isVisible());

            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();

            Method isValidSpear = AttributeSwap.class.getDeclaredMethod("isValidSpear", net.minecraft.world.item.ItemStack.class, boolean.class);
            isValidSpear.setAccessible(true);
            assertFalse((Boolean) isValidSpear.invoke(swap, net.minecraft.world.item.ItemStack.EMPTY, false));
            assertFalse((Boolean) isValidSpear.invoke(swap, net.minecraft.world.item.ItemStack.EMPTY, true));
        }

        @Test
        @SuppressWarnings("unchecked")
        void autoLogZeroHealthDisablesDamagePredictionAndSmartToggleVisibility() {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();

            AutoLog autoLog = new AutoLog();
            Setting<Integer> health = (Setting<Integer>) autoLog.settings.get("health");
            Setting<Boolean> predictDamage = (Setting<Boolean>) autoLog.settings.get("predict-incoming-damage");
            Setting<Boolean> smartToggle = (Setting<Boolean>) autoLog.settings.get("smart-toggle");

            assertNotNull(health);
            assertNotNull(predictDamage);
            assertNotNull(smartToggle);

            health.set(6);
            assertTrue(predictDamage.isVisible());
            assertTrue(smartToggle.isVisible());

            health.set(0);
            assertFalse(predictDamage.isVisible(), "predict-incoming-damage must be hidden/inactive when health is set to 0 (disabled)");
            assertFalse(smartToggle.isVisible(), "smart-toggle must be hidden when health is set to 0 (disabled)");
        }
    }
}
