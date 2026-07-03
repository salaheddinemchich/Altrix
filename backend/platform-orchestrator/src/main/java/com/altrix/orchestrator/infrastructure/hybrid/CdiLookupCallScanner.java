package com.altrix.orchestrator.infrastructure.hybrid;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Single shared definition of "what counts as a {@code CdiLookup.get(...)}
 * call" — used by both {@link CdiStatelessConverter} (auto-fix: resolves
 * literal {@code X.class} targets so it can convert the reached class to
 * {@code @Stateless}) and {@link SpringKafkaTxGapDetector} (detect-only:
 * flags every call the converter's static resolution structurally can't
 * handle). Extracted so the two can never silently drift apart on the
 * definition of a CdiLookup call — previously each had its own
 * near-identical {@code MethodCallExpr} traversal.
 */
@Component
public class CdiLookupCallScanner {

    /** Every {@code CdiLookup.get(...)} call site in an already-parsed compilation unit. */
    public List<CdiLookupCall> scan(CompilationUnit cu) {
        List<CdiLookupCall> calls = new ArrayList<>();
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            if (!"get".equals(call.getNameAsString())) continue;
            if (call.getScope().isEmpty() || !"CdiLookup".equals(call.getScope().get().toString())) continue;
            if (call.getArguments().isEmpty()) continue;

            Expression arg = call.getArguments().get(0);
            int line = call.getBegin().map(p -> p.line).orElse(-1);
            String literalTarget = arg instanceof ClassExpr classExpr
                    ? simpleName(classExpr.getTypeAsString())
                    : null;
            calls.add(new CdiLookupCall(line, arg, literalTarget));
        }
        return calls;
    }

    private static String simpleName(String fqnOrSimple) {
        int lastDot = fqnOrSimple.lastIndexOf('.');
        return lastDot < 0 ? fqnOrSimple : fqnOrSimple.substring(lastDot + 1);
    }

    /**
     * One {@code CdiLookup.get(...)} call site.
     *
     * @param line                    source line, or -1 when unavailable.
     * @param argument                the raw argument expression — a
     *                                {@code ClassExpr} for a literal
     *                                {@code X.class}, or anything else for
     *                                a dynamically-computed target.
     * @param literalTargetSimpleName the simple class name when {@code
     *                                argument} is a literal {@code X.class},
     *                                else {@code null}.
     */
    public record CdiLookupCall(int line, Expression argument, String literalTargetSimpleName) {
    }
}
