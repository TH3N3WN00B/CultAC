package ac.cult.cultac.utils.reflection;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@UtilityClass
public class ReflectionUtils {

    private static final List<Class<?>> NO_PARAMETERS = List.of();

    /**
     * Per-(class, method name, parameter types) lookup cache. Packet accessors
     * are resolved through {@link Class#getMethod(String, Class...)} for every
     * packet, which performs a linear scan and allocates a new Method on each
     * call. Caching both hits and misses removes that cost from the per-packet
     * hot path while preserving exactly the same lookup semantics.
     */
    private static final ConcurrentMap<MethodKey, Optional<Method>> METHOD_CACHE = new ConcurrentHashMap<>();

    /**
     * Per-(class) -> (field name) lookup cache for {@link Class#getDeclaredField(String)}.
     */
    private static final ConcurrentMap<Class<?>, ConcurrentMap<String, Optional<Field>>> DECLARED_FIELD_CACHE = new ConcurrentHashMap<>();

    private record MethodKey(Class<?> owner, String name, List<Class<?>> parameterTypes) {
    }

    public static boolean hasClass(String className) {
        return getClass(className) != null;
    }

    public static boolean hasMethod(@NotNull Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        return getMethod(clazz, methodName, parameterTypes) != null;
    }

    public static @Nullable Method getMethod(@NotNull Class<?> clazz, @NotNull String methodName, Class<?>... parameterTypes) {
        try {
            return clazz.getMethod(methodName, parameterTypes);
        } catch (NoSuchMethodException e) {
            while (clazz != null) {
                try {
                    return clazz.getDeclaredMethod(methodName, parameterTypes);
                } catch (NoSuchMethodException ignored) {
                    clazz = clazz.getSuperclass();
                }
            }
        }

        return null;
    }

    /**
     * Cached variant of {@code clazz.getMethod(methodName)} for the per-packet
     * hot path. Returns null when the method does not exist; null results are
     * cached so repeated lookups of unsupported accessor-name variants do not
     * re-run the reflection scan.
     */
    public static @Nullable Method getMethodCached(@NotNull Class<?> clazz, @NotNull String methodName) {
        return getMethodCachedInternal(clazz, methodName, NO_PARAMETERS);
    }

    /**
     * Cached variant of {@code clazz.getMethod(methodName, parameterTypes)}.
     */
    public static @Nullable Method getMethodCached(@NotNull Class<?> clazz, @NotNull String methodName, Class<?>... parameterTypes) {
        return getMethodCachedInternal(clazz, methodName, List.of(parameterTypes));
    }

    private static @Nullable Method getMethodCachedInternal(Class<?> clazz, String methodName, List<Class<?>> parameterTypes) {
        MethodKey key = new MethodKey(clazz, methodName, parameterTypes);
        return METHOD_CACHE.computeIfAbsent(key, ignored -> {
            try {
                return Optional.of(clazz.getMethod(methodName, parameterTypes.toArray(new Class<?>[0])));
            } catch (NoSuchMethodException e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    /**
     * Cached variant of {@code clazz.getDeclaredField(fieldName)} with
     * {@code setAccessible(true)} applied once.
     */
    public static @Nullable Field getDeclaredFieldCached(@NotNull Class<?> clazz, @NotNull String fieldName) {
        return DECLARED_FIELD_CACHE
                .computeIfAbsent(clazz, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(fieldName, name -> {
                    try {
                        Field field = clazz.getDeclaredField(name);
                        field.setAccessible(true);
                        return Optional.of(field);
                    } catch (NoSuchFieldException e) {
                        return Optional.empty();
                    }
                })
                .orElse(null);
    }

    public static @Nullable Class<?> getClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /**
     * Finds a field by name, searching up the superclass hierarchy.
     */
    public static Field getField(Class<?> clazz, String fieldName) {
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }
}
