package org.c3lang.intellij.completion;

import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.patterns.ElementPattern;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.util.ProcessingContext;
import kotlin.Pair;
import org.c3lang.intellij.C3Icons;
import org.c3lang.intellij.index.StructService;
import org.c3lang.intellij.psi.AccessPath;
import org.c3lang.intellij.psi.C3AccessIdent;
import org.c3lang.intellij.psi.C3CallExpr;
import org.c3lang.intellij.psi.C3CallExprTail;
import org.c3lang.intellij.psi.C3ExprStmt;
import org.c3lang.intellij.psi.C3Types;
import org.c3lang.intellij.psi.FullyQualifiedName;
import org.jetbrains.annotations.NotNull;

import com.intellij.codeInsight.completion.PrioritizedLookupElement;
import org.c3lang.intellij.index.NameIndexService;
import org.c3lang.intellij.psi.C3CallablePsiElement;
import org.c3lang.intellij.psi.ParamType;
import org.c3lang.intellij.psi.ShortType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static com.intellij.patterns.PlatformPatterns.psiElement;
import static com.intellij.patterns.StandardPatterns.or;

public final class TailExprCompletionContributor extends CompletionProvider<CompletionParameters>
{
	public static final TailExprCompletionContributor INSTANCE = new TailExprCompletionContributor();

	private static final ElementPattern<PsiElement> PATTERN = or(
		psiElement(C3Types.IDENT).inside(C3AccessIdent.class),
		psiElement(PsiWhiteSpace.class).inside(C3CallExprTail.class)
	);

	private TailExprCompletionContributor() {}

	@Override
	protected void addCompletions(
		@NotNull CompletionParameters parameters,
		@NotNull ProcessingContext context,
		@NotNull CompletionResultSet result)
	{
		if (!PATTERN.accepts(parameters.getPosition()) && !PATTERN.accepts(parameters.getOriginalPosition()))
		{
			return;
		}

		PsiElement lookupTarget = CompletionExtensionsKt.siblingOf(parameters, C3CallExpr.class);
		if (lookupTarget == null)
		{
			lookupTarget = CompletionExtensionsKt.siblingOf(parameters, C3ExprStmt.class);
		}
		if (lookupTarget == null) return;

		String lookupString = CompletionExtensionsKt.getLookupString(parameters, lookupTarget);
		FullyQualifiedName rootType = CompletionExtensionsKt.getRootType(lookupTarget);
		if (rootType == null) return;

		int lastDot = lookupString.lastIndexOf('.');
		String prefix = lastDot >= 0 ? lookupString.substring(lastDot + 1) : "";
		CompletionResultSet scopedResult = result.withPrefixMatcher(prefix);

		List<String> idents = List.of(lookupString.substring(lookupString.indexOf('.') + 1).split("\\."));
		List<Pair<AccessPath, String>> fields =
			StructService.INSTANCE.getFields(rootType, idents, parameters.getPosition().getProject());

		for (Pair<AccessPath, String> field : fields)
		{
			AccessPath accessPath = field.getFirst();
			if (accessPath.getSegments().size() != 1) continue;

			scopedResult.addElement(
				LookupElementBuilder.create(accessPath.getName())
					.withPresentableText(accessPath.getName())
					.withIcon(C3Icons.Nodes.STRUCT_FIELD)
					.withTypeText(field.getSecond())
			);
		}

		Collection<C3CallablePsiElement> methods =
			NameIndexService.INSTANCE.findMethodsForType(rootType, null, parameters.getPosition().getProject());
		for (C3CallablePsiElement method : methods)
		{
			String fullMethodName = method.getFqName().getName();
			int dotIndex = fullMethodName.lastIndexOf('.');
			String methodName = dotIndex >= 0 ? fullMethodName.substring(dotIndex + 1) : fullMethodName;

			List<String> params = new ArrayList<>();
			List<ParamType> paramTypes = method.getParameterTypes();
			int startIdx = 0;
			if (!paramTypes.isEmpty())
			{
				ParamType first = paramTypes.get(0);
				ShortType ft = first.getType();
				if (ft != null && (ft.getValue().equals(rootType.getName()) || ft.getValue().equals(rootType.getName() + "*")))
				{
					startIdx = 1;
				}
			}
			for (int i = startIdx; i < paramTypes.size(); i++)
			{
				ParamType p = paramTypes.get(i);
				ShortType t = p.getType();
				params.add((t != null ? t.getFullName() + " " : "") + p.getName());
			}
			String paramListStr = String.join(", ", params);
			boolean noParams = params.isEmpty();

			LookupElementBuilder builder = LookupElementBuilder.create(method, methodName)
				.withPresentableText(methodName)
				.withIcon(C3Icons.Nodes.FUNCTION)
				.appendTailText("(" + paramListStr + ")", false)
				.withTypeText(method.getReturnType() != null ? method.getReturnType().getFullName() : "void")
				.withInsertHandler((insertionContext, item) -> {
					int caretOffset = insertionContext.getEditor().getCaretModel().getOffset();
					CharSequence chars = insertionContext.getDocument().getCharsSequence();
					if (caretOffset < chars.length() && chars.charAt(caretOffset) == '(')
					{
						insertionContext.getEditor().getCaretModel().moveToOffset(caretOffset + 1);
					}
					else
					{
						insertionContext.getDocument().insertString(caretOffset, "()");
						insertionContext.getEditor().getCaretModel().moveToOffset(noParams ? caretOffset + 2 : caretOffset + 1);
					}
				});

			scopedResult.addElement(PrioritizedLookupElement.withPriority(builder, 1.0));
		}
	}
}
