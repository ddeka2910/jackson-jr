package com.fasterxml.jackson.jr.ob.impl;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.JsonTokenId;
import com.fasterxml.jackson.core.TokenStreamFactory;
import com.fasterxml.jackson.core.sym.FieldNameMatcher;
import com.fasterxml.jackson.core.util.Named;
import com.fasterxml.jackson.jr.ob.JSON;
import com.fasterxml.jackson.jr.ob.JSONObjectException;
import com.fasterxml.jackson.jr.ob.api.ValueReader;

/**
 * Class that contains information about dynamically introspected
 * Bean types, to be able to deserialize (read) JSON into a POJO
 * and serialize (write) POJO as JSON.
 */
public class BeanReader
    extends ValueReader // so we can chain calls for Collections, arrays
{
    protected final Map<String, BeanPropertyReader> _propsByName;

    /**
     * Mapping of aliased names to primary names (direct linkage unfortunately
     * impractical due to implementation limits).
     */
    protected final Map<String, String> _aliasMapping;

    protected final Set<String> _ignorableNames;

    protected final Constructor<?> _defaultCtor;
    protected final Constructor<?> _stringCtor;
    protected final Constructor<?> _longCtor;

    // // 13-Dec-2017, tatu: NOTE! These will be constructed right after construction, but
    // //    not during it (due to need to resolve possible cyclic deps). So they are
    // //    non-final due to this but never `null` before use.

    protected FieldNameMatcher _fieldMatcher;
    protected BeanPropertyReader[] _fieldReaders;

    /**
     * Constructors used for deserialization use case
     */
    protected BeanReader(Class<?> type, Map<String,BeanPropertyReader> propsByName,
            Constructor<?> defaultCtor, Constructor<?> stringCtor, Constructor<?> longCtor,
            Set<String> ignorableNames, Map<String, String> aliasMapping)
    {
        super(type);
        _propsByName = propsByName;
        _defaultCtor = defaultCtor;
        _stringCtor = stringCtor;
        _longCtor = longCtor;
        _ignorableNames = ignorableNames;
        _aliasMapping = aliasMapping;
    }

    /**
     * Initialization method called after construction and resolution of all property
     * readers: separate since caller needs to handle resolution of cyclic dependencies.
     */
    protected void initFieldMatcher(TokenStreamFactory streamFactory)
    {
        // 26-Jan-2020, tatu: One complication are aliases, if any
        Map<String,BeanPropertyReader> byName = _aliasMapping.isEmpty()
                ? _propsByName : _mixInAliases(_propsByName, _aliasMapping);

        final int size = byName.size();
        List<Named> names = new ArrayList<>(size);
        _fieldReaders = new BeanPropertyReader[size];
        int ix = 0;
        for (Map.Entry<String, BeanPropertyReader> entry : byName.entrySet()) {
            names.add(Named.fromString(entry.getKey()));
            _fieldReaders[ix++] = entry.getValue();
        }
        // 13-Dec-2017, tatu: We could relatively easily support case-insensitive matching,
        //    except for one problem: when we cache readers we cache matcher... so would
        //    need to figure out what to do with that -- can not support dynamic change
        //    easily.
        /*
        if (_caseInsensitive) {
            _fieldMatcher = streamFactory.constructCIFieldNameMatcher(names, true);
        } else {
            _fieldMatcher = streamFactory.constructFieldNameMatcher(names, true);
        }
        */
        _fieldMatcher = streamFactory.constructFieldNameMatcher(names, true);
    }

    private final Map<String,BeanPropertyReader> _mixInAliases(Map<String,BeanPropertyReader> props,
            Map<String,String> aliases)
    {
        Map<String,BeanPropertyReader> result = new LinkedHashMap<String,BeanPropertyReader>(props);
        for (Map.Entry<String, String> entry : aliases.entrySet()) {
            final String alias = entry.getKey();
            final String mappedTo = entry.getValue();

            // First: can not override primary name:
            if (!props.containsKey(alias)) {
                final BeanPropertyReader prop = props.get(mappedTo);
                if (prop != null) { // not sure if mismatch even allowed but...
                    result.put(alias, prop);
                }
            }
        }
        return result;
    }

    /**
     * @since 3.0
     */
    public static BeanReader construct(Class<?> type, Map<String, BeanPropertyReader> props,
            Constructor<?> defaultCtor, Constructor<?> stringCtor, Constructor<?> longCtor,
            Set<String> ignorableProps, Map<String, String> aliasMapping)
    {
        if (ignorableProps == null) {
            ignorableProps = Collections.<String>emptySet();
        }
        if (aliasMapping == null) {
            aliasMapping = Collections.emptyMap();
        }
        return new BeanReader(type, props, defaultCtor, stringCtor, longCtor,
                ignorableProps, aliasMapping);
    }

    public Map<String,BeanPropertyReader> propertiesByName() { return _propsByName; }

    public BeanPropertyReader findProperty(String name) {
        return _propsByName.get(name);
    }

    /**
     * Method used for deserialization; will read an instance of the bean
     * type using given parser.
     */
    @Override
    public Object read(JSONReader r, JsonParser p) throws IOException
    {
        if (p.isExpectedStartObjectToken()) {
            final Object bean;
            try {
                bean = create();
            } catch (Exception e) {
                return _reportFailureToCreate(p, e);
            }
            p.setCurrentValue(bean);
            return _readBean(r, p, bean);
        }
        try {
            switch (p.currentTokenId()) {
            case JsonTokenId.ID_NULL:
                return null;
            case JsonTokenId.ID_STRING:
                return create(p.getText());
            case JsonTokenId.ID_NUMBER_INT:
                return create(p.getLongValue());
            default:
            }
        } catch (Exception e) {
            return _reportFailureToCreate(p, e);
        }
        throw JSONObjectException.from(p, "Can not create a %s instance out of %s",
                _valueType.getName(), _tokenDesc(p));
    }

    @Override
    public Object readNext(JSONReader r, JsonParser p) throws IOException
    {
        JsonToken t = p.nextToken();
        if (t == JsonToken.START_OBJECT) {
            final Object bean;
            try {
                bean = create();
            } catch (Exception e) {
                return _reportFailureToCreate(p, e);
            }
            p.setCurrentValue(bean);
            return _readBean(r, p, bean);
        }
        if (t != null) {
            try {
                switch (t) {
                case VALUE_NULL:
                    return null;
                case VALUE_STRING:
                    return create(p.getText());
                case VALUE_NUMBER_INT:
                    return create(p.getLongValue());
                default:
                }
            } catch (Exception e) {
                return _reportFailureToCreate(p, e);
            }
        }
        throw JSONObjectException.from(p,"Can not create a %s instance out of %s",
                _valueType.getName(), _tokenDesc(p));
    }

    private final Object _readBean(JSONReader r, JsonParser p, final Object bean) throws IOException
    {
        // 13-Dec-2017, tatu: Unrolling is unpredictable business, and 
        //     performance does not seem linear. In fact, choices of 2 or 8 unrolls
        //     seem to have about same performance for our test (but in between less... :) )
        int ix = p.nextFieldName(_fieldMatcher);
        final BeanPropertyReader[] readers = _fieldReaders;
        while (ix >= 0) {
            BeanPropertyReader prop = readers[ix]; // elem #1
            Object value = prop.getReader().readNext(r, p);
            prop.setValueFor(bean, value);

            if ((ix = p.nextFieldName(_fieldMatcher)) < 0) break;
            prop = readers[ix]; // elem #2
            value = prop.getReader().readNext(r, p);
            prop.setValueFor(bean, value);

/*
            if ((ix = p.nextFieldName(_fieldMatcher)) < 0) break;
            prop = readers[ix]; // elem #3
            value = prop.getReader().readNext(r, p);
            prop.setValueFor(bean, value);

            if ((ix = p.nextFieldName(_fieldMatcher)) < 0) break;
            prop = readers[ix]; // elem #4
            value = prop.getReader().readNext(r, p);
            prop.setValueFor(bean, value);

            if ((ix = p.nextFieldName(_fieldMatcher)) < 0) break;
            prop = readers[ix]; // elem #5
            value = prop.getReader().readNext(r, p);
            prop.setValueFor(bean, value);

            if ((ix = p.nextFieldName(_fieldMatcher)) < 0) break;
            prop = readers[ix]; // elem #6
            value = prop.getReader().readNext(r, p);
            prop.setValueFor(bean, value);

            if ((ix = p.nextFieldName(_fieldMatcher)) < 0) break;
            prop = readers[ix]; // elem #7
            value = prop.getReader().readNext(r, p);
            prop.setValueFor(bean, value);

            if ((ix = p.nextFieldName(_fieldMatcher)) < 0) break;
            prop = readers[ix]; // elem #8
            value = prop.getReader().readNext(r, p);
            prop.setValueFor(bean, value);
*/
            // and then for next loop
            ix = p.nextFieldName(_fieldMatcher);
        }

        if (ix != FieldNameMatcher.MATCH_END_OBJECT) {
            if (ix == FieldNameMatcher.MATCH_UNKNOWN_NAME) {
                return _readWithUnknown(r, p, bean, p.currentName());
            }
            throw _reportProblem(p);
        }
        return bean;
    }

    /*
    private final Object _readBean(JSONReader r, JsonParser p, final Object bean) throws IOException
    {
        String propName;
        BeanPropertyReader prop;

        // 29-Oct-2017, tatu: Performance here seems to vary in... mysterious
        //   ways. This setup appears best commonly, although not reliably.
        // Unroll first 6 rounds (similar to databind)
        do {
            // Element 1
            if ((propName = p.nextFieldName()) == null) { break; }
            if ((prop = findProperty(propName)) == null) {
                return _readWithUnknown(r, p, bean, propName);
            }
            prop.setValueFor(bean, prop.getReader().readNext(r, p));

            // Element 2
            if ((propName = p.nextFieldName()) == null) { break; }
            if ((prop = findProperty(propName)) == null) {
                return _readWithUnknown(r, p, bean, propName);
            }
            prop.setValueFor(bean, prop.getReader().readNext(r, p));

            // Element 3
            if ((propName = p.nextFieldName()) == null) { break; }
            if ((prop = findProperty(propName)) == null) {
                return _readWithUnknown(r, p, bean, propName);
            }
            prop.setValueFor(bean, prop.getReader().readNext(r, p));

            // Element 4
            if ((propName = p.nextFieldName()) == null) { break; }
            if ((prop = findProperty(propName)) == null) {
                return _readWithUnknown(r, p, bean, propName);
            }
            prop.setValueFor(bean, prop.getReader().readNext(r, p));

            // Element 5
            if ((propName = p.nextFieldName()) == null) { break; }
            if ((prop = findProperty(propName)) == null) {
                return _readWithUnknown(r, p, bean, propName);
            }
            prop.setValueFor(bean, prop.getReader().readNext(r, p));

            // Element 6
            if ((propName = p.nextFieldName()) == null) { break; }
            if ((prop = findProperty(propName)) == null) {
                return _readWithUnknown(r, p, bean, propName);
            }
            prop.setValueFor(bean, prop.getReader().readNext(r, p));

            // and if more, just loop
            for (; (propName = p.nextFieldName()) != null; ) {
                prop = findProperty(propName);
                if (prop == null) {
                    handleUnknown(r, p, propName);
                    continue;
                }
                prop.setValueFor(bean, prop.getReader().readNext(r, p));
            }
        } while (false);
        // also verify we are not confused...
        if (!p.hasToken(JsonToken.END_OBJECT)) {
            throw _reportProblem(p);
        }                    
        
        return bean;
    }
    */

    private final Object _readWithUnknown(JSONReader r, JsonParser p,
            final Object bean, String propName)
        throws IOException
    {
        // first, skip current property
        handleUnknown(r, p, propName);
        // then do the rest with looping
        for (; (propName = p.nextFieldName()) != null; ) {
            BeanPropertyReader prop = findProperty(propName);
            if (prop == null) {
                handleUnknown(r, p, propName);
                continue;
            }
            prop.setValueFor(bean, prop.getReader().readNext(r, p));
        }
        if (!p.hasToken(JsonToken.END_OBJECT)) {
            throw _reportProblem(p);
        }                    
        
        return bean;
    }

    protected Object create() throws Exception {
        if (_defaultCtor == null) {
            throw new IllegalStateException("Class `"+_valueType.getName()+"` does not have default constructor to use");
        }
        return _defaultCtor.newInstance();
    }
    
    protected Object create(String str) throws Exception {
        if (_stringCtor == null) {
            throw new IllegalStateException("Class `"+_valueType.getName()+"` does not have single-String constructor to use");
        }
        return _stringCtor.newInstance(str);
    }

    protected Object create(long l) throws Exception {
        if (_longCtor == null) {
            throw new IllegalStateException("Class `"+_valueType.getName()+"` does not have single-long constructor to use");
        }
        return _longCtor.newInstance(l);
    }

    protected void handleUnknown(JSONReader reader, JsonParser parser, String fieldName) throws IOException {
        if (JSON.Feature.FAIL_ON_UNKNOWN_BEAN_PROPERTY.isEnabled(reader._features)) {
            // 20-Jan-2020, tatu: With optional annotation support, may have "known ignorable"
            //    that usually should behave as if safely ignorable
            if (!_ignorableNames.contains(fieldName)) {
                final StringBuilder sb = new StringBuilder(60);
                Iterator<String> it = new TreeSet<String>(_propsByName.keySet()).iterator();
                if (it.hasNext()) {
                    sb.append('"').append(it.next()).append('"');
                    while (it.hasNext()) {
                        sb.append(", \"").append(it.next()).append('"');
                    }
                }
                throw JSONObjectException.from(parser,
"Unrecognized JSON property \"%s\" for Bean type `%s` (known properties: [%s])",
                        fieldName, _valueType.getName(), sb.toString());
            }
        }
        parser.nextToken();
        parser.skipChildren();
    }

    protected Object _reportFailureToCreate(JsonParser p, Exception e) throws IOException
    {
        if (e instanceof IOException) {
            throw (IOException) e;
        }
        throw JSONObjectException.from(p, e,
                "Failed to create an instance of `%s` due to (%s): %s",
                _valueType.getName(), e.getClass().getName(), e.getMessage());
    }

    protected IOException _reportProblem(JsonParser p) {
        return JSONObjectException.from(p, "Unexpected token %s; should get FIELD_NAME or END_OBJECT",
                p.currentToken());
    }
}
