package com.fasterxml.jackson.jr.ob.api;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.jr.ob.impl.JSONReader;

/**
 * Helper class used when reading values of complex types other
 * than Beans.
 *<p>
 * Note that ugly "chameleon" style operation here is used to avoid
 * creating multiple separate classes, which in turn is done to minimize
 * size of resulting jars.
 */
public abstract class ValueReader
{
    /**
     * Type of values this reader will read
     *
     * @since 2.10
     */
    protected final Class<?> _valueType;

    protected ValueReader(Class<?> valueType) {
        _valueType = valueType;
    }

    /*
    /**********************************************************************
    /* Basic API to implement for actual read operations
    /**********************************************************************
     */

    public abstract Object read(JSONReader reader, JsonParser p) throws IOException;

    /**
     * Method called to deserialize value of type supported by this reader, using
     * given parser. Parser is not yet positioned to the (first) token
     * of the value to read and needs to be advanced.
     *<p>
     * Default implementation simply calls `p.nextToken()` first, then calls
     * {#link {@link #read(JSONReader, JsonParser)}, but some implementations
     * may decide to implement this differently to use (slightly) more efficient
     * accessor in {@link JsonParser}, like {@link JsonParser#nextIntValue(int)}.
     *
     * @param reader Context object that allows calling other read methods for contained
     *     values of different types (for example for collection readers).
     * @param p Underlying parser used for reading decoded token stream
     */
    public Object readNext(JSONReader reader, JsonParser p) throws IOException {
        p.nextToken();
        return read(reader, p);
    }

    /*
    /**********************************************************************
    /* Minimal metadata
    /**********************************************************************
     */

    /**
     * Accessor for non-generic (type-erased) type of values this reader
     * produces from input.
     *
     * @since 2.10
     */
    public Class<?> valueType() {
        return _valueType;
    }

    /*
    /**********************************************************************
    /* Helper methods for sub-classes
    /**********************************************************************
     */

    public static String _tokenDesc(JsonParser p) throws IOException {
        return _tokenDesc(p, p.currentToken());
    }
    
    protected static String _tokenDesc(JsonParser p, JsonToken t) throws IOException {
        if (t == null) {
            return "NULL";
        }
        switch (t) {
        case FIELD_NAME:
            return "JSON Field name '"+p.currentName()+"'";
        case START_ARRAY:
            return "JSON Array";
        case START_OBJECT:
            return "JSON Object";
        case VALUE_FALSE:
            return "'false'";
        case VALUE_NULL:
            return "'null'";
        case VALUE_NUMBER_FLOAT:
        case VALUE_NUMBER_INT:
            return "JSON Number";
        case VALUE_STRING:
            return "JSON String";
        case VALUE_TRUE:
            return "'true'";
        default:
            return t.toString();
        }
    }
}
