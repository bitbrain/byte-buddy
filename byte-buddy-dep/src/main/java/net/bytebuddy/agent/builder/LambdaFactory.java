package net.bytebuddy.agent.builder;

import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * This class serves as a dispatcher for creating lambda expression objects when Byte Buddy is configured to instrument the
 * {@code java.lang.invoke.LambdaMetafactory}. For this purpose, this class is injected into the class path to serve as a VM-global
 * singleton and for becoming reachable from the JVM's meta factory. This class keeps a reference to all registered transformers which need
 * to be explicitly deregistered in order to avoid a memory leak.
 */
public class LambdaFactory {

    /**
     * A mapping of all registered class file transformers and their lambda factories, linked in their application order.
     */
    public static final Map<ClassFileTransformer, LambdaFactory> CLASS_FILE_TRANSFORMERS = new LinkedHashMap<ClassFileTransformer, LambdaFactory>();

    /**
     * The target instance that is a factory for creating lambdas.
     */
    private final Object target;

    /**
     * The dispatcher method to invoke for creating a new lambda instance.
     */
    private final Method dispatcher;

    /**
     * Creates a new lambda factory.
     *
     * @param target     The target instance that is a factory for creating lambdas.
     * @param dispatcher The dispatcher method to invoke for creating a new lambda instance.
     */
    private LambdaFactory(Object target, Method dispatcher) {
        this.target = target;
        this.dispatcher = dispatcher;
    }

    /**
     * Registers a class file transformer together with a factory for creating a lambda expression. It is possible to call this method independently
     * of the class loader's context as the supplied injector makes sure that the manipulated collection is the one that is held by the system class
     * loader.
     *
     * @param classFileTransformer The class file transformer to register.
     * @param classFileFactory     The lambda class file factory to use. This factory must define a visible instance method with the signature
     *                             {@code byte[] make(Object, String, Object, Object, Object, Object, boolean, List, List, Collection}. The arguments provided
     *                             are the invokedynamic call site's lookup object, the lambda method's name, the factory method's type, the lambda method's
     *                             type, the target method's handle, the specialized method type of the lambda expression, a boolean to indicate
     *                             serializability, a list of marker interfaces, a list of additional bridges and a collection of class file transformers to
     *                             apply.
     * @param injector             A callable injector that returns the {@link LambdaFactory} loaded by the system class loader.
     * @return {@code true} if this is the first registered transformer. This indicates that the {@code LambdaMetafactory} must be instrumented to delegate
     * to this alternative factory.
     */
    @SuppressWarnings("all")
    public static boolean register(ClassFileTransformer classFileTransformer, Object classFileFactory, Callable<Class<?>> injector) {
        try {
            @SuppressWarnings("unchecked")
            Map<ClassFileTransformer, Object> classFileTransformers = (Map<ClassFileTransformer, Object>) injector.call()
                    .getDeclaredField("CLASS_FILE_TRANSFORMERS")
                    .get(null);
            synchronized (classFileTransformers) {
                try {
                    return classFileTransformers.isEmpty();
                } finally {
                    classFileTransformers.put(classFileTransformer, new LambdaFactory(classFileFactory, classFileFactory.getClass().getDeclaredMethod("make",
                            Object.class,
                            String.class,
                            Object.class,
                            Object.class,
                            Object.class,
                            Object.class,
                            boolean.class,
                            List.class,
                            List.class,
                            Collection.class)));
                }
            }
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not register class file transformer", exception);
        }
    }

    /**
     * Releases a class file transformer.
     *
     * @param classFileTransformer The class file transformer to release.
     * @return {@code true} if the removed transformer was the last class file transformer registered. This indicates that the {@code LambdaMetafactory} must
     * be instrumented to no longer delegate to this alternative factory.
     */
    @SuppressWarnings("all")
    public static boolean release(ClassFileTransformer classFileTransformer) {
        try {
            @SuppressWarnings("unchecked")
            Map<ClassFileTransformer, ?> classFileTransformers = (Map<ClassFileTransformer, ?>) ClassLoader.getSystemClassLoader()
                    .loadClass(LambdaFactory.class.getName())
                    .getDeclaredField("CLASS_FILE_TRANSFORMERS")
                    .get(null);
            synchronized (classFileTransformers) {
                return classFileTransformers.remove(classFileTransformer) != null && classFileTransformers.isEmpty();
            }
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not release class file transformer", exception);
        }
    }

    /**
     * Applies this lambda meta factory.
     *
     * @param caller                 A lookup context representing the creating class of this lambda expression.
     * @param invokedName            The name of the lambda expression's represented method.
     * @param invokedType            The type of the lambda expression's factory method.
     * @param samMethodType          The type of the lambda expression's represented method.
     * @param implMethod             A handle representing the target of the lambda expression's method.
     * @param instantiatedMethodType A specialization of the type of the lambda expression's represented method.
     * @param serializable           {@code true} if the lambda expression should be serializable.
     * @param markerInterfaces       A list of interfaces for the lambda expression to represent.
     * @param additionalBridges      A list of additional bridge methods to be implemented by the lambda expression.
     * @param classFileTransformers  A collection of class file transformers to apply when creating the class.
     * @return A binary representation of the transformed class file.
     */
    private byte[] invoke(Object caller,
                          String invokedName,
                          Object invokedType,
                          Object samMethodType,
                          Object implMethod,
                          Object instantiatedMethodType,
                          boolean serializable,
                          List<Class<?>> markerInterfaces,
                          List<?> additionalBridges,
                          Collection<ClassFileTransformer> classFileTransformers) {

        try {
            return (byte[]) dispatcher.invoke(target,
                    caller,
                    invokedName,
                    invokedType,
                    samMethodType,
                    implMethod,
                    instantiatedMethodType,
                    serializable,
                    markerInterfaces,
                    additionalBridges,
                    classFileTransformers);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot create class for lambda expression", exception);
        }
    }

    /**
     * Dispatches the creation of a new class representing a class file.
     *
     * @param caller                 A lookup context representing the creating class of this lambda expression.
     * @param invokedName            The name of the lambda expression's represented method.
     * @param invokedType            The type of the lambda expression's factory method.
     * @param samMethodType          The type of the lambda expression's represented method.
     * @param implMethod             A handle representing the target of the lambda expression's method.
     * @param instantiatedMethodType A specialization of the type of the lambda expression's represented method.
     * @param serializable           {@code true} if the lambda expression should be serializable.
     * @param markerInterfaces       A list of interfaces for the lambda expression to represent.
     * @param additionalBridges      A list of additional bridge methods to be implemented by the lambda expression.
     * @return A binary representation of the transformed class file.
     */
    public static byte[] make(Object caller,
                              String invokedName,
                              Object invokedType,
                              Object samMethodType,
                              Object implMethod,
                              Object instantiatedMethodType,
                              boolean serializable,
                              List<Class<?>> markerInterfaces,
                              List<?> additionalBridges) {
        return CLASS_FILE_TRANSFORMERS.values().iterator().next().invoke(caller,
                invokedName,
                invokedType,
                samMethodType,
                implMethod,
                instantiatedMethodType,
                serializable,
                markerInterfaces,
                additionalBridges,
                CLASS_FILE_TRANSFORMERS.keySet());
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (other == null || getClass() != other.getClass()) return false;
        LambdaFactory that = (LambdaFactory) other;
        return target.equals(that.target) && dispatcher.equals(that.dispatcher);
    }

    @Override
    public int hashCode() {
        int result = target.hashCode();
        result = 31 * result + dispatcher.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "LambdaFactory{" +
                "target=" + target +
                ", dispatcher=" + dispatcher +
                '}';
    }
}
