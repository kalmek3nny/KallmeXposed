/*
 * Custom changes:
 * V's Custom Attributes injected into the Defensive Unsafe Clone method.
 * Non-destructive attribute override: clones AccountAttribute objects instead of mutating in-place.
 * V's MASTER DUAL-FORENSICS DUMPER: Harvests both the Server's Reality and V's Shadow Map.
 * V's MANUAL GHOST PROTOCOL: Checks for the lock file dropped by Kotlin. If found, returns the clean map.
 * Stack Trace Filter: Prevents server-side detection by serving original data to network/sync calls.
 * Dynamic Proxy Lists: Protects Protobuf integrity by proxying list interfaces.
 */
package app.revanced.extension.spotify.misc;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashMap;

import app.revanced.extension.shared.Logger;
import de.robv.android.xposed.XposedHelpers;

@SuppressWarnings("unused")
public final class UnlockPremiumPatch {

    // V'S DUAL MEMORY BANKS
    private static final Map<String, String> SEEN_ORIGINAL_STATES = new HashMap<>();
    private static final Map<String, String> SEEN_SHADOW_STATES = new HashMap<>();
    private static final Object FILE_LOCK = new Object();

    private static class OverrideAttribute {
        final String key;
        final Object overrideValue;
        final boolean isExpected;

        OverrideAttribute(String key, Object overrideValue) {
            this(key, overrideValue, true);
        }

        OverrideAttribute(String key, Object overrideValue, boolean isExpected) {
            this.key = Objects.requireNonNull(key);
            this.overrideValue = Objects.requireNonNull(overrideValue);
            this.isExpected = isExpected;
        }
    }

    private static final List<OverrideAttribute> PREMIUM_OVERRIDES = List.of(
            // --- CORE FUNCTIONALITY ---
            new OverrideAttribute("ads", FALSE),
            new OverrideAttribute("player-license", "premium"),
            new OverrideAttribute("type", "premium"),
            new OverrideAttribute("shuffle", FALSE),
            new OverrideAttribute("on-demand", TRUE),
            new OverrideAttribute("streaming", TRUE),
            new OverrideAttribute("pick-and-shuffle", FALSE),
            new OverrideAttribute("streaming-rules", ""),
            new OverrideAttribute("nft-disabled", "1"),

            // 🎯 --- V'S AGGRESSIVE NEW HITLIST --- 🎯
            new OverrideAttribute("smart-shuffle", "AVAILABLE", false),
            new OverrideAttribute("ad-formats-preroll-video", FALSE, false),
            new OverrideAttribute("has-audiobooks-subscription", TRUE, false),
            new OverrideAttribute("social-session-free-tier", FALSE, false),
            new OverrideAttribute("jam-social-session", "PREMIUM", false),
            new OverrideAttribute("parrot", "enabled", false),
            // new OverrideAttribute("on-demand-trial-in-progress", TRUE, false),
            new OverrideAttribute("ugc-abuse-report", FALSE, false),
            new OverrideAttribute("offline-backup", "ENABLED", false),
            new OverrideAttribute("lyrics-offline", TRUE, false),

            // 🚀 --- THE PERFORMANCE & UI OVERRIDES --- 🚀
            new OverrideAttribute("is-tuna", TRUE, false),
            new OverrideAttribute("is-seadragon", TRUE, false),

            // --- V'S CUSTOM DEEP CORE ATTRIBUTES ---
            new OverrideAttribute("audio-quality", "2", false),
            new OverrideAttribute("social-session", TRUE, false),
            new OverrideAttribute("obfuscate-restricted-tracks", FALSE, false),
            new OverrideAttribute("dj-accessible", TRUE, false),
            new OverrideAttribute("enable-dj", TRUE, false),
            new OverrideAttribute("ai-playlists", TRUE, false),
            new OverrideAttribute("can_use_superbird", TRUE, false)
    );

    private static final List<Integer> REMOVED_HOME_SECTIONS = List.of(
            com.spotify.home.evopage.homeapi.proto.Section.VIDEO_BRAND_AD_FIELD_NUMBER,
            com.spotify.home.evopage.homeapi.proto.Section.IMAGE_BRAND_AD_FIELD_NUMBER
    );

    private static final List<Integer> REMOVED_BROWSE_SECTIONS = List.of(
            com.spotify.browsita.v1.resolved.Section.BRAND_ADS_FIELD_NUMBER
    );

    /**
     * Universally checks the execution stack trace to determine if the caller
     * is a network, serialization, or synchronization component.
     * This prevents server-side bans by ensuring the spoofed data is never leaked back to Spotify.
     */
    public static boolean isNetworkSyncCall() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stackTrace) {
            String className = element.getClassName().toLowerCase();
            if (className.contains("grpc") ||
                    className.contains("network") ||
                    className.contains("sync") ||
                    className.contains("api") ||
                    className.contains("retrofit") ||
                    className.contains("http") ||
                    className.contains("telemetry") ||
                    className.contains("analytics") ||
                    className.contains("metrics") ||
                    className.contains("event") ||
                    className.contains("logger") ||
                    className.contains("crashlytics")) {
                return true;
            }
        }
        return false;
    }

    // ==========================================================
    // V'S SILENT DATA CLONER (LOGGING MOVED TO KOTLIN)
    // ==========================================================
    @SuppressWarnings("unchecked")
    public static Map<String, ?> createOverriddenAttributesMap(Map<String, ?> originalMap) {
        // V's GHOST PROTOCOL: Serve reality to the server, shadows to the UI
        if (isNetworkSyncCall()) {
            return originalMap;
        }

        try {
            Map<String, Object> result = new LinkedHashMap<>((Map<String, Object>) originalMap);

            for (OverrideAttribute override : PREMIUM_OVERRIDES) {
                Object attribute = result.get(override.key);
                if (attribute == null) continue;

                Object originalValue = XposedHelpers.getObjectField(attribute, "value_");
                if (override.overrideValue.equals(originalValue)) continue;

                Object clonedAttribute = shallowCloneObject(attribute);
                XposedHelpers.setObjectField(clonedAttribute, "value_", override.overrideValue);
                result.put(override.key, clonedAttribute);
            }
            return result;
        } catch (Exception ex) {
            return originalMap;
        }
    }

    private static volatile Object unsafeInstance;
    private static volatile java.lang.reflect.Method allocateInstanceMethod;

    private static Object shallowCloneObject(Object original) {
        try {
            if (unsafeInstance == null) {
                Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
                Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
                unsafeField.setAccessible(true);
                unsafeInstance = unsafeField.get(null);
                allocateInstanceMethod = unsafeClass.getMethod("allocateInstance", Class.class);
            }

            Class<?> clazz = original.getClass();
            Object clone = allocateInstanceMethod.invoke(unsafeInstance, clazz);

            Class<?> current = clazz;
            while (current != null && current != Object.class) {
                for (Field f : current.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    f.set(clone, f.get(original));
                }
                current = current.getSuperclass();
            }

            return clone;
        } catch (Exception e) {
            throw new RuntimeException("Failed to clone " + original.getClass().getName(), e);
        }
    }

    public static String removeStationString(String spotifyUriOrUrl) {
        try {
            return spotifyUriOrUrl.replace("spotify:station:", "spotify:");
        } catch (Exception ex) {
            return spotifyUriOrUrl;
        }
    }

    private interface FeatureTypeIdProvider<T> {
        int getFeatureTypeId(T section);
    }

    /**
     * Returns a dynamically proxied list with ad sections removed.
     * The proxy implements exactly the interfaces of the original list.
     * This prevents detection through protobuf integrity checks, server-side
     * serialization of modified structures, or ClassCastExceptions.
     */
    @SuppressWarnings("unchecked")
    private static <T> List<T> filterSections(
            List<T> sections,
            FeatureTypeIdProvider<T> featureTypeExtractor,
            List<Integer> idsToRemove
    ) {
        if (isNetworkSyncCall()) {
            return sections;
        }

        try {
            List<T> filteredData = new java.util.ArrayList<>(sections.size());
            for (T section : sections) {
                int featureTypeId = featureTypeExtractor.getFeatureTypeId(section);
                if (!idsToRemove.contains(featureTypeId)) {
                    filteredData.add(section);
                }
            }

            // Create a Dynamic Proxy that implements List (and any other interfaces the original list implements)
            Class<?>[] interfaces = sections.getClass().getInterfaces();
            if (interfaces.length == 0) {
                interfaces = new Class<?>[] { List.class };
            } else {
                // Ensure java.util.List is in the array just in case
                boolean hasList = false;
                for (Class<?> i : interfaces) {
                    if (i == List.class) { hasList = true; break; }
                }
                if (!hasList) {
                    Class<?>[] newInterfaces = new Class<?>[interfaces.length + 1];
                    System.arraycopy(interfaces, 0, newInterfaces, 0, interfaces.length);
                    newInterfaces[interfaces.length] = List.class;
                    interfaces = newInterfaces;
                }
            }

            return (List<T>) java.lang.reflect.Proxy.newProxyInstance(
                    sections.getClass().getClassLoader(),
                    interfaces,
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
                            try {
                                // If the method belongs to the List/Collection interface,
                                // route it to our filtered ArrayList proxy.
                                if (method.getDeclaringClass().isAssignableFrom(List.class)) {
                                    return method.invoke(filteredData, args);
                                }

                                // Otherwise, this is likely a Protobuf-specific method (like isModifiable()).
                                // Route it to the original list to prevent IllegalArgumentExceptions
                                // which flag the account for tampering to Spotify servers.
                                return method.invoke(sections, args);
                            } catch (java.lang.reflect.InvocationTargetException e) {
                                throw e.getTargetException();
                            }
                        }
                    }
            );
        } catch (Exception ex) {
            return sections;
        }
    }

    /**
     * Injection point. Returns a new list with ads sections filtered from home.
     * Original protobuf list is not modified.
     */
    public static List<?> filterHomeSections(List<?> sections) {
        return filterSections(
                sections,
                section -> XposedHelpers.getIntField(section, "featureTypeCase_"),
                REMOVED_HOME_SECTIONS
        );
    }

    /**
     * Injection point. Returns a new list with ads sections filtered from browse.
     * Original protobuf list is not modified.
     */
    public static List<?> filterBrowseSections(List<?> sections) {
        return filterSections(
                sections,
                section -> XposedHelpers.getIntField(section, "sectionTypeCase_"),
                REMOVED_BROWSE_SECTIONS
        );
    }
}
