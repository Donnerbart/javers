package org.javers.common.reflection;

import io.github.classgraph.ClassGraph;
import org.javers.common.collections.Lists;
import org.javers.common.collections.Sets;
import org.javers.common.exception.JaversException;
import org.javers.common.exception.JaversExceptionCode;
import org.javers.common.validation.Validate;
import org.javers.core.Javers;
import org.javers.core.metamodel.property.Property;
import org.slf4j.Logger;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.unmodifiableSet;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * @author bartosz walacik
 */
public class ReflectionUtil {
    private static final Logger logger = getLogger(ReflectionUtil.class);

    public static boolean isClassPresent(String className) {
        try {
            Class.forName(className, false, Javers.class.getClassLoader());
            return true;
        }
        catch (Throwable ex) {
            // Class or one of its dependencies is not present...
            return false;
        }
    }

    /**
     * throws RuntimeException if class is not found
     */
    public static Class<?> classForName(String className) {
        try {
            return Class.forName(className, false, Javers.class.getClassLoader());
        }
        catch (ClassNotFoundException ex) {
            throw new JaversException(JaversExceptionCode.CLASS_NOT_FOUND, className);
        }
    }

    public static Object invokeGetter(Object target, String getterName) {
        Validate.argumentsAreNotNull(target, getterName);
        try {
            Method m = target.getClass().getMethod(getterName);
            return m.invoke(target);
        }catch (Exception e ) {
            throw new JaversException(e);
        }
    }

    /**
     * Creates new instance of public or package-private class.
     * Calls first, not-private constructor
     */
    public static Object newInstance(Class clazz, ArgumentResolver resolver){
        Validate.argumentIsNotNull(clazz);
        for (Constructor constructor : clazz.getDeclaredConstructors()) {
            if (isPrivate(constructor) || isProtected(constructor)) {
                continue;
            }

            Class [] types = constructor.getParameterTypes();
            Object[] params = new Object[types.length];
            for (int i=0; i<types.length; i++){
                try {
                    params[i] = resolver.resolve(types[i]);
                } catch (JaversException e){
                    logger.error("failed to create new instance of "+clazz.getName()+", argument resolver for arg["+i+"] " +
                                 types[i].getName() + " thrown exception: "+e.getMessage());
                    throw e;
                }
            }
            try {
                constructor.setAccessible(true);
                return constructor.newInstance(params);
            } catch (Exception e) {
                throw new JaversException(JaversExceptionCode.ERROR_WHEN_INVOKING_CONSTRUCTOR, clazz.getName());
            }
        }
        throw new JaversException(JaversExceptionCode.NO_PUBLIC_CONSTRUCTOR, clazz.getName());
    }

    public static Object newInstance(Class clazz) {
        Validate.argumentIsNotNull(clazz);

        for (Constructor constructor : clazz.getDeclaredConstructors()) {
            if (isPrivate(constructor) || isProtected(constructor) || constructor.getParameterCount() > 0) {
                continue;
            }

            try {
                constructor.setAccessible(true);
                return constructor.newInstance();
            } catch (Exception e) {
                throw new JaversException(JaversExceptionCode.ERROR_WHEN_INVOKING_CONSTRUCTOR, clazz.getName());
            }
        }
        throw new JaversException(JaversExceptionCode.NO_PUBLIC_ZERO_ARG_CONSTRUCTOR, clazz.getName());
    }

    public static List<JaversField> getAllPersistentFields(Class methodSource) {
        List<JaversField> result = new ArrayList<>();
        for(JaversField field : getAllFields(methodSource)) {
            if (isPersistentField(field.getRawMember())) {
                result.add(field);
            }
        }
        return result;
    }

    public static List<JaversGetter> getAllGetters(Class methodSource) {
        JaversGetterFactory getterFactory = new JaversGetterFactory(methodSource);
        return getterFactory.getAllGetters();
    }

    public static List<JaversField> getAllFields(Class<?> methodSource) {
        JaversFieldFactory fieldFactory = new JaversFieldFactory(methodSource);
        return fieldFactory.getAllFields();
    }

    public static Optional<JaversMember> getMirrorMember(JaversMember member, Class methodSource) {
        if (member instanceof JaversGetter) {
            return (Optional)getMirrorGetter((JaversGetter)member, methodSource);
        }
        if (member instanceof JaversField) {
            return (Optional)getMirrorField((JaversField)member, methodSource);
        }
        throw new JaversException(JaversExceptionCode.NOT_IMPLEMENTED);
    }

    public static Optional<JaversField> getMirrorField(JaversField field, Class methodSource) {
        return getAllFields(methodSource).stream().filter(f -> f.propertyName().equals(field.propertyName())).findFirst();
    }

    public static Optional<JaversGetter> getMirrorGetter(JaversGetter getter, Class methodSource) {
        return getAllGetters(methodSource).stream().filter(f -> f.propertyName().equals(getter.propertyName())).findFirst();
    }

    private static boolean isPersistentField(Field field) {
        return !Modifier.isTransient(field.getModifiers()) &&
               !Modifier.isStatic(field.getModifiers()) &&
               !field.getName().equals("this$0"); //owner of inner class
    }

    private static boolean isPrivate(Member member){
        return Modifier.isPrivate(member.getModifiers());
    }

    static boolean isNotStatic(Member member) {
        return !Modifier.isStatic(member.getModifiers());
    }

    private static boolean isProtected(Member member){
        return Modifier.isProtected(member.getModifiers());
    }

    /**
     * Makes sense for {@link ParameterizedType}
     */
    public static List<Type> getAllTypeArguments(Type javaType) {
        if (!(javaType instanceof ParameterizedType)) {
            return Collections.emptyList();
        }

        return Lists.immutableListOf(((ParameterizedType) javaType).getActualTypeArguments());
    }

    public static List<Class<?>> findClasses(Class<? extends Annotation> annotation, String... packages) {
        Validate.argumentsAreNotNull(annotation, packages);
    	return new ClassGraph()
                .whitelistPackages(packages)
                .enableAnnotationInfo()
                .scan()
                .getClassesWithAnnotation(annotation.getName())
                .loadClasses();
    }

    public static Optional<Type> isConcreteType(Type javaType){
        if (javaType instanceof Class || javaType instanceof ParameterizedType) {
            return Optional.of(javaType);
        } else if (javaType instanceof WildcardType) {
            // If the wildcard type has an explicit upper bound (i.e. not Object), we use that
            WildcardType wildcardType = (WildcardType) javaType;
            if (wildcardType.getLowerBounds().length == 0) {
                for (Type type : wildcardType.getUpperBounds()) {
                    if (type instanceof Class && ((Class<?>) type).equals(Object.class)) {
                        continue;
                    }
                    return Optional.of(type);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * for example: Map<String, String> -> Map
     */
    public static Class extractClass(Type javaType) {
        if (javaType instanceof ParameterizedType
                && ((ParameterizedType)javaType).getRawType() instanceof Class){
            return (Class)((ParameterizedType)javaType).getRawType();
        }  else if (javaType instanceof GenericArrayType) {
            return Object[].class;
        }  else if (javaType instanceof Class) {
            return (Class)javaType;
        }

        throw new JaversException(JaversExceptionCode.CLASS_EXTRACTION_ERROR, javaType);
    }

    public static boolean isAnnotationPresentInHierarchy(Class<?> clazz, Class<? extends Annotation> ann){
        Class<?> current = clazz;

        while (current != null && current != Object.class){
            if (current.isAnnotationPresent(ann)){
                return true;
            }
            current = current.getSuperclass();
        }
        return false;
    }

    public static List<Class<?>> getAllInterfaces(Class<?> clazz) {
        var directInterfaces = clazz.getInterfaces();
        return Stream.concat(
                 Arrays.stream(directInterfaces),
                 Arrays.stream(directInterfaces).flatMap(i -> getAllInterfaces(i).stream())
                ).collect(Collectors.toList());
    }

    public static List<Type> calculateHierarchyDistance(Class<?> clazz) {
        List<Type> interfaces = new ArrayList<>();

        List<Type> parents = new ArrayList<>();

        Class<?> current = clazz;
        while (current != null && current != Object.class){
            if (clazz != current) {
                parents.add(current);
            }

            getAllInterfaces(current).stream().filter(it -> !interfaces.contains(it)).forEach(it -> interfaces.add(it));

            current = current.getSuperclass();
        }

        parents.addAll(interfaces);

        return parents;
    }

    public static String reflectiveToString(Object obj) {
        Validate.argumentIsNotNull(obj);

        StringBuilder ret = new StringBuilder();
        for (JaversField f : getAllPersistentFields(obj.getClass()) ){
            Object val = f.getEvenIfPrivate(obj);
            if (val != null) {
                ret.append(val.toString());
            }
            ret.append(",");
        }

        if (ret.length() == 0) {
            return obj.toString();
        }
        else{
            ret.delete(ret.length()-1, ret.length());
            return ret.toString();
        }
    }

    public static boolean isAssignableFromAny(Class clazz, List<Class<?>> assignableFrom) {
        for (Class<?> standardPrimitive : assignableFrom) {
            if (standardPrimitive.isAssignableFrom(clazz)) {
                return true;
            }
        }
        return false;
    }

    public static <T> T getAnnotationValue(Annotation ann, String propertyName) {
        return (T) ReflectionUtil.invokeGetter(ann, propertyName);
    }

    public static boolean looksLikeId(Member member) {
        return getAnnotations(member).stream()
                .map(ann -> ann.annotationType().getSimpleName())
                .anyMatch(annName -> annName.equals(Property.ID_ANN) || annName.equals(Property.EMBEDDED_ID_ANN));
    }

    public static Set<Annotation> getAnnotations(Member member) {
        return unmodifiableSet(Sets.asSet(((AccessibleObject) member).getAnnotations()));
    }
}
