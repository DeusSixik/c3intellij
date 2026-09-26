package org.c3lang.intellij.types;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class CallCheckTest extends BasePlatformTestCase
{
    public void testDuplicateMethodIsError()
    {
        myFixture.configureByText("main.c3", """
            module test;
            struct Test
            {
                int f;
            }
            fn void Test.test(&self)
            {
            }
            fn void Test.test(Test* self)
            {
            }
            """);

        List<HighlightInfo> errors = errorsWithText(myFixture.doHighlighting(), "already defined for 'Test'");
        assertEquals("Expected one duplicate error, got: " + errors, 1, errors.size());
        assertTrue("Error should point at the previous definition, got: " + errors.get(0).getDescription(),
            errors.get(0).getDescription() != null
                && errors.get(0).getDescription().contains("previous definition"));
    }

    public void testDuplicateFreeFunctionIsError()
    {
        myFixture.configureByText("main.c3", """
            module test;
            fn void foo()
            {
            }
            fn void foo()
            {
            }
            """);

        List<HighlightInfo> errors = errorsWithText(myFixture.doHighlighting(), "already defined");
        assertEquals("Expected one duplicate error, got: " + errors, 1, errors.size());
    }

    public void testDistinctMethodsOk()
    {
        myFixture.configureByText("main.c3", """
            module test;
            struct Test
            {
                int f;
            }
            fn void Test.one(Test* self)
            {
            }
            fn void Test.two(Test* self)
            {
            }
            fn void other()
            {
            }
            """);

        List<HighlightInfo> highlights = myFixture.doHighlighting();
        assertTrue("Unexpected duplicate error, got: " + highlights,
            errorsWithText(highlights, "already defined").isEmpty());
    }

    public void testUnknownNamedArgIsError()
    {
        myFixture.configureByText("main.c3", """
            module test;
            fn void test_named(int times, double data)
            {
            }
            fn void test()
            {
                test_named(times: 1, data: 3.0, bogus: 2);
            }
            """);

        List<HighlightInfo> errors = errorsWithText(myFixture.doHighlighting(), "Unknown parameter 'bogus'.");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
    }

    public void testNamedOverwriteIsError()
    {
        myFixture.configureByText("main.c3", """
            module test;
            fn void named_default(int times = 1, double data = 3.0)
            {
            }
            fn void test()
            {
                named_default(2, times: 3);
            }
            """);

        List<HighlightInfo> errors = errorsWithText(myFixture.doHighlighting(), "already set");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
    }

    public void testPositionalAfterNamedIsError()
    {
        myFixture.configureByText("main.c3", """
            module test;
            fn void named_default(int times = 1, double data = 3.0)
            {
            }
            fn void test()
            {
                named_default(times: 3, 4.0);
            }
            """);

        List<HighlightInfo> errors = errorsWithText(myFixture.doHighlighting(), "may not follow named arguments");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
    }

    public void testTooManyArgsIsError()
    {
        myFixture.configureByText("main.c3", """
            module test;
            fn void fixed(int a)
            {
            }
            fn void test()
            {
                fixed(1, 2);
            }
            """);

        List<HighlightInfo> errors = errorsWithText(myFixture.doHighlighting(), "Too many arguments.");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
    }

    public void testMissingArgIsError()
    {
        myFixture.configureByText("main.c3", """
            module test;
            fn void needs_two(int a, int b)
            {
            }
            fn void test()
            {
                needs_two(1);
            }
            """);

        List<HighlightInfo> errors = errorsWithText(myFixture.doHighlighting(), "Missing argument for parameter 'b'.");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
    }

    public void testVaargForwardWithNamedOk()
    {
        myFixture.configureByText("main.c3", """
            module test;
            struct Map { int x; }
            macro Map* Map.init_with_key_values(&self, int allocator, ..., uint capacity = 4, float load_factor = 0.5)
            {
                return self;
            }
            macro Map* Map.tinit(&self, ..., uint capacity = 4, float load_factor = 0.5)
            {
                return self.init_with_key_values(1, $vasplat, capacity: capacity, load_factor: load_factor);
            }
            """);

        List<HighlightInfo> highlights = myFixture.doHighlighting();
        assertTrue("Unexpected call error, got: " + highlights,
            errorsWithText(highlights, "already set").isEmpty());
    }

    public void testGenericTypeParamArgToVoidStarOk()
    {
        // `Key` is a module generic parameter: unknowable before
        // instantiation, so passing it to `void*` must stay silent
        // (mirrors hashmap.c3 `allocator::free(map.allocator, entry.key)`).
        myFixture.configureByText("main.c3", """
            module test::map <Key, Value>;
            struct Entry
            {
                Key key;
                Entry* next;
            }
            fn void free_it(void* ptr)
            {
            }
            fn void free_entry(Entry* entry)
            {
                free_it(entry.key);
            }
            """);

        List<HighlightInfo> highlights = myFixture.doHighlighting();
        assertTrue("Unexpected call errors, got: " + highlights, callErrors(highlights).isEmpty());
    }

    public void testTypedefContractArgToInterfaceOk()
    {
        // Mirrors dstring.c3: `typedef DString (OutStream) = ...` carries the
        // contract on the typedef, so `&report` converts to `OutStream`.
        myFixture.configureByText("main.c3", """
            module test;
            interface OutStream
            {
            }
            typedef DString (OutStream) = void*;
            fn void take(OutStream out)
            {
            }
            fn void foo()
            {
                DString report;
                take(&report);
            }
            """);

        List<HighlightInfo>         highlights = myFixture.doHighlighting();
        assertTrue("Unexpected call errors, got: " + highlights, callErrors(highlights).isEmpty());
    }

    public void testTypedefWithoutContractToInterfaceIsError()
    {
        myFixture.configureByText("main.c3", """
            module test;
            interface OutStream
            {
            }
            typedef Plain = void*;
            fn void take(OutStream out)
            {
            }
            fn void foo()
            {
                Plain p;
                take(&p);
            }
            """);
        List<HighlightInfo> errors = errorsWithText(myFixture.doHighlighting(),
            "Cannot pass 'Plain*' for parameter 'out' of type 'OutStream'");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
    }

    public void testArgTypeMismatchIsError()
    {
        myFixture.configureByText("main.c3", """
            module test;
            fn void takes_uint(uint x)
            {
            }
            fn void test()
            {
                takes_uint("s");
            }
            """);

        List<HighlightInfo> errors = errorsWithText(myFixture.doHighlighting(), "Cannot pass 'String' for parameter 'x' of type 'uint'.");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
    }

    public void testCorrectCallsOk()
    {
        myFixture.configureByText("main.c3", """
            module test;
            fn void test_named(int times, double data)
            {
            }
            fn int test_with_default(int foo = 1)
            {
                return foo;
            }
            fn void test()
            {
                test_named(times: 1, data: 3.0);
                test_named(15, data: 3.14);
                test_with_default();
                test_with_default(100);
            }
            """);

        List<HighlightInfo> highlights = myFixture.doHighlighting();
        assertTrue("Unexpected call errors, got: " + highlights, callErrors(highlights).isEmpty());
    }

    public void testMethodCallReceiverSkipped()
    {
        myFixture.configureByText("main.c3", """
            module test;
            struct Point
            {
                int x;
                int y;
            }
            fn void Point.add(Point* p, int x)
            {
                p.x += x;
            }
            fn void example()
            {
                Point p = { 1, 2 };
                p.add(10);
                Point.add(&p, 10);
            }
            """);

        List<HighlightInfo> highlights = myFixture.doHighlighting();
        assertTrue("Unexpected call errors, got: " + highlights, callErrors(highlights).isEmpty());
    }

    public void testVaargCallOk()
    {
        myFixture.configureByText("main.c3", """
            module test;
            fn void va_singletyped(int... args)
            {
            }
            fn void test()
            {
                va_singletyped(1, 2, 3);
            }
            """);

        List<HighlightInfo> highlights = myFixture.doHighlighting();
        assertTrue("Unexpected call errors, got: " + highlights, callErrors(highlights).isEmpty());
    }

    public void testUndeclaredParamTypeSkipsTypeCheck()
    {
        myFixture.configureByText("main.c3", """
            module test;
            fn void generic_like(Nope x)
            {
            }
            fn void test()
            {
                generic_like(1);
            }
            """);

        List<HighlightInfo> highlights = myFixture.doHighlighting();
        assertTrue("Unexpected call errors, got: " + highlights, callErrors(highlights).isEmpty());
    }

    public void testPrintfLikeVaargCallOk()
    {
        myFixture.configureByText("main.c3", """
            module test;
            struct Test
            {
                int b;
                int f;
            }
            fn usz? printfn(String format, args...);
            fn void print_person(Test* p)
            {
                printfn("%s is %d years old.", p.b, p.f);
            }
            """);

        List<HighlightInfo> highlights = myFixture.doHighlighting();
        assertTrue("Unexpected call errors, got: " + highlights, callErrors(highlights).isEmpty());
    }

    public void testExplicitAnyVaargCallOk()
    {
        myFixture.configureByText("main.c3", """
            module test;
            fn void va_explicit(any... args)
            {
            }
            fn void test()
            {
                va_explicit(1, "s");
            }
            """);

        List<HighlightInfo> highlights = myFixture.doHighlighting();
        assertTrue("Unexpected call errors, got: " + highlights, callErrors(highlights).isEmpty());
    }

    private static @NotNull List<HighlightInfo> callErrors(@NotNull List<HighlightInfo> highlights)
    {
        List<HighlightInfo> result = new ArrayList<>();
        for (HighlightInfo info : highlights)
        {
            if (info.getSeverity() != HighlightSeverity.ERROR || info.getDescription() == null) continue;
            String description = info.getDescription();
            if (description.contains("Unknown parameter")
                || description.contains("already set")
                || description.contains("may not follow named arguments")
                || description.contains("Too many arguments")
                || description.contains("Missing argument")
                || description.contains("Cannot pass"))
            {
                result.add(info);
            }
        }
        return result;
    }

    private static @NotNull List<HighlightInfo> errorsWithText(@NotNull List<HighlightInfo> highlights, @NotNull String textPart)
    {
        List<HighlightInfo> result = new ArrayList<>();
        for (HighlightInfo info : highlights)
        {
            if (info.getSeverity() == HighlightSeverity.ERROR
                && info.getDescription() != null
                && info.getDescription().contains(textPart))
            {
                result.add(info);
            }
        }
        return result;
    }
}
