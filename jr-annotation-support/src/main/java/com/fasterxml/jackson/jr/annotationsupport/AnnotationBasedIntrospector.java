package com.fasterxml.jackson.jr.annotationsupport;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.fasterxml.jackson.jr.ob.impl.JSONReader;
import com.fasterxml.jackson.jr.ob.impl.JSONWriter;
import com.fasterxml.jackson.jr.ob.impl.POJODefinition;

/**
 *
 * @since 2.11
 */
public class AnnotationBasedIntrospector
{
    protected final Class<?> _type;

    protected final boolean _forSerialization;
    
    protected final Map<String, APropBuilder> _props = new HashMap<String, APropBuilder>();

    // Only for deserialization
    protected Set<String> _toIgnore;
    
    protected AnnotationBasedIntrospector(Class<?> type, boolean serialization) {
        _type = type;
        _forSerialization = serialization;
        _toIgnore = serialization ? null : new HashSet<String>();
    }

    public static POJODefinition pojoDefinitionForDeserialization(JSONReader r, Class<?> pojoType) {
        return new AnnotationBasedIntrospector(pojoType, false).introspectDefinition();
    }

    public static POJODefinition pojoDefinitionForSerialization(JSONWriter w, Class<?> pojoType) {
        return new AnnotationBasedIntrospector(pojoType, true).introspectDefinition();
    }

    /*
    /**********************************************************************
    /* Construction
    /**********************************************************************
     */

    protected POJODefinition introspectDefinition() {

        // and then find necessary constructors
        Constructor<?> defaultCtor = null;
        Constructor<?> stringCtor = null;
        Constructor<?> longCtor = null;

        if (!_forSerialization) {
            for (Constructor<?> ctor : _type.getDeclaredConstructors()) {
                Class<?>[] argTypes = ctor.getParameterTypes();
                if (argTypes.length == 0) {
                    defaultCtor = ctor;
                } else if (argTypes.length == 1) {
                    Class<?> argType = argTypes[0];
                    if (argType == String.class) {
                        stringCtor = ctor;
                    } else if (argType == Long.class || argType == Long.TYPE) {
                        longCtor = ctor;
                    } else {
                        continue;
                    }
                } else {
                    continue;
                }
            }
        }

        _findFields();
        _findMethods();

        return new POJODefinition(_type, _pruneReadProperties(),
                defaultCtor, stringCtor, longCtor);
    }

    /*
    /**********************************************************************
    /* Internal methods, main introspection
    /**********************************************************************
     */

    protected POJODefinition.Prop[] _pruneReadProperties()
    {
        // First round: entry removal, collections of things to rename
        List<APropBuilder> renamed = null;
        Iterator<APropBuilder> it = _props.values().iterator();
        while (it.hasNext()) {
            final APropBuilder prop = it.next();

            if (!prop.anyVisible()) { // if nothing visible, just remove altogether
                it.remove();
                continue;
            }
            if (prop.anyIgnorals()) {
                // if one or more ignorals, and no explicit markers, remove the whole thing
                if (!prop.anyExplicit()) {
                    it.remove();
                    _addIgnoral(prop.name);
                } else {
                    // otherwise just remove ones marked to be ignored
                    prop.removeIgnored();
                    if (!prop.couldDeserialize()) {
                        _addIgnoral(prop.name);
                    }
                }
                continue;
            }
            // plus then remove non-visible accessors
            prop.removeNonVisible();

            // and finally, see if renaming (due to explicit name override) needed:
            String explName = prop.findPrimaryExplicitName(_forSerialization);
            if (explName != null) {
                it.remove();
                if (renamed == null) {
                    renamed = new LinkedList<APropBuilder>();
                }
                renamed.add(prop.withName(explName));
            }
        }

        // If (but only if) renamings needed, re-process
        if (renamed != null) {
            for (APropBuilder prop : renamed) {
                APropBuilder orig = _props.get(prop.name);
                if (orig == null) { // Straight rename, no merge
                    _props.put(prop.name, prop);
                    continue;
                }
                APropBuilder merged = APropBuilder.merge(orig, prop);
                _props.put(prop.name, merged);
            }
        }

        
        // For now, order alphabetically (natural order by name)
        List<APropBuilder> sorted = new ArrayList<APropBuilder>(_props.values());
        Collections.sort(sorted);
        final int len = sorted.size();
        POJODefinition.Prop[] result = new POJODefinition.Prop[len];
        for (int i = 0; i < len; ++i) {
            result[i] = sorted.get(i).asProperty();
        }
        return result;
    }

    protected void _findFields() {
        for (Field f : _type.getDeclaredFields()) {
            // Does not include static fields, but there are couple of things we do
            // not include regardless:
            if (f.isEnumConstant() || f.isSynthetic()) {
                continue;
            }
            // otherwise, first things first; explicit ignoral?
            final String implName = f.getName();
            APropAccessor<Field> acc;

            if (Boolean.TRUE.equals(_hasIgnoreMarker(f))) {
                acc = APropAccessor.createIgnorable(implName, f);
            } else {
                final String explName = _findExplicitName(f);
                // Otherwise, do have explicit inclusion marker?
                if (explName != null) {
                    // ... with actual name?
                    if (explName.isEmpty()) { // `@JsonProperty("")`
                        acc = APropAccessor.createVisible(implName, f);
                    } else {
                        acc = APropAccessor.createExplicit(explName, f);
                    }
                } else {
                    // Otherwise may be visible
                    acc = APropAccessor.createImplicit(explName, f,
                            _isFieldVisible(f));
                }
            }
            _propBuilder(implName).field = acc;

        }
    }

    protected void _findMethods() {
        _findMethods(_type);
    }

    protected void _findMethods(final Class<?> currType)
    {
        if (currType == null || currType == Object.class) {
            return;
        }
        // Start with base type methods (so overrides work)
        _findMethods(currType.getSuperclass());

        // then get methods from within this class
        for (Method m : currType.getDeclaredMethods()) {
            final int flags = m.getModifiers();
            // 13-Jun-2015, tatu: Skip synthetic, bridge methods altogether, for now
            //    at least (add more complex handling only if absolutely necessary)
            if (Modifier.isStatic(flags)
                    || m.isSynthetic() || m.isBridge()) {
                continue;
            }
            int argCount = m.getParameterCount();
            if (argCount == 0) { // getters (including 'any getter')
                _checkGetterMethod(m);
            } else if (argCount == 1) { // setters
                _checkSetterMethod(m);
            }
        }
    }

    protected void _checkGetterMethod(Method m)
    {
        Class<?> resultType = m.getReturnType();
        if (resultType == Void.class) {
            return;
        }
        final String name0 = m.getName();
        String implName = null;

        if (name0.startsWith("get")) {
            if (name0.length() > 3) {
                implName = _decap(name0.substring(3));
            }
        } else if (name0.startsWith("is")) {
            if (name0.length() > 2) {
                // May or may not be used, but collect for now all the same:
                implName = _decap(name0.substring(2));
            }
        }

        APropAccessor<Method> acc;
        if (implName == null) { // does not follow naming convention; needs explicit
            final String explName = _findExplicitName(m);
            if (explName == null) {
                return;
            }
            implName = name0;

            // But let's first see if there is ignoral
            if (Boolean.TRUE.equals(_hasIgnoreMarker(m))) {
                // could just bail out as is, at this point? But there is explicit marker
                acc = APropAccessor.createIgnorable(implName, m);
            } else {
                if (explName.isEmpty()) {
                    acc = APropAccessor.createVisible(implName, m);
                } else {
                    acc = APropAccessor.createExplicit(explName, m);
                }
            }
        } else { // implicit name already, but ignoral/explicit?
            if (Boolean.TRUE.equals(_hasIgnoreMarker(m))) {
                acc = APropAccessor.createIgnorable(implName, m);
            } else {
                final String explName = _findExplicitName(m);
                if (explName == null) {
                    acc = APropAccessor.createImplicit(implName, m,
                            _isGetterVisible(m));
                } else if (explName.isEmpty()) {
                    acc = APropAccessor.createVisible(implName, m);
                } else {
                    acc = APropAccessor.createExplicit(explName, m);
                }                    
            }
        }
        _propBuilder(implName).getter = acc;
    }

    protected void _checkSetterMethod(Method m)
    {
        final String name0 = m.getName();
        String implName;

        if (name0.startsWith("set") && (name0.length() > 3)) {
            implName = _decap(name0.substring(3));
        } else {
            implName = null;
        }

        // Pretty much the same as with getters (just calls to couple of diff methods)
        APropAccessor<Method> acc;
        if (implName == null) {
            final String explName = _findExplicitName(m);
            if (explName == null) {
                return;
            }
            implName = name0;

            if (Boolean.TRUE.equals(_hasIgnoreMarker(m))) {
                acc = APropAccessor.createIgnorable(implName, m);
            } else {
                if (explName.isEmpty()) {
                    acc = APropAccessor.createVisible(implName, m);
                } else {
                    acc = APropAccessor.createExplicit(explName, m);
                }
            }
        } else {
            if (Boolean.TRUE.equals(_hasIgnoreMarker(m))) {
                acc = APropAccessor.createIgnorable(implName, m);
            } else {
                final String explName = _findExplicitName(m);
                if (explName == null) {
                    acc = APropAccessor.createImplicit(implName, m,
                            _isSetterVisible(m));
                } else if (explName.isEmpty()) {
                    acc = APropAccessor.createVisible(implName, m);
                } else {
                    acc = APropAccessor.createExplicit(explName, m);
                }                    
            }
        }
        _propBuilder(implName).setter = acc;
    }

    /*
    /**********************************************************************
    /* Internal methods, visibility
    /**********************************************************************
     */

    protected boolean _isFieldVisible(Field f) {
        final int flags = f.getModifiers();
        return !Modifier.isTransient(flags)
                && Modifier.isPublic(f.getModifiers());
    }

    protected boolean _isGetterVisible(Method m) {
        return Modifier.isPublic(m.getModifiers());
    }
 
    protected boolean _isSetterVisible(Method m) {
        return Modifier.isPublic(m.getModifiers());
    }
    
    /*
    /**********************************************************************
    /* Internal methods, annotation introspection
    /**********************************************************************
     */

    // wrapper type just in case in future we want to detect existence of disables
    // ignoral marker for some reason
    protected Boolean _hasIgnoreMarker(AnnotatedElement m) {
        JsonIgnore ann = _find(m, JsonIgnore.class);
        return (ann != null) && ann.value();
    }

    protected final String _findExplicitName(AnnotatedElement m) {
        JsonProperty ann = _find(m, JsonProperty.class);
        return (ann == null) ? null : ann.value();
    }

    // Overridable accessor method
    protected <ANN extends Annotation> ANN _find(AnnotatedElement elem, Class<ANN> annotationType) {
        return elem.getAnnotation(annotationType);
    }
    
    /*
    /**********************************************************************
    /* Internal methods, other
    /**********************************************************************
     */
    
    protected APropBuilder _propBuilder(String name) {
        APropBuilder b = _props.get(name);
        if (b == null) {
            b = new APropBuilder(name);
            _props.put(name, b);
        }
        return b;
    }

    protected void _addIgnoral(String name) {
        if (_toIgnore != null) {
            _toIgnore.add(name);
        }
    }

    protected static String _decap(String name) {
        char c = name.charAt(0);
        char lowerC = Character.toLowerCase(c);

        if (c != lowerC) {
            // First: do NOT lower case if more than one leading upper case letters:
            if ((name.length() == 1)
                    || !Character.isUpperCase(name.charAt(1))) {
                char chars[] = name.toCharArray();
                chars[0] = lowerC;
                return new String(chars);
            }
        }
        return name;
    }

    /*
    /**********************************************************************
    /* Helper classes
    /**********************************************************************
     */
    
    protected static class APropBuilder
        implements Comparable<APropBuilder>
    {
        public final String name;

        protected APropAccessor<Field> field;
        protected APropAccessor<Method> getter;
        protected APropAccessor<Method> setter;

        public APropBuilder(String n) {
            name = n;
        }

        public POJODefinition.Prop asProperty() {
            return new POJODefinition.Prop(name,
                    (field == null) ? null : field.accessor,
                    (setter == null) ? null : setter.accessor,
                    (getter == null) ? null : getter.accessor,
                    /* isGetter */ null);
        }

        public static APropBuilder merge(APropBuilder b1, APropBuilder b2) {
            APropBuilder newB = new APropBuilder(b1.name);
            newB.field = _merge(b1.field, b2.field);
            newB.getter = _merge(b1.getter, b2.getter);
            newB.setter = _merge(b1.setter, b2.setter);
            return newB;
        }

        private static <A extends AccessibleObject> APropAccessor<A> _merge(APropAccessor<A> a1, APropAccessor<A> a2)
        {
            if (a1 == null) {
                return a2;
            }
            if (a2 == null) {
                return a1;
            }

            if (a1.isNameExplicit) {
                return a1;
            }
            if (a2.isNameExplicit) {
                return a2;
            }
            if (a1.isExplicit) {
                return a1;
            }
            if (a2.isExplicit) {
                return a2;
            }
            // Could try other things too (visibility, place in hierarchy) but... for now
            // should be fine to take first one
            return a1;
        }
        
        public APropBuilder withName(String newName) {
            APropBuilder newB = new APropBuilder(newName);
            newB.field = field;
            newB.getter = getter;
            newB.setter = setter;
            return newB;
        }

        public void removeIgnored() {
            if ((field != null) && field.isToIgnore) {
                field = null;
            }
            if ((getter != null) && getter.isToIgnore) {
                getter = null;
            }
            if ((setter != null) && setter.isToIgnore) {
                setter = null;
            }
        }

        public void removeNonVisible() {
            if ((field != null) && !field.isVisible) {
                field = null;
            }
            if ((getter != null) && !getter.isVisible) {
                getter = null;
            }
            if ((setter != null) && !setter.isVisible) {
                setter = null;
            }
        }

        public boolean couldDeserialize() {
            return (field != null) || (setter != null);
        }

        public String findPrimaryExplicitName(boolean forSer) {
            if (forSer) {
                return _firstExplicit(getter, setter, field);
            }
            return _firstExplicit(setter, getter, field);
        }

        private String _firstExplicit(APropAccessor<?> acc1,
                APropAccessor<?> acc2,
                APropAccessor<?> acc3) {
            if (acc1 != null && acc1.isNameExplicit) {
                return acc1.name;
            }
            if (acc2 != null && acc2.isNameExplicit) {
                return acc2.name;
            }
            if (acc3 != null && acc3.isNameExplicit) {
                return acc3.name;
            }
            return null;
        }

        public boolean anyVisible() {
            return ((field != null) && field.isVisible)
                    || ((getter != null) && getter.isVisible)
                    || ((setter != null) && setter.isVisible);
        }

        public boolean anyExplicit() {
            return ((field != null) && field.isExplicit)
                    || ((getter != null) && getter.isExplicit)
                    || ((setter != null) && setter.isExplicit);
        }

        public boolean anyIgnorals() {
            return ((field != null) && field.isToIgnore)
                    || ((getter != null) && getter.isToIgnore)
                    || ((setter != null) && setter.isToIgnore);
        }

        @Override
        public int compareTo(APropBuilder o) {
            return name.compareTo(o.name);
        }
    }

    protected static class APropAccessor<ACC extends AccessibleObject> {
        public final String name;
        public final ACC accessor;

        public final boolean isExplicit, isNameExplicit;
        public final boolean isToIgnore, isVisible;

        protected APropAccessor(String n, ACC acc,
                boolean expl, boolean nameExpl,
                boolean ignore, boolean visible)
        {
            name = n;
            accessor = acc;
            isExplicit = expl;
            isNameExplicit = nameExpl;
            isToIgnore = ignore;
            isVisible = visible;
        }

        // We saw `@JsonIgnore` and that's all we need
        public static <T extends AccessibleObject> APropAccessor<T> createIgnorable(String name, T accessor) {
            return new APropAccessor<T>(name, accessor,
                    false, false, true, false);
        }

        // We didn't saw any relevant annotation
        public static <T extends AccessibleObject> APropAccessor<T> createImplicit(String name, T accessor,
                boolean visible) {
            return new APropAccessor<T>(name, accessor,
                    false, false, false, visible);
        }

        // We only saw "empty" `@JsonProperty` (or similar marker)
        public static <T extends AccessibleObject> APropAccessor<T> createVisible(String name, T accessor) {
            return new APropAccessor<T>(name, accessor,
                    true, false, false, true);
        }

        // We saw `@JsonProperty` with non-empty name
        public static <T extends AccessibleObject> APropAccessor<T> createExplicit(String name, T accessor) {
            return new APropAccessor<T>(name, accessor,
                    true, true, false, true);
        }
    }
}
