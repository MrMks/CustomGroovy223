package com.github.mrmks.gvy223;

import groovy.lang.*;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.codehaus.groovy.runtime.InvokerInvocationException;
import org.codehaus.groovy.runtime.MetaClassHelper;
import org.codehaus.groovy.runtime.MethodClosure;
import org.codehaus.groovy.vmplugin.VMPlugin;
import org.codehaus.groovy.vmplugin.VMPluginFactory;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.io.PrintWriter;
import java.io.Writer;
import java.lang.reflect.Method;
import java.security.PrivilegedAction;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class GvyScriptCore {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private final CompilerConfiguration config;
    private final GroovyClassLoader loader;

    private final ConcurrentHashMap<String, Class<?>> classMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MethodClosure> globalMethodMap = new ConcurrentHashMap<>();

    public GvyScriptCore(CompilerConfiguration config) {
        this.config = config == null ? new CompilerConfiguration(CompilerConfiguration.DEFAULT) : config;
        this.loader = getClassLoader(this.config);
    }

    public GvyScriptCore() { this(null); }

    public Object eval(ScriptContext ctx, String script) throws ScriptException {
        // here, in official groovy jsr223 implementation, the entry of the map
        // may be soft, weak, phantom or hard referenced, but in this implementation,
        // we only support hard-ref;

        Class<?> klass = getScriptClass(script, ctx);
        if (klass == null)
            throw new ScriptException("Script class is null");

        return eval(klass, ctx);
    }

    public interface Compiled { Object eval(ScriptContext ctx) throws ScriptException; }
    public Compiled compile(ScriptContext ctx, String text) throws ScriptException {
        final Class<?> klass = getScriptClass(text, ctx);
        return context -> GvyScriptCore.this.eval(klass, context);
    }

    public Object invokeTop(ScriptContext context, Object thiz, String name, Object[] args) throws ScriptException, NoSuchMethodException {
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

    public Object invokeProxy(ScriptContext context, Object thiz, Object proxy, Method method, Object[] args) throws Throwable {
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

    // ===
    // internal methods
    // ===

    // methods used to initialization;
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

    // methods used to eval a class;
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
        return "Script" + COUNTER.getAndIncrement() + ".groovy";
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

    private void addMethodToGlobal(Script script, Method[] method) {
        for (Method me : method) {
            String name = me.getName();
            globalMethodMap.putIfAbsent(name, new MethodClosure(script, name));
        }
    }

    private Object eval(Class<?> klass, ScriptContext context) throws ScriptException {

        if (!Script.class.isAssignableFrom(klass)) {
            return klass;
        }

        Binding binding = new InEngineBinding(context);
        Script script;
        try {
            script = InvokerHelper.newScript(klass.asSubclass(Script.class), binding);
        } catch (Exception e) {
            // if any exception, then we can not run this script;
            throw new ScriptException(e);
        }

        // make method closure?
        addMethodToGlobal(script, klass.getMethods());

        // update delegate meta class to invoke method in this engine context;
        script.setMetaClass(new InEngineMetaClass(script.getMetaClass(), context));

        return script.run();
    }

    // methods used to invoke
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


    // internal classes;
    private final class InEngineMetaClass extends DelegatingMetaClass {
        private final ScriptContext context;
        InEngineMetaClass(MetaClass delegate, ScriptContext context) {
            super(delegate);
            this.context = context;
        }

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
                return callGlobalFromMeta(methodName, arguments, context, getTheClass());
            }
        }

        @Override
        public Object invokeStaticMethod(Object object, String methodName, Object[] arguments) {
            // we do not delegate this methods, since they are not static methods;
            return super.invokeStaticMethod(object, methodName, arguments);
        }
    }

    private static final class InEngineBinding extends Binding {

        private final ScriptContext ctx;
        InEngineBinding(ScriptContext ctx) {
            super(ctx.getBindings(ScriptContext.ENGINE_SCOPE));
            this.ctx = ctx;
        }

        @Override
        public Object getVariable(String name) {
            synchronized (ctx) {
                int scope = ctx.getAttributesScope(name);
                if (scope != -1)
                    return ctx.getAttribute(name, scope);

                if ("out".equals(name)) {
                    Writer writer = ctx.getWriter();
                    if (writer != null) {
                        return writer instanceof PrintWriter ?
                                writer : new PrintWriter(writer, true);
                    }
                }

                // should we add a err short-cut?

                if ("context".equals(name))
                    return ctx;
            }
            throw new MissingPropertyException(name, getClass());
        }

        @Override
        public void setVariable(String name, Object value) {
            synchronized (ctx) {
                // we should set to content;
                int scope = ctx.getAttributesScope(name);
                if (scope == -1)
                    scope = ScriptContext.ENGINE_SCOPE;

                ctx.setAttribute(name, value, scope);
            }
        }

    }
}
