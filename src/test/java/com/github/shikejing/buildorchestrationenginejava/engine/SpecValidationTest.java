package com.github.shikejing.buildorchestrationenginejava.engine;

import com.github.shikejing.buildorchestrationenginejava.engine.model.BuildSpec;
import com.github.shikejing.buildorchestrationenginejava.engine.spec.BuildSpecParser;
import com.github.shikejing.buildorchestrationenginejava.engine.spec.SpecException;
import com.github.shikejing.buildorchestrationenginejava.engine.spec.SpecValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecValidationTest {

    private final BuildSpecParser parser = new BuildSpecParser();
    private final SpecValidator validator = new SpecValidator();

    private SpecException expectReject(String json) {
        BuildSpec spec = parser.parse(json);
        return assertThrows(SpecException.class, () -> validator.validate(spec));
    }

    @Test
    void rejectsCircularDependencyAndLocatesTargets() {
        SpecException e = expectReject("""
                {"targets":[
                  {"name":"a","deps":["c"],"outputs":["a.out"],"action":{"kind":"WRITE"}},
                  {"name":"b","deps":["a"],"outputs":["b.out"],"action":{"kind":"WRITE"}},
                  {"name":"c","deps":["b"],"outputs":["c.out"],"action":{"kind":"WRITE"}}
                ]}""");
        assertEquals(1, e.getProblems().size());
        String msg = e.getProblems().get(0);
        assertTrue(msg.contains("circular dependency"), msg);
        assertTrue(msg.contains("a") && msg.contains("b") && msg.contains("c"), msg);
    }

    @Test
    void rejectsSelfDependency() {
        SpecException e = expectReject("""
                {"targets":[
                  {"name":"a","deps":["a"],"outputs":["a.out"],"action":{"kind":"WRITE"}}
                ]}""");
        assertTrue(e.getProblems().get(0).contains("a -> a"), e.getProblems().get(0));
    }

    @Test
    void rejectsUnknownDependencyAndLocatesReferencingTarget() {
        SpecException e = expectReject("""
                {"targets":[
                  {"name":"a","deps":["ghost"],"outputs":["a.out"],"action":{"kind":"WRITE"}}
                ]}""");
        assertTrue(e.getMessage().contains("'a'"), e.getMessage());
        assertTrue(e.getMessage().contains("ghost"), e.getMessage());
    }

    @Test
    void rejectsDuplicateTargetDefinition() {
        SpecException e = expectReject("""
                {"targets":[
                  {"name":"a","outputs":["a.out"],"action":{"kind":"WRITE"}},
                  {"name":"a","outputs":["b.out"],"action":{"kind":"WRITE"}}
                ]}""");
        assertTrue(e.getMessage().contains("duplicate definition"), e.getMessage());
    }

    @Test
    void rejectsDuplicateOutputOwnership() {
        SpecException e = expectReject("""
                {"targets":[
                  {"name":"a","outputs":["shared.out"],"action":{"kind":"WRITE"}},
                  {"name":"b","outputs":["shared.out"],"action":{"kind":"WRITE"}}
                ]}""");
        assertTrue(e.getMessage().contains("shared.out"), e.getMessage());
        assertTrue(e.getMessage().contains("'b'"), e.getMessage());
    }

    @Test
    void rejectsUnknownToolchain() {
        SpecException e = expectReject("""
                {"toolchains":{"jdk":"17"},
                 "targets":[
                  {"name":"a","toolchain":"gcc","outputs":["a.out"],"action":{"kind":"WRITE"}}
                ]}""");
        assertTrue(e.getMessage().contains("unknown toolchain 'gcc'"), e.getMessage());
    }

    @Test
    void collectsMultipleProblemsAndRejectsAsWhole() {
        SpecException e = expectReject("""
                {"targets":[
                  {"name":"a","deps":["ghost"],"outputs":["a.out"],"action":{"kind":"WRITE"}},
                  {"name":"a","outputs":["a.out"],"action":{"kind":"WRITE"}}
                ]}""");
        assertTrue(e.getProblems().size() >= 2, e.getProblems().toString());
    }

    @Test
    void acceptsValidSpec() {
        BuildSpec spec = TestSupport.parseAndValidate("""
                {"toolchains":{"jdk":"17"},
                 "targets":[
                  {"name":"a","outputs":["a.out"],"action":{"kind":"WRITE","params":{"content":"x"}}},
                  {"name":"b","toolchain":"jdk","deps":["a"],"outputs":["b.out"],
                   "action":{"kind":"WRITE","params":{"content":"y"}}}
                ]}""");
        assertEquals(2, spec.getTargets().size());
    }
}
