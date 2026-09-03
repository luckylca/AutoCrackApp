package com.luckylca.runtimeinspector.testapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import org.junit.Test;

public final class HookTargetsTest {
    @Test
    public void exposesStableMethodAndFieldFixtures() throws Exception {
        HookTargets target = new HookTargets("test");
        assertEquals(42, target.getInt());
        assertTrue(target.getBoolean());
        assertEquals("runtime-test", target.getString());
        assertEquals(5, target.add(2, 3));
        assertEquals("int:3", target.overload(3));
        assertEquals("string:x", target.overload("x"));
        assertEquals(7, HookTargets.staticField);
        assertEquals(11, target.instanceField);
        assertThrows(IllegalStateException.class, target::exceptionMethod);
    }

    @Test
    public void overloadsHaveDistinctExactSignatures() throws Exception {
        Method intMethod = HookTargets.class.getDeclaredMethod("overload", int.class);
        Method stringMethod = HookTargets.class.getDeclaredMethod("overload", String.class);
        assertEquals(int.class, intMethod.getParameterTypes()[0]);
        assertEquals(String.class, stringMethod.getParameterTypes()[0]);
    }
}
