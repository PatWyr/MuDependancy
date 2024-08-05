package io.muserver.dependancyinjection;

import io.muserver.dependancyinjection.annotation.MuAutowired;
import io.muserver.dependancyinjection.annotation.MuQualifier;
import org.burningwave.core.classes.FieldCriteria;

import java.lang.reflect.Field;
import java.util.Collection;

import static org.burningwave.core.assembler.StaticComponentContainer.Fields;

public class InjectorUtil {
    private InjectorUtil() {
        super();
    }
    public static void autowire(MuInjector muInjector, Class<?> clazz, Object classInstance)
        throws InstantiationException, IllegalAccessException {
        try(FieldCriteria fieldCriteria = FieldCriteria.forEntireClassHierarchy().allThoseThatMatch(field -> field.isAnnotationPresent(MuAutowired.class))) {
            Collection<Field> fields = Fields.findAllAndMakeThemAccessible(
                fieldCriteria,
                clazz
            );
            for (Field field : fields) {
                String qualifier = field.isAnnotationPresent(MuQualifier.class)
                    ? field.getAnnotation(MuQualifier.class).value()
                    : null;
                Object fieldInstance = muInjector.getBeanInstance(field.getType(), field.getName(), qualifier);
                Fields.setDirect(classInstance, field, fieldInstance);
                autowire(muInjector, fieldInstance.getClass(), fieldInstance);
            }
        }
    }
}
