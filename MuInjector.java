package io.muserver.dependancyinjection;

import io.muserver.dependancyinjection.annotation.MuBean;
import io.muserver.dependancyinjection.annotation.MuComponent;
import io.muserver.dependancyinjection.annotation.MuConfiguration;
import org.burningwave.core.assembler.ComponentContainer;
import org.burningwave.core.classes.ClassCriteria;
import org.burningwave.core.classes.ClassHunter;
import org.burningwave.core.classes.SearchConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.RuntimeErrorException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;
import java.util.Map.Entry;

public class MuInjector {
    private static final Logger LOGGER = LoggerFactory.getLogger(MuInjector.class);
    private final Map<Class<?>, Class<?>> dependencyInjection;
    private final Map<Class<?>, Object> applicationContext;
    private final ComponentContainer componentContainer = ComponentContainer.getInstance();
    private final ClassHunter classHunter = componentContainer.getClassHunter();

    private static MuInjector muInjector;

    private MuInjector() {
        dependencyInjection = new HashMap<>();
        applicationContext = new HashMap<>();
    }

    public static void startApplication(Class<?> mainClass) {
        try {
            synchronized (MuInjector.class) {
                if (muInjector == null) {
                    muInjector = new MuInjector();
                    muInjector.initFramework(mainClass);
                }
            }
        } catch (Exception ex) {
            LOGGER.error("Could not init dependency framework: {}", ex.getMessage());
        }
    }

    public static <T> T getBean(Class<T> clazz) {
        try {
            return muInjector.getBeanInstance(clazz);
        } catch (Exception e) {
            LOGGER.error("Could not get required bean: {}", e.getMessage());
        }
        return null;
    }


    private void initFramework(Class<?> mainClass) {
        String packageRelativePath = mainClass.getPackage().getName();
        // get all classes
        Class<?>[] classes = getAllClasses(packageRelativePath, true);
        createComponents(packageRelativePath, classes);
        createBeansInConfigurations(packageRelativePath, classes);
    }

    private void createBeansInConfigurations(String packageRelativePath, Class<?>[] classes) {
        try (ClassCriteria classCriteria = ClassCriteria.create().allThoseThatMatch(cls -> cls.getAnnotation(MuConfiguration.class) != null);
             SearchConfig searchConfig = SearchConfig.forResources(packageRelativePath.replace(".", "/"))) {
            try (ClassHunter.SearchResult result = classHunter.findBy(
                searchConfig.by(classCriteria)
            )) {
                // Create configs
                Collection<Class<?>> configs = result.getClasses();
                createInstances(classes, configs);

                List<Method> methods = result.getClasses().stream().flatMap(clazz -> Arrays.stream(clazz.getMethods()).filter(method -> method.isAnnotationPresent(MuBean.class))).collect(Collectors.toList());
                for (Method method : methods) {
                    if(method.getParameterCount() == 0) {
                        Object obj = method.invoke(getBeanInstance(method.getDeclaringClass()));
                        applicationContext.put(method.getReturnType(), obj);
                        dependencyInjection.put(obj.getClass(), obj.getClass());
                    } else {
                        createInstances(classes, Arrays.stream(method.getParameterTypes()).collect(Collectors.toList()));
                        // TODO
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Could not create instance: {}", e.getMessage());
            }
        } catch (Exception e) {
            LOGGER.error("Could not create instance: {}", e.getMessage());
        }
    }

    private void createInstances(Class<?>[] classes, Collection<Class<?>> types) throws InstantiationException, IllegalAccessException {
        for (Class<?> implementationClass : types) {
            Class<?>[] interfaces = implementationClass.getInterfaces();
            if (interfaces.length == 0) {
                dependencyInjection.put(implementationClass, implementationClass);
            } else {
                for (Class<?> iface : interfaces) {
                    dependencyInjection.put(implementationClass, iface);
                }
            }
        }
        for (Class<?> clazz : classes) {
            if (clazz.isAnnotationPresent(MuComponent.class)) {
                Object classInstance = clazz.newInstance();
                applicationContext.put(clazz, classInstance);
                InjectorUtil.autowire(this, clazz, classInstance);
            }
        }
    }

    private void createComponents(String packageRelativePath, Class<?>[] classes) {
        try (ClassCriteria classCriteria = ClassCriteria.create().allThoseThatMatch(cls -> cls.getAnnotation(MuComponent.class) != null);
             SearchConfig searchConfig = SearchConfig.forResources(packageRelativePath.replace(".", "/"))) {
            try (ClassHunter.SearchResult result = classHunter.findBy(
                searchConfig.by(classCriteria)
            )) {
                Collection<Class<?>> types = result.getClasses();
                createInstances(classes, types);
            } catch (Exception e) {
                LOGGER.error("Could not create instance: {}", e.getMessage());
            }
        } catch (Exception e) {
            LOGGER.error("Could not create instance: {}", e.getMessage());
        }
    }

    public Class<?>[] getAllClasses(String packageName, boolean recursive) {
        // get main class package
        String packageRelativePath = packageName.replace(".", "/");
        SearchConfig config = SearchConfig.forResources(
            packageRelativePath
        );

        if (!recursive) {
            config.findInChildren();
        }

        // find all classes from package
        try (ClassHunter.SearchResult result = classHunter.findBy(config)) {
            Collection<Class<?>> classes = result.getClasses();
            return classes.toArray(new Class[classes.size()]);
        }
    }


    @SuppressWarnings("unchecked")
    private <T> T getBeanInstance(Class<T> interfaceClass) throws InstantiationException, IllegalAccessException {
        return (T) getBeanInstance(interfaceClass, null, null);
    }

    public <T> Object getBeanInstance(Class<T> interfaceClass, String fieldName, String qualifier)
        throws InstantiationException, IllegalAccessException {
        Class<?> implementationClass = getImplimentationClass(interfaceClass, fieldName, qualifier);

        if (applicationContext.containsKey(implementationClass)) {
            return applicationContext.get(implementationClass);
        }

        synchronized (applicationContext) {
            Object service = implementationClass.newInstance();
            applicationContext.put(implementationClass, service);
            return service;
        }
    }


    private Class<?> getImplimentationClass(Class<?> interfaceClass, final String fieldName, final String qualifier) {
        Set<Entry<Class<?>, Class<?>>> implementationClasses = dependencyInjection.entrySet().stream()
            .filter(entry -> entry.getValue() == interfaceClass).collect(Collectors.toSet());
        String errorMessage = "";
        if (implementationClasses.isEmpty()) {
            errorMessage = "no implementation found for interface " + interfaceClass.getName();
        } else if (implementationClasses.size() == 1) {
            Optional<Entry<Class<?>, Class<?>>> optional = implementationClasses.stream().findFirst();
            return optional.get().getKey();
        } else {
            final String findBy = (qualifier == null || qualifier.trim().isEmpty()) ? fieldName : qualifier;
            Optional<Entry<Class<?>, Class<?>>> optional = implementationClasses.stream()
                .filter(entry -> entry.getKey().getSimpleName().equalsIgnoreCase(findBy)).findAny();
            if (optional.isPresent()) {
                return optional.get().getKey();
            } else {
                errorMessage = "There are " + implementationClasses.size() + " of interface " + interfaceClass.getName()
                    + " Expected single implementation or make use of @MuQualifier to resolve conflict";
            }
        }
        throw new RuntimeErrorException(new Error(errorMessage));
    }
}
