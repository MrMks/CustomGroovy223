import com.github.mrmks.gvy223.ScriptEngineFactoryImpl;
import com.github.mrmks.gvy223.ScriptEngineImpl;
import groovy.transform.CompileStatic;
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.script.ScriptContext;

public class TestScriptEngine {

    @Test
    public void testEngine() {
        ScriptEngineImpl engine = (ScriptEngineImpl) new ScriptEngineFactoryImpl().getScriptEngine();

        Assertions.assertDoesNotThrow(() -> engine.eval("def foo() { 'foo' }"));

        engine.getContext().setAttribute(
                "#gvy223.groovy.compile.customizer", new ASTTransformationCustomizer(CompileStatic.class), ScriptContext.ENGINE_SCOPE
        );

        Assertions.assertDoesNotThrow(() -> engine.eval("def bar() { 'bar' }"));

        Assertions.assertEquals(
                "foo",
                Assertions.assertDoesNotThrow(() -> engine.invokeFunction("foo"))
        );

        Assertions.assertEquals(
                "bar",
                Assertions.assertDoesNotThrow(() -> engine.invokeFunction("bar"))
        );

        Assertions.assertDoesNotThrow(() -> engine.eval("@groovy.transform.CompileDynamic\ndef fee() { foo() }"));
        Assertions.assertEquals(
                "foo",
                Assertions.assertDoesNotThrow(() -> engine.invokeFunction("fee"))
        );
    }

}
