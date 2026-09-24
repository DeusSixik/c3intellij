package org.c3lang.intellij.completion;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public class C3CompletionTest extends BasePlatformTestCase
{
	public void testCompletionAfterScope()
	{
		myFixture.configureByText("io.c3", """
			module std::io;

			fn void printn(char[] s) {}
			fn void printf(char[] fmt) {}
			""");

		myFixture.configureByText("main.c3", """
			module main;
			import std::io;

			fn void main()
			{
				io::prin<caret>
			}
			""");

		myFixture.completeBasic();
		List<String> lookupStrings = myFixture.getLookupElementStrings();
		assertNotNull("Lookup strings should not be null", lookupStrings);
		assertTrue("Should suggest printn", lookupStrings.contains("printn"));
	}

	public void testMethodAndFieldCompletionOnInstance()
	{
		myFixture.configureByText("main.c3", """
			module main;

			struct MyTr
			{
				int a;
			}

			fn int MyTr.test()
			{
				return 0;
			}

			fn void caller(MyTr tr)
			{
				tr.<caret>
			}
			""");

		myFixture.completeBasic();
		List<String> lookupStrings = myFixture.getLookupElementStrings();
		assertNotNull("Lookup strings should not be null", lookupStrings);
		assertTrue("Should suggest field 'a', got: " + lookupStrings, lookupStrings.contains("a"));
		assertTrue("Should suggest method 'test', got: " + lookupStrings, lookupStrings.contains("test"));
	}

	public void testReturnCompletionInFunction()
	{
		myFixture.configureByText("main.c3", """
			module main;

			struct MyTr
			{
				int a;
			}

			fn int MyTr.test()
			{
				ret<caret>
			}
			""");

		myFixture.completeBasic();
		List<String> lookupStrings = myFixture.getLookupElementStrings();
		if (lookupStrings != null)
		{
			assertTrue("Should suggest 'return', got: " + lookupStrings, lookupStrings.contains("return"));
		}
		else
		{
			assertTrue("Should auto-insert 'return'", myFixture.getEditor().getDocument().getText().contains("return"));
		}
	}

	public void testParameterAndVariableCompletion()
	{
		myFixture.configureByText("main.c3", """
			module main;

			struct EnumMap
			{
				int values;
			}

			fn void EnumMap.init(EnumMap* this, int init_value)
			{
				ini<caret>
			}
			""");

		myFixture.completeBasic();
		List<String> lookupStrings = myFixture.getLookupElementStrings();
		if (lookupStrings != null)
		{
			assertTrue("Should suggest parameter 'init_value', got: " + lookupStrings, lookupStrings.contains("init_value"));
		}
		else
		{
			assertTrue("Should auto-insert 'init_value'", myFixture.getEditor().getDocument().getText().contains("init_value"));
		}
	}

	public void testThisCompletion()
	{
		myFixture.configureByText("main.c3", """
			module main;

			struct EnumMap
			{
				int values;
			}

			fn void EnumMap.init(EnumMap* this, int init_value)
			{
				th<caret>
			}
			""");

		myFixture.completeBasic();
		List<String> lookupStrings = myFixture.getLookupElementStrings();
		if (lookupStrings != null)
		{
			assertTrue("Should suggest 'this', got: " + lookupStrings, lookupStrings.contains("this"));
		}
		else
		{
			assertTrue("Should auto-insert 'this'", myFixture.getEditor().getDocument().getText().contains("this"));
		}
	}

	public void testForeachVariableCompletion()
	{
		myFixture.configureByText("main.c3", """
			module main;

			struct EnumMap
			{
				int[] values;
			}

			fn void EnumMap.init(EnumMap* this, int init_value)
			{
				foreach(&a : this.values)
				{
					<caret>
				}
			}
			""");

		myFixture.completeBasic();
		List<String> lookupStrings = myFixture.getLookupElementStrings();
		assertNotNull("Lookup strings should not be null", lookupStrings);
		assertTrue("Should suggest foreach variable 'a', got: " + lookupStrings, lookupStrings.contains("a"));
	}
}
