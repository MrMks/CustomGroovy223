package com.github.mrmks.gvy223;

import org.codehaus.groovy.control.CompilerConfiguration;

import javax.script.*;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Proxy;

public class ScriptEngineImpl extends AbstractScriptEngine implements Compilable, Invocable {

    private final ScriptEngineFactoryImpl factory;
    private final GvyScriptCore core;
    private volatile ClassLoader proxyClassLoader;

    ScriptEngineImpl(ScriptEngineFactoryImpl factory) {
        this.factory = factory;
        this.core = new GvyScriptCore(new CompilerConfiguration(CompilerConfiguration.DEFAULT));
    }

    // If there are any exception while reading the reader, then
    // the exception will be thrown again in a ScriptException
    private static String readAll(Reader reader) throws ScriptException {
        char[] bytes = new char[8 * 1024];
        int num;
        StringBuilder builder = new StringBuilder();
        try {
            while ((num = reader.read(bytes)) > 0)
                builder.append(bytes, 0, num);
        } catch (IOException ioe) {
            throw new ScriptException(ioe);
        }

        return builder.toString();
    }

    @Override
    public Object eval(String script, ScriptContext context) throws ScriptException {
        // here, in official groovy jsr223 implementation, the entry of the map
        // may be soft, weak, phantom or hard referenced, but in this implementation,
        // we only support hard-ref;

        return core.eval(context, script);
    }

    @Override
    public Object eval(Reader reader, ScriptContext context) throws ScriptException {
        return eval(readAll(reader), context);
    }

    @Override
    public Bindings createBindings() {
        return new SimpleBindings();
    }

    @Override
    public ScriptEngineFactory getFactory() {
        return factory;
    }

    @Override
    public CompiledScript compile(String script) throws ScriptException {
        GvyScriptCore.Compiled compiled = core.compile(context, script);
        return new CompiledScript() {
            @Override
            public Object eval(ScriptContext context) throws ScriptException {
                return compiled.eval(context);
            }

            @Override
            public ScriptEngine getEngine() {
                return ScriptEngineImpl.this;
            }
        };
    }

    @Override
    public CompiledScript compile(Reader script) throws ScriptException {
        return compile(readAll(script));
    }

    @Override
    public Object invokeMethod(Object thiz, String name, Object... args) throws ScriptException, NoSuchMethodException {
        if (thiz == null)
            throw new NullPointerException("thiz can't be null");

        return core.invokeTop(context, thiz, name, args);
    }

    @Override
    public Object invokeFunction(String name, Object... args) throws ScriptException, NoSuchMethodException {
        return core.invokeTop(context, null, name, args);
    }

    private <T> T makeInterface(Object obj, Class<T> clazz) {
        final Object thiz = obj;
        if (clazz == null || !clazz.isInterface()) {
            throw new IllegalArgumentException("interface Class expected");
        }

        if (proxyClassLoader == null) {
            synchronized (this) {
                if (proxyClassLoader == null)
                    proxyClassLoader = core.makeProxyClassLoader();
            }
        }

        //noinspection unchecked
        return (T) Proxy.newProxyInstance(
                proxyClassLoader,
                new Class[]{clazz},
                (proxy, method, args) -> core.invokeProxy(context, thiz, proxy, method, args)
        );
    }

    @Override
    public <T> T getInterface(Class<T> clasz) {
        return makeInterface(null, clasz);
    }

    @Override
    public <T> T getInterface(Object thiz, Class<T> clasz) {
        if (thiz == null)
            throw new NullPointerException("thiz can not be null");

        return makeInterface(thiz, clasz);
    }

}
