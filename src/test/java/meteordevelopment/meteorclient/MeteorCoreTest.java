/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient;

import meteordevelopment.meteorclient.utils.PostInit;
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
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;

import java.lang.reflect.Field;
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
            assertThrows(IllegalArgumentException.class, () -> new Version("1.a.0"));
        }
    }

    @Nested
    @DisplayName("ValueComparableMap")
    class ValueComparableMapTests {

        @Test
        void ordersByValueAndUpdatesExistingKeyCleanly() {
            ValueComparableMap<String, Integer> map = new ValueComparableMap<>(Comparator.naturalOrder());
            map.put("beta", 20);
            map.put("alpha", 10);
            map.put("gamma", 20);

            assertEquals(List.of("alpha", "beta", "gamma"), new ArrayList<>(map.keySet()));
            assertTrue(map.containsKey("beta"));
            assertEquals(20, map.get("beta"));

            // Updating "beta" to a smaller value than "alpha" must move it to the front
            map.put("beta", 5);
            assertEquals(5, map.get("beta"));
            assertEquals(List.of("beta", "alpha", "gamma"), new ArrayList<>(map.keySet()));
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
}
