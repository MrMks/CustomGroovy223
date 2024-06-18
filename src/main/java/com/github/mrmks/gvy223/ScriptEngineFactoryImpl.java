package com.github.mrmks.gvy223;

import groovy.lang.GroovySystem;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ScriptEngineFactoryImpl implements ScriptEngineFactory {

    private static final String VERSION = "0.0.1";
    private static final String NAME = "groovy";
    private static final String LANGUAGE_NAME = "Groovy";
    private static final List<String> EXTENSIONS = immutableList("groovy");
    private static final List<String> MIME_TYPES = immutableList("application/x-groovy");
    private static final List<String> NAMES = immutableList(NAME, LANGUAGE_NAME);

    @Override
    public String getEngineName() {
        return "MrMks 3td-party groovy script engine";
    }

    @Override
    public String getEngineVersion() {
        return VERSION;
    }

    @Override
    public List<String> getExtensions() {
        return EXTENSIONS;
    }

    @Override
    public List<String> getMimeTypes() {
        return MIME_TYPES;
    }

    @Override
    public List<String> getNames() {
        return NAMES;
    }

    @Override
    public String getLanguageName() {
        return LANGUAGE_NAME;
    }

    @Override
    public String getLanguageVersion() {
        return GroovySystem.getVersion();
    }

    @Override
    public Object getParameter(String key) {
        switch (key) {
            case ScriptEngine.ENGINE:
                return getEngineName();
            case ScriptEngine.ENGINE_VERSION:
                return getEngineVersion();
            case ScriptEngine.LANGUAGE:
                return getLanguageName();
            case ScriptEngine.LANGUAGE_VERSION:
                return getLanguageVersion();
            case ScriptEngine.NAME:
                return NAME;
            case "THREADING":
                return "MULTITHREADED";
            default:
                throw new IllegalArgumentException("Invalid key");
        }
    }

    @Override
    public String getMethodCallSyntax(String obj, String m, String... args) {
        StringBuilder bd = new StringBuilder(obj.length() + m.length() + args.length * 8 + 4);
        bd.append(obj).append('.').append(m).append('(');

        int len = args.length;
        if (len == 0) {
            bd.append(')');
            return bd.toString();
        }

        for (int i = 0; i < len - 1; i++) {
            bd.append(args[i]).append(", ");
        }
        bd.append(args[len - 1]).append(")");

        return bd.toString();
    }

    @Override
    public String getOutputStatement(String toDisplay) {
        int len = toDisplay.length();
        StringBuilder bd = new StringBuilder(len + 8);
        bd.append("print(\"");
        for (int i = 0; i < len; i++) {
            char ch = toDisplay.charAt(i);
            switch (ch) {
                case '"':
                case '\\':
                    bd.append('\\');
                default:
                    bd.append(ch); break;
            }
        }
        bd.append("\")");
        return bd.toString();
    }

    @Override
    public String getProgram(String... statements) {
        StringBuilder bd = new StringBuilder();
        for (String s : statements)
            bd.append(s).append('\n');

        return bd.toString();
    }

    @Override
    public ScriptEngine getScriptEngine() {
        return new ScriptEngineImpl(this);
    }

    private static List<String> immutableList(String... args) {
        if (args.length == 0)
            return Collections.emptyList();
        else if (args.length == 1)
            return Collections.singletonList(args[0]);
        else
            return Collections.unmodifiableList(Arrays.asList(args));
    }
}
