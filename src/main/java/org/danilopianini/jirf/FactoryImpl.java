package org.danilopianini.jirf;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.ClassUtils;
import org.jgrapht.DirectedGraph;
import org.jgrapht.alg.DijkstraShortestPath;
import org.jgrapht.graph.DefaultDirectedGraph;

final class FactoryImpl implements Factory {

    private final Map<Class<?>, Object> singletons = new LinkedHashMap<>();
    private final ImplicitEdgeFactory edgeFactory = new ImplicitEdgeFactory();
    private final DirectedGraph<Class<?>, FunctionEdge> implicits = new DefaultDirectedGraph<>(edgeFactory);

    FactoryImpl() {
    }

    @SuppressWarnings("unchecked")
    @Override
    public <E> E build(final Class<E> clazz, final List<?> args) {
        registerHierarchy(clazz);
        return getFromStaticSources(clazz)
                .orElseGet(() -> {
                    final Constructor<E>[] constructors = (Constructor<E>[]) clazz.getConstructors();
                    final List<Throwable> exceptions = new LinkedList<>();
                    return Arrays.stream(constructors)
                            .map(c -> new ConstructorBenchmark<E>(c, args))
                            .filter(cb -> cb.score >= 0)
                            .sorted()
                            .map(cb -> createBestEffort(cb.constructor, args))
                            .filter(e -> {
                                if (e instanceof Throwable) {
                                    exceptions.add((Throwable) e);
                                    return false;
                                }
                                return true;
                            })
                            .map(e -> (E) e)
                            .findFirst()
                            .orElseThrow(() -> {
                                final IllegalArgumentException ex = new IllegalArgumentException("Cannot create "
                                                + clazz.getName()
                                                + " with arguments "
                                                + '['
                                                + args.stream()
                                                    .map(o -> o.toString() + ':' + o.getClass().getSimpleName())
                                                    .collect(Collectors.joining(", "))
                                                + "]");
                                exceptions.forEach(ex::addSuppressed);
                                return ex;
                            });
                });
    }

    @Override
    public <E> E build(final Class<E> clazz, final Object... parameters) {
        return build(clazz, Arrays.asList(parameters));
    }

    @SuppressWarnings("unchecked")
    public <I, O> Optional<O> convert(final Class<O> destination, final I source) {
        return findConversionChain(Objects.requireNonNull(source.getClass()), Objects.requireNonNull(destination))
            .map(chain -> {
                Object in = source;
                for (final FunctionEdge implicit : chain) {
                    in = ((Function<Object, ?>) implicit.getFunction()).apply(in);
                }
                return (O) in;
            });
    }

    @SuppressWarnings("unchecked")
    private <E> Optional<E> getSingleton(final Class<? super E> clazz) {
        return Optional.ofNullable((E) singletons.get(clazz));
    }

    private <E> Optional<E> getFromStaticSources(final Class<E> clazz) {
        return getSingleton(clazz);
    }

    private Object createBestEffort(final Constructor<?> constructor, final List<?> params) {
        final Deque<?> paramsLeft = new LinkedList<>(params);
        final Class<?>[] expectedTypes = constructor.getParameterTypes();
        final Object[] actualArgs = new Object[expectedTypes.length];
        for (int i = 0; i < expectedTypes.length; i++) {
            final Class<?> expected = expectedTypes[i];
            final Optional<?> single = getFromStaticSources(expected);
            if (single.isPresent()) {
                actualArgs[i] = single.get();
            } else {
                final Object param = paramsLeft.pop();
                if (param == null || expected.isAssignableFrom(param.getClass())) {
                    actualArgs[i] = param;
                } else {
                    final Optional<?> result = convert(expected, param);
                    if (result.isPresent()) {
                        actualArgs[i] = result.get();
                    } else {
                        return new InstancingImpossibleException(constructor, "Couldn't convert " + param + " from " + param.getClass().getName() + " to " + expected.getName());
                    }
                }
            }
        }
        try {
            return constructor.newInstance(actualArgs);
        } catch (Exception e) {
            return new InstancingImpossibleException(constructor, e);
        }
    }

    private <S, D> Optional<List<FunctionEdge>> findConversionChain(final Class<S> source, final Class<D> destination) {
        registerHierarchy(source);
        registerHierarchy(destination);
        return Optional.ofNullable(DijkstraShortestPath.findPathBetween(implicits, source, destination));
    }

    @Override
    public <S, D> void registerImplicit(
            final Class<S> source,
            final Class<D> target,
            final Function<? super S, ? extends D> implicit) {
        registerHierarchy(source);
        registerHierarchy(target);
        addEdge(source, target, implicit);
    }

    private <S, D> void addEdge(
            final Class<S> source,
            final Class<D> target,
            final Function<? super S, ? extends D> implicit) {
        edgeFactory.addImplicitConversion(source, target, implicit);
        implicits.removeEdge(source, target);
        Objects.requireNonNull(implicits.addEdge(source, target));
    }

    @SuppressWarnings("unchecked")
    private <T> void registerHierarchy(final Class<T> x) {
        assert x != null;
        if (!implicits.containsVertex(x)) {
            implicits.addVertex(x);
            final Class<? super T> superclass = x.getSuperclass();
            if (superclass != null) {
                registerHierarchy(superclass);
                addEdge(x, superclass, Function.identity());
            } else if (x.isInterface()) {
                addEdge(x, Object.class, Function.identity());
            }
            for (@SuppressWarnings("rawtypes") final Class iface: x.getInterfaces()) {
                registerHierarchy(iface);
                addEdge(x, iface, Function.identity());
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <E> void registerSingleton(
            final Class<? super E> lowerBound,
            final Class<? super E> upperBound,
            final E object) {
        register(singletons, Objects.requireNonNull(lowerBound), Objects.requireNonNull(upperBound), (Class<E>) Objects.requireNonNull(object).getClass(), object);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <E> void registerSingleton(final Class<? super E> bound, final E object) {
        registerSingleton((Class<E>) Objects.requireNonNull(object).getClass(), bound, object);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <E> void registerSingleton(final E object) {
        registerSingleton((Class<E>) Objects.requireNonNull(object).getClass(), object);
    }

    private static void checkSuperclass(final Class<?> lower, final Class<?> upper) {
        if (!upper.isAssignableFrom(lower)) {
            throw new IllegalArgumentException(upper + " must be a superclass of " + lower);
        }
    }

    private static <E, O> void register(
            final Map<Class<?>, O> map,
            final Class<? super E> lowerbound,
            final Class<? super E> upperbound,
            final Class<? super E> clazz,
            final O object) {
        checkSuperclass(Objects.requireNonNull(clazz), Objects.requireNonNull(lowerbound));
        checkSuperclass(lowerbound, Objects.requireNonNull(upperbound));
        for (Class<? super E> c = lowerbound; c != null && upperbound.isAssignableFrom(c); c = c.getSuperclass()) {
            map.put(c, Objects.requireNonNull(object));
        }
        if (upperbound.isInterface()) {
            final boolean li = lowerbound.isInterface();
            for (final Class<?> i : ClassUtils.getAllInterfaces(clazz)) {
                if (upperbound.isAssignableFrom(i) && (!li || i.isAssignableFrom(lowerbound))) {
                    map.put(i, object);
                }
            }
        }
    }

    private class ConstructorBenchmark<T> implements Comparable<ConstructorBenchmark<T>> {
        private final Constructor<T> constructor;
        private final int score;

        ConstructorBenchmark(final Constructor<T> constructor, final List<?> args) {
            this.constructor = constructor;
            /*
             * Filter out constructor arguments that will be assigned to singletons
             */
            final Class<?>[] filteredParams = Arrays.stream(constructor.getParameterTypes())
                    .filter(clazz -> !singletons.containsKey(clazz))
                    .toArray(i -> new Class<?>[i]);
            score = computeScore(filteredParams, args);
        }

        @Override
        public int compareTo(final ConstructorBenchmark<T> o) {
            return score == o.score
                    ? constructor.toString().compareTo(o.constructor.toString())
                    : Integer.compare(score, o.score);
        }

        private int computeScore(final Class<?>[] filteredParams, final List<?> args) {
            final int argsSize = args.size();
            final int numDiff = argsSize - filteredParams.length;
            if (numDiff == 0) {
                int tempScore = 0;
                for (int i = 0; i < argsSize; i++) {
                    final Class<?> expected = filteredParams[i];
                    final Class<?> actual = args.get(i).getClass();
                    if (!expected.isAssignableFrom(actual)) {
                        tempScore += findConversionChain(actual, expected)
                            .map(List::size)
                            .orElse(implicits.edgeSet().size());
                    }
                }
                return tempScore;
            } else {
                return numDiff > 0 ? Short.MAX_VALUE + numDiff : -1;
            }
        }

        @Override
        public boolean equals(final Object obj) {
            return obj instanceof ConstructorBenchmark
                    && constructor.equals(((ConstructorBenchmark<?>) obj).constructor)
                    && score == ((ConstructorBenchmark<?>) obj).score;
        }

        @Override
        public int hashCode() {
            return constructor.hashCode() ^ score;
        }

        @Override
        public String toString() {
            return constructor + "->" + score;
        }
    }

    @Override
    public <I, O> O convertOrFail(final Class<O> clazz, final I target) {
        return this.<I, O>convert(clazz, target)
                .orElseThrow(() -> new IllegalArgumentException("Unable to convert " + target + " to " + clazz));
    }

    @Override
    public <E> boolean deregisterSingleton(final E object) {
        final Iterator<Object> regSingletons = singletons.values().iterator();
        boolean found = false;
        while (regSingletons.hasNext()) {
            final Object singleton = regSingletons.next();
            if (singleton.equals(object)) {
                regSingletons.remove();
                found = true;
            }
        }
        return found;
    }

}
