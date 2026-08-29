package tcl;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

final class Methods {

    private Methods() {}

    static Object call(Tcl tcl, Object receiver, List<Object> args) {
        if (args.isEmpty()) throw new TclException.Error("missing Java method name");
        var name = Values.string(args.getFirst());
        var values = args.subList(1, args.size());
        var type = receiver instanceof Class<?> found ? found : receiver.getClass();
        var candidates = new ArrayList<Candidate>();
        for (var method : type.getMethods()) {
            if (!method.getName().equals(name) || method.isBridge() || method.isSynthetic()) continue;
            if (receiver instanceof Class<?> && !Modifier.isStatic(method.getModifiers())) continue;
            var converted = convert(tcl, accessible(type, method), values);
            if (converted != null) candidates.add(converted);
        }
        if (candidates.isEmpty()) throw new TclException.Error("no public Java method \"" + name + "\" accepts " + values.size() + " argument(s)");
        candidates.sort(Comparator.comparingInt(Candidate::score));
        if (candidates.size() > 1 && candidates.get(0).score == candidates.get(1).score) {
            throw new TclException.Error("ambiguous Java method \"" + name + "\"");
        }
        var selected = candidates.getFirst();
        try {
            var result = selected.method.invoke(receiver instanceof Class<?> ? null : receiver, selected.arguments);
            return selected.method.getReturnType() == void.class ? "" : result;
        } catch (InvocationTargetException e) {
            var cause = e.getCause();
            if (cause instanceof TclException completion) throw completion;
            throw new TclException.Error(cause.getMessage() == null ? cause.getClass().getName() : cause.getMessage(),
                    java.util.Map.of("-errorcode", List.of("JAVA", cause.getClass().getName())), cause);
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            throw new TclException.Error("cannot call Java method \"" + name + "\": " + e.getMessage(),
                    java.util.Map.of("-errorcode", List.of("JAVA", e.getClass().getName())), e);
        }
    }

    private static Candidate convert(Tcl tcl, Method method, List<Object> values) {
        var types = method.getParameterTypes();
        if (!method.isVarArgs() && types.length != values.size()) return null;
        if (method.isVarArgs() && values.size() < types.length - 1) return null;
        var arguments = new Object[types.length];
        var score = 0;
        for (var at = 0; at < types.length; at++) {
            if (method.isVarArgs() && at == types.length - 1) {
                var component = types[at].componentType();
                var count = values.size() - at;
                var array = Array.newInstance(component, count);
                for (var index = 0; index < count; index++) {
                    var conversion = convert(tcl, values.get(at + index), component);
                    if (conversion == null) return null;
                    Array.set(array, index, conversion.value);
                    score += conversion.score + 2;
                }
                arguments[at] = array;
                continue;
            }
            var conversion = convert(tcl, values.get(at), types[at]);
            if (conversion == null) return null;
            arguments[at] = conversion.value;
            score += conversion.score;
        }
        return new Candidate(method, arguments, score);
    }

    private static Method accessible(Class<?> receiver, Method method) {
        if (Modifier.isPublic(method.getDeclaringClass().getModifiers())) return method;
        for (var type : receiver.getInterfaces()) {
            try {
                return type.getMethod(method.getName(), method.getParameterTypes());
            } catch (NoSuchMethodException ignored) {
                var inherited = accessibleInterface(type, method);
                if (inherited != null) return inherited;
            }
        }
        var parent = receiver.getSuperclass();
        return parent == null ? method : accessible(parent, method);
    }

    private static Method accessibleInterface(Class<?> type, Method method) {
        for (var parent : type.getInterfaces()) {
            try {
                return parent.getMethod(method.getName(), method.getParameterTypes());
            } catch (NoSuchMethodException ignored) {
                var inherited = accessibleInterface(parent, method);
                if (inherited != null) return inherited;
            }
        }
        return null;
    }

    private static Conversion convert(Tcl tcl, Object value, Class<?> parameter) {
        if (value == null) return parameter.isPrimitive() ? null : new Conversion(null, 1);
        var boxed = box(parameter);
        if (boxed == value.getClass()) return new Conversion(value, 0);
        if (parameter.isInstance(value)) return new Conversion(value, 1);
        if (value instanceof Number number && Number.class.isAssignableFrom(boxed)) {
            var converted = number(number, boxed);
            return converted == null ? null : new Conversion(converted, 2 + widening(number.getClass(), boxed));
        }
        if (parameter == String.class || parameter == CharSequence.class) return new Conversion(Values.string(value), 5);
        if (value instanceof String name && parameter.isEnum()) {
            try {
                @SuppressWarnings({"rawtypes", "unchecked"})
                var constant = Enum.valueOf((Class) parameter, name);
                return new Conversion(constant, 4);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        if ((value instanceof String || value instanceof Command) && sam(parameter) != null) {
            return new Conversion(proxy(tcl, value, parameter, sam(parameter)), 6);
        }
        return null;
    }

    private static Object number(Number value, Class<?> type) {
        if (numericRank(type) < numericRank(value.getClass())) return null;
        if (type == Byte.class) return value instanceof Byte ? value.byteValue() : null;
        if (type == Short.class) return value instanceof Byte || value instanceof Short ? value.shortValue() : null;
        if (type == Integer.class) return value instanceof Byte || value instanceof Short || value instanceof Integer ? value.intValue() : null;
        if (type == Long.class) return value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long ? value.longValue() : null;
        if (type == Float.class) return value.floatValue();
        if (type == Double.class) return value.doubleValue();
        return null;
    }

    private static int widening(Class<?> source, Class<?> target) {
        return Math.max(0, numericRank(target) - numericRank(source));
    }

    private static int numericRank(Class<?> type) {
        if (type == Byte.class) return 0;
        if (type == Short.class) return 1;
        if (type == Integer.class) return 2;
        if (type == Long.class || type == java.math.BigInteger.class) return 3;
        if (type == Float.class) return 4;
        return 5;
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) return type;
        return switch (type.getName()) {
            case "boolean" -> Boolean.class;
            case "byte" -> Byte.class;
            case "short" -> Short.class;
            case "int" -> Integer.class;
            case "long" -> Long.class;
            case "float" -> Float.class;
            case "double" -> Double.class;
            case "char" -> Character.class;
            default -> type;
        };
    }

    private static Method sam(Class<?> type) {
        if (!type.isInterface()) return null;
        Method result = null;
        for (var method : type.getMethods()) {
            if (!Modifier.isAbstract(method.getModifiers()) || method.isDefault() || method.getDeclaringClass() == Object.class) continue;
            if (result != null && !sameSignature(result, method)) return null;
            result = method;
        }
        return result;
    }

    private static boolean sameSignature(Method left, Method right) {
        return left.getName().equals(right.getName()) && Arrays.equals(left.getParameterTypes(), right.getParameterTypes());
    }

    private static Object proxy(Tcl tcl, Object command, Class<?> type, Method sam) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "Tcl callback " + Values.string(command);
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            }
            var result = tcl.callback(command, args == null ? new Object[0] : args);
            var returns = method.getReturnType();
            if (returns == void.class) return null;
            if (returns == boolean.class || returns == Boolean.class) return Values.bool(result);
            if (returns == int.class || returns == Integer.class) return Math.toIntExact(Values.integer(result));
            if (returns == long.class || returns == Long.class) return Values.integer(result);
            if (returns == double.class || returns == Double.class) return Values.number(result);
            if (returns == float.class || returns == Float.class) return (float) Values.number(result);
            return result;
        });
    }

    private record Conversion(Object value, int score) {}
    private record Candidate(Method method, Object[] arguments, int score) {}
}
