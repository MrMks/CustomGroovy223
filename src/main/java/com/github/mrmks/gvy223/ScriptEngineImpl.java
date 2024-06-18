package com.github.mrmks.gvy223;

import groovy.lang.*;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;
import org.codehaus.groovy.control.customizers.builder.CompilerCustomizationBuilder;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.codehaus.groovy.runtime.InvokerInvocationException;
import org.codehaus.groovy.runtime.MetaClassHelper;
import org.codehaus.groovy.runtime.MethodClosure;
import org.codehaus.groovy.vmplugin.VMPlugin;
import org.codehaus.groovy.vmplugin.VMPluginFactory;

import javax.script.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.PrivilegedAction;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ScriptEngineImpl extends AbstractScriptEngine implements Compilable, Invocable {

    // the global groovy script naming counter;
    private static final AtomicInteger counter = new AtomicInteger();

    private final ScriptEngineFactoryImpl factory;
    private final GroovyClassLoader loader;
    private final CompilerConfiguration config;

    private final ConcurrentHashMap<String, Class<?>> classMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MethodClosure> globalMethodMap = new ConcurrentHashMap<>();

    ScriptEngineImpl(ScriptEngineFactoryImpl factory) {
        this.factory = factory;
        this.config = new CompilerConfiguration(CompilerConfiguration.DEFAULT);
        this.loader = getClassLoader(config);
    }

    private static GroovyClassLoader getClassLoader(CompilerConfiguration config) {
        // this method is copied from groovy's official jsr223 implementation;
        PrivilegedAction<GroovyClassLoader> action = () -> new GroovyClassLoader(
                getParentLoader(), config
        );
        return java.security.AccessController.doPrivileged(action);
    }

    private static ClassLoader getParentLoader() {
        // by default, we use the thread's context classloader, if we can see the class Script
        ClassLoader parent = Thread.currentThread().getContextClassLoader();
        try {
            Class<?> klass = parent.loadClass(Script.class.getName());
            if (klass == Script.class) {
                // we can see the script class, and they are the same class;
                // we can invoke script methods from this class then.
                return parent;
            }
        } catch (ClassNotFoundException cnfe) {
            // do nothing, we can not use the thread's context classloader;
        }
        // in this case, we can only use the classloader of the script class;
        return Script.class.getClassLoader();
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

    private static String generateScriptName(ScriptContext context) {

        if (context == null)
            return generateScriptNameByCounter();

        Object obj = context.getAttribute(ScriptEngine.FILENAME, ScriptContext.ENGINE_SCOPE);
        if (obj == null)
            return generateScriptNameByCounter();

        String name = obj.toString();

        if (!name.endsWith(".groovy"))
            name = name + ".groovy";

        return name;
    }

    private static String generateScriptNameByCounter() {
        return "Script" + counter.getAndIncrement() + ".groovy";
    }

    private void updateCompilerCfg(ScriptContext ctx) {
        if (ctx == null)
            return;

        String attrHead = "#gvy223.groovy.compile.";

        Object obj = ctx.getAttribute(attrHead + "customizer");
        config.getCompilationCustomizers().clear();
        if (obj instanceof CompilationCustomizer)
            config.addCompilationCustomizers((CompilationCustomizer) obj);
        else if (obj instanceof CompilationCustomizer[])
            config.addCompilationCustomizers((CompilationCustomizer[]) obj);
        else if (obj instanceof List<?>) {
            CompilationCustomizer[] ary = ((List<?>) obj).stream()
                    .filter(it -> it instanceof CompilationCustomizer)
                    .map(it -> (CompilationCustomizer) it)
                    .toArray(CompilationCustomizer[]::new);

            config.addCompilationCustomizers(ary);
        }

        obj = ctx.getAttribute(attrHead + "script.base");
        config.setScriptBaseClass(CompilerConfiguration.DEFAULT.getScriptBaseClass());
        if (obj instanceof String)
            config.setScriptBaseClass(obj.toString());
    }

    private Class<?> getScriptClass(String script, ScriptContext context) throws ScriptException {
        Class<?> klass = classMap.get(script);
        if (klass != null)
            return klass;

        // update compileConfig using attributes in context;
        updateCompilerCfg(context);

        // here, we need to compile the script;
        try {
            klass = loader.parseClass(script, generateScriptName(context));
        } catch (CompilationFailedException cfe) {
            throw new ScriptException(cfe.getMessage());
        }
        classMap.put(script, klass);
        return klass;
    }

    private static Binding createGroovyBinding(ScriptContext context) {
        return new Binding(context.getBindings(ScriptContext.ENGINE_SCOPE)) {
            @Override
            public Object getVariable(String name) {
                synchronized (context) {
                    int scope = context.getAttributesScope(name);
                    if (scope != -1)
                        return context.getAttribute(name, scope);

                    if ("out".equals(name)) {
                        Writer writer = context.getWriter();
                        if (writer != null) {
                            return writer instanceof PrintWriter ?
                                    writer : new PrintWriter(writer, true);
                        }
                    }

                    // should we add a err short-cut?

                    if ("context".equals(name))
                        return context;
                }
                throw new MissingPropertyException(name, getClass());
            }

            @Override
            public void setVariable(String name, Object value) {
                synchronized (context) {
                    // we should set to content;
                    int scope = context.getAttributesScope(name);
                    if (scope == -1)
                        scope = ScriptContext.ENGINE_SCOPE;

                    context.setAttribute(name, value, scope);
                }
            }
        };
    }

    private void addMethodToGlobal(Script script, Method[] method) {
        for (Method me : method) {
            String name = me.getName();
            globalMethodMap.putIfAbsent(name, new MethodClosure(script, name));
        }
    }

    private Object callGlobalFromEngine(String name, Object[] args, ScriptContext ctx, Class<?> source) {
        MissingMethodException mme_ = null;
        Closure<?> closure = globalMethodMap.get(name);
        if (closure != null)
            try {
                return closure.getMetaClass().invokeMethod(closure, "doCall", args);
            } catch (MissingMethodException mme) {
                // in this case, they did not find a suitable method;
                // check context;
                mme_ = mme;
            }

        if (ctx != null) {
            Object obj = ctx.getAttribute(name);
            if (obj instanceof Closure<?>) try {
                return (closure = (Closure<?>) obj).getMetaClass().invokeMethod(closure, "doCall", args);
            } catch (MissingMethodException mme) {
                // check missingMethod;
                if (mme_ == null) mme_ = mme;
            }
        }

        // finally we were unable to determine a method to call;
        // throw a exception here;
        if (mme_ != null)
            throw mme_;
        throw new MissingMethodException(name, source, args);
    }

    private Object callGlobalFromMeta(String name, Object[] args, ScriptContext ctx, Class<?> source) {
        Closure<?> closure = globalMethodMap.get(name);
        MissingMethodException mme_ = null;
        if (closure != null)
            try {
                return closure.getMetaClass().invokeMethod(closure, "doCall", args);
            } catch (MissingMethodException mme) {
                // in this case, we can look up methods in ctx
                mme_ = mme;
            } catch (InvokerInvocationException iie) {
                throw (RuntimeException) iie.getCause();
            }

        if (ctx == null)
            throw mme_ != null ? mme_ : new MissingMethodException(name, source, args);

        Object obj = ctx.getAttribute(name);
        if (obj instanceof Closure<?>)
            return ((Closure<?>) obj).call(args);

        throw mme_ != null ? mme_ : new MissingMethodException(name, source, args);
    }

    private Object eval(Class<?> klass, ScriptContext context) throws ScriptException {

        if (!Script.class.isAssignableFrom(klass)) {
            return klass;
        }

        Binding binding = createGroovyBinding(context);
        Script script;
        try {
            script = InvokerHelper.newScript(klass.asSubclass(Script.class), binding);
        } catch (Exception e) {
            // if any exception, then we can not run this script;
            throw new ScriptException(e);
        }

        // make method closure?
        addMethodToGlobal(script, klass.getMethods());

        MetaClass metaClass = new DelegatingMetaClass(script.getMetaClass()){
            @Override
            public Object invokeMethod(Object object, String methodName, Object arguments) {
                // copied from MetaClassImpl
                if (arguments == null) {
                    return invokeMethod(object, methodName, MetaClassHelper.EMPTY_ARRAY);
                }
                if (arguments instanceof Tuple) {
                    return invokeMethod(object, methodName, ((Tuple<?>) arguments).toArray());
                }
                if (arguments instanceof Object[]) {
                    return invokeMethod(object, methodName, (Object[]) arguments);
                }
                return invokeMethod(object, methodName, new Object[]{arguments});
            }

            @Override
            public Object invokeMethod(Object object, String methodName, Object[] arguments) {
                try {
                    return super.invokeMethod(object, methodName, arguments);
                } catch (MissingMethodException mme) {
                    return callGlobalFromMeta(methodName, arguments, context, klass);
                }
            }

            @Override
            public Object invokeStaticMethod(Object object, String methodName, Object[] arguments) {
                // we do not delegate this methods, since they are not static methods;
                return super.invokeStaticMethod(object, methodName, arguments);
            }
        };
        script.setMetaClass(metaClass);

        return script.run();
    }

    @Override
    public Object eval(String script, ScriptContext context) throws ScriptException {
        // here, in official groovy jsr223 implementation, the entry of the map
        // may be soft, weak, phantom or hard referenced, but in this implementation,
        // we only support hard-ref;

        Class<?> klass = getScriptClass(script, context);
        if (klass == null)
            throw new ScriptException("Script class is null");

        return eval(klass, context);
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
        Class<?> klass = getScriptClass(script, context);
        return new CompiledScript() {
            @Override
            public Object eval(ScriptContext context) throws ScriptException {
                return ScriptEngineImpl.this.eval(klass, context);
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

    private Object invokeImpl(Object thiz, String name, Object... args) throws ScriptException, NoSuchMethodException {
        if (name == null)
            throw new NullPointerException("method name is null!");

        try {
            if (thiz == null)
                return callGlobalFromEngine(name, args, context, getClass());
            else
                return InvokerHelper.invokeMethod(thiz, name, args);
        } catch (MissingMethodException mme) {
            // real missing method exception;
            throw new NoSuchMethodException(mme.getMessage());
        } catch (Exception e) {
            Exception ex = e;
            if (ex instanceof InvokerInvocationException)
                ex = (Exception) ex.getCause();

            if (ex instanceof RuntimeException)
                throw (RuntimeException) ex;
            else
                throw new ScriptException(ex) {
                    @Override
                    public synchronized Throwable fillInStackTrace() {
                        return this;
                    }
                };
        }
    }

    @Override
    public Object invokeMethod(Object thiz, String name, Object... args) throws ScriptException, NoSuchMethodException {
        if (thiz == null)
            throw new NullPointerException("thiz can't be null");

        return invokeImpl(thiz, name, args);
    }

    @Override
    public Object invokeFunction(String name, Object... args) throws ScriptException, NoSuchMethodException {
        return invokeImpl(null, name, args);
    }

    private Object interfaceInvokeImpl(Object thiz, Object proxy, Method method, Object[] args) throws Throwable {
        if (method == null)
            throw new RuntimeException("method is null, which should never happen");

        try {
            if (thiz == null)
                return callGlobalFromEngine(method.getName(), args, context, getClass());
            else
                return InvokerHelper.invokeMethod(thiz, method.getName(), args);
        } catch (MissingMethodException re) {

            if (method.isDefault()) {

                VMPlugin PLUG = VMPluginFactory.getPlugin();

                Object mh = PLUG.getInvokeSpecialHandle(method, proxy);
                if (mh != null)
                    return PLUG.invokeHandle(mh, args);
            }

            throw new NoSuchMethodException(re.getMessage());
        } catch (InvokerInvocationException iie) {
            throw iie.getCause();
        }
    }

    private <T> T makeInterface(Object obj, Class<T> clazz) {
        final Object thiz = obj;
        if (clazz == null || !clazz.isInterface()) {
            throw new IllegalArgumentException("interface Class expected");
        }

        //noinspection unchecked
        return (T) Proxy.newProxyInstance(
                loader,
                new Class[]{clazz},
                (proxy, method, args) -> interfaceInvokeImpl(thiz, proxy, method, args)
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
